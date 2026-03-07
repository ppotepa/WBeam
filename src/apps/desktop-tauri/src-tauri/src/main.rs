#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use serde::Serialize;
use serde_json::Value;
use std::fs;
use std::path::PathBuf;
use std::process::Command;
use std::thread;
use std::time::Duration;
use tauri::Manager;

const SERVICE_NAME: &str = "wbeam-daemon";

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct DeviceBasic {
    serial: String,
    model: String,
    platform: String,
    os_version: String,
    device_class: String,
    resolution: String,
    max_resolution: String,
    api_level: String,
    battery_percent: String,
    battery_level: Option<u8>,
    battery_charging: bool,
    apk_installed: bool,
    apk_version: String,
    apk_matches_host: bool,
    apk_matches_daemon: bool,
    stream_port: u16,
    stream_state: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct DevicesBasicResponse {
    host_apk_version: String,
    daemon_apk_version: String,
    devices: Vec<DeviceBasic>,
    error: Option<String>,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct ServiceStatus {
    available: bool,
    installed: bool,
    active: bool,
    enabled: bool,
    summary: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct HostProbeBrief {
    reachable: bool,
    os: String,
    session: String,
    desktop: String,
    capture_mode: String,
    supported: bool,
}

#[tauri::command]
fn ping() -> &'static str {
    "pong"
}

#[tauri::command]
fn host_name() -> String {
    Command::new("hostname")
        .output()
        .ok()
        .map(|out| String::from_utf8_lossy(&out.stdout).trim().to_string())
        .filter(|name| !name.is_empty())
        .unwrap_or_else(|| "unknown-host".to_string())
}

#[tauri::command]
fn list_devices_basic() -> DevicesBasicResponse {
    let host_apk_version = host_expected_apk_version();
    let daemon_apk_version = host_build_revision_from_health().unwrap_or_default();
    let svc = service_status();
    if !svc.available || !svc.installed || !svc.active {
        return DevicesBasicResponse {
            host_apk_version,
            daemon_apk_version,
            devices: Vec::new(),
            error: None,
        };
    }

    let base_stream_port = std::env::var("WBEAM_STREAM_PORT")
        .ok()
        .and_then(|v| v.trim().parse::<u16>().ok())
        .unwrap_or(5000);
    let port_map = load_device_port_map();

    match adb_devices() {
        Ok(serials) => {
            let mut devices = Vec::new();
            for (idx, serial) in serials.into_iter().enumerate() {
                let model = adb_getprop(&serial, "ro.product.model");
                let manufacturer = adb_getprop(&serial, "ro.product.manufacturer").unwrap_or_default();
                let os_version = adb_getprop(&serial, "ro.build.version.release");
                let platform = detect_platform(&manufacturer);
                let resolution = adb_resolution(&serial);
                let density = adb_density(&serial);
                let device_class = classify_device(&resolution, density);
                let max_resolution = adb_max_resolution(&serial, &resolution);
                let api_level = adb_getprop(&serial, "ro.build.version.sdk");
                let (battery_percent, battery_level, battery_charging) = adb_battery_info(&serial);
                let apk_path = adb_shell(&serial, &["pm", "path", "com.wbeam"]);
                let apk_installed = apk_path
                    .as_ref()
                    .map(|out| out.contains("package:"))
                    .unwrap_or(false);
                let apk_version = if apk_installed {
                    adb_apk_version(&serial)
                } else {
                    String::new()
                };
                let apk_matches_host = !host_apk_version.is_empty() && apk_version == host_apk_version;
                let apk_matches_daemon = !daemon_apk_version.is_empty() && apk_version == daemon_apk_version;
                let stream_port = port_map
                    .get(&serial)
                    .copied()
                    .unwrap_or_else(|| base_stream_port.saturating_add(1 + idx as u16));
                let stream_state = daemon_stream_state(&serial, stream_port);

                devices.push(DeviceBasic {
                    serial,
                    model: or_unknown(model),
                    platform,
                    os_version: or_unknown(os_version),
                    device_class,
                    resolution: or_unknown(Some(resolution)),
                    max_resolution,
                    api_level: or_unknown(api_level),
                    battery_percent,
                    battery_level,
                    battery_charging,
                    apk_installed,
                    apk_version,
                    apk_matches_host,
                    apk_matches_daemon,
                    stream_port,
                    stream_state,
                });
            }

            DevicesBasicResponse {
                host_apk_version,
                daemon_apk_version,
                devices,
                error: None,
            }
        }
        Err(err) => DevicesBasicResponse {
            host_apk_version,
            daemon_apk_version,
            devices: Vec::new(),
            error: Some(err),
        },
    }
}

fn load_device_port_map() -> std::collections::HashMap<String, u16> {
    let path = repo_root().join(".wbeam_device_ports");
    let mut map = std::collections::HashMap::new();
    let Ok(content) = fs::read_to_string(path) else {
        return map;
    };
    for line in content.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with('#') {
            continue;
        }
        let mut parts = trimmed.split_whitespace();
        let serial = parts.next().unwrap_or_default().trim();
        let port_s = parts.next().unwrap_or_default().trim();
        if serial.is_empty() {
            continue;
        }
        if let Ok(port) = port_s.parse::<u16>() {
            map.insert(serial.to_string(), port);
        }
    }
    map
}

fn host_expected_apk_version() -> String {
    let file_path = repo_root().join(".wbeam_build_version");
    if let Ok(content) = std::fs::read_to_string(file_path) {
        let v = content.trim().to_string();
        if !v.is_empty() {
            return v;
        }
    }

    if let Ok(explicit) = std::env::var("WBEAM_HOST_APK_VERSION") {
        let trimmed = explicit.trim();
        if !trimmed.is_empty() {
            return trimmed.to_string();
        }
    }

    if let Some(from_health) = host_build_revision_from_health() {
        if !from_health.trim().is_empty() && from_health.trim() != "-" {
            return from_health.trim().to_string();
        }
    }

    String::new()
}

fn host_build_revision_from_health() -> Option<String> {
    let control_port = std::env::var("WBEAM_CONTROL_PORT").unwrap_or_else(|_| "5001".to_string());
    let url = format!("http://127.0.0.1:{control_port}/health");
    let output = Command::new("curl")
        .args(["-fsS", "--max-time", "1", &url])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let body = String::from_utf8_lossy(&output.stdout);
    let json: Value = serde_json::from_str(&body).ok()?;
    json.get("build_revision")
        .and_then(|v| v.as_str())
        .map(|s| s.to_string())
}

fn daemon_stream_state(serial: &str, stream_port: u16) -> String {
    let control_port = std::env::var("WBEAM_CONTROL_PORT").unwrap_or_else(|_| "5001".to_string());
    let url = format!(
        "http://127.0.0.1:{control_port}/v1/status?serial={serial}&stream_port={stream_port}"
    );
    let output = Command::new("curl")
        .args(["-fsS", "--max-time", "1", &url])
        .output();
    let Ok(output) = output else {
        return "unknown".to_string();
    };
    if !output.status.success() {
        return "unknown".to_string();
    }
    let body = String::from_utf8_lossy(&output.stdout);
    let json: Value = serde_json::from_str(&body).unwrap_or(Value::Null);
    json.get("state")
        .and_then(|v| v.as_str())
        .unwrap_or("unknown")
        .to_string()
}

fn daemon_post_action(action: &str, serial: &str, stream_port: u16) -> Result<String, String> {
    let control_port = std::env::var("WBEAM_CONTROL_PORT").unwrap_or_else(|_| "5001".to_string());
    let url = format!(
        "http://127.0.0.1:{control_port}/v1/{action}?serial={serial}&stream_port={stream_port}"
    );
    let output = Command::new("curl")
        .args(["-sS", "--max-time", "3", "-X", "POST", "-w", "\nHTTP_STATUS:%{http_code}", &url])
        .output()
        .map_err(|e| format!("curl failed: {e}"))?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
        return Err(if stderr.is_empty() {
            "Host API unreachable. Verify desktop service is running.".to_string()
        } else {
            stderr
        });
    }

    let body = String::from_utf8_lossy(&output.stdout).to_string();
    let (payload, status_line) = body
        .rsplit_once("\nHTTP_STATUS:")
        .unwrap_or((body.as_str(), "000"));
    let status_code = status_line.trim();
    if !status_code.starts_with('2') {
        let trimmed = payload.trim();
        if trimmed.is_empty() {
            return Err(format!("daemon action failed: {action} (http={status_code})"));
        }
        return Err(trimmed.to_string());
    }
    Ok(payload.trim().to_string())
}

