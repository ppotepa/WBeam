use wbeamd_api::ResolverDecisionResponse;

use super::api_level::ApiLevelTier;
use super::client_caps::ClientCaps;
use super::policy;

pub fn select(caps: &ClientCaps) -> ResolverDecisionResponse {
    let tier = ApiLevelTier::from_sdk(caps.sdk_int);
    let mut decision = match tier {
        ApiLevelTier::Api17 => policy::api17::resolve_api17(),
        ApiLevelTier::Api18To28 => policy::api21::resolve_api21_like(),
        ApiLevelTier::Api29Plus => policy::modern::resolve_modern(),
    };

    if !caps.preferred_codec.is_empty() {
        decision.selected_codec = caps.preferred_codec.clone();
    }
    if caps.preferred_fps > 0 {
        decision.reason = format!(
            "{} | preferred_fps={} policy_hint={}",
            decision.reason, caps.preferred_fps, caps.policy_hint
        );
    }

    decision
}
