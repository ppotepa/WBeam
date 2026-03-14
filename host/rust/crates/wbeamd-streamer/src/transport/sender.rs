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

struct SenderStats {
    frames: u64,
    dropped: u64,
    send_timeouts: u64,
    soft_timeout_key: u64,
    soft_timeout_delta: u64,
    key_retry_ok: u64,
    key_retry_fail: u64,
    started_at: Instant,
}

enum AcceptOutcome {
    Connected(TcpStream),
    Retry,
    Exit,
}

enum NextFrame {
    Frame(EncodedFrame),
    Continue,
    Break,
}

enum SendOutcome {
    Sent,
    Dropped,
    Continue,
    Reconnect,
}

impl SenderStats {
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

    fn note_sent(&mut self) {
        self.frames += 1;
    }

    fn note_dropped(&mut self) {
        self.dropped += 1;
    }

    fn note_send_timeout(&mut self) {
        self.send_timeouts += 1;
    }

    fn note_soft_timeout(&mut self, is_key: bool) {
        if is_key {
            self.soft_timeout_key += 1;
        } else {
            self.soft_timeout_delta += 1;
        }
    }

    fn note_key_retry(&mut self, recovered: bool) {
        if recovered {
            self.key_retry_ok += 1;
        } else {
            self.key_retry_fail += 1;
        }
    }

    fn report_if_due(
        &mut self,
        fps_counter: &AtomicU64,
        queue_drops: &AtomicU64,
        queue_depth: &AtomicU64,
        queue_peak: &AtomicU64,
        seq: u32,
    ) {
        let elapsed = self.started_at.elapsed().as_secs_f64();
        if elapsed < 1.0 {
            return;
        }

        let sent_fps = self.frames as f64 / elapsed;
        let pipe_fps = fps_counter.swap(0, Ordering::Relaxed);
        let qdrop = queue_drops.swap(0, Ordering::Relaxed);
        let qdepth = queue_depth.load(Ordering::Relaxed);
        let qpeak = queue_peak.swap(qdepth, Ordering::Relaxed);
        println!(
            "[wbeam-framed] pipeline_fps={pipe_fps} sender_fps={sent_fps:.1} timeout_misses={} send_timeouts={} timeout_key={} timeout_delta={} key_retry_ok={} key_retry_fail={} queue_depth={qdepth} queue_peak={qpeak} queue_drops={qdrop} seq={seq}",
            self.dropped,
            self.send_timeouts,
            self.soft_timeout_key,
            self.soft_timeout_delta,
            self.key_retry_ok,
            self.key_retry_fail,
        );

        *self = Self::new();
    }
}

fn sender_queue_capacity(mode: StreamMode) -> usize {
    match mode {
        StreamMode::Ultra => 4,
        StreamMode::Stable => 16,
        StreamMode::Quality => 48,
    }
}

fn pull_timeout_ms(
    cfg: &ResolvedConfig,
    stream_mode: StreamMode,
    frame_budget_ms: u64,
    is_png_stream: bool,
) -> u64 {
    if cfg.pull_timeout_ms > 0 {
        return cfg.pull_timeout_ms as u64;
    }

    match stream_mode {
        StreamMode::Ultra if is_png_stream => frame_budget_ms.clamp(10, 80),
        StreamMode::Ultra => frame_budget_ms.clamp(2, 20),
        StreamMode::Stable => frame_budget_ms.clamp(10, 50),
        StreamMode::Quality => frame_budget_ms.clamp(20, 120),
    }
}

fn connection_write_timeout(
    cfg: &ResolvedConfig,
    stream_mode: StreamMode,
    frame_budget_ms: u64,
    is_png_stream: bool,
) -> Option<Duration> {
    let cfg_timeout_ms = if cfg.write_timeout_ms > 0 {
        cfg.write_timeout_ms as u64
    } else if is_png_stream {
        (frame_budget_ms.saturating_mul(4)).clamp(80, 500)
    } else {
        (frame_budget_ms.saturating_mul(2)).clamp(20, 120)
    };

    match stream_mode {
        StreamMode::Ultra if is_png_stream => Some(Duration::from_millis(cfg_timeout_ms)),
        StreamMode::Ultra => Some(Duration::from_millis(
            (frame_budget_ms.saturating_mul(2)).clamp(20, 80),
        )),
        StreamMode::Stable => Some(Duration::from_millis(
            (frame_budget_ms.saturating_mul(4)).clamp(40, 200),
        )),
        StreamMode::Quality => None,
    }
}

fn current_pts_us() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_micros() as u64)
        .unwrap_or(0)
}

