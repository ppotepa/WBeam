use std::fs;

use axum::extract::{Query, State};
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::Json;
use serde_json::Value;

use crate::{AppState, SessionQuery};

use crate::server::runtime_utils::{bad_request_json, core_error_response, internal_json};
use crate::server::trainer_models::{
    TrainerLiveApplyRequest, TrainerLiveSaveProfileRequest, TrainerLiveStartRequest,
};
use crate::server::trainer_support::{
    live_snapshot_score, now_unix_ms, sanitize_profile_name, trainer_profile_root,
};
pub(crate) async fn get_trainer_live_status(
    State(state): State<AppState>,
    Query(query): Query<SessionQuery>,
) -> impl IntoResponse {
    let serial = query.serial.as_deref();
    let core = state
        .sessions
        .resolve_core_readonly(serial, query.stream_port)
        .await
        .unwrap_or_else(|| state.sessions.default_core());
    let status = serde_json::to_value(core.status().await).unwrap_or_else(|_| Value::Null);
    let metrics = serde_json::to_value(core.metrics().await).unwrap_or_else(|_| Value::Null);
    (
        StatusCode::OK,
        Json(serde_json::json!({
            "ok": true,
            "status": status,
            "metrics": metrics
        })),
    )
        .into_response()
}

pub(crate) async fn post_trainer_live_start(
    State(state): State<AppState>,
    body: Option<Json<TrainerLiveStartRequest>>,
) -> impl IntoResponse {
    let req = body.map(|Json(v)| v).unwrap_or_default();
    let serial = req.serial.trim().to_string();
    if serial.is_empty() {
        return bad_request_json("serial is required");
    }
    let core = state
        .sessions
        .resolve_core(Some(serial.as_str()), req.stream_port)
        .await;
    match core.start(req.patch).await {
        Ok(resp) => (
            StatusCode::OK,
            Json(serde_json::json!({"ok": true, "mode": "live", "status": resp})),
        )
            .into_response(),
        Err(err) => core_error_response(core, err).await,
    }
}

pub(crate) async fn post_trainer_live_apply(
    State(state): State<AppState>,
    body: Option<Json<TrainerLiveApplyRequest>>,
) -> impl IntoResponse {
    let req = body.map(|Json(v)| v).unwrap_or_default();
    let serial = req.serial.trim().to_string();
    if serial.is_empty() {
        return bad_request_json("serial is required");
    }
    let core = state
        .sessions
        .resolve_core(Some(serial.as_str()), req.stream_port)
        .await;
    match core.apply(req.patch).await {
        Ok(resp) => (
            StatusCode::OK,
            Json(serde_json::json!({"ok": true, "mode": "live", "status": resp})),
        )
            .into_response(),
        Err(err) => core_error_response(core, err).await,
    }
}

pub(crate) async fn post_trainer_live_save_profile(
    State(state): State<AppState>,
    body: Option<Json<TrainerLiveSaveProfileRequest>>,
) -> impl IntoResponse {
    let req = body
        .map(|Json(v)| v)
        .unwrap_or(TrainerLiveSaveProfileRequest {
            serial: String::new(),
            stream_port: None,
            profile_name: String::new(),
            description: None,
            tags: None,
        });
    let serial = req.serial.trim().to_string();
    if serial.is_empty() {
        return bad_request_json("serial is required");
    }
    let profile_name = sanitize_profile_name(req.profile_name.trim());
    if profile_name.is_empty() {
        return bad_request_json("profile_name is required");
    }

    let core = state
        .sessions
        .resolve_core_readonly(Some(serial.as_str()), req.stream_port)
        .await
        .unwrap_or_else(|| state.sessions.default_core());
    let status = serde_json::to_value(core.status().await).unwrap_or_else(|_| Value::Null);
    let metrics = serde_json::to_value(core.metrics().await).unwrap_or_else(|_| Value::Null);

    let profile_root = trainer_profile_root(&state.trainer.root);
    let profile_dir = profile_root.join(&profile_name);
    if let Err(err) = fs::create_dir_all(&profile_dir) {
        return internal_json(format!("failed to create profile dir: {err}"));
    }
    let now = now_unix_ms();
    let best_score = live_snapshot_score(&metrics);
    let runtime_cfg = status
        .get("base")
        .and_then(|v| v.get("active_config"))
        .cloned()
        .unwrap_or(Value::Null);
    let profile_json = serde_json::json!({
        "engine": "trainer_live_v1",
        "profile": {
            "name": profile_name,
            "runtime": runtime_cfg,
            "origin": {
                "mode": "live_run",
                "score": best_score,
            }
        },
        "device": {
            "serial": serial,
            "stream_port": req.stream_port.unwrap_or(state.sessions.base_stream_port),
        },
        "meta": {
            "created_at_unix_ms": now,
            "description": req.description.clone().unwrap_or_default(),
            "tags": req.tags.clone().unwrap_or_default(),
        }
    });
    let parameters_json = serde_json::json!({
        "engine": "trainer_live_v1",
        "serial": serial,
        "best": {
            "score": best_score
        },
        "live": {
            "saved_at_unix_ms": now,
            "description": req.description.unwrap_or_default(),
            "tags": req.tags.unwrap_or_default(),
            "status": status,
            "metrics": metrics
        },
        "results": []
    });
    let profile_path = profile_dir.join(format!("{profile_name}.json"));
    let params_path = profile_dir.join("parameters.json");
    if let Err(err) = fs::write(
        &profile_path,
        serde_json::to_vec_pretty(&profile_json).unwrap_or_default(),
    ) {
        return internal_json(format!("failed to write profile json: {err}"));
    }
    if let Err(err) = fs::write(
        &params_path,
        serde_json::to_vec_pretty(&parameters_json).unwrap_or_default(),
    ) {
        return internal_json(format!("failed to write parameters json: {err}"));
    }
    (
        StatusCode::OK,
        Json(serde_json::json!({
            "ok": true,
            "profile_name": profile_name,
            "profile_path": profile_path.to_string_lossy().to_string(),
            "parameters_path": params_path.to_string_lossy().to_string(),
            "best_score": best_score
        })),
    )
        .into_response()
}
