#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::{HashMap, HashSet, VecDeque};
use std::fs;
use std::fs::OpenOptions;
use std::io::Write;
use std::io::{BufRead, BufReader};
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::sync::{Mutex, OnceLock};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tauri::{Manager, WebviewUrl, WebviewWindowBuilder};

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

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct VirtualDoctor {
    ok: bool,
    message: String,
    actionable: bool,
    host_backend: String,
    resolver: String,
    missing_deps: Vec<String>,
    install_hint: String,
}

#[derive(Debug, Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct VirtualDepsInstallStatus {
    running: bool,
    done: bool,
    success: bool,
    message: String,
    logs: Vec<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
struct StartConfigPatch {
    #[serde(skip_serializing_if = "Option::is_none")]
    profile: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    encoder: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    size: Option<String>,
}

impl StartConfigPatch {
    fn is_empty(&self) -> bool {
        self.profile.is_none() && self.encoder.is_none() && self.size.is_none()
    }
}

#[derive(Debug)]
struct VirtualDepsInstallState {
    running: bool,
    done: bool,
    success: bool,
    message: String,
    logs: VecDeque<String>,
}

impl Default for VirtualDepsInstallState {
    fn default() -> Self {
        Self {
            running: false,
            done: false,
            success: false,
            message: "idle".to_string(),
            logs: VecDeque::new(),
        }
    }
}

static VIRTUAL_DEPS_INSTALL_STATE: OnceLock<Mutex<VirtualDepsInstallState>> = OnceLock::new();
static ADB_CMD_LOCK: OnceLock<Mutex<()>> = OnceLock::new();
static DEVICE_SNAPSHOT_CACHE: OnceLock<Mutex<HashMap<String, CachedDeviceSnapshot>>> =
    OnceLock::new();
static SESSION_LOGS: OnceLock<SessionLogs> = OnceLock::new();
static WBEAM_CONFIG_CACHE: OnceLock<HashMap<String, String>> = OnceLock::new();

fn wbeam_user_config_path() -> Option<PathBuf> {
    let base = std::env::var("XDG_CONFIG_HOME")
        .ok()
        .filter(|v| !v.trim().is_empty())
        .map(PathBuf::from)
        .or_else(|| {
            std::env::var("HOME")
                .ok()
                .filter(|v| !v.trim().is_empty())
                .map(|h| PathBuf::from(h).join(".config"))
        })?;
    Some(base.join("wbeam/wbeam.conf"))
}

fn ensure_user_wbeam_config(root: &PathBuf) -> Option<PathBuf> {
    let user_cfg = wbeam_user_config_path()?;
    if user_cfg.exists() {
        return Some(user_cfg);
    }
    let template = root.join("config/wbeam.conf");
    if let Some(parent) = user_cfg.parent() {
        let _ = fs::create_dir_all(parent);
    }
    if template.exists() {
        let _ = fs::copy(&template, &user_cfg);
    } else {
        let _ = fs::write(&user_cfg, "");
    }
    Some(user_cfg)
}

fn wbeam_config_cache() -> &'static HashMap<String, String> {
    WBEAM_CONFIG_CACHE.get_or_init(|| {
        let root = repo_root();
        let mut files: Vec<PathBuf> = Vec::new();
        if let Some(user_cfg) = ensure_user_wbeam_config(&root) {
            files.push(user_cfg);
        } else {
            files.push(root.join("config/wbeam.conf"));
        }

        let mut map = HashMap::new();
        for file in files {
            let Ok(raw) = fs::read_to_string(&file) else {
                continue;
            };
            for line in raw.lines() {
                let line = line.trim();
                if line.is_empty() || line.starts_with('#') {
                    continue;
                }
                let Some((k, v)) = line.split_once('=') else {
                    continue;
                };
                let key = k.trim();
                if !key.starts_with("WBEAM_") {
                    continue;
                }
                if map.contains_key(key) {
                    continue;
                }
                let mut value = v.trim().to_string();
                let bytes = value.as_bytes();
                if bytes.len() >= 2
                    && ((bytes[0] == b'"' && bytes[bytes.len() - 1] == b'"')
                        || (bytes[0] == b'\'' && bytes[bytes.len() - 1] == b'\''))
                {
                    value = value[1..value.len() - 1].to_string();
                }
                map.insert(key.to_string(), value);
            }
        }
        map
    })
}

fn wbeam_config_value(key: &str) -> Option<String> {
    if let Ok(value) = std::env::var(key) {
        let trimmed = value.trim();
        if !trimmed.is_empty() {
            return Some(trimmed.to_string());
        }
    }
    wbeam_config_cache()
        .get(key)
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
}

fn wbeam_config_u16(key: &str, default: u16) -> u16 {
    wbeam_config_value(key)
        .and_then(|v| v.parse::<u16>().ok())
        .unwrap_or(default)
}

fn wbeam_config_bool(key: &str, default: bool) -> bool {
    wbeam_config_value(key)
        .map(|v| {
            matches!(
                v.trim().to_ascii_lowercase().as_str(),
                "1" | "true" | "yes" | "on"
            )
        })
        .unwrap_or(default)
}

fn wbeam_control_port() -> u16 {
    wbeam_config_u16("WBEAM_CONTROL_PORT", 5001)
}

fn wbeam_stream_port() -> u16 {
    wbeam_config_u16("WBEAM_STREAM_PORT", 5000)
}

#[derive(Clone, Debug)]
struct CachedDeviceSnapshot {
    ts_epoch_ms: u128,
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
}

#[derive(Clone, Debug)]
struct SessionLogs {
    stamp: String,
    run_id: String,
    dir: PathBuf,
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

fn virtual_deps_state() -> &'static Mutex<VirtualDepsInstallState> {
    VIRTUAL_DEPS_INSTALL_STATE.get_or_init(|| Mutex::new(VirtualDepsInstallState::default()))
}

fn session_logs() -> &'static SessionLogs {
    SESSION_LOGS.get_or_init(|| {
        let dir = repo_root().join("logs");
        let _ = fs::create_dir_all(&dir);

        let stamp = Command::new("date")
            .args(["+%Y%m%d-%H%M%S"])
            .output()
            .ok()
            .and_then(|out| {
                if out.status.success() {
                    Some(String::from_utf8_lossy(&out.stdout).trim().to_string())
                } else {
                    None
                }
            })
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| {
                SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .map(|d| d.as_secs().to_string())
                    .unwrap_or_else(|_| "0".to_string())
            });

        let day = Command::new("date")
            .args(["+%Y%m%d"])
            .output()
            .ok()
            .and_then(|out| {
                if out.status.success() {
                    Some(String::from_utf8_lossy(&out.stdout).trim().to_string())
                } else {
                    None
                }
            })
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| "unknown".to_string());

        let counter_file = dir.join(format!(".run.{day}.counter"));
        let prev = fs::read_to_string(&counter_file)
            .ok()
            .and_then(|v| v.trim().parse::<u32>().ok())
            .unwrap_or(0);
        let next = prev.saturating_add(1);
        let _ = fs::write(&counter_file, format!("{next}"));
        let run_id = format!("{:04}", next);

        SessionLogs { stamp, run_id, dir }
    })
}

