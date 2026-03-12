use std::collections::{BTreeMap, HashMap};
use std::fs::{File, OpenOptions};
use std::io::Write;
#[cfg(unix)]
use std::os::fd::AsRawFd;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

#[cfg(unix)]
use nix::libc;
use thiserror::Error;
use tokio::io::{AsyncBufReadExt, BufReader};
use tokio::process::Command;
use tokio::sync::{mpsc, Mutex};
use tokio::time::sleep;
use tracing::{debug, error, info, warn};
use wbeamd_api::{
    valid_values, validate_config_with_presets, ActiveConfig, BaseResponse, ClientMetricsRequest,
    ClientMetricsResponse, ConfigPatch, EffectiveRuntimeConfig, ErrorResponse, HealthResponse,
    HostProbeResponse, KpiSnapshot, MetricsResponse, MetricsSnapshot, PresetsResponse, StatusResponse,
    ValidationError, VirtualDisplayDoctorResponse, VirtualDisplayProbeResponse,
};

pub mod domain;
pub mod infra;
pub mod resolver;

use domain::policy::{
    adaptation_reason, config_for_level, is_high_pressure, is_low_pressure,
    HIGH_PRESSURE_STREAK_REQUIRED, LOW_PRESSURE_STREAK_REQUIRED, MAX_ADAPTATION_LEVEL,
};
#[allow(unused_imports)]
use domain::state::{
    STATE_ERROR, STATE_IDLE, STATE_RECONNECTING, STATE_STARTING, STATE_STOPPING, STATE_STREAMING,
};
use infra::process as proc;
use infra::{adb, config_store, display_backends, host_probe, telemetry};

const DEFAULT_START_TIMEOUT: Duration = Duration::from_secs(45);
const DUPLICATE_START_GUARD: Duration = Duration::from_secs(3);
const ADAPTATION_COOLDOWN: Duration = Duration::from_secs(domain::policy::ADAPTATION_COOLDOWN_SECS);
const NO_PRESENT_RESTART_STREAK_REQUIRED: u8 = 4;
const NO_PRESENT_RESTART_COOLDOWN: Duration = Duration::from_secs(15);
const NO_PRESENT_MIN_RECV_FPS: f64 = 10.0;
const NO_PRESENT_MAX_PRESENT_FPS: f64 = 1.0;
const REVERSE_REFRESH_BACKSTOP: Duration = Duration::from_secs(120);

fn now_unix_ms() -> u128 {
    match SystemTime::now().duration_since(UNIX_EPOCH) {
        Ok(dur) => dur.as_millis(),
        Err(_) => 0,
    }
}

fn parse_bool_token(raw: &str) -> bool {
    matches!(
        raw.trim().to_ascii_lowercase().as_str(),
        "1" | "true" | "yes" | "on"
    )
}

fn parse_effective_runtime_line(line: &str, reason: &str) -> Option<EffectiveRuntimeConfig> {
    let prefix = "[wbeam-effective]";
    let body = line.trim().strip_prefix(prefix)?.trim();
    let mut map: HashMap<&str, &str> = HashMap::new();
    for token in body.split_whitespace() {
        let Some((k, v)) = token.split_once('=') else {
            continue;
        };
        map.insert(k.trim(), v.trim());
    }
    let parse_u32 = |key: &str, default: u32| -> u32 {
        map.get(key)
            .and_then(|v| v.parse::<u32>().ok())
            .unwrap_or(default)
    };
    let parse_i32 = |key: &str, default: i32| -> i32 {
        map.get(key)
            .and_then(|v| v.parse::<i32>().ok())
            .unwrap_or(default)
    };
    let parse_string = |key: &str, default: &str| -> String {
        map.get(key)
            .map(|v| (*v).to_string())
            .unwrap_or_else(|| default.to_string())
    };
    let parse_size = || -> String {
        let w = parse_u32("size", 0);
        let h = parse_u32("height", 0);
        if w > 0 && h > 0 {
            return format!("{w}x{h}");
        }
        if let Some(raw) = map.get("size") {
            if raw.contains('x') {
                return (*raw).to_string();
            }
        }
        "0x0".to_string()
    };

    Some(EffectiveRuntimeConfig {
        requested_encoder: parse_string("requested_encoder", "unknown"),
        resolved_backend: parse_string("resolved_backend", "unknown"),
        raw_format: parse_string("raw_format", "unknown"),
        size: parse_size(),
        fps: parse_u32("fps", 0),
        bitrate_kbps: parse_u32("bitrate_kbps", 0),
        cursor_mode: parse_string("cursor_mode", "unknown"),
        gop: parse_u32("gop", 0),
        intra_only: map
            .get("intra_only")
            .map(|v| parse_bool_token(v))
            .unwrap_or(false),
        stream_mode: parse_string("stream_mode", "unknown"),
        queue_max_buffers: parse_u32("queue_max_buffers", 0),
        queue_max_time_ms: parse_u32("queue_max_time_ms", 0),
        appsink_max_buffers: parse_u32("appsink_max_buffers", 0),
        appsink_drop: map
            .get("appsink_drop")
            .map(|v| parse_bool_token(v))
            .unwrap_or(false),
        appsink_sync: map
            .get("appsink_sync")
            .map(|v| parse_bool_token(v))
            .unwrap_or(false),
        capture_backend: parse_string("capture_backend", "unknown"),
        parse_mode: parse_string("parse_mode", "unknown"),
        timeout_pull_ms: parse_u32("timeout_pull_ms", 0),
        timeout_write_ms: parse_u32("timeout_write_ms", 0),
        timeout_disconnect: map
            .get("timeout_disconnect")
            .map(|v| parse_bool_token(v))
            .unwrap_or(false),
        videorate_drop_only: map
            .get("videorate_drop_only")
            .map(|v| parse_bool_token(v))
            .unwrap_or(false),
        pipewire_keepalive_ms: parse_i32("pipewire_keepalive_ms", 0),
        snapshot_unix_ms: now_unix_ms(),
        snapshot_reason: reason.to_string(),
    })
}

fn user_wbeam_config_path() -> Option<PathBuf> {
    let base = std::env::var("XDG_CONFIG_HOME")
        .ok()
        .filter(|v| !v.trim().is_empty())
        .map(PathBuf::from)
        .or_else(|| {
            std::env::var("HOME")
                .ok()
                .filter(|v| !v.trim().is_empty())
                .map(|home| PathBuf::from(home).join(".config"))
        })?;
    Some(base.join("wbeam/wbeam.conf"))
}

