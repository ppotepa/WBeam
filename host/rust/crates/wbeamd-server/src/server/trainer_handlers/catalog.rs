use std::process::Command;

use axum::extract::{Path as AxPath, State};
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::Json;
use serde_json::Value;

use crate::AppState;

use crate::server::trainer_models::{
    TrainerDevicesResponse, TrainerDiagnosticsResponse, TrainerProfileDetailResponse,
    TrainerProfilesResponse,
};
use crate::server::trainer_support::{
    list_adb_devices, list_trainer_profiles, read_json_value, sanitize_profile_name,
    trainer_profile_root,
};
pub(crate) async fn get_trainer_devices(State(state): State<AppState>) -> impl IntoResponse {
    let devices = list_adb_devices(state.sessions.base_stream_port);
    (
        StatusCode::OK,
        Json(TrainerDevicesResponse { ok: true, devices }),
    )
        .into_response()
}

pub(crate) async fn get_trainer_diagnostics(State(state): State<AppState>) -> impl IntoResponse {
    let health = {
        let core = state.sessions.default_core();
        serde_json::to_value(core.health().await).unwrap_or(Value::Null)
    };
    let adb_version = Command::new("adb")
        .arg("version")
        .output()
        .ok()
        .map(|o| String::from_utf8_lossy(&o.stdout).to_string())
        .unwrap_or_default();
    let adb_devices_raw = Command::new("adb")
        .arg("devices")
        .output()
        .ok()
        .map(|o| String::from_utf8_lossy(&o.stdout).to_string())
        .unwrap_or_default();
    let runs_count = state.trainer.runs.lock().await.len();
    (
        StatusCode::OK,
        Json(TrainerDiagnosticsResponse {
            ok: true,
            daemon_health: health,
            adb_version: adb_version.trim().to_string(),
            adb_devices_raw: adb_devices_raw.trim().to_string(),
            profile_root: trainer_profile_root(&state.trainer.root)
                .to_string_lossy()
                .to_string(),
            runs_count,
        }),
    )
        .into_response()
}

pub(crate) async fn get_trainer_profiles(State(state): State<AppState>) -> impl IntoResponse {
    let root = trainer_profile_root(&state.trainer.root);
    let mut profiles = list_trainer_profiles(&root);
    profiles.sort_by(|a, b| b.updated_at_unix_ms.cmp(&a.updated_at_unix_ms));
    (
        StatusCode::OK,
        Json(TrainerProfilesResponse { ok: true, profiles }),
    )
        .into_response()
}

pub(crate) async fn get_trainer_profile(
    State(state): State<AppState>,
    AxPath(profile_name): AxPath<String>,
) -> impl IntoResponse {
    let profile_name = sanitize_profile_name(&profile_name);
    let dir = trainer_profile_root(&state.trainer.root).join(&profile_name);
    if !dir.is_dir() {
        return (
            StatusCode::NOT_FOUND,
            Json(serde_json::json!({"ok": false, "error": "profile not found"})),
        )
            .into_response();
    }
    let profile_path = dir.join(format!("{profile_name}.json"));
    let params_path = dir.join("parameters.json");
    let preflight_path = dir.join("preflight.json");
    let resp = TrainerProfileDetailResponse {
        ok: true,
        profile_name,
        profile: read_json_value(&profile_path),
        parameters: read_json_value(&params_path),
        preflight: read_json_value(&preflight_path),
    };
    (StatusCode::OK, Json(resp)).into_response()
}
