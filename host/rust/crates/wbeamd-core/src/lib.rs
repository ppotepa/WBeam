use std::fs::{self, File, OpenOptions};
use std::io::Write;
use std::net::TcpListener;
use std::os::fd::AsRawFd;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::Arc;
use std::time::{Duration, Instant};

use nix::libc;
use nix::sys::signal::{kill, Signal};
use nix::unistd::Pid;
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

const STATE_IDLE: &str = "IDLE";
const STATE_STARTING: &str = "STARTING";
const STATE_STREAMING: &str = "STREAMING";
const STATE_RECONNECTING: &str = "RECONNECTING";
const STATE_ERROR: &str = "ERROR";
const STATE_STOPPING: &str = "STOPPING";

const DEFAULT_START_TIMEOUT: Duration = Duration::from_secs(45);
const DUPLICATE_START_GUARD: Duration = Duration::from_secs(3);
const ADAPTATION_COOLDOWN: Duration = Duration::from_secs(4);
const HIGH_PRESSURE_STREAK_REQUIRED: u8 = 2;
const LOW_PRESSURE_STREAK_REQUIRED: u8 = 8;
const MAX_ADAPTATION_LEVEL: u8 = 3;
const TARGET_FRAMETIME_MS: f64 = 16.67;

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
    suppress_auto_start_until: Option<Instant>,
    last_reverse_refresh_at: Option<Instant>,
    adaptation_level: u8,
    high_pressure_streak: u8,
    low_pressure_streak: u8,
    last_adaptation_at: Option<Instant>,
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
            suppress_auto_start_until: None,
            last_reverse_refresh_at: None,
            adaptation_level: 0,
            high_pressure_streak: 0,
            low_pressure_streak: 0,
            last_adaptation_at: None,
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
        let active_config = load_runtime_config(&runtime_config_path)
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
        }
        let _ = self.persist_active_config().await;

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

            if !can_adapt {
                inner.high_pressure_streak = 0;
                inner.low_pressure_streak = 0;
                inner.metrics.adaptive_level = inner.adaptation_level;
                inner.metrics.adaptive_action = "hold-warmup".to_string();
                inner.metrics.adaptive_reason = "waiting for stable STREAMING warmup".to_string();
            } else {
                let high = is_high_pressure(&inner, &client);
                let low = is_low_pressure(&inner, &client);

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
            terminate_pid(pid).await;
        }

        let mut inner = self.inner.lock().await;
        inner.current_pid = None;
        inner.state = STATE_IDLE.to_string();
        inner.run_started_at = None;
        inner.stream_started_at = None;
        inner.last_output_at = None;
        inner.last_streaming_line_at = None;

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
        self.ensure_usb_reverse().await;

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
            inner.last_output_at = Some(Instant::now());
            inner.last_streaming_line_at = None;
        }

        let _ = self.persist_active_config().await;

        if let Some(pid) = existing_pid {
            terminate_pid(pid).await;
        }
        self.ensure_stream_port_available()?;

        let stream_script = self.root.join("host/scripts/stream_wayland_portal_h264.py");
        let mut cmd = Command::new("python3");
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
        // C3: engage framed protocol when WBEAM_FRAMED=1; Android auto-detects via magic bytes
        if std::env::var("WBEAM_FRAMED").as_deref() == Ok("1") {
            cmd.arg("--framed");
        }

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
        }

        if let Some(bps) = parse_kbps_line_to_bps(line) {
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

            inner.current_pid = None;
            inner.run_started_at = None;
            inner.stream_started_at = None;
            inner.last_output_at = None;
            inner.last_streaming_line_at = None;

            if inner.state == STATE_STOPPING || inner.state == STATE_IDLE {
                inner.state = STATE_IDLE.to_string();
                info!(run_id, "stream exit ignored in stopping/idle state");
                return;
            }

            inner.last_error = format!("stream exited with code={exit_code}");
            inner.state = STATE_ERROR.to_string();
            inner.metrics.reconnects = inner.metrics.reconnects.saturating_add(1);

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
        let mut should_refresh_reverse = false;

        {
            let mut inner = self.inner.lock().await;
            let now = Instant::now();
            let refresh_due = inner
                .last_reverse_refresh_at
                .map(|last| now.duration_since(last) >= Duration::from_secs(3))
                .unwrap_or(true);
            if refresh_due {
                inner.last_reverse_refresh_at = Some(now);
                should_refresh_reverse = true;
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

        if should_refresh_reverse {
            self.ensure_usb_reverse().await;
        }

        if let Some(pid) = kill_pid {
            warn!(pid, "watchdog terminating stalled stream process");
            terminate_pid(pid).await;
        }
    }

    fn ensure_stream_port_available(&self) -> Result<(), CoreError> {
        if TcpListener::bind(("0.0.0.0", self.stream_port)).is_ok() {
            return Ok(());
        }

        warn!(port = self.stream_port, "stream port busy, trying self-heal");
        let _ = std::process::Command::new("fuser")
            .arg("-k")
            .arg(format!("{}/tcp", self.stream_port))
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();

        std::thread::sleep(Duration::from_millis(200));

        if TcpListener::bind(("0.0.0.0", self.stream_port)).is_ok() {
            return Ok(());
        }

        Err(CoreError::PortBusy(self.stream_port))
    }

    async fn ensure_usb_reverse(&self) {
        let script = self.root.join("host/scripts/usb_reverse.sh");
        match Command::new(script)
            .arg(self.stream_port.to_string())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .await
        {
            Ok(status) if status.success() => {}
            Ok(status) => warn!(code = ?status.code(), "usb_reverse.sh failed"),
            Err(err) => warn!(error = %err, "failed to execute usb_reverse.sh"),
        }

        match Command::new("adb")
            .arg("reverse")
            .arg(format!("tcp:{}", self.control_port))
            .arg(format!("tcp:{}", self.control_port))
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .await
        {
            Ok(status) if status.success() => {}
            Ok(status) => warn!(code = ?status.code(), "adb reverse for control port failed"),
            Err(err) => warn!(error = %err, "failed to execute adb reverse for control port"),
        }
    }

    async fn persist_active_config(&self) -> Result<(), CoreError> {
        let cfg = { self.inner.lock().await.baseline_config.clone() };
        let parent = self
            .runtime_config_path
            .parent()
            .ok_or_else(|| CoreError::Io("invalid runtime config path".to_string()))?;

        fs::create_dir_all(parent).map_err(|e| CoreError::Io(e.to_string()))?;
        let serialized = serde_json::to_string_pretty(&cfg).map_err(|e| CoreError::Io(e.to_string()))?;
        fs::write(&self.runtime_config_path, serialized).map_err(|e| CoreError::Io(e.to_string()))?;
        Ok(())
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

fn is_high_pressure(inner: &Inner, client: &ClientMetricsRequest) -> bool {
    let target = inner.active_config.fps.max(24) as f64;
    let frametime_ms = if client.present_fps > 0.0 {
        1000.0 / client.present_fps.max(0.1)
    } else {
        0.0
    };
    client.decode_time_ms_p95 > 12.0
        || client.render_time_ms_p95 > 7.0
        || client.transport_queue_depth >= 3
        || client.decode_queue_depth >= 2
        || client.render_queue_depth >= 1
        || client.too_late_frames > 0
        || (frametime_ms > 0.0 && frametime_ms > TARGET_FRAMETIME_MS * 1.20)
        || client.present_fps < target * 0.90
}

fn is_low_pressure(inner: &Inner, client: &ClientMetricsRequest) -> bool {
    let target = inner.active_config.fps.max(24) as f64;
    let frametime_ms = if client.present_fps > 0.0 {
        1000.0 / client.present_fps.max(0.1)
    } else {
        0.0
    };
    client.decode_time_ms_p95 > 0.0
        && client.decode_time_ms_p95 < 6.5
        && client.render_time_ms_p95 < 3.5
        && client.transport_queue_depth == 0
        && client.decode_queue_depth == 0
        && client.render_queue_depth == 0
        && (frametime_ms == 0.0 || frametime_ms <= TARGET_FRAMETIME_MS * 1.02)
        && client.present_fps >= target * 0.98
}

fn adaptation_reason(client: &ClientMetricsRequest, high: bool, low: bool) -> String {
    if high {
        return format!(
            "decode_p95={:.2} render_p95={:.2} q={}/{}/{} fps={:.1}",
            client.decode_time_ms_p95,
            client.render_time_ms_p95,
            client.transport_queue_depth,
            client.decode_queue_depth,
            client.render_queue_depth,
            client.present_fps
        );
    }
    if low {
        return format!(
            "recovery decode_p95={:.2} render_p95={:.2} fps={:.1}",
            client.decode_time_ms_p95, client.render_time_ms_p95, client.present_fps
        );
    }
    "stable".to_string()
}

fn config_for_level(base: &ActiveConfig, level: u8) -> ActiveConfig {
    let mut cfg = base.clone();

    let (scale_pct, fps_pct, bitrate_pct) = match level {
        0 => (100, 100, 100),
        1 => (90, 90, 85),
        2 => (80, 80, 70),
        _ => (70, 70, 55),
    };

    if let Some((w, h)) = parse_size(&cfg.size) {
        let mut scaled_w = (w.saturating_mul(scale_pct) / 100).clamp(640, 3840);
        let mut scaled_h = (h.saturating_mul(scale_pct) / 100).clamp(360, 2160);
        if scaled_w % 2 == 1 {
            scaled_w = scaled_w.saturating_sub(1);
        }
        if scaled_h % 2 == 1 {
            scaled_h = scaled_h.saturating_sub(1);
        }
        cfg.size = format!("{scaled_w}x{scaled_h}");
    }

    cfg.fps = (base.fps.saturating_mul(fps_pct) / 100).clamp(30, 120);
    cfg.bitrate_kbps = (base.bitrate_kbps.saturating_mul(bitrate_pct) / 100).clamp(4_000, 120_000);
    cfg
}

fn parse_size(size: &str) -> Option<(u32, u32)> {
    let (w, h) = size.split_once('x')?;
    Some((w.parse().ok()?, h.parse().ok()?))
}

fn load_runtime_config(path: &Path) -> Option<ActiveConfig> {
    let raw = fs::read_to_string(path).ok()?;
    let parsed: ActiveConfig = serde_json::from_str(&raw).ok()?;

    let patch = ConfigPatch {
        profile: Some(parsed.profile),
        encoder: Some(parsed.encoder),
        cursor_mode: Some(parsed.cursor_mode),
        size: Some(parsed.size),
        fps: Some(parsed.fps),
        bitrate_kbps: Some(parsed.bitrate_kbps),
        debug_fps: Some(parsed.debug_fps),
    };

    validate_config(patch, &ActiveConfig::balanced_default()).ok()
}

fn parse_kbps_line_to_bps(line: &str) -> Option<u64> {
    // Example: [libx264 @ ...] kb/s:58.61
    let marker = "kb/s:";
    let idx = line.find(marker)?;
    let part = line[idx + marker.len()..].trim();
    let value = part.split_whitespace().next()?;
    let kbps: f64 = value.parse().ok()?;
    Some((kbps * 1000.0) as u64)
}

async fn terminate_pid(pid: u32) {
    let pid = Pid::from_raw(pid as i32);
    let _ = kill(pid, Signal::SIGTERM);
    sleep(Duration::from_millis(300)).await;
    let _ = kill(pid, Signal::SIGKILL);
}
