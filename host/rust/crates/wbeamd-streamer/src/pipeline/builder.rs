//! GStreamer pipeline construction.
//!
//! Builds the PipeWire → videoconvert → videoscale → (optional videorate) →
//! encoder → h264parse/h265parse → appsink pipeline, with an optional debug JPEG
//! branch when `debug_fps > 0`.

use std::sync::{
    atomic::{AtomicU64, Ordering},
    Arc,
};

use anyhow::{Context, Result};
use gstreamer as gst;
use gstreamer::prelude::*;
use gstreamer_app as gst_app;

use crate::capture::PreparedCapture;
use crate::cli::ResolvedConfig;
use crate::encode::{configure_encoder, is_hevc, is_png, pick_encoder};

use super::profile::buffer_profile;

struct PipelineElements {
    src: gst::Element,
    q1: gst::Element,
    caps_src: gst::Element,
    convert: gst::Element,
    scale: gst::Element,
    rate: Option<gst::Element>,
    caps1: gst::Element,
    tee: Option<gst::Element>,
    qmain: Option<gst::Element>,
    enc: gst::Element,
    parse: Option<gst::Element>,
    sink: gst::Element,
}

fn encoder_element_name(encoder_name: &str) -> &'static str {
    match encoder_name {
        "nvenc264" => "nvh264enc",
        "x264" => "x264enc",
        "nvenc265" => "nvh265enc",
        "x265" => "x265enc",
        "rawpng" => "pngenc",
        _ => "x265enc",
    }
}

fn raw_format(mode_png: bool, encoder_name: &str) -> &'static str {
    if mode_png {
        "RGBA"
    } else if matches!(encoder_name, "nvenc264" | "nvenc265") {
        "NV12"
    } else {
        "I420"
    }
}

fn effective_gop(cfg: &ResolvedConfig) -> u32 {
    if cfg.intra_only {
        1
    } else if cfg.h264_gop > 0 {
        cfg.h264_gop
    } else {
        (cfg.fps / 8).max(6)
    }
}

fn parse_mode(mode_png: bool, hevc: bool, framed: bool) -> &'static str {
    if mode_png {
        "png_raw"
    } else if hevc {
        if framed {
            "h265_au"
        } else {
            "h265_nal"
        }
    } else if framed {
        "h264_au"
    } else {
        "h264_nal"
    }
}

fn make_scale(skip_videoscale: bool) -> Result<gst::Element> {
    if skip_videoscale {
        return Ok(gst::ElementFactory::make("identity")
            .name("scale_passthrough")
            .build()?);
    }

    let scale = gst::ElementFactory::make("videoscale")
        .name("scale")
        .build()?;
    let _ = scale.set_property_from_str("method", "lanczos");
    Ok(scale)
}

fn make_optional_element(enabled: bool, factory: &str, name: &str) -> Result<Option<gst::Element>> {
    Ok(enabled
        .then(|| gst::ElementFactory::make(factory).name(name).build())
        .transpose()?)
}

fn configure_queue(
    queue: &gst::Element,
    queue_buffer_frames: u32,
    buffer_time_ns: u64,
    queue_leaky: &str,
) {
    let _ = queue.set_property("max-size-buffers", queue_buffer_frames);
    let _ = queue.set_property("max-size-bytes", 0u32);
    let _ = queue.set_property("flush-on-eos", true);
    let _ = queue.set_property("silent", true);
    let _ = queue.set_property("max-size-time", buffer_time_ns);
    let _ = queue.set_property_from_str("leaky", queue_leaky);
}

fn configure_rate(rate: &gst::Element, cfg: &ResolvedConfig) {
    let _ = rate.set_property("drop-only", cfg.videorate_drop_only);
    let _ = rate.set_property("max-rate", cfg.fps as i32);
    let _ = rate.set_property("average-period", 1_000_000_000u64 / cfg.fps as u64);
}

