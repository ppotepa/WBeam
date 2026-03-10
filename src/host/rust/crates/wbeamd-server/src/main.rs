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
use axum::routing::{get, post};
use axum::{Json, Router};
use clap::Parser;
use serde::Deserialize;
use serde::Serialize;
use serde_json::Value;
use tokio::sync::Mutex;
use tracing::{error, info, warn};
use tracing_subscriber::EnvFilter;
use wbeamd_api::{ClientHelloRequest, ClientMetricsRequest, ConfigPatch, ErrorResponse};
use wbeamd_core::{CoreError, DaemonCore};

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
    engine: Option<String>,
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

#[derive(Debug, Clone)]
struct KscreenOutput {
    name: String,
    enabled: bool,
    connected: bool,
    replication_source: i64,
    x: i32,
    y: i32,
    width: i32,
    height: i32,
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
        .unwrap_or_else(|| root.join("src/host/rust/logs"));

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

    let app = Router::new()
        .route("/status", get(get_status))
        .route("/host-probe", get(get_host_probe))
        .route("/health", get(get_health))
        .route("/presets", get(get_presets))
        .route("/metrics", get(get_metrics))
        .route("/speedtest", get(get_speedtest))
        .route("/virtual/probe", get(get_virtual_probe))
        .route("/virtual/doctor", get(get_virtual_doctor))
        .route("/start", post(post_start))
        .route("/stop", post(post_stop))
        .route("/apply", post(post_apply))
        .route("/client-metrics", post(post_client_metrics))
        .route("/client-hello", post(post_client_hello))
        .route("/trainer/preflight", post(post_trainer_preflight))
        .route("/trainer/start", post(post_trainer_start))
        .route("/trainer/stop", post(post_trainer_stop))
        .route("/trainer/runs", get(get_trainer_runs))
        .route("/trainer/runs/{run_id}", get(get_trainer_run))
        .route("/trainer/runs/{run_id}/tail", get(get_trainer_run_tail))
        .route("/trainer/profiles", get(get_trainer_profiles))
        .route("/trainer/profiles/{profile_name}", get(get_trainer_profile))
        .route("/trainer/devices", get(get_trainer_devices))
        .route("/trainer/diagnostics", get(get_trainer_diagnostics))
        .route("/v1/status", get(get_status))
        .route("/v1/host-probe", get(get_host_probe))
        .route("/v1/health", get(get_health))
        .route("/v1/presets", get(get_presets))
        .route("/v1/metrics", get(get_metrics))
        .route("/v1/speedtest", get(get_speedtest))
        .route("/v1/virtual/probe", get(get_virtual_probe))
        .route("/v1/virtual/doctor", get(get_virtual_doctor))
        .route("/v1/start", post(post_start))
        .route("/v1/stop", post(post_stop))
        .route("/v1/apply", post(post_apply))
        .route("/v1/client-metrics", post(post_client_metrics))
        .route("/v1/client-hello", post(post_client_hello))
        .route("/v1/trainer/preflight", post(post_trainer_preflight))
        .route("/v1/trainer/start", post(post_trainer_start))
        .route("/v1/trainer/stop", post(post_trainer_stop))
        .route("/v1/trainer/runs", get(get_trainer_runs))
        .route("/v1/trainer/runs/{run_id}", get(get_trainer_run))
        .route("/v1/trainer/runs/{run_id}/tail", get(get_trainer_run_tail))
        .route("/v1/trainer/profiles", get(get_trainer_profiles))
        .route(
            "/v1/trainer/profiles/{profile_name}",
            get(get_trainer_profile),
        )
        .route("/v1/trainer/devices", get(get_trainer_devices))
        .route("/v1/trainer/diagnostics", get(get_trainer_diagnostics))
        .with_state(app_state.clone());

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
    let core = state
        .sessions
        .resolve_core_readonly(serial, query.stream_port)
        .await
        .unwrap_or_else(|| state.sessions.default_core());
    Json(core.metrics().await)
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
        kscreen_enabled_output_names().ok()
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
        engine: None,
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
    let engine = req.engine.unwrap_or_else(|| "proto".to_string());
    if engine != "proto" {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"ok": false, "error": "only proto engine is supported by Trainer API start endpoint"})),
        )
            .into_response();
    }
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
    let bitrate_min_kbps = req.bitrate_min_kbps.unwrap_or(10_000).clamp(1_000, 400_000);
    let bitrate_max_kbps = req
        .bitrate_max_kbps
        .unwrap_or(200_000)
        .clamp(bitrate_min_kbps, 400_000);
    let encoder_mode = req
        .encoder_mode
        .as_deref()
        .unwrap_or("multi")
        .trim()
        .to_ascii_lowercase();
    if !matches!(encoder_mode.as_str(), "single" | "multi") {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"ok": false, "error": "invalid encoder_mode (use single|multi)"})),
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
    encoders.sort();
    encoders.dedup();
    if encoder_mode == "single" && encoders.len() != 1 {
        return (
            StatusCode::BAD_REQUEST,
            Json(serde_json::json!({"ok": false, "error": "single encoder_mode requires exactly one encoder"})),
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
        .arg("--engine")
        .arg(&engine)
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
        .arg("--stream-port")
        .arg(stream_port.to_string())
        .arg("--control-port")
        .arg(state.trainer.control_port.to_string())
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
    if !command_exists("kscreen-doctor") {
        return Ok(());
    }

    let outputs = kscreen_query_outputs()?;
    let enabled_now: HashSet<String> = outputs
        .iter()
        .filter(|o| output_ready_for_layout(o))
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
                    .find(|name| output_name_looks_virtual(name))
                    .or_else(|| new_names.first())
                {
                    sessions
                        .map_wayland_output_for_serial(serial_trimmed, chosen)
                        .await;
                    info!(
                        serial = serial_trimmed,
                        output = chosen.as_str(),
                        "wayland portal output mapped for serial"
                    );
                }
            }
        }
    }

    let mapped = sessions.mapped_wayland_output_names().await;
    let mut commands = build_non_overlapping_layout_commands(&outputs, Some(&mapped));
    if commands.is_empty() {
        commands = build_non_overlapping_layout_commands(&outputs, None);
    }
    if commands.is_empty() {
        return Ok(());
    }

    apply_kscreen_layout(&commands)?;
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