fn adb_prepare_connect(serial: &str, stream_port: u16) {
    let control_port = std::env::var("WBEAM_CONTROL_PORT")
        .ok()
        .and_then(|v| v.trim().parse::<u16>().ok())
        .unwrap_or(5001);
    let stream = stream_port.to_string();
    let control = control_port.to_string();

    let _ = Command::new("adb").args(["start-server"]).output();
    let _ = Command::new("adb")
        .args(["-s", serial, "wait-for-device"])
        .output();
    let _ = Command::new("adb")
        .args(["-s", serial, "reverse", &format!("tcp:{stream}"), &format!("tcp:{stream}")])
        .output();
    let _ = Command::new("adb")
        .args(["-s", serial, "reverse", &format!("tcp:{control}"), &format!("tcp:{control}")])
        .output();
    let _ = Command::new("adb")
        .args(["-s", serial, "shell", "am", "start", "-n", "com.wbeam/.MainActivity"])
        .output();
}

#[tauri::command]
fn service_status() -> ServiceStatus {
    if !command_exists("systemctl") {
        return ServiceStatus {
            available: false,
            installed: false,
            active: false,
            enabled: false,
            summary: "systemctl not available".to_string(),
        };
    }

    let installed = unit_file_path().exists();
    let active = systemctl_state("is-active").as_deref() == Some("active");
    let enabled = systemctl_state("is-enabled").as_deref() == Some("enabled");

    let mut summary = format!(
        "installed={} active={} enabled={}",
        installed, active, enabled
    );
    if !active {
        if let Some(lock_hint) = daemon_lock_hint() {
            summary.push_str(&format!("; {lock_hint}"));
        }
    }

    ServiceStatus {
        available: true,
        installed,
        active,
        enabled,
        summary,
    }
}

