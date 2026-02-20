use std::io::{Cursor, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::{atomic::{AtomicBool, AtomicU64, Ordering}, Arc};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result};
use ashpd::desktop::screencast::{CursorMode, PersistMode, Screencast, SourceType};
use ashpd::WindowIdentifier;
use byteorder::{BigEndian, WriteBytesExt};
use clap::Parser;
use gstreamer as gst;
use gstreamer::prelude::*;
use gstreamer_app as gst_app;
use std::io::IoSlice;
use rand::Rng;
use gst::glib;

const FRAME_MAGIC: &[u8; 4] = b"WBTP";
const FRAME_VERSION: u8 = 0x01;
const FRAME_FLAG_KEYFRAME: u8 = 0x02;
const HELLO_MAGIC: &[u8; 4] = b"WBS1";
const HELLO_VERSION: u8 = 0x01;

#[derive(Debug, Parser, Clone)]
#[command(name = "wbeamd-streamer", about = "Wayland portal screencast -> WBTP framed sender")]
struct Args {
    #[arg(long, default_value = "balanced", value_parser = ["lowlatency", "balanced", "ultra"])]
    profile: String,
    #[arg(long, default_value_t = 5000)]
    port: u16,
    #[arg(long)]
    size: Option<String>,
    #[arg(long)]
    fps: Option<u32>,
    #[arg(long, default_value_t = 0)]
    debug_fps: u32,
    #[arg(long)]
    bitrate_kbps: Option<u32>,
    #[arg(long, default_value = "auto", value_parser = ["auto", "nvenc", "x264", "openh264"])]
    encoder: String,
    #[arg(long, default_value = "hidden", value_parser = ["hidden", "embedded", "metadata"])]
    cursor_mode: String,
    #[arg(long, default_value = "/tmp/wbeam-frames")]
    debug_dir: String,
    #[arg(long, default_value_t = true)]
    framed: bool,
}

#[derive(Debug, Clone)]
struct ResolvedConfig {
    width: u32,
    height: u32,
    fps: u32,
    bitrate_kbps: u32,
    encoder: String,
    nv_preset: String,
    cursor_mode: CursorMode,
}

#[derive(Debug)]
struct PortalStream {
    fd: i32,
    node_id: u32,
}

fn resolve_profile(args: &Args) -> Result<ResolvedConfig> {
    let (default_size, default_fps, default_bitrate, nv_preset) = match args.profile.as_str() {
        "lowlatency" => ("1280x720", 60, 16_000, "p2"),
        "balanced" => ("1920x1080", 60, 25_000, "p4"),
        _ => ("2560x1440", 60, 38_000, "p6"),
    };

    let size = args.size.as_deref().unwrap_or(default_size);
    let fps = args.fps.unwrap_or(default_fps).clamp(24, 120);
    let bitrate_kbps = args.bitrate_kbps.unwrap_or(default_bitrate).clamp(4_000, 120_000);
    let (w, h) = size
        .to_lowercase()
        .split_once('x')
        .and_then(|(w, h)| Some((w.parse::<u32>().ok()?, h.parse::<u32>().ok()?)))
        .context("--size must be WIDTHxHEIGHT")?;

    let encoder = args.encoder.clone();
    let cursor_mode = match args.cursor_mode.as_str() {
        "hidden" => CursorMode::Hidden,
        "embedded" => CursorMode::Embedded,
        _ => CursorMode::Metadata,
    };

    Ok(ResolvedConfig {
        width: w,
        height: h,
        fps,
        bitrate_kbps,
        encoder,
        nv_preset: nv_preset.to_string(),
        cursor_mode,
    })
}

async fn request_portal_stream(cfg: &ResolvedConfig) -> Result<PortalStream> {
    let proxy = Screencast::new().await?;
    let session = proxy.create_session().await?;

    proxy
        .select_sources(
            &session,
            cfg.cursor_mode,
            SourceType::Monitor.into(),
            false,
            None,
            PersistMode::default(),
        )
        .await?;

    let start = proxy
        .start(&session, &WindowIdentifier::default())
        .await?;

    let results = start.response()?;
    let stream = results
        .streams()
        .first()
        .cloned()
        .context("Portal returned no streams")?;

    let fd = proxy.open_pipe_wire_remote(&session).await?;
    let node_id = stream.pipe_wire_node_id();

    Ok(PortalStream { fd, node_id })
}

