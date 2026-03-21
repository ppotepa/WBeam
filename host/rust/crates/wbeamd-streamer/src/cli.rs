//! CLI argument parser and default configuration resolution for wbeamd-streamer.

use anyhow::{Context, Result};
use ashpd::desktop::screencast::CursorMode;
use clap::{Parser, ValueEnum};

#[derive(Debug, Clone, Copy, PartialEq, Eq, ValueEnum)]
pub enum StreamMode {
    Ultra,
    Stable,
    Quality,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, ValueEnum)]
pub enum CaptureBackend {
    Auto,
    WaylandPortal,
    X11,
    Evdi,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, ValueEnum)]
pub enum WaylandSourceType {
    Monitor,
    Virtual,
}

#[derive(Debug, Parser, Clone)]
#[command(
    name = "wbeamd-streamer",
    about = "Host screencast (Wayland portal/X11) -> WBTP framed sender"
)]
pub struct Args {
    #[arg(long, default_value_t = 5000)]
    pub port: u16,
    #[arg(long)]
    pub size: Option<String>,
    #[arg(long)]
    pub fps: Option<u32>,
    #[arg(long, default_value_t = 0)]
    pub debug_fps: u32,
    #[arg(long)]
    pub bitrate_kbps: Option<u32>,
    #[arg(long, default_value = "h265",
            value_parser = ["h264", "h265", "rawpng"])]
    pub encoder: String,
    #[arg(long, default_value = "hidden", value_parser = ["hidden", "embedded", "metadata"])]
    pub cursor_mode: String,
    #[arg(long, default_value = "/tmp/wbeam-frames")]
    pub debug_dir: String,
    #[arg(long, default_value_t = true)]
    pub framed: bool,
    #[arg(long, value_enum)]
    pub stream_mode: Option<StreamMode>,
    #[arg(long, default_value_t = false)]
    pub skip_videoscale: bool,
    #[arg(long, value_enum, default_value_t = CaptureBackend::Auto)]
    pub capture_backend: CaptureBackend,
    /// Use built-in synthetic benchmark scene instead of desktop capture.
    #[arg(long, default_value_t = false)]
    pub benchmark_game: bool,
    /// All-Intra mode: every frame is a full keyframe (gop-size=1).
    /// Mathematically eliminates P-frame reference artifacts at the cost of
    /// ~3-4x higher bitrate vs. P-frame HEVC — well within 300 Mbps USB.
    #[arg(long, default_value_t = false)]
    pub intra_only: bool,
    #[arg(long)]
    pub restore_token_file: Option<String>,
    #[arg(long, default_value_t = 2)]
    pub portal_persist_mode: u32,
    #[arg(long, value_enum, default_value_t = WaylandSourceType::Monitor)]
    pub wayland_source_type: WaylandSourceType,
}

/// Fully-resolved configuration after applying default settings.
#[derive(Debug, Clone)]
pub struct ResolvedConfig {
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    pub bitrate_kbps: u32,
    pub encoder: String,
    pub nv_preset: String,
    pub cursor_mode: CursorMode,
    pub stream_mode: StreamMode,
    pub skip_videoscale: bool,
    pub capture_backend: CaptureBackend,
    pub benchmark_game: bool,
    /// When true, gop-size is forced to 1 — every frame is an IDR.
    pub intra_only: bool,
    pub queue_max_buffers: u32,
    pub queue_max_time_ms: u32,
    pub appsink_max_buffers: u32,
    pub pull_timeout_ms: u32,
    pub write_timeout_ms: u32,
    pub disconnect_on_timeout: bool,
    pub videorate_drop_only: bool,
    pub pipewire_keepalive_ms: i32,
    pub h264_gop: u32,
    pub restore_token_file: Option<String>,
    pub portal_persist_mode: u32,
    pub wayland_source_type: WaylandSourceType,
}

