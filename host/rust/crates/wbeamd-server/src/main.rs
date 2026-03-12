use std::collections::{HashMap, HashSet};
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use axum::extract::{Path as AxPath, Query, State};
use axum::http::{header, StatusCode};
use axum::response::IntoResponse;
use axum::Json;
use clap::Parser;
use serde::Deserialize;
use serde::Serialize;
use serde_json::Value;
use tokio::sync::Mutex;
use tracing::{error, info, warn};
use tracing_subscriber::EnvFilter;
use wbeamd_api::{ClientHelloRequest, ClientMetricsRequest, ConfigPatch, ErrorResponse};
use wbeamd_core::{CoreError, DaemonCore};

mod server;
use server::kscreen_layout;
use server::routes::build_router;
use server::trainer_support::{
    adb_push_benchmark, adb_shell_rtt_benchmark, list_adb_devices, list_trainer_profiles,
    live_snapshot_score, now_unix_ms, persist_trainer_run_artifacts, read_json_value,
    resolve_connection_mode, resolve_trainer_overlay_payload, sanitize_profile_name,
    trainer_profile_root,
};

#[derive(Debug, Parser)]
#[command(name = "wbeamd-rust", about = "WBeam host daemon in Rust")]
struct Args {
    #[arg(long, default_value_t = 5001)]
    control_port: u16,
    #[arg(long, default_value_t = 5000)]
    stream_port: u16,
    #[arg(long, default_value = "/tmp/wbeamd.lock")]
    lock_file: String,
    #[arg(long)]
    root: Option<String>,
    #[arg(long)]
    log_dir: Option<String>,
}

#[derive(Clone)]
struct AppState {
    sessions: Arc<SessionRegistry>,
    trainer: Arc<TrainerState>,
}

#[derive(Debug, Serialize, Clone)]
struct TrainerRun {
    run_id: String,
    profile_name: String,
    serial: String,
    mode: String,
    engine: String,
    status: String,
    started_at_unix_ms: u128,
    finished_at_unix_ms: Option<u128>,
    trials: u32,
    warmup_sec: u32,
    sample_sec: u32,
    stream_port: u16,
    log_path: String,
    profile_dir: String,
    run_artifacts_dir: String,
    generations: u32,
    population: u32,
    elite_count: u32,
    mutation_rate: f64,
    crossover_rate: f64,
    bitrate_min_kbps: u32,
    bitrate_max_kbps: u32,
    encoder_mode: String,
    encoders: Vec<String>,
    encoder_tuning_mode: String,
    encoder_params: Value,
    hud_chart_mode: String,
    hud_font_preset: String,
    hud_layout: String,
    exit_code: Option<i32>,
    pid: Option<u32>,
    error: Option<String>,
}

#[derive(Debug, Serialize)]
struct TrainerRunsResponse {
    ok: bool,
    runs: Vec<TrainerRun>,
}

#[derive(Debug, Deserialize)]
struct TrainerStartRequest {
    serial: String,
    profile_name: String,
    mode: Option<String>,
    trials: Option<u32>,
    warmup_sec: Option<u32>,
    sample_sec: Option<u32>,
    overlay: Option<bool>,
    stream_port: Option<u16>,
    generations: Option<u32>,
    population: Option<u32>,
    elite_count: Option<u32>,
    mutation_rate: Option<f64>,
    crossover_rate: Option<f64>,
    bitrate_min_kbps: Option<u32>,
    bitrate_max_kbps: Option<u32>,
    encoder_mode: Option<String>,
    encoders: Option<Vec<String>>,
    encoder_tuning_mode: Option<String>,
    encoder_params: Option<Value>,
    hud_chart_mode: Option<String>,
    hud_font_preset: Option<String>,
    hud_layout: Option<String>,
}

#[derive(Debug, Deserialize)]
struct TrainerStopRequest {
    run_id: String,
}

#[derive(Debug, Serialize)]
struct TrainerStartResponse {
    ok: bool,
    run_id: String,
    status: String,
    log_path: String,
    warnings: Vec<String>,
}

#[derive(Debug, Deserialize)]
struct TrainerPreflightRequest {
    serial: String,
    stream_port: Option<u16>,
    adb_push_mb: Option<u32>,
    shell_rtt_loops: Option<u32>,
}

