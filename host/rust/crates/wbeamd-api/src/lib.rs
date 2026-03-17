use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};
use thiserror::Error;

pub const VALID_ENCODERS: &[&str] = &["h264", "h265", "rawpng"];
pub const VALID_CURSOR_MODES: &[&str] = &["hidden", "embedded", "metadata"];
pub const DEFAULT_PROFILE: &str = "default";
pub const ADAPTIVE_PROFILE: &str = "adaptive";
pub const VALID_PROFILES: &[&str] = &[DEFAULT_PROFILE, ADAPTIVE_PROFILE];

pub fn canonical_profile_name(raw: &str) -> String {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return DEFAULT_PROFILE.to_string();
    }
    if trimmed.eq_ignore_ascii_case(DEFAULT_PROFILE) || trimmed.eq_ignore_ascii_case("baseline") {
        return DEFAULT_PROFILE.to_string();
    }
    if trimmed.eq_ignore_ascii_case(ADAPTIVE_PROFILE)
        || trimmed.eq_ignore_ascii_case("adaptive_auto")
        || trimmed.eq_ignore_ascii_case("adaptive_locked_backend")
    {
        return ADAPTIVE_PROFILE.to_string();
    }
    DEFAULT_PROFILE.to_string()
}

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
            .remove(DEFAULT_PROFILE)
            .expect("default preset must exist")
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, Default, PartialEq, Eq)]
pub struct EffectiveRuntimeConfig {
    pub requested_encoder: String,
    pub resolved_backend: String,
    pub raw_format: String,
    pub size: String,
    pub fps: u32,
    pub bitrate_kbps: u32,
    pub cursor_mode: String,
    pub gop: u32,
    pub intra_only: bool,
    pub stream_mode: String,
    pub queue_max_buffers: u32,
    pub queue_max_time_ms: u32,
    pub appsink_max_buffers: u32,
    pub appsink_drop: bool,
    pub appsink_sync: bool,
    pub capture_backend: String,
    pub parse_mode: String,
    pub timeout_pull_ms: u32,
    pub timeout_write_ms: u32,
    pub timeout_disconnect: bool,
    pub videorate_drop_only: bool,
    pub pipewire_keepalive_ms: i32,
    pub snapshot_unix_ms: u128,
    pub snapshot_reason: String,
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
pub struct TuningStatus {
    pub active: bool,
    pub codec: String,
    pub phase: String,
    pub generation: u32,
    pub total_generations: u32,
    pub child: u32,
    pub children_per_generation: u32,
    pub score: f64,
    pub best_score: f64,
    pub note: String,
    pub updated_unix_ms: u64,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct TuningStatusPatch {
    pub clear: Option<bool>,
    pub active: Option<bool>,
    pub codec: Option<String>,
    pub phase: Option<String>,
    pub generation: Option<u32>,
    pub total_generations: Option<u32>,
    pub child: Option<u32>,
    pub children_per_generation: Option<u32>,
    pub score: Option<f64>,
    pub best_score: Option<f64>,
    pub note: Option<String>,
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
    #[serde(skip_serializing_if = "Option::is_none")]
    pub effective_runtime_config: Option<EffectiveRuntimeConfig>,
    pub host_name: String,
    pub uptime: u64,
    pub run_id: u64,
    pub last_error: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub target_serial: Option<String>,
    pub stream_port: u16,
    pub control_port: u16,
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
pub struct TransportRuntimeSnapshot {
    pub pipeline_fps: u32,
    pub sender_fps: f64,
    pub timeout_misses: u64,
    pub send_timeouts: u64,
    pub timeout_key: u64,
    pub timeout_delta: u64,
    pub key_retry_ok: u64,
    pub key_retry_fail: u64,
    pub queue_depth: u64,
    pub queue_peak: u64,
    pub queue_drops: u64,
    pub seq: u64,
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
    pub transport_runtime: TransportRuntimeSnapshot,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tuning: Option<TuningStatus>,
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
    /// All streaming backends available on this host (e.g. ["wayland_portal", "evdi"]).
    pub available_backends: Vec<String>,
    /// Whether the EVDI kernel module has allocated device nodes.
    pub evdi_available: bool,
}

#[derive(Debug, Clone, Serialize)]
pub struct VirtualDisplayProbeResponse {
    #[serde(flatten)]
    pub base: BaseResponse,
    pub ok: bool,
    pub host_backend: String,
    pub virtual_supported: bool,
    pub resolver: String,
    pub missing_deps: Vec<String>,
    pub install_hint: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct VirtualDisplayDoctorResponse {
    #[serde(flatten)]
    pub base: BaseResponse,
    pub ok: bool,
    pub message: String,
    pub actionable: bool,
    pub host_backend: String,
    pub resolver: String,
    pub missing_deps: Vec<String>,
    pub install_hint: String,
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
        DEFAULT_PROFILE.to_string(),
        cfg(DEFAULT_PROFILE, "1280x800", 60, 10_000, "h264"),
    );
    map.insert(
        ADAPTIVE_PROFILE.to_string(),
        cfg(ADAPTIVE_PROFILE, "1280x800", 60, 12_000, "h264"),
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
    let base_profile = canonical_profile_name(patch.profile.as_deref().unwrap_or(&current.profile));
    if !preset_map.contains_key(&base_profile) {
        return Err(ValidationError::InvalidProfile);
    }

    let mut cfg = base_config_for_patch(&patch, current, preset_map, &base_profile)?;
    cfg.profile = base_profile;

    apply_patch_values(&mut cfg, patch);

    cfg.encoder = normalize_encoder(cfg.encoder);
    validate_config_enums(&cfg)?;

    let (w, h) = parse_and_clamp_size(&cfg.size)?;

    cfg.size = format!("{w}x{h}");
    cfg.fps = cfg.fps.clamp(24, 120);
    // x265enc rejects values above ~100 Mbps on many hosts; keep config
    // within backend-safe limits to avoid runtime panics on stream start.
    let bitrate_max = bitrate_cap_for_encoder(&cfg.encoder);
    cfg.bitrate_kbps = cfg.bitrate_kbps.clamp(4_000, bitrate_max);
    cfg.debug_fps = cfg.debug_fps.clamp(0, 10);

    apply_rawpng_limits(&mut cfg, w, h);

    Ok(cfg)
}

fn base_config_for_patch(
    patch: &ConfigPatch,
    current: &ActiveConfig,
    preset_map: &BTreeMap<String, ActiveConfig>,
    base_profile: &str,
) -> Result<ActiveConfig, ValidationError> {
    if patch.profile.is_some() {
        return preset_map
            .get(base_profile)
            .cloned()
            .ok_or(ValidationError::InvalidProfile);
    }
    Ok(current.clone())
}

fn bitrate_cap_for_encoder(encoder: &str) -> u32 {
    if encoder == "h265" {
        100_000
    } else {
        300_000
    }
}

fn validate_config_enums(cfg: &ActiveConfig) -> Result<(), ValidationError> {
    if !VALID_ENCODERS.contains(&cfg.encoder.as_str()) {
        return Err(ValidationError::InvalidEncoder);
    }
    if !VALID_CURSOR_MODES.contains(&cfg.cursor_mode.as_str()) {
        return Err(ValidationError::InvalidCursorMode);
    }
    Ok(())
}

fn apply_rawpng_limits(cfg: &mut ActiveConfig, width: u32, height: u32) {
    if cfg.encoder != "rawpng" {
        return;
    }
    cfg.fps = cfg.fps.clamp(5, 20);

    let (rw, rh) = clamp_rawpng_size(width, height);
    cfg.size = format!("{rw}x{rh}");
    cfg.intra_only = false;
}

fn clamp_rawpng_size(width: u32, height: u32) -> (u32, u32) {
    let max_pixels: u32 = 1280 * 720;
    let pixels = width.saturating_mul(height);
    if pixels <= max_pixels {
        return (width, height);
    }

    let scale = (max_pixels as f64 / pixels as f64).sqrt();
    let mut rw = ((width as f64 * scale).floor() as u32).max(640);
    let mut rh = ((height as f64 * scale).floor() as u32).max(360);
    if rw % 2 == 1 {
        rw -= 1;
    }
    if rh % 2 == 1 {
        rh -= 1;
    }
    (rw, rh)
}

fn apply_patch_values(cfg: &mut ActiveConfig, patch: ConfigPatch) {
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
}

fn normalize_encoder(encoder: String) -> String {
    match encoder.as_str() {
        "auto" | "nvenc" | "nvenc265" | "x265" | "openh264" => "h265".to_string(),
        _ => encoder,
    }
}

fn parse_and_clamp_size(size: &str) -> Result<(u32, u32), ValidationError> {
    let size = size.to_lowercase();
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
    Ok((w, h))
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

#[cfg(test)]
mod tests {
    use super::*;

    fn base_config(encoder: &str, bitrate_kbps: u32) -> ActiveConfig {
        ActiveConfig {
            profile: DEFAULT_PROFILE.to_string(),
            encoder: encoder.to_string(),
            cursor_mode: "embedded".to_string(),
            size: "1280x800".to_string(),
            fps: 60,
            bitrate_kbps,
            debug_fps: 0,
            intra_only: false,
        }
    }

    #[test]
    fn validate_config_caps_h265_bitrate_to_backend_safe_limit() {
        let current = base_config("h265", 60_000);
        let patch = ConfigPatch {
            bitrate_kbps: Some(250_000),
            ..Default::default()
        };
        let cfg = validate_config(patch, &current).expect("valid config");
        assert_eq!(cfg.bitrate_kbps, 100_000);
    }

    #[test]
    fn validate_config_keeps_h264_high_bitrate_range() {
        let current = base_config("h264", 10_000);
        let patch = ConfigPatch {
            bitrate_kbps: Some(250_000),
            ..Default::default()
        };
        let cfg = validate_config(patch, &current).expect("valid config");
        assert_eq!(cfg.bitrate_kbps, 250_000);
    }

    #[test]
    fn canonical_profile_maps_adaptive_aliases() {
        assert_eq!(canonical_profile_name("adaptive"), ADAPTIVE_PROFILE.to_string());
        assert_eq!(
            canonical_profile_name("adaptive_locked_backend"),
            ADAPTIVE_PROFILE.to_string()
        );
        assert_eq!(canonical_profile_name("baseline"), DEFAULT_PROFILE.to_string());
    }

    #[test]
    fn validate_config_accepts_adaptive_profile() {
        let current = base_config("h264", 10_000);
        let patch = ConfigPatch {
            profile: Some("adaptive".to_string()),
            ..Default::default()
        };
        let cfg = validate_config(patch, &current).expect("adaptive profile should be valid");
        assert_eq!(cfg.profile, ADAPTIVE_PROFILE);
    }
}