fn user_wbeam_dir() -> Option<PathBuf> {
    user_wbeam_config_path().and_then(|p| p.parent().map(PathBuf::from))
}

fn ensure_user_wbeam_config(root: &Path) -> Option<PathBuf> {
    let user_cfg = user_wbeam_config_path()?;
    if user_cfg.exists() {
        return Some(user_cfg);
    }
    if let Some(parent) = user_cfg.parent() {
        let _ = std::fs::create_dir_all(parent);
    }
    let template = root.join("config/wbeam.conf");
    if template.exists() {
        let _ = std::fs::copy(&template, &user_cfg);
    } else {
        let _ = std::fs::write(&user_cfg, "");
    }
    Some(user_cfg)
}

fn normalize_session_label(label: &str) -> String {
    label
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '-' || c == '_' {
                c
            } else {
                '_'
            }
        })
        .collect::<String>()
}

fn load_wbeam_config(root: &Path) -> HashMap<String, String> {
    let mut files: Vec<PathBuf> = Vec::new();
    if let Some(user_cfg) = ensure_user_wbeam_config(root) {
        files.push(user_cfg);
    } else {
        files.push(root.join("config/wbeam.conf"));
    }

    let mut map = HashMap::new();
    for file in files {
        let Ok(raw) = std::fs::read_to_string(file) else {
            continue;
        };
        for line in raw.lines() {
            let line = line.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }
            let Some((k, v)) = line.split_once('=') else {
                continue;
            };
            let key = k.trim();
            if !key.starts_with("WBEAM_") {
                continue;
            }
            if map.contains_key(key) {
                continue;
            }
            let mut value = v.trim().to_string();
            let bytes = value.as_bytes();
            if bytes.len() >= 2
                && ((bytes[0] == b'"' && bytes[bytes.len() - 1] == b'"')
                    || (bytes[0] == b'\'' && bytes[bytes.len() - 1] == b'\''))
            {
                value = value[1..value.len() - 1].to_string();
            }
            map.insert(key.to_string(), value);
        }
    }
    map
}

fn wbeam_setting(settings: &HashMap<String, String>, key: &str) -> Option<String> {
    if let Ok(value) = std::env::var(key) {
        let trimmed = value.trim();
        if !trimmed.is_empty() {
            return Some(trimmed.to_string());
        }
    }
    settings
        .get(key)
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
}

fn wbeam_setting_bool(settings: &HashMap<String, String>, key: &str, default: bool) -> bool {
    wbeam_setting(settings, key)
        .map(|v| {
            matches!(
                v.trim().to_ascii_lowercase().as_str(),
                "1" | "true" | "yes" | "on"
            )
        })
        .unwrap_or(default)
}

fn wbeam_setting_u64(settings: &HashMap<String, String>, key: &str, default: u64) -> u64 {
    wbeam_setting(settings, key)
        .and_then(|v| v.parse::<u64>().ok())
        .unwrap_or(default)
}

fn resolve_xauthority_for_capture() -> Option<PathBuf> {
    let uid = std::env::var("UID")
        .ok()
        .filter(|v| !v.trim().is_empty())
        .or_else(|| std::env::var("EUID").ok())
        .unwrap_or_else(|| "1000".to_string());
    if let Ok(path) = std::env::var("XAUTHORITY") {
        let p = PathBuf::from(path);
        if p.exists() {
            return Some(p);
        }
    }

    if let Ok(entries) = std::fs::read_dir("/tmp") {
        let mut tmp_candidates: Vec<PathBuf> = entries
            .flatten()
            .map(|e| e.path())
            .filter(|p| {
                p.is_file()
                    && p.file_name()
                        .and_then(|n| n.to_str())
                        .map(|n| n.starts_with("xauth_"))
                        .unwrap_or(false)
            })
            .collect();
        tmp_candidates.sort_by_key(|p| {
            std::fs::metadata(p)
                .and_then(|m| m.modified())
                .ok()
                .unwrap_or(std::time::SystemTime::UNIX_EPOCH)
        });
        if let Some(last) = tmp_candidates.pop() {
            return Some(last);
        }
    }

    let run_dir = PathBuf::from(format!("/run/user/{uid}"));
    if run_dir.exists() {
        let mut candidates: Vec<PathBuf> = Vec::new();
        if let Ok(entries) = std::fs::read_dir(&run_dir) {
            for entry in entries.flatten() {
                let path = entry.path();
                if let Some(name) = path.file_name().and_then(|n| n.to_str()) {
                    if name.starts_with("xauth_") && path.is_file() {
                        candidates.push(path);
                    }
                }
            }
        }
        candidates.sort_by_key(|p| {
            std::fs::metadata(p)
                .and_then(|m| m.modified())
                .ok()
                .unwrap_or(std::time::SystemTime::UNIX_EPOCH)
        });
        if let Some(last) = candidates.pop() {
            return Some(last);
        }
    }

    if let Ok(home) = std::env::var("HOME") {
        let p = Path::new(&home).join(".Xauthority");
        if p.exists() {
            return Some(p);
        }
    }
    None
}

fn runtime_config_path_for_session(root: &Path, session_label: Option<&str>) -> PathBuf {
    let config_dir = user_wbeam_dir().unwrap_or_else(|| root.join("host/rust/config"));
    if let Some(label) = session_label {
        let normalized = normalize_session_label(label);
        if !normalized.is_empty() {
            if let Some(serial) = normalized.strip_prefix("serial-") {
                return config_dir.join("devices").join(format!("{serial}.json"));
            }
            return config_dir.join(format!("runtime_state.{normalized}.json"));
        }
    }
    config_dir.join("runtime_state.json")
}