#[derive(Debug, Serialize)]
struct TrainerPreflightResponse {
    ok: bool,
    serial: String,
    stream_port: u16,
    daemon_health: Value,
    adb_push: Value,
    adb_shell_rtt: Value,
}

#[derive(Debug, Serialize)]
struct TrainerProfileSummary {
    profile_name: String,
    path: String,
    has_profile: bool,
    has_parameters: bool,
    has_preflight: bool,
    best_score: Option<f64>,
    engine: Option<String>,
    serial: Option<String>,
    updated_at_unix_ms: Option<u128>,
}

#[derive(Debug, Serialize)]
struct TrainerProfilesResponse {
    ok: bool,
    profiles: Vec<TrainerProfileSummary>,
}

#[derive(Debug, Serialize)]
struct TrainerProfileDetailResponse {
    ok: bool,
    profile_name: String,
    profile: Value,
    parameters: Value,
    preflight: Value,
}

#[derive(Debug, Serialize)]
struct TrainerRunTailResponse {
    ok: bool,
    run_id: String,
    line_count: usize,
    lines: Vec<String>,
}

#[derive(Debug, Serialize)]
struct TrainerDeviceInfo {
    serial: String,
    state: String,
    model: Option<String>,
    api_level: Option<u32>,
    android_release: Option<String>,
    stream_port: Option<u16>,
}

#[derive(Debug, Serialize)]
struct TrainerDevicesResponse {
    ok: bool,
    devices: Vec<TrainerDeviceInfo>,
}

#[derive(Debug, Deserialize, Default)]
struct TrainerRunTailQuery {
    lines: Option<usize>,
}

#[derive(Debug, Serialize)]
struct TrainerDiagnosticsResponse {
    ok: bool,
    daemon_health: Value,
    adb_version: String,
    adb_devices_raw: String,
    profile_root: String,
    runs_count: usize,
}

#[derive(Debug, Serialize)]
struct TrainerDatasetSummary {
    run_id: String,
    profile_name: String,
    status: String,
    run_artifacts_dir: String,
    started_at_unix_ms: Option<u128>,
    finished_at_unix_ms: Option<u128>,
    has_run_json: bool,
    has_parameters: bool,
    has_profile: bool,
    has_preflight: bool,
    has_logs: bool,
    best_trial: Option<String>,
    best_score: Option<f64>,
    last_recompute_at_unix_ms: Option<u128>,
}

#[derive(Debug, Serialize)]
struct TrainerDatasetsResponse {
    ok: bool,
    datasets: Vec<TrainerDatasetSummary>,
}

#[derive(Debug, Serialize)]
struct TrainerDatasetDetailResponse {
    ok: bool,
    dataset: TrainerDatasetSummary,
    run: Value,
    parameters: Value,
    profile: Value,
    preflight: Value,
    recompute: Value,
}

#[derive(Debug, Serialize)]
struct TrainerDatasetRecomputeResponse {
    ok: bool,
    run_id: String,
    best_trial: String,
    best_score: f64,
    alternatives: Vec<Value>,
    output_path: String,
}

#[derive(Debug, Deserialize, Default)]
struct TrainerLiveStartRequest {
    serial: String,
    stream_port: Option<u16>,
    #[serde(flatten)]
    patch: ConfigPatch,
}

#[derive(Debug, Deserialize, Default)]
struct TrainerLiveApplyRequest {
    serial: String,
    stream_port: Option<u16>,
    #[serde(flatten)]
    patch: ConfigPatch,
}

#[derive(Debug, Deserialize)]
struct TrainerLiveSaveProfileRequest {
    serial: String,
    stream_port: Option<u16>,
    profile_name: String,
    description: Option<String>,
    tags: Option<Vec<String>>,
}

struct TrainerState {
    root: PathBuf,
    control_port: u16,
    runs: Mutex<HashMap<String, TrainerRun>>,
    run_counter: AtomicU64,
}

impl TrainerState {
    fn new(root: PathBuf, control_port: u16) -> Self {
        Self {
            root,
            control_port,
            runs: Mutex::new(HashMap::new()),
            run_counter: AtomicU64::new(0),
        }
    }