fn update_queue_depth(depth: &AtomicU64, peak: &AtomicU64) {
    let current = depth.fetch_add(1, Ordering::Relaxed) + 1;
    peak.fetch_max(current, Ordering::Relaxed);
}

fn cache_keyframe(last_keyframe: &Arc<Mutex<Option<CachedKeyframe>>>, frame: &EncodedFrame) {
    if let Ok(mut guard) = last_keyframe.lock() {
        *guard = Some(CachedKeyframe {
            pts_us: frame.pts_us,
            data: frame.data.clone(),
        });
    }
}

fn enqueue_ultra_frame(
    tx: &mpsc::SyncSender<EncodedFrame>,
    frame: EncodedFrame,
    producer_depth: &Arc<AtomicU64>,
    producer_peak: &Arc<AtomicU64>,
    producer_drops: &Arc<AtomicU64>,
) -> bool {
    match tx.try_send(frame) {
        Ok(()) => {
            update_queue_depth(producer_depth, producer_peak);
            true
        }
        Err(TrySendError::Full(_)) => {
            producer_drops.fetch_add(1, Ordering::Relaxed);
            true
        }
        Err(TrySendError::Disconnected(_)) => false,
    }
}

fn enqueue_blocking_frame(
    tx: &mpsc::SyncSender<EncodedFrame>,
    frame: EncodedFrame,
    producer_stop: &Arc<AtomicBool>,
    producer_depth: &Arc<AtomicU64>,
    producer_peak: &Arc<AtomicU64>,
) -> bool {
    match tx.try_send(frame) {
        Ok(()) => {
            update_queue_depth(producer_depth, producer_peak);
            true
        }
        Err(TrySendError::Full(back)) => {
            if producer_stop.load(Ordering::Acquire) {
                return true;
            }
            if tx.send(back).is_err() {
                return false;
            }
            update_queue_depth(producer_depth, producer_peak);
            true
        }
        Err(TrySendError::Disconnected(_)) => false,
    }
}

fn enqueue_frame(
    tx: &mpsc::SyncSender<EncodedFrame>,
    frame: EncodedFrame,
    stream_mode: StreamMode,
    producer_stop: &Arc<AtomicBool>,
    producer_depth: &Arc<AtomicU64>,
    producer_peak: &Arc<AtomicU64>,
    producer_drops: &Arc<AtomicU64>,
) -> bool {
    match stream_mode {
        StreamMode::Ultra => enqueue_ultra_frame(
            tx,
            frame,
            producer_depth,
            producer_peak,
            producer_drops,
        ),
        StreamMode::Stable | StreamMode::Quality => enqueue_blocking_frame(
            tx,
            frame,
            producer_stop,
            producer_depth,
            producer_peak,
        ),
    }
}

fn spawn_frame_producer(
    appsink: gst_app::AppSink,
    pull_timeout: Option<gst::ClockTime>,
    codec_flags: u8,
    stream_mode: StreamMode,
    stop: Arc<AtomicBool>,
    tx: mpsc::SyncSender<EncodedFrame>,
    last_keyframe: Arc<Mutex<Option<CachedKeyframe>>>,
    queue_drops: Arc<AtomicU64>,
    queue_depth: Arc<AtomicU64>,
    queue_peak: Arc<AtomicU64>,
) -> thread::JoinHandle<()> {
    thread::spawn(move || {
        while !stop.load(Ordering::Acquire) {
            let Some(sample) = appsink.try_pull_sample(pull_timeout) else {
                continue;
            };
            let Some(buffer) = sample.buffer() else {
                continue;
            };
            let Ok(map) = buffer.map_readable() else {
                continue;
            };

            let frame = EncodedFrame {
                pts_us: current_pts_us(),
                is_key: (codec_flags & HELLO_CODEC_PNG) != 0
                    || !buffer.flags().contains(gst::BufferFlags::DELTA_UNIT),
                data: map.as_slice().to_vec(),
            };
            if frame.is_key {
                cache_keyframe(&last_keyframe, &frame);
            }
            if !enqueue_frame(
                &tx,
                frame,
                stream_mode,
                &stop,
                &queue_depth,
                &queue_peak,
                &queue_drops,
            ) {
                return;
            }
        }
    })
}

fn accept_client(
    listener: &TcpListener,
    cfg: &ResolvedConfig,
    stream_mode: StreamMode,
    frame_budget_ms: u64,
    is_png_stream: bool,
) -> AcceptOutcome {
    match listener.accept() {
        Ok((stream, addr)) => {
            let _ = stream.set_nodelay(true);
            let _ = stream.set_nonblocking(false);
            let _ = stream.set_write_timeout(connection_write_timeout(
                cfg,
                stream_mode,
                frame_budget_ms,
                is_png_stream,
            ));
            println!("[wbeam-framed] client connected: {addr}");
            AcceptOutcome::Connected(stream)
        }
        Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
            thread::sleep(Duration::from_millis(10));
            AcceptOutcome::Retry
        }
        Err(error) => {
            eprintln!("[wbeam-framed] accept error: {error}");
            AcceptOutcome::Exit
        }
    }
}

