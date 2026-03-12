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
        .route("/client-metrics", post(post_client_metrics))
        .route("/client-hello", post(post_client_hello))
        .route("/trainer/preflight", post(post_trainer_preflight))
        .route("/trainer/start", post(post_trainer_start))
        .route("/trainer/stop", post(post_trainer_stop))
        .route("/trainer/runs", get(get_trainer_runs))
        .route("/trainer/runs/{run_id}", get(get_trainer_run))
        .route("/trainer/runs/{run_id}/tail", get(get_trainer_run_tail))
        .route("/trainer/profiles", get(get_trainer_profiles))
        .route("/trainer/profiles/{profile_name}", get(get_trainer_profile))
        .route("/trainer/datasets", get(get_trainer_datasets))
        .route("/trainer/datasets/{run_id}", get(get_trainer_dataset))
        .route(
            "/trainer/datasets/{run_id}/find-optimal",
            post(post_trainer_dataset_find_optimal),
        )
        .route("/trainer/devices", get(get_trainer_devices))
        .route("/trainer/diagnostics", get(get_trainer_diagnostics))
        .route("/trainer/live/status", get(get_trainer_live_status))
        .route("/trainer/live/start", post(post_trainer_live_start))
        .route("/trainer/live/apply", post(post_trainer_live_apply))
        .route("/trainer/live/save-profile", post(post_trainer_live_save_profile))
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
        .route("/v1/client-metrics", post(post_client_metrics))
        .route("/v1/client-hello", post(post_client_hello))
        .route("/v1/trainer/preflight", post(post_trainer_preflight))
        .route("/v1/trainer/start", post(post_trainer_start))
        .route("/v1/trainer/stop", post(post_trainer_stop))
        .route("/v1/trainer/runs", get(get_trainer_runs))
        .route("/v1/trainer/runs/{run_id}", get(get_trainer_run))
        .route("/v1/trainer/runs/{run_id}/tail", get(get_trainer_run_tail))
        .route("/v1/trainer/profiles", get(get_trainer_profiles))
        .route("/v1/trainer/profiles/{profile_name}", get(get_trainer_profile))
        .route("/v1/trainer/datasets", get(get_trainer_datasets))
        .route("/v1/trainer/datasets/{run_id}", get(get_trainer_dataset))
        .route(
            "/v1/trainer/datasets/{run_id}/find-optimal",
            post(post_trainer_dataset_find_optimal),
        )
        .route("/v1/trainer/devices", get(get_trainer_devices))
        .route("/v1/trainer/diagnostics", get(get_trainer_diagnostics))
        .route("/v1/trainer/live/status", get(get_trainer_live_status))
        .route("/v1/trainer/live/start", post(post_trainer_live_start))
        .route("/v1/trainer/live/apply", post(post_trainer_live_apply))
        .route("/v1/trainer/live/save-profile", post(post_trainer_live_save_profile))
        .with_state(app_state)
}