    fn next_run_id(&self) -> String {
        let ctr = self.run_counter.fetch_add(1, Ordering::Relaxed) + 1;
        let ts = now_unix_ms();
        format!("run-{ts}-{ctr:04}")
    }
}

#[derive(Debug, Deserialize, Default, Clone)]
struct SessionQuery {
    serial: Option<String>,
    stream_port: Option<u16>,
    display_mode: Option<String>,
}

struct SessionCore {
    core: Arc<DaemonCore>,
}

struct SessionRegistry {
    root: PathBuf,
    control_port: u16,
    base_stream_port: u16,
    default_core: Arc<DaemonCore>,
    portal_start_gate: Mutex<()>,
    portal_output_by_serial: Mutex<HashMap<String, String>>,
    serial_cores: Mutex<HashMap<String, SessionCore>>,
    port_cores: Mutex<HashMap<u16, SessionCore>>,
}

impl SessionRegistry {
    fn new(
        root: PathBuf,
        base_stream_port: u16,
        control_port: u16,
        default_core: Arc<DaemonCore>,
    ) -> Self {
        Self {
            root,
            control_port,
            base_stream_port,
            default_core,
            portal_start_gate: Mutex::new(()),
            portal_output_by_serial: Mutex::new(HashMap::new()),
            serial_cores: Mutex::new(HashMap::new()),
            port_cores: Mutex::new(HashMap::new()),
        }
    }

    async fn resolve_core(
        &self,
        serial: Option<&str>,
        requested_stream_port: Option<u16>,
    ) -> Arc<DaemonCore> {
        if let Some(raw) = serial {
            let normalized = raw.trim();
            if !normalized.is_empty() {
                {
                    let guard = self.serial_cores.lock().await;
                    if let Some(existing) = guard.get(normalized) {
                        return existing.core.clone();
                    }
                }

                let mut guard = self.serial_cores.lock().await;
                if let Some(existing) = guard.get(normalized) {
                    return existing.core.clone();
                }

                let stream_port = requested_stream_port.filter(|p| *p > 0).unwrap_or_else(|| {
                    self.base_stream_port.saturating_add(2 + guard.len() as u16)
                });
                let stream_port = if stream_port == self.control_port {
                    stream_port.saturating_add(1)
                } else {
                    stream_port
                };
                let session_label = Some(format!("serial-{normalized}"));
                let target_serial = Some(normalized.to_string());
                let core = Arc::new(DaemonCore::new_for_session(
                    self.root.clone(),
                    stream_port,
                    self.control_port,
                    session_label,
                    target_serial,
                ));
                guard.insert(normalized.to_string(), SessionCore { core: core.clone() });
                let mut port_guard = self.port_cores.lock().await;
                port_guard.insert(stream_port, SessionCore { core: core.clone() });
                info!(
                    serial = normalized,
                    stream_port, "created daemon session core"
                );
                return core;
            }
        }

        // Serial is unknown/empty on some Android builds; use stream_port as fallback key.
        {
            let guard = self.serial_cores.lock().await;
            if guard.len() == 1 {
                if let Some((_serial, session)) = guard.iter().next() {
                    return session.core.clone();
                }
            }
        }

        let Some(stream_port) = requested_stream_port.filter(|p| *p > 0) else {
            return self.default_core.clone();
        };
        if stream_port == self.base_stream_port {
            return self.default_core.clone();
        }
        {
            let guard = self.port_cores.lock().await;
            if let Some(existing) = guard.get(&stream_port) {
                return existing.core.clone();
            }
        }
        let mut guard = self.port_cores.lock().await;
        if let Some(existing) = guard.get(&stream_port) {
            return existing.core.clone();
        }
        let safe_stream_port = if stream_port == self.control_port {
            stream_port.saturating_add(1)
        } else {
            stream_port
        };
        let session_label = Some(format!("port-{safe_stream_port}"));
        let core = Arc::new(DaemonCore::new_for_session(
            self.root.clone(),
            safe_stream_port,
            self.control_port,
            session_label,
            None,
        ));
        guard.insert(safe_stream_port, SessionCore { core: core.clone() });
        info!(
            stream_port = safe_stream_port,
            "created daemon session core (port fallback)"
        );
        core
    }

