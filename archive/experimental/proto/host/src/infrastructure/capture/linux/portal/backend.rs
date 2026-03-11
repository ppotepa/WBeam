use crate::application::ports::capture_port::CapturePort;
use crate::domain::frame::Frame;

#[derive(Default)]
pub struct PortalCapture;

impl CapturePort for PortalCapture {
    fn backend_name(&self) -> &'static str {
        "portal"
    }

    fn start(&mut self) -> Result<(), String> {
        Err("portal backend migration not wired yet".to_string())
    }

    fn next_frame(&mut self) -> Result<Frame, String> {
        Err("portal backend migration not wired yet".to_string())
    }

    fn health(&self) -> String {
        "portal: not wired".to_string()
    }

    fn stop(&mut self) -> Result<(), String> {
        Ok(())
    }
}