fn drain_stale_frames(rx: &mpsc::Receiver<EncodedFrame>, queue_depth: &AtomicU64) -> u64 {
    let mut drained = 0;
    while rx.try_recv().is_ok() {
        queue_depth.fetch_sub(1, Ordering::Relaxed);
        drained += 1;
    }
    drained
}

fn send_cached_keyframe(
    conn: &mut TcpStream,
    last_keyframe: &Arc<Mutex<Option<CachedKeyframe>>>,
    seq: &mut u32,
) {
    if let Ok(guard) = last_keyframe.lock() {
        if let Some(keyframe) = guard.as_ref() {
            let header = build_header(*seq, keyframe.pts_us, keyframe.data.len(), true);
            if send_all_vectored(conn, &header, &keyframe.data).is_ok() {
                *seq = seq.wrapping_add(1);
            }
        }
    }
}

fn next_frame(
    rx: &mpsc::Receiver<EncodedFrame>,
    pull_timeout_ms: u64,
    queue_depth: &AtomicU64,
    stats: &mut SenderStats,
    fps_counter: &AtomicU64,
    queue_drops: &AtomicU64,
    queue_peak: &AtomicU64,
    seq: u32,
) -> NextFrame {
    match rx.recv_timeout(Duration::from_millis(pull_timeout_ms)) {
        Ok(frame) => {
            queue_depth.fetch_sub(1, Ordering::Relaxed);
            NextFrame::Frame(frame)
        }
        Err(RecvTimeoutError::Timeout) => {
            stats.report_if_due(fps_counter, queue_drops, queue_depth, queue_peak, seq);
            NextFrame::Continue
        }
        Err(RecvTimeoutError::Disconnected) => NextFrame::Break,
    }
}

fn retry_keyframe_send(conn: &mut TcpStream, header: &[u8], data: &[u8]) -> bool {
    for _ in 0..2 {
        match send_all_vectored(conn, header, data) {
            Ok(()) => return true,
            Err(error)
                if matches!(
                    error.kind(),
                    std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
                ) => {}
            Err(error) => {
                println!("[wbeam-framed] keyframe retry failed hard: {error}");
                return false;
            }
        }
    }
    false
}

fn send_frame(
    conn: &mut TcpStream,
    frame: &EncodedFrame,
    seq: &mut u32,
    stream_mode: StreamMode,
    disconnect_on_timeout: bool,
    stats: &mut SenderStats,
) -> SendOutcome {
    let header = build_header(*seq, frame.pts_us, frame.data.len(), frame.is_key);
    match send_all_vectored(conn, &header, &frame.data) {
        Ok(()) => {
            *seq = seq.wrapping_add(1);
            stats.note_sent();
            SendOutcome::Sent
        }
        Err(error)
            if matches!(
                error.kind(),
                std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
            ) =>
        {
            stats.note_send_timeout();
            stats.note_soft_timeout(frame.is_key);
            if frame.is_key {
                let recovered = retry_keyframe_send(conn, &header, &frame.data);
                stats.note_key_retry(recovered);
                if recovered {
                    *seq = seq.wrapping_add(1);
                    stats.note_sent();
                    return SendOutcome::Sent;
                }
                println!(
                    "[wbeam-framed] soft-timeout on keyframe seq={} size={} mode={:?}",
                    *seq,
                    frame.data.len(),
                    stream_mode
                );
            }
            if disconnect_on_timeout {
                println!("[wbeam-framed] timeout -> reconnect client");
                SendOutcome::Reconnect
            } else {
                stats.note_dropped();
                SendOutcome::Dropped
            }
        }
        Err(error) if error.kind() == std::io::ErrorKind::Interrupted => SendOutcome::Continue,
        Err(error) => {
            println!("[wbeam-framed] client disconnected: {error}");
            SendOutcome::Reconnect
        }
    }
}

// ── Sender thread ─────────────────────────────────────────────────────────────

fn log_ultra_reconnect_drops(
    stream_mode: StreamMode,
    rx: &mpsc::Receiver<EncodedFrame>,
    queue_depth: &Arc<AtomicU64>,
) {
    if !matches!(stream_mode, StreamMode::Ultra) {
        return;
    }
    let drained = drain_stale_frames(rx, queue_depth);
    if drained > 0 {
        println!("[wbeam-framed] ultra reconnect: dropped stale queued frames={drained}");
    }
}

