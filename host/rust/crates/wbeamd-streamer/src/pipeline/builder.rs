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
    debug_enabled: bool,
    raw_format: &'static str,
    effective_drop_only: bool,
    effective_gop: u32,
    parse_mode: &'static str,
}

impl PipelineRuntime {
    fn new(
        capture: &PreparedCapture,
        cfg: &ResolvedConfig,
        debug_fps: u32,
        framed: bool,
    ) -> Result<Self> {
        let mode_png = is_png(&cfg.encoder);
        let encoder_name = pick_encoder(&cfg.encoder)?;
        let hevc = is_hevc(&cfg.encoder);
        Ok(Self {
            mode_png,
            encoder_name,
            hevc,
            profile: pipeline_profile(capture, cfg, mode_png),
            capture_backend_name: capture_backend_name(capture),
            debug_enabled: debug_fps > 0,
            raw_format: raw_format(mode_png, encoder_name),
            effective_drop_only: effective_drop_only(capture, cfg),
            effective_gop: effective_gop(cfg),
            parse_mode: parse_mode(mode_png, hevc, framed),
        })
    }
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

fn configure_main_queues(elements: &PipelineElements, profile: &BufferProfile) {
    configure_queue(&elements.q1, profile);
    if let Some(qmain) = &elements.qmain {
        configure_queue(qmain, profile);
    }
}

fn source_hint_caps() -> gst::Caps {
    gst::Caps::builder("video/x-raw").build()
}

fn raw_caps(cfg: &ResolvedConfig, runtime: &PipelineRuntime) -> gst::Caps {
    let mut caps = gst::Caps::builder("video/x-raw")
        .field("format", runtime.raw_format)
        .field("width", cfg.width as i32)
        .field("height", cfg.height as i32);
    if runtime.profile.use_videorate {
        caps = caps.field("framerate", gst::Fraction::new(cfg.fps as i32, 1));
    }
    caps.build()
}

fn sink_caps(runtime: &PipelineRuntime, framed: bool) -> gst::Caps {
    if runtime.mode_png {
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
    }
}

fn configure_videorate(rate: &gst::Element, fps: u32, drop_only: bool) {
    let _ = rate.set_property("drop-only", drop_only);
    let _ = rate.set_property("max-rate", fps as i32);
    let _ = rate.set_property("average-period", 1_000_000_000u64 / fps as u64);
}

fn configure_parse(parse: &gst::Element) {
    parse.set_property("disable-passthrough", true);
    parse.set_property("config-interval", -1i32);
}

fn configure_encoder_path(
    elements: &PipelineElements,
    cfg: &ResolvedConfig,
    runtime: &PipelineRuntime,
) {
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
        configure_videorate(rate, cfg.fps, runtime.effective_drop_only);
    }
    if let Some(parse) = &elements.parse {
        configure_parse(parse);
    }
}

fn configure_appsink(
    sink: &gst::Element,
    runtime: &PipelineRuntime,
    framed: bool,
) -> Result<gst_app::AppSink> {
    let appsink: gst_app::AppSink = sink
        .clone()
        .dynamic_cast::<gst_app::AppSink>()
        .map_err(|_| anyhow::anyhow!("sink is not an appsink"))?;
    let caps = sink_caps(runtime, framed);
    appsink.set_caps(Some(&caps));
    let _ = appsink.set_property("emit-signals", false);
    appsink.set_sync(runtime.profile.appsink_sync);
    appsink.set_wait_on_eos(false);
    appsink.set_max_buffers(runtime.profile.appsink_buffers);
    appsink.set_drop(runtime.profile.appsink_drop);
    Ok(appsink)
}

fn configure_pipeline_elements(
    elements: &PipelineElements,
    cfg: &ResolvedConfig,
    runtime: &PipelineRuntime,
    framed: bool,
) -> Result<gst_app::AppSink> {
    configure_main_queues(elements, &runtime.profile);
    let _ = elements.caps_src.set_property("caps", &source_hint_caps());
    let _ = elements.caps1.set_property("caps", &raw_caps(cfg, runtime));
    configure_encoder_path(elements, cfg, runtime);
    configure_appsink(&elements.sink, runtime, framed)
}