    async fn resolve_core_readonly(
        &self,
        serial: Option<&str>,
        requested_stream_port: Option<u16>,
    ) -> Option<Arc<DaemonCore>> {
        if let Some(raw) = serial {
            let normalized = raw.trim();
            if !normalized.is_empty() {
                {
                    let guard = self.serial_cores.lock().await;
                    if let Some(existing) = guard.get(normalized) {
                        return Some(existing.core.clone());
                    }
                }
                if let Some(stream_port) = requested_stream_port.filter(|p| *p > 0) {
                    if stream_port == self.base_stream_port {
                        return Some(self.default_core.clone());
                    }
                    let guard = self.port_cores.lock().await;
                    if let Some(existing) = guard.get(&stream_port) {
                        return Some(existing.core.clone());
                    }
                }
                return None;
            }
        }

        if let Some(stream_port) = requested_stream_port.filter(|p| *p > 0) {
            if stream_port == self.base_stream_port {
                return Some(self.default_core.clone());
            }
            let guard = self.port_cores.lock().await;
            if let Some(existing) = guard.get(&stream_port) {
                return Some(existing.core.clone());
            }
            return None;
        }

        Some(self.default_core.clone())
    }

    fn default_core(&self) -> Arc<DaemonCore> {
        self.default_core.clone()
    }

    async fn forget_core(&self, core: &Arc<DaemonCore>) {
        if Arc::ptr_eq(core, &self.default_core) {
            return;
        }
        let mut removed_serials = Vec::new();
        {
            let mut guard = self.serial_cores.lock().await;
            for (serial, entry) in guard.iter() {
                if Arc::ptr_eq(&entry.core, core) {
                    removed_serials.push(serial.clone());
                }
            }
            guard.retain(|_, entry| !Arc::ptr_eq(&entry.core, core));
        }
        {
            let mut guard = self.port_cores.lock().await;
            guard.retain(|_, entry| !Arc::ptr_eq(&entry.core, core));
        }
        if !removed_serials.is_empty() {
            let mut guard = self.portal_output_by_serial.lock().await;
            for serial in removed_serials {
                guard.remove(&serial);
            }
        }
    }

    async fn stop_all(&self) {
        let mut cores = vec![self.default_core.clone()];
        {
            let guard = self.serial_cores.lock().await;
            for entry in guard.values() {
                cores.push(entry.core.clone());
            }
        }
        {
            let guard = self.port_cores.lock().await;
            for entry in guard.values() {
                cores.push(entry.core.clone());
            }
        }
        let mut seen = HashSet::new();
        for core in cores {
            let key = Arc::as_ptr(&core) as usize;
            if seen.insert(key) {
                let _ = core.stop().await;
            }
        }
    }

    async fn map_wayland_output_for_serial(&self, serial: &str, output_name: &str) {
        let trimmed_serial = serial.trim();
        let trimmed_output = output_name.trim();
        if trimmed_serial.is_empty() || trimmed_output.is_empty() {
            return;
        }
        let mut guard = self.portal_output_by_serial.lock().await;
        guard.insert(trimmed_serial.to_string(), trimmed_output.to_string());
    }

    async fn mapped_wayland_output_names(&self) -> HashSet<String> {
        let guard = self.portal_output_by_serial.lock().await;
        guard.values().cloned().collect()
    }
}

