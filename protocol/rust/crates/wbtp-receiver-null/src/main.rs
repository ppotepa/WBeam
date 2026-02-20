//! WBTP/1 null receiver – validates protocol framing and reports metrics.
//!
//! Payload bytes are discarded after optional CRC32 verification.
//! Metrics are printed to stdout every second.
//!
//! Usage: wbtp-receiver-null --bind 0.0.0.0:5000 --late-threshold-ms 50

use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use bytes::{Buf, BytesMut};
use clap::Parser;
use tokio::io::AsyncReadExt;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::Notify;
use tracing::{info, warn};
use tracing_subscriber::EnvFilter;

use wbtp_core::{FrameHeader, HEADER_BASE_LEN, MAX_PAYLOAD};

// ── CLI ───────────────────────────────────────────────────────────────────────

#[derive(Parser, Debug)]
#[command(version, about = "WBTP/1 null receiver (PC soak tool)")]
struct Args {
    #[arg(long, default_value = "0.0.0.0:5000")]
    bind: String,

    /// Frames with one-way delay above this threshold are counted as late.
    #[arg(long, default_value_t = 50)]
    late_threshold_ms: u64,

    /// Disable CRC32 verification (use to measure CPU overhead delta).
    #[arg(long)]
    no_verify_crc: bool,
}

// ── metrics ───────────────────────────────────────────────────────────────────

#[derive(Default, Debug)]
struct WindowMetrics {
    frames:      u64,
    crc_errors:  u64,
    magic_errors:u64,
    late_frames: u64,
    bytes_recv:  u64,

    /// Sum of |inter-arrival – expected_interval| in µs (for mean jitter).
    jitter_sum_us: u64,
    jitter_max_us: u64,
    jitter_samples: u64,
}

impl WindowMetrics {
    fn report(&self, window_secs: f64) {
        let fps  = self.frames as f64 / window_secs;
        let mbps = (self.bytes_recv as f64 * 8.0) / (window_secs * 1e6);
        let jitter_mean_us = if self.jitter_samples > 0 {
            self.jitter_sum_us / self.jitter_samples
        } else {
            0
        };
        info!(
            recv_fps        = format!("{fps:.1}"),
            mbps            = format!("{mbps:.2}"),
            crc_errors      = self.crc_errors,
            magic_errors    = self.magic_errors,
            late_frames     = self.late_frames,
            jitter_mean_us,
            jitter_max_us   = self.jitter_max_us,
            "metrics"
        );
    }
}

// ── helpers ───────────────────────────────────────────────────────────────────

fn now_us() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or(Duration::ZERO)
        .as_micros() as u64
}

// ── connection handler ────────────────────────────────────────────────────────

