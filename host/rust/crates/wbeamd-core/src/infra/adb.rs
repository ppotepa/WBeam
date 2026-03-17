//! ADB reverse-port management.
//!
//! Keeps the Android device able to reach the host's stream and control
//! ports over USB via `usb_reverse.sh` and `adb reverse`.

use std::net::TcpListener;
use std::path::Path;
use std::process::Stdio;
use std::sync::{Mutex as StdMutex, OnceLock};
use std::time::{Duration, Instant};

use tokio::process::Command;
use tracing::warn;

const ANDROID_DEVICE_STREAM_PORT: u16 = 5000;
const ANDROID_DEVICE_CONTROL_PORT: u16 = 5001;

static USB_REVERSE_GUARD: OnceLock<tokio::sync::Mutex<()>> = OnceLock::new();
static USB_REVERSE_LAST_AT: OnceLock<StdMutex<Option<Instant>>> = OnceLock::new();

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
    let guard = USB_REVERSE_GUARD.get_or_init(|| tokio::sync::Mutex::new(()));
    let _guard = match guard.try_lock() {
        Ok(g) => g,
        Err(_) => {
            warn!(
                reason,
                "skipping reverse refresh (another reverse task in progress)"
            );
            return;
        }
    };
    if refresh_throttled() {
        return;
    }

    tracing::info!(reason, "refreshing adb reverse mappings");

    let script = root.join("host/scripts/usb_reverse.sh");
    let serials = adb_target_serials(target_serial).await;

    if serials.is_empty() {
        warn!(
            reason,
            "no adb devices in 'device' state for reverse mapping"
        );
        return;
    }

    for serial in serials {
        ensure_serial_reverse(&script, &serial, stream_port, control_port, reason).await;
    }
}

fn refresh_throttled() -> bool {
    let last = USB_REVERSE_LAST_AT.get_or_init(|| StdMutex::new(None));
    if let Ok(mut slot) = last.lock() {
        if let Some(prev) = *slot {
            if prev.elapsed() < Duration::from_millis(800) {
                return true;
            }
        }
        *slot = Some(Instant::now());
    }
    false
}

async fn ensure_serial_reverse(
    script: &Path,
    serial: &str,
    stream_port: u16,
    control_port: u16,
    reason: &str,
) {
    apply_usb_reverse_script(script, serial, stream_port, reason).await;
    if needs_stream_compat_mapping(stream_port) {
        apply_adb_reverse(
            serial,
            stream_port,
            stream_port,
            reason,
            "compat adb reverse for stream port",
        )
        .await;
    }
    apply_adb_reverse(
        serial,
        ANDROID_DEVICE_CONTROL_PORT,
        control_port,
        reason,
        "adb reverse for control port",
    )
    .await;
}

async fn apply_usb_reverse_script(script: &Path, serial: &str, stream_port: u16, reason: &str) {
    match Command::new(script)
        .arg(ANDROID_DEVICE_STREAM_PORT.to_string())
        .arg(stream_port.to_string())
        .env("WBEAM_ANDROID_SERIAL", serial)
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .await
    {
        Ok(s) if s.success() => {}
        Ok(s) => warn!(reason, %serial, code = ?s.code(), "usb_reverse.sh failed"),
        Err(e) => warn!(reason, %serial, error = %e, "failed to execute usb_reverse.sh"),
    }
}

async fn apply_adb_reverse(
    serial: &str,
    device_port: u16,
    host_port: u16,
    reason: &str,
    log_label: &'static str,
) {
    match Command::new("adb")
        .arg("-s")
        .arg(serial)
        .arg("reverse")
        .arg(format!("tcp:{device_port}"))
        .arg(format!("tcp:{host_port}"))
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .await
    {
        Ok(s) if s.success() => {}
        Ok(s) => warn!(reason, %serial, code = ?s.code(), label = log_label, "adb reverse failed"),
        Err(e) => {
            warn!(reason, %serial, error = %e, label = log_label, "failed to execute adb reverse")
        }
    }
}

fn needs_stream_compat_mapping(stream_port: u16) -> bool {
    stream_port != ANDROID_DEVICE_STREAM_PORT
}

pub async fn device_resolution(target_serial: Option<&str>) -> Option<String> {
    let serials = adb_target_serials(target_serial).await;
    let serial = serials.first()?.clone();
    let output = Command::new("adb")
        .arg("-s")
        .arg(&serial)
        .args(["shell", "wm", "size"])
        .output()
        .await
        .ok()?;
    if !output.status.success() {
        return None;
    }

    parse_wm_size(&String::from_utf8_lossy(&output.stdout))
}

fn parse_wm_size(raw: &str) -> Option<String> {
    for line in raw.lines() {
        let trimmed = line.trim();
        if let Some(val) = trimmed.strip_prefix("Physical size:") {
            let v = val.trim();
            if is_size(v) {
                return Some(v.to_string());
            }
        }
        if let Some(val) = trimmed.strip_prefix("Override size:") {
            let v = val.trim();
            if is_size(v) {
                return Some(v.to_string());
            }
        }
    }
    None
}

fn is_size(v: &str) -> bool {
    let mut parts = v.split('x');
    let w_ok = parts.next().and_then(|x| x.parse::<u32>().ok()).is_some();
    let h_ok = parts.next().and_then(|x| x.parse::<u32>().ok()).is_some();
    w_ok && h_ok
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

    let output = Command::new("adb").arg("devices").output().await;

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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn compat_mapping_not_needed_for_default_stream_port() {
        assert!(!needs_stream_compat_mapping(5000));
    }

    #[test]
    fn compat_mapping_needed_for_non_default_stream_port() {
        assert!(needs_stream_compat_mapping(5002));
        assert!(needs_stream_compat_mapping(7000));
    }

    #[test]
    fn android_device_port_constants_are_stable() {
        assert_eq!(ANDROID_DEVICE_STREAM_PORT, 5000);
        assert_eq!(ANDROID_DEVICE_CONTROL_PORT, 5001);
    }
}