#[tokio::main]
async fn main() {
    let args = Args::parse();

    let root = args
        .root
        .map(PathBuf::from)
        .unwrap_or_else(|| workspace_root_from_manifest(env!("CARGO_MANIFEST_DIR")));
    let log_dir = args
        .log_dir
        .map(PathBuf::from)
        .unwrap_or_else(|| root.join("host/rust/logs"));

    if let Err(err) = std::fs::create_dir_all(&log_dir) {
        eprintln!(
            "failed to create log directory {}: {err}",
            log_dir.display()
        );
        std::process::exit(1);
    }

    let file_appender = tracing_appender::rolling::daily(&log_dir, "wbeamd-rust.log");
    let (non_blocking, _guard) = tracing_appender::non_blocking(file_appender);

    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env().add_directive("info".parse().unwrap()))
        .with_writer(non_blocking)
        .with_ansi(false)
        .init();

    let lock_path = PathBuf::from(&args.lock_file);
    let _lock_guard = match DaemonCore::acquire_lock(&lock_path) {
        Ok(lock) => lock,
        Err(err) => {
            eprintln!("{err}");
            std::process::exit(1);
        }
    };

    let core = Arc::new(DaemonCore::new(
        root.clone(),
        args.stream_port,
        args.control_port,
    ));
    let sessions = Arc::new(SessionRegistry::new(
        root.clone(),
        args.stream_port,
        args.control_port,
        core.clone(),
    ));
    let trainer = Arc::new(TrainerState::new(root.clone(), args.control_port));
    let app_state = AppState { sessions, trainer };

    let app = build_router(app_state.clone());

    let addr = SocketAddr::from(([0, 0, 0, 0], args.control_port));
    let listener = match tokio::net::TcpListener::bind(addr).await {
        Ok(listener) => listener,
        Err(err) => {
            error!(error = %err, "failed to bind control server");
            std::process::exit(1);
        }
    };

    info!(
        control_port = args.control_port,
        stream_port = args.stream_port,
        root = %root.display(),
        "wbeamd-rust started"
    );

    let shutdown_sessions = app_state.sessions.clone();
    let server =
        axum::serve(listener, app.into_make_service()).with_graceful_shutdown(async move {
            shutdown_signal().await;
            shutdown_sessions.stop_all().await;
        });

    if let Err(err) = server.await {
        error!(error = %err, "server terminated with error");
    }
}

async fn get_status(
    State(state): State<AppState>,
    Query(query): Query<SessionQuery>,
) -> impl IntoResponse {
    let serial = query.serial.as_deref();
    let core = state
        .sessions
        .resolve_core_readonly(serial, query.stream_port)
        .await
        .unwrap_or_else(|| state.sessions.default_core());
    Json(core.status().await)
}

async fn get_host_probe(
    State(state): State<AppState>,
    Query(query): Query<SessionQuery>,
) -> impl IntoResponse {
    let serial = query.serial.as_deref();
    let core = state
        .sessions
        .resolve_core_readonly(serial, query.stream_port)
        .await
        .unwrap_or_else(|| state.sessions.default_core());
    Json(core.host_probe().await)
}

async fn get_health(
    State(state): State<AppState>,
    Query(query): Query<SessionQuery>,
) -> impl IntoResponse {
    let serial = query.serial.as_deref();
    let core = state
        .sessions
        .resolve_core_readonly(serial, query.stream_port)
        .await
        .unwrap_or_else(|| state.sessions.default_core());
    Json(core.health().await)
}

async fn get_presets(
    State(state): State<AppState>,
    Query(query): Query<SessionQuery>,
) -> impl IntoResponse {
    let serial = query.serial.as_deref();
    let core = state
        .sessions
        .resolve_core_readonly(serial, query.stream_port)
        .await
        .unwrap_or_else(|| state.sessions.default_core());
    Json(core.presets().await)
}

async fn get_metrics(
    State(state): State<AppState>,
    Query(query): Query<SessionQuery>,
) -> impl IntoResponse {
    let serial = query.serial.as_deref();
    let stream_port = query.stream_port.unwrap_or(state.sessions.base_stream_port);
    let connection_mode = resolve_connection_mode(
        state.trainer.as_ref(),
        serial.map(str::trim).filter(|s| !s.is_empty()),
        stream_port,
    )
    .await;
    let core = state
        .sessions
        .resolve_core_readonly(serial, query.stream_port)
        .await
        .unwrap_or_else(|| state.sessions.default_core());
    let mut payload = serde_json::to_value(core.metrics().await)
        .unwrap_or_else(|_| serde_json::json!({}));
    if let Value::Object(ref mut obj) = payload {
        let serial_opt = query.serial.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let (hud_active, hud_text, hud_json) = resolve_trainer_overlay_payload(serial_opt, stream_port);
        obj.insert(
            "connection_mode".to_string(),
            Value::String(connection_mode.to_string()),
        );
        obj.insert("trainer_hud_active".to_string(), Value::Bool(hud_active));
        obj.insert(
            "trainer_hud_text".to_string(),
            Value::String(hud_text.unwrap_or_default()),
        );
        obj.insert("trainer_hud_json".to_string(), hud_json.unwrap_or(Value::Null));
    }
    Json(payload)
}

