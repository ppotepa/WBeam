mod capture;
mod cli;
mod encoder;
mod pipeline;
mod transport;

use std::sync::{atomic::{AtomicBool, Ordering}, Arc};

use anyhow::{Context, Result};
use clap::Parser;
use gstreamer as gst;
use gst::glib;
use gst::prelude::*;

use cli::{Args, resolve_profile};
use capture::request_portal_stream;
use pipeline::make_pipeline;
use transport::{spawn_sender, HELLO_CODEC_HEVC, HELLO_CODEC_PNG};
use encoder::{is_hevc, is_png};

#[tokio::main(flavor = "current_thread")]
async fn main() -> Result<()> {
    let args = Args::parse();
    gst::init()?;

    let cfg = resolve_profile(&args)?;
    println!(
        "[wbeam] profile={} size={}x{} fps={} bitrate={}kbps encoder={} cursor={}",
        args.profile, cfg.width, cfg.height, cfg.fps, cfg.bitrate_kbps, cfg.encoder, args.cursor_mode
    );
    println!("[wbeam] Requesting ScreenCast portal session (KDE prompt expected)...");

    let portal = request_portal_stream(&cfg).await?;
    println!("[wbeam] Got PipeWire node id: {}", portal.node_id);

    let (pipeline, appsink, fps_counter) =
        make_pipeline(&portal, &cfg, args.port, &args.debug_dir, args.debug_fps, args.framed)?;

    let bus = pipeline.bus().context("pipeline bus")?;

    let stop_flag = Arc::new(AtomicBool::new(false));
    let codec_flags = if is_png(&cfg.encoder) {
        HELLO_CODEC_PNG
    } else if is_hevc(&cfg.encoder) {
        HELLO_CODEC_HEVC
    } else {
        0x00
    };

    let sender_handle = spawn_sender(
        appsink,
        args.port,
        cfg.fps,
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

    println!(
        "[wbeam] Streaming Wayland screencast on tcp://0.0.0.0:{}",
        args.port
    );
    if args.debug_fps > 0 {
        println!(
            "[wbeam] Debug frames: {} ({} fps, max 300 files)",
            args.debug_dir, args.debug_fps
        );
    }

    pipeline.set_state(gst::State::Playing)?;
    main_loop.run();

    stop_flag.store(true, Ordering::SeqCst);
    let _ = sender_handle.join();
    pipeline.set_state(gst::State::Null)?;

    println!("[wbeam] streamer exit");
    Ok(())
}
