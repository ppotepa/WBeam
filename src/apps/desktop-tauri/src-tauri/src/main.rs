#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use serde::Serialize;
use std::fs::OpenOptions;
use std::io::{BufRead, BufReader};
use std::io::Write;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::{Mutex, OnceLock};
use std::thread;
use std::time::{SystemTime, UNIX_EPOCH};
use tauri::Emitter;

#[derive(Clone, Debug, Serialize)]
struct DeviceInfo {
    serial: String,
    adb_state: String,
    model: String,
    device_name: String,
    manufacturer: String,
    api_level: String,
    android_release: String,
    abi: String,
    battery_level: String,
    battery_status: String,
}

#[derive(Clone, Debug, Serialize)]
struct ProbeSnapshot {
    adb_available: bool,
    adb_responsive: bool,
    error: Option<String>,
    devices: Vec<DeviceInfo>,
    probed_at_unix_ms: u64,
}

#[derive(Clone, Debug, Serialize)]
struct DeployResult {
    success: bool,
    exit_code: Option<i32>,
    stdout: String,
    stderr: String,
    script_path: String,
}

#[derive(Clone, Debug, Serialize)]
struct AdbActionResult {
    success: bool,
    exit_code: Option<i32>,
    stdout: String,
    stderr: String,
    action: String,
    serial: String,
}

#[derive(Clone, Debug, Serialize)]
struct UsbUdevEvent {
    action: String,
    devpath: String,
    ts_unix_ms: u64,
}

#[derive(Default)]
struct DeviceProperties {
    manufacturer: String,
    api_level: String,
    android_release: String,
    abi: String,
    battery_level: String,
    battery_status: String,
}

static HOST_CHILD: OnceLock<Mutex<Option<Child>>> = OnceLock::new();

fn host_child_slot() -> &'static Mutex<Option<Child>> {
    HOST_CHILD.get_or_init(|| Mutex::new(None))
}

#[tauri::command]
fn adb_probe_once(source: Option<String>) -> ProbeSnapshot {
    let source = source.unwrap_or_else(|| "unknown".to_string());
    let noisy = source != "poll";
    if noisy {
        log_info(&format!("adb_probe_once start source={source}"));
    }
    let timestamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0);

    if !adb_on_path() {
        let snapshot = ProbeSnapshot {
            adb_available: false,
            adb_responsive: false,
            error: Some("adb not found on PATH".to_string()),
            devices: Vec::new(),
            probed_at_unix_ms: timestamp,
        };
        if noisy {
            log_warn("adb_probe_once adb missing on PATH");
        }
        return snapshot;
    }
    if noisy {
        log_info(&format!("adb binary: {}", adb_binary().to_string_lossy()));
    }

    match adb_devices_with_props() {
        Ok(devices) => {
            if noisy {
                log_info(&format!("adb_probe_once ok devices={}", devices.len()));
            }
            ProbeSnapshot {
                adb_available: true,
                adb_responsive: true,
                error: None,
                devices,
                probed_at_unix_ms: timestamp,
            }
        }
        Err(err) => {
            if noisy {
                log_warn(&format!("adb_probe_once failed: {err}"));
            }
            ProbeSnapshot {
                adb_available: true,
                adb_responsive: false,
                error: Some(err),
                devices: Vec::new(),
                probed_at_unix_ms: timestamp,
            }
        }
    }
}

#[tauri::command]
async fn adb_deploy_all() -> Result<DeployResult, String> {
    tauri::async_runtime::spawn_blocking(run_adb_deploy_all)
        .await
        .map_err(|err| format!("deploy worker failed: {err}"))?
}

#[tauri::command]
async fn adb_device_action(serial: String, action: String) -> Result<AdbActionResult, String> {
    tauri::async_runtime::spawn_blocking(move || run_adb_device_action(&serial, &action))
        .await
        .map_err(|err| format!("adb action worker failed: {err}"))?
}

#[tauri::command]
async fn adb_session_start(serial: String) -> Result<AdbActionResult, String> {
    tauri::async_runtime::spawn_blocking(move || run_adb_session_tunnel(&serial, true))
        .await
        .map_err(|err| format!("session start worker failed: {err}"))?
}