#[derive(Debug, Deserialize)]
struct SpeedtestQuery {
    mb: Option<u32>,
}

async fn get_speedtest(Query(query): Query<SpeedtestQuery>) -> impl IntoResponse {
    let mb = query.mb.unwrap_or(64).clamp(1, 256);
    let total = (mb as usize) * 1024 * 1024;
    let mut payload = vec![0u8; total];
    fill_pseudorandom(&mut payload);
    (
        [
            (header::CONTENT_TYPE, "application/octet-stream"),
            (header::CACHE_CONTROL, "no-store"),
            (header::PRAGMA, "no-cache"),
        ],
        payload,
    )
}

async fn get_virtual_probe(
    State(state): State<AppState>,
    Query(query): Query<SessionQuery>,
) -> impl IntoResponse {
    let serial = query.serial.as_deref();
    let core = state
        .sessions
        .resolve_core_readonly(serial, query.stream_port)
        .await
        .unwrap_or_else(|| state.sessions.default_core());
    Json(core.virtual_probe().await)
}

async fn get_virtual_doctor(
    State(state): State<AppState>,
    Query(query): Query<SessionQuery>,
) -> impl IntoResponse {
    let serial = query.serial.as_deref();
    let core = state
        .sessions
        .resolve_core_readonly(serial, query.stream_port)
        .await
        .unwrap_or_else(|| state.sessions.default_core());
    Json(core.virtual_doctor().await)
}

async fn post_start(
    State(state): State<AppState>,
    Query(query): Query<SessionQuery>,
    body: Option<Json<ConfigPatch>>,
) -> impl IntoResponse {
    let serial = query.serial.as_deref();
    let core = state.sessions.resolve_core(serial, query.stream_port).await;
    // display_mode is a sticky session preference. Do not reset it on every /start when the client
    // doesn't provide the query param (Android client uses POST /start without it).
    if query.display_mode.is_some() {
        tracing::info!(
            serial = serial.unwrap_or("default"),
            stream_port = query.stream_port.unwrap_or(0),
            display_mode = query.display_mode.as_deref().unwrap_or(""),
            "start: display_mode override"
        );
        core.set_display_mode(query.display_mode.as_deref()).await;
    }
    let patch = body.map(|Json(v)| v).unwrap_or_default();
    let host_probe = core.host_probe().await;
    let is_wayland_portal = host_probe.capture_mode == "wayland_portal";
    let pre_enabled_outputs = if is_wayland_portal {
        kscreen_layout::kscreen_enabled_output_names().ok()
    } else {
        None
    };
    let start_result = if is_wayland_portal {
        let _guard = state.sessions.portal_start_gate.lock().await;
        core.start(patch).await
    } else {
        core.start(patch).await
    };
    match start_result {
        Ok(resp) => {
            if is_wayland_portal {
                if let Err(err) = auto_layout_wayland_portal_outputs(
                    state.sessions.clone(),
                    serial,
                    pre_enabled_outputs.as_ref(),
                )
                .await
                {
                    warn!(error = %err, "wayland portal output auto-layout failed");
                }
            }
            (StatusCode::OK, Json(resp)).into_response()
        }
        Err(err) => core_error_response(core, err).await,
    }
}

async fn post_stop(
    State(state): State<AppState>,
    Query(query): Query<SessionQuery>,
) -> impl IntoResponse {
    let serial = query.serial.as_deref();
    let maybe_core = state
        .sessions
        .resolve_core_readonly(serial, query.stream_port)
        .await;
    if maybe_core.is_none() && serial.map(|s| !s.trim().is_empty()).unwrap_or(false) {
        let resp = state.sessions.default_core().status().await;
        return (StatusCode::OK, Json(resp)).into_response();
    }
    let core = maybe_core.unwrap_or_else(|| state.sessions.default_core());
    match core.stop().await {
        Ok(resp) => {
            state.sessions.forget_core(&core).await;
            (StatusCode::OK, Json(resp)).into_response()
        }
        Err(err) => core_error_response(core, err).await,
    }
}

