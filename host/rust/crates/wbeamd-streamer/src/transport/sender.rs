//! WBTP framing and TCP sender.
//!
//! The sender thread blocks on `TcpListener::accept`, drains the `AppSink`,
//! frames each buffer, and writes it over TCP.

use std::io::Write;
use std::net::{SocketAddr, TcpListener, TcpStream};
#[cfg(unix)]
use std::os::fd::AsRawFd;
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
    buffer: gst::Buffer,
}

struct EncodedFrame {
    pts_us: u64,
    is_key: bool,
    buffer: gst::Buffer,
}

fn sender_queue_capacity(mode: StreamMode) -> usize {
    match mode {
        StreamMode::Ultra => 4,
        StreamMode::Stable => 8,
        StreamMode::Quality => 16,
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

enum KeyframeRetryOutcome {
    Recovered,
    Dropped,
}

#[derive(Clone, Copy)]
enum QueueMode {
    DropWhenFull,
    BlockWhenFull,
}

impl SenderRuntime {
    fn queue_mode(self) -> QueueMode {
        match self.stream_mode {
            StreamMode::Ultra => QueueMode::DropWhenFull,
            StreamMode::Stable | StreamMode::Quality => QueueMode::BlockWhenFull,
        }
    }
}

fn producer_frame(
    sample: gst::Sample,
    codec_flags: u8,
    last_keyframe: &Arc<Mutex<Option<CachedKeyframe>>>,
) -> Option<EncodedFrame> {
    let buf = sample.buffer()?;
    let pts_us = buf.pts().map(|t| t.useconds()).unwrap_or_else(|| {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_micros() as u64)
            .unwrap_or(0)
    });
    let is_key =
        (codec_flags & HELLO_CODEC_PNG) != 0 || !buf.flags().contains(gst::BufferFlags::DELTA_UNIT);
    let buffer = buf.to_owned();
    if is_key {
        if let Ok(mut guard) = last_keyframe.lock() {
            *guard = Some(CachedKeyframe {
                pts_us,
                buffer: buffer.clone(),
            });
        }
    }
    Some(EncodedFrame {
        pts_us,
        is_key,
        buffer,
    })
}

fn enqueue_drop_frame(
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

fn enqueue_blocking_frame(
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

fn enqueue_frame(
    tx: &mpsc::SyncSender<EncodedFrame>,
    frame: EncodedFrame,
    queue_mode: QueueMode,
    stop: &Arc<AtomicBool>,
    queue_stats: &QueueStats,
) -> bool {
    match queue_mode {
        QueueMode::DropWhenFull => enqueue_drop_frame(tx, frame, queue_stats),
        QueueMode::BlockWhenFull => enqueue_blocking_frame(tx, frame, stop, queue_stats),
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
        let queue_mode = runtime.queue_mode();
        while !stop.load(Ordering::Acquire) {
            let Some(sample) = appsink.try_pull_sample(runtime.pull_timeout()) else {
                continue;
            };
            let Some(frame) = producer_frame(sample, runtime.codec_flags, &last_keyframe) else {
                continue;
            };
            let queued = enqueue_frame(&tx, frame, queue_mode, &stop, &queue_stats);
            if !queued {
                break;
            }
        }
    })
}

#[cfg(unix)]
fn wait_for_connection(listener: &TcpListener, stop: &Arc<AtomicBool>, timeout: Duration) -> bool {
    let fd = listener.as_raw_fd();
    let timeout_ms = timeout.as_millis().min(i32::MAX as u128) as i32;
    while !stop.load(Ordering::Acquire) {
        let mut pollfd = libc::pollfd {
            fd,
            events: libc::POLLIN,
            revents: 0,
        };
        let result = unsafe { libc::poll(&mut pollfd as *mut libc::pollfd, 1, timeout_ms) };
        if result > 0 {
            return true;
        }
        if result == 0 {
            return false;
        }
        let err = std::io::Error::last_os_error();
        if err.kind() == std::io::ErrorKind::Interrupted {
            continue;
        }
        eprintln!("[wbeam-framed] listener poll error: {err}");
        return false;
    }
    false
}

#[cfg(not(unix))]
fn wait_for_connection(_listener: &TcpListener, stop: &Arc<AtomicBool>, timeout: Duration) -> bool {
    if stop.load(Ordering::Acquire) {
        return false;
    }
    std::thread::sleep(timeout);
    false
}

fn accept_client(
    listener: &TcpListener,
    runtime: SenderRuntime,
    configured_write_timeout_ms: u32,
    stop: &Arc<AtomicBool>,
) -> ConnectionOutcome {
    if !wait_for_connection(listener, stop, Duration::from_millis(100)) {
        return ConnectionOutcome::Retry;
    }
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
        Err(ref err) if err.kind() == std::io::ErrorKind::WouldBlock => ConnectionOutcome::Retry,
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
            let Ok(map) = kf.buffer.map_readable() else {
                return;
            };
            let payload = map.as_slice();
            let kf_header = build_header(*seq, kf.pts_us, payload.len(), true);
            if send_all_vectored(conn, &kf_header, payload).is_ok() {
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

fn retry_keyframe_send(conn: &mut TcpStream, header: &[u8], payload: &[u8]) -> bool {
    for _ in 0..2 {
        match send_all_vectored(conn, header, payload) {
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

fn complete_frame_send(seq: &mut u32, stats: &mut SessionStats) -> FrameSendOutcome {
    *seq = seq.wrapping_add(1);
    stats.frames += 1;
    FrameSendOutcome::Sent
}

fn on_keyframe_timeout(
    conn: &mut TcpStream,
    runtime: SenderRuntime,
    _frame: &EncodedFrame,
    header: &[u8],
    payload: &[u8],
    seq: u32,
    stats: &mut SessionStats,
) -> KeyframeRetryOutcome {
    stats.soft_timeout_key += 1;
    if retry_keyframe_send(conn, header, payload) {
        stats.key_retry_ok += 1;
        return KeyframeRetryOutcome::Recovered;
    }

    stats.key_retry_fail += 1;
    println!(
        "[wbeam-framed] soft-timeout on keyframe seq={} size={} mode={:?}",
        seq,
        payload.len(),
        runtime.stream_mode
    );
    KeyframeRetryOutcome::Dropped
}

fn timeout_send_outcome(
    conn: &mut TcpStream,
    runtime: SenderRuntime,
    frame: &EncodedFrame,
    header: &[u8],
    payload: &[u8],
    seq: &mut u32,
    stats: &mut SessionStats,
) -> FrameSendOutcome {
    stats.send_timeouts += 1;
    if frame.is_key {
        match on_keyframe_timeout(conn, runtime, frame, header, payload, *seq, stats) {
            KeyframeRetryOutcome::Recovered => return complete_frame_send(seq, stats),
            KeyframeRetryOutcome::Dropped => {}
        }
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

fn hard_disconnect(err: std::io::Error) -> FrameSendOutcome {
    println!("WBEAM_EVENT:{{\"event\":\"client_disconnected\",\"reason\":\"{err}\"}}");
    println!("[wbeam-framed] client disconnected: {err}");
    FrameSendOutcome::Disconnect
}

fn send_frame(
    conn: &mut TcpStream,
    runtime: SenderRuntime,
    frame: EncodedFrame,
    seq: &mut u32,
    stats: &mut SessionStats,
) -> FrameSendOutcome {
    let Ok(map) = frame.buffer.map_readable() else {
        stats.dropped += 1;
        return FrameSendOutcome::Continue;
    };
    let payload = map.as_slice();
    let header = build_header(*seq, frame.pts_us, payload.len(), frame.is_key);
    match send_all_vectored(conn, &header, payload) {
        Ok(()) => complete_frame_send(seq, stats),
        Err(ref err)
            if err.kind() == std::io::ErrorKind::WouldBlock
                || err.kind() == std::io::ErrorKind::TimedOut =>
        {
            timeout_send_outcome(conn, runtime, &frame, &header, payload, seq, stats)
        }
        Err(ref err) if err.kind() == std::io::ErrorKind::Interrupted => FrameSendOutcome::Continue,
        Err(err) => hard_disconnect(err),
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

fn run_accept_loop(
    listener: &TcpListener,
    runtime: SenderRuntime,
    configured_write_timeout_ms: u32,
    rx: &mpsc::Receiver<EncodedFrame>,
    last_keyframe: &Arc<Mutex<Option<CachedKeyframe>>>,
    stop: &Arc<AtomicBool>,
    fps_counter: &Arc<AtomicU64>,
    queue_stats: &QueueStats,
) {
    while !stop.load(Ordering::Acquire) {
        let conn = match accept_client(listener, runtime, configured_write_timeout_ms, stop) {
            ConnectionOutcome::Connected(conn) => conn,
            ConnectionOutcome::Retry => continue,
            ConnectionOutcome::Fatal => break,
        };
        run_client_session(
            conn,
            runtime,
            rx,
            last_keyframe,
            stop,
            fps_counter,
            queue_stats,
        );
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
        run_accept_loop(
            &listener,
            runtime,
            cfg.write_timeout_ms,
            &rx,
            &last_keyframe,
            &stop,
            &fps_counter,
            &queue_stats,
        );

        stop.store(true, Ordering::Release);
        let _ = producer_handle.join();
    })
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

    #[test]
    fn enqueue_drop_mode_counts_drop_without_blocking() {
        init_gst();
        let (tx, rx) = mpsc::sync_channel::<EncodedFrame>(1);
        let queue_stats = QueueStats::new();
        let stop = Arc::new(AtomicBool::new(false));
        let frame = EncodedFrame {
            pts_us: 1,
            is_key: false,
            buffer: gst::Buffer::from_slice(vec![1u8, 2, 3]),
        };
        let another = EncodedFrame {
            pts_us: 2,
            is_key: false,
            buffer: gst::Buffer::from_slice(vec![4u8, 5, 6]),
        };

        assert!(enqueue_frame(
            &tx,
            frame,
            QueueMode::DropWhenFull,
            &stop,
            &queue_stats
        ));
        assert!(enqueue_frame(
            &tx,
            another,
            QueueMode::DropWhenFull,
            &stop,
            &queue_stats
        ));

        assert!(rx.try_recv().is_ok());
        let (_, _, drops) = queue_stats.snapshot_and_reset();
        assert_eq!(drops, 1);
    }

    #[test]
    fn enqueue_blocking_mode_stops_when_requested() {
        init_gst();
        let (tx, _rx) = mpsc::sync_channel::<EncodedFrame>(1);
        let queue_stats = QueueStats::new();
        let stop = Arc::new(AtomicBool::new(false));
        let first = EncodedFrame {
            pts_us: 1,
            is_key: false,
            buffer: gst::Buffer::from_slice(vec![1u8]),
        };
        let second = EncodedFrame {
            pts_us: 2,
            is_key: false,
            buffer: gst::Buffer::from_slice(vec![2u8]),
        };

        assert!(enqueue_frame(
            &tx,
            first,
            QueueMode::BlockWhenFull,
            &stop,
            &queue_stats
        ));
        stop.store(true, Ordering::Release);
        assert!(!enqueue_frame(
            &tx,
            second,
            QueueMode::BlockWhenFull,
            &stop,
            &queue_stats
        ));
    }
}