fn ui_log_path() -> PathBuf {
    let logs = session_logs();
    logs.dir
        .join(format!("{}.ui.{}.log", logs.stamp, logs.run_id))
}

fn connect_log_path() -> PathBuf {
    let logs = session_logs();
    logs.dir
        .join(format!("{}.connect.{}.log", logs.stamp, logs.run_id))
}

fn virtual_deps_snapshot() -> VirtualDepsInstallStatus {
    let state = virtual_deps_state()
        .lock()
        .expect("virtual deps state lock");
    VirtualDepsInstallStatus {
        running: state.running,
        done: state.done,
        success: state.success,
        message: state.message.clone(),
        logs: state.logs.iter().cloned().collect(),
    }
}

fn virtual_deps_reset_running() {
    let mut state = virtual_deps_state()
        .lock()
        .expect("virtual deps state lock");
    state.running = true;
    state.done = false;
    state.success = false;
    state.message = "Starting installation...".to_string();
    state.logs.clear();
}

fn virtual_deps_push_log(line: impl Into<String>) {
    const MAX_LOG_LINES: usize = 500;
    let mut state = virtual_deps_state()
        .lock()
        .expect("virtual deps state lock");
    state.logs.push_back(line.into());
    while state.logs.len() > MAX_LOG_LINES {
        let _ = state.logs.pop_front();
    }
}

fn virtual_deps_finish(success: bool, message: String) {
    let mut state = virtual_deps_state()
        .lock()
        .expect("virtual deps state lock");
    state.running = false;
    state.done = true;
    state.success = success;
    state.message = message;
}

#[tauri::command]
fn list_devices_basic() -> DevicesBasicResponse {
    ui_service_log("list_devices_basic", "begin", "");
    let host_apk_version = host_expected_apk_version();
    let daemon_apk_version = host_build_revision_from_health().unwrap_or_default();
    let svc = service_status();
    if !svc.available || !svc.installed || !svc.active {
        ui_service_log(
            "list_devices_basic",
            "warn",
            "service inactive; continuing with adb-only listing",
        );
    }

    let base_stream_port = wbeam_stream_port();
    let control_port = wbeam_control_port();
    let mut port_map = load_device_port_map();
    let mut port_map_changed = false;
    let mut used_ports = HashSet::new();

    match adb_devices() {
        Ok(serials) => {
            let connected: HashSet<String> = serials.iter().cloned().collect();
            let stale = port_map
                .keys()
                .filter(|serial| !connected.contains(*serial))
                .cloned()
                .collect::<Vec<_>>();
            if !stale.is_empty() {
                for serial in stale {
                    port_map.remove(&serial);
                }
                port_map_changed = true;
            }
            let mut devices = Vec::new();
            for (idx, serial) in serials.into_iter().enumerate() {
                let snap = collect_device_snapshot(&serial);
                let apk_matches_host =
                    !host_apk_version.is_empty() && snap.apk_version == host_apk_version;
                let apk_matches_daemon =
                    !daemon_apk_version.is_empty() && snap.apk_version == daemon_apk_version;
                let fallback_port =
                    default_stream_port_for_index(base_stream_port, control_port, idx);
                let preferred_port = port_map
                    .get(&serial)
                    .copied()
                    .filter(|p| *p > 0)
                    .unwrap_or(fallback_port);
                let mut stream_port =
                    pick_unique_stream_port(preferred_port, control_port, &used_ports);
                used_ports.insert(stream_port);
                let stream_state = if svc.active {
                    let (state, resolved_port) = daemon_stream_state_and_port(&serial, Some(stream_port))
                        .or_else(|| daemon_stream_state_and_port(&serial, None))
                        .unwrap_or_else(|| ("unknown".to_string(), stream_port));
                    if resolved_port > 0 && resolved_port != stream_port {
                        used_ports.remove(&stream_port);
                        stream_port =
                            pick_unique_stream_port(resolved_port, control_port, &used_ports);
                        used_ports.insert(stream_port);
                    }
                    if port_map.get(&serial).copied() != Some(stream_port) {
                        port_map.insert(serial.clone(), stream_port);
                        port_map_changed = true;
                    }
                    state
                } else {
                    "unknown".to_string()
                };

                devices.push(DeviceBasic {
                    serial,
                    model: snap.model,
                    platform: snap.platform,
                    os_version: snap.os_version,
                    device_class: snap.device_class,
                    resolution: snap.resolution,
                    max_resolution: snap.max_resolution,
                    api_level: snap.api_level,
                    battery_percent: snap.battery_percent,
                    battery_level: snap.battery_level,
                    battery_charging: snap.battery_charging,
                    apk_installed: snap.apk_installed,
                    apk_version: snap.apk_version,
                    apk_matches_host,
                    apk_matches_daemon,
                    stream_port,
                    stream_state,
                });
            }

            let response = DevicesBasicResponse {
                host_apk_version,
                daemon_apk_version,
                devices,
                error: None,
            };
            if port_map_changed {
                let _ = save_device_port_map(&port_map);
            }
            ui_service_log(
                "list_devices_basic",
                "ok",
                &format!("devices={}", response.devices.len()),
            );
            response
        }
        Err(err) => {
            ui_service_log("list_devices_basic", "error", &err);
            DevicesBasicResponse {
                host_apk_version,
                daemon_apk_version,
                devices: Vec::new(),
                error: Some(err),
            }
        }
    }
}

fn resolve_stream_port_for_serial(serial: &str, requested_stream_port: u16) -> u16 {
    let response = list_devices_basic();
    response
        .devices
        .into_iter()
        .find(|device| device.serial == serial)
        .map(|device| device.stream_port)
        .filter(|port| *port > 0)
        .unwrap_or_else(|| if requested_stream_port > 0 { requested_stream_port } else { 5000 })
}

fn default_stream_port_for_index(base_stream_port: u16, control_port: u16, idx: usize) -> u16 {
    let offset = u16::try_from(idx).unwrap_or(u16::MAX);
    let mut port = base_stream_port.saturating_add(2).saturating_add(offset);
    if port == 0 {
        port = 1;
    }
    if port == control_port {
        port = port.wrapping_add(1);
        if port == 0 {
            port = 1;
        }
    }
    port
}