async fn post_apply(
    State(state): State<AppState>,
    Query(query): Query<SessionQuery>,
    body: Option<Json<ConfigPatch>>,
) -> impl IntoResponse {
    let serial = query.serial.as_deref();
    let core = state.sessions.resolve_core(serial, query.stream_port).await;
    let patch = body.map(|Json(v)| v).unwrap_or_default();
    match core.apply(patch).await {
        Ok(resp) => (StatusCode::OK, Json(resp)).into_response(),
        Err(err) => core_error_response(core, err).await,
    }
}

async fn post_client_metrics(
    State(state): State<AppState>,
    Query(query): Query<SessionQuery>,
    body: Option<Json<ClientMetricsRequest>>,
) -> impl IntoResponse {
    let serial = query.serial.as_deref();
    let core = state.sessions.resolve_core(serial, query.stream_port).await;
    let payload = body.map(|Json(v)| v).unwrap_or_default();
    match core.ingest_client_metrics(payload).await {
        Ok(resp) => (StatusCode::OK, Json(resp)).into_response(),
        Err(err) => core_error_response(core, err).await,
    }
}

async fn post_client_hello(
    State(_state): State<AppState>,
    body: Option<Json<ClientHelloRequest>>,
) -> impl IntoResponse {
    let payload = body.map(|Json(v)| v).unwrap_or_default();
    let resp = wbeamd_core::resolver::resolve_profile_for_client(&payload);
    (StatusCode::OK, Json(resp)).into_response()
}

