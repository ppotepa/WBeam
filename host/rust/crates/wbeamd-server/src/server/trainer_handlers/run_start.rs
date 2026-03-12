use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::Path;
use std::process::{Command, Stdio};

use axum::extract::State;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::Json;

use crate::AppState;

use crate::server::runtime_utils::{bad_request_json, internal_json};
use crate::server::trainer_models::{TrainerRun, TrainerStartRequest, TrainerStartResponse};
use crate::server::trainer_process::{
    configure_trainer_command, normalize_start_request, TrainerStartConfig,
};
use crate::server::trainer_support::{now_unix_ms, persist_trainer_run_artifacts, sanitize_profile_name, trainer_profile_root};

pub(crate) async fn post_trainer_start(
    State(state): State<AppState>,
    body: Option<Json<TrainerStartRequest>>,
) -> impl IntoResponse {
    let req = body.map(|Json(v)| v).unwrap_or_default();

    let profile_name = sanitize_profile_name(&req.profile_name);
    let config = match normalize_start_request(req, state.sessions.base_stream_port + 2, profile_name) {
        Ok(cfg) => cfg,
        Err(msg) => return bad_request_json(msg),
    };

    let engine = "trainer_v2".to_string();
    let log_dir = state.trainer.root.join("logs").join("trainer");
    if let Err(err) = fs::create_dir_all(&log_dir) {
        return internal_json(format!("failed to create trainer log dir: {err}"));
    }
    let run_id = state.trainer.next_run_id();
    let log_path = log_dir.join(format!("{run_id}.log"));
    let profile_dir = trainer_profile_root(&state.trainer.root).join(&config.profile_name);
    let run_artifacts_dir = profile_dir.join("runs").join(&run_id);
    if let Err(err) = fs::create_dir_all(&run_artifacts_dir) {
        return internal_json(format!("failed to create run artifacts dir: {err}"));
    }
    let mut log_file = match OpenOptions::new().create(true).append(true).open(&log_path) {
        Ok(v) => v,
        Err(err) => return internal_json(format!("failed to open trainer log: {err}")),
    };
    let _ = writeln!(
        log_file,
        "[trainer-api] run_id={run_id} serial={} profile_name={} mode={} engine={engine}",
        config.serial, config.profile_name, config.mode
    );
    for warning in &config.warnings {
        let _ = writeln!(log_file, "[trainer-api][warn] {warning}");
    }
    let log_file_err = match log_file.try_clone() {
        Ok(v) => v,
        Err(err) => return internal_json(format!("failed to clone trainer log fd: {err}")),
    };

    let wbeam_bin = state.trainer.root.join("wbeam");
    if !wbeam_bin.exists() {
        return internal_json(format!("missing wbeam launcher: {}", wbeam_bin.display()));
    }

    let mut cmd = Command::new(&wbeam_bin);
    configure_trainer_command(&mut cmd, &config, &run_id, state.trainer.control_port);
    cmd.stdout(Stdio::from(log_file))
        .stderr(Stdio::from(log_file_err))
        .current_dir(&state.trainer.root);

    let child = match cmd.spawn() {
        Ok(c) => c,
        Err(err) => return internal_json(format!("failed to spawn trainer run: {err}")),
    };

    let run = build_trainer_run(
        &config,
        run_id.clone(),
        engine,
        child.id(),
        &log_path,
        &profile_dir,
        &run_artifacts_dir,
    );

    let run_bootstrap = run_bootstrap_payload(&run, config.stream_port);
    let _ = fs::write(
        run_artifacts_dir.join("run.json"),
        serde_json::to_vec_pretty(&run_bootstrap).unwrap_or_default(),
    );

    {
        let mut runs = state.trainer.runs.lock().await;
        runs.insert(run_id.clone(), run);
    }

    spawn_run_completion_watcher(state.trainer.clone(), run_id.clone(), child);

    (
        StatusCode::OK,
        Json(TrainerStartResponse {
            ok: true,
            run_id,
            status: "running".to_string(),
            log_path: log_path.to_string_lossy().to_string(),
            warnings: config.warnings,
        }),
    )
        .into_response()
}

