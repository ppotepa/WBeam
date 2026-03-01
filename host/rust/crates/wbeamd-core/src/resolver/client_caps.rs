use wbeamd_api::ClientHelloRequest;

#[derive(Debug, Clone)]
pub struct ClientCaps {
    pub sdk_int: u32,
    pub policy_hint: String,
    pub preferred_fps: u32,
    pub preferred_codec: String,
}

impl From<&ClientHelloRequest> for ClientCaps {
    fn from(value: &ClientHelloRequest) -> Self {
        Self {
            sdk_int: value.sdk_int,
            policy_hint: value.policy.trim().to_ascii_uppercase(),
            preferred_fps: value.preferred_fps,
            preferred_codec: value.preferred_codec.trim().to_ascii_lowercase(),
        }
    }
}
