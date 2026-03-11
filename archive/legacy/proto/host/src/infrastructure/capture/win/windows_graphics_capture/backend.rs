use crate::application::ports::capture_port::CapturePort;
use crate::domain::frame::Frame;

#[derive(Default)]
pub struct WindowsGraphicsCapture;

impl CapturePort for WindowsGraphicsCapture {
    fn backend_name(&self) -> &'static str {
        "win_wgc"
    }

    fn start(&mut self) -> Result<(), String> {
        Err("windows graphics capture backend migration not wired yet".to_string())
    }

    fn next_frame(&mut self) -> Result<Frame, String> {
        Err("windows graphics capture backend migration not wired yet".to_string())
    }

    fn health(&self) -> String {
        "win_wgc: not wired".to_string()
    }

    fn stop(&mut self) -> Result<(), String> {
        Ok(())
    }
}
