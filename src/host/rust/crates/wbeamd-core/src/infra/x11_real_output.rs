use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

#[derive(Debug, Clone)]
pub struct X11RealOutputProbe {
    pub supported: bool,
    pub reason: String,
    pub missing_deps: Vec<String>,
}

#[derive(Debug, Clone)]
pub struct X11RealOutputHandle {
    pub output_name: String,
    pub x: i32,
    pub y: i32,
    pub width: u32,
    pub height: u32,
}

#[derive(Debug, Clone)]
struct ProviderInfo {
    id: String,
    name: String,
    caps: u32,
}

pub fn probe(is_remote: bool) -> X11RealOutputProbe {
    if is_remote {
        return X11RealOutputProbe {
            supported: false,
            reason: "remote X11 sessions typically cannot expose real virtual outputs".to_string(),
            missing_deps: Vec::new(),
        };
    }

    let display = detect_x11_display().unwrap_or_default();
    if display.trim().is_empty() {
        return X11RealOutputProbe {
            supported: false,
            reason: "DISPLAY is not set for daemon process".to_string(),
            missing_deps: Vec::new(),
        };
    }
    if !command_exists("xrandr") {
        return X11RealOutputProbe {
            supported: false,
            reason: "xrandr is not installed".to_string(),
            missing_deps: vec!["xrandr".to_string()],
        };
    }

    let xauth = resolve_xauthority();
    let providers = match xrandr_output(&["--listproviders"], &display, xauth.as_deref()) {
        Ok(v) => parse_providers(&v),
        Err(e) => {
            return X11RealOutputProbe {
                supported: false,
                reason: format!("xrandr --listproviders failed: {e}"),
                missing_deps: Vec::new(),
            };
        }
    };

    let source_provider = providers
        .iter()
        .find(|p| is_evdi_name(&p.name))
        .or_else(|| providers.iter().find(|p| has_cap(p.caps, 0x1)));
    if source_provider.is_none() {
        return X11RealOutputProbe {
            supported: false,
            reason: "no virtual-output source provider was detected in current X11 session".to_string(),
            missing_deps: vec!["evdi-provider".to_string()],
        };
    }

    let query = match xrandr_output(&["--query"], &display, xauth.as_deref()) {
        Ok(v) => v,
        Err(e) => {
            return X11RealOutputProbe {
                supported: false,
                reason: format!("xrandr --query failed: {e}"),
                missing_deps: Vec::new(),
            };
        }
    };
    let outputs = parse_outputs(&query);
    if outputs.is_empty() {
        return X11RealOutputProbe {
            supported: false,
            reason: "xrandr reported no outputs in this session".to_string(),
            missing_deps: Vec::new(),
        };
    }

    let has_disconnected_candidate = outputs
        .iter()
        .any(|o| !o.connected && looks_virtual_output_name(&o.name));
    if !has_disconnected_candidate {
        return X11RealOutputProbe {
            supported: false,
            reason: "no disconnected virtual-capable output found (expected VIRTUAL/DVI/HDMI from EVDI)".to_string(),
            missing_deps: vec!["evdi-output".to_string()],
        };
    }

    X11RealOutputProbe {
        supported: true,
        reason: "X11 virtual output candidates detected".to_string(),
        missing_deps: Vec::new(),
    }
}

