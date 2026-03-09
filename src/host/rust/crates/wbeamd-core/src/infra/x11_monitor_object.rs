use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

#[derive(Debug, Clone)]
pub struct X11MonitorObjectHandle {
    pub name: String,
    pub x: i32,
    pub y: i32,
    pub width: u32,
    pub height: u32,
    pub previous_fb: Option<(u32, u32)>,
}

pub fn create(serial: &str, size: &str) -> Result<X11MonitorObjectHandle, String> {
    let display = std::env::var("DISPLAY").unwrap_or_default();
    if display.trim().is_empty() {
        return Err("DISPLAY is not set for daemon process".to_string());
    }
    if !command_exists("xrandr") {
        return Err("xrandr is not installed".to_string());
    }
    let xauth = resolve_xauthority();
    let (w, h) = parse_size(size);
    let name = monitor_name_for_serial(serial);
    let previous_fb = xrandr_output(&["--query"], &display, xauth.as_deref())
        .ok()
        .and_then(|raw| parse_current_fb(&raw));

    // Best-effort cleanup in case stale monitor object exists from previous run.
    let _ = xrandr(&["--delmonitor", &name], &display, xauth.as_deref());

    let (max_right, max_h) = existing_layout(&display, xauth.as_deref());
    let x = max_right.max(0);
    let y = 0i32;
    let fb_w = (x as u32).saturating_add(w);
    let fb_h = max_h.max(h);

    xrandr(
        &["--fb", &format!("{fb_w}x{fb_h}")],
        &display,
        xauth.as_deref(),
    )
    .map_err(|e| format!("xrandr --fb failed: {e}"))?;

    let geometry = format!("{w}/{w}x{h}/{h}+{x}+{y}");
    xrandr(
        &["--setmonitor", &name, &geometry, "none"],
        &display,
        xauth.as_deref(),
    )
    .map_err(|e| format!("xrandr --setmonitor failed: {e}"))?;

    let monitors = xrandr_output(&["--listmonitors"], &display, xauth.as_deref())
        .map_err(|e| format!("xrandr --listmonitors failed: {e}"))?;
    if !monitors.contains(&name) {
        return Err("monitor object was not visible after setmonitor".to_string());
    }

    Ok(X11MonitorObjectHandle {
        name,
        x,
        y,
        width: w,
        height: h,
        previous_fb,
    })
}

pub fn destroy(handle: &X11MonitorObjectHandle) -> Result<(), String> {
    let display = std::env::var("DISPLAY").unwrap_or_default();
    if display.trim().is_empty() {
        return Ok(());
    }
    let xauth = resolve_xauthority();
    let _ = xrandr(&["--delmonitor", &handle.name], &display, xauth.as_deref());
    if let Some((w, h)) = handle.previous_fb {
        let _ = xrandr(&["--fb", &format!("{w}x{h}")], &display, xauth.as_deref());
    }
    Ok(())
}

fn existing_layout(display: &str, xauth: Option<&Path>) -> (i32, u32) {
    let out = match xrandr_output(&["--listmonitors"], display, xauth) {
        Ok(v) => v,
        Err(_) => return (0, 720),
    };
    let mut max_right = 0i32;
    let mut max_h = 720u32;
    for line in out.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with("Monitors:") {
            continue;
        }
        let Some(geom) = line.split_whitespace().nth(2) else {
            continue;
        };
        let Some((w, x, h)) = parse_monitor_geometry_token(geom) else {
            continue;
        };
        let right = x.saturating_add(w as i32);
        if right > max_right {
            max_right = right;
        }
        if h > max_h {
            max_h = h;
        }
    }
    (max_right, max_h)
}

fn parse_monitor_geometry_token(token: &str) -> Option<(u32, i32, u32)> {
    let (size_part, pos_part) = token.split_once('+')?;
    let (w_part, h_part) = size_part.split_once('x')?;
    let w = w_part.split('/').next()?.parse::<u32>().ok()?;
    let h = h_part.split('/').next()?.parse::<u32>().ok()?;
    let mut pos_it = pos_part.split('+');
    let x = pos_it.next()?.parse::<i32>().ok()?;
    Some((w, x, h))
}