fn pick_encoder(requested: &str) -> Result<String> {
    let have_nv = gst::ElementFactory::find("nvh264enc").is_some();
    let have_x264 = gst::ElementFactory::find("x264enc").is_some();
    let have_oh = gst::ElementFactory::find("openh264enc").is_some();

    if requested == "nvenc" {
        anyhow::ensure!(have_nv, "nvh264enc not available");
        return Ok("nvenc".to_string());
    }
    if requested == "x264" {
        anyhow::ensure!(have_x264, "x264enc not available");
        return Ok("x264".to_string());
    }
    if requested == "openh264" {
        anyhow::ensure!(have_oh, "openh264enc not available");
        return Ok("openh264".to_string());
    }

    // Auto-select: prefer nvenc > x264 > openh264
    if have_nv {
        return Ok("nvenc".to_string());
    }
    if have_x264 {
        return Ok("x264".to_string());
    }
    if have_oh {
        return Ok("openh264".to_string());
    }

    anyhow::bail!("No supported H264 encoder found (nvh264enc/x264enc/openh264enc)");
}

fn configure_encoder(enc: &gst::Element, encoder: &str, bitrate_kbps: u32, fps: u32, nv_preset: &str) {
    let gop = fps.max(30);
    
    if encoder == "nvenc" {
        let _ = enc.set_property("bitrate", bitrate_kbps);
        let _ = enc.set_property("max-bitrate", bitrate_kbps);
        let _ = enc.set_property_from_str("rc-mode", "cbr");
        let _ = enc.set_property_from_str("preset", nv_preset);
        let _ = enc.set_property_from_str("gop-size", &gop.to_string());
        let _ = enc.set_property_from_str("bframes", "0");
        let _ = enc.set_property("zerolatency", true);
        let _ = enc.set_property("aud", true);
        let _ = enc.set_property("repeat-sequence-header", true);
        return;
    }
    
    if encoder == "x264" {
        let _ = enc.set_property("bitrate", bitrate_kbps);
        let _ = enc.set_property_from_str("speed-preset", "ultrafast");
        let _ = enc.set_property_from_str("tune", "zerolatency");
        let _ = enc.set_property("key-int-max", gop);
        let _ = enc.set_property("b-adapt", false);
        let _ = enc.set_property("bframes", 0u32);
        let _ = enc.set_property("vbv-buf-capacity", 1000u32);
        let _ = enc.set_property("aud", true);
        let _ = enc.set_property("byte-stream", true);
        let _ = enc.set_property("threads", 0u32);
        return;
    }

    // openh264 config
    let _ = enc.set_property("bitrate", (bitrate_kbps * 1000) as u32);
    let _ = enc.set_property_from_str("rate-control", "bitrate");
    let _ = enc.set_property("gop-size", gop as u32);
    let _ = enc.set_property("multi-thread", 0i32);
    let _ = enc.set_property_from_str("slice-mode", "n-slices");
    let _ = enc.set_property("num-slices", 1u32);
    let _ = enc.set_property("scene-change-detection", false);
    let _ = enc.set_property("background-detection", false);
    let _ = enc.set_property("qp-min", 8u32);
    let _ = enc.set_property("qp-max", 32u32);
}

