mod backend;
mod portal;

use anyhow::Result;
use gstreamer as gst;

use crate::cli::{CaptureBackend, ResolvedConfig, WaylandSourceType};

pub(crate) use portal::{request_portal_stream, PortalStream};

// ─── Structured machine-readable event protocol ──────────────────────────────
// Lines prefixed with `WBEAM_EVENT:` carry JSON payloads consumed by wbeamd-core.
// Regular `[wbeam]` lines remain human-readable log output.

fn emit_event(event: &str, payload: &str) {
    println!("WBEAM_EVENT:{{\"event\":\"{event}\",{payload}}}");
}

#[derive(Debug)]
pub enum PreparedCapture {
    Wayland(PortalStream),
    X11,
    #[cfg(feature = "evdi")]
    Evdi,
    BenchmarkGame,
}

impl PreparedCapture {
    pub fn build_source(&self, cfg: &ResolvedConfig) -> Result<gst::Element> {
        match self {
            PreparedCapture::Wayland(stream) => backend::wayland::build_source(stream, cfg),
            PreparedCapture::X11 => backend::x11::build_source(),
            #[cfg(feature = "evdi")]
            PreparedCapture::Evdi => backend::evdi::build_source(cfg),
            PreparedCapture::BenchmarkGame => backend::benchmark_game::build_source(cfg),
        }
    }

    pub fn announce_startup(&self) {
        match self {
            PreparedCapture::Wayland(stream) => {
                println!("[wbeam] Got PipeWire node id: {}", stream.node_id);
            }
            PreparedCapture::X11 => {
                println!("[wbeam] Using X11 capture source (ximagesrc)");
            }
            #[cfg(feature = "evdi")]
            PreparedCapture::Evdi => {
                println!("[wbeam] Using EVDI direct kernel-level capture (bypasses compositor)");
            }
            PreparedCapture::BenchmarkGame => {
                println!("[wbeam] Using benchmark game source (synthetic in-memory scene)");
            }
        }
    }

    pub fn announce_streaming(&self, port: u16) {
        let backend = match self {
            PreparedCapture::Wayland(_) => "wayland_portal",
            PreparedCapture::X11 => "x11",
            #[cfg(feature = "evdi")]
            PreparedCapture::Evdi => "evdi",
            PreparedCapture::BenchmarkGame => "benchmark_game",
        };
        // Machine-readable event — consumed by wbeamd-core to transition to STATE_STREAMING.
        emit_event("streaming_started", &format!("\"backend\":\"{backend}\",\"port\":{port}"));
        // Human-readable log line (kept for backwards compat and log readability).
        match self {
            PreparedCapture::Wayland(_) => {
                println!("[wbeam] Streaming Wayland screencast on tcp://0.0.0.0:{port}");
            }
            PreparedCapture::X11 => {
                println!("[wbeam] Streaming X11 screencast on tcp://0.0.0.0:{port}");
            }
            #[cfg(feature = "evdi")]
            PreparedCapture::Evdi => {
                println!("[wbeam] Streaming EVDI capture on tcp://0.0.0.0:{port}");
            }
            PreparedCapture::BenchmarkGame => {
                println!("[wbeam] Streaming benchmark game source on tcp://0.0.0.0:{port}");
            }
        }
    }
}

pub async fn prepare_capture(cfg: &ResolvedConfig) -> Result<PreparedCapture> {
    if cfg.benchmark_game {
        return Ok(PreparedCapture::BenchmarkGame);
    }
    match cfg.capture_backend {
        CaptureBackend::WaylandPortal => {
            let source_type = match cfg.wayland_source_type {
                WaylandSourceType::Monitor => "monitor",
                WaylandSourceType::Virtual => "virtual",
            };
            println!(
                "[wbeam] Requesting ScreenCast portal session source_type={source_type} (KDE prompt expected)..."
            );
            let stream = backend::wayland::prepare(cfg).await?;
            Ok(PreparedCapture::Wayland(stream))
        }
        CaptureBackend::X11 => Ok(PreparedCapture::X11),
        CaptureBackend::Evdi => {
            #[cfg(feature = "evdi")]
            {
                Ok(PreparedCapture::Evdi)
            }
            #[cfg(not(feature = "evdi"))]
            {
                anyhow::bail!(
                    "capture_backend=evdi requires wbeamd-streamer to be built with --features evdi"
                )
            }
        }
        CaptureBackend::Auto => {
            // Resolved config should already map AUTO to concrete mode.
            Ok(PreparedCapture::X11)
        }
    }
}
