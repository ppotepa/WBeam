use std::collections::HashSet;
use std::net::SocketAddr;
use std::path::PathBuf;
use std::sync::Arc;

use axum::extract::{Query, State};
use axum::http::{header, StatusCode};
use axum::response::IntoResponse;
use axum::Json;
use clap::Parser;
use serde::Deserialize;
use tracing::{error, info, warn};
use tracing_subscriber::EnvFilter;
use wbeamd_api::{ClientMetricsRequest, ConfigPatch, TuningStatusPatch};
use wbeamd_core::DaemonCore;

mod server;
use server::kscreen_layout;
use server::routes::build_router;
use server::runtime_utils::{
    core_error_response, fill_pseudorandom, shutdown_signal, workspace_root_from_manifest,
};
use server::session_registry::{SessionQuery, SessionRegistry};

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
    let app_state = AppState { sessions };

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
    let core = state
        .sessions
        .resolve_core_readonly(serial, query.stream_port)
        .await
        .unwrap_or_else(|| state.sessions.default_core());
    let payload =
        serde_json::to_value(core.metrics().await).unwrap_or_else(|_| serde_json::json!({}));
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
    apply_start_overrides(&core, &query, serial).await;
    let patch = body.map(|Json(v)| v).unwrap_or_default();
    let host_probe = core.host_probe().await;
    let selected_backend = selected_capture_backend(&query, host_probe.capture_mode.as_str());
    let is_wayland_portal = selected_backend == "wayland_portal";
    let is_evdi = selected_backend == "evdi";
    let pre_enabled_outputs = pre_enabled_outputs_for_backend(is_wayland_portal, is_evdi);
    let start_result = run_start_with_portal_gate(&state, &core, patch, is_wayland_portal).await;
    match start_result {
        Ok(resp) => {
            // Run layout configuration in background — don't block the HTTP response.
            let state_bg = state.clone();
            let serial_bg = serial.map(|s| s.to_string());
            tokio::spawn(async move {
                post_start_output_layout(
                    &state_bg,
                    serial_bg.as_deref(),
                    pre_enabled_outputs.as_ref(),
                    is_wayland_portal,
                    is_evdi,
                )
                .await;
            });
            (StatusCode::OK, Json(resp)).into_response()
        }
        Err(err) => core_error_response(core, err).await,
    }
}

async fn apply_start_overrides(core: &Arc<DaemonCore>, query: &SessionQuery, serial: Option<&str>) {
    if query.display_mode.is_some() {
        tracing::info!(
            serial = serial.unwrap_or("default"),
            stream_port = query.stream_port.unwrap_or(0),
            display_mode = query.display_mode.as_deref().unwrap_or(""),
            "start: display_mode override"
        );
        core.set_display_mode(query.display_mode.as_deref()).await;
    }
    if let Some(ref backend) = query.capture_backend {
        tracing::info!(
            serial = serial.unwrap_or("default"),
            capture_backend = backend.as_str(),
            "start: capture_backend override"
        );
        core.set_capture_backend(Some(backend.as_str())).await;
    } else {
        core.set_capture_backend(None).await;
    }
}

fn pre_enabled_outputs_for_backend(
    is_wayland_portal: bool,
    is_evdi: bool,
) -> Option<HashSet<String>> {
    if is_wayland_portal || is_evdi {
        kscreen_layout::kscreen_enabled_output_names().ok()
    } else {
        None
    }
}

async fn run_start_with_portal_gate(
    state: &AppState,
    core: &Arc<DaemonCore>,
    patch: ConfigPatch,
    is_wayland_portal: bool,
) -> Result<wbeamd_api::StatusResponse, wbeamd_core::CoreError> {
    if is_wayland_portal {
        let _guard = state.sessions.portal_start_gate.lock().await;
        core.start(patch).await
    } else {
        core.start(patch).await
    }
}

fn selected_capture_backend<'a>(query: &'a SessionQuery, probe_backend: &'a str) -> &'a str {
    query
        .capture_backend
        .as_deref()
        .filter(|v| !v.trim().is_empty())
        .unwrap_or(probe_backend)
}

