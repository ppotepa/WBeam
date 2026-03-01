//! WBTP/1 sender – packages frames into the wire protocol and streams them
//! over a TCP connection.
//!
//! # Design rules (from next.md)
//! * Bounded queue – capacity is set at construction, never grows.
//! * Latest-frame-wins – when the queue is full, the *oldest* pending frame is
//!   dropped, not the incoming one.
//! * No blocking waits in the hot path.
//! * No per-frame heap allocations in the hot path: payloads are `Bytes`
//!   (ref-counted, clone is O(1)).

use std::collections::VecDeque;
use std::sync::{Arc, Mutex};

use bytes::{Bytes, BytesMut};
use tokio::io::AsyncWriteExt;
use tokio::net::TcpStream;
use tokio::sync::Notify;
use tracing::{debug, info, warn};

use wbtp_core::{Flags, FrameHeader};

// ── public types ─────────────────────────────────────────────────────────────

/// A single frame to be transmitted.
#[derive(Debug, Clone)]
pub struct OutFrame {
    /// Encoded video payload (H.264 / AV1 / etc.)
    pub payload: Bytes,
    /// Capture timestamp in microseconds since UNIX epoch.
    pub capture_ts_us: u64,
    pub is_keyframe: bool,
}

#[derive(Debug, Clone)]
pub struct SenderConfig {
    /// Remote address to connect to, e.g. `"127.0.0.1:5000"`.
    pub remote_addr: String,
    /// Maximum number of frames held in the send queue.
    /// When full, the oldest frame is dropped.
    pub queue_capacity: usize,
    /// Append a CRC32 checksum to every frame header.
    pub enable_checksum: bool,
}

impl Default for SenderConfig {
    fn default() -> Self {
        Self {
            remote_addr: "127.0.0.1:5000".into(),
            queue_capacity: 4,
            enable_checksum: true,
        }
    }
}

// ── internal bounded queue ───────────────────────────────────────────────────

struct Inner {
    queue: Mutex<VecDeque<OutFrame>>,
    notify: Notify,
    capacity: usize,
}

/// A `FrameSender` is the producer handle for the bounded latest-frame queue.
#[derive(Clone)]
pub struct FrameSender(Arc<Inner>);

impl FrameSender {
    /// Push a frame. If the queue is full, the oldest entry is discarded first.
    ///
    /// Returns `true` if a frame was dropped to make room.
    pub fn send(&self, frame: OutFrame) -> bool {
        let mut q = self.0.queue.lock().unwrap();
        let dropped = if q.len() >= self.0.capacity {
            q.pop_front(); // drop oldest
            true
        } else {
            false
        };
        q.push_back(frame);
        drop(q);
        self.0.notify.notify_one();
        dropped
    }
}

/// A `FrameReceiver` is the consumer handle (used internally by the send task).
struct FrameReceiver(Arc<Inner>);

impl FrameReceiver {
    /// Wait until at least one frame is available, then pop and return it.
    async fn recv(&self) -> OutFrame {
        loop {
            {
                let mut q = self.0.queue.lock().unwrap();
                if let Some(f) = q.pop_front() {
                    return f;
                }
            }
            self.0.notify.notified().await;
        }
    }
}

fn bounded_queue(capacity: usize) -> (FrameSender, FrameReceiver) {
    let inner = Arc::new(Inner {
        queue: Mutex::new(VecDeque::with_capacity(capacity)),
        notify: Notify::new(),
        capacity,
    });
    (FrameSender(Arc::clone(&inner)), FrameReceiver(inner))
}

// ── sender task ──────────────────────────────────────────────────────────────

/// Spawnable async task.  Connects to `cfg.remote_addr`, reads frames from
/// `rx`, and writes framed WBTP/1 packets to the TCP socket.
///
/// Returns when the connection drops or `shutdown` is notified.
pub(crate) async fn run_sender(cfg: SenderConfig, rx: FrameReceiver, shutdown: Arc<Notify>) {
    loop {
        info!(addr = %cfg.remote_addr, "connecting…");
        let mut stream = match TcpStream::connect(&cfg.remote_addr).await {
            Ok(s) => {
                info!("connected");
                s
            }
            Err(e) => {
                warn!("connect failed: {e}, retrying in 1 s");
                tokio::time::sleep(std::time::Duration::from_secs(1)).await;
                continue;
            }
        };
        stream.set_nodelay(true).ok();

        let mut seq: u32 = 0;
        // Reuse a single BytesMut for the header to avoid per-frame allocs.
        let mut hdr_buf = BytesMut::with_capacity(wbtp_core::HEADER_MAX_LEN);

        loop {
            let frame = tokio::select! {
                f = rx.recv() => f,
                _ = shutdown.notified() => {
                    info!("shutdown signal, stopping sender");
                    return;
                }
            };

            let mut flags = Flags::default();
            if frame.is_keyframe {
                flags = flags.set_keyframe();
            }
            if cfg.enable_checksum {
                flags = flags.set_checksum();
            }

            let header = FrameHeader {
                flags,
                sequence: seq,
                capture_ts_us: frame.capture_ts_us,
                payload_len: frame.payload.len() as u32,
            };
            seq = seq.wrapping_add(1);

            hdr_buf.clear();
            header.encode(&mut hdr_buf, Some(&frame.payload));

            // Write header then payload without extra copy.
            if let Err(e) = stream.write_all(&hdr_buf).await {
                warn!(seq = seq - 1, error = %e, kind = ?e.kind(), "write_all header failed");
                break;
            }
            if let Err(e) = stream.write_all(&frame.payload).await {
                warn!(seq = seq - 1, error = %e, kind = ?e.kind(), "write_all payload failed");
                break;
            }

            debug!(
                seq = seq - 1,
                bytes = hdr_buf.len() + frame.payload.len(),
                "sent frame"
            );
        }

        info!("disconnected, will reconnect");
    }
}

// ── public API ───────────────────────────────────────────────────────────────

/// Spawn the sender background task and return a `FrameSender` handle.
pub fn spawn_sender(cfg: SenderConfig, shutdown: Arc<Notify>) -> FrameSender {
    let cap = cfg.queue_capacity;
    let (tx, rx) = bounded_queue(cap);
    tokio::spawn(run_sender(cfg, rx, shutdown));
    tx
}
