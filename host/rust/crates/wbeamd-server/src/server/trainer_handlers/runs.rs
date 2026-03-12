use std::fs;

use axum::extract::{Path as AxPath, Query, State};
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::Json;

use crate::AppState;

use crate::server::trainer_models::{TrainerRun, TrainerRunsResponse, TrainerRunTailQuery, TrainerRunTailResponse};
pub(crate) async fn get_trainer_runs(State(state): State<AppState>) -> impl IntoResponse {
    let runs_map = state.trainer.runs.lock().await;
    let mut runs: Vec<TrainerRun> = runs_map.values().cloned().collect();
    runs.sort_by(|a, b| b.started_at_unix_ms.cmp(&a.started_at_unix_ms));
    (StatusCode::OK, Json(TrainerRunsResponse { ok: true, runs })).into_response()
}

pub(crate) async fn get_trainer_run(
    State(state): State<AppState>,
    AxPath(run_id): AxPath<String>,
) -> impl IntoResponse {
    let runs = state.trainer.runs.lock().await;
    match runs.get(run_id.as_str()) {
        Some(run) => (
            StatusCode::OK,
            Json(serde_json::json!({"ok": true, "run": run})),
        )
            .into_response(),
        None => (
            StatusCode::NOT_FOUND,
            Json(serde_json::json!({"ok": false, "error": "run not found"})),
        )
            .into_response(),
    }
}

pub(crate) async fn get_trainer_run_tail(
    State(state): State<AppState>,
    AxPath(run_id): AxPath<String>,
    Query(query): Query<TrainerRunTailQuery>,
) -> impl IntoResponse {
    let lines_limit = query.lines.unwrap_or(160).clamp(1, 1000);
    let run = {
        let runs = state.trainer.runs.lock().await;
        runs.get(run_id.as_str()).cloned()
    };
    let Some(run) = run else {
        return (
            StatusCode::NOT_FOUND,
            Json(serde_json::json!({"ok": false, "error": "run not found"})),
        )
            .into_response();
    };
    let content = fs::read_to_string(&run.log_path).unwrap_or_default();
    let mut lines: Vec<String> = content.lines().map(|v| v.to_string()).collect();
    if lines.len() > lines_limit {
        lines = lines.split_off(lines.len() - lines_limit);
    }
    (
        StatusCode::OK,
        Json(TrainerRunTailResponse {
            ok: true,
            run_id: run.run_id,
            line_count: lines.len(),
            lines,
        }),
    )
        .into_response()
}
