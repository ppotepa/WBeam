use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

#[derive(Debug, Clone)]
pub struct X11ExtendProbe {
    pub supported: bool,
    pub reason: String,
    pub missing_deps: Vec<String>,
}

fn unsupported(reason: impl Into<String>) -> X11ExtendProbe {
    X11ExtendProbe {
        supported: false,
        reason: reason.into(),
        missing_deps: Vec::new(),
    }
}

fn unsupported_missing(reason: impl Into<String>, missing_dep: &str) -> X11ExtendProbe {
    X11ExtendProbe {
        supported: false,
        reason: reason.into(),
        missing_deps: vec![missing_dep.to_string()],
    }
}

fn run_xrandr(
    args: &[&str],
    display: &str,
    xauth: Option<&PathBuf>,
) -> Result<std::process::Output, String> {
    let mut cmd = Command::new("xrandr");
    cmd.args(args).env("DISPLAY", display);
    if let Some(path) = xauth {
        cmd.env("XAUTHORITY", path);
    }
    cmd.output()
        .map_err(|_| format!("failed to execute xrandr {}", args.join(" ")))
}

fn check_version_support(display: &str, xauth: Option<&PathBuf>) -> Result<(), X11ExtendProbe> {
    let version_out = run_xrandr(&["--version"], display, xauth).map_err(unsupported)?;
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
        return Err(unsupported(reason));
    }
    let version_text = String::from_utf8_lossy(&version_out.stdout).to_string();
    if !detect_randr_15_or_newer(&version_text) {
        return Err(unsupported(
            "RandR >= 1.5 is required for monitor extension APIs",
        ));
    }
    Ok(())
}

fn query_outputs(display: &str, xauth: Option<&PathBuf>) -> Result<String, X11ExtendProbe> {
    let query_out = run_xrandr(&["--query"], display, xauth).map_err(unsupported)?;
    if !query_out.status.success() {
        let stderr = String::from_utf8_lossy(&query_out.stderr)
            .trim()
            .to_string();
        let reason = if stderr.is_empty() {
            "xrandr --query failed".to_string()
        } else {
            format!("xrandr query failed: {stderr}")
        };
        return Err(unsupported(reason));
    }
    Ok(String::from_utf8_lossy(&query_out.stdout).to_string())
}

pub fn probe(is_remote: bool) -> X11ExtendProbe {
    let display = std::env::var("DISPLAY").unwrap_or_default();
    if display.trim().is_empty() {
        return unsupported("DISPLAY is not set for daemon process");
    }

    if !command_exists("xrandr") {
        return unsupported_missing("xrandr is not installed", "xrandr");
    }

    let xauth = resolve_xauthority();
    if let Err(probe) = check_version_support(&display, xauth.as_ref()) {
        return probe;
    }

    let query_text = match query_outputs(&display, xauth.as_ref()) {
        Ok(text) => text,
        Err(probe) => return probe,
    };
    let connected_outputs = query_text
        .lines()
        .filter(|line| line.contains(" connected"))
        .count();
    if connected_outputs == 0 {
        return unsupported("xrandr reports no connected outputs in this X11 session");
    }

    if is_remote {
        let low = query_text.to_ascii_lowercase();
        if low.contains("xrdp") || low.contains("rdp") {
            return unsupported(
                "remote X11 (RDP/xrdp) session usually does not expose extend-capable RandR outputs",
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
    find_xauthority_from_env()
        .or_else(find_xauthority_in_home)
        .or_else(find_xauthority_in_run_dir)
}

fn find_xauthority_from_env() -> Option<PathBuf> {
    let path = std::env::var("XAUTHORITY").ok()?;
    let candidate = PathBuf::from(path);
    candidate.exists().then_some(candidate)
}

fn find_xauthority_in_home() -> Option<PathBuf> {
    let home = std::env::var_os("HOME")?;
    let candidate = Path::new(&home).join(".Xauthority");
    candidate.exists().then_some(candidate)
}

fn find_xauthority_in_run_dir() -> Option<PathBuf> {
    let uid = std::env::var("UID")
        .ok()
        .filter(|v| !v.trim().is_empty())
        .or_else(|| std::env::var("EUID").ok())
        .unwrap_or_else(|| "1000".to_string());
    let run_dir = PathBuf::from(format!("/run/user/{uid}"));
    if !run_dir.exists() {
        return None;
    }

    let mut candidates = run_dir_xauth_candidates(&run_dir);
    candidates.sort();
    candidates.pop()
}

fn run_dir_xauth_candidates(run_dir: &Path) -> Vec<PathBuf> {
    let mut candidates = Vec::new();
    if let Ok(entries) = fs::read_dir(run_dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if is_run_dir_xauth_file(&path) {
                candidates.push(path);
            }
        }
    }
    candidates
}

fn is_run_dir_xauth_file(path: &Path) -> bool {
    path.file_name()
        .and_then(|n| n.to_str())
        .map(|name| name.starts_with("xauth_") && path.is_file())
        .unwrap_or(false)
}
