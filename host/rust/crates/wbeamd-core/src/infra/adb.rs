//! ADB reverse-port management.
//!
//! Keeps the Android device able to reach the host's stream and control
//! ports over USB via `usb_reverse.sh` and `adb reverse`.

use std::net::TcpListener;
use std::path::Path;
use std::process::Stdio;

use tokio::process::Command;
use tracing::warn;

/// Attempt to open the stream port.  If it is busy, try to kill the occupant
/// with `fuser -k`, then retry once.
pub fn ensure_stream_port_available(stream_port: u16) -> Result<(), String> {
    if TcpListener::bind(("0.0.0.0", stream_port)).is_ok() {
        return Ok(());
    }

    warn!(port = stream_port, "stream port busy, trying self-heal");
    let _ = std::process::Command::new("fuser")
        .arg("-k")
        .arg(format!("{stream_port}/tcp"))
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status();

    std::thread::sleep(std::time::Duration::from_millis(200));

    if TcpListener::bind(("0.0.0.0", stream_port)).is_ok() {
        return Ok(());
    }

    Err(format!("stream port {stream_port} is busy"))
}

/// Run `usb_reverse.sh` and `adb reverse` to expose host ports on the device.
pub async fn ensure_usb_reverse(root: &Path, stream_port: u16, control_port: u16, reason: &str) {
    tracing::info!(reason, "refreshing adb reverse mappings");

    let script = root.join("host/scripts/usb_reverse.sh");
    match Command::new(&script)
        .arg(stream_port.to_string())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .await
    {
        Ok(s) if s.success() => {}
        Ok(s) => warn!(reason, code = ?s.code(), "usb_reverse.sh failed"),
        Err(e) => warn!(reason, error = %e, "failed to execute usb_reverse.sh"),
    }

    match Command::new("adb")
        .arg("reverse")
        .arg(format!("tcp:{control_port}"))
        .arg(format!("tcp:{control_port}"))
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .await
    {
        Ok(s) if s.success() => {}
        Ok(s) => warn!(reason, code = ?s.code(), "adb reverse for control port failed"),
        Err(e) => warn!(reason, error = %e, "failed to execute adb reverse for control port"),
    }
}
