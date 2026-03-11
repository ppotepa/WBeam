//! wbtp-sender CLI – synthetic frame generator for protocol testing.
//!
//! Usage: wbtp-sender --addr 127.0.0.1:5000 --fps 60 --payload-bytes 50000

use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use bytes::Bytes;
use clap::Parser;
use tokio::sync::Notify;
use tracing::info;
use tracing_subscriber::EnvFilter;

use wbtp_sender::{spawn_sender, FrameSender, OutFrame, SenderConfig};

#[derive(Parser, Debug)]
#[command(version, about = "WBTP/1 synthetic sender")]
struct Args {
    #[arg(long, default_value = "127.0.0.1:5000")]
    addr: String,

    #[arg(long, default_value_t = 60)]
    fps: u32,

    /// Synthetic payload size in bytes (simulates a compressed video frame).
    #[arg(long, default_value_t = 50_000)]
    payload_bytes: usize,

    /// Queue capacity (frames). Oldest is dropped when full.
    #[arg(long, default_value_t = 4)]
    queue: usize,

    /// Disable CRC32 checksums.
    #[arg(long)]
    no_checksum: bool,
}

fn now_us() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or(Duration::ZERO)
        .as_micros() as u64
}

#[tokio::main]
async fn main() {
    let args = Args::parse();

    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env().add_directive("info".parse().unwrap()))
        .with_ansi(false)
        .init();

    let shutdown = Arc::new(Notify::new());

    // Ctrl-C → shutdown
    let sh2 = Arc::clone(&shutdown);
    tokio::spawn(async move {
        tokio::signal::ctrl_c().await.ok();
        info!("ctrl-c, shutting down");
        sh2.notify_waiters();
    });

    let cfg = SenderConfig {
        remote_addr: args.addr,
        queue_capacity: args.queue,
        enable_checksum: !args.no_checksum,
    };

    info!(
        fps = args.fps,
        payload_bytes = args.payload_bytes,
        "starting synthetic sender"
    );
    let sender: FrameSender = spawn_sender(cfg, Arc::clone(&shutdown));

    // Pre-allocate a synthetic payload once, reuse as Bytes (O(1) clone).
    let payload = Bytes::from(vec![0xABu8; args.payload_bytes]);
    let frame_interval = Duration::from_micros(1_000_000 / args.fps as u64);

    let mut seq: u64 = 0;
    let mut dropped_total: u64 = 0;
    let mut report_at = tokio::time::Instant::now() + Duration::from_secs(1);
    let mut sent_since_report: u64 = 0;

    let mut ticker = tokio::time::interval(frame_interval);
    ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);

    loop {
        tokio::select! {
            _ = ticker.tick() => {}
            _ = shutdown.notified() => break,
        }

        let frame = OutFrame {
            payload: Bytes::clone(&payload),
            capture_ts_us: now_us(),
            is_keyframe: seq % 60 == 0,
        };

        if sender.send(frame) {
            dropped_total += 1;
        }
        seq += 1;
        sent_since_report += 1;

        let now = tokio::time::Instant::now();
        if now >= report_at {
            info!(
                sent_fps = sent_since_report,
                dropped_total, seq, "sender stats"
            );
            sent_since_report = 0;
            report_at = now + Duration::from_secs(1);
        }
    }

    info!("sender exiting");
}
