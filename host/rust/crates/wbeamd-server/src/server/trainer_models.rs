use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};

use serde::{Deserialize, Serialize};
use serde_json::Value;
use tokio::sync::Mutex;
use wbeamd_api::ConfigPatch;

use crate::server::trainer_support::now_unix_ms;

#[derive(Debug, Serialize, Clone)]
pub(crate) struct TrainerRun {
    pub(crate) run_id: String,
    pub(crate) profile_name: String,
    pub(crate) serial: String,
    pub(crate) mode: String,
    pub(crate) engine: String,
    pub(crate) status: String,
    pub(crate) started_at_unix_ms: u128,
    pub(crate) finished_at_unix_ms: Option<u128>,
    pub(crate) trials: u32,
    pub(crate) warmup_sec: u32,
    pub(crate) sample_sec: u32,
    pub(crate) stream_port: u16,
    pub(crate) log_path: String,
    pub(crate) profile_dir: String,
    pub(crate) run_artifacts_dir: String,
    pub(crate) generations: u32,
    pub(crate) population: u32,
    pub(crate) elite_count: u32,
    pub(crate) mutation_rate: f64,
    pub(crate) crossover_rate: f64,
    pub(crate) bitrate_min_kbps: u32,
    pub(crate) bitrate_max_kbps: u32,
    pub(crate) encoder_mode: String,
    pub(crate) encoders: Vec<String>,
    pub(crate) encoder_tuning_mode: String,
    pub(crate) encoder_params: Value,
    pub(crate) hud_chart_mode: String,
    pub(crate) hud_font_preset: String,
    pub(crate) hud_layout: String,
    pub(crate) exit_code: Option<i32>,
    pub(crate) pid: Option<u32>,
    pub(crate) error: Option<String>,
}

