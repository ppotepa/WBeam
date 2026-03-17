use serde::Serialize;
use std::fs;
use std::path::PathBuf;
use std::process::Command;
use std::thread;
use std::time::Duration;

pub(crate) const SERVICE_NAME: &str = "wbeam-daemon";

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ServiceStatus {
    pub(crate) available: bool,
    pub(crate) installed: bool,
    pub(crate) active: bool,
    pub(crate) enabled: bool,
    pub(crate) summary: String,
}

pub(crate) fn service_ready_for_device_actions() -> Result<(), String> {
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

#[tauri::command]
pub(crate) fn service_status() -> ServiceStatus {
    crate::ui_service_log("service_status", "begin", "");
    if !command_exists("systemctl") {
        let status = ServiceStatus {
            available: false,
            installed: false,
            active: false,
            enabled: false,
            summary: "systemctl not available".to_string(),
        };
        crate::ui_service_log("service_status", "ok", "systemctl missing");
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
    crate::ui_service_log(
        "service_status",
        "ok",
        &format!(
            "installed={} active={} enabled={} state={} sub_state={}",
            status.installed, status.active, status.enabled, active_state, sub_state
        ),
    );
    status
}

pub(crate) fn systemctl_show_prop(prop: &str) -> Option<String> {
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

pub(crate) fn daemon_lock_hint() -> Option<String> {
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

fn terminate_and_cleanup_lock(lock_path: &PathBuf, pid: u32) {
    let pid_s = pid.to_string();
    let _ = Command::new("kill").args(["-TERM", &pid_s]).status();
    for _ in 0..10 {
        if !process_exists(pid) {
            let _ = fs::remove_file(lock_path);
            return;
        }
        thread::sleep(Duration::from_millis(100));
    }

    let _ = Command::new("kill").args(["-KILL", &pid_s]).status();
    for _ in 0..10 {
        if !process_exists(pid) {
            let _ = fs::remove_file(lock_path);
            return;
        }
        thread::sleep(Duration::from_millis(100));
    }
}

pub(crate) fn stop_conflicting_lock_holder() {
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
        terminate_and_cleanup_lock(&lock_path, pid);
    }
}

pub(crate) fn stop_conflicting_port_holder(control_port: u16) {
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

pub(crate) fn process_exists(pid: u32) -> bool {
    Command::new("ps")
        .args(["-p", &pid.to_string()])
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

pub(crate) fn process_name_matches(pid: u32, needle: &str) -> bool {
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

pub(crate) fn ensure_systemctl() -> Result<(), String> {
    if command_exists("systemctl") {
        Ok(())
    } else {
        Err("systemctl is not available on this host".to_string())
    }
}

pub(crate) fn apply_systemctl_user_env(cmd: &mut Command) {
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

pub(crate) fn command_exists(name: &str) -> bool {
    Command::new("sh")
        .args(["-c", &format!("command -v {name} >/dev/null 2>&1")])
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

pub(crate) fn systemctl_state(action: &str) -> Option<String> {
    let mut cmd = Command::new("systemctl");
    cmd.args(["--user", action, SERVICE_NAME]);
    apply_systemctl_user_env(&mut cmd);
    cmd.output()
        .ok()
        .map(|out| String::from_utf8_lossy(&out.stdout).trim().to_string())
        .filter(|v| !v.is_empty())
}

pub(crate) fn systemctl_user(args: &[&str]) -> Result<(), String> {
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

pub(crate) fn unit_file_path() -> PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| ".".to_string());
    PathBuf::from(home).join(format!(".config/systemd/user/{SERVICE_NAME}.service"))
}

pub(crate) fn service_unit_content() -> String {
    let root_path = crate::repo_root();
    let _ = crate::ensure_user_wbeam_config(&root_path);
    let daemon_bin_override = crate::wbeam_config_value("WBEAM_DAEMON_BIN");
    let default_runner = root_path
        .join("host/scripts/run_wbeamd.sh")
        .to_string_lossy()
        .to_string();
    let control_port = crate::wbeam_control_port();
    let stream_port = crate::wbeam_stream_port();
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

#[tauri::command]
pub(crate) fn service_install() -> Result<ServiceStatus, String> {
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
pub(crate) fn service_uninstall() -> Result<ServiceStatus, String> {
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
pub(crate) fn service_start() -> Result<ServiceStatus, String> {
    crate::ui_service_log("service_start", "begin", "");
    ensure_systemctl()?;
    let unit_path = unit_file_path();
    if !unit_path.exists() {
        crate::ui_service_log("service_start", "error", "service is not installed");
        return Err("service is not installed".to_string());
    }
    let expected = service_unit_content();
    let current = fs::read_to_string(&unit_path).unwrap_or_default();
    if current != expected {
        fs::write(&unit_path, expected).map_err(|e| format!("cannot rewrite unit file: {e}"))?;
        systemctl_user(&["daemon-reload"])?;
    }
    let control_port = crate::wbeam_control_port();
    stop_conflicting_port_holder(control_port);
    stop_conflicting_lock_holder();
    let _ = systemctl_user(&["reset-failed", SERVICE_NAME]);
    if let Err(err) = systemctl_user(&["start", SERVICE_NAME]) {
        crate::ui_service_log("service_start", "error", &err);
        return Err(err);
    }
    // Wait briefly for systemd to settle so UI doesn't require a second click.
    // Exit early if systemd reports an unambiguous terminal state.
    for _ in 0..24 {
        let state = systemctl_state("is-active").unwrap_or_default();
        match state.as_str() {
            "active" | "activating" | "reloading" => break,
            // systemd reached a terminal failure state — no point waiting more.
            "failed" | "inactive" => break,
            _ => {}
        }
        thread::sleep(Duration::from_millis(250));
    }
    let status = service_status();
    crate::ui_service_log(
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
pub(crate) fn service_stop() -> Result<ServiceStatus, String> {
    ensure_systemctl()?;
    if unit_file_path().exists() {
        systemctl_user(&["stop", SERVICE_NAME])?;
    }
    Ok(service_status())
}
