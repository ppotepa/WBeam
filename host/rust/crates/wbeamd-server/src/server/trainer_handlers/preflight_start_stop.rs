use std::collections::HashSet;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::process::{Command, Stdio};

use axum::extract::{State};
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::Json;
use serde_json::Value;

use crate::AppState;

use crate::server::trainer_models::{
    TrainerPreflightRequest, TrainerPreflightResponse, TrainerRun, TrainerStartRequest,
    TrainerStartResponse, TrainerStopRequest,
};
use crate::server::trainer_support::{
    adb_push_benchmark, adb_shell_rtt_benchmark, now_unix_ms, persist_trainer_run_artifacts,
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
