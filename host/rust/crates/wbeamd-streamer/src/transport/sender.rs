//! WBTP framing and TCP sender.
//!
//! The sender thread blocks on `TcpListener::accept`, drains the `AppSink`,
//! frames each buffer, and writes it over TCP.

use std::io::Write;
use std::net::{SocketAddr, TcpListener, TcpStream};
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

// Arc<[u8]> shares the encoded payload between producer (keyframe cache) and
// sender without a second heap allocation. Cloning the Arc is an atomic
// increment, replacing the previous data.clone() on every keyframe.
#[derive(Clone)]
struct CachedKeyframe {
    pts_us: u64,
    data: Arc<[u8]>,
}

struct EncodedFrame {
    pts_us: u64,
    is_key: bool,
    data: Arc<[u8]>,
}

fn sender_queue_capacity(mode: StreamMode) -> usize {
    match mode {
        StreamMode::Ultra => 4,
        StreamMode::Stable => 16,
        StreamMode::Quality => 48,
    }
}

#[derive(Clone)]
struct QueueStats {
    drops: Arc<AtomicU64>,
    depth: Arc<AtomicU64>,
    peak: Arc<AtomicU64>,
}

impl QueueStats {
    fn new() -> Self {
        Self {
            drops: Arc::new(AtomicU64::new(0)),
            depth: Arc::new(AtomicU64::new(0)),
            peak: Arc::new(AtomicU64::new(0)),
        }
    }

    fn record_enqueue(&self) {
        let depth = self.depth.fetch_add(1, Ordering::Relaxed) + 1;
        self.peak.fetch_max(depth, Ordering::Relaxed);
    }

    fn record_drop(&self) {
        self.drops.fetch_add(1, Ordering::Relaxed);
    }

    fn record_dequeue(&self) {
        self.depth.fetch_sub(1, Ordering::Relaxed);
    }

    fn snapshot_and_reset(&self) -> (u64, u64, u64) {
        let depth = self.depth.load(Ordering::Relaxed);
        let peak = self.peak.swap(depth, Ordering::Relaxed);
        let drops = self.drops.swap(0, Ordering::Relaxed);
        (depth, peak, drops)
    }
}

#[derive(Clone, Copy)]
struct SenderRuntime {
    stream_mode: StreamMode,
    codec_flags: u8,
    fps: u32,
    is_png_stream: bool,
    frame_budget_ms: u64,
    pull_timeout_ms: u64,
    disconnect_on_timeout: bool,
    width: u32,
    height: u32,
}

impl SenderRuntime {
    fn new(fps: u32, cfg: &ResolvedConfig, stream_mode: StreamMode, codec_flags: u8) -> Self {
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

        Self {
            stream_mode,
            codec_flags,
            fps,
            is_png_stream,
            frame_budget_ms,
            pull_timeout_ms,
            disconnect_on_timeout: cfg.disconnect_on_timeout,
            width: cfg.width,
            height: cfg.height,
        }
    }

    fn pull_timeout(&self) -> Option<gst::ClockTime> {
        Some(gst::ClockTime::from_mseconds(self.pull_timeout_ms))
    }

    fn write_timeout_ms(&self, configured_ms: u32) -> Option<u64> {
        if configured_ms > 0 {
            return Some(configured_ms as u64);
        }
        match self.stream_mode {
            StreamMode::Ultra => Some(if self.is_png_stream {
                self.frame_budget_ms.clamp(80, 500)
            } else {
                (self.frame_budget_ms.saturating_mul(2)).clamp(20, 80)
            }),
            StreamMode::Stable => Some((self.frame_budget_ms.saturating_mul(4)).clamp(40, 200)),
            StreamMode::Quality => {
                if self.is_png_stream {
                    Some((self.frame_budget_ms.saturating_mul(4)).clamp(80, 500))
                } else {
                    None
                }
            }
        }
    }
}

struct SessionStats {
    frames: u64,
    dropped: u64,
    send_timeouts: u64,
    soft_timeout_key: u64,
    soft_timeout_delta: u64,
    key_retry_ok: u64,
    key_retry_fail: u64,
    started_at: Instant,
}

impl SessionStats {
    fn new() -> Self {
        Self {
            frames: 0,
            dropped: 0,
            send_timeouts: 0,
            soft_timeout_key: 0,
            soft_timeout_delta: 0,
            key_retry_ok: 0,
            key_retry_fail: 0,
            started_at: Instant::now(),
        }
    }