async fn handle_conn(
    mut stream:          TcpStream,
    peer:                std::net::SocketAddr,
    late_threshold_us:   u64,
    verify_crc:          bool,
    shutdown:            Arc<Notify>,
) {
    info!(%peer, "accepted connection");

    // Read budget: 4 MiB rolling buffer.
    let mut buf = BytesMut::with_capacity(4 * 1024 * 1024);

    let mut metrics = WindowMetrics::default();
    let mut report_deadline = tokio::time::Instant::now() + Duration::from_secs(1);
    let mut window_start    = tokio::time::Instant::now();

    // Jitter tracking
    let mut last_arrival_us:  Option<u64> = None;
    let mut last_capture_us:  Option<u64> = None;

    loop {
        // ── report metrics every second ───────────────────────────────────
        let now = tokio::time::Instant::now();
        if now >= report_deadline {
            let elapsed = (now - window_start).as_secs_f64();
            metrics.report(elapsed);
            metrics         = WindowMetrics::default();
            window_start    = now;
            report_deadline = now + Duration::from_secs(1);
        }

        // ── parse a complete frame from buf ───────────────────────────────
        // We need at least the base header.
        while buf.len() >= HEADER_BASE_LEN {
            // Check for valid magic before spending effort on decode.
            if &buf[0..4] != b"WBTP" {
                // Scan for next magic to re-sync.
                if let Some(pos) = buf[1..].windows(4).position(|w| w == b"WBTP") {
                    warn!(%peer, skip = pos + 1, "magic sync lost, skipping bytes");
                    metrics.magic_errors += 1;
                    buf.advance(pos + 1);
                } else {
                    // No magic found – discard all but last 3 bytes.
                    metrics.magic_errors += 1;
                    let keep = buf.len().min(3);
                    buf.advance(buf.len() - keep);
                }
                break;
            }

            let (header, hdr_len) = match FrameHeader::decode(&buf) {
                Ok(v)  => v,
                Err(wbtp_core::FrameError::TooShort { .. }) => break, // need more data
                Err(e) => {
                    warn!(%peer, "header error: {e}, dropping byte");
                    metrics.magic_errors += 1;
                    buf.advance(1);
                    break;
                }
            };

            if header.payload_len > MAX_PAYLOAD {
                warn!(%peer, "payload_len too large, dropping connection");
                return;
            }

            let total_needed = hdr_len + header.payload_len as usize;
            if buf.len() < total_needed {
                break; // need more data
            }

            // ── frame complete ────────────────────────────────────────────
            let recv_us = now_us();
            metrics.frames     += 1;
            metrics.bytes_recv += total_needed as u64;

            // CRC verification
            if header.flags.has_checksum() && verify_crc {
                let expected_crc = FrameHeader::extract_crc(&buf[..hdr_len]);
                let payload_slice = &buf[hdr_len..total_needed];
                if let Err(e) = FrameHeader::verify_crc(expected_crc, payload_slice) {
                    warn!(%peer, seq = header.sequence, "crc error: {e}");
                    metrics.crc_errors += 1;
                }
            }

            // Lateness
            let delay_us = recv_us.saturating_sub(header.capture_ts_us);
            if delay_us > late_threshold_us {
                metrics.late_frames += 1;
            }

            // Jitter (inter-frame arrival vs inter-frame capture delta)
            if let (Some(la), Some(lc)) = (last_arrival_us, last_capture_us) {
                let arrival_delta  = recv_us.saturating_sub(la);
                let capture_delta  = header.capture_ts_us.saturating_sub(lc);
                let jitter = if arrival_delta > capture_delta {
                    arrival_delta - capture_delta
                } else {
                    capture_delta - arrival_delta
                };
                metrics.jitter_sum_us    += jitter;
                metrics.jitter_samples   += 1;
                if jitter > metrics.jitter_max_us { metrics.jitter_max_us = jitter; }
            }
            last_arrival_us = Some(recv_us);
            last_capture_us = Some(header.capture_ts_us);

            // Discard (null receiver – no decode)
            buf.advance(total_needed);
        }

        // ── read more bytes from socket ───────────────────────────────────
        let read_timeout = report_deadline
            .checked_duration_since(tokio::time::Instant::now())
            .unwrap_or(Duration::from_millis(10));

        let read_fut = stream.read_buf(&mut buf);
        let n = tokio::select! {
            res = read_fut => match res {
                Ok(0) => {
                    info!(%peer, "connection closed by sender");
                    metrics.report((tokio::time::Instant::now() - window_start).as_secs_f64());
                    return;
                }
                Ok(n)  => n,
                Err(e) => {
                    warn!(%peer, "read error: {e}");
                    return;
                }
            },
            _ = tokio::time::sleep(read_timeout) => continue,
            _ = shutdown.notified() => {
                info!(%peer, "shutdown, closing connection");
                return;
            }
        };

        tracing::trace!(%peer, bytes = n, "read");
    }
}

// ── main ──────────────────────────────────────────────────────────────────────

#[tokio::main]
async fn main() {
    let args = Args::parse();

    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env().add_directive("info".parse().unwrap()))
        .with_ansi(false)
        .init();

    let listener = TcpListener::bind(&args.bind).await
        .unwrap_or_else(|e| panic!("cannot bind {}: {e}", args.bind));
    info!(bind = %args.bind, "WBTP/1 null receiver listening");

    let shutdown = Arc::new(Notify::new());
    let sh2      = Arc::clone(&shutdown);

    tokio::spawn(async move {
        tokio::signal::ctrl_c().await.ok();
        info!("ctrl-c, shutting down");
        sh2.notify_waiters();
    });

    let late_threshold_us = args.late_threshold_ms * 1_000;
    let verify_crc        = !args.no_verify_crc;

    loop {
        tokio::select! {
            res = listener.accept() => {
                match res {
                    Ok((stream, peer)) => {
                        let sh = Arc::clone(&shutdown);
                        tokio::spawn(handle_conn(stream, peer, late_threshold_us, verify_crc, sh));
                    }
                    Err(e) => warn!("accept error: {e}"),
                }
            }
            _ = shutdown.notified() => break,
        }
    }

    info!("receiver stopped");
}