fn daemon_lock_hint() -> Option<String> {
    let lock_path = PathBuf::from("/tmp/wbeamd.lock");
    let pid_text = fs::read_to_string(lock_path).ok()?;
    let pid = pid_text.trim().parse::<u32>().ok()?;

    let output = Command::new("ps")
        .args(["-p", &pid.to_string(), "-o", "comm="])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let comm = String::from_utf8_lossy(&output.stdout).trim().to_string();
    if comm.contains("wbeamd-server") {
        return Some(format!("lock held by pid={pid} ({comm})"));
    }
    None
}

fn stop_conflicting_lock_holder() {
    let lock_path = PathBuf::from("/tmp/wbeamd.lock");
    let Ok(pid_text) = fs::read_to_string(&lock_path) else {
        return;
    };
    let Ok(pid) = pid_text.trim().parse::<u32>() else {
        return;
    };
    if !process_name_matches(pid, "wbeamd-server") {
        return;
    }

    let pid_s = pid.to_string();
    let _ = Command::new("kill").args(["-TERM", &pid_s]).status();
    for _ in 0..10 {
        if !process_exists(pid) {
            let _ = fs::remove_file(&lock_path);
            return;
        }
        thread::sleep(Duration::from_millis(100));
    }

    let _ = Command::new("kill").args(["-KILL", &pid_s]).status();
    for _ in 0..10 {
        if !process_exists(pid) {
            let _ = fs::remove_file(&lock_path);
            return;
        }
        thread::sleep(Duration::from_millis(100));
    }
}

