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

use super::profile::{buffer_profile, BufferProfile};

struct PipelineRuntime {
    mode_png: bool,
    encoder_name: &'static str,
    hevc: bool,
    profile: BufferProfile,
    capture_backend_name: &'static str,
    source_dynamic_pad: bool,
    debug_enabled: bool,
    raw_format: &'static str,
    effective_drop_only: bool,
    effective_gop: u32,
    parse_mode: &'static str,
}

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

fn pipeline_profile(
    capture: &PreparedCapture,
    cfg: &ResolvedConfig,
    mode_png: bool,
) -> BufferProfile {
    let mut profile = buffer_profile(cfg.stream_mode, cfg.fps, mode_png);
    profile.queue_buffers = cfg.queue_max_buffers.max(1);
    profile.appsink_buffers = cfg.appsink_max_buffers.max(1);
    profile.queue_time_ns = (cfg.queue_max_time_ms.max(1) as u64) * 1_000_000u64;
    if matches!(capture, PreparedCapture::Wayland(_)) {
        profile.queue_leaky = "upstream";
        if profile.queue_buffers < 4 {
            profile.queue_buffers = 4;
        }
        profile.queue_time_ns = 0;
    }
    profile
}

fn capture_backend_name(capture: &PreparedCapture) -> &'static str {
    match capture {
        PreparedCapture::Wayland(_) => "wayland_portal",
        PreparedCapture::X11 => "x11",
        #[cfg(feature = "evdi")]
        PreparedCapture::Evdi => "evdi",
        PreparedCapture::BenchmarkGame => "benchmark_game",
    }
}

fn raw_format(mode_png: bool, encoder_name: &str) -> &'static str {
    if mode_png {
        "RGBA"
    } else if encoder_name == "nvenc264" || encoder_name == "nvenc265" {
        "NV12"
    } else {
        "I420"
    }
}

