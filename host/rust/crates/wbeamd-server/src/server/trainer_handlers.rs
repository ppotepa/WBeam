use std::collections::HashSet;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::time::UNIX_EPOCH;

use axum::extract::{Path as AxPath, Query, State};
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::Json;
use serde_json::Value;

use crate::{AppState, SessionQuery};

use super::trainer_models::{
    TrainerDatasetDetailResponse, TrainerDatasetRecomputeResponse, TrainerDatasetSummary,
    TrainerDatasetsResponse, TrainerDevicesResponse, TrainerDiagnosticsResponse,
    TrainerLiveApplyRequest, TrainerLiveSaveProfileRequest, TrainerLiveStartRequest,
    TrainerPreflightRequest, TrainerPreflightResponse, TrainerProfileDetailResponse,
    TrainerProfilesResponse, TrainerRun, TrainerRunsResponse, TrainerRunTailQuery,
    TrainerRunTailResponse, TrainerStartRequest, TrainerStartResponse, TrainerStopRequest,
};
use super::runtime_utils::core_error_response;
use super::trainer_support::{
    adb_push_benchmark, adb_shell_rtt_benchmark, list_adb_devices, list_trainer_profiles,
    live_snapshot_score, now_unix_ms, persist_trainer_run_artifacts, read_json_value,
    sanitize_profile_name, trainer_profile_root,
};

pub(crate) async fn post_trainer_preflight(
    State(state): State<AppState>,
    body: Option<Json<TrainerPreflightRequest>>,
) -> impl IntoResponse {
    let req = body.map(|Json(v)| v).unwrap_or(TrainerPreflightRequest {
        serial: String::new(),
        stream_port: None,
        adb_push_mb: None,
        shell_rtt_loops: None,
    });
    let serial = req.serial.trim().to_string();
    if serial.is_empty() {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"ok": false, "error": "serial is required"})),
        )
            .into_response();
    }

    let stream_port = req.stream_port.unwrap_or(state.sessions.base_stream_port);
    let health = {
        let core = state
            .sessions
            .resolve_core_readonly(Some(serial.as_str()), Some(stream_port))
            .await
            .unwrap_or_else(|| state.sessions.default_core());
        serde_json::to_value(core.health().await).unwrap_or(Value::Null)
    };
    let push_mb = req.adb_push_mb.unwrap_or(8).clamp(1, 64);
    let rtt_loops = req.shell_rtt_loops.unwrap_or(10).clamp(1, 50);
    let adb_push = adb_push_benchmark(&serial, push_mb);
    let adb_shell_rtt = adb_shell_rtt_benchmark(&serial, rtt_loops);

    let resp = TrainerPreflightResponse {
        ok: true,
        serial,
        stream_port,
        daemon_health: health,
        adb_push,
        adb_shell_rtt,
    };
    (StatusCode::OK, Json(resp)).into_response()
}

