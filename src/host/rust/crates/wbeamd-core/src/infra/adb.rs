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
pub async fn ensure_usb_reverse(
    root: &Path,
    stream_port: u16,
    control_port: u16,
    reason: &str,
    target_serial: Option<&str>,
) {
    tracing::info!(reason, "refreshing adb reverse mappings");

    let script = root.join("src/host/scripts/usb_reverse.sh");
    let serials = adb_target_serials(target_serial).await;

    if serials.is_empty() {
        warn!(reason, "no adb devices in 'device' state for reverse mapping");
        return;
    }

    for serial in serials {
        match Command::new(&script)
            .arg(stream_port.to_string())
            .env("WBEAM_ANDROID_SERIAL", &serial)
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .await
        {
            Ok(s) if s.success() => {}
            Ok(s) => warn!(reason, %serial, code = ?s.code(), "usb_reverse.sh failed"),
            Err(e) => warn!(reason, %serial, error = %e, "failed to execute usb_reverse.sh"),
        }

        match Command::new("adb")
            .arg("-s")
            .arg(&serial)
            .arg("reverse")
            .arg(format!("tcp:{control_port}"))
            .arg(format!("tcp:{control_port}"))
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status()
            .await
        {
            Ok(s) if s.success() => {}
            Ok(s) => warn!(reason, %serial, code = ?s.code(), "adb reverse for control port failed"),
            Err(e) => warn!(reason, %serial, error = %e, "failed to execute adb reverse for control port"),
        }
    }
}

async fn adb_target_serials(target_serial: Option<&str>) -> Vec<String> {
    if let Some(serial) = target_serial {
        let trimmed = serial.trim();
        if !trimmed.is_empty() {
            return vec![trimmed.to_string()];
        }
    }

    if let Ok(forced) = std::env::var("WBEAM_ANDROID_SERIAL") {
        let trimmed = forced.trim();
        if !trimmed.is_empty() {
            return vec![trimmed.to_string()];
        }
    }

    let output = Command::new("adb")
        .arg("devices")
        .output()
        .await;

    let Ok(out) = output else {
        return Vec::new();
    };
    if !out.status.success() {
        return Vec::new();
    }

    String::from_utf8_lossy(&out.stdout)
        .lines()
        .filter_map(|line| {
            let trimmed = line.trim();
            if trimmed.is_empty() || trimmed.starts_with("List of devices attached") {
                return None;
            }
            let mut parts = trimmed.split_whitespace();
            let serial = parts.next()?;
            let state = parts.next().unwrap_or_default();
            if state == "device" {
                Some(serial.to_string())
            } else {
                None
            }
        })
        .collect()
}
