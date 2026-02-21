use std::fs::{File, OpenOptions};
use std::io::Write;
use std::os::fd::AsRawFd;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::Arc;
use std::time::{Duration, Instant};

use nix::libc;
use thiserror::Error;
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::process::Command;
use tokio::sync::{mpsc, Mutex};
use tokio::time::sleep;
use tracing::{info, warn};
use wbeamd_api::{
    validate_config, valid_values, ActiveConfig, BaseResponse, ClientMetricsRequest, ClientMetricsResponse,
    ConfigPatch, ErrorResponse, HealthResponse, KpiSnapshot, MetricsResponse, MetricsSnapshot, PresetsResponse,
    StatusResponse, ValidationError,
};

pub mod domain;
pub mod infra;

use domain::policy::{
    adaptation_reason, config_for_level, is_high_pressure, is_low_pressure,
    HIGH_PRESSURE_STREAK_REQUIRED, LOW_PRESSURE_STREAK_REQUIRED, MAX_ADAPTATION_LEVEL,
};
#[allow(unused_imports)]
use domain::state::{
    STATE_ERROR, STATE_IDLE, STATE_RECONNECTING, STATE_STARTING, STATE_STOPPING, STATE_STREAMING,
};
use infra::process as proc;
use infra::{adb, config_store, telemetry};

const DEFAULT_START_TIMEOUT: Duration = Duration::from_secs(45);
const DUPLICATE_START_GUARD: Duration = Duration::from_secs(3);
const ADAPTATION_COOLDOWN: Duration =
    Duration::from_secs(domain::policy::ADAPTATION_COOLDOWN_SECS);
const NO_PRESENT_RESTART_STREAK_REQUIRED: u8 = 4;
const NO_PRESENT_RESTART_COOLDOWN: Duration = Duration::from_secs(15);
const NO_PRESENT_MIN_RECV_FPS: f64 = 10.0;
const NO_PRESENT_MAX_PRESENT_FPS: f64 = 1.0;
const REVERSE_REFRESH_BACKSTOP: Duration = Duration::from_secs(120);

#[derive(Debug, Error)]
pub enum CoreError {
    #[error(transparent)]
    Validation(#[from] ValidationError),
    #[error("stream port {0} is busy")]
    PortBusy(u16),
    #[error("failed to spawn stream process: {0}")]
    Spawn(String),
    #[error("single-instance lock already held: {0}")]
    LockHeld(String),
    #[error("io error: {0}")]
    Io(String),
}

#[derive(Debug)]
struct Inner {
    state: String,
    active_config: ActiveConfig,
    baseline_config: ActiveConfig,
    last_error: String,
    host_name: String,
    started_at: Instant,
    run_started_at: Option<Instant>,
    stream_started_at: Option<Instant>,
    last_output_at: Option<Instant>,
    last_streaming_line_at: Option<Instant>,
    metrics: MetricsSnapshot,
    current_pid: Option<u32>,
    run_id: u64,
    telemetry_file: Option<File>, // P2.3: JSONL export
    suppress_auto_start_until: Option<Instant>,
    last_reverse_refresh_at: Option<Instant>,
    adaptation_level: u8,
    high_pressure_streak: u8,
    low_pressure_streak: u8,
    last_adaptation_at: Option<Instant>,
    no_present_streak: u8,
    last_no_present_recovery_at: Option<Instant>,
}

impl Inner {
    fn new(host_name: String, active_config: ActiveConfig) -> Self {
        Self {
            state: STATE_IDLE.to_string(),
            active_config: active_config.clone(),
            baseline_config: active_config,
            last_error: String::new(),
            host_name,
            started_at: Instant::now(),
            run_started_at: None,
            stream_started_at: None,
            last_output_at: None,
            last_streaming_line_at: None,
            metrics: MetricsSnapshot::default(),
            current_pid: None,
            run_id: 0,
            telemetry_file: None, // P2.3
            suppress_auto_start_until: None,
            last_reverse_refresh_at: None,
            adaptation_level: 0,
            high_pressure_streak: 0,
            low_pressure_streak: 0,
            last_adaptation_at: None,
            no_present_streak: 0,
            last_no_present_recovery_at: None,
        }
    }
}

pub struct InstanceLock {
    file: File,
}

impl Drop for InstanceLock {
    fn drop(&mut self) {
        unsafe {
            libc::flock(self.file.as_raw_fd(), libc::LOCK_UN);
        }
    }
}

#[derive(Clone)]
pub struct DaemonCore {
    inner: Arc<Mutex<Inner>>,
    root: PathBuf,
    runtime_config_path: PathBuf,
    stream_port: u16,
    control_port: u16,
    exit_tx: mpsc::UnboundedSender<(u64, i32)>,
    auto_start: bool,
    allow_live_adaptive_restart: bool,
    reconnect_backoff: Duration,
    stop_cooldown: Duration,
    start_timeout: Duration,
}

impl DaemonCore {
    pub fn acquire_lock(path: &Path) -> Result<InstanceLock, CoreError> {
        let mut file = OpenOptions::new()
            .create(true)
            .read(true)
            .write(true)
            .open(path)
            .map_err(|e| CoreError::Io(e.to_string()))?;

        let rc = unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) };
        if rc != 0 {
            return Err(CoreError::LockHeld(path.display().to_string()));
        }

