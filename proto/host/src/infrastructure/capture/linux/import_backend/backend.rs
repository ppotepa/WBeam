use crate::application::ports::capture_port::CapturePort;
use crate::domain::frame::Frame;

#[derive(Default)]
pub struct ImportCapture;

impl CapturePort for ImportCapture {
    fn backend_name(&self) -> &'static str {
        "import"
    }

    fn start(&mut self) -> Result<(), String> {
        Err("import backend migration not wired yet".to_string())
    }

    fn next_frame(&mut self) -> Result<Frame, String> {
        Err("import backend migration not wired yet".to_string())
    }

    fn health(&self) -> String {
        "import: not wired".to_string()
    }

    fn stop(&mut self) -> Result<(), String> {
        Ok(())
    }
}
