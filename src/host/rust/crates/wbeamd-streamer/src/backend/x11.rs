use anyhow::{Context, Result};
use gstreamer as gst;
use gstreamer::prelude::*;

fn parse_env_i32(key: &str) -> Option<i32> {
    std::env::var(key).ok()?.trim().parse::<i32>().ok()
}

pub fn build_source() -> Result<gst::Element> {
    let src = gst::ElementFactory::make("ximagesrc")
        .name("src")
        .build()
        .context("ximagesrc missing")?;
    let _ = src.set_property("use-damage", false);
    let _ = src.set_property("show-pointer", true);
    let x = parse_env_i32("WBEAM_X11_CAPTURE_X");
    let y = parse_env_i32("WBEAM_X11_CAPTURE_Y");
    let w = parse_env_i32("WBEAM_X11_CAPTURE_W");
    let h = parse_env_i32("WBEAM_X11_CAPTURE_H");
    if let (Some(x), Some(y), Some(w), Some(h)) = (x, y, w, h) {
        let endx = x.saturating_add(w.saturating_sub(1)).max(x);
        let endy = y.saturating_add(h.saturating_sub(1)).max(y);
        let _ = src.set_property("startx", x);
        let _ = src.set_property("starty", y);
        let _ = src.set_property("endx", endx);
        let _ = src.set_property("endy", endy);
        println!("[wbeam] X11 capture region: x={} y={} w={} h={}", x, y, w, h);
    }
    Ok(src)
}