fn build_raw_caps(cfg: &ResolvedConfig, raw_format: &str, use_videorate: bool) -> gst::Caps {
    let mut caps = gst::Caps::builder("video/x-raw")
        .field("format", raw_format)
        .field("width", cfg.width as i32)
        .field("height", cfg.height as i32);
    if use_videorate {
        caps = caps.field("framerate", gst::Fraction::new(cfg.fps as i32, 1));
    }
    caps.build()
}

fn build_sink_caps(mode_png: bool, hevc: bool, framed: bool) -> gst::Caps {
    if mode_png {
        gst::Caps::builder("image/png").build()
    } else {
        gst::Caps::builder(if hevc { "video/x-h265" } else { "video/x-h264" })
            .field("stream-format", "byte-stream")
            .field("alignment", if framed { "au" } else { "nal" })
            .build()
    }
}

fn push_optional<'a>(elements: &mut Vec<&'a gst::Element>, element: Option<&'a gst::Element>) {
    if let Some(element) = element {
        elements.push(element);
    }
}

fn add_main_path_elements(
    pipeline: &gst::Pipeline,
    elements: &PipelineElements,
    debug_enabled: bool,
) -> Result<()> {
    let mut pipeline_elements = vec![
        &elements.src,
        &elements.q1,
        &elements.caps_src,
        &elements.convert,
        &elements.scale,
    ];
    push_optional(&mut pipeline_elements, elements.rate.as_ref());
    pipeline_elements.push(&elements.caps1);
    if debug_enabled {
        push_optional(&mut pipeline_elements, elements.tee.as_ref());
        push_optional(&mut pipeline_elements, elements.qmain.as_ref());
    }
    pipeline_elements.push(&elements.enc);
    push_optional(&mut pipeline_elements, elements.parse.as_ref());
    pipeline_elements.push(&elements.sink);
    pipeline.add_many(pipeline_elements)?;
    Ok(())
}

fn link_capture_chain(elements: &PipelineElements, debug_enabled: bool) -> Result<()> {
    let mut chain = vec![
        &elements.src,
        &elements.q1,
        &elements.caps_src,
        &elements.convert,
        &elements.scale,
    ];
    push_optional(&mut chain, elements.rate.as_ref());
    chain.push(&elements.caps1);
    if debug_enabled {
        let tee = elements
            .tee
            .as_ref()
            .context("tee missing while debug enabled")?;
        chain.push(tee);
    } else {
        chain.push(&elements.enc);
        push_optional(&mut chain, elements.parse.as_ref());
        chain.push(&elements.sink);
    }
    gst::Element::link_many(chain)?;
    Ok(())
}

fn link_encoder_chain(elements: &PipelineElements) -> Result<()> {
    let qmain = elements
        .qmain
        .as_ref()
        .context("qmain missing while debug enabled")?;
    let tee = elements
        .tee
        .as_ref()
        .context("tee missing while debug enabled")?;
    let tee_pad_main = tee
        .request_pad_simple("src_%u")
        .context("tee src pad (main)")?;
    let qmain_sink = qmain.static_pad("sink").context("qmain sink pad")?;
    tee_pad_main.link(&qmain_sink)?;

    let mut chain = vec![qmain, &elements.enc];
    push_optional(&mut chain, elements.parse.as_ref());
    chain.push(&elements.sink);
    gst::Element::link_many(chain)?;
    Ok(())
}

fn add_and_link_main_path(
    pipeline: &gst::Pipeline,
    elements: &PipelineElements,
    debug_enabled: bool,
) -> Result<()> {
    add_main_path_elements(pipeline, elements, debug_enabled)?;
    link_capture_chain(elements, debug_enabled)?;
    if debug_enabled {
        link_encoder_chain(elements)?;
    }
    Ok(())
}