#[derive(Debug, Serialize)]
pub(crate) struct TrainerRunsResponse {
    pub(crate) ok: bool,
    pub(crate) runs: Vec<TrainerRun>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct TrainerStartRequest {
    pub(crate) serial: String,
    pub(crate) profile_name: String,
    pub(crate) mode: Option<String>,
    pub(crate) trials: Option<u32>,
    pub(crate) warmup_sec: Option<u32>,
    pub(crate) sample_sec: Option<u32>,
    pub(crate) overlay: Option<bool>,
    pub(crate) stream_port: Option<u16>,
    pub(crate) generations: Option<u32>,
    pub(crate) population: Option<u32>,
    pub(crate) elite_count: Option<u32>,
    pub(crate) mutation_rate: Option<f64>,
    pub(crate) crossover_rate: Option<f64>,
    pub(crate) bitrate_min_kbps: Option<u32>,
    pub(crate) bitrate_max_kbps: Option<u32>,
    pub(crate) encoder_mode: Option<String>,
    pub(crate) encoders: Option<Vec<String>>,
    pub(crate) encoder_tuning_mode: Option<String>,
    pub(crate) encoder_params: Option<Value>,
    pub(crate) hud_chart_mode: Option<String>,
    pub(crate) hud_font_preset: Option<String>,
    pub(crate) hud_layout: Option<String>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct TrainerStopRequest {
    pub(crate) run_id: String,
}

#[derive(Debug, Serialize)]
pub(crate) struct TrainerStartResponse {
    pub(crate) ok: bool,
    pub(crate) run_id: String,
    pub(crate) status: String,
    pub(crate) log_path: String,
    pub(crate) warnings: Vec<String>,
}

#[derive(Debug, Deserialize)]
pub(crate) struct TrainerPreflightRequest {
    pub(crate) serial: String,
    pub(crate) stream_port: Option<u16>,
    pub(crate) adb_push_mb: Option<u32>,
    pub(crate) shell_rtt_loops: Option<u32>,
}

#[derive(Debug, Serialize)]
pub(crate) struct TrainerPreflightResponse {
    pub(crate) ok: bool,
    pub(crate) serial: String,
    pub(crate) stream_port: u16,
    pub(crate) daemon_health: Value,
    pub(crate) adb_push: Value,
    pub(crate) adb_shell_rtt: Value,
}

#[derive(Debug, Serialize)]
pub(crate) struct TrainerProfileSummary {
    pub(crate) profile_name: String,
    pub(crate) path: String,
    pub(crate) has_profile: bool,
    pub(crate) has_parameters: bool,
    pub(crate) has_preflight: bool,
    pub(crate) best_score: Option<f64>,
    pub(crate) engine: Option<String>,
    pub(crate) serial: Option<String>,
    pub(crate) updated_at_unix_ms: Option<u128>,
}

#[derive(Debug, Serialize)]
pub(crate) struct TrainerProfilesResponse {
    pub(crate) ok: bool,
    pub(crate) profiles: Vec<TrainerProfileSummary>,
}

#[derive(Debug, Serialize)]
pub(crate) struct TrainerProfileDetailResponse {
    pub(crate) ok: bool,
    pub(crate) profile_name: String,
    pub(crate) profile: Value,
    pub(crate) parameters: Value,
    pub(crate) preflight: Value,
}

#[derive(Debug, Serialize)]
pub(crate) struct TrainerRunTailResponse {
    pub(crate) ok: bool,
    pub(crate) run_id: String,
    pub(crate) line_count: usize,
    pub(crate) lines: Vec<String>,
}

#[derive(Debug, Serialize)]
pub(crate) struct TrainerDeviceInfo {
    pub(crate) serial: String,
    pub(crate) state: String,
    pub(crate) model: Option<String>,
    pub(crate) api_level: Option<u32>,
    pub(crate) android_release: Option<String>,
    pub(crate) stream_port: Option<u16>,
}

#[derive(Debug, Serialize)]
pub(crate) struct TrainerDevicesResponse {
    pub(crate) ok: bool,
    pub(crate) devices: Vec<TrainerDeviceInfo>,
}

#[derive(Debug, Deserialize, Default)]
pub(crate) struct TrainerRunTailQuery {
    pub(crate) lines: Option<usize>,
}

#[derive(Debug, Serialize)]
pub(crate) struct TrainerDiagnosticsResponse {
    pub(crate) ok: bool,
    pub(crate) daemon_health: Value,
    pub(crate) adb_version: String,
    pub(crate) adb_devices_raw: String,
    pub(crate) profile_root: String,
    pub(crate) runs_count: usize,
}

#[derive(Debug, Serialize)]
pub(crate) struct TrainerDatasetSummary {
    pub(crate) run_id: String,
    pub(crate) profile_name: String,
    pub(crate) status: String,
    pub(crate) run_artifacts_dir: String,
    pub(crate) started_at_unix_ms: Option<u128>,
    pub(crate) finished_at_unix_ms: Option<u128>,
    pub(crate) has_run_json: bool,
    pub(crate) has_parameters: bool,
    pub(crate) has_profile: bool,
    pub(crate) has_preflight: bool,
    pub(crate) has_logs: bool,
    pub(crate) best_trial: Option<String>,
    pub(crate) best_score: Option<f64>,
    pub(crate) last_recompute_at_unix_ms: Option<u128>,
}

#[derive(Debug, Serialize)]
pub(crate) struct TrainerDatasetsResponse {
    pub(crate) ok: bool,
    pub(crate) datasets: Vec<TrainerDatasetSummary>,
}

#[derive(Debug, Serialize)]
pub(crate) struct TrainerDatasetDetailResponse {
    pub(crate) ok: bool,
    pub(crate) dataset: TrainerDatasetSummary,
    pub(crate) run: Value,
    pub(crate) parameters: Value,
    pub(crate) profile: Value,
    pub(crate) preflight: Value,
    pub(crate) recompute: Value,
}

#[derive(Debug, Serialize)]
pub(crate) struct TrainerDatasetRecomputeResponse {
    pub(crate) ok: bool,
    pub(crate) run_id: String,
    pub(crate) best_trial: String,
    pub(crate) best_score: f64,
    pub(crate) alternatives: Vec<Value>,
    pub(crate) output_path: String,
}

#[derive(Debug, Deserialize, Default)]
pub(crate) struct TrainerLiveStartRequest {
    pub(crate) serial: String,
    pub(crate) stream_port: Option<u16>,
    #[serde(flatten)]
    pub(crate) patch: ConfigPatch,
}

#[derive(Debug, Deserialize, Default)]
pub(crate) struct TrainerLiveApplyRequest {
    pub(crate) serial: String,
    pub(crate) stream_port: Option<u16>,
    #[serde(flatten)]
    pub(crate) patch: ConfigPatch,
}

#[derive(Debug, Deserialize)]
pub(crate) struct TrainerLiveSaveProfileRequest {
    pub(crate) serial: String,
    pub(crate) stream_port: Option<u16>,
    pub(crate) profile_name: String,
    pub(crate) description: Option<String>,
    pub(crate) tags: Option<Vec<String>>,
}

pub(crate) struct TrainerState {
    pub(crate) root: PathBuf,
    pub(crate) control_port: u16,
    pub(crate) runs: Mutex<HashMap<String, TrainerRun>>,
    run_counter: AtomicU64,
}

impl TrainerState {
    pub(crate) fn new(root: PathBuf, control_port: u16) -> Self {
        Self {
            root,
            control_port,
            runs: Mutex::new(HashMap::new()),
            run_counter: AtomicU64::new(0),
        }
    }

    pub(crate) fn next_run_id(&self) -> String {
        let ctr = self.run_counter.fetch_add(1, Ordering::Relaxed) + 1;
        let ts = now_unix_ms();
        format!("run-{ts}-{ctr:04}")
    }
}
