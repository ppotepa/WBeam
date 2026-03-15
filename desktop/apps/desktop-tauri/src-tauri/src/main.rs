#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod adb;
mod profiles;
mod service;
mod virtual_monitor;

use crate::adb::{adb_cmd, adb_prepare_connect, list_devices_basic, resolve_stream_port_for_serial};
use crate::profiles::{find_trained_profile, list_trained_profiles, migrate_legacy_profiles_once};
use crate::service::{
    service_install, service_ready_for_device_actions, service_start, service_status, service_stop,
    service_uninstall,
};
use crate::virtual_monitor::{
    virtual_doctor, virtual_install_deps_start, virtual_install_deps_status,
};

use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::HashMap;
use std::fs;
use std::fs::OpenOptions;
use std::io::Write;
use std::path::PathBuf;
use std::process::Command;
use std::sync::OnceLock;
use std::time::{SystemTime, UNIX_EPOCH};
use tauri::{Manager, WebviewUrl, WebviewWindowBuilder};

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

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub(crate) struct StartConfigPatch {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) profile: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) encoder: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) size: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) fps: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) bitrate_kbps: Option<u32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub(crate) intra_only: Option<bool>,
}

impl StartConfigPatch {
    fn is_empty(&self) -> bool {
        self.profile.is_none()
            && self.encoder.is_none()
            && self.size.is_none()
            && self.fps.is_none()
            && self.bitrate_kbps.is_none()
            && self.intra_only.is_none()
    }
}

#[derive(Clone, Debug)]
struct SessionLogs {
    stamp: String,
    run_id: String,
    dir: PathBuf,
}

static SESSION_LOGS: OnceLock<SessionLogs> = OnceLock::new();
static WBEAM_CONFIG_CACHE: OnceLock<HashMap<String, String>> = OnceLock::new();

pub(crate) fn wbeam_user_config_path() -> Option<PathBuf> {
    Some(wbeam_user_config_dir()?.join("wbeam.conf"))
}

pub(crate) fn wbeam_user_config_dir() -> Option<PathBuf> {
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
    Some(base.join("wbeam"))
}

pub(crate) fn ensure_user_wbeam_config(root: &PathBuf) -> Option<PathBuf> {
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

pub(crate) fn wbeam_config_value(key: &str) -> Option<String> {
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

pub(crate) fn wbeam_config_u16(key: &str, default: u16) -> u16 {
    wbeam_config_value(key)
        .and_then(|v| v.parse::<u16>().ok())
        .unwrap_or(default)
}

pub(crate) fn wbeam_config_bool(key: &str, default: bool) -> bool {
    wbeam_config_value(key)
        .map(|v| {
            matches!(
                v.trim().to_ascii_lowercase().as_str(),
                "1" | "true" | "yes" | "on"
            )
        })
        .unwrap_or(default)
}

pub(crate) fn wbeam_control_port() -> u16 {
    wbeam_config_u16("WBEAM_CONTROL_PORT", 5001)
}

pub(crate) fn wbeam_stream_port() -> u16 {
    wbeam_config_u16("WBEAM_STREAM_PORT", 5000)
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

pub(crate) fn connect_log(serial: &str, stream_port: u16, message: &str) {
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

pub(crate) fn ui_service_log(command: &str, phase: &str, details: &str) {
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

fn normalize_profile_selector(value: Option<String>) -> Option<String> {
    value
        .as_deref()
        .map(str::trim)
        .filter(|v| !v.is_empty())
        .map(str::to_string)
}

fn daemon_post_action(
    action: &str,
    serial: &str,
    stream_port: u16,
    display_mode: Option<&str>,
    start_patch: Option<&StartConfigPatch>,
    capture_backend: Option<&str>,
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
        if let Some(backend) = capture_backend {
            let b = backend.trim().to_lowercase();
            if !b.is_empty() && b != "auto" {
                url.push_str("&capture_backend=");
                url.push_str(&b);
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
fn device_connect(
    serial: String,
    stream_port: u16,
    display_mode: Option<String>,
    connect_encoder: Option<String>,
    connect_size: Option<String>,
    connect_profile_name: Option<String>,
    connect_capture_backend: Option<String>,
) -> Result<String, String> {
    service_ready_for_device_actions()?;
    crate::adb::invalidate_devices_cache();
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
    let selected_profile = normalize_profile_selector(connect_profile_name);
    let mut start_patch = StartConfigPatch {
        profile: None,
        encoder: normalize_encoder_name(connect_encoder),
        size: normalize_size_name(connect_size),
        fps: None,
        bitrate_kbps: None,
        intra_only: None,
    };
    if let Some(selector) = selected_profile.as_deref() {
        let profile_backend = connect_capture_backend.as_deref().unwrap_or("wayland_portal");
        let profile = find_trained_profile(selector, profile_backend)?
            .ok_or_else(|| format!("trained profile not found: {selector}"))?;
        start_patch.encoder = normalize_encoder_name(Some(profile.encoder.clone()));
        if start_patch.encoder.is_none() {
            return Err(format!(
                "trained profile '{}' has unsupported encoder '{}'",
                profile.name, profile.encoder
            ));
        }
        start_patch.fps = Some(profile.fps.max(1));
        start_patch.bitrate_kbps = Some(profile.bitrate_kbps.max(100));
        start_patch.intra_only = Some(profile.intra_only);
    }
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
            selected_profile.as_deref().unwrap_or("-"),
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
        connect_capture_backend.as_deref(),
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
    crate::adb::invalidate_devices_cache();
    let effective_stream_port = resolve_stream_port_for_serial(&serial, stream_port);
    ui_service_log(
        "device_disconnect",
        "begin",
        &format!(
            "serial={} requested_port={} effective_port={}",
            serial, stream_port, effective_stream_port
        ),
    );
    let mut res = daemon_post_action("stop", &serial, effective_stream_port, None, None, None);
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

pub(crate) fn repo_root() -> PathBuf {
    if let Ok(root) = std::env::var("WBEAM_ROOT") {
        let candidate = PathBuf::from(root);
        if candidate.exists() {
            return fs::canonicalize(&candidate).unwrap_or(candidate);
        }
    }

    let packaged_root = PathBuf::from("/usr/share/wbeam");
    if packaged_root.exists() {
        return packaged_root;
    }

    let dev_root = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../../");
    fs::canonicalize(&dev_root).unwrap_or(dev_root)
}

pub(crate) fn or_unknown(value: Option<String>) -> String {
    value
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| "unknown".to_string())
}

fn detect_session_type_for_notice() -> Option<String> {
    if let Some(kind) = std::env::var("XDG_SESSION_TYPE")
        .ok()
        .map(|v| v.trim().to_ascii_lowercase())
        .filter(|v| !v.is_empty())
    {
        return Some(kind);
    }
    if let Ok(session_id) = std::env::var("XDG_SESSION_ID") {
        let trimmed = session_id.trim();
        if !trimmed.is_empty() {
            let output = Command::new("loginctl")
                .args(["show-session", trimmed, "-p", "Type", "--value"])
                .output();
            if let Ok(output) = output {
                if output.status.success() {
                    let value = String::from_utf8_lossy(&output.stdout)
                        .trim()
                        .to_ascii_lowercase();
                    if !value.is_empty() {
                        return Some(value);
                    }
                }
            }
        }
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
    migrate_legacy_profiles_once();
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
            list_trained_profiles,
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
