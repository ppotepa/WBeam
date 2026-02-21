//! WBTP framing and TCP sender.
//!
//! Implements the WBTP/1 wire format:
//!   - HELLO (16 bytes) sent on new connection
//!   - Per-frame header (22 bytes) + H.264 NAL payload
//!
//! The sender thread blocks on `TcpListener::accept`, drains the `AppSink`,
//! frames each buffer, and writes it with a single vectored syscall.

use std::io::{IoSlice, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::{
    atomic::{AtomicBool, AtomicU64, Ordering},
    Arc,
};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use gstreamer as gst;
use gstreamer_app as gst_app;
use rand::Rng;
use wbtp_core::{Flags, MAGIC, VERSION};

// ── Protocol constants ────────────────────────────────────────────────────────

const HELLO_MAGIC: &[u8; 4] = b"WBS1";
const HELLO_VERSION: u8 = 0x01;/// HELLO byte[5] codec flag — signals HEVC/H.265 stream to the Android client.
pub const HELLO_CODEC_HEVC: u8 = 0x01;
pub const HELLO_CODEC_PNG: u8 = 0x02;
// ── Header builders ───────────────────────────────────────────────────────────

/// Build a 22-byte WBTP frame header directly into a stack array.
///
/// Wire layout (big-endian, from wbtp-core):
///   [0..4]  magic  [4] version  [5] flags  [6..10] seq
///   [10..18] capture_ts_us  [18..22] payload_len
///
/// No heap allocation: all fields are written with `to_be_bytes()` directly
/// into the returned stack buffer — critical since this runs on every frame.
#[inline]
pub fn build_header(seq: u32, pts_us: u64, payload_len: usize, is_key: bool) -> [u8; 22] {
    let mut h = [0u8; 22];
    h[0..4].copy_from_slice(MAGIC);
    h[4] = VERSION;
    h[5] = if is_key { Flags::KEYFRAME } else { 0 };
    h[6..10].copy_from_slice(&seq.to_be_bytes());
    h[10..18].copy_from_slice(&pts_us.to_be_bytes());
    h[18..22].copy_from_slice(&(payload_len as u32).to_be_bytes());
    h
}

/// Build a 16-byte WBTP HELLO greeting for a new connection.
///
/// `codec_flags`: `0x00` = AVC, `HELLO_CODEC_HEVC` (0x01) = H.265, `HELLO_CODEC_PNG` (0x02) = PNG frames.
/// Direct byte writes — no Cursor, no trait dispatch, no allocations.
#[inline]
pub fn build_hello(session_id: u64, codec_flags: u8) -> [u8; 16] {
    let mut buf = [0u8; 16];
    buf[0..4].copy_from_slice(HELLO_MAGIC);
    buf[4] = HELLO_VERSION;
    buf[5] = codec_flags;
    buf[6..8].copy_from_slice(&16u16.to_be_bytes());
    buf[8..16].copy_from_slice(&session_id.to_be_bytes());
    buf
}

// ── Efficient I/O ─────────────────────────────────────────────────────────────

/// Write `header` followed by `payload` to `stream` using vectored I/O,
/// retrying partial writes until complete or an error occurs.
pub fn send_all_vectored(
    stream: &mut TcpStream,
    header: &[u8],
    payload: &[u8],
) -> std::io::Result<usize> {
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

// ── Sender thread ─────────────────────────────────────────────────────────────

/// Spawn the sender thread that:
/// 1. Listens on `port` for a single Android client.
/// 2. Sends HELLO on connection.
/// 3. Pulls frames from `appsink`, wraps them in WBTP headers, and sends them.
/// 4. Falls back to repeating the last keyframe when no new sample arrives.
/// 5. Exits when `stop` is set to `true`.
pub fn spawn_sender(
    appsink: gst_app::AppSink,
    port: u16,
    fps: u32,
    stop: Arc<AtomicBool>,
    fps_counter: Arc<AtomicU64>,
    codec_flags: u8,
) -> thread::JoinHandle<()> {
    thread::spawn(move || {
        let listener = TcpListener::bind(SocketAddr::from(([0, 0, 0, 0], port)))
            .expect("bind tcp listener");
        listener.set_nonblocking(true).ok();
        println!("[wbeam-framed] listening on :{port}");
        let fps = fps.max(1);
        let pull_timeout_ms = ((1_000u64 + (fps as u64 - 1)) / fps as u64).clamp(2, 20);
        let pull_timeout = Some(gst::ClockTime::from_mseconds(pull_timeout_ms));
        let mut seq: u32 = 0;
        // Persistent buffer: clear()+extend_from_slice() reuses capacity across
        // keyframes instead of allocating a new Vec on every keyframe update.
        let mut last_keyframe: Vec<u8> = Vec::new();
        let mut last_key_pts = 0u64;

        // Acquire pairs with the Release store on the shutdown writer; cheaper
        // than SeqCst (avoids full fences on ARM/weak-order architectures).
        while !stop.load(Ordering::Acquire) {
            let mut conn = match listener.accept() {
                Ok((s, addr)) => {
                    let _ = s.set_nodelay(true);
                    let _ = s.set_nonblocking(false);
                    // 30 ms write timeout: enough headroom for one frame at 60 fps
                    // (16.7 ms) plus OS scheduling jitter, while still bounding
                    // the blocked time when the Android TCP receive buffer is full.
                    let _ = s.set_write_timeout(Some(Duration::from_millis(30)));
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

            // Send the last keyframe immediately after HELLO so the client
            // can decode from the very first frame without waiting up to one
            // full GOP for the next encoder IDR (up to 1 s at 60 fps/GOP=60).
            if !last_keyframe.is_empty() {
                let kf_header = build_header(seq, last_key_pts, last_keyframe.len(), true);
                if send_all_vectored(&mut conn, &kf_header, &last_keyframe).is_ok() {
                    seq = seq.wrapping_add(1);
                }
            }

            let mut frames = 0u64;
            let mut dropped = 0u64;
            let mut partial_writes = 0u64;
            let mut send_timeouts = 0u64;
            let mut t0 = Instant::now();

            loop {
                if stop.load(Ordering::Acquire) {
                    break;
                }
                let sample = appsink.try_pull_sample(pull_timeout);
                let mut pts_us = SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .map(|d| d.as_micros() as u64)
                    .unwrap_or(0);
                let mut sent_frame = false;

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
                        let is_key = (codec_flags & HELLO_CODEC_PNG) != 0
                            || !buf.flags().contains(gst::BufferFlags::DELTA_UNIT);
                        if is_key {
                            // Reuse existing allocation: clear keeps capacity,
                            // extend_from_slice copies without re-allocating
                            // when new keyframe fits in the existing Vec.
                            last_keyframe.clear();
                            last_keyframe.extend_from_slice(data);
                            last_key_pts = pts_us;
                        }

                        let header = build_header(seq, pts_us, data.len(), is_key);
                        let sent = match send_all_vectored(&mut conn, &header, data) {
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
                        if sent < header.len() + data.len() {
                            partial_writes += 1;
                        }
                        sent_frame = true;
                    }
                } else if !last_keyframe.is_empty() {
                    pts_us = last_key_pts;
                    let header = build_header(seq, pts_us, last_keyframe.len(), true);
                    let sent = match send_all_vectored(&mut conn, &header, &last_keyframe) {
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
                    if sent < header.len() + last_keyframe.len() {
                        partial_writes += 1;
                    }
                    sent_frame = true;
                } else {
                    dropped += 1;
                    let elapsed = t0.elapsed().as_secs_f64();
                    if elapsed >= 1.0 {
                        let sent_fps = frames as f64 / elapsed;
                        let pipe_fps = fps_counter.swap(0, Ordering::Relaxed);
                        println!(
                            "[wbeam-framed] pipeline_fps={pipe_fps} sender_fps={sent_fps:.1} timeout_misses={dropped} seq={seq}"
                        );
                        frames = 0;
                        dropped = 0;
                        t0 = Instant::now();
                    }
                    continue;
                }

                if !sent_frame {
                    continue;
                }

                seq = seq.wrapping_add(1);
                frames += 1;
                let elapsed = t0.elapsed().as_secs_f64();
                if elapsed >= 1.0 {
                    let sent_fps = frames as f64 / elapsed;
                    let pipe_fps = fps_counter.swap(0, Ordering::Relaxed);
                    println!(
                        "[wbeam-framed] pipeline_fps={pipe_fps} sender_fps={sent_fps:.1} timeout_misses={dropped} partial_writes={partial_writes} send_timeouts={send_timeouts} seq={seq}"
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