fn effective_drop_only(capture: &PreparedCapture, cfg: &ResolvedConfig) -> bool {
    #[cfg(feature = "evdi")]
    let is_evdi = matches!(capture, PreparedCapture::Evdi);
    #[cfg(not(feature = "evdi"))]
    let is_evdi = false;
    matches!(capture, PreparedCapture::Wayland(_)) || is_evdi || cfg.videorate_drop_only
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

fn create_scale_element(cfg: &ResolvedConfig) -> Result<gst::Element> {
    if cfg.skip_videoscale {
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

fn create_pipeline_elements(
    capture: &PreparedCapture,
    cfg: &ResolvedConfig,
    runtime: &PipelineRuntime,
) -> Result<PipelineElements> {
    let src = capture.build_source(cfg)?;
    let q1 = gst::ElementFactory::make("queue").name("q1").build()?;
    let caps_src = gst::ElementFactory::make("capsfilter")
        .name("caps_src")
        .build()?;
    let convert = gst::ElementFactory::make("videoconvert")
        .name("conv")
        .build()?;
    let scale = create_scale_element(cfg)?;
    let rate = runtime
        .profile
        .use_videorate
        .then(|| gst::ElementFactory::make("videorate").name("rate").build())
        .transpose()?;
    let caps1 = gst::ElementFactory::make("capsfilter")
        .name("caps1")
        .build()?;
    let tee = runtime
        .debug_enabled
        .then(|| gst::ElementFactory::make("tee").name("tee").build())
        .transpose()?;
    let qmain = runtime
        .debug_enabled
        .then(|| gst::ElementFactory::make("queue").name("qmain").build())
        .transpose()?;

    let enc_element = match runtime.encoder_name {
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
    let parse = (!runtime.mode_png)
        .then(|| {
            gst::ElementFactory::make(if runtime.hevc {
                "h265parse"
            } else {
                "h264parse"
            })
            .name("parse")
            .build()
        })
        .transpose()?;
    let sink = gst::ElementFactory::make("appsink").name("sink").build()?;

    Ok(PipelineElements {
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
    })
}

fn configure_queue(q: &gst::Element, profile: &BufferProfile) {
    let _ = q.set_property("max-size-buffers", profile.queue_buffers);
    let _ = q.set_property("max-size-bytes", 0u32);
    let _ = q.set_property("flush-on-eos", true);
    let _ = q.set_property("silent", true);
    let _ = q.set_property("max-size-time", profile.queue_time_ns);
    let _ = q.set_property_from_str("leaky", profile.queue_leaky);
}

fn maybe_connect_source_pad_handler(
    source_dynamic_pad: bool,
    src: &gst::Element,
    q1: &gst::Element,
) -> Result<()> {
    if !source_dynamic_pad {
        return Ok(());
    }

    let q1_sink = q1.static_pad("sink").context("q1 sink pad")?;
    src.connect_pad_added(move |_src, src_pad| {
        if q1_sink.is_linked() {
            return;
        }
        let caps = src_pad
            .current_caps()
            .unwrap_or_else(|| src_pad.query_caps(None));
        if !caps.to_string().contains("video/") {
            return;
        }
        let _ = src_pad.link(&q1_sink);
    });
    Ok(())
}

fn configure_pipeline_elements(
    elements: &PipelineElements,
    cfg: &ResolvedConfig,
    runtime: &PipelineRuntime,
    framed: bool,
) -> Result<gst_app::AppSink> {
    configure_queue(&elements.q1, &runtime.profile);
    if let Some(qmain) = &elements.qmain {
        configure_queue(qmain, &runtime.profile);
    }
    maybe_connect_source_pad_handler(runtime.source_dynamic_pad, &elements.src, &elements.q1)?;

    let caps_source_hint = gst::Caps::builder("video/x-raw").build();
    let _ = elements.caps_src.set_property("caps", &caps_source_hint);

    let mut raw_caps_builder = gst::Caps::builder("video/x-raw");
    raw_caps_builder = raw_caps_builder
        .field("format", runtime.raw_format)
        .field("width", cfg.width as i32)
        .field("height", cfg.height as i32);
    if runtime.profile.use_videorate {
        raw_caps_builder =
            raw_caps_builder.field("framerate", gst::Fraction::new(cfg.fps as i32, 1));
    }
    let caps_raw = raw_caps_builder.build();
    let _ = elements.caps1.set_property("caps", &caps_raw);

    if !runtime.mode_png {
        configure_encoder(
            &elements.enc,
            runtime.encoder_name,
            cfg.bitrate_kbps,
            cfg.fps,
            &cfg.nv_preset,
            cfg.intra_only,
            cfg.h264_gop,
        );
    }
    if let Some(rate) = &elements.rate {
        let _ = rate.set_property("drop-only", runtime.effective_drop_only);
        let _ = rate.set_property("max-rate", cfg.fps as i32);
        let _ = rate.set_property("average-period", 1_000_000_000u64 / cfg.fps as u64);
    }
    if let Some(parse) = &elements.parse {
        parse.set_property("disable-passthrough", true);
        parse.set_property("config-interval", -1i32);
    }

    let caps_sink = if runtime.mode_png {
        gst::Caps::builder("image/png").build()
    } else {
        gst::Caps::builder(if runtime.hevc {
            "video/x-h265"
        } else {
            "video/x-h264"
        })
        .field("stream-format", "byte-stream")
        .field("alignment", if framed { "au" } else { "nal" })
        .build()
    };
    let appsink: gst_app::AppSink = elements
        .sink
        .clone()
        .dynamic_cast::<gst_app::AppSink>()
        .map_err(|_| anyhow::anyhow!("sink is not an appsink"))?;
    appsink.set_caps(Some(&caps_sink));
    let _ = appsink.set_property("emit-signals", false);
    appsink.set_sync(runtime.profile.appsink_sync);
    appsink.set_wait_on_eos(false);
    appsink.set_max_buffers(runtime.profile.appsink_buffers);
    appsink.set_drop(runtime.profile.appsink_drop);
    Ok(appsink)
}

fn source_chain<'a>(
    elements: &'a PipelineElements,
    source_dynamic_pad: bool,
) -> Vec<&'a gst::Element> {
    let mut chain = Vec::new();
    if !source_dynamic_pad {
        chain.push(&elements.src);
    }
    chain.push(&elements.q1);
    chain.push(&elements.caps_src);
    chain.push(&elements.convert);
    chain.push(&elements.scale);
    if let Some(rate) = &elements.rate {
        chain.push(rate);
    }
    chain.push(&elements.caps1);
    chain
}

fn main_path_elements<'a>(
    elements: &'a PipelineElements,
    debug_enabled: bool,
) -> Vec<&'a gst::Element> {
    let mut refs = source_chain(elements, false);
    if debug_enabled {
        refs.push(elements.tee.as_ref().expect("tee missing"));
        refs.push(elements.qmain.as_ref().expect("qmain missing"));
    }
    refs.push(&elements.enc);
    if let Some(parse) = &elements.parse {
        refs.push(parse);
    }
    refs.push(&elements.sink);
    refs
}

fn encoder_chain<'a>(elements: &'a PipelineElements, debug_enabled: bool) -> Vec<&'a gst::Element> {
    let mut refs = Vec::new();
    if debug_enabled {
        refs.push(elements.qmain.as_ref().expect("qmain missing"));
    }
    refs.push(&elements.enc);
    if let Some(parse) = &elements.parse {
        refs.push(parse);
    }
    refs.push(&elements.sink);
    refs
}

