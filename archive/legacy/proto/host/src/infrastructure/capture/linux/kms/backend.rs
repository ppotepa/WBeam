use crate::application::ports::capture_port::CapturePort;
use crate::domain::frame::Frame;

#[derive(Default)]
pub struct KmsCapture;

impl CapturePort for KmsCapture {
    fn backend_name(&self) -> &'static str {
        "kms"
    }

    fn start(&mut self) -> Result<(), String> {
        Err("kms backend migration not wired yet".to_string())
    }

    fn next_frame(&mut self) -> Result<Frame, String> {
        Err("kms backend migration not wired yet".to_string())
    }

    fn health(&self) -> String {
        "kms: not wired".to_string()
    }

    fn stop(&mut self) -> Result<(), String> {
        Ok(())
    }
}
