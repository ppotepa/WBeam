use anyhow::Result;

use crate::capture::PreparedCapture;
use crate::cli::ResolvedConfig;
use crate::encode::{effective_gop, is_hevc, is_png, pick_encoder};

use super::super::profile::{buffer_profile, BufferProfile};

pub(super) struct PipelineRuntime {
    pub(super) mode_png: bool,
    pub(super) encoder_name: &'static str,
    pub(super) hevc: bool,
    pub(super) profile: BufferProfile,
    pub(super) capture_backend_name: &'static str,
    pub(super) debug_enabled: bool,
    pub(super) raw_format: &'static str,
    pub(super) effective_drop_only: bool,
    pub(super) effective_gop: u32,
    pub(super) parse_mode: &'static str,
}

impl PipelineRuntime {
    pub(super) fn new(
        capture: &PreparedCapture,
        cfg: &ResolvedConfig,
        debug_fps: u32,
        framed: bool,
    ) -> Result<Self> {
        let mode_png = is_png(&cfg.encoder);
        let encoder_name = pick_encoder(&cfg.encoder)?;
        let hevc = is_hevc(&cfg.encoder);
        Ok(Self {
            mode_png,
            encoder_name,
            hevc,
            profile: pipeline_profile(capture, cfg, mode_png),
            capture_backend_name: capture_backend_name(capture),
            debug_enabled: debug_fps > 0,
            raw_format: raw_format(mode_png, encoder_name),
            effective_drop_only: effective_drop_only(capture, cfg),
            effective_gop: effective_gop(cfg.fps, cfg.intra_only, cfg.h264_gop),
            parse_mode: parse_mode(mode_png, hevc, framed),
        })
    }

    pub(super) fn encoder_factory(&self) -> &'static str {
        match self.encoder_name {
            "nvenc264" => "nvh264enc",
            "x264" => "x264enc",
            "nvenc265" => "nvh265enc",
            "x265" => "x265enc",
            "rawpng" => "pngenc",
            _ => "x265enc",
        }
    }

    pub(super) fn parse_factory(&self) -> Option<&'static str> {
        (!self.mode_png).then_some(if self.hevc { "h265parse" } else { "h264parse" })
    }

    pub(super) fn sink_media_type(&self) -> &'static str {
        if self.hevc {
            "video/x-h265"
        } else {
            "video/x-h264"
        }
    }
}

fn pipeline_profile(
    capture: &PreparedCapture,
    cfg: &ResolvedConfig,
    mode_png: bool,
) -> BufferProfile {
    let mut profile = buffer_profile(cfg.stream_mode, cfg.fps, mode_png);
    profile.queue_buffers = cfg.queue_max_buffers.max(1);
    profile.appsink_buffers = cfg.appsink_max_buffers.max(1);
    profile.queue_time_ns = (cfg.queue_max_time_ms.max(1) as u64) * 1_000_000u64;
    if matches!(capture, PreparedCapture::Wayland(_)) {
        profile.queue_leaky = "upstream";
        if profile.queue_buffers < 4 {
            profile.queue_buffers = 4;
        }
        profile.queue_time_ns = 0;
    }
    profile
}

fn capture_backend_name(capture: &PreparedCapture) -> &'static str {
    match capture {
        PreparedCapture::Wayland(_) => "wayland_portal",
        PreparedCapture::X11 => "x11",
        #[cfg(feature = "evdi")]
        PreparedCapture::Evdi => "evdi",
        PreparedCapture::BenchmarkGame => "benchmark_game",
    }
}

fn raw_format(mode_png: bool, encoder_name: &str) -> &'static str {
    if mode_png {
        "RGBA"
    } else if encoder_name == "nvenc264" || encoder_name == "nvenc265" {
        "NV12"
    } else {
        "I420"
    }
}

fn effective_drop_only(capture: &PreparedCapture, cfg: &ResolvedConfig) -> bool {
    #[cfg(feature = "evdi")]
    let is_evdi = matches!(capture, PreparedCapture::Evdi);
    #[cfg(not(feature = "evdi"))]
    let is_evdi = false;
    matches!(capture, PreparedCapture::Wayland(_)) || is_evdi || cfg.videorate_drop_only
}

fn parse_mode(mode_png: bool, hevc: bool, framed: bool) -> &'static str {
    if mode_png {
        "png_raw"
    } else if hevc {
        if framed {
            "h265_au"
        } else {
            "h265_nal"
        }
    } else if framed {
        "h264_au"
    } else {
        "h264_nal"
    }
}
