//! CLI argument parser and profile resolution for wbeamd-streamer.

use anyhow::{Context, Result};
use ashpd::desktop::screencast::CursorMode;
use clap::{Parser, ValueEnum};

#[derive(Debug, Clone, Copy, PartialEq, Eq, ValueEnum)]
pub enum StreamMode {
    Ultra,
    Stable,
    Quality,
}

#[derive(Debug, Parser, Clone)]
#[command(name = "wbeamd-streamer", about = "Wayland portal screencast -> WBTP framed sender")]
pub struct Args {
    #[arg(long, default_value = "balanced", value_parser = ["lowlatency", "balanced", "ultra"])]
    pub profile: String,
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
            value_parser = ["h265", "rawpng"])]
    pub encoder: String,
    #[arg(long, default_value = "embedded", value_parser = ["hidden", "embedded", "metadata"])]
    pub cursor_mode: String,
    #[arg(long, default_value = "/tmp/wbeam-frames")]
    pub debug_dir: String,
    #[arg(long, default_value_t = true)]
    pub framed: bool,
    #[arg(long, value_enum, default_value_t = StreamMode::Stable)]
    pub stream_mode: StreamMode,
    #[arg(long, default_value_t = false)]
    pub skip_videoscale: bool,
    /// All-Intra mode: every frame is a full keyframe (gop-size=1).
    /// Mathematically eliminates P-frame reference artifacts at the cost of
    /// ~3-4x higher bitrate vs. P-frame HEVC — well within 300 Mbps USB.
    #[arg(long, default_value_t = false)]
    pub intra_only: bool,
}

/// Fully-resolved configuration after applying profile defaults.
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
    /// When true, gop-size is forced to 1 — every frame is an IDR.
    pub intra_only: bool,
}

/// Apply profile defaults and parse size/fps/bitrate overrides.
pub fn resolve_profile(args: &Args) -> Result<ResolvedConfig> {
    let (default_size, default_fps, default_bitrate, nv_preset) = match args.profile.as_str() {
        // With 300 Mbps USB bandwidth available, raise defaults so bitrate is
        // never the constraint.  HEVC at 100 Mbps / 1080p is near-lossless;
        // no quantisation banding even on cursor-heavy or fast-moving content.
        "lowlatency" => ("1280x720",  60,  60_000u32, "p4"),
        "balanced"   => ("1920x1080", 60, 100_000u32, "p6"),
        _            => ("2560x1440", 60, 150_000u32, "p7"),
    };

    let size = args.size.as_deref().unwrap_or(default_size);
    let fps = args.fps.unwrap_or(default_fps).clamp(24, 120);
    let default_scaled_bitrate = ((default_bitrate as u64)
        .saturating_mul(fps as u64)
        .saturating_add(default_fps as u64 - 1)
        / default_fps as u64)
        .clamp(4_000, 300_000) as u32;
    let bitrate_kbps = args.bitrate_kbps.unwrap_or(default_scaled_bitrate).clamp(4_000, 300_000);
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
        nv_preset: nv_preset.to_string(),
        cursor_mode,
        stream_mode: args.stream_mode,
        skip_videoscale: args.skip_videoscale,
        intra_only: args.intra_only,
    })
}
