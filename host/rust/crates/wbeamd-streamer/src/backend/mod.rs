use anyhow::Result;
use gstreamer as gst;

use crate::capture::PortalStream;
use crate::cli::{CaptureBackend, ResolvedConfig};

pub mod wayland;
pub mod x11;

#[derive(Debug)]
pub enum PreparedCapture {
    Wayland(PortalStream),
    X11,
}

impl PreparedCapture {
    pub fn build_source(&self, cfg: &ResolvedConfig) -> Result<gst::Element> {
        match self {
            PreparedCapture::Wayland(stream) => wayland::build_source(stream, cfg),
            PreparedCapture::X11 => x11::build_source(),
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
        }
    }
}

pub async fn prepare_capture(cfg: &ResolvedConfig) -> Result<PreparedCapture> {
    match cfg.capture_backend {
        CaptureBackend::WaylandPortal => {
            println!("[wbeam] Requesting ScreenCast portal session (KDE prompt expected)...");
            let stream = wayland::prepare(cfg).await?;
            Ok(PreparedCapture::Wayland(stream))
        }
        CaptureBackend::X11 => Ok(PreparedCapture::X11),
        CaptureBackend::Auto => {
            // Resolved config should already map AUTO to concrete mode.
            Ok(PreparedCapture::X11)
        }
    }
}