fn add_and_link_main_path(
    pipeline: &gst::Pipeline,
    elements: &PipelineElements,
    source_dynamic_pad: bool,
    debug_enabled: bool,
) -> Result<()> {
    pipeline.add_many(main_path_elements(elements, debug_enabled))?;
    gst::Element::link_many(source_chain(elements, source_dynamic_pad))?;

    if debug_enabled {
        let tee = elements
            .tee
            .as_ref()
            .context("tee missing while debug enabled")?;
        let qmain = elements
            .qmain
            .as_ref()
            .context("qmain missing while debug enabled")?;
        let tee_pad_main = tee
            .request_pad_simple("src_%u")
            .context("tee src pad (main)")?;
        let qmain_sink = qmain.static_pad("sink").context("qmain sink pad")?;
        tee_pad_main.link(&qmain_sink)?;
    }

    gst::Element::link_many(encoder_chain(elements, debug_enabled))?;
    Ok(())
}

fn log_effective_runtime(cfg: &ResolvedConfig, runtime: &PipelineRuntime) {
    if !runtime.mode_png {
        println!(
            "[wbeam] encoder-select requested={} backend={} raw_format={} fps={} bitrate={}kbps gop={} intra_only={}",
            cfg.encoder,
            runtime.encoder_name,
            runtime.raw_format,
            cfg.fps,
            cfg.bitrate_kbps,
            runtime.effective_gop,
            cfg.intra_only
        );
    } else {
        println!(
            "[wbeam] encoder-select requested={} backend={} raw_format={} fps={} bitrate={}kbps",
            cfg.encoder, runtime.encoder_name, runtime.raw_format, cfg.fps, cfg.bitrate_kbps
        );
    }
    println!(
        "[wbeam-effective] requested_encoder={} resolved_backend={} raw_format={} size={}x{} fps={} bitrate_kbps={} cursor_mode={:?} gop={} intra_only={} stream_mode={:?} queue_max_buffers={} queue_max_time_ms={} appsink_max_buffers={} appsink_drop={} appsink_sync={} capture_backend={} parse_mode={} timeout_pull_ms={} timeout_write_ms={} timeout_disconnect={} videorate_drop_only={} pipewire_keepalive_ms={} portal_queue_leaky={} portal_queue_time_ns={}",
        cfg.encoder,
        runtime.encoder_name,
        runtime.raw_format,
        cfg.width,
        cfg.height,
        cfg.fps,
        cfg.bitrate_kbps,
        cfg.cursor_mode,
        runtime.effective_gop,
        cfg.intra_only,
        cfg.stream_mode,
        runtime.profile.queue_buffers,
        cfg.queue_max_time_ms.max(1),
        cfg.appsink_max_buffers.max(1),
        runtime.profile.appsink_drop,
        runtime.profile.appsink_sync,
        runtime.capture_backend_name,
        runtime.parse_mode,
        cfg.pull_timeout_ms,
        cfg.write_timeout_ms,
        cfg.disconnect_on_timeout,
        runtime.effective_drop_only,
        cfg.pipewire_keepalive_ms,
        runtime.profile.queue_leaky,
        runtime.profile.queue_time_ns
    );
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
    let runtime = PipelineRuntime {
        mode_png,
        encoder_name,
        hevc,
        profile: pipeline_profile(capture, cfg, mode_png),
        capture_backend_name: capture_backend_name(capture),
        source_dynamic_pad: false,
        debug_enabled: debug_fps > 0,
        raw_format: raw_format(mode_png, encoder_name),
        effective_drop_only: effective_drop_only(capture, cfg),
        effective_gop: effective_gop(cfg),
        parse_mode: parse_mode(mode_png, hevc, framed),
    };
    let elements = create_pipeline_elements(capture, cfg, &runtime)?;
    let appsink = configure_pipeline_elements(&elements, cfg, &runtime, framed)?;
    log_effective_runtime(cfg, &runtime);
    add_and_link_main_path(
        &pipeline,
        &elements,
        runtime.source_dynamic_pad,
        runtime.debug_enabled,
    )?;

    // ── Optional debug JPEG branch ───────────────────────────────────────────
    if runtime.debug_enabled {
        let tee = elements
            .tee
            .as_ref()
            .context("tee missing for debug branch")?;
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
    if let Some(pad) = elements.sink.static_pad("sink") {
        let counter = fps_counter.clone();
        let _ = pad.add_probe(gst::PadProbeType::BUFFER, move |_, _| {
            counter.fetch_add(1, Ordering::Relaxed);
            gst::PadProbeReturn::Ok
        });
    }

    Ok((pipeline, appsink, fps_counter))
}
