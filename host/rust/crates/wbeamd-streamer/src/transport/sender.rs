//! WBTP framing and TCP sender.
//!
//! The sender thread blocks on `TcpListener::accept`, drains the `AppSink`,
//! frames each buffer, and writes it over TCP.

use std::io::Write;
use std::net::{SocketAddr, TcpListener};
use std::sync::mpsc::{self, RecvTimeoutError, TrySendError};
use std::sync::{
    atomic::{AtomicBool, AtomicU64, Ordering},
    Arc, Mutex,
};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use gstreamer as gst;
use gstreamer_app as gst_app;
use rand::Rng;

use crate::cli::ResolvedConfig;
use crate::cli::StreamMode;
use crate::packetize::{build_header, build_hello, send_all_vectored};

use super::HELLO_CODEC_PNG;

#[derive(Clone)]
struct CachedKeyframe {
    pts_us: u64,
    data: Vec<u8>,
}

struct EncodedFrame {
    pts_us: u64,
    is_key: bool,
    data: Vec<u8>,
}

fn sender_queue_capacity(mode: StreamMode) -> usize {
    match mode {
        StreamMode::Ultra => 4,
        StreamMode::Stable => 16,
        StreamMode::Quality => 48,
    }
}

// ── Sender thread ─────────────────────────────────────────────────────────────