fn command_exists(name: &str) -> bool {
    Command::new("sh")
        .args(["-c", &format!("command -v {name} >/dev/null 2>&1")])
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

fn kscreen_enabled_output_names() -> Result<HashSet<String>, String> {
    let outputs = kscreen_query_outputs()?;
    Ok(outputs
        .into_iter()
        .filter(output_ready_for_layout)
        .map(|o| o.name)
        .collect())
}

fn kscreen_query_outputs() -> Result<Vec<KscreenOutput>, String> {
    let output = Command::new("kscreen-doctor")
        .arg("-j")
        .output()
        .map_err(|e| format!("failed to execute kscreen-doctor -j: {e}"))?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
        return Err(if stderr.is_empty() {
            format!("kscreen-doctor -j failed with status {}", output.status)
        } else {
            format!("kscreen-doctor -j failed: {stderr}")
        });
    }
    let root: Value = serde_json::from_slice(&output.stdout)
        .map_err(|e| format!("failed to parse kscreen-doctor json: {e}"))?;
    let Some(outputs) = root.get("outputs").and_then(|v| v.as_array()) else {
        return Ok(Vec::new());
    };

    let mut parsed = Vec::new();
    for item in outputs {
        let Some(name) = item.get("name").and_then(|v| v.as_str()) else {
            continue;
        };
        let x = item
            .get("pos")
            .and_then(|v| v.get("x"))
            .and_then(|v| v.as_i64())
            .and_then(|v| i32::try_from(v).ok())
            .unwrap_or(0);
        let y = item
            .get("pos")
            .and_then(|v| v.get("y"))
            .and_then(|v| v.as_i64())
            .and_then(|v| i32::try_from(v).ok())
            .unwrap_or(0);
        let width = item
            .get("size")
            .and_then(|v| v.get("width"))
            .and_then(|v| v.as_i64())
            .and_then(|v| i32::try_from(v).ok())
            .unwrap_or(-1);
        let height = item
            .get("size")
            .and_then(|v| v.get("height"))
            .and_then(|v| v.as_i64())
            .and_then(|v| i32::try_from(v).ok())
            .unwrap_or(-1);
        let replication_source = item
            .get("replicationSource")
            .and_then(|v| v.as_i64())
            .unwrap_or(0);
        parsed.push(KscreenOutput {
            name: name.to_string(),
            enabled: item
                .get("enabled")
                .and_then(|v| v.as_bool())
                .unwrap_or(false),
            connected: item
                .get("connected")
                .and_then(|v| v.as_bool())
                .unwrap_or(false),
            replication_source,
            x,
            y,
            width,
            height,
        });
    }
    Ok(parsed)
}

fn output_ready_for_layout(output: &KscreenOutput) -> bool {
    output.enabled
        && output.connected
        && output.replication_source == 0
        && output.width > 0
        && output.height > 0
}

