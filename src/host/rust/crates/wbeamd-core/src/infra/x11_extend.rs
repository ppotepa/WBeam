use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

#[derive(Debug, Clone)]
pub struct X11ExtendProbe {
    pub supported: bool,
    pub reason: String,
    pub missing_deps: Vec<String>,
}

pub fn probe(is_remote: bool) -> X11ExtendProbe {
    let display = std::env::var("DISPLAY").unwrap_or_default();
    if display.trim().is_empty() {
        return X11ExtendProbe {
            supported: false,
            reason: "DISPLAY is not set for daemon process".to_string(),
            missing_deps: Vec::new(),
        };
    }

    if !command_exists("xrandr") {
        return X11ExtendProbe {
            supported: false,
            reason: "xrandr is not installed".to_string(),
            missing_deps: vec!["xrandr".to_string()],
        };
    }

    let xauth = resolve_xauthority();
    let mut version_cmd = Command::new("xrandr");
    version_cmd.arg("--version").env("DISPLAY", &display);
    if let Some(path) = xauth.as_deref() {
        version_cmd.env("XAUTHORITY", path);
    }
    let version_out = version_cmd.output();
    let Ok(version_out) = version_out else {
        return X11ExtendProbe {
            supported: false,
            reason: "failed to execute xrandr --version".to_string(),
            missing_deps: Vec::new(),
        };
    };
    if !version_out.status.success() {
        let stderr = String::from_utf8_lossy(&version_out.stderr)
            .trim()
            .to_string();
        let mut reason = "xrandr --version returned non-zero status".to_string();
        if !stderr.is_empty() {
            reason = format!("{reason}: {stderr}");
        }
        if xauth.is_none() {
            reason = format!("{reason} (XAUTHORITY not resolved)");
        }
        return X11ExtendProbe {
            supported: false,
            reason,
            missing_deps: Vec::new(),
        };
    }
    let version_text = String::from_utf8_lossy(&version_out.stdout).to_string();
    let randr_15 = detect_randr_15_or_newer(&version_text);
    if !randr_15 {
        return X11ExtendProbe {
            supported: false,
            reason: "RandR >= 1.5 is required for monitor extension APIs".to_string(),
            missing_deps: Vec::new(),
        };
    }

    let mut query_cmd = Command::new("xrandr");
    query_cmd.arg("--query").env("DISPLAY", &display);
    if let Some(path) = xauth.as_deref() {
        query_cmd.env("XAUTHORITY", path);
    }
    let query_out = query_cmd.output();
    let Ok(query_out) = query_out else {
        return X11ExtendProbe {
            supported: false,
            reason: "failed to execute xrandr --query".to_string(),
            missing_deps: Vec::new(),
        };
    };
    if !query_out.status.success() {
        let stderr = String::from_utf8_lossy(&query_out.stderr)
            .trim()
            .to_string();
        let reason = if stderr.is_empty() {
            "xrandr --query failed".to_string()
        } else {
            format!("xrandr query failed: {stderr}")
        };
        return X11ExtendProbe {
            supported: false,
            reason,
            missing_deps: Vec::new(),
        };
    }
    let query_text = String::from_utf8_lossy(&query_out.stdout).to_string();
    let connected_outputs = query_text
        .lines()
        .filter(|line| line.contains(" connected"))
        .count();
    if connected_outputs == 0 {
        return X11ExtendProbe {
            supported: false,
            reason: "xrandr reports no connected outputs in this X11 session".to_string(),
            missing_deps: Vec::new(),
        };
    }

    if is_remote {
        let low = query_text.to_ascii_lowercase();
        if low.contains("xrdp") || low.contains("rdp") {
            return X11ExtendProbe {
                supported: false,
                reason: "remote X11 (RDP/xrdp) session usually does not expose extend-capable RandR outputs".to_string(),
                missing_deps: Vec::new(),
            };
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
    if let Ok(path) = std::env::var("XAUTHORITY") {
        let p = PathBuf::from(path);
        if p.exists() {
            return Some(p);
        }
    }

    if let Some(home) = std::env::var_os("HOME") {
        let p = Path::new(&home).join(".Xauthority");
        if p.exists() {
            return Some(p);
        }
    }

    let uid = std::env::var("UID")
        .ok()
        .filter(|v| !v.trim().is_empty())
        .or_else(|| std::env::var("EUID").ok())
        .unwrap_or_else(|| "1000".to_string());
    let run_dir = PathBuf::from(format!("/run/user/{uid}"));
    if run_dir.exists() {
        let mut candidates: Vec<PathBuf> = Vec::new();
        if let Ok(entries) = fs::read_dir(&run_dir) {
            for entry in entries.flatten() {
                let path = entry.path();
                if let Some(name) = path.file_name().and_then(|n| n.to_str()) {
                    if name.starts_with("xauth_") && path.is_file() {
                        candidates.push(path);
                    }
                }
            }
        }
        candidates.sort();
        if let Some(last) = candidates.pop() {
            return Some(last);
        }
    }

    None
}
