//! GStreamer pipeline construction.
//!
//! Builds the PipeWire -> videoconvert -> videoscale -> (optional videorate) ->
//! encoder -> h264parse/h265parse -> appsink pipeline, with an optional debug JPEG
//! branch when `debug_fps > 0`.

mod caps;
mod debug;
mod runtime;

use std::sync::{
    atomic::{AtomicU64, Ordering},
    Arc, Mutex,
};
use std::time::{Duration, Instant};

use anyhow::{anyhow, Context, Result};
use gstreamer as gst;
use gstreamer::prelude::*;
use gstreamer_app as gst_app;
use gstreamer_video as gst_video;

use crate::capture::PreparedCapture;
use crate::cli::ResolvedConfig;
use crate::encode::configure_encoder;

use super::profile::BufferProfile;
use caps::{debug_caps, raw_caps, sink_caps, source_hint_caps};
use debug::DebugBranchElements;
use runtime::PipelineRuntime;

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

impl PipelineElements {
    fn add_to_pipeline(&self, pipeline: &gst::Pipeline) -> Result<()> {
        pipeline.add_many([
            &self.src,
            &self.q1,
            &self.caps_src,
            &self.convert,
            &self.scale,
        ])?;
        if let Some(rate) = &self.rate {
            pipeline.add(rate)?;
        }
        pipeline.add(&self.caps1)?;
        if let Some(tee) = &self.tee {
            pipeline.add(tee)?;
        }
        if let Some(qmain) = &self.qmain {
            pipeline.add(qmain)?;
        }
        pipeline.add(&self.enc)?;
        if let Some(parse) = &self.parse {
            pipeline.add(parse)?;
        }
        pipeline.add(&self.sink)?;
        Ok(())
    }

    fn link_source_chain(&self) -> Result<()> {
        if let Some(rate) = &self.rate {
            gst::Element::link_many([
                &self.src,
                &self.q1,
                &self.caps_src,
                &self.convert,
                &self.scale,
                rate,
                &self.caps1,
            ])?;
        } else {
            gst::Element::link_many([
                &self.src,
                &self.q1,
                &self.caps_src,
                &self.convert,
                &self.scale,
                &self.caps1,
            ])?;
        }
        Ok(())
    }

    fn link_encoded_chain(&self, input: &gst::Element) -> Result<()> {
        if let Some(parse) = &self.parse {
            gst::Element::link_many([input, &self.enc, parse, &self.sink])?;
        } else {
            gst::Element::link_many([input, &self.enc, &self.sink])?;
        }
        Ok(())
    }

    fn link_output_chain(&self) -> Result<()> {
        match (&self.tee, &self.qmain) {
            (Some(tee), Some(qmain)) => {
                gst::Element::link_many([&self.caps1, tee])?;
                link_tee_branch(tee, qmain, "qmain")?;
                self.link_encoded_chain(qmain)
            }
            (None, None) => self.link_encoded_chain(&self.caps1),
            _ => Err(anyhow!("debug pipeline state is inconsistent")),
        }
    }
}

fn build_element(factory: &str, name: &str) -> Result<gst::Element> {
    gst::ElementFactory::make(factory)
        .name(name)
        .build()
        .with_context(|| format!("{factory} not available"))
}

fn create_scale_element(cfg: &ResolvedConfig) -> Result<gst::Element> {
    if cfg.skip_videoscale {
        return build_element("identity", "scale_passthrough");
    }

    let scale = build_element("videoscale", "scale")?;
    let _ = scale.set_property_from_str("method", "lanczos");
    Ok(scale)
}

