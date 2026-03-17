use serde::Serialize;
use serde_json::Value;
use std::collections::{HashMap, HashSet};
use std::fs;
use std::process::Command;
use std::sync::{Mutex, OnceLock};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct DeviceBasic {
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

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct DevicesBasicResponse {
    host_apk_version: String,
    daemon_apk_version: String,
    devices: Vec<DeviceBasic>,
    error: Option<String>,
}

#[derive(Clone, Debug)]
pub(crate) struct CachedDeviceSnapshot {
    pub(crate) ts_epoch_ms: u128,
    pub(crate) model: String,
    pub(crate) platform: String,
    pub(crate) os_version: String,
    pub(crate) device_class: String,
    pub(crate) resolution: String,
    pub(crate) max_resolution: String,
    pub(crate) api_level: String,
    pub(crate) battery_percent: String,
    pub(crate) battery_level: Option<u8>,
    pub(crate) battery_charging: bool,
    pub(crate) apk_installed: bool,
    pub(crate) apk_version: String,
}

/// Static device properties: hardware identity that does not change while
/// the device is connected.  Cached for 5 minutes to avoid re-spawning
/// multiple `adb getprop` calls every poll tick.
#[derive(Clone, Debug)]
struct StaticDeviceInfo {
    ts_epoch_ms: u128,
    model: String,
    platform: String,
    os_version: String,
    device_class: String,
    resolution: String,
    max_resolution: String,
    api_level: String,
}

static ADB_CMD_LOCK: OnceLock<Mutex<()>> = OnceLock::new();
static DEVICE_SNAPSHOT_CACHE: OnceLock<Mutex<HashMap<String, CachedDeviceSnapshot>>> =
    OnceLock::new();
static DEVICE_STATIC_CACHE: OnceLock<Mutex<HashMap<String, StaticDeviceInfo>>> = OnceLock::new();

/// Short-lived cache for the full device list response.
/// Avoids calling `adb devices` + per-device probes on every frontend poll tick.
const DEVICES_BASIC_CACHE_TTL_MS: u128 = 2_000;
static DEVICES_BASIC_CACHE: OnceLock<Mutex<Option<(u128, DevicesBasicResponse)>>> =
    OnceLock::new();

fn devices_basic_cache() -> &'static Mutex<Option<(u128, DevicesBasicResponse)>> {
    DEVICES_BASIC_CACHE.get_or_init(|| Mutex::new(None))
}

/// Invalidate the devices cache so the next call re-probes ADB.
pub(crate) fn invalidate_devices_cache() {
    if let Ok(mut c) = devices_basic_cache().lock() {
        *c = None;
    }
}

pub(crate) fn device_snapshot_cache() -> &'static Mutex<HashMap<String, CachedDeviceSnapshot>> {
    DEVICE_SNAPSHOT_CACHE.get_or_init(|| Mutex::new(HashMap::new()))
}

fn device_static_cache() -> &'static Mutex<HashMap<String, StaticDeviceInfo>> {
    DEVICE_STATIC_CACHE.get_or_init(|| Mutex::new(HashMap::new()))
}

pub(crate) fn now_epoch_ms() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0)
}

