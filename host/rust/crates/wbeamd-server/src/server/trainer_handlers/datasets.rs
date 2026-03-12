use std::fs;
use std::path::PathBuf;

use axum::extract::{Path as AxPath, State};
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::Json;
use serde_json::Value;

use crate::AppState;

use crate::server::runtime_utils::{bad_request_json, internal_json, not_found_json};
use crate::server::trainer_dataset_service::{
    rank_dataset_results, trainer_dataset_detail_from_run, trainer_dataset_summary_from_run,
};
use crate::server::trainer_models::{
    TrainerDatasetRecomputeResponse, TrainerDatasetSummary, TrainerDatasetsResponse,
};
use crate::server::trainer_support::{now_unix_ms, read_json_value};

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
        return not_found_json("dataset run not found");
    };
    let detail = trainer_dataset_detail_from_run(&run);
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
        return not_found_json("dataset run not found");
    };
    let run_dir = PathBuf::from(&run.run_artifacts_dir);
    let parameters = read_json_value(&run_dir.join("parameters.json"));
    let ranked = match rank_dataset_results(&parameters) {
        Ok(rows) => rows,
        Err(msg) => return bad_request_json(msg),
    };
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
        return internal_json(format!("failed to write recompute file: {err}"));
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
