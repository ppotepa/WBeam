mod backend;
mod portal;

use anyhow::Result;
use gstreamer as gst;

use crate::cli::{CaptureBackend, ResolvedConfig, WaylandSourceType};

pub(crate) use portal::{request_portal_stream, PortalStream};

#[derive(Debug)]
pub enum PreparedCapture {
    Wayland(PortalStream),
    X11,
    BenchmarkGame,
}

impl PreparedCapture {
    pub fn build_source(&self, cfg: &ResolvedConfig) -> Result<gst::Element> {
        match self {
            PreparedCapture::Wayland(stream) => backend::wayland::build_source(stream, cfg),
            PreparedCapture::X11 => backend::x11::build_source(),
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
            PreparedCapture::BenchmarkGame => {
                println!("[wbeam] Using benchmark game source (synthetic in-memory scene)");
            }
        }
    }

    pub fn announce_streaming(&self, port: u16) {
        match self {
            PreparedCapture::Wayland(_) => {
                println!("[wbeam] Streaming Wayland screencast on tcp://0.0.0.0:{port}");
            }
            PreparedCapture::X11 => {
                println!("[wbeam] Streaming X11 screencast on tcp://0.0.0.0:{port}");
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
        CaptureBackend::Auto => {
            // Resolved config should already map AUTO to concrete mode.
            Ok(PreparedCapture::X11)
        }
    }
}