fn output_name_looks_virtual(name: &str) -> bool {
    let low = name.to_ascii_lowercase();
    low.contains("virtual")
        || low.contains("wbeam")
        || low.contains("headless")
        || low.contains("dummy")
        || low.contains("evdi")
        || low.starts_with("dvi-")
}

fn build_non_overlapping_layout_commands(
    outputs: &[KscreenOutput],
    mapped: Option<&HashSet<String>>,
) -> Vec<String> {
    let managed = outputs
        .iter()
        .filter(|o| output_ready_for_layout(o))
        .filter(|o| {
            if let Some(names) = mapped {
                if !names.is_empty() {
                    return names.contains(&o.name);
                }
            }
            output_name_looks_virtual(&o.name)
        })
        .cloned()
        .collect::<Vec<_>>();
    if managed.len() < 2 {
        return Vec::new();
    }
    if !has_overlap(&managed) {
        return Vec::new();
    }

    let managed_names: HashSet<String> = managed.iter().map(|o| o.name.clone()).collect();
    let mut anchor_x = outputs
        .iter()
        .filter(|o| output_ready_for_layout(o))
        .filter(|o| !managed_names.contains(&o.name))
        .map(|o| o.x.saturating_add(o.width.max(320)))
        .max()
        .unwrap_or(0);
    if anchor_x < 0 {
        anchor_x = 0;
    }

    let mut ordered = managed;
    ordered.sort_by(|a, b| {
        a.x.cmp(&b.x)
            .then_with(|| a.y.cmp(&b.y))
            .then_with(|| a.name.cmp(&b.name))
    });

    let mut commands = Vec::new();
    let mut x = anchor_x;
    for output in ordered {
        commands.push(format!("output.{}.position.{},{}", output.name, x, 0));
        x = x.saturating_add(output.width.max(320));
    }
    commands
}

fn has_overlap(outputs: &[KscreenOutput]) -> bool {
    for (idx, left) in outputs.iter().enumerate() {
        for right in outputs.iter().skip(idx + 1) {
            if rects_overlap(left, right) {
                return true;
            }
        }
    }
    false
}

fn rects_overlap(a: &KscreenOutput, b: &KscreenOutput) -> bool {
    let aw = a.width.max(1);
    let ah = a.height.max(1);
    let bw = b.width.max(1);
    let bh = b.height.max(1);
    let ax2 = a.x.saturating_add(aw);
    let ay2 = a.y.saturating_add(ah);
    let bx2 = b.x.saturating_add(bw);
    let by2 = b.y.saturating_add(bh);
    a.x < bx2 && ax2 > b.x && a.y < by2 && ay2 > b.y
}

fn apply_kscreen_layout(commands: &[String]) -> Result<(), String> {
    if commands.is_empty() {
        return Ok(());
    }
    let status = Command::new("kscreen-doctor")
        .args(commands)
        .status()
        .map_err(|e| format!("failed to execute kscreen-doctor layout: {e}"))?;
    if status.success() {
        Ok(())
    } else {
        Err(format!("kscreen-doctor layout failed with status {status}"))
    }
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

fn persist_trainer_run_artifacts(run: &TrainerRun) {
    let run_dir = PathBuf::from(&run.run_artifacts_dir);
    if fs::create_dir_all(&run_dir).is_err() {
        return;
    }
    let run_doc = serde_json::json!({
        "run_id": run.run_id,
        "status": run.status,
        "started_at_unix_ms": run.started_at_unix_ms,
        "finished_at_unix_ms": run.finished_at_unix_ms,
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
        "exit_code": run.exit_code,
        "error": run.error,
    });
    let _ = fs::write(
        run_dir.join("run.json"),
        serde_json::to_vec_pretty(&run_doc).unwrap_or_default(),
    );

    let log_src = PathBuf::from(&run.log_path);
    if log_src.exists() {
        let _ = fs::copy(&log_src, run_dir.join("logs.txt"));
    }

    let profile_name = sanitize_profile_name(&run.profile_name);
    let profile_dir = PathBuf::from(&run.profile_dir);
    let profile_src = profile_dir.join(format!("{profile_name}.json"));
    let params_src = profile_dir.join("parameters.json");
    let preflight_src = profile_dir.join("preflight.json");
    if profile_src.exists() {
        let _ = fs::copy(profile_src, run_dir.join(format!("{profile_name}.json")));
    }
    if params_src.exists() {
        let _ = fs::copy(params_src, run_dir.join("parameters.json"));
    }
    if preflight_src.exists() {
        let _ = fs::copy(preflight_src, run_dir.join("preflight.json"));
    }
}

fn list_adb_devices(base_stream_port: u16) -> Vec<TrainerDeviceInfo> {
    let out = Command::new("adb").arg("devices").output();
    let Ok(output) = out else {
        return Vec::new();
    };
    let stdout = String::from_utf8_lossy(&output.stdout);
    let mut devices = Vec::new();
    let mut idx = 0usize;
    for line in stdout.lines().skip(1) {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        let parts: Vec<&str> = trimmed.split_whitespace().collect();
        if parts.len() < 2 {
            continue;
        }
        let serial = parts[0].to_string();
        let state = parts[1].to_string();
        let model = adb_shell_getprop(&serial, "ro.product.model");
        let api_level =
            adb_shell_getprop(&serial, "ro.build.version.sdk").and_then(|v| v.parse::<u32>().ok());
        let android_release = adb_shell_getprop(&serial, "ro.build.version.release");
        let stream_port = if state == "device" {
            Some(base_stream_port.saturating_add(idx as u16 + 1))
        } else {
            None
        };
        if state == "device" {
            idx += 1;
        }
        devices.push(TrainerDeviceInfo {
            serial,
            state,
            model,
            api_level,
            android_release,
            stream_port,
        });
    }
    devices
}

fn adb_shell_getprop(serial: &str, prop: &str) -> Option<String> {
    let out = Command::new("adb")
        .args(["-s", serial, "shell", "getprop", prop])
        .output()
        .ok()?;
    if !out.status.success() {
        return None;
    }
    let raw = String::from_utf8_lossy(&out.stdout).trim().to_string();
    if raw.is_empty() {
        None
    } else {
        Some(raw)
    }
}

fn now_unix_ms() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0)
}

