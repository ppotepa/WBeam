#[derive(Clone, Debug, Default)]
pub struct StreamSession {
    pub id: String,
    pub started_at_ms: u64,
    pub capture_backend: String,
    pub codec: String,
    pub transport: String,
}
