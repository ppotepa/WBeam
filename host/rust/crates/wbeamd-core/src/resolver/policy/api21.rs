use wbeamd_api::ResolverDecisionResponse;

pub fn resolve_api21_like() -> ResolverDecisionResponse {
    ResolverDecisionResponse {
        selected_profile: "AUTO_60FPS_BALANCED".to_string(),
        selected_backend: "auto".to_string(),
        selected_codec: "h264".to_string(),
        reason: "balanced default for Android API 18-28".to_string(),
        sdk_tier: "api18_28".to_string(),
    }
}
