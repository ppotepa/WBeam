use anyhow::{Context, Result};
use gstreamer as gst;
use gstreamer::prelude::*;

pub fn build_source() -> Result<gst::Element> {
    let src = gst::ElementFactory::make("ximagesrc")
        .name("src")
        .build()
        .context("ximagesrc missing")?;
    let _ = src.set_property("use-damage", false);
    let _ = src.set_property("show-pointer", true);
    Ok(src)
}