fn create_pipeline_elements(
    capture: &PreparedCapture,
    cfg: &ResolvedConfig,
    runtime: &PipelineRuntime,
) -> Result<PipelineElements> {
    let src = capture.build_source(cfg)?;
    let q1 = build_element("queue", "q1")?;
    let caps_src = build_element("capsfilter", "caps_src")?;
    let convert = build_element("videoconvert", "conv")?;
    let scale = create_scale_element(cfg)?;
    let rate = runtime
        .profile
        .use_videorate
        .then(|| build_element("videorate", "rate"))
        .transpose()?;
    let caps1 = build_element("capsfilter", "caps1")?;
    let tee = runtime
        .debug_enabled
        .then(|| build_element("tee", "tee"))
        .transpose()?;
    let qmain = runtime
        .debug_enabled
        .then(|| build_element("queue", "qmain"))
        .transpose()?;

    let enc_element = runtime.encoder_factory();
    let enc = build_element(enc_element, "enc")?;
    let parse = runtime
        .parse_factory()
        .map(|factory| build_element(factory, "parse"))
        .transpose()?;
    let sink = build_element("appsink", "sink")?;

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
    elements.add_to_pipeline(pipeline)?;
    elements.link_source_chain()?;
    elements.link_output_chain()?;
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

fn attach_wake_discont_force_key_probe(
    elements: &PipelineElements,
    runtime: &PipelineRuntime,
    cfg: &ResolvedConfig,
) {
    if runtime.mode_png || cfg.intra_only {
        return;
    }
    let Some(probe_pad) = elements.caps1.static_pad("src") else {
        return;
    };
    let Some(upstream_event_pad) = elements.sink.static_pad("sink") else {
        return;
    };

    let min_interval = Duration::from_millis(80);
    let last_request_at = Arc::new(Mutex::new(Instant::now() - min_interval));
    let encoder_backend = runtime.encoder_name.to_string();
    let capture_backend = runtime.capture_backend_name.to_string();
    let _ = probe_pad.add_probe(gst::PadProbeType::BUFFER, move |_pad, info| {
        let Some(buffer) = info.buffer() else {
            return gst::PadProbeReturn::Ok;
        };
        if !buffer.flags().contains(gst::BufferFlags::DISCONT) {
            return gst::PadProbeReturn::Ok;
        }
        let mut should_request = false;
        if let Ok(mut guard) = last_request_at.lock() {
            if guard.elapsed() >= min_interval {
                *guard = Instant::now();
                should_request = true;
            }
        }
        if should_request {
            let event = gst_video::UpstreamForceKeyUnitEvent::builder()
                .all_headers(true)
                .build();
            let sent = upstream_event_pad.send_event(event);
            println!(
                "[wbeam] wake-discont => force-key-unit request sent={} encoder={} capture={}",
                sent, encoder_backend, capture_backend
            );
        }
        gst::PadProbeReturn::Ok
    });
}

fn log_encoder_selection(cfg: &ResolvedConfig, runtime: &PipelineRuntime) {
    if runtime.mode_png {
        println!(
            "[wbeam] encoder-select requested={} backend={} raw_format={} fps={} bitrate={}kbps",
            cfg.encoder, runtime.encoder_name, runtime.raw_format, cfg.fps, cfg.bitrate_kbps
        );
        return;
    }

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
}

fn log_effective_runtime(cfg: &ResolvedConfig, runtime: &PipelineRuntime) {
    log_encoder_selection(cfg, runtime);
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

/// Build the full capture -> encode -> appsink pipeline.
///
/// Returns the `(Pipeline, AppSink, fps_counter)` triple. The `fps_counter`
/// is incremented by a pad probe on the appsink's sink pad on every buffer.
pub fn make_pipeline(
    capture: &PreparedCapture,
    cfg: &ResolvedConfig,
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
        DebugBranchElements::new(debug_fps, debug_dir)?.attach_to_pipeline(
            &pipeline,
            tee,
            link_tee_branch,
        )?;
    }

    attach_wake_discont_force_key_probe(&elements, &runtime, cfg);
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

    #[test]
    fn runtime_resolves_encoder_and_parser_factories() {
        let png_runtime = test_runtime(true, false, false);
        assert_eq!(png_runtime.encoder_factory(), "pngenc");
        assert_eq!(png_runtime.parse_factory(), None);

        let h265_runtime = test_runtime(false, true, true);
        assert_eq!(h265_runtime.encoder_factory(), "x265enc");
        assert_eq!(h265_runtime.parse_factory(), Some("h265parse"));

        let h264_runtime = test_runtime(false, false, true);
        assert_eq!(h264_runtime.encoder_factory(), "x264enc");
        assert_eq!(h264_runtime.parse_factory(), Some("h264parse"));
    }
}