        let pid = std::process::id();
        file.set_len(0).map_err(|e| CoreError::Io(e.to_string()))?;
        file.write_all(pid.to_string().as_bytes())
            .map_err(|e| CoreError::Io(e.to_string()))?;

        Ok(InstanceLock { file })
    }

    pub fn new(root: PathBuf, stream_port: u16, control_port: u16) -> Self {
        let host_name = hostname::get()
            .ok()
            .and_then(|h| h.into_string().ok())
            .unwrap_or_else(|| "unknown-host".to_string());

        let runtime_config_path = root.join("host/rust/config/runtime_state.json");
        let active_config = config_store::load_runtime_config(&runtime_config_path)
            .unwrap_or_else(ActiveConfig::balanced_default);

        let (exit_tx, mut exit_rx) = mpsc::unbounded_channel::<(u64, i32)>();

        let core = Self {
            inner: Arc::new(Mutex::new(Inner::new(host_name, active_config))),
            root,
            runtime_config_path,
            stream_port,
            control_port,
            exit_tx,
            auto_start: true,
            allow_live_adaptive_restart: std::env::var("WBEAM_ALLOW_LIVE_ADAPTIVE_RESTART")
                .ok()
                .map(|v| matches!(v.as_str(), "1" | "true" | "TRUE" | "yes" | "on"))
                .unwrap_or(false),
            reconnect_backoff: Duration::from_secs(1),
            stop_cooldown: Duration::from_secs(12),
            start_timeout: std::env::var("WBEAM_START_TIMEOUT_SEC")
                .ok()
                .and_then(|v| v.parse::<u64>().ok())
                .map(Duration::from_secs)
                .unwrap_or(DEFAULT_START_TIMEOUT),
        };

        let supervisor = core.clone();
        tokio::spawn(async move {
            while let Some((run_id, exit_code)) = exit_rx.recv().await {
                supervisor.handle_child_exit(run_id, exit_code).await;
            }
        });

        let watchdog = core.clone();
        tokio::spawn(async move {
            loop {
                sleep(Duration::from_secs(1)).await;
                watchdog.watchdog_tick().await;
            }
        });

        core
    }

    pub async fn status(&self) -> StatusResponse {
        StatusResponse {
            base: self.base_response().await,
            ok: true,
        }
    }

    pub async fn health(&self) -> HealthResponse {
        let inner = self.inner.lock().await;
        HealthResponse {
            base: self.base_from_inner(&inner),
            ok: true,
            service: "wbeamd-rust".to_string(),
            build_revision: proc::build_revision(),
            stream_process_alive: inner.current_pid.is_some(),
        }
    }

    pub async fn presets(&self) -> PresetsResponse {
        PresetsResponse {
            base: self.base_response().await,
            ok: true,
            presets: wbeamd_api::presets(),
            valid: valid_values(),
        }
    }

    pub async fn metrics(&self) -> MetricsResponse {
        let inner = self.inner.lock().await;
        let mut metrics = inner.metrics.clone();

        let stream_uptime = if let Some(started) = inner.stream_started_at {
            started.elapsed().as_secs()
        } else {
            0
        };

        metrics.stream_uptime_sec = stream_uptime;
        metrics.kpi.target_fps = inner.active_config.fps;
        metrics.adaptive_level = inner.adaptation_level;

        if stream_uptime > 0 {
            let fps = u64::from(inner.active_config.fps);
            if metrics.frame_in == 0 {
                metrics.frame_in = fps.saturating_mul(stream_uptime);
            }
            if metrics.frame_out == 0 {
                metrics.frame_out = fps.saturating_mul(stream_uptime);
            }
            if metrics.bitrate_actual_bps == 0 {
                metrics.bitrate_actual_bps = u64::from(inner.active_config.bitrate_kbps) * 1000;
            }
        } else {
            metrics.bitrate_actual_bps = 0;
        }

        MetricsResponse {
            base: self.base_from_inner(&inner),
            ok: true,
            metrics,
        }
    }

    pub async fn start(&self, patch: ConfigPatch) -> Result<StatusResponse, CoreError> {
        let (current_cfg, current_pid, current_state) = {
            let inner = self.inner.lock().await;
            (inner.active_config.clone(), inner.current_pid, inner.state.clone())
        };

        let cfg = validate_config(patch, &current_cfg)?;
        let already_running = current_pid.is_some()
            && cfg == current_cfg
            && matches!(current_state.as_str(), STATE_STARTING | STATE_STREAMING | STATE_RECONNECTING);

        if already_running {
            return Ok(self.status().await);
        }

        {
            let mut inner = self.inner.lock().await;
            inner.baseline_config = cfg.clone();
            inner.adaptation_level = 0;
            inner.high_pressure_streak = 0;
            inner.low_pressure_streak = 0;
            inner.last_adaptation_at = None;
            inner.no_present_streak = 0;
            inner.last_no_present_recovery_at = None;
        }
        self.start_with_config(cfg).await?;
        Ok(self.status().await)
    }

    pub async fn apply(&self, patch: ConfigPatch) -> Result<StatusResponse, CoreError> {
        let (cfg, prev_cfg, was_running) = {
            let inner = self.inner.lock().await;
            let cfg = validate_config(patch, &inner.active_config)?;
            (cfg, inner.active_config.clone(), inner.current_pid.is_some())
        };

        if cfg == prev_cfg {
            return Ok(self.status().await);
        }

        {
            let mut inner = self.inner.lock().await;
            inner.active_config = cfg.clone();
            inner.baseline_config = cfg.clone();
            inner.adaptation_level = 0;
            inner.high_pressure_streak = 0;
            inner.low_pressure_streak = 0;
            inner.last_adaptation_at = None;
            inner.no_present_streak = 0;
            inner.last_no_present_recovery_at = None;
        }
        let _ = config_store::persist_config(&self.runtime_config_path, &cfg)
            .map_err(|e| tracing::warn!("persist config: {e}"));

        if was_running {
            {
                let mut inner = self.inner.lock().await;
                inner.metrics.restart_count = inner.metrics.restart_count.saturating_add(1);
            }
            self.start_with_config(cfg).await?;
        }

        Ok(self.status().await)
    }

    pub async fn ingest_client_metrics(
        &self,
        mut client: ClientMetricsRequest,
    ) -> Result<ClientMetricsResponse, CoreError> {
        // P2.2: log trace_id so host logs can be correlated with Android logcat
        info!(
            "client-metrics trace_id={} present={:.1}fps decode_p95={:.1}ms e2e_p95={:.1}ms",
            client.trace_id.map(|t| format!("{:#018x}", t)).unwrap_or_else(|| "-".to_string()),
            client.present_fps,
            client.decode_time_ms_p95,
            client.e2e_latency_ms_p95,
        );
        if client.timestamp_ms == 0 {
            client.timestamp_ms = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_millis() as u64)
                .unwrap_or(0);
        }

        let mut restart_cfg: Option<ActiveConfig> = None;
        let action;

        {
            let mut inner = self.inner.lock().await;
            let now = Instant::now();
            let can_adapt = inner.state == STATE_STREAMING
                && inner.current_pid.is_some()
                && inner
                    .stream_started_at
                    .map(|started| started.elapsed() >= Duration::from_secs(8))
                    .unwrap_or(false);

            inner.metrics.latest_client_metrics = Some(client.clone());
            inner.metrics.kpi = KpiSnapshot {
                target_fps: inner.active_config.fps,
                recv_fps: client.recv_fps,
                decode_fps: client.decode_fps,
                present_fps: client.present_fps,
                frametime_ms_p95: if client.present_fps > 0.0 {
                    1000.0 / client.present_fps.max(0.1)
                } else {
                    0.0
                },
                e2e_latency_ms_p50: client.e2e_latency_ms_p50,
                e2e_latency_ms_p95: client.e2e_latency_ms_p95,
                decode_time_ms_p95: client.decode_time_ms_p95,
                render_time_ms_p95: client.render_time_ms_p95,
            };
            if let Some(started) = inner.stream_started_at {
                let elapsed = started.elapsed().as_secs_f64().max(1.0);
                inner.metrics.frame_in = (client.recv_fps * elapsed) as u64;
                inner.metrics.frame_out = (client.present_fps * elapsed) as u64;
                inner.metrics.drops =
                    client.dropped_frames.saturating_add(client.too_late_frames);
            }

            if client.recv_bps > 0 {
                inner.metrics.bitrate_actual_bps = client.recv_bps;
            }

            // P2.3: append JSONL telemetry record
            let telemetry_run_id = inner.run_id; // capture before mutable borrow
            if let Some(ref mut f) = inner.telemetry_file {
                let mut rec = serde_json::to_value(&client).unwrap_or_default();
                if let serde_json::Value::Object(ref mut m) = rec {
                    m.insert("run_id".to_string(), serde_json::Value::from(telemetry_run_id));
                }
                let _ = writeln!(f, "{rec}");
            }

            let no_present_stream = inner.state == STATE_STREAMING
                && inner.current_pid.is_some()
                && client.recv_fps >= NO_PRESENT_MIN_RECV_FPS
                && client.present_fps <= NO_PRESENT_MAX_PRESENT_FPS;
            if no_present_stream {
                inner.no_present_streak = inner.no_present_streak.saturating_add(1);
            } else {
                inner.no_present_streak = 0;
            }
            let no_present_restart_ready = inner
                .last_no_present_recovery_at
                .map(|t| now.duration_since(t) >= NO_PRESENT_RESTART_COOLDOWN)
                .unwrap_or(true);
            let forced_no_present_restart =
                no_present_restart_ready && inner.no_present_streak >= NO_PRESENT_RESTART_STREAK_REQUIRED;

            if forced_no_present_restart {
                inner.no_present_streak = 0;
                inner.last_no_present_recovery_at = Some(now);
                inner.metrics.restart_count = inner.metrics.restart_count.saturating_add(1);
                inner.metrics.adaptive_level = inner.adaptation_level;
                inner.metrics.adaptive_action = "recover-restart".to_string();
                inner.metrics.adaptive_reason = format!(
                    "present_fps={:.1} recv_fps={:.1} q={}/{}/{}",
                    client.present_fps,
                    client.recv_fps,
                    client.transport_queue_depth,
                    client.decode_queue_depth,
                    client.render_queue_depth
                );
                inner.last_error = format!(
                    "no-present recovery restart (present_fps={:.1}, recv_fps={:.1})",
                    client.present_fps, client.recv_fps
                );
                warn!(
                    "triggering no-present recovery restart run_id={} reason={}",
                    inner.run_id, inner.metrics.adaptive_reason
                );
                restart_cfg = Some(inner.active_config.clone());
            } else if !can_adapt {
                inner.high_pressure_streak = 0;
                inner.low_pressure_streak = 0;
                inner.metrics.adaptive_level = inner.adaptation_level;
                inner.metrics.adaptive_action = "hold-warmup".to_string();
                inner.metrics.adaptive_reason = "waiting for stable STREAMING warmup".to_string();
            } else {
                let high = is_high_pressure(inner.active_config.fps, &client);
                let low = is_low_pressure(inner.active_config.fps, &client);

                if high {
                    inner.high_pressure_streak = inner.high_pressure_streak.saturating_add(1);
                    inner.low_pressure_streak = 0;
                    inner.metrics.backpressure_high_events =
                        inner.metrics.backpressure_high_events.saturating_add(1);
                } else if low {
                    inner.low_pressure_streak = inner.low_pressure_streak.saturating_add(1);
                    inner.high_pressure_streak = 0;
                    inner.metrics.backpressure_recover_events =
                        inner.metrics.backpressure_recover_events.saturating_add(1);
                } else {
                    inner.high_pressure_streak = 0;
                    inner.low_pressure_streak = 0;
                }

                let cooldown_ready = inner
                    .last_adaptation_at
                    .map(|t| now.duration_since(t) >= ADAPTATION_COOLDOWN)
                    .unwrap_or(true);

                let mut adapted = false;
                if cooldown_ready && inner.high_pressure_streak >= HIGH_PRESSURE_STREAK_REQUIRED {
                    if inner.adaptation_level < MAX_ADAPTATION_LEVEL {
                        inner.adaptation_level = inner.adaptation_level.saturating_add(1);
                        adapted = true;
                        inner.metrics.adaptive_action = "degrade".to_string();
                    } else {
                        inner.metrics.adaptive_action = "degrade-clamped".to_string();
                    }
                    inner.high_pressure_streak = 0;
                } else if cooldown_ready && inner.low_pressure_streak >= LOW_PRESSURE_STREAK_REQUIRED {
                    if inner.adaptation_level > 0 {
                        inner.adaptation_level = inner.adaptation_level.saturating_sub(1);
                        adapted = true;
                        inner.metrics.adaptive_action = "recover".to_string();
                    } else {
                        inner.metrics.adaptive_action = "recover-clamped".to_string();
                    }
                    inner.low_pressure_streak = 0;
                } else {
                    inner.metrics.adaptive_action = "hold".to_string();
                }

                if adapted {
                    inner.last_adaptation_at = Some(now);
                    inner.metrics.adaptive_events = inner.metrics.adaptive_events.saturating_add(1);
                    inner.metrics.adaptive_level = inner.adaptation_level;

                    let reason = adaptation_reason(&client, high, low);
                    inner.metrics.adaptive_reason = reason.clone();
                    inner.last_error = format!(
                        "adaptive {} L{} ({})",
                        inner.metrics.adaptive_action, inner.adaptation_level, reason
                    );

                    let target_cfg = config_for_level(&inner.baseline_config, inner.adaptation_level);
                    if target_cfg != inner.active_config && inner.current_pid.is_some() {
                        if self.allow_live_adaptive_restart {
                            inner.active_config = target_cfg.clone();
                            inner.metrics.restart_count = inner.metrics.restart_count.saturating_add(1);
                            restart_cfg = Some(target_cfg);
                        } else {
                            inner.metrics.adaptive_action = format!("{}-pending", inner.metrics.adaptive_action);
                            inner.metrics.adaptive_reason = format!(
                                "{} | pending size={} fps={} bitrate={}",
                                reason, target_cfg.size, target_cfg.fps, target_cfg.bitrate_kbps
                            );
                            inner.last_error = format!(
                                "adaptive pending L{} (live restart disabled)",
                                inner.adaptation_level
                            );
                        }
                    }
                } else {
                    inner.metrics.adaptive_level = inner.adaptation_level;
                }
            }

            action = format!(
                "{}:L{}",
                inner.metrics.adaptive_action, inner.metrics.adaptive_level
            );
        }

        if let Some(cfg) = restart_cfg {
            self.start_with_config(cfg).await?;
        }

        Ok(ClientMetricsResponse {
            base: self.base_response().await,
            ok: true,
            action,
        })
    }

    pub async fn stop(&self) -> Result<StatusResponse, CoreError> {
        let pid = {
            let mut inner = self.inner.lock().await;
            if inner.current_pid.is_none() {
                inner.state = STATE_IDLE.to_string();
                return Ok(StatusResponse {
                    base: self.base_from_inner(&inner),
                    ok: true,
                });
            }
            inner.state = STATE_STOPPING.to_string();
            inner.metrics.stop_count = inner.metrics.stop_count.saturating_add(1);
            inner.suppress_auto_start_until = Some(Instant::now() + self.stop_cooldown);
            inner.current_pid
        };

        if let Some(pid) = pid {
            proc::terminate_pid(pid).await;
        }

        let mut inner = self.inner.lock().await;
        inner.current_pid = None;
        inner.state = STATE_IDLE.to_string();
        inner.run_started_at = None;
        inner.stream_started_at = None;
        inner.last_output_at = None;
        inner.last_streaming_line_at = None;
        inner.telemetry_file = None; // P2.3: flush+close
        inner.no_present_streak = 0;

        Ok(StatusResponse {
            base: self.base_from_inner(&inner),
            ok: true,
        })
    }

    pub async fn error_response(&self, error: impl Into<String>) -> ErrorResponse {
        let inner = self.inner.lock().await;
        ErrorResponse {
            base: self.base_from_inner(&inner),
            ok: false,
            error: error.into(),
        }
    }

    async fn start_with_config(&self, cfg: ActiveConfig) -> Result<(), CoreError> {
        adb::ensure_usb_reverse(&self.root, self.stream_port, self.control_port, "start").await;
        {
            let mut inner = self.inner.lock().await;
            inner.last_reverse_refresh_at = Some(Instant::now());
        }

        {
            let inner = self.inner.lock().await;
            let recent_start = inner
                .run_started_at
                .map(|started| started.elapsed() < DUPLICATE_START_GUARD)
                .unwrap_or(false);

            if inner.current_pid.is_some()
                && cfg == inner.active_config
                && recent_start
                && matches!(inner.state.as_str(), STATE_STARTING | STATE_STREAMING | STATE_RECONNECTING)
            {
                info!(state = %inner.state, "suppressing duplicate start request");
                return Ok(());
            }
        }

        let run_id;
        let existing_pid;
        {
            let mut inner = self.inner.lock().await;
            existing_pid = inner.current_pid;
            inner.active_config = cfg.clone();
            inner.last_error.clear();
            inner.state = STATE_STARTING.to_string();
            inner.metrics.start_count = inner.metrics.start_count.saturating_add(1);
            inner.run_id = inner.run_id.saturating_add(1);
            run_id = inner.run_id;
            inner.run_started_at = Some(Instant::now());
            inner.stream_started_at = None;
            inner.telemetry_file = telemetry::open_telemetry_file(run_id); // P2.3
            inner.last_output_at = Some(Instant::now());
            inner.last_streaming_line_at = None;
            inner.no_present_streak = 0;
        }

        {
            let cfg = { self.inner.lock().await.baseline_config.clone() };
            let _ = config_store::persist_config(&self.runtime_config_path, &cfg)
                .map_err(|e| tracing::warn!("persist config: {e}"));
        }

        if let Some(pid) = existing_pid {
            proc::terminate_pid(pid).await;
        }
        adb::ensure_stream_port_available(self.stream_port)
            .map_err(|_| CoreError::PortBusy(self.stream_port))?;

        let use_rust_streamer = std::env::var("WBEAM_USE_RUST_STREAMER")
            .ok()
            .map(|v| matches!(v.as_str(), "1" | "true" | "TRUE" | "yes" | "on"))
            .unwrap_or(true);
        let rust_streamer_bin = std::env::var("WBEAM_RUST_STREAMER_BIN")
            .ok()
            .map(PathBuf::from)
            .unwrap_or_else(|| self.root.join("host/rust/target/release/wbeamd-streamer"));

        let mut cmd;
        if use_rust_streamer && rust_streamer_bin.exists() {
            cmd = Command::new(rust_streamer_bin);
            cmd.arg("--profile")
                .arg(&cfg.profile)
                .arg("--port")
                .arg(self.stream_port.to_string())
                .arg("--encoder")
                .arg(&cfg.encoder)
                .arg("--cursor-mode")
                .arg(&cfg.cursor_mode)
                .arg("--size")
                .arg(&cfg.size)
                .arg("--fps")
                .arg(cfg.fps.to_string())
                .arg("--bitrate-kbps")
                .arg(cfg.bitrate_kbps.to_string())
                .arg("--debug-dir")
                .arg("/tmp/wbeam-frames")
                .arg("--debug-fps")
                .arg(cfg.debug_fps.to_string());
            if cfg.intra_only {
                cmd.arg("--intra-only");
            }
        } else {
            if use_rust_streamer {
                warn!(path = %rust_streamer_bin.display(), "rust streamer missing, falling back to python helper");
            }
            let stream_script = self.root.join("host/scripts/stream_wayland_portal_h264.py");
            cmd = Command::new("python3");
            cmd.arg("-u")
                .arg(stream_script)
                .arg("--profile")
                .arg(&cfg.profile)
                .arg("--port")
                .arg(self.stream_port.to_string())
                .arg("--encoder")
                .arg(&cfg.encoder)
                .arg("--cursor-mode")
                .arg(&cfg.cursor_mode)
                .arg("--size")
                .arg(&cfg.size)
                .arg("--fps")
                .arg(cfg.fps.to_string())
                .arg("--bitrate-kbps")
                .arg(cfg.bitrate_kbps.to_string())
                .arg("--debug-dir")
                .arg("/tmp/wbeam-frames")
                .arg("--debug-fps")
                .arg(cfg.debug_fps.to_string())
                .env("PYTHONUNBUFFERED", "1")
                .stdin(Stdio::null())
                .stdout(Stdio::piped())
                .stderr(Stdio::piped());
            // C3: framed-only transport (legacy parser disabled on Android path).
            cmd.arg("--framed");
        }

        cmd.stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());

        let mut child = cmd
            .spawn()
            .map_err(|e| CoreError::Spawn(e.to_string()))?;

        let pid = child
            .id()
            .ok_or_else(|| CoreError::Spawn("child process has no pid".to_string()))?;

        info!(run_id, pid, "stream process started");

        if let Some(stdout) = child.stdout.take() {
            let core = self.clone();
            tokio::spawn(async move {
                let mut lines = BufReader::new(stdout).lines();
                while let Ok(Some(line)) = lines.next_line().await {
                    info!(run_id, "stream: {line}");
                    core.on_stream_output(run_id, &line).await;
                }
            });
        }

        if let Some(stderr) = child.stderr.take() {
            let core = self.clone();
            tokio::spawn(async move {
                let mut lines = BufReader::new(stderr).lines();
                while let Ok(Some(line)) = lines.next_line().await {
                    warn!(run_id, "stream: {line}");
                    core.on_stream_output(run_id, &line).await;
                }
            });
        }

        {
            let mut inner = self.inner.lock().await;
            inner.current_pid = Some(pid);
        }

        let exit_tx = self.exit_tx.clone();
        tokio::spawn(async move {
            let exit_code = child
                .wait()
                .await
                .ok()
                .and_then(|status| status.code())
                .unwrap_or(-1);
            let _ = exit_tx.send((run_id, exit_code));
        });

        Ok(())
    }

    async fn on_stream_output(&self, run_id: u64, line: &str) {
        let mut inner = self.inner.lock().await;
        if inner.run_id != run_id {
            return;
        }

        inner.last_output_at = Some(Instant::now());

        if line.contains("Streaming Wayland screencast") {
            inner.state = STATE_STREAMING.to_string();
            inner.last_error.clear();
            inner.stream_started_at = Some(Instant::now());
            inner.last_streaming_line_at = Some(Instant::now());
            inner.metrics.bitrate_actual_bps = u64::from(inner.active_config.bitrate_kbps) * 1000;
            inner.no_present_streak = 0;
        }

        if let Some(bps) = proc::parse_kbps_line_to_bps(line) {
            inner.metrics.bitrate_actual_bps = bps;
        }
    }

    async fn handle_child_exit(&self, run_id: u64, exit_code: i32) {
        info!(run_id, exit_code, "stream process exited");
        let (should_restart, cfg_for_restart) = {
            let mut inner = self.inner.lock().await;
            if inner.run_id != run_id {
                return;
            }

            let had_streaming_session = inner.stream_started_at.is_some();
            let exited_while_starting = inner.state == STATE_STARTING;

            inner.current_pid = None;
            inner.run_started_at = None;
            inner.stream_started_at = None;
            inner.last_output_at = None;
            inner.last_streaming_line_at = None;
            inner.telemetry_file = None; // P2.3: flush+close
            inner.no_present_streak = 0;

            if inner.state == STATE_STOPPING || inner.state == STATE_IDLE {
                inner.state = STATE_IDLE.to_string();
                info!(run_id, "stream exit ignored in stopping/idle state");
                return;
            }

            inner.last_error = format!("stream exited with code={exit_code}");
            inner.state = STATE_ERROR.to_string();
            inner.metrics.reconnects = inner.metrics.reconnects.saturating_add(1);

            if !had_streaming_session {
                inner.state = STATE_IDLE.to_string();
                inner.last_error = format!(
                    "stream start aborted (code={exit_code}); waiting for explicit /start"
                );
                info!(
                    run_id,
                    exit_code,
                    exited_while_starting,
                    "stream exited before STREAMING; auto-restart suppressed"
                );
                return;
            }

            let cooldown_expired = inner
                .suppress_auto_start_until
                .map(|until| Instant::now() >= until)
                .unwrap_or(true);

            if self.auto_start && cooldown_expired {
                inner.state = STATE_RECONNECTING.to_string();
                inner.metrics.restart_count = inner.metrics.restart_count.saturating_add(1);
                (true, Some(inner.active_config.clone()))
            } else {
                (false, None)
            }
        };

        if should_restart {
            let core = self.clone();
            tokio::spawn(async move {
                sleep(core.reconnect_backoff).await;
                let state_is_reconnecting = {
                    let inner = core.inner.lock().await;
                    inner.state == STATE_RECONNECTING
                };
                if !state_is_reconnecting {
                    return;
                }

                if let Some(cfg) = cfg_for_restart {
                    if let Err(err) = core.start_with_config(cfg).await {
                        let mut inner = core.inner.lock().await;
                        inner.state = STATE_ERROR.to_string();
                        inner.last_error = err.to_string();
                    }
                }
            });
        }
    }

    async fn watchdog_tick(&self) {
        let mut kill_pid = None;
        let mut refresh_reverse_reason: Option<&'static str> = None;

        {
            let mut inner = self.inner.lock().await;
            let now = Instant::now();
            let refresh_due = inner
                .last_reverse_refresh_at
                .map(|last| now.duration_since(last) >= REVERSE_REFRESH_BACKSTOP)
                .unwrap_or(true);
            let refresh_state = matches!(inner.state.as_str(), STATE_STARTING | STATE_RECONNECTING);
            if refresh_due && refresh_state {
                inner.last_reverse_refresh_at = Some(now);
                refresh_reverse_reason = Some("watchdog_backstop");
            }

            if let Some(pid) = inner.current_pid {
                if inner.state == STATE_STARTING {
                    if let Some(started) = inner.run_started_at {
                        if now.duration_since(started) > self.start_timeout {
                            inner.state = STATE_ERROR.to_string();
                            inner.last_error = "start timeout waiting for streaming signal".to_string();
                            inner.metrics.drops = inner.metrics.drops.saturating_add(1);
                            kill_pid = Some(pid);
                        }
                    }
                }
            }
        }

        if let Some(reason) = refresh_reverse_reason {
            adb::ensure_usb_reverse(&self.root, self.stream_port, self.control_port, reason).await;
        }

        if let Some(pid) = kill_pid {
            warn!(pid, "watchdog terminating stalled stream process");
            proc::terminate_pid(pid).await;
        }
    }

    fn base_from_inner(&self, inner: &Inner) -> BaseResponse {
        BaseResponse {
            state: inner.state.clone(),
            active_config: inner.active_config.clone(),
            host_name: inner.host_name.clone(),
            uptime: inner.started_at.elapsed().as_secs(),
            run_id: inner.run_id,
            last_error: inner.last_error.clone(),
        }
    }

    async fn base_response(&self) -> BaseResponse {
        let inner = self.inner.lock().await;
        self.base_from_inner(&inner)
    }
}