/// Fetch (or return cached) static device properties.
///
/// Static properties (model, resolution, API level…) never change while a
/// device remains connected, so a 5-minute TTL is safe and dramatically
/// reduces the number of `adb getprop` subprocess calls per poll cycle.
fn collect_static_info(serial: &str, now_ms: u128) -> StaticDeviceInfo {
    const STATIC_TTL_MS: u128 = 300_000; // 5 minutes
    if let Ok(cache) = device_static_cache().lock() {
        if let Some(hit) = cache.get(serial) {
            if now_ms.saturating_sub(hit.ts_epoch_ms) <= STATIC_TTL_MS {
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
    let info = StaticDeviceInfo {
        ts_epoch_ms: now_ms,
        model: crate::or_unknown(model),
        platform,
        os_version: crate::or_unknown(os_version),
        device_class,
        resolution: crate::or_unknown(Some(resolution)),
        max_resolution,
        api_level: crate::or_unknown(api_level),
    };
    if let Ok(mut cache) = device_static_cache().lock() {
        cache.insert(serial.to_string(), info.clone());
    }
    info
}

pub(crate) fn collect_device_snapshot(serial: &str) -> CachedDeviceSnapshot {
    // Dynamic tier: battery + APK info, refreshed every 10 seconds.
    const DYNAMIC_TTL_MS: u128 = 10_000;
    let now_ms = now_epoch_ms();
    if let Ok(cache) = device_snapshot_cache().lock() {
        if let Some(hit) = cache.get(serial) {
            if now_ms.saturating_sub(hit.ts_epoch_ms) <= DYNAMIC_TTL_MS {
                return hit.clone();
            }
        }
    }

    // Static fields come from the long-TTL tier; dynamic fields are re-probed.
    let static_info = collect_static_info(serial, now_ms);
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
        model: static_info.model,
        platform: static_info.platform,
        os_version: static_info.os_version,
        device_class: static_info.device_class,
        resolution: static_info.resolution,
        max_resolution: static_info.max_resolution,
        api_level: static_info.api_level,
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

pub(crate) fn adb_devices() -> Result<Vec<String>, String> {
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

pub(crate) fn adb_getprop(serial: &str, key: &str) -> Option<String> {
    adb_shell(serial, &["getprop", key])
        .ok()
        .filter(|v| !v.is_empty())
}

pub(crate) fn adb_resolution(serial: &str) -> String {
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

pub(crate) fn adb_density(serial: &str) -> Option<u32> {
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

pub(crate) fn parse_resolution_dims(value: &str) -> Option<(u32, u32)> {
    let trimmed = value.trim().to_lowercase().replace(' ', "");
    let parts: Vec<&str> = trimmed.split('x').collect();
    if parts.len() != 2 {
        return None;
    }
    let w = parts[0].parse::<u32>().ok()?;
    let h = parts[1].parse::<u32>().ok()?;
    Some((w, h))
}

pub(crate) fn classify_device(resolution: &str, density: Option<u32>) -> String {
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

pub(crate) fn adb_max_resolution(serial: &str, fallback: &str) -> String {
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

pub(crate) fn adb_battery_info(serial: &str) -> (String, Option<u8>, bool) {
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

pub(crate) fn adb_apk_version(serial: &str) -> String {
    let dumpsys = adb_shell(serial, &["dumpsys", "package", "com.wbeam"]).unwrap_or_default();
    for line in dumpsys.lines() {
        let clean = line.trim();
        if let Some(val) = clean.strip_prefix("versionName=") {
            return val.trim().to_string();
        }
    }
    String::new()
}

pub(crate) fn adb_shell(serial: &str, args: &[&str]) -> Result<String, String> {
    let mut cmd = vec!["-s", serial, "shell"];
    cmd.extend_from_slice(args);
    adb_cmd(&cmd)
}

// Separate lock so that only one thread at a time runs the adb-server restart
// sequence, without holding ADB_CMD_LOCK during the 120ms sleep.
static ADB_RESTART_LOCK: OnceLock<Mutex<()>> = OnceLock::new();

pub(crate) fn adb_cmd(args: &[&str]) -> Result<String, String> {
    // First attempt: hold the global lock only for this one adb invocation.
    let needs_restart = {
        let _guard = ADB_CMD_LOCK
            .get_or_init(|| Mutex::new(()))
            .lock()
            .expect("adb lock poisoned");
        let output = run_adb(args)?;
        if output.status.success() {
            return Ok(String::from_utf8_lossy(&output.stdout)
                .replace('\r', "")
                .trim()
                .to_string());
        }
        let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
        if should_retry_adb_error(&stderr) {
            true
        } else if stderr.is_empty() {
            return Err(format!("adb exited with {}", output.status));
        } else {
            return Err(stderr);
        }
    }; // ADB_CMD_LOCK released here

    if needs_restart {
        // Serialise the adb-server restart (rare path) without holding
        // ADB_CMD_LOCK during the sleep — unrelated adb calls are not blocked.
        let _restart_guard = ADB_RESTART_LOCK
            .get_or_init(|| Mutex::new(()))
            .lock()
            .expect("adb restart lock poisoned");
        let _ = run_adb(&["start-server"]);
        std::thread::sleep(Duration::from_millis(120));

        // Retry under the main lock.
        let _guard = ADB_CMD_LOCK
            .get_or_init(|| Mutex::new(()))
            .lock()
            .expect("adb lock poisoned");
        let retry = run_adb(args)?;
        if retry.status.success() {
            return Ok(String::from_utf8_lossy(&retry.stdout)
                .replace('\r', "")
                .trim()
                .to_string());
        }
        let stderr = String::from_utf8_lossy(&retry.stderr).trim().to_string();
        if stderr.is_empty() {
            return Err(format!("adb exited with {}", retry.status));
        }
        return Err(stderr);
    }

    unreachable!()
}

pub(crate) fn run_adb(args: &[&str]) -> Result<std::process::Output, String> {
    Command::new("adb")
        .args(args)
        .output()
        .map_err(|err| format!("adb failed: {err}"))
}

pub(crate) fn should_retry_adb_error(stderr: &str) -> bool {
    let low = stderr.to_ascii_lowercase();
    low.contains("protocol fault")
        || low.contains("connection reset by peer")
        || low.contains("cannot connect to daemon")
        || low.contains("daemon still not running")
}

pub(crate) fn detect_platform(manufacturer: &str) -> String {
    let m = manufacturer.to_lowercase();
    if m.contains("apple") {
        "iOS".to_string()
    } else {
        "Android".to_string()
    }
}

pub(crate) fn resolve_stream_port_for_serial(serial: &str, requested_stream_port: u16) -> u16 {
    let response = list_devices_basic();
    response
        .devices
        .into_iter()
        .find(|device| device.serial == serial)
        .map(|device| device.stream_port)
        .filter(|port| *port > 0)
        .unwrap_or_else(|| if requested_stream_port > 0 { requested_stream_port } else { 5000 })
}

pub(crate) fn default_stream_port_for_index(
    base_stream_port: u16,
    control_port: u16,
    idx: usize,
) -> u16 {
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

pub(crate) fn pick_unique_stream_port(start: u16, control_port: u16, used: &HashSet<u16>) -> u16 {
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

pub(crate) fn load_device_port_map() -> HashMap<String, u16> {
    let path = crate::repo_root().join(".wbeam_device_ports");
    let mut map = HashMap::new();
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

pub(crate) fn save_device_port_map(map: &HashMap<String, u16>) -> Result<(), String> {
    let path = crate::repo_root().join(".wbeam_device_ports");
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

pub(crate) fn host_expected_apk_version() -> String {
    let file_path = crate::repo_root().join(".wbeam_build_version");
    if let Ok(content) = std::fs::read_to_string(file_path) {
        let v = content.trim().to_string();
        if !v.is_empty() {
            return v;
        }
    }

    if let Some(explicit) = crate::wbeam_config_value("WBEAM_HOST_APK_VERSION") {
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

    if let Some(from_repo) = repo_build_revision() {
        return from_repo;
    }

    String::new()
}

fn repo_build_revision() -> Option<String> {
    let base = crate::wbeam_config_value("WBEAM_VERSION_BASE")
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| "0.1.2".to_string());
    let repo_root = crate::repo_root();
    let output = Command::new("git")
        .arg("-C")
        .arg(&repo_root)
        .args(["rev-parse", "--short=5", "HEAD"])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }

    let rev = String::from_utf8(output.stdout).ok()?.trim().to_string();
    if rev.is_empty() {
        None
    } else {
        Some(format!("{base}.{rev}"))
    }
}

pub(crate) fn host_build_revision_from_health() -> Option<String> {
    let control_port = crate::wbeam_control_port();
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

pub(crate) fn daemon_stream_state_and_port(
    serial: &str,
    stream_port: Option<u16>,
) -> Option<(String, u16)> {
    let control_port = crate::wbeam_control_port();
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

fn wait_for_device_ready(serial: &str, stream_port: u16) -> Result<(), String> {
    for attempt in 1..=20 {
        let state_txt = adb_cmd(&["-s", serial, "get-state"]).map_err(|e| {
            let msg = format!("adb get-state failed: {e}");
            crate::connect_log(serial, stream_port, &msg);
            msg
        })?;
        crate::connect_log(
            serial,
            stream_port,
            &format!("adb get-state attempt={} state='{}'", attempt, state_txt),
        );
        if state_txt.trim() == "device" {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(150));
    }
    let msg = "ADB device is not ready after 3s. Reconnect USB / authorize device.".to_string();
    crate::connect_log(serial, stream_port, &msg);
    Err(msg)
}

fn log_reverse_result(serial: &str, stream_port: u16, success_msg: String, failure_msg: String, result: Result<String, String>) {
    match result {
        Ok(out) => crate::connect_log(serial, stream_port, &format!("{success_msg} out='{out}'")),
        Err(err) => crate::connect_log(serial, stream_port, &format!("{failure_msg}: {err}")),
    }
}

pub(crate) fn adb_prepare_connect(serial: &str, stream_port: u16) -> Result<(), String> {
    const LEGACY_DEVICE_STREAM_PORT: u16 = 5002;
    let control_port = crate::wbeam_control_port();
    crate::connect_log(
        serial,
        stream_port,
        &format!("prepare_connect begin control_port={control_port}"),
    );

    adb_cmd(&["start-server"]).map_err(|e| {
        let msg = format!("adb start-server failed: {e}");
        crate::connect_log(serial, stream_port, &msg);
        msg
    })?;
    crate::connect_log(serial, stream_port, "adb start-server ok");

    // `wait-for-device` can block for a long time and makes UI look frozen.
    // Use short polling with a hard timeout instead.
    wait_for_device_ready(serial, stream_port)?;

    let rev_stream_primary = adb_cmd(&[
        "-s",
        serial,
        "reverse",
        "tcp:5000",
        &format!("tcp:{stream_port}"),
    ]);
    if let Ok(out) = &rev_stream_primary {
        crate::connect_log(
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
        crate::connect_log(
            serial,
            stream_port,
            &format!("adb reverse control primary ok 5001->{control_port} out='{out}'"),
        );
    }
    if stream_port != 5000 {
        log_reverse_result(
            serial,
            stream_port,
            format!("adb reverse stream compat ok {stream_port}->{stream_port}"),
            format!("adb reverse stream compat failed {stream_port}->{stream_port}"),
            adb_cmd(&[
            "-s",
            serial,
            "reverse",
            &format!("tcp:{stream_port}"),
            &format!("tcp:{stream_port}"),
        ]),
        );
    }
    if stream_port != LEGACY_DEVICE_STREAM_PORT {
        log_reverse_result(
            serial,
            stream_port,
            format!("adb reverse stream legacy compat ok {LEGACY_DEVICE_STREAM_PORT}->{stream_port}"),
            format!("adb reverse stream legacy compat failed {LEGACY_DEVICE_STREAM_PORT}->{stream_port}"),
            adb_cmd(&[
            "-s",
            serial,
            "reverse",
            &format!("tcp:{LEGACY_DEVICE_STREAM_PORT}"),
            &format!("tcp:{stream_port}"),
        ]),
        );
    }
    if control_port != 5001 {
        log_reverse_result(
            serial,
            stream_port,
            format!("adb reverse control compat ok {control_port}->{control_port}"),
            format!("adb reverse control compat failed {control_port}->{control_port}"),
            adb_cmd(&[
            "-s",
            serial,
            "reverse",
            &format!("tcp:{control_port}"),
            &format!("tcp:{control_port}"),
        ]),
        );
    }

    if rev_stream_primary.is_err() || rev_control_primary.is_err() {
        let api_level = adb_api_level(serial).unwrap_or(0);
        if api_level > 0 && api_level <= 18 {
            crate::connect_log(
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
            crate::connect_log(serial, stream_port, &msg);
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
        crate::connect_log(serial, stream_port, &msg);
        msg
    })?;
    crate::connect_log(serial, stream_port, &format!("adb am start out='{launch}'"));
    if launch.trim().is_empty() {
        let msg = "Failed to launch Android app via adb.".to_string();
        crate::connect_log(serial, stream_port, &msg);
        return Err(msg);
    }

    crate::connect_log(serial, stream_port, "prepare_connect ok");
    Ok(())
}

pub(crate) fn adb_api_level(serial: &str) -> Option<u32> {
    adb_cmd(&["-s", serial, "shell", "getprop", "ro.build.version.sdk"])
        .ok()
        .and_then(|v| v.trim().parse::<u32>().ok())
}

fn cached_devices_basic_response(now: u128) -> Option<DevicesBasicResponse> {
    let guard = devices_basic_cache().lock().ok()?;
    let (ts, cached) = guard.as_ref()?;
    if now.saturating_sub(*ts) < DEVICES_BASIC_CACHE_TTL_MS {
        crate::ui_service_log("list_devices_basic", "cache-hit", "");
        return Some(cached.clone());
    }
    None
}

fn remove_stale_port_map_entries(
    port_map: &mut HashMap<String, u16>,
    connected: &HashSet<String>,
) -> bool {
    let stale = port_map
        .keys()
        .filter(|serial| !connected.contains(*serial))
        .cloned()
        .collect::<Vec<_>>();
    if stale.is_empty() {
        return false;
    }
    for serial in stale {
        port_map.remove(&serial);
    }
    true
}

fn resolve_device_stream(
    serial: &str,
    idx: usize,
    svc_active: bool,
    base_stream_port: u16,
    control_port: u16,
    port_map: &mut HashMap<String, u16>,
    used_ports: &mut HashSet<u16>,
) -> (u16, String, bool) {
    let fallback_port = default_stream_port_for_index(base_stream_port, control_port, idx);
    let preferred_port = port_map
        .get(serial)
        .copied()
        .filter(|p| *p > 0)
        .unwrap_or(fallback_port);
    let mut stream_port = pick_unique_stream_port(preferred_port, control_port, used_ports);
    used_ports.insert(stream_port);

    if !svc_active {
        return (stream_port, "unknown".to_string(), false);
    }

    let (state, resolved_port) = daemon_stream_state_and_port(serial, Some(stream_port))
        .or_else(|| daemon_stream_state_and_port(serial, None))
        .unwrap_or_else(|| ("unknown".to_string(), stream_port));
    if resolved_port > 0 && resolved_port != stream_port {
        used_ports.remove(&stream_port);
        stream_port = pick_unique_stream_port(resolved_port, control_port, used_ports);
        used_ports.insert(stream_port);
    }
    let mut port_map_changed = false;
    if port_map.get(serial).copied() != Some(stream_port) {
        port_map.insert(serial.to_string(), stream_port);
        port_map_changed = true;
    }
    (stream_port, state, port_map_changed)
}

#[tauri::command]
pub(crate) fn list_devices_basic() -> DevicesBasicResponse {
    let now = now_epoch_ms();
    if let Some(cached) = cached_devices_basic_response(now) {
        return cached;
    }

    crate::ui_service_log("list_devices_basic", "begin", "");
    let host_apk_version = host_expected_apk_version();
    let daemon_apk_version = host_build_revision_from_health().unwrap_or_default();
    let svc = crate::service::service_status();
    if !svc.available || !svc.installed || !svc.active {
        crate::ui_service_log(
            "list_devices_basic",
            "warn",
            "service inactive; continuing with adb-only listing",
        );
    }

    let base_stream_port = crate::wbeam_stream_port();
    let control_port = crate::wbeam_control_port();
    let mut port_map = load_device_port_map();
    let mut port_map_changed = false;
    let mut used_ports = HashSet::new();

    let serials = match adb_devices() {
        Ok(v) => v,
        Err(err) => {
            crate::ui_service_log("list_devices_basic", "error", &err);
            return DevicesBasicResponse {
                host_apk_version,
                daemon_apk_version,
                devices: Vec::new(),
                error: Some(err),
            };
        }
    };
    let connected: HashSet<String> = serials.iter().cloned().collect();
    if remove_stale_port_map_entries(&mut port_map, &connected) {
        port_map_changed = true;
    }

    let mut devices = Vec::new();
    for (idx, serial) in serials.into_iter().enumerate() {
        let snap = collect_device_snapshot(&serial);
        let apk_matches_host = !host_apk_version.is_empty() && snap.apk_version == host_apk_version;
        let apk_matches_daemon =
            !daemon_apk_version.is_empty() && snap.apk_version == daemon_apk_version;
        let (stream_port, stream_state, did_change) = resolve_device_stream(
            &serial,
            idx,
            svc.active,
            base_stream_port,
            control_port,
            &mut port_map,
            &mut used_ports,
        );
        port_map_changed |= did_change;

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
    crate::ui_service_log(
        "list_devices_basic",
        "ok",
        &format!("devices={}", response.devices.len()),
    );
    if let Ok(mut guard) = devices_basic_cache().lock() {
        *guard = Some((now_epoch_ms(), response.clone()));
    }
    response
}