pub fn create(_serial: &str, size: &str) -> Result<X11RealOutputHandle, String> {
    let display = detect_x11_display().unwrap_or_default();
    if display.trim().is_empty() {
        return Err("DISPLAY is not set for daemon process".to_string());
    }
    let xauth = resolve_xauthority();
    let providers_raw = xrandr_output(&["--listproviders"], &display, xauth.as_deref())
        .map_err(|e| format!("xrandr --listproviders failed: {e}"))?;
    let providers = parse_providers(&providers_raw);
    let source = providers
        .iter()
        .find(|p| is_evdi_name(&p.name))
        .or_else(|| {
            providers
                .iter()
                .find(|p| has_cap(p.caps, 0x1) && !is_modesetting_name(&p.name))
        })
        .or_else(|| providers.iter().find(|p| has_cap(p.caps, 0x1)))
        .ok_or_else(|| "no source provider found for virtual output".to_string())?;
    let sink = providers
        .iter()
        .find(|p| has_cap(p.caps, 0x2) && p.id != source.id)
        .or_else(|| providers.iter().find(|p| is_modesetting_name(&p.name) && p.id != source.id))
        .or_else(|| providers.iter().find(|p| p.id != source.id))
        .or_else(|| providers.first())
        .ok_or_else(|| "no xrandr providers found".to_string())?;

    // Some setups need this link, others already have it. Try both directions best-effort.
    let _ = xrandr(
        &["--setprovideroutputsource", &sink.id, &source.id],
        &display,
        xauth.as_deref(),
    );
    let _ = xrandr(
        &["--setprovideroutputsource", &source.id, &sink.id],
        &display,
        xauth.as_deref(),
    );

    let query = xrandr_output(&["--query"], &display, xauth.as_deref())
        .map_err(|e| format!("xrandr --query failed: {e}"))?;
    let outputs = parse_outputs(&query);
    let output = outputs
        .iter()
        .find(|o| !o.connected && looks_virtual_output_name(&o.name))
        .or_else(|| outputs.iter().find(|o| !o.connected))
        .ok_or_else(|| "no disconnected output available for virtual monitor".to_string())?;

    let (w, h) = parse_size(size);
    let primary = outputs.iter().find(|o| o.connected).map(|o| o.name.clone());
    let desired_mode = format!("{w}x{h}");
    let mut chosen_mode: Option<String> = output
        .modes
        .iter()
        .find(|m| m.trim() == desired_mode || m.trim().starts_with(&desired_mode))
        .map(|m| m.trim().to_string());

    // EVDI outputs often expose no modes until we inject one (cvt/newmode/addmode).
    if chosen_mode.is_none() {
        if let Ok(mode) = ensure_mode_with_cvt(&output.name, w, h, &display, xauth.as_deref()) {
            chosen_mode = Some(mode);
        }
        // Refresh modes list after injection.
        if chosen_mode.is_none() {
            if let Ok(q) = xrandr_output(&["--query"], &display, xauth.as_deref()) {
                let refreshed = parse_outputs(&q);
                if let Some(found) = refreshed.iter().find(|o| o.name == output.name) {
                    chosen_mode = found
                        .modes
                        .iter()
                        .find(|m| m.trim() == desired_mode || m.trim().starts_with(&desired_mode))
                        .map(|m| m.trim().to_string());
                }
            }
        }
    }

    // Try explicit mode first; if unavailable fallback to --auto.
    let mut mode_ok = false;
    if let Some(mode_name) = chosen_mode.as_deref() {
        let mut args = vec![
            "--output".to_string(),
            output.name.clone(),
            "--mode".to_string(),
            mode_name.to_string(),
        ];
        if let Some(p) = primary.as_deref() {
            args.push("--right-of".to_string());
            args.push(p.to_string());
        }
        let arg_refs = args.iter().map(|s| s.as_str()).collect::<Vec<_>>();
        if xrandr(&arg_refs, &display, xauth.as_deref()).is_ok() {
            mode_ok = true;
        }
    }

    if !mode_ok {
        let hint = if let Some(mode_name) = chosen_mode.as_deref() {
            format!(" (mode={mode_name})")
        } else {
            " (no mode available)".to_string()
        };
        return Err(format!(
            "failed to enable output {}{}: no usable mode could be applied",
            output.name, hint
        ));
    }

    let query_after = xrandr_output(&["--query"], &display, xauth.as_deref())
        .map_err(|e| format!("xrandr --query after output enable failed: {e}"))?;
    let outputs_after = parse_outputs(&query_after);
    let active = outputs_after
        .iter()
        .find(|o| o.name == output.name && o.geometry.is_some())
        .ok_or_else(|| "output did not expose geometry after enable".to_string())?;

    let (x, y, aw, ah) = active
        .geometry
        .ok_or_else(|| "active output has no geometry".to_string())?;

    Ok(X11RealOutputHandle {
        output_name: output.name.clone(),
        x,
        y,
        width: aw,
        height: ah,
    })
}

pub fn destroy(handle: &X11RealOutputHandle) -> Result<(), String> {
    let display = detect_x11_display().unwrap_or_default();
    if display.trim().is_empty() {
        return Ok(());
    }
    let xauth = resolve_xauthority();
    xrandr(
        &["--output", &handle.output_name, "--off"],
        &display,
        xauth.as_deref(),
    )
    .map(|_| ())
}

#[derive(Debug, Clone)]
struct OutputInfo {
    name: String,
    connected: bool,
    modes: Vec<String>,
    geometry: Option<(i32, i32, u32, u32)>,
}

fn parse_outputs(raw: &str) -> Vec<OutputInfo> {
    let mut out = Vec::new();
    let mut current: Option<OutputInfo> = None;
    for line in raw.lines() {
        if line.trim().is_empty() {
            continue;
        }
        if !line.starts_with(' ') && !line.starts_with('\t') {
            if let Some(prev) = current.take() {
                out.push(prev);
            }
            let parts = line.split_whitespace().collect::<Vec<_>>();
            if parts.len() >= 2 && (parts[1] == "connected" || parts[1] == "disconnected") {
                let name = parts[0].to_string();
                let connected = parts[1] == "connected";
                let geometry = parse_connected_geometry(line);
                current = Some(OutputInfo {
                    name,
                    connected,
                    modes: Vec::new(),
                    geometry,
                });
            }
            continue;
        }
        if let Some(cur) = current.as_mut() {
            let t = line.trim();
            if let Some(mode) = t.split_whitespace().next() {
                if looks_mode(mode) {
                    cur.modes.push(mode.to_string());
                }
            }
        }
    }
    if let Some(prev) = current.take() {
        out.push(prev);
    }
    out
}