fn add_main_elements_to_pipeline(
    pipeline: &gst::Pipeline,
    elements: &PipelineElements,
) -> Result<()> {
    pipeline.add_many([
        &elements.src,
        &elements.q1,
        &elements.caps_src,
        &elements.convert,
        &elements.scale,
    ])?;
    if let Some(rate) = &elements.rate {
        pipeline.add(rate)?;
    }
    pipeline.add(&elements.caps1)?;
    if let Some(tee) = &elements.tee {
        pipeline.add(tee)?;
    }
    if let Some(qmain) = &elements.qmain {
        pipeline.add(qmain)?;
    }
    pipeline.add(&elements.enc)?;
    if let Some(parse) = &elements.parse {
        pipeline.add(parse)?;
    }
    pipeline.add(&elements.sink)?;
    Ok(())
}

fn link_source_path(elements: &PipelineElements) -> Result<()> {
    if let Some(rate) = &elements.rate {
        gst::Element::link_many([
            &elements.src,
            &elements.q1,
            &elements.caps_src,
            &elements.convert,
            &elements.scale,
            rate,
            &elements.caps1,
        ])?;
    } else {
        gst::Element::link_many([
            &elements.src,
            &elements.q1,
            &elements.caps_src,
            &elements.convert,
            &elements.scale,
            &elements.caps1,
        ])?;
    }
    Ok(())
}

fn link_encoded_path(input: &gst::Element, elements: &PipelineElements) -> Result<()> {
    if let Some(parse) = &elements.parse {
        gst::Element::link_many([input, &elements.enc, parse, &elements.sink])?;
    } else {
        gst::Element::link_many([input, &elements.enc, &elements.sink])?;
    }
    Ok(())
}

fn link_tee_branch(tee: &gst::Element, downstream: &gst::Element, name: &str) -> Result<()> {
    let tee_pad = tee
        .request_pad_simple("src_%u")
        .with_context(|| format!("tee src pad ({name})"))?;
    let downstream_sink = downstream
        .static_pad("sink")
        .with_context(|| format!("{name} sink pad"))?;
    tee_pad.link(&downstream_sink)?;
    Ok(())
}

fn add_and_link_main_path(pipeline: &gst::Pipeline, elements: &PipelineElements) -> Result<()> {
    add_main_elements_to_pipeline(pipeline, elements)?;
    link_source_path(elements)?;

    if let Some(tee) = &elements.tee {
        gst::Element::link_many([&elements.caps1, tee])?;
        let qmain = elements
            .qmain
            .as_ref()
            .context("qmain missing while debug enabled")?;
        link_tee_branch(tee, qmain, "qmain")?;
        link_encoded_path(qmain, elements)?;
    } else {
        link_encoded_path(&elements.caps1, elements)?;
    }
    Ok(())
}

fn configure_debug_queue(qdbg: &gst::Element) {
    let _ = qdbg.set_property("max-size-buffers", 1u32);
    let _ = qdbg.set_property("max-size-bytes", 0u32);
    let _ = qdbg.set_property("max-size-time", 200_000_000u64);
    let _ = qdbg.set_property_from_str("leaky", "downstream");
}

fn debug_caps(debug_fps: u32) -> gst::Caps {
    gst::Caps::builder("video/x-raw")
        .field("format", "I420")
        .field("framerate", gst::Fraction::new(debug_fps as i32, 1))
        .build()
}