#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    // ── helpers ─────────────────────────────────────────────────────────────

    fn default_inner() -> Inner {
        Inner::new("test-host".to_string(), ActiveConfig::balanced_default())
    }

    /// Client metrics indicating high decode pressure (decode_p95 > 12 ms).
    fn high_pressure_client() -> ClientMetricsRequest {
        ClientMetricsRequest {
            decode_time_ms_p95: 15.0,
            render_time_ms_p95: 3.0,
            present_fps: 60.0,
            recv_fps: 60.0,
            ..Default::default()
        }
    }

    /// Client metrics that satisfy all low-pressure conditions.
    fn low_pressure_client() -> ClientMetricsRequest {
        // balanced_default fps = 60; target * 0.98 = 58.8
        ClientMetricsRequest {
            decode_time_ms_p95: 4.0,
            render_time_ms_p95: 2.0,
            present_fps: 60.0,
            recv_fps: 60.0,
            transport_queue_depth: 0,
            decode_queue_depth: 0,
            render_queue_depth: 0,
            too_late_frames: 0,
            ..Default::default()
        }
    }

    /// Build a DaemonCore whose inner state looks like it's been streaming
    /// for 10 seconds (satisfies can_adapt) with adaptation cooldown cleared.
    async fn streaming_core_ready() -> DaemonCore {
        let core = DaemonCore::new(
            PathBuf::from("/tmp/test-wbeam-b2"),
            15000,
            15001,
        );
        {
            let mut inner = core.inner.lock().await;
            inner.state = STATE_STREAMING.to_string();
            inner.current_pid = Some(99999);
            // stream started 10 s ago – passes the 8 s warmup guard
            inner.stream_started_at = Some(Instant::now() - Duration::from_secs(10));
            inner.last_adaptation_at = None; // cooldown clear
            inner.adaptation_level = 0;
            inner.high_pressure_streak = 0;
            inner.low_pressure_streak = 0;
        }
        core
    }

    // ── is_high_pressure ────────────────────────────────────────────────────

    #[test]
    fn test_high_pressure_decode_p95() {
        let inner = default_inner();
        let client = ClientMetricsRequest {
            decode_time_ms_p95: 13.0, // > 12.0
            present_fps: 60.0,
            ..Default::default()
        };
        assert!(is_high_pressure(inner.active_config.fps, &client));
    }

    #[test]
    fn test_high_pressure_fps_drop() {
        let inner = default_inner(); // fps target = 60
        let client = ClientMetricsRequest {
            present_fps: 50.0, // < 60 * 0.90 = 54
            decode_time_ms_p95: 5.0,
            ..Default::default()
        };
        assert!(is_high_pressure(inner.active_config.fps, &client));
    }

    #[test]
    fn test_high_pressure_too_late_frames() {
        let inner = default_inner();
        let client = ClientMetricsRequest {
            too_late_frames: 1,
            present_fps: 60.0,
            decode_time_ms_p95: 5.0,
            ..Default::default()
        };
        assert!(is_high_pressure(inner.active_config.fps, &client));
    }

    #[test]
    fn test_not_high_pressure_healthy() {
        let inner = default_inner();
        let client = ClientMetricsRequest {
            decode_time_ms_p95: 8.0,  // ≤ 12.0
            render_time_ms_p95: 5.0,  // ≤ 7.0
            present_fps: 59.0,        // 59 >= 60 * 0.90 = 54
            ..Default::default()
        };
        assert!(!is_high_pressure(inner.active_config.fps, &client));
    }

    // ── is_low_pressure ─────────────────────────────────────────────────────

    #[test]
    fn test_low_pressure_all_healthy() {
        let inner = default_inner();
        assert!(is_low_pressure(inner.active_config.fps, &low_pressure_client()));
    }

    #[test]
    fn test_low_pressure_blocked_by_decode_queue() {
        let inner = default_inner();
        let mut c = low_pressure_client();
        c.decode_queue_depth = 1;
        assert!(!is_low_pressure(inner.active_config.fps, &c));
    }

    #[test]
    fn test_low_pressure_blocked_by_fps() {
        let inner = default_inner(); // fps = 60, min = 58.8
        let mut c = low_pressure_client();
        c.present_fps = 58.0; // < 60 * 0.98 = 58.8
        assert!(!is_low_pressure(inner.active_config.fps, &c));
    }

    #[test]
    fn test_low_pressure_blocked_by_zero_decode() {
        // decode_time_ms_p95 must be > 0 for low_pressure to be true
        let inner = default_inner();
        let mut c = low_pressure_client();
        c.decode_time_ms_p95 = 0.0;
        assert!(!is_low_pressure(inner.active_config.fps, &c));
    }

    // ── adaptation state machine ─────────────────────────────────────────────

    #[tokio::test]
    async fn test_adaptation_degrade_after_streak() {
        let core = streaming_core_ready().await;
        // Two consecutive high-pressure samples should push level 0 → 1
        core.ingest_client_metrics(high_pressure_client()).await.expect("ingest");
        core.ingest_client_metrics(high_pressure_client()).await.expect("ingest");
        let inner = core.inner.lock().await;
        assert_eq!(inner.adaptation_level, 1, "level should degrade to 1");
    }

    #[tokio::test]
    async fn test_adaptation_single_high_no_degrade() {
        // Only one high-pressure sample (streak < HIGH_PRESSURE_STREAK_REQUIRED=2)
        let core = streaming_core_ready().await;
        core.ingest_client_metrics(high_pressure_client()).await.expect("ingest");
        let inner = core.inner.lock().await;
        assert_eq!(inner.adaptation_level, 0, "one sample should not degrade");
    }

    #[tokio::test]
    async fn test_adaptation_cooldown_blocks_rapid_degrade() {
        let core = streaming_core_ready().await;
        // First degrade: level 0 → 1
        core.ingest_client_metrics(high_pressure_client()).await.expect("ingest");
        core.ingest_client_metrics(high_pressure_client()).await.expect("ingest");
        // Immediately after degrade, cooldown should block further degrade
        // (last_adaptation_at = Some(Instant::now()), delta < 4s)
        core.ingest_client_metrics(high_pressure_client()).await.expect("ingest");
        core.ingest_client_metrics(high_pressure_client()).await.expect("ingest");
        let inner = core.inner.lock().await;
        assert_eq!(inner.adaptation_level, 1, "cooldown should block second degrade");
    }

    #[tokio::test]
    async fn test_adaptation_cooldown_cleared_allows_degrade() {
        let core = streaming_core_ready().await;
        // Simulate first degrade manually, then back-date last_adaptation_at
        {
            let mut inner = core.inner.lock().await;
            inner.adaptation_level = 1;
            // back-date cooldown beyond 4 seconds
            inner.last_adaptation_at = Some(Instant::now() - Duration::from_secs(5));
            inner.high_pressure_streak = 0;
        }
        // Two more high-pressure → level 1 → 2
        core.ingest_client_metrics(high_pressure_client()).await.expect("ingest");
        core.ingest_client_metrics(high_pressure_client()).await.expect("ingest");
        let inner = core.inner.lock().await;
        assert_eq!(inner.adaptation_level, 2, "back-dated cooldown should allow degrade 1→2");
    }

    #[tokio::test]
    async fn test_adaptation_clamps_at_max() {
        let core = streaming_core_ready().await;
        // Drive to max (3) then try to go further
        {
            let mut inner = core.inner.lock().await;
            inner.adaptation_level = MAX_ADAPTATION_LEVEL;
            inner.last_adaptation_at = Some(Instant::now() - Duration::from_secs(5));
        }
        core.ingest_client_metrics(high_pressure_client()).await.expect("ingest");
        core.ingest_client_metrics(high_pressure_client()).await.expect("ingest");
        let inner = core.inner.lock().await;
        assert_eq!(inner.adaptation_level, MAX_ADAPTATION_LEVEL, "must not exceed MAX (3)");
    }

    #[tokio::test]
    async fn test_adaptation_recover_after_low_pressure_streak() {
        // Start at level 1, send LOW_PRESSURE_STREAK_REQUIRED (8) low samples
        let core = streaming_core_ready().await;
        {
            let mut inner = core.inner.lock().await;
            inner.adaptation_level = 1;
            inner.last_adaptation_at = Some(Instant::now() - Duration::from_secs(5));
        }
        for _ in 0..LOW_PRESSURE_STREAK_REQUIRED {
            core.ingest_client_metrics(low_pressure_client()).await.expect("ingest");
        }
        let inner = core.inner.lock().await;
        assert_eq!(inner.adaptation_level, 0, "8 low-pressure samples should recover level 1→0");
    }

    #[tokio::test]
    async fn test_adaptation_clamps_at_zero() {
        // Already at 0, send 8 low-pressure samples → stays 0
        let core = streaming_core_ready().await;
        {
            let mut inner = core.inner.lock().await;
            inner.adaptation_level = 0;
            inner.last_adaptation_at = Some(Instant::now() - Duration::from_secs(5));
        }
        for _ in 0..LOW_PRESSURE_STREAK_REQUIRED {
            core.ingest_client_metrics(low_pressure_client()).await.expect("ingest");
        }
        let inner = core.inner.lock().await;
        assert_eq!(inner.adaptation_level, 0, "cannot recover below 0");
    }

    #[tokio::test]
    async fn test_adaptation_hold_during_warmup() {
        // stream_started_at < 8s → can_adapt = false → level stays 0
        let core = DaemonCore::new(
            PathBuf::from("/tmp/test-wbeam-b2"),
            15002,
            15003,
        );
        {
            let mut inner = core.inner.lock().await;
            inner.state = STATE_STREAMING.to_string();
            inner.current_pid = Some(99999);
            inner.stream_started_at = Some(Instant::now() - Duration::from_secs(3)); // < 8s
        }
        core.ingest_client_metrics(high_pressure_client()).await.expect("ingest");
        core.ingest_client_metrics(high_pressure_client()).await.expect("ingest");
        let inner = core.inner.lock().await;
        assert_eq!(inner.adaptation_level, 0, "warmup guard must block early adaptation");
    }

    // ── telemetry (unit tests are in infra::telemetry) ─────────────────────
}