fn make_pipeline(stream: &PortalStream, cfg: &ResolvedConfig, _port: u16, debug_dir: &str, debug_fps: u32, framed: bool) -> Result<(gst::Pipeline, gst_app::AppSink, Arc<AtomicU64>)> {
    let pipeline = gst::Pipeline::with_name("wbeam-wayland-pipeline");

    let src = gst::ElementFactory::make("pipewiresrc")
        .name("src")
        .build()
        .context("pipewiresrc missing")?;
    src.set_property("fd", stream.fd);
    src.set_property("path", stream.node_id.to_string());
    src.set_property("do-timestamp", true);
    src.set_property("keepalive-time", 1000i32);

    let q1 = gst::ElementFactory::make("queue").name("q1").build()?;
    let convert = gst::ElementFactory::make("videoconvert").name("conv").build()?;
    let scale = gst::ElementFactory::make("videoscale").name("scale").build()?;
    let rate = gst::ElementFactory::make("videorate").name("rate").build()?;
    let caps1 = gst::ElementFactory::make("capsfilter").name("caps1").build()?;
    let tee = gst::ElementFactory::make("tee").name("tee").build()?;
    let qmain = gst::ElementFactory::make("queue").name("qmain").build()?;

    let encoder_name = pick_encoder(&cfg.encoder)?;
    let enc_element = match encoder_name.as_str() {
        "nvenc" => "nvh264enc",
        "x264" => "x264enc",
        _ => "openh264enc",
    };
    let enc = gst::ElementFactory::make(enc_element)
        .name("enc")
        .build()
        .with_context(|| format!("{} not available", enc_element))?;
    let parse = gst::ElementFactory::make("h264parse").name("parse").build()?;
    let caps2 = gst::ElementFactory::make("capsfilter").name("caps2").build()?;
    let sink = gst::ElementFactory::make("appsink").name("sink").build()?;

    for q in [&q1, &qmain] {
        let _ = q.set_property("max-size-buffers", 2u32);
        let _ = q.set_property("max-size-bytes", 0u32);
        let _ = q.set_property("max-size-time", 40_000_000u64);
        let _ = q.set_property_from_str("leaky", "downstream");
    }

    let raw_format = match encoder_name.as_str() {
        "nvenc" => "NV12",
        "x264" => "I420",
        _ => "I420",
    };
    let caps_raw = gst::Caps::builder("video/x-raw")
        .field("format", raw_format)
        .field("width", cfg.width as i32)
        .field("height", cfg.height as i32)
        .field("framerate", gst::Fraction::new(cfg.fps as i32, 1))
        .build();
    let _ = caps1.set_property("caps", &caps_raw);

    configure_encoder(&enc, &encoder_name, cfg.bitrate_kbps, cfg.fps, &cfg.nv_preset);
    let _ = rate.set_property("drop-only", false);
    let _ = rate.set_property_from_str("max-rate", &cfg.fps.to_string());
    let _ = rate.set_property_from_str("average-period", &(1_000_000_000u64 / cfg.fps as u64).to_string());

    let caps_sink = gst::Caps::builder("video/x-h264")
        .field("stream-format", "byte-stream")
        .field("alignment", if framed { "au" } else { "nal" })
        .build();
    let _ = caps2.set_property("caps", &caps_sink);

    parse.set_property("disable-passthrough", true);
    parse.set_property("config-interval", 1i32);

    let appsink: gst_app::AppSink = sink
        .clone()
        .dynamic_cast::<gst_app::AppSink>()
        .map_err(|_| anyhow::anyhow!("sink is not an appsink"))?;
    appsink.set_caps(Some(&caps_sink));
    appsink.set_sync(true);
    appsink.set_max_buffers(4);
    appsink.set_drop(true);

    pipeline.add_many([
        &src, &q1, &convert, &scale, &rate, &caps1, &tee, &qmain, &enc, &parse, &caps2, &sink,
    ])?;

    gst::Element::link_many([&src, &q1, &convert, &scale, &rate, &caps1, &tee])?;

    let tee_pad_main = tee
        .request_pad_simple("src_%u")
        .context("tee src pad (main)")?;
    let qmain_sink = qmain
        .static_pad("sink")
        .context("qmain sink pad")?;
    tee_pad_main.link(&qmain_sink)?;

    gst::Element::link_many([&qmain, &enc, &parse, &caps2, &sink])?;

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
        let _ = capsdbg.set_property("caps", &gst::Caps::builder("video/x-raw").field("framerate", gst::Fraction::new(debug_fps as i32, 1)).build());
        multi.set_property("location", format!("{}/frame-%06d.jpg", debug_dir));
        multi.set_property("post-messages", false);
        multi.set_property("max-files", 300i32);

        pipeline.add_many([&qdbg, &vrdbg, &capsdbg, &jpeg, &multi])?;

        let tee_pad_dbg = tee
            .request_pad_simple("src_%u")
            .context("tee src pad (debug)")?;
        let qdbg_sink = qdbg.static_pad("sink").context("qdbg sink pad")?;
        tee_pad_dbg.link(&qdbg_sink)?;

        gst::Element::link_many([&qdbg, &vrdbg, &capsdbg, &jpeg, &multi])?;
    }

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

