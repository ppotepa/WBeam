use anyhow::Result;
use gstreamer as gst;

pub(super) fn pick_encoder(requested: &str) -> Result<&'static str> {
    let present = |name| gst::ElementFactory::find(name).is_some();

    let pick_h264 = || {
        if present("nvh264enc") {
            return Some("nvenc264");
        }
        if present("x264enc") {
            return Some("x264");
        }
        if present("openh264enc") {
            return Some("openh264");
        }
        None
    };

    let pick_h265 = || {
        if present("nvh265enc") {
            return Some("nvenc265");
        }
        if present("x265enc") {
            return Some("x265");
        }
        None
    };

    match requested {
        "rawpng" => {
            anyhow::ensure!(present("pngenc"), "pngenc not available");
            Ok("rawpng")
        }
        "h264" => {
            if let Some(encoder) = pick_h264() {
                return Ok(encoder);
            }
            anyhow::bail!("No supported H264 encoder found (nvh264enc/x264enc/openh264enc)");
        }
        "openh264" => {
            if present("openh264enc") {
                return Ok("openh264");
            }
            if let Some(encoder) = pick_h264() {
                return Ok(encoder);
            }
            anyhow::bail!("No supported H264 encoder found (openh264enc/nvh264enc/x264enc)");
        }
        "h265" | "auto" | "nvenc" | "nvenc265" | "x265" => {
            if let Some(encoder) = pick_h265() {
                return Ok(encoder);
            }
            if let Some(encoder) = pick_h264() {
                return Ok(encoder);
            }
            anyhow::bail!("No supported H265 or H264 encoder found (nvh265enc/x265enc/nvh264enc/x264enc/openh264enc)");
        }
        _ => anyhow::bail!("Unsupported encoder mode: {requested}"),
    }
}
