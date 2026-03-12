use std::fs;
use std::path::PathBuf;
use std::time::UNIX_EPOCH;

use axum::extract::{Path as AxPath, State};
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::Json;
use serde_json::Value;

use crate::AppState;

use crate::server::trainer_models::{
    TrainerDatasetDetailResponse, TrainerDatasetRecomputeResponse, TrainerDatasetSummary,
    TrainerDatasetsResponse, TrainerRun,
};
use crate::server::trainer_support::{
    now_unix_ms, read_json_value, sanitize_profile_name,
};
fn trainer_dataset_summary_from_run(run: &TrainerRun) -> TrainerDatasetSummary {
    let run_dir = PathBuf::from(&run.run_artifacts_dir);
    let run_json = run_dir.join("run.json");
    let params_path = run_dir.join("parameters.json");
    let profile_name = sanitize_profile_name(&run.profile_name);
    let profile_path = run_dir.join(format!("{profile_name}.json"));
    let preflight_path = run_dir.join("preflight.json");
    let logs_path = run_dir.join("logs.txt");
    let recompute_path = run_dir.join("recompute.json");
    let parameters = read_json_value(&params_path);
    let best_trial = parameters
        .get("best")
        .and_then(|v| v.get("trial_id"))
        .and_then(|v| v.as_str())
        .map(str::to_string);
    let best_score = parameters
        .get("best")
        .and_then(|v| v.get("score"))
        .and_then(|v| v.as_f64());
    let last_recompute_at_unix_ms = fs::metadata(&recompute_path)
        .ok()
        .and_then(|m| m.modified().ok())
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|d| d.as_millis());
    TrainerDatasetSummary {
        run_id: run.run_id.clone(),
        profile_name: run.profile_name.clone(),
        status: run.status.clone(),
        run_artifacts_dir: run.run_artifacts_dir.clone(),
        started_at_unix_ms: Some(run.started_at_unix_ms),
        finished_at_unix_ms: run.finished_at_unix_ms,
        has_run_json: run_json.exists(),
        has_parameters: params_path.exists(),
        has_profile: profile_path.exists(),
        has_preflight: preflight_path.exists(),
        has_logs: logs_path.exists(),
        best_trial,
        best_score,
        last_recompute_at_unix_ms,
    }
}

pub(crate) async fn get_trainer_datasets(State(state): State<AppState>) -> impl IntoResponse {
    let runs_map = state.trainer.runs.lock().await;
    let mut datasets: Vec<TrainerDatasetSummary> = runs_map
        .values()
        .map(trainer_dataset_summary_from_run)
        .collect();
    datasets.sort_by(|a, b| b.run_id.cmp(&a.run_id));
    (
        StatusCode::OK,
        Json(TrainerDatasetsResponse { ok: true, datasets }),
    )
        .into_response()
}

pub(crate) async fn get_trainer_dataset(
    State(state): State<AppState>,
    AxPath(run_id): AxPath<String>,
) -> impl IntoResponse {
    let run = {
        let runs = state.trainer.runs.lock().await;
        runs.get(run_id.as_str()).cloned()
    };
    let Some(run) = run else {
        return (
            StatusCode::NOT_FOUND,
            Json(serde_json::json!({"ok": false, "error": "dataset run not found"})),
        )
            .into_response();
    };
    let run_dir = PathBuf::from(&run.run_artifacts_dir);
    let profile_name = sanitize_profile_name(&run.profile_name);
    let detail = TrainerDatasetDetailResponse {
        ok: true,
        dataset: trainer_dataset_summary_from_run(&run),
        run: read_json_value(&run_dir.join("run.json")),
        parameters: read_json_value(&run_dir.join("parameters.json")),
        profile: read_json_value(&run_dir.join(format!("{profile_name}.json"))),
        preflight: read_json_value(&run_dir.join("preflight.json")),
        recompute: read_json_value(&run_dir.join("recompute.json")),
    };
    (StatusCode::OK, Json(detail)).into_response()
}

pub(crate) async fn post_trainer_dataset_find_optimal(
    State(state): State<AppState>,
    AxPath(run_id): AxPath<String>,
) -> impl IntoResponse {
    let run = {
        let runs = state.trainer.runs.lock().await;
        runs.get(run_id.as_str()).cloned()
    };
    let Some(run) = run else {
        return (
            StatusCode::NOT_FOUND,
            Json(serde_json::json!({"ok": false, "error": "dataset run not found"})),
        )
            .into_response();
    };
    let run_dir = PathBuf::from(&run.run_artifacts_dir);
    let parameters = read_json_value(&run_dir.join("parameters.json"));
    let Some(results) = parameters.get("results").and_then(|v| v.as_array()) else {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"ok": false, "error": "dataset has no results[] in parameters.json"})),
        )
            .into_response();
    };
    if results.is_empty() {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"ok": false, "error": "dataset results[] is empty"})),
        )
            .into_response();
    }

    let mut ranked = results.clone();
    ranked.sort_by(|a, b| {
        let ascore = a.get("score").and_then(|v| v.as_f64()).unwrap_or(-1e9);
        let bscore = b.get("score").and_then(|v| v.as_f64()).unwrap_or(-1e9);
        bscore
            .partial_cmp(&ascore)
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    let best = ranked[0].clone();
    let best_trial = best
        .get("trial_id")
        .and_then(|v| v.as_str())
        .unwrap_or("unknown")
        .to_string();
    let best_score = best.get("score").and_then(|v| v.as_f64()).unwrap_or(-999.0);
    let alternatives: Vec<Value> = ranked.into_iter().skip(1).take(4).collect();
    let now_ms = now_unix_ms();
    let recompute_doc = serde_json::json!({
        "ok": true,
        "run_id": run.run_id,
        "recomputed_at_unix_ms": now_ms,
        "method": "deterministic_sort_by_score",
        "best_trial": best_trial,
        "best_score": best_score,
        "best": best,
        "alternatives": alternatives,
    });
    let output_path = run_dir.join("recompute.json");
    if let Err(err) = fs::write(
        &output_path,
        serde_json::to_vec_pretty(&recompute_doc).unwrap_or_default(),
    ) {
        return (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"ok": false, "error": format!("failed to write recompute file: {err}")})),
        )
            .into_response();
    }
    (
        StatusCode::OK,
        Json(TrainerDatasetRecomputeResponse {
            ok: true,
            run_id: run.run_id,
            best_trial: recompute_doc
                .get("best_trial")
                .and_then(|v| v.as_str())
                .unwrap_or("unknown")
                .to_string(),
            best_score: recompute_doc
                .get("best_score")
                .and_then(|v| v.as_f64())
                .unwrap_or(best_score),
            alternatives: recompute_doc
                .get("alternatives")
                .and_then(|v| v.as_array())
                .cloned()
                .unwrap_or_default(),
            output_path: output_path.to_string_lossy().to_string(),
        }),
    )
        .into_response()
}