#[tauri::command]
async fn adb_session_stop(serial: String) -> Result<AdbActionResult, String> {
    tauri::async_runtime::spawn_blocking(move || run_adb_session_tunnel(&serial, false))
        .await
        .map_err(|err| format!("session stop worker failed: {err}"))?
}

#[tauri::command]
async fn adb_install_device(serial: String) -> Result<AdbActionResult, String> {
    tauri::async_runtime::spawn_blocking(move || run_wbeam_android_install(&serial))
        .await
        .map_err(|err| format!("device install worker failed: {err}"))?
}

#[tauri::command]
async fn host_status() -> Result<AdbActionResult, String> {
    tauri::async_runtime::spawn_blocking(|| run_host_http_get("host_status", "/v1/status"))
        .await
        .map_err(|err| format!("host status worker failed: {err}"))?
}

#[tauri::command]
async fn host_status_api() -> Result<AdbActionResult, String> {
    tauri::async_runtime::spawn_blocking(|| run_host_http_get("host_status_api", "/v1/status"))
        .await
        .map_err(|err| format!("host status api worker failed: {err}"))?
}

#[tauri::command]
async fn host_up() -> Result<AdbActionResult, String> {
    tauri::async_runtime::spawn_blocking(|| ensure_host_runtime("host_up"))
        .await
        .map_err(|err| format!("host up worker failed: {err}"))?
}

#[tauri::command]
async fn host_down() -> Result<AdbActionResult, String> {
    tauri::async_runtime::spawn_blocking(|| stop_local_host_runtime("host_down"))
        .await
        .map_err(|err| format!("host down worker failed: {err}"))?
}

#[tauri::command]
async fn host_ensure() -> Result<AdbActionResult, String> {
    tauri::async_runtime::spawn_blocking(|| ensure_host_runtime("host_ensure"))
        .await
        .map_err(|err| format!("host ensure worker failed: {err}"))?
}

#[tauri::command]
async fn host_stream_start() -> Result<AdbActionResult, String> {
    tauri::async_runtime::spawn_blocking(|| {
        let _ = ensure_host_runtime("host_stream_start_preflight")?;
        run_host_http_post("host_stream_start", "/v1/start")
    })
        .await
        .map_err(|err| format!("host stream start worker failed: {err}"))?
}

#[tauri::command]
async fn host_stream_stop() -> Result<AdbActionResult, String> {
    tauri::async_runtime::spawn_blocking(|| run_host_http_post("host_stream_stop", "/v1/stop"))
        .await
        .map_err(|err| format!("host stream stop worker failed: {err}"))?
}

#[tauri::command]
async fn host_metrics() -> Result<AdbActionResult, String> {
    tauri::async_runtime::spawn_blocking(|| run_host_http_get("host_metrics", "/v1/metrics"))
        .await
        .map_err(|err| format!("host metrics worker failed: {err}"))?
}

#[tauri::command]
fn ui_log(level: String, message: String) -> Result<(), String> {
    let path = match std::env::var("WBEAM_UI_LOG_PATH") {
        Ok(path) if !path.trim().is_empty() => path,
        _ => return Ok(()),
    };
    let run_tag = std::env::var("WBEAM_LOG_RUN_TAG").unwrap_or_else(|_| "unknown-run".to_string());
    let line = format!("[{}][{}][{}] {}\n", unix_ms_now(), run_tag, level, message);
    append_line_to_file(&path, &line)
}

fn run_adb_deploy_all() -> Result<DeployResult, String> {
    log_info("adb_deploy_all start");
    let devtool = repo_root().join("devtool");
    if !devtool.exists() {
        return Err(format!(
            "devtool not found: {}",
            devtool.to_string_lossy()
        ));
    }

    let output = Command::new(&devtool)
        .arg("deploy")
        .output()
        .map_err(|err| format!("cannot run devtool deploy: {err}"))?;

    let result = DeployResult {
        success: output.status.success(),
        exit_code: output.status.code(),
        stdout: String::from_utf8_lossy(&output.stdout).to_string(),
        stderr: String::from_utf8_lossy(&output.stderr).to_string(),
        script_path: devtool.to_string_lossy().to_string(),
    };
    log_info(&format!(
        "adb_deploy_all done success={} exit={:?}",
        result.success, result.exit_code
    ));
    Ok(result)
}