fn add_debug_branch(
    pipeline: &gst::Pipeline,
    tee: &gst::Element,
    debug_dir: &str,
    debug_fps: u32,
) -> Result<()> {
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
    Ok(())
}

fn log_encoder_selection(
    cfg: &ResolvedConfig,
    encoder_name: &str,
    raw_format: &str,
    mode_png: bool,
    effective_gop: u32,
) {
    if !mode_png {
        println!(
            "[wbeam] encoder-select requested={} backend={} raw_format={} fps={} bitrate={}kbps gop={} intra_only={}",
            cfg.encoder,
            encoder_name,
            raw_format,
            cfg.fps,
            cfg.bitrate_kbps,
            effective_gop,
            cfg.intra_only
        );
    } else {
        println!(
            "[wbeam] encoder-select requested={} backend={} raw_format={} fps={} bitrate={}kbps",
            cfg.encoder, encoder_name, raw_format, cfg.fps, cfg.bitrate_kbps
        );
    }
}

fn log_effective_runtime(
    cfg: &ResolvedConfig,
    encoder_name: &str,
    raw_format: &str,
    profile: &super::profile::BufferProfile,
    capture_backend_name: &str,
    parse_mode: &str,
    effective_gop: u32,
) {
    println!(
        "[wbeam-effective] requested_encoder={} resolved_backend={} raw_format={} size={}x{} fps={} bitrate_kbps={} cursor_mode={:?} gop={} intra_only={} stream_mode={:?} queue_max_buffers={} queue_max_time_ms={} appsink_max_buffers={} appsink_drop={} appsink_sync={} capture_backend={} parse_mode={} timeout_pull_ms={} timeout_write_ms={} timeout_disconnect={} videorate_drop_only={} pipewire_keepalive_ms={}",
        cfg.encoder,
        encoder_name,
        raw_format,
        cfg.width,
        cfg.height,
        cfg.fps,
        cfg.bitrate_kbps,
        cfg.cursor_mode,
        effective_gop,
        cfg.intra_only,
        cfg.stream_mode,
        cfg.queue_max_buffers.max(1),
        cfg.queue_max_time_ms.max(1),
        cfg.appsink_max_buffers.max(1),
        profile.appsink_drop,
        profile.appsink_sync,
        capture_backend_name,
        parse_mode,
        cfg.pull_timeout_ms,
        cfg.write_timeout_ms,
        cfg.disconnect_on_timeout,
        cfg.videorate_drop_only,
        cfg.pipewire_keepalive_ms
    );
}

fn attach_fps_probe(sink: &gst::Element) -> Arc<AtomicU64> {
    let fps_counter = Arc::new(AtomicU64::new(0));
    if let Some(pad) = sink.static_pad("sink") {
        let counter = fps_counter.clone();
        let _ = pad.add_probe(gst::PadProbeType::BUFFER, move |_, _| {
            counter.fetch_add(1, Ordering::Relaxed);
            gst::PadProbeReturn::Ok
        });
    }
    fps_counter
}

