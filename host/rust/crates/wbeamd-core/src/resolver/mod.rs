pub mod api_level;
pub mod client_caps;
pub mod policy;
pub mod profile_selector;

use wbeamd_api::{ClientHelloRequest, ResolverDecisionResponse};

pub fn resolve_profile_for_client(req: &ClientHelloRequest) -> ResolverDecisionResponse {
    let caps = client_caps::ClientCaps::from(req);
    profile_selector::select(&caps)
}
