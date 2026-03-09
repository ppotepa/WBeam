//! Adaptive-quality policy: pressure detection and config degradation logic.
//!
//! All functions are pure — they depend only on the inputs supplied, never on
//! the `Inner` lock or any async runtime state.  This makes them trivially
//! testable without spinning up a full `DaemonCore`.

use wbeamd_api::{ActiveConfig, ClientMetricsRequest};

// ── Thresholds & tuning constants ───────────────────────────────────────────

pub const ADAPTATION_COOLDOWN_SECS: u64 = 4;
pub const HIGH_PRESSURE_STREAK_REQUIRED: u8 = 2;
pub const LOW_PRESSURE_STREAK_REQUIRED: u8 = 8;
pub const MAX_ADAPTATION_LEVEL: u8 = 3;

fn frame_budget_ms(fps_target: u32) -> f64 {
    1000.0 / fps_target.max(24) as f64
}

// ── Pressure detection ───────────────────────────────────────────────────────

/// Returns `true` when the client is showing signs of decode / render
/// pressure that warrant a quality reduction.
pub fn is_high_pressure(fps_target: u32, client: &ClientMetricsRequest) -> bool {
    let png_profile = fps_target <= 20;
    let target = fps_target.max(24) as f64;
    let budget_ms = frame_budget_ms(fps_target);
    let frametime_ms = if client.present_fps > 0.0 {
        1000.0 / client.present_fps.max(0.1)
    } else {
        0.0
    };
    if png_profile {
        return client.decode_time_ms_p95 > 8.5
            || client.render_time_ms_p95 > 5.0
            || client.transport_queue_depth >= 2
            || client.decode_queue_depth >= 1
            || client.render_queue_depth >= 1
            || client.too_late_frames > 0
            || client.present_fps < target * 0.95;
    }
    client.decode_time_ms_p95 > budget_ms * 0.55
        || client.render_time_ms_p95 > budget_ms * 0.35
        || client.transport_queue_depth >= 3
        || client.decode_queue_depth >= 2
        || client.render_queue_depth >= 1
        || client.too_late_frames > 0
        || (frametime_ms > 0.0 && frametime_ms > budget_ms * 1.10)
        || client.present_fps < target * 0.95
}

/// Returns `true` when the client is comfortably below all pressure thresholds
/// and a quality step-up is safe to attempt.
pub fn is_low_pressure(fps_target: u32, client: &ClientMetricsRequest) -> bool {
    let png_profile = fps_target <= 20;
    let target = fps_target.max(24) as f64;
    let budget_ms = frame_budget_ms(fps_target);
    let frametime_ms = if client.present_fps > 0.0 {
        1000.0 / client.present_fps.max(0.1)
    } else {
        0.0
    };
    if png_profile {
        return client.decode_time_ms_p95 > 0.0
            && client.decode_time_ms_p95 < 4.8
            && client.render_time_ms_p95 < 2.5
            && client.transport_queue_depth == 0
            && client.decode_queue_depth == 0
            && client.render_queue_depth == 0
            && client.present_fps >= target * 0.99;
    }
    client.decode_time_ms_p95 > 0.0
        && client.decode_time_ms_p95 < budget_ms * 0.30
        && client.render_time_ms_p95 < budget_ms * 0.20
        && client.transport_queue_depth == 0
        && client.decode_queue_depth == 0
        && client.render_queue_depth == 0
        && (frametime_ms == 0.0 || frametime_ms <= budget_ms * 1.02)
        && client.present_fps >= target * 0.99
}

/// Build a human-readable reason string for a telemetry/log entry.
pub fn adaptation_reason(client: &ClientMetricsRequest, high: bool, low: bool) -> String {
    if high {
        return format!(
            "decode_p95={:.2} render_p95={:.2} q={}/{}/{} fps={:.1}",
            client.decode_time_ms_p95,
            client.render_time_ms_p95,
            client.transport_queue_depth,
            client.decode_queue_depth,
            client.render_queue_depth,
            client.present_fps
        );
    }
    if low {
        return format!(
            "recovery decode_p95={:.2} render_p95={:.2} fps={:.1}",
            client.decode_time_ms_p95, client.render_time_ms_p95, client.present_fps
        );
    }
    "stable".to_string()
}

// ── Quality degradation profile ───────────────────────────────────────────────

