use crate::application::ports::capture_port::CapturePort;
use crate::domain::frame::Frame;

#[derive(Default)]
pub struct GrimCapture;

impl CapturePort for GrimCapture {
    fn backend_name(&self) -> &'static str {
        "grim"
    }

    fn start(&mut self) -> Result<(), String> {
        Err("grim backend migration not wired yet".to_string())
    }

    fn next_frame(&mut self) -> Result<Frame, String> {
        Err("grim backend migration not wired yet".to_string())
    }

    fn health(&self) -> String {
        "grim: not wired".to_string()
    }

    fn stop(&mut self) -> Result<(), String> {
        Ok(())
    }
}