fn trainer_profile_root(root: &Path) -> PathBuf {
    root.join("config").join("training").join("profiles")
}

fn sanitize_profile_name(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    for ch in raw.trim().chars() {
        if ch.is_ascii_alphanumeric() || ch == '_' || ch == '-' || ch == '.' {
            out.push(ch);
        } else if ch.is_whitespace() {
            out.push('_');
        }
    }
    let out = out
        .trim_matches(|c: char| c == '_' || c == '-' || c == '.')
        .to_string();
    if out.is_empty() {
        "profile".to_string()
    } else {
        out
    }
}

fn read_json_value(path: &Path) -> Value {
    let Ok(raw) = fs::read_to_string(path) else {
        return Value::Null;
    };
    serde_json::from_str(&raw).unwrap_or(Value::Null)
}

fn list_trainer_profiles(root: &Path) -> Vec<TrainerProfileSummary> {
    let mut out = Vec::new();
    let entries = match fs::read_dir(root) {
        Ok(v) => v,
        Err(_) => return out,
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if !path.is_dir() {
            continue;
        }
        let Some(name) = path.file_name().and_then(|v| v.to_str()) else {
            continue;
        };
        let profile_name = sanitize_profile_name(name);
        let profile_path = path.join(format!("{profile_name}.json"));
        let params_path = path.join("parameters.json");
        let preflight_path = path.join("preflight.json");
        let params = read_json_value(&params_path);
        let profile = read_json_value(&profile_path);
        let best_score = params
            .get("best")
            .and_then(|v| v.get("score"))
            .and_then(|v| v.as_f64())
            .or_else(|| {
                profile
                    .get("profile")
                    .and_then(|v| v.get("origin"))
                    .and_then(|v| v.get("score"))
                    .and_then(|v| v.as_f64())
            });
        let engine = params
            .get("engine")
            .and_then(|v| v.as_str())
            .map(str::to_string)
            .or_else(|| {
                profile
                    .get("engine")
                    .and_then(|v| v.as_str())
                    .map(str::to_string)
            });
        let serial = params
            .get("serial")
            .and_then(|v| v.as_str())
            .map(str::to_string)
            .or_else(|| {
                profile
                    .get("device")
                    .and_then(|v| v.get("serial"))
                    .and_then(|v| v.as_str())
                    .map(str::to_string)
            });
        let updated_at_unix_ms = fs::metadata(&path)
            .ok()
            .and_then(|m| m.modified().ok())
            .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
            .map(|d| d.as_millis());
        out.push(TrainerProfileSummary {
            profile_name,
            path: path.to_string_lossy().to_string(),
            has_profile: profile_path.exists(),
            has_parameters: params_path.exists(),
            has_preflight: preflight_path.exists(),
            best_score,
            engine,
            serial,
            updated_at_unix_ms,
        });
    }
    out
}