fn run_adb_session_tunnel(serial: &str, start: bool) -> Result<AdbActionResult, String> {
    log_info(&format!(
        "adb_session_tunnel start serial={} mode={}",
        serial,
        if start { "start" } else { "stop" }
    ));
    let stream_port = std::env::var("WBEAM_STREAM_PORT").unwrap_or_else(|_| "5000".to_string());
    let control_port = std::env::var("WBEAM_CONTROL_PORT").unwrap_or_else(|_| "5001".to_string());

    if let Some(api) = adb_api_level(serial) {
        if api <= 20 {
            let mode = if start { "start" } else { "stop" };
            let note = format!(
                "legacy api={} mode={} -> skipping adb reverse; use USB tethering or same-LAN host path",
                api, mode
            );
            log_warn(&note);
            return Ok(AdbActionResult {
                success: true,
                exit_code: Some(0),
                stdout: note,
                stderr: String::new(),
                action: if start {
                    "session_start_legacy_skip_reverse".to_string()
                } else {
                    "session_stop_legacy_skip_reverse".to_string()
                },
                serial: serial.to_string(),
            });
        }
    }

    let mut logs = Vec::new();
    let mut ok = true;

    for port in [&stream_port, &control_port] {
        let mut cmd = Command::new(adb_binary());
        if start {
            cmd.args(["-s", serial, "reverse", &format!("tcp:{port}"), &format!("tcp:{port}")]);
        } else {
            cmd.args(["-s", serial, "reverse", "--remove", &format!("tcp:{port}")]);
        }
        let output = cmd
            .output()
            .map_err(|err| format!("cannot run adb reverse on port {port}: {err}"))?;
        let stdout = String::from_utf8_lossy(&output.stdout).trim().to_string();
        let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
        if !output.status.success() {
            ok = false;
        }
        logs.push(format!(
            "port {port}: {} {} {}",
            output.status,
            stdout,
            stderr
        ));
    }

    let result = AdbActionResult {
        success: ok,
        exit_code: if ok { Some(0) } else { Some(1) },
        stdout: cap_output(logs.join("\n")),
        stderr: String::new(),
        action: if start {
            "session_start_tunnel".to_string()
        } else {
            "session_stop_tunnel".to_string()
        },
        serial: serial.to_string(),
    };
    log_info(&format!(
        "adb_session_tunnel done serial={} success={} exit={:?}",
        serial, result.success, result.exit_code
    ));
    Ok(result)
}

fn run_wbeam_android_install(serial: &str) -> Result<AdbActionResult, String> {
    log_info(&format!("adb_install_device start serial={serial}"));
    let repo = repo_root();
    let devtool = repo.join("devtool");
    if !devtool.exists() {
        return Err(format!(
            "devtool not found: {}",
            devtool.to_string_lossy()
        ));
    }

    let deploy = Command::new(&devtool)
        .arg("deploy")
        .arg("--serial")
        .arg(serial)
        .current_dir(&repo)
        .output()
        .map_err(|err| format!("cannot run devtool deploy for {serial}: {err}"))?;

    let success = deploy.status.success();
    let stdout = String::from_utf8_lossy(&deploy.stdout).to_string();
    let stderr = String::from_utf8_lossy(&deploy.stderr).to_string();

    let result = AdbActionResult {
        success,
        exit_code: if success { Some(0) } else { deploy.status.code().or(Some(1)) },
        stdout: cap_output(stdout),
        stderr: cap_output(stderr),
        action: "deploy_apk".to_string(),
        serial: serial.to_string(),
    };
    log_info(&format!(
        "adb_install_device done serial={} success={} exit={:?}",
        serial, result.success, result.exit_code
    ));
    Ok(result)
}

fn host_control_port() -> String {
    std::env::var("WBEAM_CONTROL_PORT").unwrap_or_else(|_| "5001".to_string())
}

fn host_stream_port() -> String {
    std::env::var("WBEAM_STREAM_PORT").unwrap_or_else(|_| "5000".to_string())
}