fn process_exists(pid: u32) -> bool {
    Command::new("ps")
        .args(["-p", &pid.to_string()])
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

fn process_name_matches(pid: u32, needle: &str) -> bool {
    let output = Command::new("ps")
        .args(["-p", &pid.to_string(), "-o", "comm="])
        .output();
    let Ok(output) = output else {
        return false;
    };
    if !output.status.success() {
        return false;
    }
    String::from_utf8_lossy(&output.stdout)
        .trim()
        .contains(needle)
}

#[tauri::command]
fn host_probe_brief() -> HostProbeBrief {
    let control_port = std::env::var("WBEAM_CONTROL_PORT").unwrap_or_else(|_| "5001".to_string());
    let url = format!("http://127.0.0.1:{control_port}/host-probe");
    let output = Command::new("curl")
        .args(["-fsS", "--max-time", "1", &url])
        .output();

    let Ok(output) = output else {
        return HostProbeBrief {
            reachable: false,
            os: "unknown".to_string(),
            session: "unknown".to_string(),
            desktop: "unknown".to_string(),
            capture_mode: "unknown".to_string(),
            supported: false,
        };
    };

    if !output.status.success() {
        return HostProbeBrief {
            reachable: false,
            os: "unknown".to_string(),
            session: "unknown".to_string(),
            desktop: "unknown".to_string(),
            capture_mode: "unknown".to_string(),
            supported: false,
        };
    }

    let body = String::from_utf8_lossy(&output.stdout);
    let json: Value = serde_json::from_str(&body).unwrap_or(Value::Null);
    HostProbeBrief {
        reachable: true,
        os: json.get("os").and_then(|v| v.as_str()).unwrap_or("unknown").to_string(),
        session: json
            .get("session")
            .and_then(|v| v.as_str())
            .unwrap_or("unknown")
            .to_string(),
        desktop: json
            .get("desktop")
            .and_then(|v| v.as_str())
            .unwrap_or("unknown")
            .to_string(),
        capture_mode: json
            .get("capture_mode")
            .and_then(|v| v.as_str())
            .unwrap_or("unknown")
            .to_string(),
        supported: json.get("supported").and_then(|v| v.as_bool()).unwrap_or(false),
    }
}

#[tauri::command]
fn device_connect(serial: String, stream_port: u16) -> Result<String, String> {
    adb_prepare_connect(&serial, stream_port);
    daemon_post_action("start", &serial, stream_port)
}

#[tauri::command]
fn device_disconnect(serial: String, stream_port: u16) -> Result<String, String> {
    daemon_post_action("stop", &serial, stream_port)
}

#[tauri::command]
fn service_install() -> Result<ServiceStatus, String> {
    ensure_systemctl()?;
    let unit_path = unit_file_path();
    if let Some(parent) = unit_path.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("cannot create unit dir: {e}"))?;
    }

    fs::write(&unit_path, service_unit_content()).map_err(|e| format!("cannot write unit file: {e}"))?;

    systemctl_user(&["daemon-reload"])?;
    systemctl_user(&["enable", SERVICE_NAME])?;
    Ok(service_status())
}

#[tauri::command]
fn service_uninstall() -> Result<ServiceStatus, String> {
    ensure_systemctl()?;
    let _ = systemctl_user(&["stop", SERVICE_NAME]);
    let _ = systemctl_user(&["disable", SERVICE_NAME]);
    let unit_path = unit_file_path();
    if unit_path.exists() {
        fs::remove_file(&unit_path).map_err(|e| format!("cannot remove unit file: {e}"))?;
    }
    systemctl_user(&["daemon-reload"])?;
    Ok(service_status())
}

#[tauri::command]
fn service_start() -> Result<ServiceStatus, String> {
    ensure_systemctl()?;
    if !unit_file_path().exists() {
        return Err("service is not installed".to_string());
    }
    stop_conflicting_lock_holder();
    systemctl_user(&["start", SERVICE_NAME])?;
    Ok(service_status())
}

#[tauri::command]
fn service_stop() -> Result<ServiceStatus, String> {
    ensure_systemctl()?;
    if unit_file_path().exists() {
        systemctl_user(&["stop", SERVICE_NAME])?;
    }
    Ok(service_status())
}

fn ensure_systemctl() -> Result<(), String> {
    if command_exists("systemctl") {
        Ok(())
    } else {
        Err("systemctl is not available on this host".to_string())
    }
}