/// Spawn the sender thread that:
/// 1. Listens on `port` for a single Android client.
/// 2. Sends HELLO on connection.
/// 3. Pulls frames from `appsink`, wraps them in WBTP headers, and sends them.
/// 4. Falls back to repeating the last keyframe when no new sample arrives.
/// 5. Exits when `stop` is set to `true`.
#[allow(clippy::cognitive_complexity)]
pub fn spawn_sender(
    appsink: gst_app::AppSink,
    port: u16,
    fps: u32,
    cfg: ResolvedConfig,
    stream_mode: StreamMode,
    stop: Arc<AtomicBool>,
    fps_counter: Arc<AtomicU64>,
    codec_flags: u8,
) -> thread::JoinHandle<()> {
    thread::spawn(move || {
        let queue_capacity = sender_queue_capacity(stream_mode);
        let (tx, rx) = mpsc::sync_channel::<EncodedFrame>(queue_capacity);
        let last_keyframe = Arc::new(Mutex::new(None::<CachedKeyframe>));
        let producer_keyframe = last_keyframe.clone();
        let queue_drops = Arc::new(AtomicU64::new(0));
        let producer_drops = queue_drops.clone();
        let queue_depth = Arc::new(AtomicU64::new(0));
        let producer_depth = queue_depth.clone();
        let queue_peak = Arc::new(AtomicU64::new(0));
        let producer_peak = queue_peak.clone();

        let listener =
            TcpListener::bind(SocketAddr::from(([0, 0, 0, 0], port))).expect("bind tcp listener");
        listener.set_nonblocking(true).ok();
        println!("[wbeam-framed] listening on :{port}");
        let fps = fps.max(1);
        let is_png_stream = (codec_flags & HELLO_CODEC_PNG) != 0;
        let frame_budget_ms = ((1_000u64 + (fps as u64 - 1)) / fps as u64).max(1);
        let pull_timeout_ms = if cfg.pull_timeout_ms > 0 {
            cfg.pull_timeout_ms as u64
        } else {
            match stream_mode {
                StreamMode::Ultra => {
                    if is_png_stream {
                        frame_budget_ms.clamp(10, 80)
                    } else {
                        frame_budget_ms.clamp(2, 20)
                    }
                }
                StreamMode::Stable => frame_budget_ms.clamp(10, 50),
                StreamMode::Quality => frame_budget_ms.clamp(20, 120),
            }
        };
        let pull_timeout = Some(gst::ClockTime::from_mseconds(pull_timeout_ms));
        let disconnect_on_timeout = cfg.disconnect_on_timeout;
        let producer_stop = stop.clone();
        let producer_handle = thread::spawn(move || {
            while !producer_stop.load(Ordering::Acquire) {
                let Some(sample) = appsink.try_pull_sample(pull_timeout) else {
                    continue;
                };
                let Some(buf) = sample.buffer() else {
                    continue;
                };
                let map = match buf.map_readable() {
                    Ok(m) => m,
                    Err(_) => continue,
                };
                let data = map.as_slice();
                let pts_us = SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .map(|d| d.as_micros() as u64)
                    .unwrap_or(0);
                let is_key = (codec_flags & HELLO_CODEC_PNG) != 0
                    || !buf.flags().contains(gst::BufferFlags::DELTA_UNIT);
                let frame = EncodedFrame {
                    pts_us,
                    is_key,
                    data: data.to_vec(),
                };

                if is_key {
                    if let Ok(mut guard) = producer_keyframe.lock() {
                        *guard = Some(CachedKeyframe {
                            pts_us,
                            data: frame.data.clone(),
                        });
                    }
                }

                match stream_mode {
                    StreamMode::Ultra => match tx.try_send(frame) {
                        Ok(()) => {
                            let depth = producer_depth.fetch_add(1, Ordering::Relaxed) + 1;
                            producer_peak.fetch_max(depth, Ordering::Relaxed);
                        }
                        Err(TrySendError::Full(_)) => {
                            producer_drops.fetch_add(1, Ordering::Relaxed);
                        }
                        Err(TrySendError::Disconnected(_)) => break,
                    },
                    StreamMode::Stable | StreamMode::Quality => {
                        let mut pending = Some(frame);
                        while let Some(to_send) = pending.take() {
                            match tx.try_send(to_send) {
                                Ok(()) => {
                                    let depth = producer_depth.fetch_add(1, Ordering::Relaxed) + 1;
                                    producer_peak.fetch_max(depth, Ordering::Relaxed);
                                }
                                Err(TrySendError::Full(back)) => {
                                    if producer_stop.load(Ordering::Acquire) {
                                        break;
                                    }
                                    if tx.send(back).is_err() {
                                        return;
                                    }
                                    let depth = producer_depth.fetch_add(1, Ordering::Relaxed) + 1;
                                    producer_peak.fetch_max(depth, Ordering::Relaxed);
                                }
                                Err(TrySendError::Disconnected(_)) => return,
                            }
                        }
                    }
                }
            }
        });

        let mut seq: u32 = 0;

        // Acquire pairs with the Release store on the shutdown writer; cheaper
        // than SeqCst (avoids full fences on ARM/weak-order architectures).
        while !stop.load(Ordering::Acquire) {
            let mut conn = match listener.accept() {
                Ok((s, addr)) => {
                    let _ = s.set_nodelay(true);
                    let _ = s.set_nonblocking(false);
                    let write_timeout_ms = if cfg.write_timeout_ms > 0 {
                        cfg.write_timeout_ms as u64
                    } else if is_png_stream {
                        (frame_budget_ms.saturating_mul(4)).clamp(80, 500)
                    } else {
                        (frame_budget_ms.saturating_mul(2)).clamp(20, 120)
                    };
                    let _ = match stream_mode {
                        StreamMode::Ultra => {
                            let timeout_ms = if is_png_stream {
                                write_timeout_ms
                            } else {
                                (frame_budget_ms.saturating_mul(2)).clamp(20, 80)
                            };
                            s.set_write_timeout(Some(Duration::from_millis(timeout_ms)))
                        }
                        StreamMode::Stable => s.set_write_timeout(Some(Duration::from_millis(
                            (frame_budget_ms.saturating_mul(4)).clamp(40, 200),
                        ))),
                        StreamMode::Quality => s.set_write_timeout(None),
                    };
                    println!("[wbeam-framed] client connected: {addr}");
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
            let hello = build_hello(session_id, codec_flags);
            let _ = conn.write_all(&hello);
            println!("[wbeam-framed] session_id=0x{session_id:016x}");

            if matches!(stream_mode, StreamMode::Ultra) {
                let mut drained = 0u64;
                while rx.try_recv().is_ok() {
                    queue_depth.fetch_sub(1, Ordering::Relaxed);
                    drained += 1;
                }
                if drained > 0 {
                    println!(
                        "[wbeam-framed] ultra reconnect: dropped stale queued frames={drained}"
                    );
                }
            }

            // Send the last keyframe immediately after HELLO so the client
            // can decode from the very first frame without waiting up to one
            // full GOP for the next encoder IDR (up to 1 s at 60 fps/GOP=60).
            if let Ok(guard) = last_keyframe.lock() {
                if let Some(kf) = guard.as_ref() {
                    let kf_header = build_header(seq, kf.pts_us, kf.data.len(), true);
                    if send_all_vectored(&mut conn, &kf_header, &kf.data).is_ok() {
                        seq = seq.wrapping_add(1);
                    }
                }
            }

            let mut frames = 0u64;
            let mut dropped = 0u64;
            let mut send_timeouts = 0u64;
            let mut soft_timeout_key = 0u64;
            let mut soft_timeout_delta = 0u64;
            let mut key_retry_ok = 0u64;
            let mut key_retry_fail = 0u64;
            let mut t0 = Instant::now();

            loop {
                if stop.load(Ordering::Acquire) {
                    break;
                }

                let frame = match rx.recv_timeout(Duration::from_millis(pull_timeout_ms)) {
                    Ok(frame) => {
                        queue_depth.fetch_sub(1, Ordering::Relaxed);
                        frame
                    }
                    Err(RecvTimeoutError::Timeout) => {
                        let elapsed = t0.elapsed().as_secs_f64();
                        if elapsed >= 1.0 {
                            let sent_fps = frames as f64 / elapsed;
                            let pipe_fps = fps_counter.swap(0, Ordering::Relaxed);
                            let qdrop = queue_drops.swap(0, Ordering::Relaxed);
                            let qdepth = queue_depth.load(Ordering::Relaxed);
                            let qpeak = queue_peak.swap(qdepth, Ordering::Relaxed);
                            println!(
                                "[wbeam-framed] pipeline_fps={pipe_fps} sender_fps={sent_fps:.1} timeout_misses={dropped} send_timeouts={send_timeouts} timeout_key={soft_timeout_key} timeout_delta={soft_timeout_delta} key_retry_ok={key_retry_ok} key_retry_fail={key_retry_fail} queue_depth={qdepth} queue_peak={qpeak} queue_drops={qdrop} seq={seq}"
                            );
                            frames = 0;
                            dropped = 0;
                            send_timeouts = 0;
                            soft_timeout_key = 0;
                            soft_timeout_delta = 0;
                            key_retry_ok = 0;
                            key_retry_fail = 0;
                            t0 = Instant::now();
                        }
                        continue;
                    }
                    Err(RecvTimeoutError::Disconnected) => break,
                };

                let header = build_header(seq, frame.pts_us, frame.data.len(), frame.is_key);
                match send_all_vectored(&mut conn, &header, &frame.data) {
                    Ok(()) => {
                        seq = seq.wrapping_add(1);
                        frames += 1;
                    }
                    Err(ref e)
                        if e.kind() == std::io::ErrorKind::WouldBlock
                            || e.kind() == std::io::ErrorKind::TimedOut =>
                    {
                        send_timeouts += 1;
                        if frame.is_key {
                            soft_timeout_key += 1;
                            let mut recovered = false;
                            for _ in 0..2 {
                                match send_all_vectored(&mut conn, &header, &frame.data) {
                                    Ok(()) => {
                                        recovered = true;
                                        break;
                                    }
                                    Err(ref retry_err)
                                        if retry_err.kind() == std::io::ErrorKind::WouldBlock
                                            || retry_err.kind() == std::io::ErrorKind::TimedOut => {
                                    }
                                    Err(retry_err) => {
                                        println!("[wbeam-framed] keyframe retry failed hard: {retry_err}");
                                        break;
                                    }
                                }
                            }
                            if recovered {
                                key_retry_ok += 1;
                                seq = seq.wrapping_add(1);
                                frames += 1;
                                continue;
                            }
                            key_retry_fail += 1;
                            println!(
                                "[wbeam-framed] soft-timeout on keyframe seq={seq} size={} mode={:?}",
                                frame.data.len(),
                                stream_mode
                            );
                        } else {
                            soft_timeout_delta += 1;
                        }
                        if disconnect_on_timeout {
                            println!("[wbeam-framed] timeout -> reconnect client");
                            break;
                        }
                        dropped += 1;
                        continue;
                    }
                    Err(ref e) if e.kind() == std::io::ErrorKind::Interrupted => {
                        continue;
                    }
                    Err(e) => {
                        println!("[wbeam-framed] client disconnected: {e}");
                        break;
                    }
                }

                let elapsed = t0.elapsed().as_secs_f64();
                if elapsed >= 1.0 {
                    let sent_fps = frames as f64 / elapsed;
                    let pipe_fps = fps_counter.swap(0, Ordering::Relaxed);
                    let qdrop = queue_drops.swap(0, Ordering::Relaxed);
                    let qdepth = queue_depth.load(Ordering::Relaxed);
                    let qpeak = queue_peak.swap(qdepth, Ordering::Relaxed);
                    println!(
                        "[wbeam-framed] pipeline_fps={pipe_fps} sender_fps={sent_fps:.1} timeout_misses={dropped} send_timeouts={send_timeouts} timeout_key={soft_timeout_key} timeout_delta={soft_timeout_delta} key_retry_ok={key_retry_ok} key_retry_fail={key_retry_fail} queue_depth={qdepth} queue_peak={qpeak} queue_drops={qdrop} seq={seq}"
                    );
                    frames = 0;
                    dropped = 0;
                    send_timeouts = 0;
                    soft_timeout_key = 0;
                    soft_timeout_delta = 0;
                    key_retry_ok = 0;
                    key_retry_fail = 0;
                    t0 = Instant::now();
                }
            }
        }

        stop.store(true, Ordering::Release);
        let _ = producer_handle.join();
    })
}