    fn maybe_emit(&mut self, fps_counter: &Arc<AtomicU64>, queue_stats: &QueueStats, seq: u32) {
        let elapsed = self.started_at.elapsed().as_secs_f64();
        if elapsed < 1.0 {
            return;
        }

        let sent_fps = self.frames as f64 / elapsed;
        let pipe_fps = fps_counter.swap(0, Ordering::Relaxed);
        let (qdepth, qpeak, qdrop) = queue_stats.snapshot_and_reset();
        println!(
            "[wbeam-framed] pipeline_fps={pipe_fps} sender_fps={sent_fps:.1} timeout_misses={} send_timeouts={} timeout_key={} timeout_delta={} key_retry_ok={} key_retry_fail={} queue_depth={qdepth} queue_peak={qpeak} queue_drops={qdrop} seq={seq}",
            self.dropped,
            self.send_timeouts,
            self.soft_timeout_key,
            self.soft_timeout_delta,
            self.key_retry_ok,
            self.key_retry_fail
        );
        println!(
            "WBEAM_EVENT:{{\"event\":\"transport_stats\",\"pipeline_fps\":{pipe_fps},\"sender_fps\":{sent_fps:.1},\"timeout_misses\":{},\"send_timeouts\":{},\"timeout_key\":{},\"timeout_delta\":{},\"key_retry_ok\":{},\"key_retry_fail\":{},\"queue_depth\":{qdepth},\"queue_peak\":{qpeak},\"queue_drops\":{qdrop},\"seq\":{seq}}}",
            self.dropped,
            self.send_timeouts,
            self.soft_timeout_key,
            self.soft_timeout_delta,
            self.key_retry_ok,
            self.key_retry_fail
        );
        self.frames = 0;
        self.dropped = 0;
        self.send_timeouts = 0;
        self.soft_timeout_key = 0;
        self.soft_timeout_delta = 0;
        self.key_retry_ok = 0;
        self.key_retry_fail = 0;
        self.started_at = Instant::now();
    }
}

enum ConnectionOutcome {
    Retry,
    Connected(TcpStream),
    Fatal,
}

enum FrameSendOutcome {
    Sent,
    Continue,
    Disconnect,
}

fn producer_frame(
    sample: gst::Sample,
    codec_flags: u8,
    last_keyframe: &Arc<Mutex<Option<CachedKeyframe>>>,
) -> Option<EncodedFrame> {
    let buf = sample.buffer()?;
    let map = buf.map_readable().ok()?;
    let data = map.as_slice();
    let pts_us = buf.pts().map(|t| t.useconds()).unwrap_or_else(|| {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_micros() as u64)
            .unwrap_or(0)
    });
    let is_key =
        (codec_flags & HELLO_CODEC_PNG) != 0 || !buf.flags().contains(gst::BufferFlags::DELTA_UNIT);
    let data_arc: Arc<[u8]> = Arc::from(data);
    if is_key {
        if let Ok(mut guard) = last_keyframe.lock() {
            *guard = Some(CachedKeyframe {
                pts_us,
                data: data_arc.clone(),
            });
        }
    }
    Some(EncodedFrame {
        pts_us,
        is_key,
        data: data_arc,
    })
}

fn enqueue_ultra_frame(
    tx: &mpsc::SyncSender<EncodedFrame>,
    frame: EncodedFrame,
    queue_stats: &QueueStats,
) -> bool {
    match tx.try_send(frame) {
        Ok(()) => {
            queue_stats.record_enqueue();
            true
        }
        Err(TrySendError::Full(_)) => {
            queue_stats.record_drop();
            true
        }
        Err(TrySendError::Disconnected(_)) => false,
    }
}

fn enqueue_buffered_frame(
    tx: &mpsc::SyncSender<EncodedFrame>,
    frame: EncodedFrame,
    stop: &Arc<AtomicBool>,
    queue_stats: &QueueStats,
) -> bool {
    match tx.try_send(frame) {
        Ok(()) => {
            queue_stats.record_enqueue();
            true
        }
        Err(TrySendError::Full(back)) => {
            if stop.load(Ordering::Acquire) {
                return false;
            }
            if tx.send(back).is_err() {
                return false;
            }
            queue_stats.record_enqueue();
            true
        }
        Err(TrySendError::Disconnected(_)) => false,
    }
}

