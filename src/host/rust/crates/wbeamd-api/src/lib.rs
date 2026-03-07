use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};
use thiserror::Error;

pub const VALID_ENCODERS: &[&str] = &["h264", "h265", "rawpng"];
pub const VALID_CURSOR_MODES: &[&str] = &["hidden", "embedded", "metadata"];
pub const VALID_PROFILES: &[&str] = &[
    "lowlatency",
    "balanced",
    "ultra",
    "safe_60",
    "aggressive_60",
    "quality_60",
    "debug_60",
    "fast60",
    "balanced60",
    "quality60",
    "fast60_2",
    "fast60_3",
];

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ActiveConfig {
    pub profile: String,
    pub encoder: String,
    pub cursor_mode: String,
    pub size: String,
    pub fps: u32,
    pub bitrate_kbps: u32,
    pub debug_fps: u32,
    /// All-Intra mode: gop=1, every frame is a full IDR keyframe.
    /// Backward-compatible: missing in JSON deserializes to false.
    #[serde(default)]
    pub intra_only: bool,
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

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ClientHelloRequest {
    pub sdk_int: u32,
    pub device_model: String,
    pub device_manufacturer: String,
    pub abi: String,
    pub policy: String,
    pub preferred_fps: u32,
    pub preferred_codec: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResolverDecisionResponse {
    pub selected_profile: String,
    pub selected_backend: String,
    pub selected_codec: String,
    pub reason: String,
    pub sdk_tier: String,
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
    pub intra_only: Option<bool>,
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
pub struct HostProbeResponse {
    #[serde(flatten)]
    pub base: BaseResponse,
    pub ok: bool,
    pub os: String,
    pub session: String,
    pub desktop: String,
    pub capture_mode: String,
    pub remote: bool,
    pub display: Option<String>,
    pub wayland_display: Option<String>,
    pub supported: bool,
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
    fn cfg(profile: &str, size: &str, fps: u32, bitrate_kbps: u32, encoder: &str) -> ActiveConfig {
        ActiveConfig {
            profile: profile.to_string(),
            encoder: encoder.to_string(),
            cursor_mode: "embedded".to_string(),
            size: size.to_string(),
            fps,
            bitrate_kbps,
            debug_fps: 0,
            intra_only: false,
        }
    }

    let mut map = BTreeMap::new();
    map.insert(
        "lowlatency".to_string(),
        cfg("lowlatency", "1280x720", 60, 60_000, "h265"),
    );
    map.insert(
        "balanced".to_string(),
        cfg("balanced", "1920x1080", 60, 100_000, "h265"),
    );
    map.insert(
        "ultra".to_string(),
        cfg("ultra", "2560x1440", 60, 150_000, "h265"),
    );

    // Proto-trained profiles (2026-02 autotune lineage), integrated into main presets.
    map.insert(
        "safe_60".to_string(),
        cfg("safe_60", "1024x640", 60, 10_000, "h264"),
    );
    map.insert(
        "aggressive_60".to_string(),
        cfg("aggressive_60", "1024x640", 60, 7_000, "h264"),
    );
    map.insert(
        "quality_60".to_string(),
        cfg("quality_60", "1024x640", 60, 11_000, "h264"),
    );
    map.insert(
        "debug_60".to_string(),
        cfg("debug_60", "1024x640", 60, 8_500, "h264"),
    );
    map.insert(
        "fast60".to_string(),
        cfg("fast60", "1024x640", 60, 10_000, "h264"),
    );
    map.insert(
        "balanced60".to_string(),
        cfg("balanced60", "1024x640", 60, 10_000, "h264"),
    );
    map.insert(
        "quality60".to_string(),
        cfg("quality60", "1024x640", 60, 10_000, "h264"),
    );
    map.insert(
        "fast60_2".to_string(),
        cfg("fast60_2", "1280x800", 60, 10_000, "h264"),
    );
    map.insert(
        "fast60_3".to_string(),
        cfg("fast60_3", "1280x800", 60, 10_000, "h264"),
    );
    map
}

pub fn validate_config(
    patch: ConfigPatch,
    current: &ActiveConfig,
) -> Result<ActiveConfig, ValidationError> {
    validate_config_with_presets(patch, current, &presets())
}

pub fn validate_config_with_presets(
    patch: ConfigPatch,
    current: &ActiveConfig,
    preset_map: &BTreeMap<String, ActiveConfig>,
) -> Result<ActiveConfig, ValidationError> {
    let base_profile = patch
        .profile
        .as_deref()
        .unwrap_or(&current.profile)
        .to_string();
    if !preset_map.contains_key(&base_profile) {
        return Err(ValidationError::InvalidProfile);
    }

    let requested_profile = patch.profile.clone();
    let mut cfg = if requested_profile.is_some() {
        preset_map
            .get(&base_profile)
            .cloned()
            .ok_or(ValidationError::InvalidProfile)?
    } else {
        current.clone()
    };
    cfg.profile = base_profile;

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
    if let Some(intra_only) = patch.intra_only {
        cfg.intra_only = intra_only;
    }

    cfg.encoder = match cfg.encoder.as_str() {
        "h264" | "h265" | "rawpng" => cfg.encoder,
        // Backward-compatible migration of old modes to unified h265.
        "auto" | "nvenc" | "nvenc265" | "x265" | "openh264" => "h265".to_string(),
        _ => cfg.encoder,
    };

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
    cfg.bitrate_kbps = cfg.bitrate_kbps.clamp(4_000, 300_000);
    cfg.debug_fps = cfg.debug_fps.clamp(0, 10);

    // RAW PNG is CPU/network heavy. Clamp to a latency-friendly envelope.
    if cfg.encoder == "rawpng" {
        cfg.fps = cfg.fps.clamp(5, 20);

        let mut rw = w;
        let mut rh = h;
        let max_pixels: u32 = 1280 * 720;
        let pixels = rw.saturating_mul(rh);
        if pixels > max_pixels {
            let scale = (max_pixels as f64 / pixels as f64).sqrt();
            rw = ((rw as f64 * scale).floor() as u32).max(640);
            rh = ((rh as f64 * scale).floor() as u32).max(360);
            if rw % 2 == 1 {
                rw -= 1;
            }
            if rh % 2 == 1 {
                rh -= 1;
            }
        }
        cfg.size = format!("{rw}x{rh}");
        cfg.intra_only = false;
    }

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
