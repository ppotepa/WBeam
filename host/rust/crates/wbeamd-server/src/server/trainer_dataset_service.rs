use std::fs;
use std::path::PathBuf;
use std::time::UNIX_EPOCH;

use serde_json::Value;

use super::trainer_models::{
    TrainerDatasetDetailResponse, TrainerDatasetSummary, TrainerRun,
};
use super::trainer_support::{read_json_value, sanitize_profile_name};

pub(crate) fn trainer_dataset_summary_from_run(run: &TrainerRun) -> TrainerDatasetSummary {
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

pub(crate) fn trainer_dataset_detail_from_run(run: &TrainerRun) -> TrainerDatasetDetailResponse {
    let run_dir = PathBuf::from(&run.run_artifacts_dir);
    let profile_name = sanitize_profile_name(&run.profile_name);
    TrainerDatasetDetailResponse {
        ok: true,
        dataset: trainer_dataset_summary_from_run(run),
        run: read_json_value(&run_dir.join("run.json")),
        parameters: read_json_value(&run_dir.join("parameters.json")),
        profile: read_json_value(&run_dir.join(format!("{profile_name}.json"))),
        preflight: read_json_value(&run_dir.join("preflight.json")),
        recompute: read_json_value(&run_dir.join("recompute.json")),
    }
}

pub(crate) fn rank_dataset_results(parameters: &Value) -> Result<Vec<Value>, &'static str> {
    let Some(results) = parameters.get("results").and_then(|v| v.as_array()) else {
        return Err("dataset has no results[] in parameters.json");
    };
    if results.is_empty() {
        return Err("dataset results[] is empty");
    }
    let mut ranked = results.clone();
    ranked.sort_by(|a, b| {
        let ascore = a.get("score").and_then(|v| v.as_f64()).unwrap_or(-1e9);
        let bscore = b.get("score").and_then(|v| v.as_f64()).unwrap_or(-1e9);
        bscore
            .partial_cmp(&ascore)
            .unwrap_or(std::cmp::Ordering::Equal)
    });
    Ok(ranked)
}
