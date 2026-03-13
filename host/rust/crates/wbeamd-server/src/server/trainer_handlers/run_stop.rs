use std::process::Command;

use axum::extract::State;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::Json;

use crate::AppState;

use crate::server::runtime_utils::{bad_request_json, internal_json, not_found_json};
use crate::server::trainer_models::TrainerStopRequest;
use crate::server::trainer_support::persist_trainer_run_artifacts;

pub(crate) async fn post_trainer_stop(
    State(state): State<AppState>,
    body: Option<Json<TrainerStopRequest>>,
) -> impl IntoResponse {
    let req = body.map(|Json(v)| v).unwrap_or(TrainerStopRequest {
        run_id: String::new(),
    });
    let run_id = req.run_id.trim();
    if run_id.is_empty() {
        return bad_request_json("run_id is required");
    }
    let mut runs = state.trainer.runs.lock().await;
    let Some(run) = runs.get_mut(run_id) else {
        return not_found_json("run not found");
    };
    let Some(pid) = run.pid else {
        return (
            StatusCode::OK,
            Json(serde_json::json!({"ok": true, "status": run.status, "note": "run already finished"})),
        )
            .into_response();
    };

    let kill_status = Command::new("kill")
        .arg("-TERM")
        .arg(pid.to_string())
        .status();
    match kill_status {
        Ok(status) if status.success() => {
            run.status = "stopping".to_string();
            persist_trainer_run_artifacts(run);
            (
                StatusCode::OK,
                Json(serde_json::json!({"ok": true, "run_id": run_id, "status": "stopping"})),
            )
                .into_response()
        }
        Ok(status) => internal_json(format!("kill returned status {status}")),
        Err(err) => internal_json(format!("failed to execute kill: {err}")),
    }
}