async fn post_start_output_layout(
    state: &AppState,
    serial: Option<&str>,
    pre_enabled_outputs: Option<&std::collections::HashSet<String>>,
    is_wayland_portal: bool,
    is_evdi: bool,
) {
    if is_wayland_portal {
        if let Err(err) =
            auto_layout_wayland_portal_outputs(state.sessions.clone(), serial, pre_enabled_outputs)
                .await
        {
            warn!(error = %err, "wayland portal output auto-layout failed");
        }
        return;
    }
    if is_evdi {
        if let Err(err) = auto_configure_evdi_outputs(pre_enabled_outputs).await {
            warn!(error = %err, "evdi output auto-layout failed");
        }
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

async fn post_tuning(
    State(state): State<AppState>,
    Query(query): Query<SessionQuery>,
    body: Option<Json<TuningStatusPatch>>,
) -> impl IntoResponse {
    let serial = query.serial.as_deref();
    let core = state.sessions.resolve_core(serial, query.stream_port).await;
    let patch = body.map(|Json(v)| v).unwrap_or_default();
    let resp = core.update_tuning_status(patch).await;
    (StatusCode::OK, Json(resp)).into_response()
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

async fn auto_layout_wayland_portal_outputs(
    sessions: Arc<SessionRegistry>,
    serial: Option<&str>,
    pre_enabled_outputs: Option<&std::collections::HashSet<String>>,
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
    let mut commands =
        kscreen_layout::build_non_overlapping_layout_commands(&outputs, Some(&mapped));
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

async fn auto_configure_evdi_outputs(
    pre_enabled_outputs: Option<&std::collections::HashSet<String>>,
) -> Result<(), String> {
    if !kscreen_layout::command_exists("kscreen-doctor") {
        return Ok(());
    }

    let outputs = kscreen_layout::kscreen_query_outputs()?;
    let enabled_now: HashSet<String> = outputs
        .iter()
        .filter(|o| kscreen_layout::output_active(o))
        .map(|o| o.name.clone())
        .collect();

    let mut preferred_targets = HashSet::new();
    if let Some(before) = pre_enabled_outputs {
        let mut new_names: Vec<String> = enabled_now.difference(before).cloned().collect();
        new_names.sort();
        preferred_targets.extend(
            new_names
                .into_iter()
                .filter(|name| kscreen_layout::output_name_looks_virtual(name)),
        );
    }

    let preferred = if preferred_targets.is_empty() {
        None
    } else {
        Some(&preferred_targets)
    };

    if evdi_auto_mirror_enabled() {
        let commands = kscreen_layout::build_virtual_replication_commands(&outputs, preferred);
        if commands.is_empty() {
            return Ok(());
        }

        kscreen_layout::apply_kscreen_layout(&commands)?;
        info!(commands = ?commands, "applied evdi auto-mirror layout");
        return Ok(());
    }

    let unmirror_commands = kscreen_layout::build_virtual_unmirror_commands(&outputs, preferred);
    if !unmirror_commands.is_empty() {
        kscreen_layout::apply_kscreen_layout(&unmirror_commands)?;
        info!(commands = ?unmirror_commands, "applied evdi mirror detach");
    }

    let latest_outputs = if unmirror_commands.is_empty() {
        outputs
    } else {
        kscreen_layout::kscreen_query_outputs()?
    };
    let extend_commands = kscreen_layout::build_virtual_extend_commands(
        &latest_outputs,
        if preferred_targets.is_empty() {
            None
        } else {
            Some(&preferred_targets)
        },
    );
    if extend_commands.is_empty() {
        return Ok(());
    }

    kscreen_layout::apply_kscreen_layout(&extend_commands)?;
    info!(commands = ?extend_commands, "applied evdi non-overlap layout");
    Ok(())
}

fn wayland_portal_auto_layout_enabled() -> bool {
    let raw = std::env::var("WBEAM_WAYLAND_PORTAL_AUTO_LAYOUT").unwrap_or_else(|_| "1".to_string());
    !matches!(
        raw.trim().to_ascii_lowercase().as_str(),
        "0" | "false" | "no" | "off"
    )
}

fn evdi_auto_mirror_enabled() -> bool {
    let raw = std::env::var("WBEAM_EVDI_AUTO_MIRROR").unwrap_or_else(|_| "0".to_string());
    !matches!(
        raw.trim().to_ascii_lowercase().as_str(),
        "0" | "false" | "no" | "off"
    )
}