fn command_exists(name: &str) -> bool {
    Command::new("sh")
        .args(["-c", &format!("command -v {name} >/dev/null 2>&1")])
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

fn systemctl_state(action: &str) -> Option<String> {
    Command::new("systemctl")
        .args(["--user", action, SERVICE_NAME])
        .output()
        .ok()
        .map(|out| String::from_utf8_lossy(&out.stdout).trim().to_string())
        .filter(|v| !v.is_empty())
}

fn systemctl_user(args: &[&str]) -> Result<(), String> {
    let output = Command::new("systemctl")
        .arg("--user")
        .args(args)
        .output()
        .map_err(|e| format!("systemctl failed: {e}"))?;

    if output.status.success() {
        return Ok(());
    }

    let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
    let stdout = String::from_utf8_lossy(&output.stdout).trim().to_string();
    let msg = if !stderr.is_empty() {
        stderr
    } else if !stdout.is_empty() {
        stdout
    } else {
        format!("systemctl exited with {}", output.status)
    };
    Err(msg)
}

fn unit_file_path() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| ".".to_string());
    PathBuf::from(home).join(format!(".config/systemd/user/{SERVICE_NAME}.service"))
}

fn service_unit_content() -> String {
    let daemon_bin = std::env::var("WBEAM_DAEMON_BIN").ok().filter(|v| !v.trim().is_empty()).unwrap_or_else(|| {
        repo_root()
            .join("src/host/rust/target/release/wbeamd-server")
            .to_string_lossy()
            .to_string()
    });
    let control_port = std::env::var("WBEAM_CONTROL_PORT").unwrap_or_else(|_| "5001".to_string());
    let stream_port = std::env::var("WBEAM_STREAM_PORT").unwrap_or_else(|_| "5000".to_string());
    let root = repo_root().to_string_lossy().to_string();

    format!(
        "[Unit]\nDescription=WBeam Screen Streaming Daemon\nAfter=graphical-session.target\n\n[Service]\nType=simple\nExecStart={daemon_bin} --control-port {control_port} --stream-port {stream_port} --root {root}\nRestart=on-failure\nRestartSec=3\nEnvironment=RUST_LOG=info\nEnvironment=WBEAM_USE_RUST_STREAMER=1\nEnvironment=WBEAM_ROOT={root}\n\n[Install]\nWantedBy=default.target\n"
    )
}

fn detect_platform(manufacturer: &str) -> String {
    let m = manufacturer.to_lowercase();
    if m.contains("apple") {
        "iOS".to_string()
    } else {
        "Android".to_string()
    }
}

fn adb_devices() -> Result<Vec<String>, String> {
    let output = adb_cmd(&["devices"])?;
    let mut serials = Vec::new();
    for line in output.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with("List of devices") {
            continue;
        }
        let mut parts = trimmed.split_whitespace();
        let serial = parts.next().unwrap_or_default();
        let state = parts.next().unwrap_or_default();
        if !serial.is_empty() && state == "device" {
            serials.push(serial.to_string());
        }
    }
    Ok(serials)
}

fn adb_getprop(serial: &str, key: &str) -> Option<String> {
    adb_shell(serial, &["getprop", key]).ok().filter(|v| !v.is_empty())
}

fn adb_resolution(serial: &str) -> String {
    let wm = adb_shell(serial, &["wm", "size"]).unwrap_or_default();
    for line in wm.lines() {
        let clean = line.trim();
        if let Some(val) = clean.strip_prefix("Physical size:") {
            return val.trim().to_string();
        }
        if let Some(val) = clean.strip_prefix("Override size:") {
            return val.trim().to_string();
        }
    }
    "unknown".to_string()
}

fn adb_density(serial: &str) -> Option<u32> {
    let wm = adb_shell(serial, &["wm", "density"]).unwrap_or_default();
    for line in wm.lines() {
        let clean = line.trim();
        if let Some(val) = clean.strip_prefix("Physical density:") {
            return val.trim().parse::<u32>().ok();
        }
        if let Some(val) = clean.strip_prefix("Override density:") {
            return val.trim().parse::<u32>().ok();
        }
    }
    None
}

fn parse_resolution_dims(value: &str) -> Option<(u32, u32)> {
    let trimmed = value.trim().to_lowercase().replace(' ', "");
    let parts: Vec<&str> = trimmed.split('x').collect();
    if parts.len() != 2 {
        return None;
    }
    let w = parts[0].parse::<u32>().ok()?;
    let h = parts[1].parse::<u32>().ok()?;
    Some((w, h))
}

