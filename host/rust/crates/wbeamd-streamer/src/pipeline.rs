//! GStreamer pipeline construction.
//!
//! Builds the PipeWire → videoconvert → videoscale → (optional videorate) →
//! encoder → h264parse/h265parse → appsink pipeline, with an optional debug JPEG
//! branch when `debug_fps > 0`.

use std::os::unix::io::AsRawFd;
use std::sync::{
    atomic::{AtomicU64, Ordering},
    Arc,
};

use anyhow::{Context, Result};
use gstreamer as gst;
use gstreamer::prelude::*;
use gstreamer_app as gst_app;

use crate::capture::PortalStream;
use crate::cli::{ResolvedConfig, StreamMode};
use crate::encoder::{configure_encoder, is_hevc, is_png, pick_encoder};

struct BufferProfile {
    queue_buffers: u32,
    appsink_buffers: u32,
    queue_leaky: &'static str,
    appsink_drop: bool,
    appsink_sync: bool,
    use_videorate: bool,
    queue_time_ns: u64,
}

fn buffer_profile(mode: StreamMode, fps: u32, mode_png: bool) -> BufferProfile {
    let frame_ns = (1_000_000_000u64 / fps.max(1) as u64).max(1);
    match mode {
        StreamMode::Ultra => {
            let queue_buffers = if mode_png { 4 } else { 3 };
            let appsink_buffers = if mode_png { 2 } else { 1 };
            BufferProfile {
                queue_buffers,
                appsink_buffers,
                queue_leaky: "downstream",
                appsink_drop: true,
                appsink_sync: false,
                use_videorate: !mode_png,
                queue_time_ns: frame_ns.saturating_mul(queue_buffers as u64),
            }
        }
        StreamMode::Stable => {
            let queue_buffers = if mode_png { 12 } else { 10 };
            let appsink_buffers = if mode_png { 10 } else { 8 };
            BufferProfile {
                queue_buffers,
                appsink_buffers,
                queue_leaky: "no",
                appsink_drop: false,
                appsink_sync: false,
                use_videorate: false,
                queue_time_ns: frame_ns.saturating_mul(queue_buffers as u64),
            }
        }
        StreamMode::Quality => {
            let queue_buffers = if mode_png { 20 } else { 24 };
            let appsink_buffers = if mode_png { 16 } else { 20 };
            BufferProfile {
                queue_buffers,
                appsink_buffers,
                queue_leaky: "no",
                appsink_drop: false,
                appsink_sync: true,
                use_videorate: false,
                queue_time_ns: frame_ns.saturating_mul(queue_buffers as u64),
            }
        }
    }
}