fn attach_debug_branch(
    pipeline: &gst::Pipeline,
    tee: &gst::Element,
    debug_dir: &str,
    debug_fps: u32,
) -> Result<()> {
    std::fs::create_dir_all(debug_dir).with_context(|| format!("create debug dir {debug_dir}"))?;

    let qdbg = gst::ElementFactory::make("queue").name("qdbg").build()?;
    let vrdbg = gst::ElementFactory::make("videorate")
        .name("vrdbg")
        .build()?;
    let convdbg = gst::ElementFactory::make("videoconvert")
        .name("convdbg")
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

    configure_debug_queue(&qdbg);
    let _ = capsdbg.set_property("caps", &debug_caps(debug_fps));
    multi.set_property("location", format!("{debug_dir}/frame-%06d.jpg"));
    multi.set_property("sync", false);
    multi.set_property("async", false);
    multi.set_property("post-messages", false);
    multi.set_property("max-files", 300u32);
    let _ = jpeg.set_property("quality", 70i32);

    pipeline.add_many([&qdbg, &vrdbg, &convdbg, &capsdbg, &jpeg, &multi])?;
    link_tee_branch(tee, &qdbg, "qdbg")?;
    gst::Element::link_many([&qdbg, &vrdbg, &convdbg, &capsdbg, &jpeg, &multi])?;
    Ok(())
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
    let runtime = PipelineRuntime::new(capture, cfg, debug_fps, framed)?;
    let elements = create_pipeline_elements(capture, cfg, &runtime)?;
    let appsink = configure_pipeline_elements(&elements, cfg, &runtime, framed)?;
    log_effective_runtime(cfg, &runtime);
    add_and_link_main_path(&pipeline, &elements)?;

    if let Some(tee) = &elements.tee {
        attach_debug_branch(&pipeline, tee, debug_dir, debug_fps)?;
    }

    let fps_counter = attach_fps_probe(&elements.sink);
    Ok((pipeline, appsink, fps_counter))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Once;

    static GST_INIT: Once = Once::new();

    fn init_gst() {
        GST_INIT.call_once(|| {
            gst::init().expect("gst init");
        });
    }

    fn test_profile(use_videorate: bool) -> BufferProfile {
        BufferProfile {
            queue_buffers: 1,
            appsink_buffers: 1,
            queue_leaky: "downstream",
            appsink_drop: true,
            appsink_sync: false,
            use_videorate,
            queue_time_ns: 8_000_000,
        }
    }

    fn test_runtime(mode_png: bool, hevc: bool, use_videorate: bool) -> PipelineRuntime {
        PipelineRuntime {
            mode_png,
            encoder_name: if mode_png {
                "rawpng"
            } else if hevc {
                "x265"
            } else {
                "x264"
            },
            hevc,
            profile: test_profile(use_videorate),
            capture_backend_name: "benchmark_game",
            debug_enabled: false,
            raw_format: if mode_png { "RGBA" } else { "I420" },
            effective_drop_only: false,
            effective_gop: 30,
            parse_mode: if mode_png {
                "png_raw"
            } else if hevc {
                "h265_au"
            } else {
                "h264_au"
            },
        }
    }

    fn test_config() -> ResolvedConfig {
        use crate::cli::{CaptureBackend, StreamMode, WaylandSourceType};
        use ashpd::desktop::screencast::CursorMode;

        ResolvedConfig {
            width: 1280,
            height: 800,
            fps: 60,
            bitrate_kbps: 10_000,
            encoder: "h265".to_string(),
            nv_preset: "p4".to_string(),
            cursor_mode: CursorMode::Hidden,
            stream_mode: StreamMode::Ultra,
            skip_videoscale: false,
            capture_backend: CaptureBackend::Auto,
            benchmark_game: true,
            intra_only: false,
            queue_max_buffers: 1,
            queue_max_time_ms: 8,
            appsink_max_buffers: 1,
            pull_timeout_ms: 20,
            write_timeout_ms: 40,
            disconnect_on_timeout: false,
            videorate_drop_only: false,
            pipewire_keepalive_ms: 12,
            h264_gop: 30,
            restore_token_file: None,
            portal_persist_mode: 2,
            wayland_source_type: WaylandSourceType::Monitor,
        }
    }

    #[test]
    fn raw_caps_only_include_framerate_when_videorate_is_enabled() {
        init_gst();
        let cfg = test_config();

        let caps_with_rate = raw_caps(&cfg, &test_runtime(false, true, true));
        assert!(caps_with_rate
            .to_string()
            .contains("framerate=(fraction)60/1"));

        let caps_without_rate = raw_caps(&cfg, &test_runtime(true, false, false));
        assert!(!caps_without_rate.to_string().contains("framerate="));
    }

    #[test]
    fn sink_caps_follow_output_mode() {
        init_gst();

        let png_caps = sink_caps(&test_runtime(true, false, false), true);
        assert_eq!(png_caps.structure(0).expect("png caps").name(), "image/png");

        let h265_caps = sink_caps(&test_runtime(false, true, true), true);
        let h265_caps_str = h265_caps.to_string();
        assert!(h265_caps_str.contains("video/x-h265"));
        assert!(h265_caps_str.contains("alignment=(string)au"));

        let h264_caps = sink_caps(&test_runtime(false, false, true), false);
        let h264_caps_str = h264_caps.to_string();
        assert!(h264_caps_str.contains("video/x-h264"));
        assert!(h264_caps_str.contains("alignment=(string)nal"));
    }
}