fn build_header(seq: u32, pts_us: u64, payload_len: usize, is_key: bool) -> [u8; 22] {
    let mut buf = [0u8; 22];
    let mut cur = Cursor::new(&mut buf[..]);
    let flags = if is_key { FRAME_FLAG_KEYFRAME } else { 0x00 };
    let _ = cur.write_all(FRAME_MAGIC);
    let _ = cur.write_u8(FRAME_VERSION);
    let _ = cur.write_u8(flags);
    let _ = cur.write_u32::<BigEndian>(seq);
    let _ = cur.write_u64::<BigEndian>(pts_us);
    let _ = cur.write_u32::<BigEndian>(payload_len as u32);
    buf
}

fn build_hello(session_id: u64) -> [u8; 16] {
    let mut buf = [0u8; 16];
    let mut cur = Cursor::new(&mut buf[..]);
    let _ = cur.write_all(HELLO_MAGIC);
    let _ = cur.write_u8(HELLO_VERSION);
    let _ = cur.write_u8(0x00);
    let _ = cur.write_u16::<BigEndian>(16);
    let _ = cur.write_u64::<BigEndian>(session_id);
    buf
}

fn send_all_vectored(stream: &mut TcpStream, header: &[u8], payload: &[u8]) -> std::io::Result<usize> {
    let mut total = 0usize;
    let mut header_rem = header;
    let mut payload_rem = payload;

    loop {
        let bufs = [IoSlice::new(header_rem), IoSlice::new(payload_rem)];
        let written = stream.write_vectored(&bufs)?;
        if written == 0 {
            return Ok(total);
        }
        total += written;

        if written < header_rem.len() {
            header_rem = &header_rem[written..];
            continue;
        }

        let consumed_payload = written.saturating_sub(header_rem.len());
        header_rem = &[];
        if consumed_payload < payload_rem.len() {
            payload_rem = &payload_rem[consumed_payload..];
            continue;
        }

        break;
    }

    Ok(total)
}