fn run_client_session(
    conn: &mut TcpStream,
    rx: &mpsc::Receiver<EncodedFrame>,
    pull_timeout_ms: u64,
    last_keyframe: &Arc<Mutex<Option<CachedKeyframe>>>,
    stop: &Arc<AtomicBool>,
    fps_counter: &Arc<AtomicU64>,
    queue_drops: &Arc<AtomicU64>,
    queue_depth: &Arc<AtomicU64>,
    queue_peak: &Arc<AtomicU64>,
    stream_mode: StreamMode,
    disconnect_on_timeout: bool,
    codec_flags: u8,
    seq: &mut u32,
) {
    let session_id: u64 = rand::thread_rng().gen();
    let hello = build_hello(session_id, codec_flags);
    let _ = conn.write_all(&hello);
    println!("[wbeam-framed] session_id=0x{session_id:016x}");

    log_ultra_reconnect_drops(stream_mode, rx, queue_depth);
    send_cached_keyframe(conn, last_keyframe, seq);
    let mut stats = SenderStats::new();

    loop {
        if stop.load(Ordering::Acquire) {
            break;
        }

        let frame = match next_frame(
            rx,
            pull_timeout_ms,
            queue_depth,
            &mut stats,
            fps_counter,
            queue_drops,
            queue_peak,
            *seq,
        ) {
            NextFrame::Frame(frame) => frame,
            NextFrame::Continue => continue,
            NextFrame::Break => break,
        };

        match send_frame(
            conn,
            &frame,
            seq,
            stream_mode,
            disconnect_on_timeout,
            &mut stats,
        ) {
            SendOutcome::Reconnect => break,
            SendOutcome::Continue | SendOutcome::Dropped | SendOutcome::Sent => {
                stats.report_if_due(
                    fps_counter,
                    queue_drops,
                    queue_depth,
                    queue_peak,
                    *seq,
                );
            }
        }
    }
}

fn run_sender(
    appsink: gst_app::AppSink,
    port: u16,
    fps: u32,
    cfg: ResolvedConfig,
    stream_mode: StreamMode,
    stop: Arc<AtomicBool>,
    fps_counter: Arc<AtomicU64>,
    codec_flags: u8,
) {
    let queue_capacity = sender_queue_capacity(stream_mode);
    let (tx, rx) = mpsc::sync_channel::<EncodedFrame>(queue_capacity);
    let last_keyframe = Arc::new(Mutex::new(None::<CachedKeyframe>));
    let queue_drops = Arc::new(AtomicU64::new(0));
    let queue_depth = Arc::new(AtomicU64::new(0));
    let queue_peak = Arc::new(AtomicU64::new(0));

    let listener =
        TcpListener::bind(SocketAddr::from(([0, 0, 0, 0], port))).expect("bind tcp listener");
    listener.set_nonblocking(true).ok();
    println!("[wbeam-framed] listening on :{port}");

    let fps = fps.max(1);
    let is_png_stream = (codec_flags & HELLO_CODEC_PNG) != 0;
    let frame_budget_ms = ((1_000u64 + (fps as u64 - 1)) / fps as u64).max(1);
    let pull_timeout_ms = pull_timeout_ms(&cfg, stream_mode, frame_budget_ms, is_png_stream);
    let pull_timeout = Some(gst::ClockTime::from_mseconds(pull_timeout_ms));
    let disconnect_on_timeout = cfg.disconnect_on_timeout;
    let producer_handle = spawn_frame_producer(
        appsink,
        pull_timeout,
        codec_flags,
        stream_mode,
        stop.clone(),
        tx,
        last_keyframe.clone(),
        queue_drops.clone(),
        queue_depth.clone(),
        queue_peak.clone(),
    );

    let mut seq: u32 = 0;
    while !stop.load(Ordering::Acquire) {
        let mut conn =
            match accept_client(&listener, &cfg, stream_mode, frame_budget_ms, is_png_stream) {
                AcceptOutcome::Connected(conn) => conn,
                AcceptOutcome::Retry => continue,
                AcceptOutcome::Exit => break,
            };

        run_client_session(
            &mut conn,
            &rx,
            pull_timeout_ms,
            &last_keyframe,
            &stop,
            &fps_counter,
            &queue_drops,
            &queue_depth,
            &queue_peak,
            stream_mode,
            disconnect_on_timeout,
            codec_flags,
            &mut seq,
        );
    }

    stop.store(true, Ordering::Release);
    let _ = producer_handle.join();
}

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
    thread::spawn(move || run_sender(appsink, port, fps, cfg, stream_mode, stop, fps_counter, codec_flags))
}
