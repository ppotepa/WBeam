#[derive(Clone, Debug, Default)]
pub struct Frame {
    pub sequence: u64,
    pub timestamp_ms: u64,
    pub bytes: Vec<u8>,
    pub keyframe: bool,
}
