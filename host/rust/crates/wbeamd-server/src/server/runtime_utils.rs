use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{SystemTime, UNIX_EPOCH};

use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::Json;
use serde_json::json;
use wbeamd_api::ErrorResponse;
use wbeamd_core::{CoreError, DaemonCore};

pub(crate) async fn core_error_response(
    core: Arc<DaemonCore>,
    err: CoreError,
) -> axum::response::Response {
    let status = match err {
        CoreError::Validation(_) | CoreError::UnsupportedHost(_) => StatusCode::BAD_REQUEST,
        _ => StatusCode::INTERNAL_SERVER_ERROR,
    };

    let resp: ErrorResponse = core.error_response(err.to_string()).await;
    (status, Json(resp)).into_response()
}

pub(crate) async fn shutdown_signal() {
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

pub(crate) fn bad_request_json(message: impl AsRef<str>) -> axum::response::Response {
    (
        StatusCode::BAD_REQUEST,
        Json(json!({"ok": false, "error": message.as_ref()})),
    )
        .into_response()
}

pub(crate) fn not_found_json(message: impl AsRef<str>) -> axum::response::Response {
    (
        StatusCode::NOT_FOUND,
        Json(json!({"ok": false, "error": message.as_ref()})),
    )
        .into_response()
}

pub(crate) fn internal_json(message: impl AsRef<str>) -> axum::response::Response {
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        Json(json!({"ok": false, "error": message.as_ref()})),
    )
        .into_response()
}

pub(crate) fn workspace_root_from_manifest(manifest_dir: &str) -> PathBuf {
    let path = Path::new(manifest_dir);
    // host/rust/crates/wbeamd-server -> repo root is 4 levels up.
    path.join("../../../../")
        .canonicalize()
        .unwrap_or_else(|_| PathBuf::from("."))
}

pub(crate) fn fill_pseudorandom(buf: &mut [u8]) {
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