fn parse_size(raw: &str) -> (u32, u32) {
    let mut parts = raw.split('x');
    let w = parts
        .next()
        .and_then(|v| v.parse::<u32>().ok())
        .unwrap_or(1280);
    let h = parts
        .next()
        .and_then(|v| v.parse::<u32>().ok())
        .unwrap_or(720);
    (w.max(320), h.max(240))
}

fn parse_current_fb(raw: &str) -> Option<(u32, u32)> {
    for line in raw.lines() {
        let low = line.to_ascii_lowercase();
        if !low.starts_with("screen ") || !low.contains(" current ") {
            continue;
        }
        let current_part = line.split("current").nth(1)?;
        let dims = current_part.split(',').next()?.trim();
        let mut it = dims.split('x');
        let w = it.next()?.trim().parse::<u32>().ok()?;
        let h = it.next()?.trim().parse::<u32>().ok()?;
        return Some((w, h));
    }
    None
}

fn monitor_name_for_serial(serial: &str) -> String {
    let mut clean = serial
        .chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() {
                c.to_ascii_uppercase()
            } else {
                '_'
            }
        })
        .collect::<String>();
    clean.truncate(18);
    format!("WBEAM_{}", clean)
}

fn xrandr(args: &[&str], display: &str, xauth: Option<&Path>) -> Result<(), String> {
    let out = run_xrandr(args, display, xauth)?;
    if out.status.success() {
        Ok(())
    } else {
        let stderr = String::from_utf8_lossy(&out.stderr).trim().to_string();
        if stderr.is_empty() {
            Err(format!("xrandr exited with {}", out.status))
        } else {
            Err(stderr)
        }
    }
}

fn xrandr_output(args: &[&str], display: &str, xauth: Option<&Path>) -> Result<String, String> {
    let out = run_xrandr(args, display, xauth)?;
    if out.status.success() {
        Ok(String::from_utf8_lossy(&out.stdout).to_string())
    } else {
        let stderr = String::from_utf8_lossy(&out.stderr).trim().to_string();
        if stderr.is_empty() {
            Err(format!("xrandr exited with {}", out.status))
        } else {
            Err(stderr)
        }
    }
}

fn run_xrandr(
    args: &[&str],
    display: &str,
    xauth: Option<&Path>,
) -> Result<std::process::Output, String> {
    let mut cmd = Command::new("xrandr");
    cmd.args(args).env("DISPLAY", display);
    if let Some(path) = xauth {
        cmd.env("XAUTHORITY", path);
    }
    cmd.output()
        .map_err(|e| format!("failed to run xrandr {:?}: {e}", args))
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_monitor_geometry_token_parses_valid_geometry() {
        let token = "1920/508x1080/286+1920+0";
        assert_eq!(
            parse_monitor_geometry_token(token),
            Some((1920, 1920, 1080))
        );
    }

    #[test]
    fn parse_monitor_geometry_token_rejects_invalid_geometry() {
        assert_eq!(parse_monitor_geometry_token("foo"), None);
        assert_eq!(parse_monitor_geometry_token("1920+0+0"), None);
    }

    #[test]
    fn parse_size_applies_defaults_and_minimums() {
        assert_eq!(parse_size("160x100"), (320, 240));
        assert_eq!(parse_size("1920x1080"), (1920, 1080));
        assert_eq!(parse_size("bad"), (1280, 720));
    }

    #[test]
    fn parse_current_fb_extracts_dimensions() {
        let query = "Screen 0: minimum 8 x 8, current 3280 x 1080, maximum 32767 x 32767";
        assert_eq!(parse_current_fb(query), Some((3280, 1080)));
    }

    #[test]
    fn monitor_name_for_serial_normalizes_and_truncates() {
        let name = monitor_name_for_serial("ab:cd-ef_1234567890-XYZ-extra");
        assert!(name.starts_with("WBEAM_"));
        assert!(name.len() <= 24);
        assert!(name.chars().all(|c| c.is_ascii_uppercase() || c.is_ascii_digit() || c == '_'));
    }
}
