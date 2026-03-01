use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use axum::extract::{Query, State};
use axum::http::{header, StatusCode};
use axum::response::IntoResponse;
use axum::routing::{get, post};
use axum::{Json, Router};
use clap::Parser;
use serde::Deserialize;
use tracing::{error, info};
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
    core: Arc<DaemonCore>,
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
    let app_state = AppState { core: core.clone() };

    let app = Router::new()
        .route("/status", get(get_status))
        .route("/health", get(get_health))
        .route("/presets", get(get_presets))
        .route("/metrics", get(get_metrics))
        .route("/speedtest", get(get_speedtest))
        .route("/start", post(post_start))
        .route("/stop", post(post_stop))
        .route("/apply", post(post_apply))
        .route("/client-metrics", post(post_client_metrics))
        .route("/client-hello", post(post_client_hello))
        .route("/v1/status", get(get_status))
        .route("/v1/health", get(get_health))
        .route("/v1/presets", get(get_presets))
        .route("/v1/metrics", get(get_metrics))
        .route("/v1/speedtest", get(get_speedtest))
        .route("/v1/start", post(post_start))
        .route("/v1/stop", post(post_stop))
        .route("/v1/apply", post(post_apply))
        .route("/v1/client-metrics", post(post_client_metrics))
        .route("/v1/client-hello", post(post_client_hello))
        .with_state(app_state);

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

    let shutdown_core = core.clone();
    let server =
        axum::serve(listener, app.into_make_service()).with_graceful_shutdown(async move {
            shutdown_signal().await;
            let _ = shutdown_core.stop().await;
        });

    if let Err(err) = server.await {
        error!(error = %err, "server terminated with error");
    }
}

async fn get_status(State(state): State<AppState>) -> impl IntoResponse {
    Json(state.core.status().await)
}

async fn get_health(State(state): State<AppState>) -> impl IntoResponse {
    Json(state.core.health().await)
}

async fn get_presets(State(state): State<AppState>) -> impl IntoResponse {
    Json(state.core.presets().await)
}

async fn get_metrics(State(state): State<AppState>) -> impl IntoResponse {
    Json(state.core.metrics().await)
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

async fn post_start(
    State(state): State<AppState>,
    body: Option<Json<ConfigPatch>>,
) -> impl IntoResponse {
    let patch = body.map(|Json(v)| v).unwrap_or_default();
    match state.core.start(patch).await {
        Ok(resp) => (StatusCode::OK, Json(resp)).into_response(),
        Err(err) => core_error_response(state.core.clone(), err).await,
    }
}

async fn post_stop(State(state): State<AppState>) -> impl IntoResponse {
    match state.core.stop().await {
        Ok(resp) => (StatusCode::OK, Json(resp)).into_response(),
        Err(err) => core_error_response(state.core.clone(), err).await,
    }
}

async fn post_apply(
    State(state): State<AppState>,
    body: Option<Json<ConfigPatch>>,
) -> impl IntoResponse {
    let patch = body.map(|Json(v)| v).unwrap_or_default();
    match state.core.apply(patch).await {
        Ok(resp) => (StatusCode::OK, Json(resp)).into_response(),
        Err(err) => core_error_response(state.core.clone(), err).await,
    }
}

async fn post_client_metrics(
    State(state): State<AppState>,
    body: Option<Json<ClientMetricsRequest>>,
) -> impl IntoResponse {
    let payload = body.map(|Json(v)| v).unwrap_or_default();
    match state.core.ingest_client_metrics(payload).await {
        Ok(resp) => (StatusCode::OK, Json(resp)).into_response(),
        Err(err) => core_error_response(state.core.clone(), err).await,
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
        CoreError::Validation(_) => StatusCode::BAD_REQUEST,
        _ => StatusCode::INTERNAL_SERVER_ERROR,
    };

    let resp: ErrorResponse = core.error_response(err.to_string()).await;
    (status, Json(resp)).into_response()
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