fn adb_push_benchmark(serial: &str, size_mb: u32) -> Value {
    let size_mb = size_mb.clamp(1, 64);
    let bytes = (size_mb as usize) * 1024 * 1024;
    let tmp_path = std::env::temp_dir().join(format!(
        "wbeam-trainer-preflight-{}-{}.bin",
        serial,
        now_unix_ms()
    ));
    let remote_path = "/data/local/tmp/wbeam-trainer-preflight.bin";
    let write_ok = fs::write(&tmp_path, vec![0u8; bytes]).is_ok();
    if !write_ok {
        return serde_json::json!({
            "ok": false,
            "error": "failed to write temp benchmark file",
        });
    }
    let started = std::time::Instant::now();
    let out = Command::new("adb")
        .args(["-s", serial, "push"])
        .arg(&tmp_path)
        .arg(remote_path)
        .output();
    let elapsed = started.elapsed().as_secs_f64().max(0.001);
    let _ = Command::new("adb")
        .args(["-s", serial, "shell", "rm", "-f", remote_path])
        .status();
    let _ = fs::remove_file(&tmp_path);
    let output = match out {
        Ok(v) => v,
        Err(err) => {
            return serde_json::json!({
                "ok": false,
                "size_mb": size_mb,
                "error": format!("adb push failed to execute: {err}"),
            });
        }
    };
    let ok = output.status.success();
    let stdout = String::from_utf8_lossy(&output.stdout).to_string();
    let stderr = String::from_utf8_lossy(&output.stderr).to_string();
    let mut throughput = ((bytes as f64) / elapsed) / (1024.0 * 1024.0);
    if let Some((parsed_bytes, parsed_secs)) = parse_adb_push_bytes_and_secs(&stdout) {
        if parsed_secs > 0.0 {
            throughput = ((parsed_bytes as f64) / parsed_secs) / (1024.0 * 1024.0);
        }
    }
    serde_json::json!({
        "ok": ok,
        "size_mb": size_mb,
        "elapsed_sec": elapsed,
        "throughput_mb_s": throughput,
        "stdout": stdout.trim(),
        "stderr": stderr.trim(),
    })
}

fn parse_adb_push_bytes_and_secs(stdout: &str) -> Option<(u64, f64)> {
    let start = stdout.find('(')?;
    let end = stdout[start..].find(')')?;
    let inner = &stdout[start + 1..start + end];
    let mut bytes: Option<u64> = None;
    let mut secs: Option<f64> = None;
    for token in inner.split_whitespace() {
        if token == "bytes" {
            continue;
        }
        if token.ends_with('s') {
            let clean = token.trim_end_matches('s');
            if let Ok(v) = clean.parse::<f64>() {
                secs = Some(v);
            }
        } else if bytes.is_none() {
            if let Ok(v) = token.parse::<u64>() {
                bytes = Some(v);
            }
        }
    }
    Some((bytes?, secs?))
}

fn adb_shell_rtt_benchmark(serial: &str, loops: u32) -> Value {
    let loops = loops.clamp(1, 50);
    let mut samples = Vec::<f64>::new();
    let mut failures = 0u32;
    for _ in 0..loops {
        let started = std::time::Instant::now();
        let status = Command::new("adb")
            .args(["-s", serial, "shell", "true"])
            .status();
        let ms = started.elapsed().as_secs_f64() * 1000.0;
        match status {
            Ok(s) if s.success() => samples.push(ms),
            _ => failures += 1,
        }
    }
    if samples.is_empty() {
        return serde_json::json!({
            "ok": false,
            "loops": loops,
            "failures": failures,
            "error": "no successful adb shell samples",
        });
    }
    samples.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    let avg = samples.iter().sum::<f64>() / samples.len() as f64;
    let p50 = percentile_sorted(&samples, 0.5);
    let p95 = percentile_sorted(&samples, 0.95);
    serde_json::json!({
        "ok": true,
        "loops": loops,
        "failures": failures,
        "rtt_avg_ms": avg,
        "rtt_p50_ms": p50,
        "rtt_p95_ms": p95,
    })
}

fn percentile_sorted(sorted: &[f64], q: f64) -> f64 {
    if sorted.is_empty() {
        return 0.0;
    }
    let qq = q.clamp(0.0, 1.0);
    let idx = qq * ((sorted.len() - 1) as f64);
    let lo = idx.floor() as usize;
    let hi = idx.ceil() as usize;
    if lo == hi {
        return sorted[lo];
    }
    let frac = idx - lo as f64;
    sorted[lo] * (1.0 - frac) + sorted[hi] * frac
}