fn load_presets_from_training_files(root: &Path) -> Option<BTreeMap<String, ActiveConfig>> {
    // New canonical path: config/training/profiles.json
    // Legacy archived fallback: archive/legacy/proto/config/profiles.json
    let candidates = [
        root.join("config/training/profiles.json"),
        root.join("archive/legacy/proto/config/profiles.json"),
    ];

    for path in candidates {
        let raw = match std::fs::read_to_string(&path) {
            Ok(v) => v,
            Err(e) => {
                debug!("presets: cannot read {}: {e}", path.display());
                continue;
            }
        };
        let value: serde_json::Value = match serde_json::from_str(&raw) {
            Ok(v) => v,
            Err(e) => {
                warn!("presets: invalid JSON in {}: {e}", path.display());
                continue;
            }
        };
        let Some(profiles) = value.get("profiles").and_then(|v| v.as_object()) else {
            warn!("presets: missing .profiles object in {}", path.display());
            continue;
        };

        let mut out = BTreeMap::new();
        for (name, node) in profiles {
            let values = node.get("values").and_then(|v| v.as_object());
            let size = values
                .and_then(|v| v.get("PROTO_CAPTURE_SIZE"))
                .and_then(|v| v.as_str())
                .unwrap_or("1280x720")
                .to_string();
            let fps = values
                .and_then(|v| v.get("PROTO_CAPTURE_FPS"))
                .and_then(|v| v.as_u64())
                .unwrap_or(60) as u32;
            let bitrate_kbps = values
                .and_then(|v| v.get("PROTO_CAPTURE_BITRATE_KBPS"))
                .and_then(|v| v.as_u64())
                .unwrap_or(10_000) as u32;

            out.insert(
                name.clone(),
                ActiveConfig {
                    profile: name.clone(),
                    encoder: "h264".to_string(),
                    cursor_mode: "embedded".to_string(),
                    size,
                    fps,
                    bitrate_kbps,
                    debug_fps: 0,
                    intra_only: false,
                },
            );
        }

        if out.is_empty() {
            warn!("presets: no profiles loaded from {}", path.display());
            continue;
        }
        info!(
            "presets: loaded {} profile(s) from {}",
            out.len(),
            path.display()
        );
        return Some(out);
    }

    warn!("presets: no training profiles found in config/training or archive/legacy/proto/config");
    None
}

fn default_config_from_presets(presets: &BTreeMap<String, ActiveConfig>) -> ActiveConfig {
    for key in ["baseline"] {
        if let Some(cfg) = presets.get(key) {
            return cfg.clone();
        }
    }
    if let Some((_name, cfg)) = presets.iter().next() {
        return cfg.clone();
    }
    ActiveConfig::balanced_default()
}

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
    #[error("unsupported host mode: {0}")]
    UnsupportedHost(String),
}

#[derive(Debug)]
struct Inner {
    state: String,
    active_config: ActiveConfig,
    effective_runtime_config: Option<EffectiveRuntimeConfig>,
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
    presets: BTreeMap<String, ActiveConfig>,
    requested_display_mode: String,
    display_runtime: Option<display_backends::RuntimeHandle>,
    pending_runtime_snapshot_reason: String,
}

impl Inner {
    fn new(
        host_name: String,
        active_config: ActiveConfig,
        presets: BTreeMap<String, ActiveConfig>,
    ) -> Self {
        Self {
            state: STATE_IDLE.to_string(),
            active_config: active_config.clone(),
            effective_runtime_config: None,
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
            presets,
            requested_display_mode: "duplicate".to_string(),
            display_runtime: None,
            pending_runtime_snapshot_reason: "init".to_string(),
        }
    }
}

pub struct InstanceLock {
    file: Option<File>,
    #[cfg(windows)]
    path: PathBuf,
}

impl Drop for InstanceLock {
    fn drop(&mut self) {
        #[cfg(unix)]
        if let Some(file) = self.file.as_ref() {
            unsafe {
                libc::flock(file.as_raw_fd(), libc::LOCK_UN);
            }
        }
        #[cfg(windows)]
        {
            // Close handle before cleanup, otherwise Windows keeps the file in-use.
            self.file.take();
            let _ = std::fs::remove_file(&self.path);
        }
    }
}

#[derive(Clone)]
pub struct DaemonCore {
    inner: Arc<Mutex<Inner>>,
    root: PathBuf,
    settings: Arc<HashMap<String, String>>,
    runtime_config_path: PathBuf,
    host_probe: host_probe::HostProbe,
    stream_port: u16,
    control_port: u16,
    target_serial: Option<String>,
    exit_tx: mpsc::UnboundedSender<(u64, i32)>,
    auto_start: bool,
    allow_live_adaptive_restart: bool,
    reconnect_backoff: Duration,
    stop_cooldown: Duration,
    start_timeout: Duration,
}