fn classify_device(resolution: &str, density: Option<u32>) -> String {
    let Some((w, h)) = parse_resolution_dims(resolution) else {
        return "Unknown".to_string();
    };
    let Some(dpi) = density else {
        return "Unknown".to_string();
    };
    if dpi == 0 {
        return "Unknown".to_string();
    }
    let smallest_px = std::cmp::min(w, h) as f32;
    let sw_dp = smallest_px * 160.0 / dpi as f32;
    if sw_dp >= 600.0 {
        "Tablet".to_string()
    } else {
        "Phone".to_string()
    }
}

fn adb_max_resolution(serial: &str, fallback: &str) -> String {
    let dumpsys = adb_shell(serial, &["dumpsys", "display"]).unwrap_or_default();
    let mut best: Option<(u32, u32)> = None;

    for line in dumpsys.lines() {
        let mut rest = line;
        while let Some(w_idx) = rest.find("width=") {
            let w_part = &rest[w_idx + 6..];
            let w_str: String = w_part.chars().take_while(|c| c.is_ascii_digit()).collect();
            let Some(h_idx_abs) = w_part.find("height=") else {
                break;
            };
            let h_part = &w_part[h_idx_abs + 7..];
            let h_str: String = h_part.chars().take_while(|c| c.is_ascii_digit()).collect();

            if let (Ok(w), Ok(h)) = (w_str.parse::<u32>(), h_str.parse::<u32>()) {
                let area = w.saturating_mul(h);
                match best {
                    Some((bw, bh)) if bw.saturating_mul(bh) >= area => {}
                    _ => best = Some((w, h)),
                }
            }

            rest = h_part;
        }
    }

    if let Some((w, h)) = best {
        return format!("{}x{}", w, h);
    }
    if fallback.trim().is_empty() {
        "unknown".to_string()
    } else {
        fallback.to_string()
    }
}

fn adb_battery_info(serial: &str) -> (String, Option<u8>, bool) {
    let dumpsys = adb_shell(serial, &["dumpsys", "battery"]).unwrap_or_default();
    let mut level: Option<u8> = None;
    let mut status: Option<u8> = None;

    for line in dumpsys.lines() {
        let clean = line.trim();
        if let Some(val) = clean.strip_prefix("level:") {
            level = val.trim().parse::<u8>().ok();
        }
        if let Some(val) = clean.strip_prefix("status:") {
            status = val.trim().parse::<u8>().ok();
        }
    }

    let percent = level
        .map(|v| format!("{}%", v))
        .unwrap_or_else(|| "unknown".to_string());
    let charging = matches!(status, Some(2));

    (percent, level, charging)
}

fn adb_apk_version(serial: &str) -> String {
    let dumpsys = adb_shell(serial, &["dumpsys", "package", "com.wbeam"]).unwrap_or_default();
    for line in dumpsys.lines() {
        let clean = line.trim();
        if let Some(val) = clean.strip_prefix("versionName=") {
            return val.trim().to_string();
        }
    }
    String::new()
}

fn adb_shell(serial: &str, args: &[&str]) -> Result<String, String> {
    let mut cmd = vec!["-s", serial, "shell"];
    cmd.extend_from_slice(args);
    adb_cmd(&cmd)
}

fn adb_cmd(args: &[&str]) -> Result<String, String> {
    let output = Command::new("adb")
        .args(args)
        .output()
        .map_err(|err| format!("adb failed: {err}"))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
        if stderr.is_empty() {
            return Err(format!("adb exited with {}", output.status));
        }
        return Err(stderr);
    }

    Ok(String::from_utf8_lossy(&output.stdout)
        .replace('\r', "")
        .trim()
        .to_string())
}

fn repo_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../../")
}

fn or_unknown(value: Option<String>) -> String {
    value
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| "unknown".to_string())
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            if let Some(window) = app.get_webview_window("main") {
                let _ = window.show();
                let _ = window.unminimize();
                let _ = window.set_focus();
            }
        }))
        .invoke_handler(tauri::generate_handler![
            ping,
            host_name,
            list_devices_basic,
            service_status,
            host_probe_brief,
            device_connect,
            device_disconnect,
            service_install,
            service_uninstall,
            service_start,
            service_stop
        ])
        .run(tauri::generate_context!())
        .expect("failed to run tauri app");
}
