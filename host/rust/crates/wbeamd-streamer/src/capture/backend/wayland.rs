use std::os::unix::io::AsRawFd;

use anyhow::{Context, Result};
use gstreamer as gst;
use gstreamer::prelude::*;

use crate::capture::{request_portal_stream, PortalStream};
use crate::cli::ResolvedConfig;

pub async fn prepare(cfg: &ResolvedConfig) -> Result<PortalStream> {
    request_portal_stream(cfg).await
}

pub fn build_source(stream: &PortalStream, cfg: &ResolvedConfig) -> Result<gst::Element> {
    let src = gst::ElementFactory::make("pipewiresrc")
        .name("src")
        .build()
        .context("pipewiresrc missing")?;
    src.set_property("fd", stream.fd.as_raw_fd());
    src.set_property("path", stream.node_id.to_string());
    src.set_property("do-timestamp", true);
    src.set_property("keepalive-time", cfg.pipewire_keepalive_ms);
    Ok(src)
}