/// Build the full capture → encode → appsink pipeline.
///
/// Returns the `(Pipeline, AppSink, fps_counter)` triple.  The `fps_counter`
/// is incremented by a pad probe on the appsink's sink pad on every buffer.
pub fn make_pipeline(
    capture: &PreparedCapture,
    cfg: &ResolvedConfig,
    _port: u16,
    debug_dir: &str,
    debug_fps: u32,
    framed: bool,
) -> Result<(gst::Pipeline, gst_app::AppSink, Arc<AtomicU64>)> {
    let pipeline = gst::Pipeline::with_name("wbeam-capture-pipeline");
    let mode_png = is_png(&cfg.encoder);
    let encoder_name = pick_encoder(&cfg.encoder)?;
    let hevc = is_hevc(&cfg.encoder);
    let mut profile = buffer_profile(cfg.stream_mode, cfg.fps, mode_png);
    profile.queue_buffers = cfg.queue_max_buffers.max(1);
    profile.appsink_buffers = cfg.appsink_max_buffers.max(1);
    profile.queue_time_ns = (cfg.queue_max_time_ms.max(1) as u64) * 1_000_000u64;
    let capture_backend_name = match capture {
        PreparedCapture::Wayland(_) => "wayland_portal",
        PreparedCapture::X11 => "x11",
    };
    let debug_enabled = debug_fps > 0;

    let src = capture.build_source(cfg)?;
    let q1 = gst::ElementFactory::make("queue").name("q1").build()?;
    let caps_src = gst::ElementFactory::make("capsfilter")
        .name("caps_src")
        .build()?;
    let convert = gst::ElementFactory::make("videoconvert")
        .name("conv")
        .build()?;
    let scale = make_scale(cfg.skip_videoscale)?;
    let rate = make_optional_element(profile.use_videorate, "videorate", "rate")?;
    let caps1 = gst::ElementFactory::make("capsfilter")
        .name("caps1")
        .build()?;
    let tee = make_optional_element(debug_enabled, "tee", "tee")?;
    let qmain = make_optional_element(debug_enabled, "queue", "qmain")?;

    let enc_element = encoder_element_name(encoder_name);
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

    configure_queue(
        &q1,
        profile.queue_buffers,
        profile.queue_time_ns,
        profile.queue_leaky,
    );
    if let Some(qmain) = &qmain {
        configure_queue(
            qmain,
            profile.queue_buffers,
            profile.queue_time_ns,
            profile.queue_leaky,
        );
    }

    let caps_source_hint = gst::Caps::builder("video/x-raw").build();
    let _ = caps_src.set_property("caps", &caps_source_hint);

    let raw_format = raw_format(mode_png, encoder_name);
    let caps_raw = build_raw_caps(cfg, raw_format, profile.use_videorate);
    let _ = caps1.set_property("caps", &caps_raw);

    if !mode_png {
        configure_encoder(
            &enc,
            encoder_name,
            cfg.bitrate_kbps,
            cfg.fps,
            &cfg.nv_preset,
            cfg.intra_only,
            cfg.h264_gop,
        );
    }
    if let Some(rate) = &rate {
        configure_rate(rate, cfg);
    }
    if let Some(parse) = &parse {
        parse.set_property("disable-passthrough", false);
        parse.set_property("config-interval", -1i32);
    }

    let caps_sink = build_sink_caps(mode_png, hevc, framed);
    let effective_gop = effective_gop(cfg);
    let parse_mode = parse_mode(mode_png, hevc, framed);
    log_encoder_selection(cfg, encoder_name, raw_format, mode_png, effective_gop);
    log_effective_runtime(
        cfg,
        encoder_name,
        raw_format,
        &profile,
        capture_backend_name,
        parse_mode,
        effective_gop,
    );

    let elements = PipelineElements {
        src,
        q1,
        caps_src,
        convert,
        scale,
        rate,
        caps1,
        tee,
        qmain,
        enc,
        parse,
        sink,
    };
    add_and_link_main_path(&pipeline, &elements, debug_enabled)?;
    if let Some(tee) = elements.tee.as_ref() {
        add_debug_branch(&pipeline, tee, debug_dir, debug_fps)?;
    }

    let appsink: gst_app::AppSink = elements
        .sink
        .clone()
        .dynamic_cast::<gst_app::AppSink>()
        .map_err(|_| anyhow::anyhow!("sink is not an appsink"))?;
    appsink.set_caps(Some(&caps_sink));
    let _ = appsink.set_property("emit-signals", false);
    appsink.set_sync(profile.appsink_sync);
    appsink.set_wait_on_eos(false);
    appsink.set_max_buffers(profile.appsink_buffers);
    appsink.set_drop(profile.appsink_drop);

    let fps_counter = attach_fps_probe(&elements.sink);
    Ok((pipeline, appsink, fps_counter))
}
