//! Encoder component registry and dispatch.
//!
//! This module exposes stable helper functions used by pipeline construction
//! while delegating concrete backend logic to per-codec components.

use anyhow::Result;
use gstreamer as gst;

mod h264;
mod h265;
mod rawpng;
mod selector;

/// Returns `true` when the requested encoder mode is HEVC/H.265.
pub fn is_hevc(encoder: &str) -> bool {
    encoder == "h265"
}

/// Returns `true` when the requested encoder mode is PNG frames.
pub fn is_png(encoder: &str) -> bool {
    encoder == "rawpng"
}

/// Resolve concrete GStreamer encoder backend from requested mode.
///
/// `h264`   -> `nvenc264` if available, else `x264`
/// `h265`   -> `nvenc265` if available, else `x265`
/// `rawpng` -> `rawpng`
///
/// Legacy aliases: `auto`, `nvenc`, `nvenc265`, `x265`, `openh264` map to `h265`.
pub fn pick_encoder(requested: &str) -> Result<&'static str> {
    selector::pick_encoder(requested)
}

/// Compute the effective GOP length used by all encoder backends.
pub fn effective_gop(fps: u32, intra_only: bool, h264_gop: u32) -> u32 {
    if intra_only {
        1
    } else if h264_gop > 0 {
        h264_gop
    } else {
        (fps / 8).max(6)
    }
}

/// Apply bitrate/GOP/tuning parameters to the chosen encoder backend.
pub fn configure_encoder(
    enc: &gst::Element,
    encoder: &str,
    bitrate_kbps: u32,
    fps: u32,
    nv_preset: &str,
    intra_only: bool,
    h264_gop: u32,
) {
    let gop = effective_gop(fps, intra_only, h264_gop);

    if intra_only {
        println!("[wbeam] ALL-INTRA mode: gop=1, every frame is a full IDR");
    }

    match encoder {
        "nvenc264" | "x264" => {
            h264::configure(enc, encoder, bitrate_kbps, nv_preset, intra_only, gop)
        }
        "nvenc265" | "x265" => {
            h265::configure(enc, encoder, bitrate_kbps, nv_preset, intra_only, gop)
        }
        "rawpng" => rawpng::configure(enc),
        _ => {}
    }
}

#[cfg(test)]
mod tests {
    use super::effective_gop;

    #[test]
    fn effective_gop_prefers_intra_only() {
        assert_eq!(effective_gop(60, true, 120), 1);
    }

    #[test]
    fn effective_gop_uses_explicit_override() {
        assert_eq!(effective_gop(60, false, 48), 48);
    }

    #[test]
    fn effective_gop_falls_back_to_fps_based_default() {
        assert_eq!(effective_gop(60, false, 0), 7);
        assert_eq!(effective_gop(30, false, 0), 6);
    }
}