fn host_status_api_ok() -> bool {
    let url = format!("http://127.0.0.1:{}/v1/status", host_control_port());
    let output = Command::new("curl")
        .args(["-sS", "--max-time", "2", "-o", "/dev/null", "-w", "%{http_code}", &url])
        .output();

    let Ok(out) = output else {
        return false;
    };
    if !out.status.success() {
        return false;
    }
    let code = String::from_utf8_lossy(&out.stdout);
    code.starts_with('2')
}

fn host_daemon_bin() -> PathBuf {
    if let Ok(path) = std::env::var("WBEAM_DAEMON_BIN") {
        if !path.trim().is_empty() {
            return PathBuf::from(path);
        }
    }
    repo_root().join("src/host/rust/target/release/wbeamd-server")
}

fn ensure_host_runtime(action: &str) -> Result<AdbActionResult, String> {
    log_info(&format!("host ensure start action={action}"));
    if host_status_api_ok() {
        return Ok(AdbActionResult {
            success: true,
            exit_code: Some(0),
            stdout: "host already reachable".to_string(),
            stderr: String::new(),
            action: action.to_string(),
            serial: String::new(),
        });
    }

    {
        let mut slot = host_child_slot()
            .lock()
            .map_err(|_| "cannot lock host child state".to_string())?;

        if let Some(child) = slot.as_mut() {
            if let Some(status) = child.try_wait().map_err(|err| format!("cannot query host process: {err}"))? {
                log_warn(&format!("host child exited before ensure: {status}"));
                *slot = None;
            }
        }

        if slot.is_none() {
            let bin = host_daemon_bin();
            if !bin.exists() {
                return Err(format!(
                    "host daemon binary not found: {}. Build first: ./devtool project build",
                    bin.to_string_lossy()
                ));
            }

            let child = Command::new(&bin)
                .arg("--control-port")
                .arg(host_control_port())
                .arg("--stream-port")
                .arg(host_stream_port())
                .arg("--root")
                .arg(repo_root())
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .spawn()
                .map_err(|err| format!("cannot spawn host daemon: {err}"))?;
            *slot = Some(child);
        }
    }

    for _ in 0..30 {
        if host_status_api_ok() {
            return Ok(AdbActionResult {
                success: true,
                exit_code: Some(0),
                stdout: "host started by desktop runtime".to_string(),
                stderr: String::new(),
                action: action.to_string(),
                serial: String::new(),
            });
        }
        thread::sleep(std::time::Duration::from_millis(200));
    }

    Err("host daemon did not become ready in time".to_string())
}

fn stop_local_host_runtime(action: &str) -> Result<AdbActionResult, String> {
    let mut stopped = false;
    {
        let mut slot = host_child_slot()
            .lock()
            .map_err(|_| "cannot lock host child state".to_string())?;
        if let Some(mut child) = slot.take() {
            let _ = child.kill();
            let _ = child.wait();
            stopped = true;
        }
    }

    let _ = run_host_http_post("host_stream_stop_local", "/v1/stop");
    Ok(AdbActionResult {
        success: true,
        exit_code: Some(0),
        stdout: if stopped {
            "local host process stopped".to_string()
        } else {
            "no local host process to stop".to_string()
        },
        stderr: String::new(),
        action: action.to_string(),
        serial: String::new(),
    })
}

fn run_host_http_post(action: &str, path: &str) -> Result<AdbActionResult, String> {
    run_host_http_request(action, "POST", path)
}

fn run_host_http_get(action: &str, path: &str) -> Result<AdbActionResult, String> {
    run_host_http_request(action, "GET", path)
}