/// Build the full capture → encode → appsink pipeline.
///
/// Returns the `(Pipeline, AppSink, fps_counter)` triple.  The `fps_counter`
/// is incremented by a pad probe on the appsink's sink pad on every buffer.
pub fn make_pipeline(
    stream: &PortalStream,
    cfg: &ResolvedConfig,
    _port: u16,
    debug_dir: &str,
    debug_fps: u32,
    framed: bool,
) -> Result<(gst::Pipeline, gst_app::AppSink, Arc<AtomicU64>)> {
    let pipeline = gst::Pipeline::with_name("wbeam-wayland-pipeline");
    let mode_png = is_png(&cfg.encoder);
    let encoder_name = pick_encoder(&cfg.encoder)?;
    let hevc = is_hevc(&cfg.encoder);
    let profile = buffer_profile(cfg.stream_mode, cfg.fps, mode_png);

    // ── Source ───────────────────────────────────────────────────────────────
    let src = gst::ElementFactory::make("pipewiresrc")
        .name("src")
        .build()
        .context("pipewiresrc missing")?;
    src.set_property("fd", stream.fd.as_raw_fd());
    src.set_property("path", stream.node_id.to_string());
    src.set_property("do-timestamp", true);
    src.set_property("keepalive-time", 1000i32);

    // ── Transform chain ──────────────────────────────────────────────────────
    let debug_enabled = debug_fps > 0;
    let q1 = gst::ElementFactory::make("queue").name("q1").build()?;
    let caps_src = gst::ElementFactory::make("capsfilter")
        .name("caps_src")
        .build()?;
    let convert = gst::ElementFactory::make("videoconvert")
        .name("conv")
        .build()?;
    let scale = if cfg.skip_videoscale {
        gst::ElementFactory::make("identity")
            .name("scale_passthrough")
            .build()?
    } else {
        let s = gst::ElementFactory::make("videoscale")
            .name("scale")
            .build()?;
        let _ = s.set_property_from_str("method", "lanczos");
        s
    };
    let use_videorate = profile.use_videorate;
    let rate = if use_videorate {
        Some(
            gst::ElementFactory::make("videorate")
                .name("rate")
                .build()?,
        )
    } else {
        None
    };
    let caps1 = gst::ElementFactory::make("capsfilter")
        .name("caps1")
        .build()?;
    let tee = if debug_enabled {
        Some(gst::ElementFactory::make("tee").name("tee").build()?)
    } else {
        None
    };
    let qmain = if debug_enabled {
        Some(gst::ElementFactory::make("queue").name("qmain").build()?)
    } else {
        None
    };

    // ── Encoder ──────────────────────────────────────────────────────────────
    // pick_encoder now returns &'static str — no heap allocation.
    let queue_buffer_frames = profile.queue_buffers;
    let buffer_time_ns = profile.queue_time_ns;
    let enc_element = match encoder_name {
        "nvenc264" => "nvh264enc",
        "x264" => "x264enc",
        "nvenc265" => "nvh265enc",
        "x265" => "x265enc",
        "rawpng" => "pngenc",
        _ => "x265enc",
    };
    let enc = gst::ElementFactory::make(enc_element)
        .name("enc")
        .build()
        .with_context(|| format!("{enc_element} not available"))?;
    let parse = if mode_png {
        None
    } else {
        Some(
            gst::ElementFactory::make(if hevc { "h265parse" } else { "h264parse" })
                .name("parse")
                .build()?,
        )
    };
    let sink = gst::ElementFactory::make("appsink").name("sink").build()?;

    // configure drop queues
    let configure_queue = |q: &gst::Element| {
        let _ = q.set_property("max-size-buffers", queue_buffer_frames);
        let _ = q.set_property("max-size-bytes", 0u32);
        let _ = q.set_property("flush-on-eos", true);
        let _ = q.set_property("silent", true);
        let _ = q.set_property("max-size-time", buffer_time_ns);
        let _ = q.set_property_from_str("leaky", profile.queue_leaky);
    };
    configure_queue(&q1);
    if let Some(qmain) = &qmain {
        configure_queue(qmain);
    }

    let caps_source_hint = gst::Caps::builder("video/x-raw").build();
    let _ = caps_src.set_property("caps", &caps_source_hint);

    let mut raw_caps_builder = gst::Caps::builder("video/x-raw");
    let raw_format = if mode_png {
        "RGBA"
    } else if encoder_name == "nvenc264" || encoder_name == "nvenc265" {
        "NV12"
    } else {
        "I420"
    };
    raw_caps_builder = raw_caps_builder
        .field("format", raw_format)
        .field("width", cfg.width as i32)
        .field("height", cfg.height as i32);
    if use_videorate {
        raw_caps_builder =
            raw_caps_builder.field("framerate", gst::Fraction::new(cfg.fps as i32, 1));
    }
    let caps_raw = raw_caps_builder.build();
    let _ = caps1.set_property("caps", &caps_raw);

    if !mode_png {
        configure_encoder(
            &enc,
            encoder_name,
            cfg.bitrate_kbps,
            cfg.fps,
            &cfg.nv_preset,
            cfg.intra_only,
        );
    }
    if let Some(rate) = &rate {
        // drop-only=true: videorate only drops frames to hit target fps; it never
        // duplicates a frame to pad up to rate.  Duplicated frames waste encoded
        // bits (CBR rate control still charges them) and cause micro-stutter on
        // the Android side.
        let _ = rate.set_property("drop-only", true);
        let _ = rate.set_property("max-rate", cfg.fps as i32);
        let _ = rate.set_property("average-period", 1_000_000_000u64 / cfg.fps as u64);
    }

    let caps_sink = if mode_png {
        gst::Caps::builder("image/png").build()
    } else {
        gst::Caps::builder(if hevc { "video/x-h265" } else { "video/x-h264" })
            .field("stream-format", "byte-stream")
            .field("alignment", if framed { "au" } else { "nal" })
            .build()
    };
    if let Some(parse) = &parse {
        parse.set_property("disable-passthrough", false);
        parse.set_property("config-interval", -1i32);
    }

    let appsink: gst_app::AppSink = sink
        .clone()
        .dynamic_cast::<gst_app::AppSink>()
        .map_err(|_| anyhow::anyhow!("sink is not an appsink"))?;
    appsink.set_caps(Some(&caps_sink));
    // Stream mode controls latency-vs-loss behavior.
    let _ = appsink.set_property("emit-signals", false);
    appsink.set_sync(profile.appsink_sync);
    appsink.set_wait_on_eos(false);
    appsink.set_max_buffers(profile.appsink_buffers);
    appsink.set_drop(profile.appsink_drop);

    // ── Link main path ───────────────────────────────────────────────────────
    if debug_enabled {
        let tee = tee.as_ref().context("tee missing while debug enabled")?;
        let qmain = qmain
            .as_ref()
            .context("qmain missing while debug enabled")?;

        if let Some(parse) = &parse {
            if let Some(rate) = &rate {
                pipeline.add_many([
                    &src, &q1, &caps_src, &convert, &scale, rate, &caps1, tee, qmain, &enc, parse,
                    &sink,
                ])?;
            } else {
                pipeline.add_many([
                    &src, &q1, &caps_src, &convert, &scale, &caps1, tee, qmain, &enc, parse, &sink,
                ])?;
            }
        } else {
            if let Some(rate) = &rate {
                pipeline.add_many([
                    &src, &q1, &caps_src, &convert, &scale, rate, &caps1, tee, qmain, &enc, &sink,
                ])?;
            } else {
                pipeline.add_many([
                    &src, &q1, &caps_src, &convert, &scale, &caps1, tee, qmain, &enc, &sink,
                ])?;
            }
        }
        if let Some(rate) = &rate {
            gst::Element::link_many([&src, &q1, &caps_src, &convert, &scale, rate, &caps1, tee])?;
        } else {
            gst::Element::link_many([&src, &q1, &caps_src, &convert, &scale, &caps1, tee])?;
        }

        let tee_pad_main = tee
            .request_pad_simple("src_%u")
            .context("tee src pad (main)")?;
        let qmain_sink = qmain.static_pad("sink").context("qmain sink pad")?;
        tee_pad_main.link(&qmain_sink)?;
        if let Some(parse) = &parse {
            gst::Element::link_many([qmain, &enc, parse, &sink])?;
        } else {
            gst::Element::link_many([qmain, &enc, &sink])?;
        }
    } else {
        if let Some(parse) = &parse {
            if let Some(rate) = &rate {
                pipeline.add_many([
                    &src, &q1, &caps_src, &convert, &scale, rate, &caps1, &enc, parse, &sink,
                ])?;
                gst::Element::link_many([
                    &src, &q1, &caps_src, &convert, &scale, rate, &caps1, &enc, parse, &sink,
                ])?;
            } else {
                pipeline.add_many([
                    &src, &q1, &caps_src, &convert, &scale, &caps1, &enc, parse, &sink,
                ])?;
                gst::Element::link_many([
                    &src, &q1, &caps_src, &convert, &scale, &caps1, &enc, parse, &sink,
                ])?;
            }
        } else {
            if let Some(rate) = &rate {
                pipeline.add_many([
                    &src, &q1, &caps_src, &convert, &scale, rate, &caps1, &enc, &sink,
                ])?;
                gst::Element::link_many([
                    &src, &q1, &caps_src, &convert, &scale, rate, &caps1, &enc, &sink,
                ])?;
            } else {
                pipeline.add_many([&src, &q1, &caps_src, &convert, &scale, &caps1, &enc, &sink])?;
                gst::Element::link_many([
                    &src, &q1, &caps_src, &convert, &scale, &caps1, &enc, &sink,
                ])?;
            }
        }
    }

    // ── Optional debug JPEG branch ───────────────────────────────────────────
    if debug_enabled {
        let tee = tee.as_ref().context("tee missing for debug branch")?;
        std::fs::create_dir_all(debug_dir).ok();
        let qdbg = gst::ElementFactory::make("queue").name("qdbg").build()?;
        let vrdbg = gst::ElementFactory::make("videorate")
            .name("vrdbg")
            .build()?;
        let capsdbg = gst::ElementFactory::make("capsfilter")
            .name("capsdbg")
            .build()?;
        let jpeg = gst::ElementFactory::make("jpegenc")
            .name("jpegdbg")
            .build()?;
        let multi = gst::ElementFactory::make("multifilesink")
            .name("filesdbg")
            .build()?;

        let _ = qdbg.set_property("max-size-buffers", 1u32);
        let _ = qdbg.set_property("max-size-bytes", 0u32);
        let _ = qdbg.set_property("max-size-time", 200_000_000u64);
        let _ = qdbg.set_property_from_str("leaky", "downstream");
        let _ = capsdbg.set_property(
            "caps",
            &gst::Caps::builder("video/x-raw")
                .field("framerate", gst::Fraction::new(debug_fps as i32, 1))
                .build(),
        );
        multi.set_property("location", format!("{debug_dir}/frame-%06d.jpg"));
        multi.set_property("sync", false);
        multi.set_property("async", false);
        multi.set_property("post-messages", false);
        multi.set_property("max-files", 300i32);
        let _ = jpeg.set_property("quality", 70i32);

        pipeline.add_many([&qdbg, &vrdbg, &capsdbg, &jpeg, &multi])?;
        let tee_pad_dbg = tee
            .request_pad_simple("src_%u")
            .context("tee src pad (debug)")?;
        let qdbg_sink = qdbg.static_pad("sink").context("qdbg sink pad")?;
        tee_pad_dbg.link(&qdbg_sink)?;
        gst::Element::link_many([&qdbg, &vrdbg, &capsdbg, &jpeg, &multi])?;
    }

    // ── FPS counter probe ────────────────────────────────────────────────────
    let fps_counter = Arc::new(AtomicU64::new(0));
    if let Some(pad) = sink.static_pad("sink") {
        let counter = fps_counter.clone();
        let _ = pad.add_probe(gst::PadProbeType::BUFFER, move |_, _| {
            counter.fetch_add(1, Ordering::Relaxed);
            gst::PadProbeReturn::Ok
        });
    }

    Ok((pipeline, appsink, fps_counter))
}
