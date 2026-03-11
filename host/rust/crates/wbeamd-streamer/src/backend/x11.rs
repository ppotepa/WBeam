use anyhow::{Context, Result};
use gstreamer as gst;
use gstreamer::prelude::*;

fn parse_env_i64(key: &str) -> Option<i64> {
    std::env::var(key).ok()?.trim().parse::<i64>().ok()
}

fn to_guint(v: i64, fallback: u32) -> u32 {
    if v < 0 {
        return fallback;
    }
    u32::try_from(v).unwrap_or(u32::MAX)
}

pub fn build_source() -> Result<gst::Element> {
    let src = gst::ElementFactory::make("ximagesrc")
        .name("src")
        .build()
        .context("ximagesrc missing")?;
    let _ = src.set_property("use-damage", false);
    let _ = src.set_property("show-pointer", true);
    let x = parse_env_i64("WBEAM_X11_CAPTURE_X");
    let y = parse_env_i64("WBEAM_X11_CAPTURE_Y");
    let w = parse_env_i64("WBEAM_X11_CAPTURE_W");
    let h = parse_env_i64("WBEAM_X11_CAPTURE_H");
    if let (Some(x), Some(y), Some(w), Some(h)) = (x, y, w, h) {
        // GstXImageSrc uses guint for start/end coordinates.
        let sx = to_guint(x, 0);
        let sy = to_guint(y, 0);
        let sw = to_guint(w, 1).max(1);
        let sh = to_guint(h, 1).max(1);
        let endx = sx.saturating_add(sw.saturating_sub(1));
        let endy = sy.saturating_add(sh.saturating_sub(1));
        let _ = src.set_property("startx", sx);
        let _ = src.set_property("starty", sy);
        let _ = src.set_property("endx", endx);
        let _ = src.set_property("endy", endy);
        println!(
            "[wbeam] X11 capture region: x={} y={} w={} h={} (normalized: x={} y={} w={} h={})",
            x, y, w, h, sx, sy, sw, sh
        );
    }
    Ok(src)
}
