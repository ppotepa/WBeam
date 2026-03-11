use anyhow::Result;
use gstreamer as gst;

pub(super) fn pick_encoder(requested: &str) -> Result<&'static str> {
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

