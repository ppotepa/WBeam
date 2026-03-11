mod backend;
mod capture;
mod cli;
mod encoder;
mod pipeline;
mod transport;

use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc,
};

use anyhow::{Context, Result};
use clap::Parser;
use gst::glib;
use gst::prelude::*;
use gstreamer as gst;

use backend::prepare_capture;
use cli::{resolve_profile, Args};
use encoder::{is_hevc, is_png};
use pipeline::make_pipeline;
use transport::{hello_mode_bits, spawn_sender, HELLO_CODEC_HEVC, HELLO_CODEC_PNG};

#[tokio::main(flavor = "current_thread")]
async fn main() -> Result<()> {
    let args = Args::parse();
    gst::init()?;

    let cfg = resolve_profile(&args)?;
    println!(
        "[wbeam] profile={} mode={:?} capture={:?} size={}x{} fps={} bitrate={}kbps encoder={} cursor={} skip_videoscale={}",
        args.profile, cfg.stream_mode, cfg.capture_backend, cfg.width, cfg.height, cfg.fps, cfg.bitrate_kbps, cfg.encoder, args.cursor_mode, cfg.skip_videoscale
    );
    let capture = prepare_capture(&cfg).await?;
    capture.announce_startup();

    let (pipeline, appsink, fps_counter) = make_pipeline(
        &capture,
        &cfg,
        args.port,
        &args.debug_dir,
        args.debug_fps,
        args.framed,
    )?;

    let bus = pipeline.bus().context("pipeline bus")?;

    let stop_flag = Arc::new(AtomicBool::new(false));
    let codec_bits = if is_png(&cfg.encoder) {
        HELLO_CODEC_PNG
    } else if is_hevc(&cfg.encoder) {
        HELLO_CODEC_HEVC
    } else {
        0x00
    };
    let codec_flags = codec_bits | hello_mode_bits(cfg.stream_mode);

    let sender_handle = spawn_sender(
        appsink,
        args.port,
        cfg.fps,
        cfg.clone(),
        cfg.stream_mode,
        stop_flag.clone(),
        fps_counter,
        codec_flags,
    );

    let main_loop = glib::MainLoop::new(None, false);
    let main_loop_clone = main_loop.clone();
    let _watch = bus.add_watch_local(move |_bus, msg: &gst::Message| {
        use gst::MessageView;
        match msg.view() {
            MessageView::Eos(..) => {
                println!("[gst] EOS received, stopping");
                main_loop_clone.quit();
                glib::ControlFlow::Break
            }
            MessageView::Error(err) => {
                eprintln!(
                    "[gst-error] {}: {}",
                    err.error(),
                    err.debug().unwrap_or_default()
                );
                main_loop_clone.quit();
                glib::ControlFlow::Break
            }
            _ => glib::ControlFlow::Continue,
        }
    })?;

    capture.announce_streaming(args.port);
    if args.debug_fps > 0 {
        println!(
            "[wbeam] Debug frames: {} ({} fps, max 300 files)",
            args.debug_dir, args.debug_fps
        );
    }

    pipeline.set_state(gst::State::Playing)?;
    main_loop.run();

    stop_flag.store(true, Ordering::Release);
    let _ = sender_handle.join();
    pipeline.set_state(gst::State::Null)?;

    println!("[wbeam] streamer exit");
    Ok(())
}
