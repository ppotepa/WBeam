use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

#[derive(Debug, Clone)]
pub struct X11ExtendProbe {
    pub supported: bool,
    pub reason: String,
    pub missing_deps: Vec<String>,
}

fn unsupported(reason: impl Into<String>, missing_deps: Vec<String>) -> X11ExtendProbe {
    X11ExtendProbe {
        supported: false,
        reason: reason.into(),
        missing_deps,
    }
}

fn xrandr_command(display: &str, xauth: Option<&Path>) -> Command {
    let mut cmd = Command::new("xrandr");
    cmd.env("DISPLAY", display);
    if let Some(path) = xauth {
        cmd.env("XAUTHORITY", path);
    }
    cmd
}

fn xrandr_unsupported(reason: &str) -> X11ExtendProbe {
    unsupported(reason, Vec::new())
}

fn xrandr_text(display: &str, xauth: Option<&Path>, arg: &str) -> Result<String, X11ExtendProbe> {
    let mut cmd = xrandr_command(display, xauth);
    cmd.arg(arg);
    let out = cmd
        .output()
        .map_err(|_| xrandr_unsupported(&format!("failed to execute xrandr {arg}")))?;
    if out.status.success() {
        return Ok(String::from_utf8_lossy(&out.stdout).to_string());
    }
    let stderr = String::from_utf8_lossy(&out.stderr).trim().to_string();
    let reason = if stderr.is_empty() {
        format!("xrandr {arg} returned non-zero status")
    } else {
        format!("xrandr {arg} failed: {stderr}")
    };
    Err(xrandr_unsupported(&reason))
}

pub fn probe(is_remote: bool) -> X11ExtendProbe {
    let display = std::env::var("DISPLAY").unwrap_or_default();
    if display.trim().is_empty() {
        return unsupported("DISPLAY is not set for daemon process", Vec::new());
    }

    if !command_exists("xrandr") {
        return unsupported("xrandr is not installed", vec!["xrandr".to_string()]);
    }

    let xauth = resolve_xauthority();
    let version_text = match xrandr_text(&display, xauth.as_deref(), "--version") {
        Ok(text) => text,
        Err(mut probe) => {
            if xauth.is_none() {
                probe.reason = format!("{} (XAUTHORITY not resolved)", probe.reason);
            }
            return probe;
        }
    };
    let randr_15 = detect_randr_15_or_newer(&version_text);
    if !randr_15 {
        return unsupported(
            "RandR >= 1.5 is required for monitor extension APIs",
            Vec::new(),
        );
    }

    let query_text = match xrandr_text(&display, xauth.as_deref(), "--query") {
        Ok(text) => text,
        Err(probe) => return probe,
    };
    let connected_outputs = query_text
        .lines()
        .filter(|line| line.contains(" connected"))
        .count();
    if connected_outputs == 0 {
        return unsupported(
            "xrandr reports no connected outputs in this X11 session",
            Vec::new(),
        );
    }

    if is_remote {
        let low = query_text.to_ascii_lowercase();
        if low.contains("xrdp") || low.contains("rdp") {
            return unsupported(
                "remote X11 (RDP/xrdp) session usually does not expose extend-capable RandR outputs",
                Vec::new(),
            );
        }
    }

    X11ExtendProbe {
        supported: true,
        reason: "x11 RandR monitor extension looks available in current session".to_string(),
        missing_deps: Vec::new(),
    }
}

fn detect_randr_15_or_newer(raw: &str) -> bool {
    for line in raw.lines() {
        let low = line.to_ascii_lowercase();
        if !low.contains("version") {
            continue;
        }
        for token in line.split_whitespace() {
            let normalized = token.trim_matches(|c: char| !c.is_ascii_alphanumeric() && c != '.');
            if !normalized.contains('.') {
                continue;
            }
            let mut parts = normalized.split('.');
            let major = parts
                .next()
                .and_then(|v| v.parse::<u32>().ok())
                .unwrap_or(0);
            let minor = parts
                .next()
                .and_then(|v| v.parse::<u32>().ok())
                .unwrap_or(0);
            if major > 1 || (major == 1 && minor >= 5) {
                return true;
            }
        }
    }
    false
}

fn command_exists(name: &str) -> bool {
    Command::new("sh")
        .args(["-c", &format!("command -v {name} >/dev/null 2>&1")])
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

fn resolve_xauthority() -> Option<PathBuf> {
    if let Some(path) = existing_path_from_env("XAUTHORITY") {
        return Some(path);
    }
    if let Some(home) = std::env::var_os("HOME") {
        let candidate = Path::new(&home).join(".Xauthority");
        if candidate.exists() {
            return Some(candidate);
        }
    }

    let uid = std::env::var("UID")
        .ok()
        .filter(|v| !v.trim().is_empty())
        .or_else(|| std::env::var("EUID").ok())
        .unwrap_or_else(|| "1000".to_string());
    let run_dir = PathBuf::from(format!("/run/user/{uid}"));
    if !run_dir.exists() {
        return None;
    }
    let mut candidates: Vec<PathBuf> = Vec::new();
    if let Ok(entries) = fs::read_dir(&run_dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            let is_xauth = path
                .file_name()
                .and_then(|n| n.to_str())
                .map(|name| name.starts_with("xauth_"))
                .unwrap_or(false);
            if is_xauth && path.is_file() {
                candidates.push(path);
            }
        }
    }
    candidates.sort();
    if let Some(last) = candidates.pop() {
        return Some(last);
    }

    None
}

fn existing_path_from_env(var: &str) -> Option<PathBuf> {
    let path = std::env::var(var).ok()?;
    let candidate = PathBuf::from(path);
    candidate.exists().then_some(candidate)
}
