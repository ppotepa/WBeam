use axum::{
    routing::{get, post},
    Router,
};

use crate::*;

pub(crate) fn build_router(app_state: AppState) -> Router {
    Router::new()
        .route("/status", get(get_status))
        .route("/host-probe", get(get_host_probe))
        .route("/health", get(get_health))
        .route("/presets", get(get_presets))
        .route("/metrics", get(get_metrics))
        .route("/speedtest", get(get_speedtest))
        .route("/virtual/probe", get(get_virtual_probe))
        .route("/virtual/doctor", get(get_virtual_doctor))
        .route("/start", post(post_start))
        .route("/stop", post(post_stop))
        .route("/apply", post(post_apply))
        .route("/tuning", post(post_tuning))
        .route("/client-metrics", post(post_client_metrics))
        .route("/v1/status", get(get_status))
        .route("/v1/host-probe", get(get_host_probe))
        .route("/v1/health", get(get_health))
        .route("/v1/presets", get(get_presets))
        .route("/v1/metrics", get(get_metrics))
        .route("/v1/speedtest", get(get_speedtest))
        .route("/v1/virtual/probe", get(get_virtual_probe))
        .route("/v1/virtual/doctor", get(get_virtual_doctor))
        .route("/v1/start", post(post_start))
        .route("/v1/stop", post(post_stop))
        .route("/v1/apply", post(post_apply))
        .route("/v1/tuning", post(post_tuning))
        .route("/v1/client-metrics", post(post_client_metrics))
        .with_state(app_state)
}
