use serde::Serialize;
use serde_json::Value;
use std::collections::VecDeque;
use std::io::{BufRead, BufReader};
use std::process::{Command, Stdio};
use std::sync::{Mutex, OnceLock};
use std::thread;

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct VirtualDoctor {
    pub(crate) ok: bool,
    pub(crate) message: String,
    pub(crate) actionable: bool,
    pub(crate) host_backend: String,
    pub(crate) resolver: String,
    pub(crate) missing_deps: Vec<String>,
    pub(crate) install_hint: String,
}

#[derive(Debug, Serialize, Clone)]
#[serde(rename_all = "camelCase")]
pub(crate) struct VirtualDepsInstallStatus {
    running: bool,
    done: bool,
    success: bool,
    message: String,
    logs: Vec<String>,
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

fn virtual_deps_state() -> &'static Mutex<VirtualDepsInstallState> {
    VIRTUAL_DEPS_INSTALL_STATE.get_or_init(|| Mutex::new(VirtualDepsInstallState::default()))
}

pub(crate) fn virtual_deps_snapshot() -> VirtualDepsInstallStatus {
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

pub(crate) fn virtual_deps_reset_running() {
    let mut state = virtual_deps_state()
        .lock()
        .expect("virtual deps state lock");
    state.running = true;
    state.done = false;
    state.success = false;
    state.message = "Starting installation...".to_string();
    state.logs.clear();
}

pub(crate) fn virtual_deps_push_log(line: impl Into<String>) {
    const MAX_LOG_LINES: usize = 500;
    let mut state = virtual_deps_state()
        .lock()
        .expect("virtual deps state lock");
    state.logs.push_back(line.into());
    while state.logs.len() > MAX_LOG_LINES {
        let _ = state.logs.pop_front();
    }
}

pub(crate) fn virtual_deps_finish(success: bool, message: String) {
    let mut state = virtual_deps_state()
        .lock()
        .expect("virtual deps state lock");
    state.running = false;
    state.done = true;
    state.success = success;
    state.message = message;
}

pub(crate) fn run_virtual_install_job() {
    let root = crate::repo_root();
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
    } else if crate::service::command_exists("pkexec") {
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
pub(crate) fn virtual_doctor(
    serial: Option<String>,
    stream_port: Option<u16>,
) -> Result<VirtualDoctor, String> {
    let control_port = crate::wbeam_control_port();
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
pub(crate) fn virtual_install_deps_start() -> Result<String, String> {
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
pub(crate) fn virtual_install_deps_status() -> VirtualDepsInstallStatus {
    virtual_deps_snapshot()
}
