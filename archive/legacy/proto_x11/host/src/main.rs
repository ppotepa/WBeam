use serde_json::Value;
use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::thread;
use std::time::Duration;

const REAL_OUTPUT_RESOLVER: &str = "linux_x11_real_output";
const MONITOR_OBJECT_RESOLVER: &str = "linux_x11_monitor_object_experimental";
const DEFAULT_CURL_MAX_TIME_SEC: &str = "20";

#[derive(Debug, Clone)]
struct Opts {
    control_port: u16,
    serial: Option<String>,
    stream_port: Option<u16>,
}

fn main() {
    if let Err(err) = run() {
        eprintln!("error: {err}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let mut args = env::args().skip(1).collect::<Vec<_>>();
    if args.is_empty() {
        print_help();
        return Ok(());
    }

    let command = args.remove(0);
    let opts = parse_opts(&args)?;

    match command.as_str() {
        "probe-host" => cmd_probe_host(),
        "doctor" => cmd_doctor(&opts),
        "status" => cmd_status(&opts),
        "start" => cmd_start(&opts),
        "stop" => cmd_stop(&opts),
        "smoke" => cmd_smoke(&opts),
        "acceptance" => cmd_acceptance(&opts),
        "help" | "--help" | "-h" => {
            print_help();
            Ok(())
        }
        _ => Err(format!("unknown command: {command}")),
    }
}

fn print_help() {
    println!(
        "wbeam-x11-proto-host\n\nUsage:\n  cargo run -- <command> [options]\n\nCommands:\n  probe-host               Print host fingerprint (probe_host.py JSON)\n  doctor                   Call /v1/virtual/doctor\n  status                   Call /v1/status\n  start                    Start virtual_monitor session (real-output only)\n  stop                     Stop session\n  smoke                    doctor -> start -> wait STREAMING -> stop\n  acceptance               smoke + assert RandR topology changed\n\nOptions:\n  --control-port <port>    Default: 5001\n  --serial <adb_serial>    Required for start/stop/smoke/acceptance\n  --stream-port <port>     Required for start/stop/smoke/acceptance\n\nExample:\n  cargo run -- doctor --serial HVA6PKNT --stream-port 5002\n  cargo run -- start --serial HVA6PKNT --stream-port 5002\n  cargo run -- acceptance --serial HVA6PKNT --stream-port 5002"
    );
}

fn parse_opts(args: &[String]) -> Result<Opts, String> {
    let mut control_port: u16 = env::var("WBEAM_CONTROL_PORT")
        .ok()
        .and_then(|v| v.parse::<u16>().ok())
        .unwrap_or(5001);
    let mut serial: Option<String> = None;
    let mut stream_port: Option<u16> = None;

    let mut i = 0usize;
    while i < args.len() {
        match args[i].as_str() {
            "--control-port" => {
                i += 1;
                let Some(raw) = args.get(i) else {
                    return Err("missing value for --control-port".to_string());
                };
                control_port = raw
                    .parse::<u16>()
                    .map_err(|_| format!("invalid --control-port: {raw}"))?;
            }
            "--serial" => {
                i += 1;
                let Some(raw) = args.get(i) else {
                    return Err("missing value for --serial".to_string());
                };
                let trimmed = raw.trim();
                if trimmed.is_empty() {
                    return Err("--serial cannot be empty".to_string());
                }
                serial = Some(trimmed.to_string());
            }
            "--stream-port" => {
                i += 1;
                let Some(raw) = args.get(i) else {
                    return Err("missing value for --stream-port".to_string());
                };
                stream_port = Some(
                    raw.parse::<u16>()
                        .map_err(|_| format!("invalid --stream-port: {raw}"))?,
                );
            }
            other if other.starts_with("--") => {
                return Err(format!("unknown option: {other}"));
            }
            _ => {}
        }
        i += 1;
    }

    Ok(Opts {
        control_port,
        serial,
        stream_port,
    })
}

fn cmd_probe_host() -> Result<(), String> {
    let json = probe_host_json()?;
    print_json_pretty_value(&json)
}

fn cmd_doctor(opts: &Opts) -> Result<(), String> {
    let url = api_url(opts, "/v1/virtual/doctor", true);
    let body = curl_json("GET", &url)?;
    print_json_pretty(&body)
}

fn cmd_status(opts: &Opts) -> Result<(), String> {
    let url = api_url(opts, "/v1/status", true);
    let body = curl_json("GET", &url)?;
    print_json_pretty(&body)
}

fn cmd_start(opts: &Opts) -> Result<(), String> {
    let json = start_session(opts)?;
    print_json_pretty_value(&json)
}

fn cmd_stop(opts: &Opts) -> Result<(), String> {
    let json = stop_session(opts)?;
    print_json_pretty_value(&json)
}

fn cmd_smoke(opts: &Opts) -> Result<(), String> {
    let serial = require_serial(opts)?;
    let stream_port = require_stream_port(opts)?;
    println!("[smoke] serial={serial} stream_port={stream_port}");
    reset_session_best_effort(opts);

    let doctor = virtual_doctor(opts)?;
    println!(
        "[smoke] doctor ok={} resolver={} missing={} hint={}",
        doctor.ok,
        doctor.resolver,
        doctor.missing_deps.join(","),
        doctor.install_hint
    );
    if !doctor.ok || !is_supported_virtual_resolver(&doctor.resolver) {
        return Err(format!(
            "smoke aborted: virtual backend is not ready (ok={} resolver={} allow_monitor_object={})",
            doctor.ok,
            doctor.resolver,
            allow_monitor_object()
        ));
    }

    let _ = start_session(opts)?;
    let reached_streaming = wait_for_state(opts, "STREAMING", Duration::from_secs(8))?;
    let _ = stop_session(opts);
    if !reached_streaming {
        return Err("smoke failed: session did not reach STREAMING".to_string());
    }
    println!("[smoke] OK");
    Ok(())
}

fn cmd_acceptance(opts: &Opts) -> Result<(), String> {
    let serial = require_serial(opts)?;
    let stream_port = require_stream_port(opts)?;
    println!("[acceptance] serial={serial} stream_port={stream_port}");
    reset_session_best_effort(opts);

    let doctor = virtual_doctor(opts)?;
    println!(
        "[acceptance] doctor ok={} resolver={} missing={} hint={}",
        doctor.ok,
        doctor.resolver,
        doctor.missing_deps.join(","),
        doctor.install_hint
    );
    if !doctor.ok || !is_supported_virtual_resolver(&doctor.resolver) {
        return Err(format!(
            "acceptance aborted: virtual backend is not ready (ok={} resolver={} allow_monitor_object={})",
            doctor.ok,
            doctor.resolver,
            allow_monitor_object()
        ));
    }

    if doctor.resolver == MONITOR_OBJECT_RESOLVER {
        println!(
            "[acceptance] monitor-object fallback active; skipping real-output RandR topology assertion"
        );
        let _ = start_session(opts)?;
        let reached_streaming = wait_for_state(opts, "STREAMING", Duration::from_secs(8))?;
        let _ = stop_session(opts);
        if !reached_streaming {
            return Err("acceptance failed: session did not reach STREAMING".to_string());
        }
        println!("[acceptance] OK (fallback mode)");
        return Ok(());
    }

    let before = xrandr_topology()?;
    println!(
        "[acceptance] before screen={}x{} connected={} active={}",
        before.screen_w,
        before.screen_h,
        before.connected_outputs().len(),
        before.active_outputs().len()
    );

    let mut started = false;
    let run_result = (|| -> Result<(), String> {
        let _ = start_session(opts)?;
        started = true;

        let reached_streaming = wait_for_state(opts, "STREAMING", Duration::from_secs(8))?;
        if !reached_streaming {
            return Err("acceptance failed: session did not reach STREAMING".to_string());
        }

        let after = xrandr_topology()?;
        println!(
            "[acceptance] after screen={}x{} connected={} active={}",
            after.screen_w,
            after.screen_h,
            after.connected_outputs().len(),
            after.active_outputs().len()
        );

        validate_extended_topology(&before, &after)?;
        Ok(())
    })();

    if started {
        let _ = stop_session(opts);
    }

    run_result?;
    println!("[acceptance] OK");
    Ok(())
}

#[derive(Debug)]
struct DoctorSnapshot {
    ok: bool,
    resolver: String,
    missing_deps: Vec<String>,
    install_hint: String,
}

fn virtual_doctor(opts: &Opts) -> Result<DoctorSnapshot, String> {
    let url = api_url(opts, "/v1/virtual/doctor", true);
    let body = curl_json("GET", &url)?;
    let json: Value =
        serde_json::from_str(&body).map_err(|e| format!("invalid JSON from doctor API: {e}"))?;

    let ok = json.get("ok").and_then(|v| v.as_bool()).unwrap_or(false);
    let resolver = json
        .get("resolver")
        .and_then(|v| v.as_str())
        .unwrap_or("none")
        .to_string();
    let missing_deps = json
        .get("missing_deps")
        .and_then(|v| v.as_array())
        .map(|arr| {
            arr.iter()
                .filter_map(|i| i.as_str().map(|s| s.to_string()))
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();
    let install_hint = json
        .get("install_hint")
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string();

    Ok(DoctorSnapshot {
        ok,
        resolver,
        missing_deps,
        install_hint,
    })
}

#[derive(Debug, Clone)]
struct X11Env {
    display: String,
    xauthority: Option<String>,
}

#[derive(Debug, Clone)]
struct XrandrOutput {
    name: String,
    connected: bool,
    geometry: Option<(i32, i32, u32, u32)>,
}

#[derive(Debug, Clone)]
struct XrandrTopology {
    screen_w: u32,
    screen_h: u32,
    outputs: Vec<XrandrOutput>,
}

fn start_session(opts: &Opts) -> Result<Value, String> {
    let serial = require_serial(opts)?;
    let stream_port = require_stream_port(opts)?;

    let doctor = virtual_doctor(opts)?;
    if !doctor.ok || !is_supported_virtual_resolver(&doctor.resolver) {
        return Err(format!(
            "virtual monitor preflight failed: ok={} resolver={} allow_monitor_object={} missing=[{}] hint={}",
            doctor.ok,
            doctor.resolver,
            allow_monitor_object(),
            doctor.missing_deps.join(","),
            doctor.install_hint
        ));
    }

    let mut start_opts = opts.clone();
    start_opts.serial = Some(serial.to_string());
    start_opts.stream_port = Some(stream_port);
    let mut url = api_url(&start_opts, "/v1/start", true);
    append_query(&mut url, "display_mode", "virtual_monitor");

    let body = match curl_json("POST", &url) {
        Ok(body) => body,
        Err(e) if e.contains("Operation timed out") => {
            thread::sleep(Duration::from_millis(1200));
            curl_json("POST", &url)?
        }
        Err(e) => return Err(e),
    };
    let json: Value =
        serde_json::from_str(&body).map_err(|e| format!("invalid JSON from start API: {e}"))?;
    let ok = json.get("ok").and_then(|v| v.as_bool()).unwrap_or(false);
    if !ok {
        let error = json
            .get("error")
            .and_then(|v| v.as_str())
            .unwrap_or("start failed without error message");
        return Err(error.to_string());
    }
    Ok(json)
}

fn allow_monitor_object() -> bool {
    read_policy_bool("ALLOW_MONITOR_OBJECT", true)
}

fn is_supported_virtual_resolver(resolver: &str) -> bool {
    resolver == REAL_OUTPUT_RESOLVER
        || (allow_monitor_object() && resolver == MONITOR_OBJECT_RESOLVER)
}

fn read_policy_bool(key: &str, default: bool) -> bool {
    let path = env::var("XDG_CONFIG_HOME")
        .ok()
        .filter(|v| !v.trim().is_empty())
        .map(|v| PathBuf::from(v).join("wbeam/x11-virtual-policy.conf"))
        .or_else(|| {
            env::var("HOME")
                .ok()
                .filter(|v| !v.trim().is_empty())
                .map(|home| PathBuf::from(home).join(".config/wbeam/x11-virtual-policy.conf"))
        })
        .or_else(|| {
            env::var("USER")
                .ok()
                .filter(|v| !v.trim().is_empty())
                .map(|user| PathBuf::from(format!("/home/{user}")).join(".config/wbeam/x11-virtual-policy.conf"))
        });
    let Some(path) = path else {
        return default;
    };
    let Ok(raw) = fs::read_to_string(path) else {
        return default;
    };
    for line in raw.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let Some((k, v)) = line.split_once('=') else {
            continue;
        };
        if k.trim() != key {
            continue;
        }
        let low = v.trim().to_ascii_lowercase();
        return low == "1" || low == "true" || low == "on" || low == "yes";
    }
    default
}

fn stop_session(opts: &Opts) -> Result<Value, String> {
    let serial = require_serial(opts)?;
    let stream_port = require_stream_port(opts)?;
    let mut stop_opts = opts.clone();
    stop_opts.serial = Some(serial.to_string());
    stop_opts.stream_port = Some(stream_port);
    let url = api_url(&stop_opts, "/v1/stop", true);
    let body = curl_json("POST", &url)?;
    serde_json::from_str(&body).map_err(|e| format!("invalid JSON from stop API: {e}"))
}

fn status_session(opts: &Opts) -> Result<Value, String> {
    let url = api_url(opts, "/v1/status", true);
    let body = curl_json("GET", &url)?;
    serde_json::from_str(&body).map_err(|e| format!("invalid JSON from status API: {e}"))
}

fn wait_for_state(opts: &Opts, wanted: &str, timeout: Duration) -> Result<bool, String> {
    let start = std::time::Instant::now();
    while start.elapsed() <= timeout {
        let json = status_session(opts)?;
        let state = json
            .get("state")
            .and_then(|v| v.as_str())
            .unwrap_or("unknown");
        println!("[wait] state={state}");
        if state == wanted {
            return Ok(true);
        }
        thread::sleep(Duration::from_millis(300));
    }
    Ok(false)
}

fn reset_session_best_effort(opts: &Opts) {
    if stop_session(opts).is_ok() {
        let _ = wait_for_state(opts, "IDLE", Duration::from_secs(2));
    }
}

fn xrandr_topology() -> Result<XrandrTopology, String> {
    let env = detect_x11_env()?;
    let mut cmd = Command::new("xrandr");
    cmd.arg("--query");
    cmd.env("DISPLAY", &env.display);
    if let Some(xauth) = env.xauthority.as_deref() {
        cmd.env("XAUTHORITY", xauth);
    }
    let output = cmd
        .output()
        .map_err(|e| format!("failed to run xrandr --query: {e}"))?;
    if !output.status.success() {
        let err = String::from_utf8_lossy(&output.stderr).trim().to_string();
        return Err(format!(
            "xrandr --query failed for DISPLAY={} XAUTHORITY={}: {}",
            env.display,
            env.xauthority.as_deref().unwrap_or("-"),
            if err.is_empty() {
                format!("status={}", output.status)
            } else {
                err
            }
        ));
    }
    let raw = String::from_utf8_lossy(&output.stdout);
    parse_xrandr_topology(&raw)
}

fn validate_extended_topology(
    before: &XrandrTopology,
    after: &XrandrTopology,
) -> Result<(), String> {
    let mut before_by_name = std::collections::HashMap::<&str, Option<(i32, i32, u32, u32)>>::new();
    for out in &before.outputs {
        before_by_name.insert(out.name.as_str(), out.geometry);
    }

    // On provider-link topologies, an activated virtual output may remain
    // marked as "disconnected" while still having geometry and extending fb.
    let newly_activated = after
        .active_outputs()
        .into_iter()
        .filter(|out| match before_by_name.get(out.name.as_str()) {
            Some(prev_geom) => prev_geom.is_none(),
            None => true,
        })
        .collect::<Vec<_>>();
    if newly_activated.is_empty() {
        return Err(
            "RandR topology did not expose a newly activated output after start".to_string(),
        );
    }

    let with_geom = newly_activated
        .iter()
        .find_map(|o| o.geometry.map(|g| ((*o).name.clone(), g, (*o).connected)))
        .ok_or_else(|| "newly activated output has no geometry".to_string())?;

    let mirrored = after
        .active_outputs()
        .into_iter()
        .any(|other| other.name != with_geom.0 && other.geometry == Some(with_geom.1));
    if mirrored {
        return Err(format!(
            "new output {} mirrors an existing output at {:?}, expected extended desktop",
            with_geom.0, with_geom.1
        ));
    }
    if !with_geom.2 {
        println!(
            "[acceptance] note: output {} is active with geometry {:?} but xrandr still reports it as disconnected (provider-link topology)",
            with_geom.0, with_geom.1
        );
    }

    if after.screen_w == before.screen_w && after.screen_h == before.screen_h {
        println!(
            "[acceptance] warning: root framebuffer size unchanged ({}x{}), but new output {} is active at {:?}",
            after.screen_w, after.screen_h, with_geom.0, with_geom.1
        );
    }
    Ok(())
}

fn detect_x11_env() -> Result<X11Env, String> {
    let mut display = env::var("DISPLAY").unwrap_or_default();
    let mut xauthority = env::var("XAUTHORITY").ok();

    if display.trim().is_empty() || xauthority.as_deref().unwrap_or("").trim().is_empty() {
        if let Some((sys_display, sys_xauth)) = read_systemd_user_x11_env() {
            if display.trim().is_empty() {
                display = sys_display;
            }
            if xauthority.as_deref().unwrap_or("").trim().is_empty() {
                xauthority = Some(sys_xauth);
            }
        }
    }

    if display.trim().is_empty() {
        if let Some(json) = probe_host_json().ok() {
            display = json
                .get("details")
                .and_then(|v| v.get("session"))
                .and_then(|v| v.get("display"))
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();
            if xauthority.as_deref().unwrap_or("").trim().is_empty() {
                xauthority = json
                    .get("details")
                    .and_then(|v| v.get("session"))
                    .and_then(|v| v.get("xauthority"))
                    .and_then(|v| v.as_str())
                    .map(|s| s.to_string());
            }
        }
    }

    if display.trim().is_empty() {
        return Err("DISPLAY not available for xrandr topology checks".to_string());
    }

    Ok(X11Env {
        display,
        xauthority,
    })
}

fn read_systemd_user_x11_env() -> Option<(String, String)> {
    let output = Command::new("systemctl")
        .args(["--user", "show-environment"])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    let mut display = String::new();
    let mut xauthority = String::new();
    for line in stdout.lines() {
        if let Some(v) = line.strip_prefix("DISPLAY=") {
            display = v.trim().to_string();
        } else if let Some(v) = line.strip_prefix("XAUTHORITY=") {
            xauthority = v.trim().to_string();
        }
    }
    if display.is_empty() || xauthority.is_empty() {
        return None;
    }
    Some((display, xauthority))
}

fn parse_xrandr_topology(raw: &str) -> Result<XrandrTopology, String> {
    let mut screen_w = 0u32;
    let mut screen_h = 0u32;
    let mut outputs = Vec::<XrandrOutput>::new();

    for line in raw.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        if trimmed.starts_with("Screen ") && trimmed.contains(" current ") {
            if let Some((w, h)) = parse_screen_current(trimmed) {
                screen_w = w;
                screen_h = h;
            }
            continue;
        }
        if line.starts_with(' ') || line.starts_with('\t') {
            continue;
        }
        let parts = trimmed.split_whitespace().collect::<Vec<_>>();
        if parts.len() < 2 {
            continue;
        }
        if parts[1] != "connected" && parts[1] != "disconnected" {
            continue;
        }
        let connected_flag = parts[1] == "connected";
        outputs.push(XrandrOutput {
            name: parts[0].to_string(),
            connected: connected_flag,
            geometry: parse_output_geometry(trimmed),
        });
    }

    if screen_w == 0 || screen_h == 0 {
        return Err("failed to parse xrandr current screen size".to_string());
    }

    Ok(XrandrTopology {
        screen_w,
        screen_h,
        outputs,
    })
}

impl XrandrTopology {
    fn connected_outputs(&self) -> Vec<&XrandrOutput> {
        self.outputs.iter().filter(|o| o.connected).collect()
    }

    fn active_outputs(&self) -> Vec<&XrandrOutput> {
        self.outputs.iter().filter(|o| o.geometry.is_some()).collect()
    }
}

fn parse_screen_current(line: &str) -> Option<(u32, u32)> {
    let current_part = line.split("current").nth(1)?;
    let dims = current_part.split(',').next()?.trim();
    let mut parts = dims.split('x');
    let w = parts.next()?.trim().parse::<u32>().ok()?;
    let h = parts.next()?.trim().parse::<u32>().ok()?;
    Some((w, h))
}

fn parse_output_geometry(line: &str) -> Option<(i32, i32, u32, u32)> {
    for tok in line.split_whitespace() {
        if !tok.contains('x') || !tok.contains('+') {
            continue;
        }
        let (wh, xy) = tok.split_once('+')?;
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

#[cfg(test)]
mod tests {
    use super::{parse_xrandr_topology, validate_extended_topology};

    #[test]
    fn acceptance_handles_disconnected_but_active_virtual_output() {
        let before = r#"Screen 0: minimum 320 x 200, current 1920 x 1080, maximum 16384 x 16384
eDP-1 connected primary 1920x1080+0+0 (normal left inverted right x axis y axis)
HDMI-1 disconnected (normal left inverted right x axis y axis)
DVI-I-1-1 disconnected (normal left inverted right x axis y axis)"#;

        let after = r#"Screen 0: minimum 320 x 200, current 3120 x 2000, maximum 16384 x 16384
eDP-1 connected primary 1920x1080+0+0 (normal left inverted right x axis y axis)
HDMI-1 disconnected (normal left inverted right x axis y axis)
DVI-I-1-1 disconnected 1200x2000+1920+0 (normal left inverted right x axis y axis)
   WBEAM_1200x2000_60_00917  59.96*"#;

        let before_topo = parse_xrandr_topology(before).expect("before topology");
        let after_topo = parse_xrandr_topology(after).expect("after topology");
        let check = validate_extended_topology(&before_topo, &after_topo);
        assert!(
            check.is_ok(),
            "expected disconnected-but-active output to pass validation: {check:?}"
        );
    }
}

fn probe_host_json() -> Result<Value, String> {
    let repo = find_repo_root()?;
    let script = repo.join("host/scripts/probe_host.py");
    let output = Command::new("python3")
        .arg(&script)
        .output()
        .map_err(|e| format!("failed to execute python3: {e}"))?;
    if !output.status.success() {
        let err = String::from_utf8_lossy(&output.stderr).trim().to_string();
        return Err(format!("probe_host failed: {err}"));
    }
    let body = String::from_utf8_lossy(&output.stdout).trim().to_string();
    serde_json::from_str(&body).map_err(|e| format!("probe_host invalid JSON: {e}"))
}

fn require_serial(opts: &Opts) -> Result<&str, String> {
    opts.serial
        .as_deref()
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| "--serial is required".to_string())
}

fn require_stream_port(opts: &Opts) -> Result<u16, String> {
    opts.stream_port
        .filter(|p| *p > 0)
        .ok_or_else(|| "--stream-port is required".to_string())
}

fn api_url(opts: &Opts, path: &str, include_device_query: bool) -> String {
    let mut url = format!("http://127.0.0.1:{}{}", opts.control_port, path);
    if include_device_query {
        let mut has_q = false;
        if let Some(serial) = opts.serial.as_deref() {
            append_query_inner(&mut url, &mut has_q, "serial", serial);
        }
        if let Some(port) = opts.stream_port {
            append_query_inner(&mut url, &mut has_q, "stream_port", &port.to_string());
        }
    }
    url
}

fn append_query(url: &mut String, key: &str, value: &str) {
    let mut has_q = url.contains('?');
    append_query_inner(url, &mut has_q, key, value);
}

fn append_query_inner(url: &mut String, has_q: &mut bool, key: &str, value: &str) {
    let sep = if *has_q { '&' } else { '?' };
    url.push(sep);
    *has_q = true;
    url.push_str(&pct_encode(key));
    url.push('=');
    url.push_str(&pct_encode(value));
}

fn pct_encode(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    for b in raw.bytes() {
        let keep = b.is_ascii_alphanumeric() || matches!(b, b'-' | b'_' | b'.' | b'~');
        if keep {
            out.push(char::from(b));
        } else {
            out.push('%');
            out.push_str(&format!("{:02X}", b));
        }
    }
    out
}

fn curl_json(method: &str, url: &str) -> Result<String, String> {
    let max_time = env::var("WBEAM_X11_CURL_MAX_TIME_SEC")
        .ok()
        .filter(|v| !v.trim().is_empty())
        .unwrap_or_else(|| DEFAULT_CURL_MAX_TIME_SEC.to_string());
    let output = Command::new("curl")
        .args([
            "-sS",
            "--connect-timeout",
            "2",
            "--max-time",
            &max_time,
            "-X",
            method,
            url,
        ])
        .output()
        .map_err(|e| format!("failed to execute curl: {e}"))?;

    if !output.status.success() {
        let err = String::from_utf8_lossy(&output.stderr).trim().to_string();
        return Err(if err.is_empty() {
            format!("curl failed with status {}", output.status)
        } else {
            err
        });
    }

    Ok(String::from_utf8_lossy(&output.stdout).trim().to_string())
}

fn print_json_pretty(body: &str) -> Result<(), String> {
    let json: Value = serde_json::from_str(body).map_err(|e| format!("invalid JSON: {e}"))?;
    print_json_pretty_value(&json)
}

fn print_json_pretty_value(json: &Value) -> Result<(), String> {
    let pretty =
        serde_json::to_string_pretty(json).map_err(|e| format!("failed to format JSON: {e}"))?;
    println!("{pretty}");
    Ok(())
}

fn find_repo_root() -> Result<PathBuf, String> {
    let cwd = env::current_dir().map_err(|e| format!("failed to get cwd: {e}"))?;
    find_upwards(&cwd, ".git").ok_or_else(|| "failed to locate repo root".to_string())
}

fn find_upwards(start: &Path, marker: &str) -> Option<PathBuf> {
    let mut dir = start.to_path_buf();
    loop {
        if dir.join(marker).exists() {
            return Some(dir);
        }
        if !dir.pop() {
            return None;
        }
    }
}