fn spawn_producer(
    appsink: gst_app::AppSink,
    runtime: SenderRuntime,
    stop: Arc<AtomicBool>,
    tx: mpsc::SyncSender<EncodedFrame>,
    last_keyframe: Arc<Mutex<Option<CachedKeyframe>>>,
    queue_stats: QueueStats,
) -> thread::JoinHandle<()> {
    thread::spawn(move || {
        while !stop.load(Ordering::Acquire) {
            let Some(sample) = appsink.try_pull_sample(runtime.pull_timeout()) else {
                continue;
            };
            let Some(frame) = producer_frame(sample, runtime.codec_flags, &last_keyframe) else {
                continue;
            };
            let queued = match runtime.stream_mode {
                StreamMode::Ultra => enqueue_ultra_frame(&tx, frame, &queue_stats),
                StreamMode::Stable | StreamMode::Quality => {
                    enqueue_buffered_frame(&tx, frame, &stop, &queue_stats)
                }
            };
            if !queued {
                break;
            }
        }
    })
}

fn accept_client(
    listener: &TcpListener,
    runtime: SenderRuntime,
    configured_write_timeout_ms: u32,
) -> ConnectionOutcome {
    match listener.accept() {
        Ok((stream, addr)) => {
            let _ = stream.set_nodelay(true);
            let _ = stream.set_nonblocking(false);
            let _ = stream.set_write_timeout(
                runtime
                    .write_timeout_ms(configured_write_timeout_ms)
                    .map(Duration::from_millis),
            );
            println!("WBEAM_EVENT:{{\"event\":\"client_connected\",\"addr\":\"{addr}\"}}");
            println!("[wbeam-framed] client connected: {addr}");
            ConnectionOutcome::Connected(stream)
        }
        Err(ref err) if err.kind() == std::io::ErrorKind::WouldBlock => {
            std::thread::sleep(Duration::from_millis(10));
            ConnectionOutcome::Retry
        }
        Err(err) => {
            eprintln!("[wbeam-framed] accept error: {err}");
            ConnectionOutcome::Fatal
        }
    }
}

fn drain_ultra_reconnect_queue(
    runtime: SenderRuntime,
    rx: &mpsc::Receiver<EncodedFrame>,
    queue_stats: &QueueStats,
) {
    if !matches!(runtime.stream_mode, StreamMode::Ultra) {
        return;
    }

    let mut drained = 0u64;
    while rx.try_recv().is_ok() {
        queue_stats.record_dequeue();
        drained += 1;
    }
    if drained > 0 {
        println!("[wbeam-framed] ultra reconnect: dropped stale queued frames={drained}");
    }
}

fn send_cached_keyframe(
    conn: &mut TcpStream,
    last_keyframe: &Arc<Mutex<Option<CachedKeyframe>>>,
    seq: &mut u32,
) {
    if let Ok(guard) = last_keyframe.lock() {
        if let Some(kf) = guard.as_ref() {
            let kf_header = build_header(*seq, kf.pts_us, kf.data.len(), true);
            if send_all_vectored(conn, &kf_header, &kf.data).is_ok() {
                *seq = seq.wrapping_add(1);
            }
        }
    }
}

fn recv_frame(
    rx: &mpsc::Receiver<EncodedFrame>,
    queue_stats: &QueueStats,
    timeout_ms: u64,
) -> Option<Option<EncodedFrame>> {
    match rx.recv_timeout(Duration::from_millis(timeout_ms)) {
        Ok(frame) => {
            queue_stats.record_dequeue();
            Some(Some(frame))
        }
        Err(RecvTimeoutError::Timeout) => Some(None),
        Err(RecvTimeoutError::Disconnected) => None,
    }
}

fn retry_keyframe_send(conn: &mut TcpStream, header: &[u8], frame: &EncodedFrame) -> bool {
    for _ in 0..2 {
        match send_all_vectored(conn, header, &frame.data) {
            Ok(()) => return true,
            Err(ref retry_err)
                if retry_err.kind() == std::io::ErrorKind::WouldBlock
                    || retry_err.kind() == std::io::ErrorKind::TimedOut => {}
            Err(retry_err) => {
                println!("[wbeam-framed] keyframe retry failed hard: {retry_err}");
                return false;
            }
        }
    }
    false
}