fn run_host_http_request(action: &str, method: &str, path: &str) -> Result<AdbActionResult, String> {
    log_info(&format!(
        "host http start action={} method={} path={}",
        action, method, path
    ));
    let url = format!("http://127.0.0.1:{}{path}", host_control_port());
    let output = Command::new("curl")
        .args([
            "-sS",
            "-X",
            method,
            "--max-time",
            "8",
            "-w",
            "\n__HTTP__:%{http_code}",
            &url,
        ])
        .output()
        .map_err(|err| format!("cannot run curl for {url}: {err}"))?;

    let stdout_raw = String::from_utf8_lossy(&output.stdout).to_string();
    let stderr = cap_output(String::from_utf8_lossy(&output.stderr).to_string());

    let (body, http_code) = parse_http_trailer(&stdout_raw);
    let http_ok = http_code
        .as_deref()
        .map(|code| code.starts_with('2'))
        .unwrap_or(false);
    let success = output.status.success() && http_ok;
    let exit_code = if success { Some(0) } else { output.status.code().or(Some(1)) };

    let result = AdbActionResult {
        success,
        exit_code,
        stdout: cap_output(match &http_code {
            Some(code) => format!("http_status={code}\n{body}"),
            None => body,
        }),
        stderr,
        action: action.to_string(),
        serial: String::new(),
    };
    log_info(&format!(
        "host http done action={} success={} exit={:?} http={}",
        action,
        result.success,
        result.exit_code,
        http_code.as_deref().unwrap_or("n/a")
    ));
    Ok(result)
}

fn parse_http_trailer(stdout: &str) -> (String, Option<String>) {
    let marker = "\n__HTTP__:";
    if let Some(idx) = stdout.rfind(marker) {
        let body = stdout[..idx].trim().to_string();
        let code = stdout[idx + marker.len()..]
            .lines()
            .next()
            .map(|s| s.trim().to_string())
            .filter(|s| !s.is_empty());
        (body, code)
    } else {
        (stdout.trim().to_string(), None)
    }
}

fn run_adb_device_action(serial: &str, action: &str) -> Result<AdbActionResult, String> {
    log_info(&format!(
        "adb_device_action start serial={} action={}",
        serial, action
    ));
    let mut cmd = Command::new(adb_binary());
    match action {
        "get_state" => {
            cmd.args(["-s", serial, "get-state"]);
        }
        "reverse_default" => {
            cmd.args(["-s", serial, "reverse", "tcp:27183", "tcp:27183"]);
        }
        "forward_default" => {
            cmd.args(["-s", serial, "forward", "tcp:27183", "tcp:27183"]);
        }
        "clear_forwards" => {
            cmd.args(["-s", serial, "forward", "--remove-all"]);
        }
        _ => return Err(format!("unsupported action: {action}")),
    };

    let output = cmd
        .output()
        .map_err(|err| format!("cannot run adb action: {err}"))?;

    let result = AdbActionResult {
        success: output.status.success(),
        exit_code: output.status.code(),
        stdout: cap_output(String::from_utf8_lossy(&output.stdout).to_string()),
        stderr: cap_output(String::from_utf8_lossy(&output.stderr).to_string()),
        action: action.to_string(),
        serial: serial.to_string(),
    };
    log_info(&format!(
        "adb_device_action done serial={} action={} success={} exit={:?}",
        serial, action, result.success, result.exit_code
    ));
    Ok(result)
}

fn cap_output(mut text: String) -> String {
    const MAX: usize = 4000;
    if text.len() > MAX {
        text.truncate(MAX);
        text.push_str("\n...[truncated]");
    }
    text
}

fn repo_root() -> PathBuf {
    // src/apps/desktop-tauri/src-tauri -> repo root
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../../")
}

fn adb_on_path() -> bool {
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
            serial: serial.to_string(),
            adb_state: adb_state.to_string(),
            model,
            device_name,
            manufacturer: props.manufacturer,
            api_level: props.api_level,
            android_release: props.android_release,
            abi: props.abi,
            battery_level: props.battery_level,
            battery_status: props.battery_status,
        });
    }

    devices.sort_by(|a, b| a.serial.cmp(&b.serial));
    Ok(devices)
}

