use wbeamd_api::ResolverDecisionResponse;

pub fn resolve_modern() -> ResolverDecisionResponse {
    ResolverDecisionResponse {
        selected_profile: "AUTO_60FPS_QUALITY".to_string(),
        selected_backend: "auto".to_string(),
        selected_codec: "h264".to_string(),
        reason: "modern Android default path".to_string(),
        sdk_tier: "api29_plus".to_string(),
    }
}