#[derive(Clone, Copy)]
struct ProfileDefaults {
    size: &'static str,
    fps: u32,
    bitrate_kbps: u32,
    nv_preset: &'static str,
    stream_mode: StreamMode,
    queue_max_buffers: u32,
    queue_max_time_ms: u32,
    appsink_max_buffers: u32,
    pull_timeout_ms: u32,
    write_timeout_ms: u32,
    disconnect_on_timeout: bool,
    videorate_drop_only: bool,
    pipewire_keepalive_ms: i32,
    h264_gop: u32,
}

fn defaults() -> ProfileDefaults {
    ProfileDefaults {
        size: "1280x800",
        fps: 60,
        bitrate_kbps: 10_000,
        nv_preset: "p2",
        stream_mode: StreamMode::Ultra,
        queue_max_buffers: 1,
        queue_max_time_ms: 8,
        appsink_max_buffers: 1,
        pull_timeout_ms: 20,
        write_timeout_ms: 40,
        disconnect_on_timeout: false,
        videorate_drop_only: false,
        pipewire_keepalive_ms: 12,
        h264_gop: 30,
    }
}

/// Apply default settings and parse size/fps/bitrate overrides.
pub fn resolve_profile(args: &Args) -> Result<ResolvedConfig> {
    let defaults = defaults();

    let size = args.size.as_deref().unwrap_or(defaults.size);
    let fps = args.fps.unwrap_or(defaults.fps).clamp(24, 120);
    let default_scaled_bitrate = ((defaults.bitrate_kbps as u64)
        .saturating_mul(fps as u64)
        .saturating_add(defaults.fps as u64 - 1)
        / defaults.fps as u64)
        .clamp(4_000, 300_000) as u32;
    let bitrate_kbps = args
        .bitrate_kbps
        .unwrap_or(default_scaled_bitrate)
        .clamp(4_000, 300_000);
    let (w, h) = size
        .to_lowercase()
        .split_once('x')
        .and_then(|(w, h)| Some((w.parse::<u32>().ok()?, h.parse::<u32>().ok()?)))
        .context("--size must be WIDTHxHEIGHT")?;

    let cursor_mode = match args.cursor_mode.as_str() {
        "hidden" => CursorMode::Hidden,
        "embedded" => CursorMode::Embedded,
        _ => CursorMode::Metadata,
    };

    Ok(ResolvedConfig {
        width: w,
        height: h,
        fps,
        bitrate_kbps,
        encoder: args.encoder.clone(),
        nv_preset: defaults.nv_preset.to_string(),
        cursor_mode,
        stream_mode: args.stream_mode.unwrap_or(defaults.stream_mode),
        skip_videoscale: args.skip_videoscale,
        capture_backend: match args.capture_backend {
            CaptureBackend::Auto => {
                if std::env::var("XDG_SESSION_TYPE")
                    .ok()
                    .map(|v| v.eq_ignore_ascii_case("x11"))
                    .unwrap_or(false)
                    || std::env::var_os("DISPLAY").is_some()
                {
                    CaptureBackend::X11
                } else {
                    CaptureBackend::WaylandPortal
                }
            }
            v => v,
        },
        benchmark_game: args.benchmark_game,
        intra_only: args.intra_only,
        queue_max_buffers: defaults.queue_max_buffers,
        queue_max_time_ms: defaults.queue_max_time_ms,
        appsink_max_buffers: defaults.appsink_max_buffers,
        pull_timeout_ms: defaults.pull_timeout_ms,
        write_timeout_ms: defaults.write_timeout_ms,
        disconnect_on_timeout: defaults.disconnect_on_timeout,
        videorate_drop_only: defaults.videorate_drop_only,
        pipewire_keepalive_ms: defaults.pipewire_keepalive_ms,
        h264_gop: defaults.h264_gop,
        restore_token_file: args
            .restore_token_file
            .as_ref()
            .map(|v| v.trim().to_string())
            .filter(|v| !v.is_empty()),
        portal_persist_mode: args.portal_persist_mode.min(2),
        wayland_source_type: args.wayland_source_type,
    })
}
