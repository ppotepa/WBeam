//! Encoder selection and configuration.
//!
//! Supported output modes:
//! - `h264`   -> auto-select `nvh264enc` (NVENC) or fallback `x264enc`
//! - `h265`   -> auto-select `nvh265enc` (NVENC) or fallback `x265enc`
//! - `rawpng` -> PNG-in-WBTP frame stream (`pngenc`)

use anyhow::Result;
use gstreamer as gst;
use gstreamer::prelude::*;

/// Returns `true` when the requested encoder mode is AVC/H.264.
pub fn is_h264(encoder: &str) -> bool {
    encoder == "h264"
}

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
    let present = |name| gst::ElementFactory::find(name).is_some();

    match requested {
        "rawpng" => {
            anyhow::ensure!(present("pngenc"), "pngenc not available");
            Ok("rawpng")
        }
        "h264" => {
            if present("nvh264enc") {
                return Ok("nvenc264");
            }
            if present("x264enc") {
                return Ok("x264");
            }
            anyhow::bail!("No supported H264 encoder found (nvh264enc/x264enc)");
        }
        "h265" | "auto" | "nvenc" | "nvenc265" | "x265" | "openh264" => {
            if present("nvh265enc") {
                return Ok("nvenc265");
            }
            if present("x265enc") {
                return Ok("x265");
            }
            anyhow::bail!("No supported H265 encoder found (nvh265enc/x265enc)");
        }
        _ => anyhow::bail!("Unsupported encoder mode: {requested}"),
    }
}

/// Apply low-latency bitrate/GOP/tuning parameters to the chosen encoder backend.
pub fn configure_encoder(
    enc: &gst::Element,
    encoder: &str,
    bitrate_kbps: u32,
    fps: u32,
    nv_preset: &str,
    intra_only: bool,
    h264_gop: u32,
) {
    const X265_MAX_BITRATE_KBPS: u32 = 100_000;
    let gop: u32 = if intra_only {
        1
    } else if h264_gop > 0 {
        h264_gop
    } else {
        (fps / 8).max(6)
    };

    if intra_only {
        println!("[wbeam] ALL-INTRA mode: gop=1, every frame is a full IDR");
    }

    if encoder == "nvenc264" {
        let _ = enc.set_property("bitrate", bitrate_kbps);
        let _ = enc.set_property("max-bitrate", bitrate_kbps * 3);
        let _ = enc.set_property_from_str("rc-mode", "vbr");
        let _ = enc.set_property_from_str("preset", nv_preset);
        let _ = enc.set_property("gop-size", gop as i32);
        let _ = enc.set_property("bframes", 0u32);
        let _ = enc.set_property("zerolatency", true);
        let _ = enc.set_property("aud", true);
        let _ = enc.set_property("repeat-sequence-header", true);
        return;
    }

    if encoder == "nvenc265" {
        // VBR mode: encoder targets `bitrate_kbps` on average but can burst
        // up to 3x on IDR / scene-change frames.  At 100 Mbps avg / 300 Mbps
        // peak the IDR frames are essentially lossless — no quantization
        // banding or residual-block shimmer on new windows.
        let _ = enc.set_property("bitrate", bitrate_kbps);
        let _ = enc.set_property("max-bitrate", bitrate_kbps * 3);
        let _ = enc.set_property_from_str("rc-mode", "vbr");
        let _ = enc.set_property_from_str("preset", nv_preset);
        let _ = enc.set_property("gop-size", gop as i32);
        let _ = enc.set_property("bframes", 0u32);
        let _ = enc.set_property("zerolatency", true);
        let _ = enc.set_property("aud", true);
        let _ = enc.set_property("repeat-sequence-header", true);
        return;
    }

    if encoder == "x264" {
        let _ = enc.set_property("bitrate", bitrate_kbps);
        let _ = enc.set_property_from_str("speed-preset", "ultrafast");
        let _ = enc.set_property_from_str("tune", "zerolatency");
        let _ = enc.set_property("byte-stream", true);
        let _ = enc.set_property("key-int-max", gop as i32);
        let option_str = if intra_only {
            "bframes=0:cabac=0:ref=1:8x8dct=0:no-open-gop=1:scenecut=0"
        } else {
            "bframes=0:cabac=0:ref=1:8x8dct=0:no-open-gop=1:scenecut=40"
        };
        let _ = enc.set_property("option-string", option_str);
        return;
    }

    if encoder == "x265" {
        let safe_bitrate = bitrate_kbps.min(X265_MAX_BITRATE_KBPS);
        if safe_bitrate != bitrate_kbps {
            println!(
                "[wbeam] clamping x265 bitrate from {} to {} kbps (backend safety)",
                bitrate_kbps, safe_bitrate
            );
        }
        let _ = enc.set_property("bitrate", safe_bitrate);
        let _ = enc.set_property_from_str("speed-preset", "ultrafast");
        let _ = enc.set_property_from_str("tune", "zerolatency");
        let _ = enc.set_property("key-int-max", gop as i32);
        let option_str = if intra_only {
            "bframes=0:no-open-gop=1:scenecut=0:strong-intra-smoothing=0"
        } else {
            "bframes=0:no-open-gop=1:strong-intra-smoothing=0:scenecut=40"
        };
        let _ = enc.set_property("option-string", option_str);
        return;
    }
}
