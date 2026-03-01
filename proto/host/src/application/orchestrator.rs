use crate::application::ports::capture_port::CapturePort;
use crate::application::ports::codec_port::CodecPort;
use crate::application::ports::transport_port::TransportPort;

pub struct StreamingOrchestrator {
    pub capture: Box<dyn CapturePort>,
    pub codec: Box<dyn CodecPort>,
    pub transport: Box<dyn TransportPort>,
}

impl StreamingOrchestrator {
    pub fn run_single_tick(&mut self) -> Result<(), String> {
        let frame = self.capture.next_frame()?;
        let payload = self.codec.encode(&frame)?;
        self.transport
            .send(&payload, frame.sequence, frame.keyframe)?;
        Ok(())
    }
}
