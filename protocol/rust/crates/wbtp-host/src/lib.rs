//! `wbtp-host` – host-side integration helper for the WBTP/1 protocol.
//!
//! Wraps [`wbtp_sender`] with a thread-safe, sync-callable push API and
//! cumulative stats so GStreamer appsink callbacks (or any non-async thread)
//! can push frames without worrying about tokio internals.
//!
//! # Usage
//!
//! ```rust,no_run
//! use std::sync::Arc;
//! use tokio::sync::Notify;
//! use wbtp_host::{HostSender, HostSenderConfig};
//!
//! #[tokio::main]
//! async fn main() {
//!     let shutdown = Arc::new(Notify::new());
//!     let sender = HostSender::spawn(HostSenderConfig::default(), Arc::clone(&shutdown));
//!     // Push a synthetic frame from any thread:
//!     let payload = bytes::Bytes::from_static(b"\x00\x00\x00\x01test");
//!     sender.push(payload, 0, true);
//!     // … later … 
//!     shutdown.notify_waiters();
//! }
//! ```

use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};

use bytes::Bytes;
use tokio::sync::Notify;
use tracing::warn;

use wbtp_sender::{FrameSender, OutFrame, SenderConfig, spawn_sender};

// ── config ────────────────────────────────────────────────────────────────────

/// Configuration for [`HostSender`].
#[derive(Debug, Clone)]
pub struct HostSenderConfig {
    /// Remote address (receiver side), e.g. `"127.0.0.1:5000"`.
    pub remote_addr: String,
    /// Maximum queued frames before oldest is dropped (latest-frame-wins).
    pub queue_capacity: usize,
    /// Append CRC32 to every frame for integrity verification.
    pub enable_checksum: bool,
}

impl Default for HostSenderConfig {
    fn default() -> Self {
        Self {
            remote_addr: "127.0.0.1:5000".into(),
            queue_capacity: 4,
            enable_checksum: false, // CRC disabled by default for lowest host CPU
        }
    }
}

// ── stats ─────────────────────────────────────────────────────────────────────

/// Cumulative counters updated on every [`HostSender::push`] call.
#[derive(Debug, Default)]
pub struct HostStats {
    pub frames_sent:    u64,
    pub frames_dropped: u64,
}

#[derive(Default)]
struct AtomicStats {
    frames_sent:    AtomicU64,
    frames_dropped: AtomicU64,
}

impl AtomicStats {
    fn snapshot(&self) -> HostStats {
        HostStats {
            frames_sent:    self.frames_sent.load(Ordering::Relaxed),
            frames_dropped: self.frames_dropped.load(Ordering::Relaxed),
        }
    }
}

// ── HostSender ────────────────────────────────────────────────────────────────

/// Thread-safe handle for pushing H.264 frames into the WBTP/1 send pipeline.
///
/// `HostSender` is cheaply cloneable (`Arc` inside) – you can share it across
/// GStreamer appsink callbacks or multiple capture threads.
#[derive(Clone)]
pub struct HostSender {
    inner: Arc<Inner>,
}

struct Inner {
    tx:    FrameSender,
    stats: AtomicStats,
}

impl HostSender {
    /// Spawn the sender background task and return a `HostSender` handle.
    ///
    /// Must be called from within a Tokio async context.
    pub fn spawn(cfg: HostSenderConfig, shutdown: Arc<Notify>) -> Self {
        let sender_cfg = SenderConfig {
            remote_addr:     cfg.remote_addr,
            queue_capacity:  cfg.queue_capacity,
            enable_checksum: cfg.enable_checksum,
        };
        let tx = spawn_sender(sender_cfg, shutdown);
        Self {
            inner: Arc::new(Inner {
                tx,
                stats: AtomicStats::default(),
            }),
        }
    }

    /// Push a single H.264 access unit frame.
    ///
    /// **Hot path – sync, non-blocking.**  If the internal queue is full the
    /// oldest frame is silently dropped and the drop counter is incremented.
    ///
    /// - `payload` – complete H.264 access unit, `Bytes` (ref-counted clone is O(1)).
    /// - `capture_ts_us` – frame capture timestamp in µs since UNIX epoch.
    /// - `is_keyframe` – true for IDR / I-frames.
    pub fn push(&self, payload: Bytes, capture_ts_us: u64, is_keyframe: bool) {
        let dropped = self.inner.tx.send(OutFrame {
            payload,
            capture_ts_us,
            is_keyframe,
        });
        if dropped {
            self.inner.stats.frames_dropped.fetch_add(1, Ordering::Relaxed);
            warn!("wbtp-host: queue full, dropped oldest frame");
        }
        self.inner.stats.frames_sent.fetch_add(1, Ordering::Relaxed);
    }

    /// Returns a snapshot of cumulative stats since construction.
    pub fn stats(&self) -> HostStats {
        self.inner.stats.snapshot()
    }
}

// ── tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    /// Verify push() increments sent counter and does not panic.
    #[tokio::test]
    async fn push_increments_sent() {
        let shutdown = Arc::new(Notify::new());
        let cfg = HostSenderConfig {
            remote_addr: "127.0.0.1:59999".into(), // nothing listens here
            queue_capacity: 4,
            enable_checksum: false,
        };
        let sender = HostSender::spawn(cfg, Arc::clone(&shutdown));
        sender.push(Bytes::from_static(b"\x00test"), 12345, false);
        sender.push(Bytes::from_static(b"\x00key"), 12350, true);
        let stats = sender.stats();
        assert_eq!(stats.frames_sent, 2);
        assert_eq!(stats.frames_dropped, 0);
        shutdown.notify_waiters();
    }

    /// Fill the queue past capacity and verify drop counting.
    #[tokio::test]
    async fn drop_when_queue_full() {
        let shutdown = Arc::new(Notify::new());
        let cfg = HostSenderConfig {
            remote_addr: "127.0.0.1:59998".into(),
            queue_capacity: 2,
            enable_checksum: false,
        };
        let sender = HostSender::spawn(cfg, Arc::clone(&shutdown));
        // Push 5 frames into a capacity-2 queue; sender task won't drain them
        // quickly since nothing is connected.  Some will be dropped.
        for i in 0u64..5 {
            sender.push(Bytes::from_static(b"\x00f"), i * 1000, i == 0);
        }
        let stats = sender.stats();
        assert_eq!(stats.frames_sent, 5);
        // At least 2 should have been dropped (5 frames, capacity 2).
        assert!(stats.frames_dropped >= 2, "expected drops, got {:?}", stats);
        shutdown.notify_waiters();
    }
}