/// Derive a reduced-quality `ActiveConfig` from `base` according to
/// `level` (0 = full quality, 1–3 = progressively degraded).
pub fn config_for_level(base: &ActiveConfig, level: u8) -> ActiveConfig {
    let mut cfg = base.clone();
    let png_profile = base.encoder == "rawpng";

    let (scale_pct, fps_pct, bitrate_pct): (u32, u32, u32) = match level {
        0 => (100, 100, 100),
        1 => {
            if png_profile {
                (95, 80, 90)
            } else {
                (90, 90, 85)
            }
        }
        2 => {
            if png_profile {
                (85, 60, 75)
            } else {
                (80, 80, 70)
            }
        }
        _ => {
            if png_profile {
                (75, 45, 60)
            } else {
                (70, 70, 55)
            }
        }
    };

    if let Some((w, h)) = parse_size(&cfg.size) {
        let mut scaled_w = (w.saturating_mul(scale_pct) / 100).clamp(640, 3840);
        let mut scaled_h = (h.saturating_mul(scale_pct) / 100).clamp(360, 2160);
        if scaled_w % 2 == 1 {
            scaled_w = scaled_w.saturating_sub(1);
        }
        if scaled_h % 2 == 1 {
            scaled_h = scaled_h.saturating_sub(1);
        }
        cfg.size = format!("{scaled_w}x{scaled_h}");
    }

    cfg.fps = if png_profile {
        (base.fps.saturating_mul(fps_pct as u32) / 100).clamp(5, 20)
    } else {
        (base.fps.saturating_mul(fps_pct as u32) / 100).clamp(30, 120)
    };
    let bitrate_max = if cfg.encoder == "h265" { 100_000 } else { 120_000 };
    cfg.bitrate_kbps = if png_profile {
        (base.bitrate_kbps.saturating_mul(bitrate_pct) / 100).clamp(4_000, 80_000)
    } else {
        (base.bitrate_kbps.saturating_mul(bitrate_pct) / 100).clamp(4_000, bitrate_max)
    };
    cfg
}

/// Parse `"WxH"` into `(width, height)`.
pub fn parse_size(size: &str) -> Option<(u32, u32)> {
    let (w, h) = size.split_once('x')?;
    Some((w.parse().ok()?, h.parse().ok()?))
}

// ── Unit tests ────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use wbeamd_api::ClientMetricsRequest;

    fn healthy() -> ClientMetricsRequest {
        ClientMetricsRequest {
            decode_time_ms_p95: 4.0,
            render_time_ms_p95: 2.0,
            present_fps: 60.0,
            recv_fps: 60.0,
            transport_queue_depth: 0,
            decode_queue_depth: 0,
            render_queue_depth: 0,
            too_late_frames: 0,
            ..Default::default()
        }
    }

    #[test]
    fn high_pressure_on_bad_decode() {
        let mut c = healthy();
        c.decode_time_ms_p95 = 13.5;
        assert!(is_high_pressure(60, &c));
    }

    #[test]
    fn high_pressure_on_fps_drop() {
        let mut c = healthy();
        c.present_fps = 50.0; // < 60 * 0.90
        assert!(is_high_pressure(60, &c));
    }

    #[test]
    fn not_high_pressure_when_healthy() {
        assert!(!is_high_pressure(60, &healthy()));
    }

    #[test]
    fn low_pressure_when_all_ok() {
        assert!(is_low_pressure(60, &healthy()));
    }

    #[test]
    fn not_low_pressure_when_decode_queue_busy() {
        let mut c = healthy();
        c.decode_queue_depth = 1;
        assert!(!is_low_pressure(60, &c));
    }

    #[test]
    fn config_level0_unchanged() {
        let base = ActiveConfig::balanced_default();
        let cfg = config_for_level(&base, 0);
        assert_eq!(cfg.fps, base.fps);
        assert_eq!(cfg.bitrate_kbps, base.bitrate_kbps);
        assert_eq!(cfg.size, base.size);
    }

    #[test]
    fn config_level1_reduces_fps() {
        let base = ActiveConfig::balanced_default();
        let cfg = config_for_level(&base, 1);
        assert!(cfg.fps < base.fps || base.fps * 90 / 100 == cfg.fps);
    }

    #[test]
    fn parse_size_roundtrip() {
        assert_eq!(parse_size("1920x1080"), Some((1920, 1080)));
        assert_eq!(parse_size("invalid"), None);
    }

    #[test]
    fn high_pressure_detects_120fps_budget_overrun() {
        let mut c = healthy();
        c.present_fps = 120.0;
        c.recv_fps = 120.0;
        c.decode_time_ms_p95 = 5.0;
        assert!(is_high_pressure(120, &c));
    }

    #[test]
    fn low_pressure_when_healthy_at_120fps() {
        let mut c = healthy();
        c.present_fps = 120.0;
        c.recv_fps = 120.0;
        c.decode_time_ms_p95 = 2.0;
        c.render_time_ms_p95 = 1.0;
        assert!(is_low_pressure(120, &c));
    }

    #[test]
    fn config_level_caps_h265_bitrate_to_safe_limit() {
        let mut base = ActiveConfig::balanced_default();
        base.encoder = "h265".to_string();
        base.bitrate_kbps = 150_000;
        let cfg = config_for_level(&base, 0);
        assert_eq!(cfg.bitrate_kbps, 100_000);
    }
}