fn spawn_sender(appsink: gst_app::AppSink, port: u16, stop: Arc<AtomicBool>, fps_counter: Arc<AtomicU64>) -> thread::JoinHandle<()> {
    thread::spawn(move || {
        let listener = TcpListener::bind(SocketAddr::from(([0, 0, 0, 0], port)))
            .expect("bind tcp listener");
        listener.set_nonblocking(true).ok();
        println!("[wbeam-framed] listening on :{}", port);
        let pull_timeout = Some(gst::ClockTime::from_mseconds(20));
        let mut seq: u32 = 0;
        let mut last_keyframe: Option<Vec<u8>> = None;
        let mut last_key_pts = 0u64;

        while !stop.load(Ordering::SeqCst) {
            let mut conn = match listener.accept() {
                Ok((s, addr)) => {
                    let _ = s.set_nodelay(true);
                    let _ = s.set_nonblocking(false);
                    let _ = s.set_write_timeout(Some(Duration::from_millis(10)));
                    println!("[wbeam-framed] client connected: {}", addr);
                    s
                }
                Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    std::thread::sleep(Duration::from_millis(10));
                    continue;
                }
                Err(e) => {
                    eprintln!("[wbeam-framed] accept error: {e}");
                    break;
                }
            };

            let session_id: u64 = rand::thread_rng().gen();
            let hello = build_hello(session_id);
            let _ = conn.write_all(&hello);
            println!("[wbeam-framed] session_id=0x{session_id:016x}");

            let mut frames = 0u64;
            let mut dropped = 0u64;
            let mut partial_writes = 0u64;
            let mut send_timeouts = 0u64;
            let mut t0 = Instant::now();

            loop {
                if stop.load(Ordering::SeqCst) {
                    break;
                }
                let sample = appsink.try_pull_sample(pull_timeout);
                let mut payload: Option<Vec<u8>> = None;
                let mut pts_us = SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .map(|d| d.as_micros() as u64)
                    .unwrap_or(0);
                let mut is_key = true;

                if let Some(sample) = sample {
                    if let Some(buf) = sample.buffer() {
                        let map = match buf.map_readable() {
                            Ok(m) => m,
                            Err(_) => continue,
                        };
                        let data = map.as_slice();
                        pts_us = buf
                            .pts()
                            .map(|t| t.nseconds() / 1000)
                            .unwrap_or(pts_us);
                        is_key = !buf.flags().contains(gst::BufferFlags::DELTA_UNIT);
                        payload = Some(data.to_vec());
                        if is_key {
                            last_keyframe = Some(data.to_vec());
                            last_key_pts = pts_us;
                        }
                    }
                } else {
                    if let Some(ref kf) = last_keyframe {
                        payload = Some(kf.clone());
                        pts_us = last_key_pts;
                        is_key = true;
                    } else {
                        dropped += 1;
                        let elapsed = t0.elapsed().as_secs_f64();
                        if elapsed >= 1.0 {
                            let sent_fps = frames as f64 / elapsed;
                            let pipe_fps = fps_counter.swap(0, Ordering::Relaxed);
                            println!(
                                "[wbeam-framed] pipeline_fps={} sender_fps={:.1} timeout_misses={} seq={}",
                                pipe_fps, sent_fps, dropped, seq
                            );
                            frames = 0;
                            dropped = 0;
                            t0 = Instant::now();
                        }
                        continue;
                    }
                }

                let payload = match payload {
                    Some(p) => p,
                    None => continue,
                };

                let header = build_header(seq, pts_us, payload.len(), is_key);
                let sent = match send_all_vectored(&mut conn, &header, &payload) {
                    Ok(n) => n,
                    Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        send_timeouts += 1;
                        dropped += 1;
                        continue;
                    }
                    Err(e) => {
                        println!("[wbeam-framed] client disconnected: {e}");
                        break;
                    }
                };
                if sent < header.len() + payload.len() {
                    partial_writes += 1;
                }

                seq = seq.wrapping_add(1);
                frames += 1;
                let elapsed = t0.elapsed().as_secs_f64();
                if elapsed >= 1.0 {
                    let sent_fps = frames as f64 / elapsed;
                    let pipe_fps = fps_counter.swap(0, Ordering::Relaxed);
                    println!(
                        "[wbeam-framed] pipeline_fps={} sender_fps={:.1} timeout_misses={} partial_writes={} send_timeouts={} seq={}",
                        pipe_fps, sent_fps, dropped, partial_writes, send_timeouts, seq
                    );
                    frames = 0;
                    dropped = 0;
                    partial_writes = 0;
                    send_timeouts = 0;
                    t0 = Instant::now();
                }
            }
        }
    })
}

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

    let (pipeline, appsink, fps_counter) = make_pipeline(&portal, &cfg, args.port, &args.debug_dir, args.debug_fps, args.framed)?;

    let bus = pipeline
        .bus()
        .context("pipeline bus")?;

    let stop_flag = Arc::new(AtomicBool::new(false));
    let sender_handle = spawn_sender(appsink, args.port, stop_flag.clone(), fps_counter);

    let main_loop = glib::MainLoop::new(None, false);
    let main_loop_clone = main_loop.clone();
    let _watch = bus.add_watch_local(move |_bus, msg| {
        use gst::MessageView;
        match msg.view() {
            MessageView::Eos(..) => {
                println!("[gst] EOS received, stopping");
                main_loop_clone.quit();
                glib::ControlFlow::Break
            }
            MessageView::Error(err) => {
                eprintln!("[gst-error] {}: {}", err.error(), err.debug().unwrap_or_default());
                main_loop_clone.quit();
                glib::ControlFlow::Break
            }
            _ => glib::ControlFlow::Continue,
        }
    })?;

    println!("[wbeam] Streaming Wayland screencast on tcp://0.0.0.0:{}", args.port);
    if args.debug_fps > 0 {
        println!("[wbeam] Debug frames: {} ({} fps, max 300 files)", args.debug_dir, args.debug_fps);
    }

    pipeline.set_state(gst::State::Playing)?;
    main_loop.run();

    stop_flag.store(true, Ordering::SeqCst);
    let _ = sender_handle.join();
    pipeline.set_state(gst::State::Null)?;

    println!("[wbeam] streamer exit");
    Ok(())
}