fn build_trainer_run(
    config: &TrainerStartConfig,
    run_id: String,
    engine: String,
    pid: u32,
    log_path: &Path,
    profile_dir: &Path,
    run_artifacts_dir: &Path,
) -> TrainerRun {
    TrainerRun {
        run_id,
        profile_name: config.profile_name.clone(),
        serial: config.serial.clone(),
        mode: config.mode.clone(),
        engine,
        status: "running".to_string(),
        started_at_unix_ms: now_unix_ms(),
        finished_at_unix_ms: None,
        trials: config.trials,
        warmup_sec: config.warmup_sec,
        sample_sec: config.sample_sec,
        stream_port: config.stream_port,
        log_path: log_path.to_string_lossy().to_string(),
        profile_dir: profile_dir.to_string_lossy().to_string(),
        run_artifacts_dir: run_artifacts_dir.to_string_lossy().to_string(),
        generations: config.generations,
        population: config.population,
        elite_count: config.elite_count,
        mutation_rate: config.mutation_rate,
        crossover_rate: config.crossover_rate,
        bitrate_min_kbps: config.bitrate_min_kbps,
        bitrate_max_kbps: config.bitrate_max_kbps,
        encoder_mode: config.encoder_mode.clone(),
        encoders: config.encoders.clone(),
        encoder_tuning_mode: config.encoder_tuning_mode.clone(),
        encoder_params: config.encoder_params.clone(),
        hud_chart_mode: config.hud_chart_mode.clone(),
        hud_font_preset: config.hud_font_preset.clone(),
        hud_layout: config.hud_layout.clone(),
        exit_code: None,
        pid: Some(pid),
        error: None,
    }
}

fn run_bootstrap_payload(run: &TrainerRun, stream_port: u16) -> serde_json::Value {
    serde_json::json!({
        "run_id": run.run_id.clone(),
        "status": run.status,
        "started_at_unix_ms": run.started_at_unix_ms,
        "profile_name": run.profile_name,
        "serial": run.serial,
        "mode": run.mode,
        "engine": run.engine,
        "trials": run.trials,
        "warmup_sec": run.warmup_sec,
        "sample_sec": run.sample_sec,
        "log_path": run.log_path,
        "profile_dir": run.profile_dir,
        "run_artifacts_dir": run.run_artifacts_dir,
        "generations": run.generations,
        "population": run.population,
        "elite_count": run.elite_count,
        "mutation_rate": run.mutation_rate,
        "crossover_rate": run.crossover_rate,
        "bitrate_min_kbps": run.bitrate_min_kbps,
        "bitrate_max_kbps": run.bitrate_max_kbps,
        "encoder_mode": run.encoder_mode,
        "encoders": run.encoders,
        "encoder_tuning_mode": run.encoder_tuning_mode.clone(),
        "encoder_params": run.encoder_params.clone(),
        "hud_chart_mode": run.hud_chart_mode,
        "hud_font_preset": run.hud_font_preset.clone(),
        "hud_layout": run.hud_layout.clone(),
        "stream_port": stream_port,
    })
}

fn spawn_run_completion_watcher(
    trainer_state: std::sync::Arc<crate::server::trainer_models::TrainerState>,
    run_id_for_task: String,
    mut child: std::process::Child,
) {
    tokio::spawn(async move {
        let wait_result = tokio::task::spawn_blocking(move || child.wait()).await;
        let finished_at = now_unix_ms();
        let (status, exit_code, error_msg) = match wait_result {
            Ok(Ok(exit_status)) => {
                let code = exit_status.code().unwrap_or(-1);
                if code == 0 {
                    ("completed".to_string(), Some(code), None)
                } else {
                    (
                        "failed".to_string(),
                        Some(code),
                        Some(format!("trainer run exited with code {code}")),
                    )
                }
            }
            Ok(Err(err)) => (
                "failed".to_string(),
                None,
                Some(format!("trainer run wait failed: {err}")),
            ),
            Err(err) => (
                "failed".to_string(),
                None,
                Some(format!("trainer run join failed: {err}")),
            ),
        };
        let mut runs = trainer_state.runs.lock().await;
        if let Some(run) = runs.get_mut(&run_id_for_task) {
            run.status = status;
            run.finished_at_unix_ms = Some(finished_at);
            run.exit_code = exit_code;
            run.error = error_msg;
            run.pid = None;
            persist_trainer_run_artifacts(run);
        }
    });
}
