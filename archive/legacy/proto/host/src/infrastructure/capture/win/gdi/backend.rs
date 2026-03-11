use crate::application::ports::capture_port::CapturePort;
use crate::domain::frame::Frame;

#[derive(Default)]
pub struct GdiCapture;

impl CapturePort for GdiCapture {
    fn backend_name(&self) -> &'static str {
        "win_gdi"
    }

    fn start(&mut self) -> Result<(), String> {
        Err("windows gdi backend migration not wired yet".to_string())
    }

    fn next_frame(&mut self) -> Result<Frame, String> {
        Err("windows gdi backend migration not wired yet".to_string())
    }

    fn health(&self) -> String {
        "win_gdi: not wired".to_string()
    }

    fn stop(&mut self) -> Result<(), String> {
        Ok(())
    }
}