impl DaemonCore {
    fn normalize_display_mode(mode: Option<&str>) -> &'static str {
        display_backends::normalize_requested_mode(mode).as_str()
    }

    pub async fn set_display_mode(&self, mode: Option<&str>) {
        let normalized = Self::normalize_display_mode(mode);
        let mut inner = self.inner.lock().await;
        inner.requested_display_mode = normalized.to_string();
    }

    async fn stop_virtual_display_if_any(&self) {
        let runtime = {
            let mut inner = self.inner.lock().await;
            inner.display_runtime.take()
        };
        if let Some(handle) = runtime {
            display_backends::stop_runtime(handle).await;
        }
    }

    pub fn acquire_lock(path: &Path) -> Result<InstanceLock, CoreError> {
        #[cfg(unix)]
        let mut file = {
            let file = OpenOptions::new()
                .create(true)
                .read(true)
                .write(true)
                .open(path)
                .map_err(|e| CoreError::Io(e.to_string()))?;
            let rc = unsafe { libc::flock(file.as_raw_fd(), libc::LOCK_EX | libc::LOCK_NB) };
            if rc != 0 {
                return Err(CoreError::LockHeld(path.display().to_string()));
            }
            file
        };
        #[cfg(windows)]
        let mut file = {
            match OpenOptions::new()
                .create_new(true)
                .read(true)
                .write(true)
                .open(path)
            {
                Ok(file) => file,
                Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => {
                    return Err(CoreError::LockHeld(path.display().to_string()));
                }
                Err(e) => return Err(CoreError::Io(e.to_string())),
            }
        };

        let pid = std::process::id();
        file.set_len(0).map_err(|e| CoreError::Io(e.to_string()))?;
        file.write_all(pid.to_string().as_bytes())
            .map_err(|e| CoreError::Io(e.to_string()))?;

        Ok(InstanceLock {
            file: Some(file),
            #[cfg(windows)]
            path: path.to_path_buf(),
        })
    }

    fn session_suffix(&self) -> String {
        self.target_serial
            .as_deref()
            .unwrap_or("default")
            .chars()
            .map(|ch| {
                if ch.is_ascii_alphanumeric() || ch == '-' || ch == '_' {
                    ch
                } else {
                    '_'
                }
            })
            .collect::<String>()
    }

    fn trainer_active_marker_path(&self) -> PathBuf {
        PathBuf::from(format!(
            "/tmp/wbeam-trainer-active-{}-{}.flag",
            self.session_suffix(),
            self.stream_port
        ))
    }

    fn trainer_run_active(&self) -> bool {
        self.trainer_active_marker_path().exists()
    }

    pub fn new(root: PathBuf, stream_port: u16, control_port: u16) -> Self {
        Self::new_for_session(root, stream_port, control_port, None, None)
    }

    pub fn new_for_session(
        root: PathBuf,
        stream_port: u16,
        control_port: u16,
        session_label: Option<String>,
        target_serial: Option<String>,
    ) -> Self {
        let settings = Arc::new(load_wbeam_config(&root));
        let host_name = hostname::get()
            .ok()
            .and_then(|h| h.into_string().ok())
            .unwrap_or_else(|| "unknown-host".to_string());

        let runtime_config_path = runtime_config_path_for_session(&root, session_label.as_deref());
        // Merge built-in presets with training-domain presets (training profiles can override).
        // Canonical runtime profile is now "baseline"; legacy profile names are
        // canonicalized in API validation to preserve backward compatibility.
        let mut presets = wbeamd_api::presets();
        if let Some(training_presets) = load_presets_from_training_files(&root) {
            for (k, v) in training_presets {
                presets.insert(k, v);
            }
        }
        let restored_config =
            config_store::load_runtime_config_with_presets(&runtime_config_path, &presets);
        let active_config = restored_config
            .clone()
            .unwrap_or_else(|| default_config_from_presets(&presets));
        if restored_config.is_none() {
            let _ = config_store::persist_config(&runtime_config_path, &active_config)
                .map_err(|e| tracing::warn!("persist initial runtime config: {e}"));
        }

        let host_probe = host_probe::HostProbe::detect();
        info!(
            os = host_probe.os_name(),
            session = host_probe.session_name(),
            desktop = host_probe.desktop_name(),
            capture_mode = host_probe.capture_mode_name(),
            remote = host_probe.is_remote,
            display = host_probe.display.as_deref().unwrap_or("-"),
            wayland = host_probe.wayland_display.as_deref().unwrap_or("-"),
            "host probe initialized"
        );

        let (exit_tx, mut exit_rx) = mpsc::unbounded_channel::<(u64, i32)>();

        let core = Self {
            inner: Arc::new(Mutex::new(Inner::new(host_name, active_config, presets))),
            root,
            settings: settings.clone(),
            runtime_config_path,
            host_probe,
            stream_port,
            control_port,
            target_serial,
            exit_tx,
            auto_start: true,
            allow_live_adaptive_restart: wbeam_setting_bool(
                &settings,
                "WBEAM_ALLOW_LIVE_ADAPTIVE_RESTART",
                false,
            ),
            reconnect_backoff: Duration::from_secs(1),
            stop_cooldown: Duration::from_secs(12),
            start_timeout: Duration::from_secs(wbeam_setting_u64(
                &settings,
                "WBEAM_START_TIMEOUT_SEC",
                DEFAULT_START_TIMEOUT.as_secs(),
            )),
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

    pub async fn host_probe(&self) -> HostProbeResponse {
        let inner = self.inner.lock().await;
        HostProbeResponse {
            base: self.base_from_inner(&inner),
            ok: true,
            os: self.host_probe.os_name().to_string(),
            session: self.host_probe.session_name().to_string(),
            desktop: self.host_probe.desktop_name().to_string(),
            capture_mode: self.host_probe.capture_mode_name().to_string(),
            remote: self.host_probe.is_remote,
            display: self.host_probe.display.clone(),
            wayland_display: self.host_probe.wayland_display.clone(),
            supported: self.host_probe.supports_streaming(),
        }
    }

    pub async fn virtual_probe(&self) -> VirtualDisplayProbeResponse {
        let backend = self.host_probe.capture_mode_name().to_string();
        let probe = display_backends::virtual_monitor_probe(&self.host_probe);

        VirtualDisplayProbeResponse {
            base: self.base_response().await,
            ok: true,
            host_backend: backend,
            virtual_supported: probe.supported,
            resolver: probe.resolver,
            missing_deps: probe.missing_deps,
            install_hint: probe.hint,
        }
    }

    pub async fn virtual_doctor(&self) -> VirtualDisplayDoctorResponse {
        let probe = self.virtual_probe().await;
        let (ok, message, actionable) = if probe.virtual_supported {
            (true, "Virtual desktop backend is ready.".to_string(), false)
        } else if !probe.missing_deps.is_empty() {
            (
                false,
                format!("Missing dependency: {}", probe.missing_deps.join(", ")),
                true,
            )
        } else {
            (
                false,
                "Virtual monitor mode is unavailable in current host session.".to_string(),
                false,
            )
        };

        VirtualDisplayDoctorResponse {
            base: probe.base,
            ok,
            message,
            actionable,
            host_backend: probe.host_backend,
            resolver: probe.resolver,
            missing_deps: probe.missing_deps,
            install_hint: probe.install_hint,
        }
    }

    pub async fn presets(&self) -> PresetsResponse {
        let inner = self.inner.lock().await;
        PresetsResponse {
            base: self.base_from_inner(&inner),
            ok: true,
            presets: inner.presets.clone(),
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
            (
                inner.active_config.clone(),
                inner.current_pid,
                inner.state.clone(),
            )
        };

        let presets = { self.inner.lock().await.presets.clone() };
        let cfg = match validate_config_with_presets(patch, &current_cfg, &presets) {
            Ok(cfg) => cfg,
            Err(ValidationError::InvalidProfile) => {
                // Runtime config can be stale (e.g. old pre-baseline profile names).
                // Auto-heal to a valid preset so /start does not hard-fail.
                let fallback = default_config_from_presets(&presets);
                warn!(
                    invalid_profile = %current_cfg.profile,
                    fallback_profile = %fallback.profile,
                    "invalid runtime profile; auto-healing to fallback"
                );
                {
                    let mut inner = self.inner.lock().await;
                    inner.active_config = fallback.clone();
                    inner.baseline_config = fallback.clone();
                }
                let _ = config_store::persist_config(&self.runtime_config_path, &fallback)
                    .map_err(|e| tracing::warn!("persist config during profile auto-heal: {e}"));
                fallback
            }
            Err(err) => return Err(err.into()),
        };
        let already_running = current_pid.is_some()
            && cfg == current_cfg
            && matches!(
                current_state.as_str(),
                STATE_STARTING | STATE_STREAMING | STATE_RECONNECTING
            );

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
        self.start_with_config(cfg, "start_request").await?;
        Ok(self.status().await)
    }

    pub async fn apply(&self, patch: ConfigPatch) -> Result<StatusResponse, CoreError> {
        let (cfg, prev_cfg, was_running) = {
            let inner = self.inner.lock().await;
            let cfg = validate_config_with_presets(patch, &inner.active_config, &inner.presets)?;
            (
                cfg,
                inner.active_config.clone(),
                inner.current_pid.is_some(),
            )
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
            self.start_with_config(cfg, "apply_request").await?;
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
            client
                .trace_id
                .map(|t| format!("{:#018x}", t))
                .unwrap_or_else(|| "-".to_string()),
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
        let trainer_run_active = self.trainer_run_active();

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
                inner.metrics.drops = client.dropped_frames.saturating_add(client.too_late_frames);
            }

            if client.recv_bps > 0 {
                inner.metrics.bitrate_actual_bps = client.recv_bps;
            }

            // P2.3: append JSONL telemetry record
            let telemetry_run_id = inner.run_id; // capture before mutable borrow
            if let Some(ref mut f) = inner.telemetry_file {
                let mut rec = serde_json::to_value(&client).unwrap_or_default();
                if let serde_json::Value::Object(ref mut m) = rec {
                    m.insert(
                        "run_id".to_string(),
                        serde_json::Value::from(telemetry_run_id),
                    );
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
            let forced_no_present_restart = no_present_restart_ready
                && inner.no_present_streak >= NO_PRESENT_RESTART_STREAK_REQUIRED;

            if forced_no_present_restart && !trainer_run_active {
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
            } else if forced_no_present_restart && trainer_run_active {
                inner.no_present_streak = 0;
                inner.metrics.adaptive_level = inner.adaptation_level;
                inner.metrics.adaptive_action = "recover-hold-training".to_string();
                inner.metrics.adaptive_reason = format!(
                    "training run active; suppress no-present restart present_fps={:.1} recv_fps={:.1}",
                    client.present_fps, client.recv_fps
                );
                inner.last_error =
                    "training run active: no-present recovery restart suppressed".to_string();
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
                } else if cooldown_ready
                    && inner.low_pressure_streak >= LOW_PRESSURE_STREAK_REQUIRED
                {
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

                    let target_cfg =
                        config_for_level(&inner.baseline_config, inner.adaptation_level);
                    if target_cfg != inner.active_config && inner.current_pid.is_some() {
                        if self.allow_live_adaptive_restart && !trainer_run_active {
                            inner.active_config = target_cfg.clone();
                            inner.metrics.restart_count =
                                inner.metrics.restart_count.saturating_add(1);
                            restart_cfg = Some(target_cfg);
                        } else if trainer_run_active {
                            inner.metrics.adaptive_action =
                                format!("{}-hold-training", inner.metrics.adaptive_action);
                            inner.metrics.adaptive_reason = format!(
                                "{} | training run active; suppress restart size={} fps={} bitrate={}",
                                reason, target_cfg.size, target_cfg.fps, target_cfg.bitrate_kbps
                            );
                            inner.last_error = format!(
                                "adaptive hold during training L{}",
                                inner.adaptation_level
                            );
                        } else {
                            inner.metrics.adaptive_action =
                                format!("{}-pending", inner.metrics.adaptive_action);
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
            self.start_with_config(cfg, "adaptive_restart").await?;
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
        self.stop_virtual_display_if_any().await;

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

    async fn mark_start_failed(&self, message: String) {
        let mut inner = self.inner.lock().await;
        inner.current_pid = None;
        inner.state = STATE_IDLE.to_string();
        inner.last_error = message;
        inner.run_started_at = None;
        inner.stream_started_at = None;
        inner.last_output_at = None;
        inner.last_streaming_line_at = None;
        inner.telemetry_file = None;
        inner.no_present_streak = 0;
    }

    async fn start_with_config(&self, cfg: ActiveConfig, reason: &str) -> Result<(), CoreError> {
        let requested_display_mode = {
            let inner = self.inner.lock().await;
            inner.requested_display_mode.clone()
        };
        let requested_mode =
            display_backends::normalize_requested_mode(Some(&requested_display_mode));
        let mut launch_size = cfg.size.clone();
        if !self.host_probe.supports_streaming() {
            let reason = self.host_probe.unsupported_reason();
            {
                let mut inner = self.inner.lock().await;
                inner.state = STATE_IDLE.to_string();
                inner.last_error = reason.clone();
            }
            return Err(CoreError::UnsupportedHost(reason));
        }

        if requested_mode.is_virtual() {
            if let Some(target_size) = adb::device_resolution(self.target_serial.as_deref()).await {
                if launch_size != target_size {
                    info!(
                        serial = self.target_serial.as_deref().unwrap_or("auto"),
                        from = %launch_size,
                        to = %target_size,
                        "virtual mode: overriding stream size to target device resolution"
                    );
                    launch_size = target_size;
                }
            } else {
                warn!(
                    serial = self.target_serial.as_deref().unwrap_or("auto"),
                    configured = %launch_size,
                    "virtual mode: could not detect target device resolution; using configured size"
                );
            }
        }

        {
            let mut inner = self.inner.lock().await;
            inner.last_reverse_refresh_at = Some(Instant::now());
        }
        // Do not block /start HTTP response on adb reverse, because Android API17
        // client timeout is short (~1.5s) and reverse may fail/lag on tethered links.
        {
            let root = self.root.clone();
            let stream_port = self.stream_port;
            let control_port = self.control_port;
            let target_serial = self.target_serial.clone();
            tokio::spawn(async move {
                adb::ensure_usb_reverse(
                    &root,
                    stream_port,
                    control_port,
                    "start",
                    target_serial.as_deref(),
                )
                .await;
            });
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
                && matches!(
                    inner.state.as_str(),
                    STATE_STARTING | STATE_STREAMING | STATE_RECONNECTING
                )
            {
                info!(state = %inner.state, "suppressing duplicate start request");
                return Ok(());
            }
        }

        let run_id;
        let existing_pid;
        let provisional_effective;
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
            inner.pending_runtime_snapshot_reason = reason.to_string();
            provisional_effective = EffectiveRuntimeConfig {
                requested_encoder: cfg.encoder.clone(),
                resolved_backend: "unknown".to_string(),
                raw_format: "unknown".to_string(),
                size: launch_size.clone(),
                fps: cfg.fps,
                bitrate_kbps: cfg.bitrate_kbps,
                cursor_mode: cfg.cursor_mode.clone(),
                gop: if cfg.intra_only { 1 } else { (cfg.fps / 8).max(6) },
                intra_only: cfg.intra_only,
                stream_mode: "unknown".to_string(),
                queue_max_buffers: 0,
                queue_max_time_ms: 0,
                appsink_max_buffers: 0,
                appsink_drop: false,
                appsink_sync: false,
                capture_backend: self.host_probe.capture_mode_name().to_string(),
                parse_mode: "unknown".to_string(),
                timeout_pull_ms: 0,
                timeout_write_ms: 0,
                timeout_disconnect: false,
                videorate_drop_only: false,
                pipewire_keepalive_ms: 0,
                snapshot_unix_ms: now_unix_ms(),
                snapshot_reason: format!("{reason}:provisional"),
            };
            inner.effective_runtime_config = Some(provisional_effective.clone());
        }
        self.persist_effective_runtime_snapshot(run_id, &provisional_effective);

        {
            let cfg = { self.inner.lock().await.baseline_config.clone() };
            let _ = config_store::persist_config(&self.runtime_config_path, &cfg)
                .map_err(|e| tracing::warn!("persist config: {e}"));
        }

        if let Some(pid) = existing_pid {
            proc::terminate_pid(pid).await;
        }
        if adb::ensure_stream_port_available(self.stream_port).is_err() {
            let err = CoreError::PortBusy(self.stream_port);
            self.mark_start_failed(err.to_string()).await;
            return Err(err);
        }

        let mut use_rust_streamer =
            wbeam_setting_bool(&self.settings, "WBEAM_USE_RUST_STREAMER", true);
        let rust_streamer_bin = wbeam_setting(&self.settings, "WBEAM_RUST_STREAMER_BIN")
            .map(PathBuf::from)
            .unwrap_or_else(|| {
                self.root
                    .join("host/rust/target/release/wbeamd-streamer")
            });

        let mut cmd;
        let capture_backend = self.host_probe.capture_mode_name();
        self.stop_virtual_display_if_any().await;
        let serial_hint = self.target_serial.as_deref().unwrap_or("default");
        let activation = match display_backends::activate_start(
            &self.host_probe,
            requested_mode,
            serial_hint,
            &launch_size,
        ) {
            Ok(v) => v,
            Err(e) => {
                let err = match e {
                    display_backends::ActivationError::Unsupported(msg) => {
                        CoreError::UnsupportedHost(msg)
                    }
                    display_backends::ActivationError::Failed(msg) => CoreError::Spawn(msg),
                };
                self.mark_start_failed(err.to_string()).await;
                return Err(err);
            }
        };
        info!(
            serial = serial_hint,
            requested_mode = requested_mode.as_str(),
            capture_backend = capture_backend,
            x11_display = activation.display_override.as_deref().unwrap_or("-"),
            x11_region = activation
                .capture_region
                .map(|(x, y, w, h)| format!("{x},{y} {w}x{h}"))
                .unwrap_or_else(|| "-".to_string()),
            virtual_x11 = activation.using_virtual_x11,
            runtime_handle = activation.runtime_handle.is_some(),
            "display backend activation"
        );
        let x11_display_override = activation.display_override;
        let x11_capture_region = activation.capture_region;
        let using_virtual_x11 = activation.using_virtual_x11;
        if let Some(runtime_handle) = activation.runtime_handle {
            let mut inner = self.inner.lock().await;
            inner.display_runtime = Some(runtime_handle);
        }

        let session_suffix = self
            .target_serial
            .as_deref()
            .unwrap_or("default")
            .chars()
            .map(|ch| {
                if ch.is_ascii_alphanumeric() || ch == '-' || ch == '_' {
                    ch
                } else {
                    '_'
                }
            })
            .collect::<String>();
        let restore_token_file =
            format!("/tmp/wbeam-portal-restore-token-{}-{}", session_suffix, self.stream_port);
        let trainer_active_marker =
            format!("/tmp/wbeam-trainer-active-{}-{}.flag", session_suffix, self.stream_port);
        let trainer_overlay_file =
            format!("/tmp/wbeam-trainer-overlay-{}-{}.txt", session_suffix, self.stream_port);
        let trainer_run_active = Path::new(&trainer_active_marker).exists();
        let trainer_overlay_active = Path::new(&trainer_overlay_file).exists();
        let trainer_hud_burnin =
            wbeam_setting_bool(&self.settings, "WBEAM_TRAINER_HUD_BURNIN", false);

        if capture_backend == "wayland_portal" && trainer_run_active {
            if use_rust_streamer {
                warn!(
                    serial = session_suffix,
                    marker = %trainer_active_marker,
                    "trainer run marker detected; forcing python streamer to keep one portal consent"
                );
            }
            use_rust_streamer = false;
        }

        if use_rust_streamer {
            if !rust_streamer_bin.exists() {
                error!(
                    path = %rust_streamer_bin.display(),
                    "rust streamer binary not found – run `./devtool host build` to build it \
                     (set WBEAM_USE_RUST_STREAMER=false to force legacy python streamer)"
                );
                let err = CoreError::Spawn(format!(
                    "rust streamer binary not found: {} \
                     (run `./devtool host build`; or set WBEAM_USE_RUST_STREAMER=false to use python fallback)",
                    rust_streamer_bin.display()
                ));
                self.mark_start_failed(err.to_string()).await;
                return Err(err);
            }
            cmd = Command::new(rust_streamer_bin);
            cmd.arg("--profile")
                .arg(&cfg.profile)
                .arg("--capture-backend")
                .arg(match capture_backend {
                    "x11_gst" => "x11",
                    "wayland_portal" => "wayland-portal",
                    _ => "auto",
                })
                .arg("--port")
                .arg(self.stream_port.to_string())
                .arg("--encoder")
                .arg(&cfg.encoder)
                .arg("--cursor-mode")
                .arg(&cfg.cursor_mode)
                .arg("--size")
                .arg(&launch_size)
                .arg("--fps")
                .arg(cfg.fps.to_string())
                .arg("--bitrate-kbps")
                .arg(cfg.bitrate_kbps.to_string())
                .arg("--restore-token-file")
                .arg(&restore_token_file)
                .arg("--portal-persist-mode")
                .arg("2")
                .arg("--debug-dir")
                .arg("/tmp/wbeam-frames")
                .arg("--debug-fps")
                .arg(cfg.debug_fps.to_string());
            if trainer_overlay_active && trainer_hud_burnin {
                cmd.env("WBEAM_OVERLAY_TEXT_FILE", &trainer_overlay_file);
            } else {
                cmd.env_remove("WBEAM_OVERLAY_TEXT_FILE");
            }
            if cfg.intra_only {
                cmd.arg("--intra-only");
            }
            if capture_backend == "x11_gst" {
                if let Some(display) = x11_display_override.as_deref() {
                    cmd.env("DISPLAY", display);
                }
                if let Some((x, y, w, h)) = x11_capture_region {
                    cmd.env("WBEAM_X11_CAPTURE_X", x.to_string());
                    cmd.env("WBEAM_X11_CAPTURE_Y", y.to_string());
                    cmd.env("WBEAM_X11_CAPTURE_W", w.to_string());
                    cmd.env("WBEAM_X11_CAPTURE_H", h.to_string());
                } else {
                    cmd.env_remove("WBEAM_X11_CAPTURE_X");
                    cmd.env_remove("WBEAM_X11_CAPTURE_Y");
                    cmd.env_remove("WBEAM_X11_CAPTURE_W");
                    cmd.env_remove("WBEAM_X11_CAPTURE_H");
                }
                if !using_virtual_x11 {
                    if let Some(xauth) = resolve_xauthority_for_capture() {
                        cmd.env("XAUTHORITY", xauth);
                    }
                } else {
                    cmd.env_remove("XAUTHORITY");
                }
            }
        } else {
            if capture_backend == "x11_gst" {
                let err = CoreError::Spawn(
                    "x11 backend requires Rust streamer (set WBEAM_USE_RUST_STREAMER=true)"
                        .to_string(),
                );
                self.mark_start_failed(err.to_string()).await;
                return Err(err);
            }
            warn!("WBEAM_USE_RUST_STREAMER=false – using legacy python streamer");
            let stream_script = self
                .root
                .join("host/scripts/stream_wayland_portal_h264.py");
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
                .arg(&launch_size)
                .arg("--fps")
                .arg(cfg.fps.to_string())
                .arg("--bitrate-kbps")
                .arg(cfg.bitrate_kbps.to_string())
                .arg("--debug-dir")
                .arg("/tmp/wbeam-frames")
                .arg("--debug-fps")
                .arg(cfg.debug_fps.to_string())
                .arg("--restore-token-file")
                .arg(restore_token_file)
                .env("PYTHONUNBUFFERED", "1")
                .stdin(Stdio::null())
                .stdout(Stdio::piped())
                .stderr(Stdio::piped());
            if trainer_overlay_active && trainer_hud_burnin {
                cmd.env("WBEAM_OVERLAY_TEXT_FILE", &trainer_overlay_file);
            } else {
                cmd.env_remove("WBEAM_OVERLAY_TEXT_FILE");
            }
            // C3: framed-only transport (legacy parser disabled on Android path).
            cmd.arg("--framed");
        }

        cmd.stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());

        let mut child = match cmd.spawn() {
            Ok(c) => c,
            Err(e) => {
                let err = CoreError::Spawn(e.to_string());
                self.mark_start_failed(err.to_string()).await;
                return Err(err);
            }
        };

        let pid = if let Some(pid) = child.id() {
            pid
        } else {
            let err = CoreError::Spawn("child process has no pid".to_string());
            self.mark_start_failed(err.to_string()).await;
            return Err(err);
        };

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
        let mut effective_snapshot: Option<EffectiveRuntimeConfig> = None;
        let mut inner = self.inner.lock().await;
        if inner.run_id != run_id {
            return;
        }

        inner.last_output_at = Some(Instant::now());

        if line.contains("Streaming Wayland screencast")
            || line.contains("Streaming X11 screencast")
        {
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
        if let Some(transport) = proc::parse_transport_runtime_line(line) {
            inner.metrics.transport_runtime = transport;
        }

        if let Some(mut parsed) =
            parse_effective_runtime_line(line, &inner.pending_runtime_snapshot_reason)
        {
            parsed.snapshot_unix_ms = now_unix_ms();
            inner.effective_runtime_config = Some(parsed.clone());
            effective_snapshot = Some(parsed);
        }

        let trimmed = line.trim();
        if !trimmed.is_empty() {
            let lower = trimmed.to_ascii_lowercase();
            if lower.contains("panic")
                || lower.contains("error")
                || lower.contains("failed")
                || lower.contains("cannot")
                || lower.contains("property '")
            {
                inner.last_error = trimmed.to_string();
            }
        }
        drop(inner);
        if let Some(snapshot) = effective_snapshot {
            self.persist_effective_runtime_snapshot(run_id, &snapshot);
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

            let preserved_stream_error = inner.last_error.clone();
            inner.last_error = format!("stream exited with code={exit_code}");
            inner.state = STATE_ERROR.to_string();
            inner.metrics.reconnects = inner.metrics.reconnects.saturating_add(1);

            if !had_streaming_session {
                inner.state = STATE_IDLE.to_string();
                inner.last_error = if preserved_stream_error.is_empty() {
                    format!("stream start aborted (code={exit_code}); waiting for explicit /start")
                } else {
                    format!("stream start aborted (code={exit_code}): {preserved_stream_error}")
                };
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
                    if let Err(err) = core.start_with_config(cfg, "auto_reconnect").await {
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
                            inner.last_error =
                                "start timeout waiting for streaming signal".to_string();
                            inner.metrics.drops = inner.metrics.drops.saturating_add(1);
                            kill_pid = Some(pid);
                        }
                    }
                }
            }
        }

        if let Some(reason) = refresh_reverse_reason {
            adb::ensure_usb_reverse(
                &self.root,
                self.stream_port,
                self.control_port,
                reason,
                self.target_serial.as_deref(),
            )
            .await;
        }

        if let Some(pid) = kill_pid {
            warn!(pid, "watchdog terminating stalled stream process");
            proc::terminate_pid(pid).await;
        }
    }

    fn effective_runtime_log_path(&self) -> PathBuf {
        let serial = self
            .target_serial
            .as_deref()
            .unwrap_or("default")
            .chars()
            .map(|ch| {
                if ch.is_ascii_alphanumeric() || ch == '-' || ch == '_' {
                    ch
                } else {
                    '_'
                }
            })
            .collect::<String>();
        self.root
            .join("logs")
            .join("effective-runtime")
            .join(format!("{serial}-{}.jsonl", self.stream_port))
    }

    fn persist_effective_runtime_snapshot(&self, run_id: u64, snapshot: &EffectiveRuntimeConfig) {
        let path = self.effective_runtime_log_path();
        if let Some(parent) = path.parent() {
            if let Err(err) = std::fs::create_dir_all(parent) {
                warn!(error = %err, path = %path.display(), "effective runtime: mkdir failed");
                return;
            }
        }
        let Ok(mut file) = OpenOptions::new().create(true).append(true).open(&path) else {
            warn!(path = %path.display(), "effective runtime: open jsonl failed");
            return;
        };
        let line = serde_json::json!({
            "run_id": run_id,
            "stream_port": self.stream_port,
            "serial": self.target_serial,
            "snapshot": snapshot,
        });
        if let Err(err) = writeln!(file, "{line}") {
            warn!(error = %err, path = %path.display(), "effective runtime: append jsonl failed");
        }
    }

    fn base_from_inner(&self, inner: &Inner) -> BaseResponse {
        BaseResponse {
            state: inner.state.clone(),
            active_config: inner.active_config.clone(),
            effective_runtime_config: inner.effective_runtime_config.clone(),
            host_name: inner.host_name.clone(),
            uptime: inner.started_at.elapsed().as_secs(),
            run_id: inner.run_id,
            last_error: inner.last_error.clone(),
            target_serial: self.target_serial.clone(),
            stream_port: self.stream_port,
            control_port: self.control_port,
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
        let presets = wbeamd_api::presets();
        Inner::new(
            "test-host".to_string(),
            ActiveConfig::balanced_default(),
            presets,
        )
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
        let core = DaemonCore::new(PathBuf::from("/tmp/test-wbeam-b2"), 15000, 15001);
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
            decode_time_ms_p95: 8.0, // ≤ 12.0
            render_time_ms_p95: 5.0, // ≤ 7.0
            present_fps: 59.0,       // 59 >= 60 * 0.90 = 54
            ..Default::default()
        };
        assert!(!is_high_pressure(inner.active_config.fps, &client));
    }

    // ── is_low_pressure ─────────────────────────────────────────────────────

    #[test]
    fn test_low_pressure_all_healthy() {
        let inner = default_inner();
        assert!(is_low_pressure(
            inner.active_config.fps,
            &low_pressure_client()
        ));
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
        core.ingest_client_metrics(high_pressure_client())
            .await
            .expect("ingest");
        core.ingest_client_metrics(high_pressure_client())
            .await
            .expect("ingest");
        let inner = core.inner.lock().await;
        assert_eq!(inner.adaptation_level, 1, "level should degrade to 1");
    }

    #[tokio::test]
    async fn test_adaptation_single_high_no_degrade() {
        // Only one high-pressure sample (streak < HIGH_PRESSURE_STREAK_REQUIRED=2)
        let core = streaming_core_ready().await;
        core.ingest_client_metrics(high_pressure_client())
            .await
            .expect("ingest");
        let inner = core.inner.lock().await;
        assert_eq!(inner.adaptation_level, 0, "one sample should not degrade");
    }

    #[tokio::test]
    async fn test_adaptation_cooldown_blocks_rapid_degrade() {
        let core = streaming_core_ready().await;
        // First degrade: level 0 → 1
        core.ingest_client_metrics(high_pressure_client())
            .await
            .expect("ingest");
        core.ingest_client_metrics(high_pressure_client())
            .await
            .expect("ingest");
        // Immediately after degrade, cooldown should block further degrade
        // (last_adaptation_at = Some(Instant::now()), delta < 4s)
        core.ingest_client_metrics(high_pressure_client())
            .await
            .expect("ingest");
        core.ingest_client_metrics(high_pressure_client())
            .await
            .expect("ingest");
        let inner = core.inner.lock().await;
        assert_eq!(
            inner.adaptation_level, 1,
            "cooldown should block second degrade"
        );
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
        core.ingest_client_metrics(high_pressure_client())
            .await
            .expect("ingest");
        core.ingest_client_metrics(high_pressure_client())
            .await
            .expect("ingest");
        let inner = core.inner.lock().await;
        assert_eq!(
            inner.adaptation_level, 2,
            "back-dated cooldown should allow degrade 1→2"
        );
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
        core.ingest_client_metrics(high_pressure_client())
            .await
            .expect("ingest");
        core.ingest_client_metrics(high_pressure_client())
            .await
            .expect("ingest");
        let inner = core.inner.lock().await;
        assert_eq!(
            inner.adaptation_level, MAX_ADAPTATION_LEVEL,
            "must not exceed MAX (3)"
        );
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
            core.ingest_client_metrics(low_pressure_client())
                .await
                .expect("ingest");
        }
        let inner = core.inner.lock().await;
        assert_eq!(
            inner.adaptation_level, 0,
            "8 low-pressure samples should recover level 1→0"
        );
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
            core.ingest_client_metrics(low_pressure_client())
                .await
                .expect("ingest");
        }
        let inner = core.inner.lock().await;
        assert_eq!(inner.adaptation_level, 0, "cannot recover below 0");
    }

    #[tokio::test]
    async fn test_adaptation_hold_during_warmup() {
        // stream_started_at < 8s → can_adapt = false → level stays 0
        let core = DaemonCore::new(PathBuf::from("/tmp/test-wbeam-b2"), 15002, 15003);
        {
            let mut inner = core.inner.lock().await;
            inner.state = STATE_STREAMING.to_string();
            inner.current_pid = Some(99999);
            inner.stream_started_at = Some(Instant::now() - Duration::from_secs(3));
            // < 8s
        }
        core.ingest_client_metrics(high_pressure_client())
            .await
            .expect("ingest");
        core.ingest_client_metrics(high_pressure_client())
            .await
            .expect("ingest");
        let inner = core.inner.lock().await;
        assert_eq!(
            inner.adaptation_level, 0,
            "warmup guard must block early adaptation"
        );
    }

    // ── telemetry (unit tests are in infra::telemetry) ─────────────────────
}
