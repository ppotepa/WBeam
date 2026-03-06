use std::process::Command;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::Sender;
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::Duration;

use crate::domain::device::{DeviceInfo, DiscoverySource};
use crate::domain::host::HostContext;

#[derive(Clone, Debug)]
pub(crate) struct ProbeSnapshot {
    pub(crate) host: HostContext,
    pub(crate) devices: Vec<DeviceInfo>,
    pub(crate) adb_available: bool,
    pub(crate) adb_responsive: bool,
    pub(crate) error: Option<String>,
}

pub(crate) struct ProbeWorker {
    stop: Arc<AtomicBool>,
    handle: Option<JoinHandle<()>>,
}

impl Drop for ProbeWorker {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(handle) = self.handle.take() {
            let _ = handle.join();
        }
    }
}

#[derive(Clone, Debug)]
pub(crate) struct ProbeService {
    poll_interval: Duration,
}

impl ProbeService {
    pub(crate) fn new(poll_interval: Duration) -> Self {
        Self { poll_interval }
    }

    pub(crate) fn probe_once(&self) -> ProbeSnapshot {
        log_info("probe_once start");
        let host = HostContext::probe();
        if !adb_on_path() {
            log_warn("probe_once adb not found on PATH");
            return ProbeSnapshot {
                host,
                devices: Vec::new(),
                adb_available: false,
                adb_responsive: false,
                error: Some("adb not found on PATH".to_string()),
            };
        }
        log_info(&format!("probe_once adb binary={}", adb_binary().to_string_lossy()));

        match adb_devices_with_props() {
            Ok(devices) => {
                log_info(&format!("probe_once ok devices={}", devices.len()));
                ProbeSnapshot {
                    host,
                    devices,
                    adb_available: true,
                    adb_responsive: true,
                    error: None,
                }
            }
            Err(err) => {
                log_warn(&format!("probe_once failed: {err}"));
                ProbeSnapshot {
                    host,
                    devices: Vec::new(),
                    adb_available: true,
                    adb_responsive: false,
                    error: Some(err),
                }
            }
        }
    }

    pub(crate) fn start_background(&self, tx: Sender<ProbeSnapshot>) -> ProbeWorker {
        let stop = Arc::new(AtomicBool::new(false));
        let stop_for_thread = Arc::clone(&stop);
        let service = self.clone();
        let poll = self.poll_interval;

        let handle = thread::spawn(move || {
            while !stop_for_thread.load(Ordering::Relaxed) {
                let snapshot = service.probe_once();
                if tx.send(snapshot).is_err() {
                    break;
                }

                let mut slept = Duration::from_millis(0);
                while slept < poll && !stop_for_thread.load(Ordering::Relaxed) {
                    let step = Duration::from_millis(200);
                    thread::sleep(step);
                    slept += step;
                }
            }
        });

        ProbeWorker {
            stop,
            handle: Some(handle),
        }
    }
}

pub(crate) fn adb_on_path() -> bool {
    resolve_adb_binary().is_some()
}

fn adb_devices_with_props() -> Result<Vec<DeviceInfo>, String> {
    let output = Command::new(adb_binary())
        .args(["devices", "-l"])
        .output()
        .map_err(|err| format!("cannot run adb: {err}"))?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
        return if stderr.is_empty() {
            Err(format!("adb exited with status {}", output.status))
        } else {
            Err(format!("adb failed: {stderr}"))
        };
    }

    let text = String::from_utf8_lossy(&output.stdout);
    let mut devices = Vec::new();

    for line in text.lines() {
        let raw = line.trim();
        if raw.is_empty()
            || raw.starts_with("List of devices attached")
            || raw.starts_with("* daemon")
        {
            continue;
        }

        let mut parts = raw.split_whitespace();
        let Some(serial) = parts.next() else {
            continue;
        };
        let Some(adb_state) = parts.next() else {
            continue;
        };

        let mut model = String::new();
        let mut device_name = String::new();
        for token in parts {
            if let Some(value) = token.strip_prefix("model:") {
                model = value.to_string();
            } else if let Some(value) = token.strip_prefix("device:") {
                device_name = value.to_string();
            }
        }

        let props = if adb_state == "device" {
            device_properties(serial)
        } else {
            DeviceProperties::default()
        };

        devices.push(DeviceInfo {
            discovery_source: DiscoverySource::Adb,
            source_identity: serial.to_string(),
            serial: serial.to_string(),
            adb_state: adb_state.to_string(),
            model,
            device_name,
            manufacturer: props.manufacturer,
            api_level: props.api_level,
            android_release: props.android_release,
            abi: props.abi,
            characteristics: props.characteristics,
            battery_level: props.battery_level,
            battery_status: props.battery_status,
        });
    }

    devices.sort_by(|a, b| a.sort_key().cmp(b.sort_key()));
    Ok(devices)
}

#[derive(Default)]
struct DeviceProperties {
    manufacturer: String,
    api_level: String,
    android_release: String,
    abi: String,
    characteristics: String,
    battery_level: String,
    battery_status: String,
}

fn device_properties(serial: &str) -> DeviceProperties {
    DeviceProperties {
        manufacturer: adb_getprop(serial, "ro.product.manufacturer"),
        api_level: adb_getprop(serial, "ro.build.version.sdk"),
        android_release: adb_getprop(serial, "ro.build.version.release"),
        abi: adb_getprop(serial, "ro.product.cpu.abi"),
        characteristics: adb_getprop(serial, "ro.build.characteristics"),
        battery_level: adb_battery_field(serial, "level"),
        battery_status: adb_battery_field(serial, "status"),
    }
}

fn adb_getprop(serial: &str, key: &str) -> String {
    let output = Command::new(adb_binary())
        .args(["-s", serial, "shell", "getprop", key])
        .output();
    match output {
        Ok(out) if out.status.success() => String::from_utf8_lossy(&out.stdout).trim().to_string(),
        _ => String::new(),
    }
}

fn adb_battery_field(serial: &str, field: &str) -> String {
    let output = Command::new(adb_binary())
        .args(["-s", serial, "shell", "dumpsys", "battery"])
        .output();
    let Ok(out) = output else {
        return String::new();
    };
    if !out.status.success() {
        return String::new();
    }

    let text = String::from_utf8_lossy(&out.stdout);
    let prefix = format!("{field}:");
    for line in text.lines() {
        let trimmed = line.trim();
        if let Some(value) = trimmed.strip_prefix(&prefix) {
            return value.trim().to_string();
        }
    }
    String::new()
}

fn which(bin: &str) -> Option<std::path::PathBuf> {
    let paths = std::env::var_os("PATH")?;
    std::env::split_paths(&paths)
        .map(|p| p.join(bin))
        .find(|candidate| candidate.exists())
}

fn resolve_adb_binary() -> Option<PathBuf> {
    which("adb").or_else(|| {
        [
            "/usr/bin/adb",
            "/usr/local/bin/adb",
            "/opt/android-sdk/platform-tools/adb",
            "/opt/android-sdk-linux/platform-tools/adb",
        ]
        .iter()
        .map(PathBuf::from)
        .find(|candidate| candidate.exists())
    })
}

fn adb_binary() -> PathBuf {
    resolve_adb_binary().unwrap_or_else(|| PathBuf::from("adb"))
}

fn log_info(msg: &str) {
    eprintln!("[wbeam-egui][probe][info] {msg}");
}

fn log_warn(msg: &str) {
    eprintln!("[wbeam-egui][probe][warn] {msg}");
}
