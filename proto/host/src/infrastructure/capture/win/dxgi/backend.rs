use crate::application::ports::capture_port::CapturePort;
use crate::domain::frame::Frame;

#[derive(Default)]
pub struct DxgiCapture;

impl CapturePort for DxgiCapture {
    fn backend_name(&self) -> &'static str {
        "win_dxgi"
    }

    fn start(&mut self) -> Result<(), String> {
        Err("windows dxgi backend migration not wired yet".to_string())
    }

    fn next_frame(&mut self) -> Result<Frame, String> {
        Err("windows dxgi backend migration not wired yet".to_string())
    }

    fn health(&self) -> String {
        "win_dxgi: not wired".to_string()
    }

    fn stop(&mut self) -> Result<(), String> {
        Ok(())
    }
}