fn parse_connected_geometry(line: &str) -> Option<(i32, i32, u32, u32)> {
    // e.g. "HDMI-1 connected primary 1920x1080+0+0 ..."
    for token in line.split_whitespace() {
        if !token.contains('+') || !token.contains('x') {
            continue;
        }
        let (wh, xy) = token.split_once('+')?;
        let (w_s, h_s) = wh.split_once('x')?;
        let (x_s, y_s) = xy.split_once('+')?;
        let w = w_s.parse::<u32>().ok()?;
        let h = h_s.parse::<u32>().ok()?;
        let x = x_s.parse::<i32>().ok()?;
        let y = y_s.parse::<i32>().ok()?;
        return Some((x, y, w, h));
    }
    None
}

fn parse_providers(raw: &str) -> Vec<ProviderInfo> {
    let mut providers = Vec::new();
    for line in raw.lines() {
        let low = line.to_ascii_lowercase();
        if !low.contains("provider") || !line.contains("name:") {
            continue;
        }
        let id = line
            .split_whitespace()
            .find(|tok| tok.starts_with("0x"))
            .unwrap_or("0x0")
            .to_string();
        let name = line
            .split("name:")
            .nth(1)
            .map(|s| s.trim().to_string())
            .unwrap_or_else(|| "unknown".to_string());
        let caps = extract_provider_caps(line).unwrap_or(0);
        providers.push(ProviderInfo { id, name, caps });
    }
    providers
}

fn extract_provider_caps(line: &str) -> Option<u32> {
    let cap_token = line.split("cap:").nth(1)?.split_whitespace().next()?;
    let cap = cap_token.trim().trim_end_matches(',');
    if let Some(hex) = cap.strip_prefix("0x") {
        u32::from_str_radix(hex, 16).ok()
    } else {
        cap.parse::<u32>().ok()
    }
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

fn ensure_mode_with_cvt(
    output: &str,
    w: u32,
    h: u32,
    display: &str,
    xauth: Option<&Path>,
) -> Result<String, String> {
    if !command_exists("cvt") {
        return Err("cvt command is not available to generate modeline".to_string());
    }

    let mut cmd = Command::new("cvt");
    cmd.arg(w.to_string()).arg(h.to_string()).arg("60");
    let out = cmd
        .output()
        .map_err(|e| format!("failed to run cvt: {e}"))?;
    if !out.status.success() {
        return Err("cvt failed to generate modeline".to_string());
    }
    let stdout = String::from_utf8_lossy(&out.stdout);
    let line = stdout
        .lines()
        .find(|l| l.trim_start().starts_with("Modeline "))
        .ok_or_else(|| "cvt output did not contain Modeline".to_string())?
        .trim()
        .trim_start_matches("Modeline ")
        .to_string();

    // Modeline "name" <rest...>
    let (name_quoted, rest) = line
        .split_once(' ')
        .ok_or_else(|| "invalid cvt modeline format".to_string())?;
    let mode_name = name_quoted.trim_matches('"').to_string();
    let mut newmode_args = vec!["--newmode".to_string(), mode_name.clone()];
    newmode_args.extend(rest.split_whitespace().map(|s| s.to_string()));
    let newmode_refs = newmode_args.iter().map(|s| s.as_str()).collect::<Vec<_>>();
    let _ = xrandr(&newmode_refs, display, xauth);
    let _ = xrandr(
        &["--addmode", output, &mode_name],
        display,
        xauth,
    );
    Ok(mode_name)
}

fn looks_mode(token: &str) -> bool {
    let mut p = token.split('x');
    p.next().and_then(|v| v.parse::<u32>().ok()).is_some()
        && p.next().and_then(|v| v.parse::<u32>().ok()).is_some()
}

fn looks_virtual_output_name(name: &str) -> bool {
    let low = name.to_ascii_lowercase();
    low.contains("virtual")
        || low.contains("vkms")
        || low.contains("dvi")
        || low.contains("hdmi")
        || low.contains("displayport")
}

fn detect_x11_display() -> Option<String> {
    if let Ok(value) = std::env::var("DISPLAY") {
        if !value.trim().is_empty() {
            return Some(value);
        }
    }

    let entries = fs::read_dir("/tmp/.X11-unix").ok()?;
    let mut best: Option<u32> = None;
    for entry in entries.flatten() {
        let name = entry.file_name();
        let Some(name) = name.to_str() else {
            continue;
        };
        if !name.starts_with('X') {
            continue;
        }
        let Ok(num) = name[1..].parse::<u32>() else {
            continue;
        };
        best = Some(best.map(|cur| cur.max(num)).unwrap_or(num));
    }
    best.map(|n| format!(":{n}"))
}

fn is_evdi_name(name: &str) -> bool {
    let low = name.to_ascii_lowercase();
    low.contains("evdi") || low.contains("displaylink")
}

fn is_modesetting_name(name: &str) -> bool {
    name.to_ascii_lowercase().contains("modesetting")
}

fn has_cap(caps: u32, flag: u32) -> bool {
    caps & flag != 0
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