fn device_properties(serial: &str) -> DeviceProperties {
    DeviceProperties {
        manufacturer: adb_getprop(serial, "ro.product.manufacturer"),
        api_level: adb_getprop(serial, "ro.build.version.sdk"),
        android_release: adb_getprop(serial, "ro.build.version.release"),
        abi: adb_getprop(serial, "ro.product.cpu.abi"),
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

fn adb_api_level(serial: &str) -> Option<u32> {
    let raw = adb_getprop(serial, "ro.build.version.sdk");
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    trimmed.parse::<u32>().ok()
}

fn which(bin: &str) -> Option<std::path::PathBuf> {
    let paths = std::env::var_os("PATH")?;
    std::env::split_paths(&paths)
        .map(|p| p.join(bin))
        .find(|candidate| candidate.exists())
}

fn resolve_adb_binary() -> Option<PathBuf> {
    which("adb")
        .or_else(|| which("adb.exe"))
        .or_else(|| {
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

fn unix_ms_now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

fn log_info(msg: &str) {
    eprintln!("[wbeam-tauri][{}][info] {}", unix_ms_now(), msg);
}

fn log_warn(msg: &str) {
    eprintln!("[wbeam-tauri][{}][warn] {}", unix_ms_now(), msg);
}

fn append_line_to_file(path: &str, line: &str) -> Result<(), String> {
    let mut file = OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)
        .map_err(|err| format!("cannot open log file {path}: {err}"))?;
    file.write_all(line.as_bytes())
        .map_err(|err| format!("cannot write log file {path}: {err}"))
}

fn spawn_usb_udev_worker(app: tauri::AppHandle) {
    thread::spawn(move || {
        loop {
            log_info("udev usb worker starting");
            let mut child = match Command::new("udevadm")
                .args(["monitor", "--udev", "--subsystem-match=usb", "--property"])
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .spawn()
            {
                Ok(child) => child,
                Err(err) => {
                    log_warn(&format!("udev usb worker spawn failed: {err}"));
                    // On hosts without udevadm we keep fallback polling only.
                    return;
                }
            };

            if let Some(stdout) = child.stdout.take() {
                let reader = BufReader::new(stdout);
                let mut action = String::new();
                let mut devpath = String::new();
                let mut is_usb_device = false;
                for line in reader.lines() {
                    match line {
                        Ok(raw) => {
                            let trimmed = raw.trim();
                            if trimmed.is_empty() || trimmed.starts_with("UDEV") {
                                if !action.is_empty()
                                    && is_usb_device
                                    && (action == "add" || action == "remove")
                                {
                                    let payload = UsbUdevEvent {
                                        action: action.clone(),
                                        devpath: devpath.clone(),
                                        ts_unix_ms: unix_ms_now(),
                                    };
                                    if let Err(err) = app.emit("usb-udev", payload) {
                                        log_warn(&format!("udev usb emit failed: {err}"));
                                    } else {
                                        log_info(&format!("udev usb event: action={action} devpath={devpath}"));
                                    }
                                }
                                action.clear();
                                devpath.clear();
                                is_usb_device = false;
                                continue;
                            }

                            if let Some(value) = trimmed.strip_prefix("ACTION=") {
                                action = value.to_string();
                            } else if let Some(value) = trimmed.strip_prefix("DEVPATH=") {
                                devpath = value.to_string();
                            } else if let Some(value) = trimmed.strip_prefix("DEVTYPE=") {
                                is_usb_device = value == "usb_device";
                            }
                        }
                        Err(err) => {
                            log_warn(&format!("udev usb read failed: {err}"));
                            break;
                        }
                    }
                }
            }

            match child.wait() {
                Ok(status) => log_warn(&format!("udev usb worker exited: {status}")),
                Err(err) => log_warn(&format!("udev usb wait failed: {err}")),
            }
            thread::sleep(std::time::Duration::from_secs(2));
        }
    });
}

fn main() {
    tauri::Builder::default()
        .setup(|app| {
            spawn_usb_udev_worker(app.handle().clone());
            if let Err(err) = ensure_host_runtime("startup") {
                log_warn(&format!("startup host ensure failed: {err}"));
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            adb_probe_once,
            adb_deploy_all,
            adb_device_action,
            adb_session_start,
            adb_session_stop,
            adb_install_device,
            host_status,
            host_status_api,
            host_up,
            host_down,
            host_ensure,
            host_stream_start,
            host_stream_stop,
            host_metrics,
            ui_log
        ])
        .run(tauri::generate_context!())
        .expect("failed to run tauri app");
}
