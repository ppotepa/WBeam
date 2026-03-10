use std::collections::{HashMap, HashSet};
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use axum::extract::{Query, State};
use axum::http::{header, StatusCode};
use axum::response::IntoResponse;
use axum::routing::{get, post};
use axum::{Json, Router};
use clap::Parser;
use serde::Deserialize;
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

                let stream_port = requested_stream_port
                    .filter(|p| *p > 0)
                    .unwrap_or_else(|| self.base_stream_port.saturating_add(2 + guard.len() as u16));
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
    let app_state = AppState { sessions };

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
    let raw = std::env::var("WBEAM_WAYLAND_PORTAL_AUTO_LAYOUT")
        .unwrap_or_else(|_| "1".to_string());
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