async fn post_trainer_preflight(
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

async fn post_trainer_start(
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
    let mutation_rate = req
        .mutation_rate
        .unwrap_or(0.34_f64)
        .clamp(0.0, 1.0);
    let crossover_rate = req
        .crossover_rate
        .unwrap_or(0.50_f64)
        .clamp(0.0, 1.0);
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

async fn post_trainer_stop(
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

async fn get_trainer_live_status(
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

async fn post_trainer_live_start(
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

async fn post_trainer_live_apply(
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

async fn post_trainer_live_save_profile(
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

async fn get_trainer_runs(State(state): State<AppState>) -> impl IntoResponse {
    let runs_map = state.trainer.runs.lock().await;
    let mut runs: Vec<TrainerRun> = runs_map.values().cloned().collect();
    runs.sort_by(|a, b| b.started_at_unix_ms.cmp(&a.started_at_unix_ms));
    (StatusCode::OK, Json(TrainerRunsResponse { ok: true, runs })).into_response()
}

async fn get_trainer_run(
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

async fn get_trainer_run_tail(
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

async fn get_trainer_devices(State(state): State<AppState>) -> impl IntoResponse {
    let devices = list_adb_devices(state.sessions.base_stream_port);
    (
        StatusCode::OK,
        Json(TrainerDevicesResponse { ok: true, devices }),
    )
        .into_response()
}

async fn get_trainer_diagnostics(State(state): State<AppState>) -> impl IntoResponse {
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

async fn get_trainer_profiles(State(state): State<AppState>) -> impl IntoResponse {
    let root = trainer_profile_root(&state.trainer.root);
    let mut profiles = list_trainer_profiles(&root);
    profiles.sort_by(|a, b| b.updated_at_unix_ms.cmp(&a.updated_at_unix_ms));
    (
        StatusCode::OK,
        Json(TrainerProfilesResponse { ok: true, profiles }),
    )
        .into_response()
}

async fn get_trainer_profile(
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

async fn get_trainer_datasets(State(state): State<AppState>) -> impl IntoResponse {
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

async fn get_trainer_dataset(
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

async fn post_trainer_dataset_find_optimal(
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

async fn core_error_response(core: Arc<DaemonCore>, err: CoreError) -> axum::response::Response {
    let status = match err {
        CoreError::Validation(_) | CoreError::UnsupportedHost(_) => StatusCode::BAD_REQUEST,
        _ => StatusCode::INTERNAL_SERVER_ERROR,
    };

    let resp: ErrorResponse = core.error_response(err.to_string()).await;
    (status, Json(resp)).into_response()
}

async fn auto_layout_wayland_portal_outputs(
    sessions: Arc<SessionRegistry>,
    serial: Option<&str>,
    pre_enabled_outputs: Option<&HashSet<String>>,
) -> Result<(), String> {
    if !wayland_portal_auto_layout_enabled() {
        return Ok(());
    }
    if !kscreen_layout::command_exists("kscreen-doctor") {
        return Ok(());
    }

    let outputs = kscreen_layout::kscreen_query_outputs()?;
    let enabled_now: HashSet<String> = outputs
        .iter()
        .filter(|o| kscreen_layout::output_ready_for_layout(o))
        .map(|o| o.name.clone())
        .collect();

    if let Some(raw_serial) = serial {
        let serial_trimmed = raw_serial.trim();
        if !serial_trimmed.is_empty() {
            if let Some(before) = pre_enabled_outputs {
                let mut new_names: Vec<String> = enabled_now.difference(before).cloned().collect();
                new_names.sort();
                if let Some(chosen) = new_names
                    .iter()
                    .find(|name| kscreen_layout::output_name_looks_virtual(name))
                    .or_else(|| new_names.first())
                {
                    sessions
                        .map_wayland_output_for_serial(serial_trimmed, chosen)
                        .await;
                    info!(
                        serial = serial_trimmed,
                        output = chosen,
                        "wayland portal output mapped for serial"
                    );
                }
            }
        }
    }

    let mapped = sessions.mapped_wayland_output_names().await;
    let mut commands = kscreen_layout::build_non_overlapping_layout_commands(&outputs, Some(&mapped));
    if commands.is_empty() {
        commands = kscreen_layout::build_non_overlapping_layout_commands(&outputs, None);
    }
    if commands.is_empty() {
        return Ok(());
    }

    kscreen_layout::apply_kscreen_layout(&commands)?;
    info!(commands = ?commands, "applied wayland portal non-overlap layout");
    Ok(())
}

fn wayland_portal_auto_layout_enabled() -> bool {
    let raw = std::env::var("WBEAM_WAYLAND_PORTAL_AUTO_LAYOUT").unwrap_or_else(|_| "1".to_string());
    !matches!(
        raw.trim().to_ascii_lowercase().as_str(),
        "0" | "false" | "no" | "off"
    )
}

async fn shutdown_signal() {
    #[cfg(unix)]
    {
        use tokio::signal::unix::{signal, SignalKind};

        let mut sigterm = signal(SignalKind::terminate()).expect("sigterm handler");
        tokio::select! {
            _ = tokio::signal::ctrl_c() => {},
            _ = sigterm.recv() => {},
        }
    }

    #[cfg(not(unix))]
    {
        let _ = tokio::signal::ctrl_c().await;
    }
}

fn workspace_root_from_manifest(manifest_dir: &str) -> PathBuf {
    let path = Path::new(manifest_dir);
    // host/rust/crates/wbeamd-server -> repo root is 4 levels up.
    path.join("../../../../")
        .canonicalize()
        .unwrap_or_else(|_| PathBuf::from("."))
}

fn fill_pseudorandom(buf: &mut [u8]) {
    let mut seed = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos() as u64)
        .unwrap_or(0x9E37_79B9_7F4A_7C15);
    if seed == 0 {
        seed = 0xA5A5_A5A5_A5A5_A5A5;
    }
    let mut i = 0usize;
    while i + 8 <= buf.len() {
        // xorshift64*
        seed ^= seed >> 12;
        seed ^= seed << 25;
        seed ^= seed >> 27;
        let v = seed.wrapping_mul(0x2545F4914F6CDD1D);
        buf[i..i + 8].copy_from_slice(&v.to_le_bytes());
        i += 8;
    }
    if i < buf.len() {
        seed ^= seed >> 12;
        seed ^= seed << 25;
        seed ^= seed >> 27;
        let v = seed.wrapping_mul(0x2545F4914F6CDD1D).to_le_bytes();
        let rem = buf.len() - i;
        buf[i..].copy_from_slice(&v[..rem]);
    }
}
