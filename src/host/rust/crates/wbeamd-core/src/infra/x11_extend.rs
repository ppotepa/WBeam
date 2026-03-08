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

    let version_out = Command::new("xrandr").arg("--version").output();
    let Ok(version_out) = version_out else {
        return X11ExtendProbe {
            supported: false,
            reason: "failed to execute xrandr --version".to_string(),
            missing_deps: Vec::new(),
        };
    };
    if !version_out.status.success() {
        return X11ExtendProbe {
            supported: false,
            reason: "xrandr --version returned non-zero status".to_string(),
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

    let query_out = Command::new("xrandr").arg("--query").output();
    let Ok(query_out) = query_out else {
        return X11ExtendProbe {
            supported: false,
            reason: "failed to execute xrandr --query".to_string(),
            missing_deps: Vec::new(),
        };
    };
    if !query_out.status.success() {
        let stderr = String::from_utf8_lossy(&query_out.stderr).trim().to_string();
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
        if !low.contains("xrandr version") {
            continue;
        }
        let maybe_ver = line.split_whitespace().last().unwrap_or_default();
        let mut parts = maybe_ver.split('.');
        let major = parts.next().and_then(|v| v.parse::<u32>().ok()).unwrap_or(0);
        let minor = parts.next().and_then(|v| v.parse::<u32>().ok()).unwrap_or(0);
        if major > 1 {
            return true;
        }
        if major == 1 && minor >= 5 {
            return true;
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

