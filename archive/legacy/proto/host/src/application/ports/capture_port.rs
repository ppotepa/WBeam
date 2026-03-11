use crate::domain::frame::Frame;

pub trait CapturePort: Send {
    fn backend_name(&self) -> &'static str;
    fn start(&mut self) -> Result<(), String>;
    fn next_frame(&mut self) -> Result<Frame, String>;
    fn health(&self) -> String;
    fn stop(&mut self) -> Result<(), String>;
}
