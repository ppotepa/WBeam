use wbeamd_api::ResolverDecisionResponse;

pub fn resolve_api17() -> ResolverDecisionResponse {
    ResolverDecisionResponse {
        selected_profile: "API17_SAFE_60FPS_BALANCED".to_string(),
        selected_backend: "auto".to_string(),
        selected_codec: "h264".to_string(),
        reason: "legacy Android API 17 compatibility mode".to_string(),
        sdk_tier: "api17".to_string(),
    }
}
