use crate::domain::frame::Frame;

pub trait CodecPort: Send {
    fn codec_name(&self) -> &'static str;
    fn encode(&mut self, frame: &Frame) -> Result<Vec<u8>, String>;
    fn request_keyframe(&mut self) -> Result<(), String>;
}