pub(crate) async fn post_trainer_start(
    State(state): State<AppState>,
    body: Option<Json<TrainerStartRequest>>,
) -> impl IntoResponse {
    let req = body.map(|Json(v)| v).unwrap_or(TrainerStartRequest {
        serial: String::new(),
        profile_name: "baseline".to_string(),
        mode: None,
        trials: None,
        warmup_sec: None,
        sample_sec: None,
        overlay: None,
        stream_port: None,
        generations: None,
        population: None,
        elite_count: None,
        mutation_rate: None,
        crossover_rate: None,
        bitrate_min_kbps: None,
        bitrate_max_kbps: None,
        encoder_mode: None,
        encoders: None,
        encoder_tuning_mode: None,
        encoder_params: None,
        hud_chart_mode: None,
        hud_font_preset: None,
        hud_layout: None,
    });

    let serial = req.serial.trim().to_string();
    if serial.is_empty() {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"ok": false, "error": "serial is required"})),
        )
            .into_response();
    }
    let profile_name = sanitize_profile_name(&req.profile_name);
    let mode = req.mode.as_deref().unwrap_or("quality").trim().to_string();
    if !matches!(mode.as_str(), "quality" | "balanced" | "latency" | "custom") {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"ok": false, "error": "invalid mode"})),
        )
            .into_response();
    }
    let engine = "trainer_v2".to_string();
    let trials = req.trials.unwrap_or(18).clamp(1, 128);
    let warmup_sec = req.warmup_sec.unwrap_or(4).clamp(1, 60);
    let sample_sec = req.sample_sec.unwrap_or(12).clamp(4, 180);
    let overlay = req.overlay.unwrap_or(true);
    let generations = req.generations.unwrap_or(2).clamp(1, 32);
    let population = req.population.unwrap_or(trials.max(4)).clamp(2, 256);
    let elite_count = req
        .elite_count
        .unwrap_or((population / 3).max(2))
        .clamp(1, population.saturating_sub(1).max(1));
    let mutation_rate = req.mutation_rate.unwrap_or(0.34_f64).clamp(0.0, 1.0);
    let crossover_rate = req.crossover_rate.unwrap_or(0.50_f64).clamp(0.0, 1.0);
    let bitrate_min_kbps = req.bitrate_min_kbps.unwrap_or(10_000).clamp(4_000, 400_000);
    let bitrate_max_kbps = req
        .bitrate_max_kbps
        .unwrap_or(200_000)
        .clamp(bitrate_min_kbps, 400_000);
    let mut warnings: Vec<String> = Vec::new();
    let requested_encoder_mode = req
        .encoder_mode
        .as_deref()
        .unwrap_or("auto")
        .trim()
        .to_ascii_lowercase();
    if !matches!(requested_encoder_mode.as_str(), "auto" | "single" | "multi") {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"ok": false, "error": "invalid encoder_mode (use auto|single|multi)"})),
        )
            .into_response();
    }
    let mut encoders = req
        .encoders
        .unwrap_or_else(|| vec!["h265".to_string(), "h264".to_string()])
        .into_iter()
        .map(|v| v.trim().to_ascii_lowercase())
        .filter_map(|v| match v.as_str() {
            "h264" => Some("h264".to_string()),
            "h265" => Some("h265".to_string()),
            "rawpng" => Some("rawpng".to_string()),
            "jpeg" | "mjpeg" => Some("mjpeg".to_string()),
            _ => None,
        })
        .collect::<Vec<_>>();
    if encoders.is_empty() {
        encoders = vec!["h264".to_string()];
    }
    {
        let mut seen = HashSet::new();
        encoders.retain(|enc| seen.insert(enc.clone()));
    }
    let mut encoder_mode = if requested_encoder_mode == "auto" {
        if encoders.len() > 1 {
            "multi".to_string()
        } else {
            "single".to_string()
        }
    } else {
        requested_encoder_mode
    };
    if encoder_mode == "single" && encoders.len() > 1 {
        warnings.push(format!(
            "encoder_mode=single requested with {} encoders; using first encoder '{}'",
            encoders.len(),
            encoders[0]
        ));
        encoders = vec![encoders[0].clone()];
    }
    if encoder_mode == "multi" && encoders.len() == 1 {
        warnings.push(
            "encoder_mode=multi requested with one encoder; switching to single mode".to_string(),
        );
        encoder_mode = "single".to_string();
    }
    let encoder_tuning_mode = req
        .encoder_tuning_mode
        .as_deref()
        .unwrap_or("auto")
        .trim()
        .to_ascii_lowercase();
    if !matches!(encoder_tuning_mode.as_str(), "auto" | "manual") {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"ok": false, "error": "invalid encoder_tuning_mode (use auto|manual)"})),
        )
            .into_response();
    }
    let encoder_params = req.encoder_params.unwrap_or(Value::Null);
    if encoder_tuning_mode == "manual" {
        warnings.push(
            "encoder_tuning_mode=manual is experimental; unsupported params may be ignored by runtime".to_string(),
        );
    }
    if encoder_tuning_mode == "manual" && !encoder_params.is_null() {
        warnings.push(
            "encoder_params are accepted by trainer flow but may not map 1:1 to active streamer backend".to_string(),
        );
    }
    let hud_chart_mode = req
        .hud_chart_mode
        .as_deref()
        .unwrap_or("bars")
        .trim()
        .to_ascii_lowercase();
    if !matches!(hud_chart_mode.as_str(), "bars" | "line") {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"ok": false, "error": "invalid hud_chart_mode (use bars|line)"})),
        )
            .into_response();
    }
    let hud_font_preset = req
        .hud_font_preset
        .as_deref()
        .unwrap_or("compact")
        .trim()
        .to_ascii_lowercase();
    if !matches!(hud_font_preset.as_str(), "compact" | "dense" | "arcade" | "system") {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"ok": false, "error": "invalid hud_font_preset (use compact|dense|arcade|system)"})),
        )
            .into_response();
    }
    let hud_layout = req
        .hud_layout
        .as_deref()
        .unwrap_or("wide")
        .trim()
        .to_ascii_lowercase();
    if !matches!(hud_layout.as_str(), "compact" | "wide") {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"ok": false, "error": "invalid hud_layout (use compact|wide)"})),
        )
            .into_response();
    }
    let stream_port = req
        .stream_port
        .unwrap_or_else(|| state.sessions.base_stream_port + 2);

    let log_dir = state.trainer.root.join("logs").join("trainer");
    if let Err(err) = fs::create_dir_all(&log_dir) {
        return (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"ok": false, "error": format!("failed to create trainer log dir: {err}")})),
        )
            .into_response();
    }
    let run_id = state.trainer.next_run_id();
    let log_path = log_dir.join(format!("{run_id}.log"));
    let profile_dir = trainer_profile_root(&state.trainer.root).join(&profile_name);
    let run_artifacts_dir = profile_dir.join("runs").join(&run_id);
    if let Err(err) = fs::create_dir_all(&run_artifacts_dir) {
        return (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"ok": false, "error": format!("failed to create run artifacts dir: {err}")})),
        )
            .into_response();
    }
    let mut log_file = match OpenOptions::new().create(true).append(true).open(&log_path) {
        Ok(v) => v,
        Err(err) => {
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(serde_json::json!({"ok": false, "error": format!("failed to open trainer log: {err}")})),
            )
                .into_response();
        }
    };
    let _ = writeln!(
        log_file,
        "[trainer-api] run_id={run_id} serial={serial} profile_name={profile_name} mode={mode} engine={engine}"
    );
    for warning in &warnings {
        let _ = writeln!(log_file, "[trainer-api][warn] {warning}");
    }
    let log_file_err = match log_file.try_clone() {
        Ok(v) => v,
        Err(err) => {
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(serde_json::json!({"ok": false, "error": format!("failed to clone trainer log fd: {err}")})),
            )
                .into_response();
        }
    };

    let wbeam_bin = state.trainer.root.join("wbeam");
    if !wbeam_bin.exists() {
        return (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"ok": false, "error": format!("missing wbeam launcher: {}", wbeam_bin.display())})),
        )
            .into_response();
    }
    let mut cmd = Command::new(&wbeam_bin);
    cmd.arg("train")
        .arg("wizard")
        .arg("--non-interactive")
        .arg("--apply-best")
        .arg("--export-best")
        .arg("--serial")
        .arg(&serial)
        .arg("--profile-name")
        .arg(&profile_name)
        .arg("--mode")
        .arg(&mode)
        .arg("--run-id")
        .arg(&run_id)
        .arg("--trials")
        .arg(trials.to_string())
        .arg("--warmup-sec")
        .arg(warmup_sec.to_string())
        .arg("--sample-sec")
        .arg(sample_sec.to_string())
        .arg("--generations")
        .arg(generations.to_string())
        .arg("--population")
        .arg(population.to_string())
        .arg("--elite-count")
        .arg(elite_count.to_string())
        .arg("--mutation-rate")
        .arg(format!("{mutation_rate:.4}"))
        .arg("--crossover-rate")
        .arg(format!("{crossover_rate:.4}"))
        .arg("--bitrate-min-kbps")
        .arg(bitrate_min_kbps.to_string())
        .arg("--bitrate-max-kbps")
        .arg(bitrate_max_kbps.to_string())
        .arg("--encoder-mode")
        .arg(&encoder_mode)
        .arg("--encoders")
        .arg(encoders.join(","))
        .arg("--encoder-tuning-mode")
        .arg(&encoder_tuning_mode)
        .arg("--encoder-params-json")
        .arg(encoder_params.to_string())
        .arg("--overlay-chart")
        .arg(&hud_chart_mode)
        .arg("--overlay-layout")
        .arg(&hud_layout)
        .arg("--stream-port")
        .arg(stream_port.to_string())
        .arg("--control-port")
        .arg(state.trainer.control_port.to_string())
        .env("PYTHONUNBUFFERED", "1")
        .env(
            "WBEAM_OVERLAY_FONT_DESC",
            match hud_font_preset.as_str() {
                "dense" => "JetBrains Mono SemiBold 12",
                "arcade" => "IBM Plex Mono SemiBold 14",
                "system" => "Monospace Semi-Bold 13",
                _ => "JetBrains Mono SemiBold 13",
            },
        )
        .stdout(Stdio::from(log_file))
        .stderr(Stdio::from(log_file_err))
        .current_dir(&state.trainer.root);
    if overlay {
        cmd.arg("--overlay");
    } else {
        cmd.arg("--no-overlay");
    }
    let mut child = match cmd.spawn() {
        Ok(c) => c,
        Err(err) => {
            return (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(serde_json::json!({"ok": false, "error": format!("failed to spawn trainer run: {err}")})),
            )
                .into_response();
        }
    };
    let pid = child.id();
    let run = TrainerRun {
        run_id: run_id.clone(),
        profile_name,
        serial,
        mode,
        engine,
        status: "running".to_string(),
        started_at_unix_ms: now_unix_ms(),
        finished_at_unix_ms: None,
        trials,
        warmup_sec,
        sample_sec,
        stream_port,
        log_path: log_path.to_string_lossy().to_string(),
        profile_dir: profile_dir.to_string_lossy().to_string(),
        run_artifacts_dir: run_artifacts_dir.to_string_lossy().to_string(),
        generations,
        population,
        elite_count,
        mutation_rate,
        crossover_rate,
        bitrate_min_kbps,
        bitrate_max_kbps,
        encoder_mode: encoder_mode.clone(),
        encoders: encoders.clone(),
        encoder_tuning_mode: encoder_tuning_mode.clone(),
        encoder_params: encoder_params.clone(),
        hud_chart_mode: hud_chart_mode.clone(),
        hud_font_preset: hud_font_preset.clone(),
        hud_layout: hud_layout.clone(),
        exit_code: None,
        pid: Some(pid),
        error: None,
    };
    let run_bootstrap = serde_json::json!({
        "run_id": run_id.clone(),
        "status": "running",
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
    });
    let _ = fs::write(
        run_artifacts_dir.join("run.json"),
        serde_json::to_vec_pretty(&run_bootstrap).unwrap_or_default(),
    );
    {
        let mut runs = state.trainer.runs.lock().await;
        runs.insert(run_id.clone(), run);
    }
    let trainer_state = state.trainer.clone();
    let run_id_for_task = run_id.clone();
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

    (
        StatusCode::OK,
        Json(TrainerStartResponse {
            ok: true,
            run_id,
            status: "running".to_string(),
            log_path: log_path.to_string_lossy().to_string(),
            warnings,
        }),
    )
        .into_response()
}

