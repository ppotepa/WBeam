//! GStreamer pipeline construction.
//!
//! Builds the PipeWire → videoconvert → videoscale → videorate →
//! encoder → h264parse/h265parse → appsink pipeline, with an optional debug JPEG
//! branch when `debug_fps > 0`.

use std::os::unix::io::AsRawFd;
use std::sync::{atomic::{AtomicU64, Ordering}, Arc};

use anyhow::{Context, Result};
use gstreamer as gst;
use gstreamer::prelude::*;
use gstreamer_app as gst_app;

use crate::capture::PortalStream;
use crate::cli::ResolvedConfig;
use crate::encoder::{configure_encoder, is_hevc, is_png, pick_encoder};

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
    let q1 = gst::ElementFactory::make("queue").name("q1").build()?;
    let convert = gst::ElementFactory::make("videoconvert").name("conv").build()?;
    let scale = gst::ElementFactory::make("videoscale").name("scale").build()?;
    let rate = gst::ElementFactory::make("videorate").name("rate").build()?;
    let caps1 = gst::ElementFactory::make("capsfilter").name("caps1").build()?;
    let tee = gst::ElementFactory::make("tee").name("tee").build()?;
    let qmain = gst::ElementFactory::make("queue").name("qmain").build()?;

    // ── Encoder ──────────────────────────────────────────────────────────────
    // pick_encoder now returns &'static str — no heap allocation.
    let encoder_name = pick_encoder(&cfg.encoder)?;
    let mode_png = is_png(&cfg.encoder);
    let hevc = is_hevc(&cfg.encoder);
    let low_latency_fresh = mode_png || hevc;
    let enc_element = match encoder_name {
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
    let caps2 = gst::ElementFactory::make("capsfilter").name("caps2").build()?;
    let sink = gst::ElementFactory::make("appsink").name("sink").build()?;

    // configure drop queues
    for q in [&q1, &qmain] {
        let _ = q.set_property("max-size-buffers", if low_latency_fresh { 1u32 } else { 2u32 });
        let _ = q.set_property("max-size-bytes", 0u32);
        let _ = q.set_property("max-size-time", if low_latency_fresh { 20_000_000u64 } else { 40_000_000u64 });
        let _ = q.set_property_from_str("leaky", "downstream");
    }

    let raw_format = if mode_png {
        "RGBA"
    } else if encoder_name == "nvenc265" {
        "NV12"
    } else {
        "I420"
    };
    let caps_raw = gst::Caps::builder("video/x-raw")
        .field("format", raw_format)
        .field("width", cfg.width as i32)
        .field("height", cfg.height as i32)
        .field("framerate", gst::Fraction::new(cfg.fps as i32, 1))
        .build();
    let _ = caps1.set_property("caps", &caps_raw);

    if !mode_png {
        configure_encoder(&enc, encoder_name, cfg.bitrate_kbps, cfg.fps, &cfg.nv_preset, cfg.intra_only);
    }
    // drop-only=true: videorate only drops frames to hit target fps; it never
    // duplicates a frame to pad up to rate.  Duplicated frames waste encoded
    // bits (CBR rate control still charges them) and cause micro-stutter on
    // the Android side.
    let _ = rate.set_property("drop-only", true);
    let _ = rate.set_property_from_str("max-rate", &cfg.fps.to_string());
    let _ = rate.set_property_from_str(
        "average-period",
        &(1_000_000_000u64 / cfg.fps as u64).to_string(),
    );

    let caps_sink = if mode_png {
        gst::Caps::builder("image/png").build()
    } else {
        gst::Caps::builder(if hevc { "video/x-h265" } else { "video/x-h264" })
            .field("stream-format", "byte-stream")
            .field("alignment", if framed { "au" } else { "nal" })
            .build()
    };
    let _ = caps2.set_property("caps", &caps_sink);

    if let Some(parse) = &parse {
        parse.set_property("disable-passthrough", true);
        parse.set_property("config-interval", -1i32);
    }

    let appsink: gst_app::AppSink = sink
        .clone()
        .dynamic_cast::<gst_app::AppSink>()
        .map_err(|_| anyhow::anyhow!("sink is not an appsink"))?;
    appsink.set_caps(Some(&caps_sink));
    // For raw PNG / H265 low-latency mode we prefer freshest-frame semantics
    // over clock-sync accuracy.
    appsink.set_sync(!low_latency_fresh);
    // 2-buffer appsink depth: 1 being encoded + 1 ready to pull.
    // Deeper queues add latency without benefit — the sender thread pulls
    // immediately and drops if the client is behind.
    appsink.set_max_buffers(if low_latency_fresh { 1 } else { 2 });
    appsink.set_drop(true);

    // ── Link main path ───────────────────────────────────────────────────────
    if let Some(parse) = &parse {
        pipeline.add_many([&src, &q1, &convert, &scale, &rate, &caps1, &tee, &qmain, &enc, parse, &caps2, &sink])?;
    } else {
        pipeline.add_many([&src, &q1, &convert, &scale, &rate, &caps1, &tee, &qmain, &enc, &caps2, &sink])?;
    }
    gst::Element::link_many([&src, &q1, &convert, &scale, &rate, &caps1, &tee])?;

    let tee_pad_main = tee.request_pad_simple("src_%u").context("tee src pad (main)")?;
    let qmain_sink = qmain.static_pad("sink").context("qmain sink pad")?;
    tee_pad_main.link(&qmain_sink)?;
    if let Some(parse) = &parse {
        gst::Element::link_many([&qmain, &enc, parse, &caps2, &sink])?;
    } else {
        gst::Element::link_many([&qmain, &enc, &caps2, &sink])?;
    }

    // ── Optional debug JPEG branch ───────────────────────────────────────────
    if debug_fps > 0 {
        std::fs::create_dir_all(debug_dir).ok();
        let qdbg = gst::ElementFactory::make("queue").name("qdbg").build()?;
        let vrdbg = gst::ElementFactory::make("videorate").name("vrdbg").build()?;
        let capsdbg = gst::ElementFactory::make("capsfilter").name("capsdbg").build()?;
        let jpeg = gst::ElementFactory::make("jpegenc").name("jpegdbg").build()?;
        let multi = gst::ElementFactory::make("multifilesink").name("filesdbg").build()?;

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
        multi.set_property("post-messages", false);
        multi.set_property("max-files", 300i32);

        pipeline.add_many([&qdbg, &vrdbg, &capsdbg, &jpeg, &multi])?;
        let tee_pad_dbg = tee.request_pad_simple("src_%u").context("tee src pad (debug)")?;
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
