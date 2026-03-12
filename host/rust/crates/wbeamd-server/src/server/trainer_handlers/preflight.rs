use axum::extract::State;
use axum::http::StatusCode;
use axum::response::IntoResponse;
use axum::Json;
use serde_json::Value;

use crate::AppState;

use crate::server::runtime_utils::bad_request_json;
use crate::server::trainer_models::{TrainerPreflightRequest, TrainerPreflightResponse};
use crate::server::trainer_support::{adb_push_benchmark, adb_shell_rtt_benchmark};

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
        return bad_request_json("serial is required");
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