pub(crate) async fn post_trainer_stop(
    State(state): State<AppState>,
    body: Option<Json<TrainerStopRequest>>,
) -> impl IntoResponse {
    let req = body.map(|Json(v)| v).unwrap_or(TrainerStopRequest {
        run_id: String::new(),
    });
    let run_id = req.run_id.trim();
    if run_id.is_empty() {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"ok": false, "error": "run_id is required"})),
        )
            .into_response();
    }
    let mut runs = state.trainer.runs.lock().await;
    let Some(run) = runs.get_mut(run_id) else {
        return (
            StatusCode::NOT_FOUND,
            Json(serde_json::json!({"ok": false, "error": "run not found"})),
        )
            .into_response();
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
        Ok(status) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(
                serde_json::json!({"ok": false, "error": format!("kill returned status {status}")}),
            ),
        )
            .into_response(),
        Err(err) => (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(
                serde_json::json!({"ok": false, "error": format!("failed to execute kill: {err}")}),
            ),
        )
            .into_response(),
    }
}

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
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"ok": false, "error": "serial is required"})),
        )
            .into_response();
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
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"ok": false, "error": "serial is required"})),
        )
            .into_response();
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
    let req = body.map(|Json(v)| v).unwrap_or(TrainerLiveSaveProfileRequest {
        serial: String::new(),
        stream_port: None,
        profile_name: String::new(),
        description: None,
        tags: None,
    });
    let serial = req.serial.trim().to_string();
    if serial.is_empty() {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"ok": false, "error": "serial is required"})),
        )
            .into_response();
    }
    let profile_name = sanitize_profile_name(req.profile_name.trim());
    if profile_name.is_empty() {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"ok": false, "error": "profile_name is required"})),
        )
            .into_response();
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
        return (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"ok": false, "error": format!("failed to create profile dir: {err}")})),
        )
            .into_response();
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
        return (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"ok": false, "error": format!("failed to write profile json: {err}")})),
        )
            .into_response();
    }
    if let Err(err) = fs::write(
        &params_path,
        serde_json::to_vec_pretty(&parameters_json).unwrap_or_default(),
    ) {
        return (
            StatusCode::INTERNAL_SERVER_ERROR,
            Json(serde_json::json!({"ok": false, "error": format!("failed to write parameters json: {err}")})),
        )
            .into_response();
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