fn pick_unique_stream_port(start: u16, control_port: u16, used: &HashSet<u16>) -> u16 {
    let mut port = if start == 0 { 1 } else { start };
    for _ in 0..=u16::MAX {
        if port != control_port && !used.contains(&port) {
            return port;
        }
        port = port.wrapping_add(1);
        if port == 0 {
            port = 1;
        }
    }
    if control_port == 1 { 2 } else { 1 }
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

fn save_device_port_map(map: &std::collections::HashMap<String, u16>) -> Result<(), String> {
    let path = repo_root().join(".wbeam_device_ports");
    let mut rows = map
        .iter()
        .map(|(serial, port)| format!("{serial} {port}"))
        .collect::<Vec<_>>();
    rows.sort();
    let mut body = String::new();
    for row in rows {
        body.push_str(&row);
        body.push('\n');
    }
    fs::write(&path, body).map_err(|e| format!("failed to write {}: {e}", path.display()))
}

fn host_expected_apk_version() -> String {
    let file_path = repo_root().join(".wbeam_build_version");
    if let Ok(content) = std::fs::read_to_string(file_path) {
        let v = content.trim().to_string();
        if !v.is_empty() {
            return v;
        }
    }

    if let Some(explicit) = wbeam_config_value("WBEAM_HOST_APK_VERSION") {
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
    let control_port = wbeam_control_port();
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

fn daemon_stream_state_and_port(serial: &str, stream_port: Option<u16>) -> Option<(String, u16)> {
    let control_port = wbeam_control_port();
    let mut url = format!("http://127.0.0.1:{control_port}/v1/status?serial={serial}");
    if let Some(port) = stream_port {
        url.push_str(&format!("&stream_port={port}"));
    }
    let output = Command::new("curl")
        .args(["-fsS", "--max-time", "1", &url])
        .output();
    let Ok(output) = output else { return None; };
    if !output.status.success() {
        return None;
    }
    let body = String::from_utf8_lossy(&output.stdout);
    let json: Value = serde_json::from_str(&body).ok()?;
    let target_serial = json
        .get("target_serial")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .unwrap_or("");
    // Ignore default/non-matching daemon sessions when querying per-device state.
    if target_serial.is_empty() || target_serial != serial {
        return None;
    }
    let state = json
        .get("state")
        .and_then(|v| v.as_str())
        .unwrap_or("unknown");
    let resolved_port = json
        .get("stream_port")
        .and_then(|v| v.as_u64())
        .and_then(|v| u16::try_from(v).ok())
        .or(stream_port)
        .unwrap_or(0);
    Some((state.to_string(), resolved_port))
}

fn normalize_profile_name(value: Option<String>) -> Option<String> {
    let trimmed = value.as_deref().map(str::trim).filter(|v| !v.is_empty())?;
    Some(trimmed.to_string())
}

fn normalize_encoder_name(value: Option<String>) -> Option<String> {
    let trimmed = value
        .as_deref()
        .map(str::trim)
        .map(str::to_lowercase)
        .filter(|v| !v.is_empty())?;
    let normalized = match trimmed.as_str() {
        "h264" => "h264",
        "h265" => "h265",
        "rawpng" | "raw-png" => "rawpng",
        _ => return None,
    };
    Some(normalized.to_string())
}

fn normalize_size_name(value: Option<String>) -> Option<String> {
    let trimmed = value
        .as_deref()
        .map(str::trim)
        .map(str::to_lowercase)
        .filter(|v| !v.is_empty())?;
    let (w_raw, h_raw) = trimmed.split_once('x')?;
    let mut width = w_raw.parse::<u32>().ok()?.clamp(640, 3840);
    let mut height = h_raw.parse::<u32>().ok()?.clamp(360, 2160);
    if width % 2 == 1 {
        width -= 1;
    }
    if height % 2 == 1 {
        height -= 1;
    }
    Some(format!("{width}x{height}"))
}

fn daemon_post_action(
    action: &str,
    serial: &str,
    stream_port: u16,
    display_mode: Option<&str>,
    start_patch: Option<&StartConfigPatch>,
) -> Result<String, String> {
    let control_port = wbeam_control_port();
    let mut url = format!(
        "http://127.0.0.1:{control_port}/v1/{action}?serial={serial}&stream_port={stream_port}"
    );
    if action == "start" {
        if let Some(mode) = display_mode {
            let normalized = mode.trim().to_lowercase();
            let mode_param = match normalized.as_str() {
                "duplicate" => Some("duplicate"),
                "virtual" | "virtual_monitor" => Some("virtual_monitor"),
                "virtual_mirror" | "virtual-duplicate" | "virtual_duplicate" => Some("virtual_mirror"),
                _ => None,
            };
            if let Some(mode_param) = mode_param {
                url.push_str("&display_mode=");
                url.push_str(mode_param);
            }
        }
    }
    let body_json = if action == "start" {
        if let Some(patch) = start_patch {
            if patch.is_empty() {
                None
            } else {
                Some(serde_json::to_string(patch).map_err(|e| format!("serialize start patch failed: {e}"))?)
            }
        } else {
            None
        }
    } else {
        None
    };

    let mut curl_args: Vec<String> = vec![
        "-sS".to_string(),
        "--max-time".to_string(),
        "3".to_string(),
        "-X".to_string(),
        "POST".to_string(),
    ];
    if let Some(ref body) = body_json {
        curl_args.push("-H".to_string());
        curl_args.push("Content-Type: application/json".to_string());
        curl_args.push("--data".to_string());
        curl_args.push(body.clone());
    }
    curl_args.push("-w".to_string());
    curl_args.push("\nHTTP_STATUS:%{http_code}".to_string());
    curl_args.push(url.clone());

    let output = Command::new("curl")
        .args(&curl_args)
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
    ui_service_log(
        "daemon_post_action",
        "http",
        &format!(
            "action={} serial={} port={} status={}",
            action, serial, stream_port, status_code
        ),
    );
    if !status_code.starts_with('2') {
        let trimmed = payload.trim();
        if trimmed.is_empty() {
            return Err(format!(
                "daemon action failed: {action} (http={status_code})"
            ));
        }
        return Err(trimmed.to_string());
    }
    Ok(payload.trim().to_string())
}

fn service_ready_for_device_actions() -> Result<(), String> {
    let svc = service_status();
    if !svc.available {
        return Err("Desktop service control is unavailable on this host.".to_string());
    }
    if !svc.installed {
        return Err("Desktop service is not installed.".to_string());
    }
    if !svc.active {
        return Err("Desktop service is not running.".to_string());
    }
    Ok(())
}

fn connect_log(serial: &str, stream_port: u16, message: &str) {
    let ts = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let path = connect_log_path();
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    if let Ok(mut file) = OpenOptions::new().create(true).append(true).open(path) {
        let _ = writeln!(
            file,
            "[{}][pid={}][serial={}][port={}] {}",
            ts,
            std::process::id(),
            serial,
            stream_port,
            message
        );
    }
}

fn ui_service_log(command: &str, phase: &str, details: &str) {
    let ts = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let path = ui_log_path();
    if let Some(parent) = path.parent() {
        let _ = fs::create_dir_all(parent);
    }
    if let Ok(mut file) = OpenOptions::new().create(true).append(true).open(path) {
        let _ = writeln!(
            file,
            "[{}][pid={}][cmd={}][{}] {}",
            ts,
            std::process::id(),
            command,
            phase,
            details
        );
    }
}

fn adb_api_level(serial: &str) -> Option<u32> {
    adb_cmd(&["-s", serial, "shell", "getprop", "ro.build.version.sdk"])
        .ok()
        .and_then(|v| v.trim().parse::<u32>().ok())
}

fn adb_prepare_connect(serial: &str, stream_port: u16) -> Result<(), String> {
    let control_port = wbeam_control_port();
    connect_log(
        serial,
        stream_port,
        &format!("prepare_connect begin control_port={control_port}"),
    );

    adb_cmd(&["start-server"]).map_err(|e| {
        let msg = format!("adb start-server failed: {e}");
        connect_log(serial, stream_port, &msg);
        msg
    })?;
    connect_log(serial, stream_port, "adb start-server ok");

    // `wait-for-device` can block for a long time and makes UI look frozen.
    // Use short polling with a hard timeout instead.
    let mut ready = false;
    for attempt in 1..=20 {
        let state_txt = adb_cmd(&["-s", serial, "get-state"]).map_err(|e| {
            let msg = format!("adb get-state failed: {e}");
            connect_log(serial, stream_port, &msg);
            msg
        })?;
        connect_log(
            serial,
            stream_port,
            &format!("adb get-state attempt={} state='{}'", attempt, state_txt),
        );
        if state_txt.trim() == "device" {
            ready = true;
            break;
        }
        thread::sleep(Duration::from_millis(150));
    }
    if !ready {
        let msg = "ADB device is not ready after 3s. Reconnect USB / authorize device.".to_string();
        connect_log(serial, stream_port, &msg);
        return Err(msg);
    }

    let rev_stream_primary = adb_cmd(&[
        "-s",
        serial,
        "reverse",
        "tcp:5000",
        &format!("tcp:{stream_port}"),
    ]);
    if let Ok(out) = &rev_stream_primary {
        connect_log(
            serial,
            stream_port,
            &format!("adb reverse stream primary ok 5000->{stream_port} out='{out}'"),
        );
    }
    let rev_control_primary = adb_cmd(&[
        "-s",
        serial,
        "reverse",
        "tcp:5001",
        &format!("tcp:{control_port}"),
    ]);
    if let Ok(out) = &rev_control_primary {
        connect_log(
            serial,
            stream_port,
            &format!("adb reverse control primary ok 5001->{control_port} out='{out}'"),
        );
    }
    if stream_port != 5000 {
        match adb_cmd(&[
            "-s",
            serial,
            "reverse",
            &format!("tcp:{stream_port}"),
            &format!("tcp:{stream_port}"),
        ]) {
            Ok(out) => connect_log(
                serial,
                stream_port,
                &format!("adb reverse stream compat ok {stream_port}->{stream_port} out='{out}'"),
            ),
            Err(err) => connect_log(
                serial,
                stream_port,
                &format!("adb reverse stream compat failed {stream_port}->{stream_port}: {err}"),
            ),
        }
    }
    if control_port != 5001 {
        match adb_cmd(&[
            "-s",
            serial,
            "reverse",
            &format!("tcp:{control_port}"),
            &format!("tcp:{control_port}"),
        ]) {
            Ok(out) => connect_log(
                serial,
                stream_port,
                &format!(
                    "adb reverse control compat ok {control_port}->{control_port} out='{out}'"
                ),
            ),
            Err(err) => connect_log(
                serial,
                stream_port,
                &format!(
                    "adb reverse control compat failed {control_port}->{control_port}: {err}"
                ),
            ),
        }
    }

    if rev_stream_primary.is_err() || rev_control_primary.is_err() {
        let api_level = adb_api_level(serial).unwrap_or(0);
        if api_level > 0 && api_level <= 18 {
            connect_log(
                serial,
                stream_port,
                &format!(
                    "adb reverse failed but continuing for legacy api_level={} (tether/LAN path)",
                    api_level
                ),
            );
        } else {
            let msg = "ADB reverse failed. Run full redeploy or check USB transport permissions."
                .to_string();
            connect_log(serial, stream_port, &msg);
            return Err(msg);
        }
    }

    let launch = adb_cmd(&[
        "-s",
        serial,
        "shell",
        "am",
        "start",
        "-n",
        "com.wbeam/.MainActivity",
    ])
    .map_err(|e| {
        let msg = format!("adb launch failed: {e}");
        connect_log(serial, stream_port, &msg);
        msg
    })?;
    connect_log(serial, stream_port, &format!("adb am start out='{launch}'"));
    if launch.trim().is_empty() {
        let msg = "Failed to launch Android app via adb.".to_string();
        connect_log(serial, stream_port, &msg);
        return Err(msg);
    }

    connect_log(serial, stream_port, "prepare_connect ok");
    Ok(())
}

#[tauri::command]
fn service_status() -> ServiceStatus {
    ui_service_log("service_status", "begin", "");
    if !command_exists("systemctl") {
        let status = ServiceStatus {
            available: false,
            installed: false,
            active: false,
            enabled: false,
            summary: "systemctl not available".to_string(),
        };
        ui_service_log("service_status", "ok", "systemctl missing");
        return status;
    }

    let load_state = systemctl_show_prop("LoadState").unwrap_or_default();
    let active_state = systemctl_state("is-active").unwrap_or_default();
    let sub_state = systemctl_show_prop("SubState").unwrap_or_default();
    let installed = load_state == "loaded" || unit_file_path().exists();
    // Treat transitional systemd states as active from UI perspective to avoid
    // "double click start" behavior when service is still activating.
    let active = matches!(
        active_state.as_str(),
        "active" | "activating" | "reloading"
    );
    let enabled = systemctl_state("is-enabled").as_deref() == Some("enabled");

    let mut summary = format!(
        "installed={} active={} enabled={} state={} sub_state={}",
        installed, active, enabled, active_state, sub_state
    );
    if !active {
        if let Some(lock_hint) = daemon_lock_hint() {
            summary.push_str(&format!("; {lock_hint}"));
        }
    }

    let status = ServiceStatus {
        available: true,
        installed,
        active,
        enabled,
        summary,
    };
    ui_service_log(
        "service_status",
        "ok",
        &format!(
            "installed={} active={} enabled={} state={} sub_state={}",
            status.installed, status.active, status.enabled, active_state, sub_state
        ),
    );
    status
}

fn systemctl_show_prop(prop: &str) -> Option<String> {
    let mut cmd = Command::new("systemctl");
    cmd.args(["--user", "show", "-p", prop, "--value", SERVICE_NAME]);
    apply_systemctl_user_env(&mut cmd);
    cmd.output()
        .ok()
        .and_then(|out| {
            if !out.status.success() {
                return None;
            }
            let v = String::from_utf8_lossy(&out.stdout).trim().to_string();
            if v.is_empty() {
                None
            } else {
                Some(v)
            }
        })
}

fn daemon_lock_hint() -> Option<String> {
    for lock_path in ["/tmp/wbeamd-service-5001.lock", "/tmp/wbeamd.lock"] {
        let pid_text = match fs::read_to_string(lock_path) {
            Ok(v) => v,
            Err(_) => continue,
        };
        let pid = match pid_text.trim().parse::<u32>() {
            Ok(v) => v,
            Err(_) => continue,
        };

        let output = match Command::new("ps")
            .args(["-p", &pid.to_string(), "-o", "comm="])
            .output()
        {
            Ok(v) => v,
            Err(_) => continue,
        };
        if !output.status.success() {
            continue;
        }
        let comm = String::from_utf8_lossy(&output.stdout).trim().to_string();
        if comm.contains("wbeamd-server") {
            return Some(format!("lock held by pid={pid} ({comm})"));
        }
    }
    None
}

fn stop_conflicting_lock_holder() {
    for lock in ["/tmp/wbeamd.lock", "/tmp/wbeamd-service-5001.lock"] {
        let lock_path = PathBuf::from(lock);
        let Ok(pid_text) = fs::read_to_string(&lock_path) else {
            continue;
        };
        let Ok(pid) = pid_text.trim().parse::<u32>() else {
            continue;
        };
        if !process_name_matches(pid, "wbeamd-server") {
            continue;
        }

        let pid_s = pid.to_string();
        let _ = Command::new("kill").args(["-TERM", &pid_s]).status();
        for _ in 0..10 {
            if !process_exists(pid) {
                let _ = fs::remove_file(&lock_path);
                break;
            }
            thread::sleep(Duration::from_millis(100));
        }
        if process_exists(pid) {
            let _ = Command::new("kill").args(["-KILL", &pid_s]).status();
            for _ in 0..10 {
                if !process_exists(pid) {
                    let _ = fs::remove_file(&lock_path);
                    break;
                }
                thread::sleep(Duration::from_millis(100));
            }
        }
    }
}

fn stop_conflicting_port_holder(control_port: u16) {
    let pattern = format!("wbeamd-server --control-port {control_port}");
    let output = Command::new("pgrep").args(["-f", &pattern]).output();
    let Ok(output) = output else {
        return;
    };
    if !output.status.success() {
        return;
    }
    let pids = String::from_utf8_lossy(&output.stdout)
        .lines()
        .filter_map(|line| line.trim().parse::<u32>().ok())
        .collect::<Vec<_>>();
    for pid in pids {
        if !process_name_matches(pid, "wbeamd-server") {
            continue;
        }
        let pid_s = pid.to_string();
        let _ = Command::new("kill").args(["-TERM", &pid_s]).status();
        for _ in 0..10 {
            if !process_exists(pid) {
                break;
            }
            thread::sleep(Duration::from_millis(100));
        }
        if process_exists(pid) {
            let _ = Command::new("kill").args(["-KILL", &pid_s]).status();
        }
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
    ui_service_log("host_probe_brief", "begin", "");
    let control_port = wbeam_control_port();
    let url = format!("http://127.0.0.1:{control_port}/host-probe");
    let output = Command::new("curl")
        .args(["-fsS", "--max-time", "1", &url])
        .output();

    let Ok(output) = output else {
        ui_service_log("host_probe_brief", "error", "curl failed");
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
        ui_service_log("host_probe_brief", "error", "curl non-success");
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
    let probe = HostProbeBrief {
        reachable: true,
        os: json
            .get("os")
            .and_then(|v| v.as_str())
            .unwrap_or("unknown")
            .to_string(),
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
        supported: json
            .get("supported")
            .and_then(|v| v.as_bool())
            .unwrap_or(false),
    };
    ui_service_log(
        "host_probe_brief",
        "ok",
        &format!(
            "reachable={} mode={} supported={}",
            probe.reachable, probe.capture_mode, probe.supported
        ),
    );
    probe
}

#[tauri::command]
fn virtual_doctor(
    serial: Option<String>,
    stream_port: Option<u16>,
) -> Result<VirtualDoctor, String> {
    let control_port = wbeam_control_port();
    let mut url = format!("http://127.0.0.1:{control_port}/v1/virtual/doctor");
    let mut params: Vec<String> = Vec::new();
    if let Some(s) = serial {
        let trimmed = s.trim();
        if !trimmed.is_empty() {
            params.push(format!("serial={trimmed}"));
        }
    }
    if let Some(port) = stream_port {
        if port > 0 {
            params.push(format!("stream_port={port}"));
        }
    }
    if !params.is_empty() {
        url.push('?');
        url.push_str(&params.join("&"));
    }

    let output = Command::new("curl")
        .args(["-fsS", "--max-time", "2", &url])
        .output()
        .map_err(|e| format!("virtual_doctor curl failed: {e}"))?;
    if !output.status.success() {
        return Err("virtual_doctor API unavailable".to_string());
    }
    let body = String::from_utf8_lossy(&output.stdout);
    let json: Value =
        serde_json::from_str(&body).map_err(|e| format!("virtual_doctor invalid json: {e}"))?;
    Ok(VirtualDoctor {
        ok: json.get("ok").and_then(|v| v.as_bool()).unwrap_or(false),
        message: json
            .get("message")
            .and_then(|v| v.as_str())
            .unwrap_or("virtual_doctor returned no message")
            .to_string(),
        actionable: json
            .get("actionable")
            .and_then(|v| v.as_bool())
            .unwrap_or(false),
        host_backend: json
            .get("host_backend")
            .and_then(|v| v.as_str())
            .unwrap_or("unknown")
            .to_string(),
        resolver: json
            .get("resolver")
            .and_then(|v| v.as_str())
            .unwrap_or("none")
            .to_string(),
        missing_deps: json
            .get("missing_deps")
            .and_then(|v| v.as_array())
            .map(|arr| {
                arr.iter()
                    .filter_map(|item| item.as_str().map(ToString::to_string))
                    .collect::<Vec<String>>()
            })
            .unwrap_or_default(),
        install_hint: json
            .get("install_hint")
            .and_then(|v| v.as_str())
            .unwrap_or("")
            .to_string(),
    })
}

#[tauri::command]
fn virtual_install_deps_start() -> Result<String, String> {
    {
        let state = virtual_deps_state()
            .lock()
            .expect("virtual deps state lock");
        if state.running {
            return Ok("already running".to_string());
        }
    }
    virtual_deps_reset_running();
    thread::spawn(run_virtual_install_job);
    Ok("started".to_string())
}

#[tauri::command]
fn virtual_install_deps_status() -> VirtualDepsInstallStatus {
    virtual_deps_snapshot()
}

fn run_virtual_install_job() {
    let root = repo_root();
    let wbeam = root.join("wbeam");
    virtual_deps_push_log(format!("[virtual-deps] root={}", root.display()));

    let is_root = Command::new("id")
        .arg("-u")
        .output()
        .ok()
        .map(|out| String::from_utf8_lossy(&out.stdout).trim().to_string() == "0")
        .unwrap_or(false);

    let mut cmd = if is_root {
        let mut c = Command::new(&wbeam);
        c.args(["deps", "virtual", "install", "--yes"]);
        c
    } else if command_exists("pkexec") {
        virtual_deps_push_log("[virtual-deps] requesting privilege elevation via pkexec");
        let mut c = Command::new("pkexec");
        c.arg(&wbeam);
        c.args(["deps", "virtual", "install", "--yes"]);
        c
    } else {
        virtual_deps_push_log("[virtual-deps] ERROR: pkexec not available and user is not root");
        virtual_deps_finish(
            false,
            "Root privileges required. Install polkit/pkexec or run as root.".to_string(),
        );
        return;
    };

    let child_spawn = cmd
        .current_dir(&root)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn();
    let mut child = match child_spawn {
        Ok(c) => c,
        Err(e) => {
            virtual_deps_finish(false, format!("Failed to start installer: {e}"));
            return;
        }
    };

    let stdout = child.stdout.take();
    let stderr = child.stderr.take();
    let out_thread = stdout.map(|out| {
        thread::spawn(move || {
            let reader = BufReader::new(out);
            for line in reader.lines().map_while(Result::ok) {
                virtual_deps_push_log(line);
            }
        })
    });
    let err_thread = stderr.map(|err| {
        thread::spawn(move || {
            let reader = BufReader::new(err);
            for line in reader.lines().map_while(Result::ok) {
                virtual_deps_push_log(format!("[stderr] {line}"));
            }
        })
    });

    let status = match child.wait() {
        Ok(s) => s,
        Err(e) => {
            virtual_deps_finish(false, format!("Installer wait failed: {e}"));
            return;
        }
    };
    if let Some(h) = out_thread {
        let _ = h.join();
    }
    if let Some(h) = err_thread {
        let _ = h.join();
    }

    if status.success() {
        virtual_deps_finish(true, "Dependencies installed successfully.".to_string());
    } else {
        let code = status
            .code()
            .map(|v| v.to_string())
            .unwrap_or_else(|| "signal".to_string());
        virtual_deps_finish(
            false,
            format!("Dependency installation failed (exit: {code})."),
        );
    }
}

#[tauri::command]
fn device_connect(
    serial: String,
    stream_port: u16,
    display_mode: Option<String>,
    connect_profile: Option<String>,
    connect_encoder: Option<String>,
    connect_size: Option<String>,
) -> Result<String, String> {
    service_ready_for_device_actions()?;
    let mut effective_stream_port = resolve_stream_port_for_serial(&serial, stream_port);
    if effective_stream_port != stream_port {
        ui_service_log(
            "device_connect",
            "port_remap",
            &format!(
                "serial={} requested_port={} effective_port={}",
                serial, stream_port, effective_stream_port
            ),
        );
    }
    if effective_stream_port == 0 {
        effective_stream_port = 5000;
    }
    let start_patch = StartConfigPatch {
        profile: normalize_profile_name(connect_profile),
        encoder: normalize_encoder_name(connect_encoder),
        size: normalize_size_name(connect_size),
    };
    let chosen_mode = display_mode.unwrap_or_else(|| "duplicate".to_string());
    let normalized_mode = chosen_mode.trim().to_lowercase();
    if normalized_mode != "duplicate"
        && normalized_mode != "virtual"
        && normalized_mode != "virtual_monitor"
        && normalized_mode != "virtual_mirror"
        && normalized_mode != "virtual-duplicate"
        && normalized_mode != "virtual_duplicate"
    {
        let msg = format!("Unsupported display mode: {normalized_mode}");
        ui_service_log(
            "device_connect",
            "error",
            &format!(
                "serial={} requested_port={} effective_port={} mode={} err={}",
                serial, stream_port, effective_stream_port, normalized_mode, msg
            ),
        );
        return Err(msg);
    }
    ui_service_log(
        "device_connect",
        "begin",
        &format!(
            "serial={} requested_port={} effective_port={} requested_mode={} profile={} encoder={} size={}",
            serial,
            stream_port,
            effective_stream_port,
            normalized_mode,
            start_patch.profile.as_deref().unwrap_or("-"),
            start_patch.encoder.as_deref().unwrap_or("-"),
            start_patch.size.as_deref().unwrap_or("-")
        ),
    );
    connect_log(&serial, effective_stream_port, "device_connect begin");
    let host_probe = host_probe_brief();
    let is_wayland_portal = host_probe.capture_mode == "wayland_portal";
    let is_virtual_mode = normalized_mode == "virtual"
        || normalized_mode == "virtual_monitor"
        || normalized_mode == "virtual_mirror"
        || normalized_mode == "virtual-duplicate"
        || normalized_mode == "virtual_duplicate";
    let skip_virtual_doctor = is_wayland_portal
        && (normalized_mode == "virtual_monitor"
            || normalized_mode == "virtual_mirror"
            || normalized_mode == "virtual-duplicate"
            || normalized_mode == "virtual_duplicate"
            || normalized_mode == "virtual");
    if is_virtual_mode && !skip_virtual_doctor {
        let doctor = virtual_doctor(Some(serial.clone()), Some(effective_stream_port))?;
        if !doctor.ok {
            let msg = if !doctor.install_hint.trim().is_empty() {
                format!("Virtual monitor unavailable: {}", doctor.install_hint)
            } else {
                format!("Virtual monitor unavailable: {}", doctor.message)
            };
            ui_service_log(
                "device_connect",
                "error",
                &format!(
                    "serial={} requested_port={} effective_port={} mode={} err={}",
                    serial, stream_port, effective_stream_port, normalized_mode, msg
                ),
            );
            connect_log(&serial, effective_stream_port, &msg);
            return Err(msg);
        }
        let resolver = doctor.resolver.as_str();
        let is_real_output = resolver == "linux_x11_real_output";
        let is_monitor_object = resolver == "linux_x11_monitor_object_experimental";
        if !is_real_output && !is_monitor_object {
            let msg = format!(
                "Virtual monitor backend is unsupported for connect. Active resolver={}. {}",
                doctor.resolver, doctor.install_hint
            );
            ui_service_log(
                "device_connect",
                "error",
                &format!(
                    "serial={} requested_port={} effective_port={} mode={} err={}",
                    serial, stream_port, effective_stream_port, normalized_mode, msg
                ),
            );
            connect_log(&serial, effective_stream_port, &msg);
            return Err(msg);
        }
        if is_monitor_object {
            let allow_monitor_object = wbeam_config_bool("WBEAM_X11_ALLOW_MONITOR_OBJECT", false);
            if !allow_monitor_object {
                let msg = "Virtual monitor fallback (xrandr --setmonitor) is experimental and disabled by default. Use a real RandR output backend (EVDI) or explicitly set WBEAM_X11_ALLOW_MONITOR_OBJECT=1.".to_string();
                ui_service_log(
                    "device_connect",
                    "error",
                    &format!(
                        "serial={} requested_port={} effective_port={} mode={} err={}",
                        serial, stream_port, effective_stream_port, normalized_mode, msg
                    ),
                );
                connect_log(&serial, effective_stream_port, &msg);
                return Err(msg);
            }
            connect_log(
                &serial,
                effective_stream_port,
                "virtual monitor fallback active: xrandr --setmonitor (simulated monitor)",
            );
        }
    }
    adb_prepare_connect(&serial, effective_stream_port)?;
    connect_log(
        &serial,
        effective_stream_port,
        "device_connect daemon_post_action start",
    );
    let resp = match daemon_post_action(
        "start",
        &serial,
        effective_stream_port,
        Some(&normalized_mode),
        Some(&start_patch),
    ) {
        Ok(v) => v,
        Err(err) => {
            ui_service_log(
                "device_connect",
                "error",
                &format!(
                    "serial={} requested_port={} effective_port={} err={}",
                    serial, stream_port, effective_stream_port, err
                ),
            );
            connect_log(
                &serial,
                effective_stream_port,
                &format!("device_connect daemon_post_action error='{}'", err),
            );
            return Err(err);
        }
    };
    ui_service_log(
        "device_connect",
        "ok",
        &format!(
            "serial={} requested_port={} effective_port={}",
            serial, stream_port, effective_stream_port
        ),
    );
    connect_log(
        &serial,
        effective_stream_port,
        &format!("device_connect ok response='{}'", resp),
    );
    Ok(resp)
}

#[tauri::command]
fn device_disconnect(serial: String, stream_port: u16) -> Result<String, String> {
    service_ready_for_device_actions()?;
    let effective_stream_port = resolve_stream_port_for_serial(&serial, stream_port);
    ui_service_log(
        "device_disconnect",
        "begin",
        &format!(
            "serial={} requested_port={} effective_port={}",
            serial, stream_port, effective_stream_port
        ),
    );
    let mut res = daemon_post_action("stop", &serial, effective_stream_port, None, None);
    if res.is_err() {
        // Fallback: stream port may have drifted from stale UI mapping.
        let control_port = wbeam_control_port();
        let url = format!("http://127.0.0.1:{control_port}/v1/stop?serial={serial}");
        let output = Command::new("curl")
            .args(["-sS", "--max-time", "3", "-X", "POST", &url])
            .output()
            .map_err(|e| format!("curl failed: {e}"))?;
        if output.status.success() {
            res = Ok(String::from_utf8_lossy(&output.stdout).trim().to_string());
        }
    }
    if res.is_ok() {
        let _ = adb_cmd(&["-s", &serial, "shell", "am", "start", "-n", "com.wbeam/.MainActivity"]);
    }
    match &res {
        Ok(_) => ui_service_log(
            "device_disconnect",
            "ok",
            &format!(
                "serial={} requested_port={} effective_port={}",
                serial, stream_port, effective_stream_port
            ),
        ),
        Err(err) => ui_service_log(
            "device_disconnect",
            "error",
            &format!(
                "serial={} requested_port={} effective_port={} err={}",
                serial, stream_port, effective_stream_port, err
            ),
        ),
    }
    res
}

#[tauri::command]
fn service_install() -> Result<ServiceStatus, String> {
    ensure_systemctl()?;
    let unit_path = unit_file_path();
    if let Some(parent) = unit_path.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("cannot create unit dir: {e}"))?;
    }

    fs::write(&unit_path, service_unit_content())
        .map_err(|e| format!("cannot write unit file: {e}"))?;

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
    ui_service_log("service_start", "begin", "");
    ensure_systemctl()?;
    let unit_path = unit_file_path();
    if !unit_path.exists() {
        ui_service_log("service_start", "error", "service is not installed");
        return Err("service is not installed".to_string());
    }
    let expected = service_unit_content();
    let current = fs::read_to_string(&unit_path).unwrap_or_default();
    if current != expected {
        fs::write(&unit_path, expected).map_err(|e| format!("cannot rewrite unit file: {e}"))?;
        systemctl_user(&["daemon-reload"])?;
    }
    let control_port = wbeam_control_port();
    stop_conflicting_port_holder(control_port);
    stop_conflicting_lock_holder();
    let _ = systemctl_user(&["reset-failed", SERVICE_NAME]);
    if let Err(err) = systemctl_user(&["start", SERVICE_NAME]) {
        ui_service_log("service_start", "error", &err);
        return Err(err);
    }
    // Wait briefly for systemd to settle so UI doesn't require a second click.
    for _ in 0..24 {
        let state = systemctl_state("is-active").unwrap_or_default();
        if matches!(state.as_str(), "active" | "activating" | "reloading") {
            break;
        }
        thread::sleep(Duration::from_millis(250));
    }
    let status = service_status();
    ui_service_log(
        "service_start",
        "ok",
        &format!(
            "installed={} active={} enabled={}",
            status.installed, status.active, status.enabled
        ),
    );
    Ok(status)
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

fn apply_systemctl_user_env(cmd: &mut Command) {
    let uid = Command::new("id")
        .arg("-u")
        .output()
        .ok()
        .filter(|out| out.status.success())
        .and_then(|out| {
            let txt = String::from_utf8_lossy(&out.stdout).trim().to_string();
            if txt.is_empty() {
                None
            } else {
                Some(txt)
            }
        });

    if let Some(uid) = uid {
        let runtime = format!("/run/user/{uid}");
        cmd.env("XDG_RUNTIME_DIR", &runtime);
        cmd.env("DBUS_SESSION_BUS_ADDRESS", format!("unix:path={runtime}/bus"));
    } else {
        cmd.env_remove("XDG_RUNTIME_DIR");
        cmd.env_remove("DBUS_SESSION_BUS_ADDRESS");
    }
    cmd.env_remove("DISPLAY");
    cmd.env_remove("XAUTHORITY");
    cmd.env_remove("WAYLAND_DISPLAY");
}

fn command_exists(name: &str) -> bool {
    Command::new("sh")
        .args(["-c", &format!("command -v {name} >/dev/null 2>&1")])
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

fn systemctl_state(action: &str) -> Option<String> {
    let mut cmd = Command::new("systemctl");
    cmd.args(["--user", action, SERVICE_NAME]);
    apply_systemctl_user_env(&mut cmd);
    cmd.output()
        .ok()
        .map(|out| String::from_utf8_lossy(&out.stdout).trim().to_string())
        .filter(|v| !v.is_empty())
}

fn systemctl_user(args: &[&str]) -> Result<(), String> {
    let mut cmd = Command::new("systemctl");
    cmd.arg("--user").args(args);
    apply_systemctl_user_env(&mut cmd);
    let output = cmd.output().map_err(|e| format!("systemctl failed: {e}"))?;

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
    let root_path = repo_root();
    let _ = ensure_user_wbeam_config(&root_path);
    let daemon_bin_override = wbeam_config_value("WBEAM_DAEMON_BIN");
    let default_runner = root_path
        .join("host/scripts/run_wbeamd.sh")
        .to_string_lossy()
        .to_string();
    let control_port = wbeam_control_port();
    let stream_port = wbeam_stream_port();
    let service_lock_file = format!("/tmp/wbeamd-service-{control_port}.lock");
    let root = root_path.to_string_lossy().to_string();
    let exec_start = if let Some(bin) = daemon_bin_override {
        // Backward compatible override for prebuilt daemon binaries.
        format!("{bin} --control-port {control_port} --stream-port {stream_port} --root {root}")
    } else {
        // Default to repository runner to avoid stale release-binary drift.
        format!("{default_runner} {control_port} {stream_port}")
    };
    format!(
        "[Unit]\nDescription=WBeam Screen Streaming Daemon\nAfter=graphical-session.target\n\n[Service]\nType=simple\nExecStart={exec_start}\nRestart=on-failure\nRestartSec=3\nEnvironment=RUST_LOG=info\nEnvironment=WBEAM_ROOT={root}\nEnvironment=WBEAM_LOCK_FILE={service_lock_file}\n\n[Install]\nWantedBy=default.target\n"
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

fn device_snapshot_cache() -> &'static Mutex<HashMap<String, CachedDeviceSnapshot>> {
    DEVICE_SNAPSHOT_CACHE.get_or_init(|| Mutex::new(HashMap::new()))
}

fn now_epoch_ms() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0)
}

fn collect_device_snapshot(serial: &str) -> CachedDeviceSnapshot {
    const SNAPSHOT_TTL_MS: u128 = 10_000;
    let now_ms = now_epoch_ms();
    if let Ok(cache) = device_snapshot_cache().lock() {
        if let Some(hit) = cache.get(serial) {
            if now_ms.saturating_sub(hit.ts_epoch_ms) <= SNAPSHOT_TTL_MS {
                return hit.clone();
            }
        }
    }

    let model = adb_getprop(serial, "ro.product.model");
    let manufacturer = adb_getprop(serial, "ro.product.manufacturer").unwrap_or_default();
    let os_version = adb_getprop(serial, "ro.build.version.release");
    let platform = detect_platform(&manufacturer);
    let resolution = adb_resolution(serial);
    let density = adb_density(serial);
    let device_class = classify_device(&resolution, density);
    let max_resolution = adb_max_resolution(serial, &resolution);
    let api_level = adb_getprop(serial, "ro.build.version.sdk");
    let (battery_percent, battery_level, battery_charging) = adb_battery_info(serial);
    let apk_path = adb_shell(serial, &["pm", "path", "com.wbeam"]);
    let apk_installed = apk_path
        .as_ref()
        .map(|out| out.contains("package:"))
        .unwrap_or(false);
    let apk_version = if apk_installed {
        adb_apk_version(serial)
    } else {
        String::new()
    };

    let snap = CachedDeviceSnapshot {
        ts_epoch_ms: now_ms,
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
    };

    if let Ok(mut cache) = device_snapshot_cache().lock() {
        cache.insert(serial.to_string(), snap.clone());
    }
    snap
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
    adb_shell(serial, &["getprop", key])
        .ok()
        .filter(|v| !v.is_empty())
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
    let _guard = ADB_CMD_LOCK
        .get_or_init(|| Mutex::new(()))
        .lock()
        .expect("adb lock poisoned");

    let output = run_adb(args)?;

    if !output.status.success() {
        let mut stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
        if should_retry_adb_error(&stderr) {
            let _ = run_adb(&["start-server"]);
            std::thread::sleep(Duration::from_millis(120));
            let retry = run_adb(args)?;
            if retry.status.success() {
                return Ok(String::from_utf8_lossy(&retry.stdout)
                    .replace('\r', "")
                    .trim()
                    .to_string());
            }
            stderr = String::from_utf8_lossy(&retry.stderr).trim().to_string();
        }
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

fn run_adb(args: &[&str]) -> Result<std::process::Output, String> {
    Command::new("adb")
        .args(args)
        .output()
        .map_err(|err| format!("adb failed: {err}"))
}

fn should_retry_adb_error(stderr: &str) -> bool {
    let low = stderr.to_ascii_lowercase();
    low.contains("protocol fault")
        || low.contains("connection reset by peer")
        || low.contains("cannot connect to daemon")
        || low.contains("daemon still not running")
}

fn repo_root() -> PathBuf {
    let raw = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../../");
    fs::canonicalize(&raw).unwrap_or(raw)
}

fn or_unknown(value: Option<String>) -> String {
    value
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| "unknown".to_string())
}

fn loginctl_session_type(session_id: &str) -> Option<String> {
    let output = Command::new("loginctl")
        .args(["show-session", session_id, "-p", "Type", "--value"])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let value = String::from_utf8_lossy(&output.stdout)
        .trim()
        .to_ascii_lowercase();
    if value.is_empty() {
        return None;
    }
    Some(value)
}

fn detect_session_type_for_notice() -> Option<String> {
    if let Some(kind) = std::env::var("XDG_SESSION_TYPE")
        .ok()
        .map(|v| v.trim().to_ascii_lowercase())
        .filter(|v| !v.is_empty())
    {
        return Some(kind);
    }

    if let Some(kind) = std::env::var("XDG_SESSION_ID")
        .ok()
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
        .and_then(|session_id| loginctl_session_type(&session_id))
    {
        return Some(kind);
    }

    if std::env::var_os("WAYLAND_DISPLAY").is_some() {
        return Some("wayland".to_string());
    }
    if std::env::var_os("DISPLAY").is_some() {
        return Some("x11".to_string());
    }
    None
}

fn should_show_x11_startup_notice() -> bool {
    if !wbeam_config_bool("WBEAM_X11_STARTUP_NOTICE", true) {
        return false;
    }
    detect_session_type_for_notice().as_deref() == Some("x11")
}

fn show_x11_startup_notice_window(app: &tauri::AppHandle) {
    const LABEL: &str = "x11-startup-notice";
    if app.get_webview_window(LABEL).is_some() {
        return;
    }
    let _ = WebviewWindowBuilder::new(app, LABEL, WebviewUrl::App("x11-warning.html".into()))
        .title("WBeam - X11 Experimental Notice")
        .inner_size(560.0, 260.0)
        .resizable(false)
        .always_on_top(true)
        .center()
        .build();
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
        .setup(|app| {
            if should_show_x11_startup_notice() {
                show_x11_startup_notice_window(app.handle());
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            ping,
            host_name,
            list_devices_basic,
            service_status,
            host_probe_brief,
            virtual_doctor,
            virtual_install_deps_start,
            virtual_install_deps_status,
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
