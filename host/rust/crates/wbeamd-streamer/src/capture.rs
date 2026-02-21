//! Wayland XDG portal screen-capture session.
//!
//! Requests a `ScreenCast` portal session, lets the user pick a monitor,
//! and returns the PipeWire file descriptor + node ID needed to build the
//! GStreamer source element.

use std::os::unix::io::{FromRawFd, OwnedFd};

use anyhow::{Context, Result};
use ashpd::desktop::screencast::{PersistMode, Screencast, SourceType};
use ashpd::WindowIdentifier;

use crate::cli::ResolvedConfig;

/// Holds the PipeWire transport parameters returned by the portal.
///
/// `fd` is an [`OwnedFd`] so the kernel file descriptor is closed
/// automatically on drop — prevents fd leaks during error paths or restarts.
#[derive(Debug)]
pub struct PortalStream {
    /// PipeWire remote file descriptor (RAII-owned).
    pub fd: OwnedFd,
    /// PipeWire node ID for the selected source.
    pub node_id: u32,
}

/// Open a screencasting portal session and return the PipeWire stream handle.
pub async fn request_portal_stream(cfg: &ResolvedConfig) -> Result<PortalStream> {
    let proxy = Screencast::new().await?;
    let session = proxy.create_session().await?;

    proxy
        .select_sources(
            &session,
            cfg.cursor_mode,
            SourceType::Monitor.into(),
            false,          // multiple=false: single monitor, no extra allocations
            None,
            PersistMode::DoNot, // explicit: skip portal persistence metadata write
        )
        .await?;

    let start = proxy
        .start(&session, &WindowIdentifier::default())
        .await?;

    let results = start.response()?;
    // Extract node_id directly from the reference — no .cloned() heap copy.
    let node_id = results
        .streams()
        .first()
        .context("Portal returned no streams")?
        .pipe_wire_node_id();

    // The portal hands us ownership of the fd; wrap in OwnedFd for RAII cleanup.
    // SAFETY: the returned i32 is a freshly-opened, uniquely-owned file descriptor.
    let raw = proxy.open_pipe_wire_remote(&session).await?;
    let fd = unsafe { OwnedFd::from_raw_fd(raw) };

    Ok(PortalStream { fd, node_id })
}
