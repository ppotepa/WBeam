//! Wayland XDG portal screen-capture session.
//!
//! Requests a `ScreenCast` portal session, lets the user pick a monitor,
//! and returns the PipeWire file descriptor + node ID needed to build the
//! GStreamer source element.

use std::os::unix::io::{FromRawFd, OwnedFd};
use std::path::Path;

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
    let persist_mode = match cfg.portal_persist_mode {
        1 => PersistMode::Application,
        2 => PersistMode::ExplicitlyRevoked,
        _ => PersistMode::DoNot,
    };
    let restore_token = cfg
        .restore_token_file
        .as_deref()
        .and_then(read_restore_token_file);

    let select_with_token = async {
        proxy
            .select_sources(
                &session,
                cfg.cursor_mode,
                SourceType::Monitor.into(),
                false, // multiple=false: single monitor, no extra allocations
                restore_token.as_deref(),
                persist_mode,
            )
            .await
    };

    if let Err(err) = select_with_token.await {
        if restore_token.is_some() {
            eprintln!("[wbeam] restore-token select failed, retrying without token: {err}");
            proxy
                .select_sources(
                    &session,
                    cfg.cursor_mode,
                    SourceType::Monitor.into(),
                    false,
                    None,
                    persist_mode,
                )
                .await?;
        } else {
            return Err(err.into());
        }
    }

    let start = proxy.start(&session, &WindowIdentifier::default()).await?;
    let results = start.response()?;
    if let Some(token) = results.restore_token() {
        if let Some(path) = cfg.restore_token_file.as_deref() {
            if let Err(err) = write_restore_token_file(path, token) {
                eprintln!("[wbeam] failed to save restore token to {path}: {err}");
            }
        }
    }
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

fn read_restore_token_file(path: &str) -> Option<String> {
    let value = std::fs::read_to_string(path).ok()?;
    let token = value.trim();
    if token.is_empty() {
        None
    } else {
        Some(token.to_string())
    }
}

fn write_restore_token_file(path: &str, token: &str) -> Result<()> {
    let parent = Path::new(path).parent().map(|p| p.to_path_buf());
    if let Some(dir) = parent {
        std::fs::create_dir_all(&dir)?;
    }
    std::fs::write(path, token.as_bytes())?;
    Ok(())
}