fn send_frame(
    conn: &mut TcpStream,
    runtime: SenderRuntime,
    frame: EncodedFrame,
    seq: &mut u32,
    stats: &mut SessionStats,
) -> FrameSendOutcome {
    let header = build_header(*seq, frame.pts_us, frame.data.len(), frame.is_key);
    match send_all_vectored(conn, &header, &frame.data) {
        Ok(()) => {
            *seq = seq.wrapping_add(1);
            stats.frames += 1;
            FrameSendOutcome::Sent
        }
        Err(ref err)
            if err.kind() == std::io::ErrorKind::WouldBlock
                || err.kind() == std::io::ErrorKind::TimedOut =>
        {
            stats.send_timeouts += 1;
            if frame.is_key {
                stats.soft_timeout_key += 1;
                if retry_keyframe_send(conn, &header, &frame) {
                    stats.key_retry_ok += 1;
                    *seq = seq.wrapping_add(1);
                    stats.frames += 1;
                    return FrameSendOutcome::Sent;
                }
                stats.key_retry_fail += 1;
                println!(
                    "[wbeam-framed] soft-timeout on keyframe seq={} size={} mode={:?}",
                    *seq,
                    frame.data.len(),
                    runtime.stream_mode
                );
            } else {
                stats.soft_timeout_delta += 1;
            }
            if runtime.disconnect_on_timeout {
                println!("[wbeam-framed] timeout -> reconnect client");
                return FrameSendOutcome::Disconnect;
            }
            stats.dropped += 1;
            FrameSendOutcome::Continue
        }
        Err(ref err) if err.kind() == std::io::ErrorKind::Interrupted => FrameSendOutcome::Continue,
        Err(err) => {
            println!("WBEAM_EVENT:{{\"event\":\"client_disconnected\",\"reason\":\"{err}\"}}");
            println!("[wbeam-framed] client disconnected: {err}");
            FrameSendOutcome::Disconnect
        }
    }
}

fn run_client_session(
    mut conn: TcpStream,
    runtime: SenderRuntime,
    rx: &mpsc::Receiver<EncodedFrame>,
    last_keyframe: &Arc<Mutex<Option<CachedKeyframe>>>,
    stop: &Arc<AtomicBool>,
    fps_counter: &Arc<AtomicU64>,
    queue_stats: &QueueStats,
) {
    let session_id: u64 = rand::thread_rng().gen();
    let hello = build_hello(
        session_id,
        runtime.codec_flags,
        runtime.width,
        runtime.height,
        runtime.fps,
    );
    let _ = conn.write_all(&hello);
    println!("[wbeam-framed] session_id=0x{session_id:016x}");

    drain_ultra_reconnect_queue(runtime, rx, queue_stats);

    let mut seq = 0u32;
    send_cached_keyframe(&mut conn, last_keyframe, &mut seq);
    let mut stats = SessionStats::new();

    while !stop.load(Ordering::Acquire) {
        let Some(frame_result) = recv_frame(rx, queue_stats, runtime.pull_timeout_ms) else {
            break;
        };
        let Some(frame) = frame_result else {
            stats.maybe_emit(fps_counter, queue_stats, seq);
            continue;
        };
        match send_frame(&mut conn, runtime, frame, &mut seq, &mut stats) {
            FrameSendOutcome::Sent | FrameSendOutcome::Continue => {
                stats.maybe_emit(fps_counter, queue_stats, seq);
            }
            FrameSendOutcome::Disconnect => break,
        }
    }
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
        let queue_stats = QueueStats::new();
        let runtime = SenderRuntime::new(fps, &cfg, stream_mode, codec_flags);

        let listener =
            TcpListener::bind(SocketAddr::from(([0, 0, 0, 0], port))).expect("bind tcp listener");
        listener.set_nonblocking(true).ok();
        println!("[wbeam-framed] listening on :{port}");
        let producer_handle = spawn_producer(
            appsink,
            runtime,
            stop.clone(),
            tx,
            last_keyframe.clone(),
            queue_stats.clone(),
        );

        // Acquire pairs with the Release store on the shutdown writer; cheaper
        // than SeqCst (avoids full fences on ARM/weak-order architectures).
        while !stop.load(Ordering::Acquire) {
            let conn = match accept_client(&listener, runtime, cfg.write_timeout_ms) {
                ConnectionOutcome::Connected(conn) => conn,
                ConnectionOutcome::Retry => continue,
                ConnectionOutcome::Fatal => break,
            };
            run_client_session(
                conn,
                runtime,
                &rx,
                &last_keyframe,
                &stop,
                &fps_counter,
                &queue_stats,
            );
            if stop.load(Ordering::Acquire) {
                break;
            }
        }

        stop.store(true, Ordering::Release);
        let _ = producer_handle.join();
    })
}
