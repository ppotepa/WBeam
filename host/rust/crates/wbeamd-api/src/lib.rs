use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};
use thiserror::Error;

pub const VALID_ENCODERS: &[&str] = &["auto", "nvenc", "openh264"];
pub const VALID_CURSOR_MODES: &[&str] = &["hidden", "embedded", "metadata"];
pub const VALID_PROFILES: &[&str] = &["lowlatency", "balanced", "ultra"];

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ActiveConfig {
    pub profile: String,
    pub encoder: String,
    pub cursor_mode: String,
    pub size: String,
    pub fps: u32,
    pub bitrate_kbps: u32,
    pub debug_fps: u32,
}

impl ActiveConfig {
    pub fn balanced_default() -> Self {
        presets()
            .remove("balanced")
            .expect("balanced preset must exist")
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClientMetricsRequest {
    pub recv_fps: f64,
    pub decode_fps: f64,
    pub present_fps: f64,
    pub recv_bps: u64,
    pub decode_time_ms_p50: f64,
    pub decode_time_ms_p95: f64,
    pub render_time_ms_p95: f64,
    pub e2e_latency_ms_p50: f64,
    pub e2e_latency_ms_p95: f64,
    pub transport_queue_depth: u32,
    pub decode_queue_depth: u32,
    pub render_queue_depth: u32,
    pub jitter_buffer_frames: u32,
    pub dropped_frames: u64,
    pub too_late_frames: u64,
    pub timestamp_ms: u64,
    /// P2.2: monotonic trace ID set by Android – high 32 bits = connect counter,
    /// low 32 bits = per-session sample counter.  Missing/null is silently OK.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub trace_id: Option<u64>,
}

impl Default for ClientMetricsRequest {
    fn default() -> Self {
        Self {
            recv_fps: 0.0,
            decode_fps: 0.0,
            present_fps: 0.0,
            recv_bps: 0,
            decode_time_ms_p50: 0.0,
            decode_time_ms_p95: 0.0,
            render_time_ms_p95: 0.0,
            e2e_latency_ms_p50: 0.0,
            e2e_latency_ms_p95: 0.0,
            transport_queue_depth: 0,
            decode_queue_depth: 0,
            render_queue_depth: 0,
            jitter_buffer_frames: 0,
            dropped_frames: 0,
            too_late_frames: 0,
            timestamp_ms: 0,
            trace_id: None,
        }
    }
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct ConfigPatch {
    pub profile: Option<String>,
    pub encoder: Option<String>,
    pub cursor_mode: Option<String>,
    pub size: Option<String>,
    pub fps: Option<u32>,
    pub bitrate_kbps: Option<u32>,
    pub debug_fps: Option<u32>,
}

#[derive(Debug, Clone, Serialize)]
pub struct BaseResponse {
    pub state: String,
    pub active_config: ActiveConfig,
    pub host_name: String,
    pub uptime: u64,
    pub run_id: u64,
    pub last_error: String,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct ValidValues {
    pub encoder: Vec<String>,
    pub cursor_mode: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct FrameBudgetMs {
    pub capture: f64,
    pub encode: f64,
    pub transport: f64,
    pub decode: f64,
    pub render: f64,
    pub margin: f64,
    pub total: f64,
}

impl Default for FrameBudgetMs {
    fn default() -> Self {
        Self {
            capture: 2.6,
            encode: 3.4,
            transport: 2.2,
            decode: 4.1,
            render: 2.7,
            margin: 1.67,
            total: 16.67,
        }
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct QueueLimits {
    pub usb_jitter_frames_max: u32,
    pub wifi_jitter_frames_max: u32,
    pub decode_queue_max: u32,
    pub render_queue_max: u32,
    pub transport_queue_max: u32,
    pub policy: String,
}

impl Default for QueueLimits {
    fn default() -> Self {
        Self {
            usb_jitter_frames_max: 1,
            wifi_jitter_frames_max: 3,
            decode_queue_max: 2,
            render_queue_max: 1,
            transport_queue_max: 3,
            policy: "bounded + latest-frame-wins + drop-late".to_string(),
        }
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct E2eTelemetrySchema {
    pub timestamps: Vec<String>,
    pub queue_depth_fields: Vec<String>,
    pub required_client_fields: Vec<String>,
}

impl Default for E2eTelemetrySchema {
    fn default() -> Self {
        Self {
            timestamps: vec![
                "capture_ts".to_string(),
                "encode_ts".to_string(),
                "send_ts".to_string(),
                "recv_ts".to_string(),
                "decode_ts".to_string(),
                "present_ts".to_string(),
            ],
            queue_depth_fields: vec![
                "transport_queue_depth".to_string(),
                "decode_queue_depth".to_string(),
                "render_queue_depth".to_string(),
            ],
            required_client_fields: vec![
                "decode_time_ms_p95".to_string(),
                "render_time_ms_p95".to_string(),
                "present_fps".to_string(),
                "dropped_frames".to_string(),
                "too_late_frames".to_string(),
            ],
        }
    }
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct KpiSnapshot {
    pub target_fps: u32,
    pub recv_fps: f64,
    pub decode_fps: f64,
    pub present_fps: f64,
    pub frametime_ms_p95: f64,
    pub e2e_latency_ms_p50: f64,
    pub e2e_latency_ms_p95: f64,
    pub decode_time_ms_p95: f64,
    pub render_time_ms_p95: f64,
}

#[derive(Debug, Clone, Serialize, Default)]
pub struct MetricsSnapshot {
    pub start_count: u64,
    pub stop_count: u64,
    pub restart_count: u64,
    pub reconnects: u64,
    pub frame_in: u64,
    pub frame_out: u64,
    pub drops: u64,
    pub bitrate_actual_bps: u64,
    pub encode_latency_ms: f64,
    pub stream_uptime_sec: u64,
    pub frame_budget_ms: FrameBudgetMs,
    pub queue_limits: QueueLimits,
    pub e2e_schema: E2eTelemetrySchema,
    pub kpi: KpiSnapshot,
    pub latest_client_metrics: Option<ClientMetricsRequest>,
    pub adaptive_level: u8,
    pub adaptive_events: u64,
    pub adaptive_action: String,
    pub adaptive_reason: String,
    pub backpressure_high_events: u64,
    pub backpressure_recover_events: u64,
}

#[derive(Debug, Clone, Serialize)]
pub struct StatusResponse {
    #[serde(flatten)]
    pub base: BaseResponse,
    pub ok: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct HealthResponse {
    #[serde(flatten)]
    pub base: BaseResponse,
    pub ok: bool,
    pub service: String,
    pub build_revision: String,
    pub stream_process_alive: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct PresetsResponse {
    #[serde(flatten)]
    pub base: BaseResponse,
    pub ok: bool,
    pub presets: BTreeMap<String, ActiveConfig>,
    pub valid: ValidValues,
}

#[derive(Debug, Clone, Serialize)]
pub struct MetricsResponse {
    #[serde(flatten)]
    pub base: BaseResponse,
    pub ok: bool,
    pub metrics: MetricsSnapshot,
}

#[derive(Debug, Clone, Serialize)]
pub struct ClientMetricsResponse {
    #[serde(flatten)]
    pub base: BaseResponse,
    pub ok: bool,
    pub action: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct ErrorResponse {
    #[serde(flatten)]
    pub base: BaseResponse,
    pub ok: bool,
    pub error: String,
}

#[derive(Debug, Error)]
pub enum ValidationError {
    #[error("invalid profile")]
    InvalidProfile,
    #[error("invalid encoder")]
    InvalidEncoder,
    #[error("invalid cursor_mode")]
    InvalidCursorMode,
    #[error("size must be WxH")]
    InvalidSize,
}

pub fn presets() -> BTreeMap<String, ActiveConfig> {
    let mut map = BTreeMap::new();
    map.insert(
        "lowlatency".to_string(),
        ActiveConfig {
            profile: "lowlatency".to_string(),
            encoder: "auto".to_string(),
            cursor_mode: "hidden".to_string(),
            size: "1280x720".to_string(),
            fps: 60,
            bitrate_kbps: 16_000,
            debug_fps: 0,
        },
    );
    map.insert(
        "balanced".to_string(),
        ActiveConfig {
            profile: "balanced".to_string(),
            encoder: "auto".to_string(),
            cursor_mode: "hidden".to_string(),
            size: "1920x1080".to_string(),
            fps: 60,
            bitrate_kbps: 25_000,
            debug_fps: 0,
        },
    );
    map.insert(
        "ultra".to_string(),
        ActiveConfig {
            profile: "ultra".to_string(),
            encoder: "auto".to_string(),
            cursor_mode: "hidden".to_string(),
            size: "2560x1440".to_string(),
            fps: 60,
            bitrate_kbps: 38_000,
            debug_fps: 0,
        },
    );
    map
}

pub fn validate_config(patch: ConfigPatch, current: &ActiveConfig) -> Result<ActiveConfig, ValidationError> {
    let base_profile = patch
        .profile
        .as_deref()
        .unwrap_or(&current.profile)
        .to_string();
    if !VALID_PROFILES.contains(&base_profile.as_str()) {
        return Err(ValidationError::InvalidProfile);
    }

    let mut cfg = presets()
        .remove(&base_profile)
        .ok_or(ValidationError::InvalidProfile)?;

    if let Some(encoder) = patch.encoder {
        cfg.encoder = encoder;
    }
    if let Some(cursor_mode) = patch.cursor_mode {
        cfg.cursor_mode = cursor_mode;
    }
    if let Some(size) = patch.size {
        cfg.size = size;
    }
    if let Some(fps) = patch.fps {
        cfg.fps = fps;
    }
    if let Some(bitrate_kbps) = patch.bitrate_kbps {
        cfg.bitrate_kbps = bitrate_kbps;
    }
    if let Some(debug_fps) = patch.debug_fps {
        cfg.debug_fps = debug_fps;
    }

    if !VALID_ENCODERS.contains(&cfg.encoder.as_str()) {
        return Err(ValidationError::InvalidEncoder);
    }
    if !VALID_CURSOR_MODES.contains(&cfg.cursor_mode.as_str()) {
        return Err(ValidationError::InvalidCursorMode);
    }

    let size = cfg.size.to_lowercase();
    let Some((w_str, h_str)) = size.split_once('x') else {
        return Err(ValidationError::InvalidSize);
    };

    let Ok(mut w) = w_str.parse::<u32>() else {
        return Err(ValidationError::InvalidSize);
    };
    let Ok(mut h) = h_str.parse::<u32>() else {
        return Err(ValidationError::InvalidSize);
    };

    w = w.clamp(640, 3840);
    h = h.clamp(360, 2160);
    if w % 2 == 1 {
        w -= 1;
    }
    if h % 2 == 1 {
        h -= 1;
    }

    cfg.size = format!("{w}x{h}");
    cfg.fps = cfg.fps.clamp(24, 120);
    cfg.bitrate_kbps = cfg.bitrate_kbps.clamp(4_000, 120_000);
    cfg.debug_fps = cfg.debug_fps.clamp(0, 10);

    Ok(cfg)
}

pub fn valid_values() -> ValidValues {
    ValidValues {
        encoder: VALID_ENCODERS.iter().map(|v| (*v).to_string()).collect(),
        cursor_mode: VALID_CURSOR_MODES
            .iter()
            .map(|v| (*v).to_string())
            .collect(),
    }
}
