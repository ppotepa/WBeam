use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::thread::sleep;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tracing::{debug, info, warn};

const CAP_SOURCE_OUTPUT: u32 = 0x1;
const CAP_SINK_OUTPUT: u32 = 0x2;

#[derive(Debug, Clone)]
pub struct X11RealOutputProbe {
    pub supported: bool,
    pub reason: String,
    pub missing_deps: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum X11BackendKind {
    Evdi,
    Vkms,
    Dummy,
    Unknown,
}

#[derive(Debug, Clone)]
pub struct X11RealOutputHandle {
    pub backend_kind: X11BackendKind,
    pub provider_source_id: Option<String>,
    pub provider_sink_id: Option<String>,
    pub output_name: String,
    pub primary_output_name: Option<String>,
    pub added_mode_name: Option<String>,
    pub previous_fb: Option<(u32, u32)>,
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

#[derive(Debug, Clone)]
struct OutputInfo {
    name: String,
    connected: bool,
    modes: Vec<String>,
    geometry: Option<(i32, i32, u32, u32)>,
}

#[derive(Debug, Clone, Copy)]
struct EvdiModuleStatus {
    available: bool,
    loaded: bool,
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
    let evdi = detect_evdi_module_status();

    let providers = match xrandr_output(&["--listproviders"], &display, xauth.as_deref()) {
        Ok(v) => parse_providers(&v),
        Err(e) => {
            return X11RealOutputProbe {
                supported: false,
                reason: format!("xrandr --listproviders failed: {e}"),
                missing_deps: Vec::new(),
            }
        }
    };
    let disp = display.clone();
    debug!(x_display = %disp, providers = providers.len(), "x11 real-output probe: providers");

    let source_provider = choose_source_provider(&providers);
    if source_provider.is_none() {
        if evdi.available && !evdi.loaded {
            warn!("x11 real-output probe: evdi installed but module not loaded");
            return X11RealOutputProbe {
                supported: false,
                reason: "evdi module is installed but not loaded (run: sudo modprobe evdi initial_device_count=1)".to_string(),
                missing_deps: vec!["evdi-module-loaded".to_string()],
            };
        }
        warn!("x11 real-output probe: no source provider with virtual output capability");
        return X11RealOutputProbe {
            supported: false,
            reason: "no source provider with virtual output capability found".to_string(),
            missing_deps: vec!["evdi-provider".to_string()],
        };
    }

    let sink_provider = choose_sink_provider(&providers, source_provider.unwrap());
    if sink_provider.is_none() {
        warn!("x11 real-output probe: no sink provider for provider link");
        return X11RealOutputProbe {
            supported: false,
            reason: "no sink provider found for provider link".to_string(),
            missing_deps: vec!["xrandr-sink-provider".to_string()],
        };
    }

    let query = match xrandr_output(&["--query"], &display, xauth.as_deref()) {
        Ok(v) => v,
        Err(e) => {
            return X11RealOutputProbe {
                supported: false,
                reason: format!("xrandr --query failed: {e}"),
                missing_deps: Vec::new(),
            }
        }
    };
    let outputs = parse_outputs(&query);
    debug!(outputs = outputs.len(), "x11 real-output probe: outputs");
    if outputs.is_empty() {
        return X11RealOutputProbe {
            supported: false,
            reason: "xrandr reported no outputs in this session".to_string(),
            missing_deps: Vec::new(),
        };
    }

    if choose_candidate_output(&outputs).is_none() {
        if has_aux_sink_only_provider(&providers) {
            warn!(
                "x11 real-output probe: no disconnected output pre-link, but sink-only provider exists; deferring output detection to activation"
            );
            return X11RealOutputProbe {
                supported: true,
                reason: "providers detected; output candidate will be resolved during activation"
                    .to_string(),
                missing_deps: Vec::new(),
            };
        }
        warn!("x11 real-output probe: no disconnected virtual-capable output candidate");
        return X11RealOutputProbe {
            supported: false,
            reason: "no disconnected virtual-capable output found".to_string(),
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
    debug!(providers = providers.len(), "x11 real-output create: parsed providers");
    let source = choose_source_provider(&providers)
        .ok_or_else(|| "no source provider found for virtual output".to_string())?;
    let sink = choose_sink_provider(&providers, source)
        .ok_or_else(|| "no sink provider found for source provider".to_string())?;
    info!(
        source_provider_id = %source.id,
        source_provider_name = %source.name,
        sink_provider_id = %sink.id,
        sink_provider_name = %sink.name,
        "x11 real-output create: selected providers"
    );

    let _ = xrandr(
        &["--setprovideroutputsource", &sink.id, &source.id],
        &display,
        xauth.as_deref(),
    );

    wait_for_xrandr_settle(&display, xauth.as_deref());

    let query = xrandr_output(&["--query"], &display, xauth.as_deref())
        .map_err(|e| format!("xrandr --query failed: {e}"))?;
    let outputs = parse_outputs(&query);
    debug!(outputs = outputs.len(), "x11 real-output create: parsed outputs");

    let output = choose_candidate_output(&outputs)
        .ok_or_else(|| "no disconnected output available for virtual monitor".to_string())?;
    info!(output = %output.name, "x11 real-output create: selected output candidate");

    let (w, h) = parse_size(size);
    let primary = outputs.iter().find(|o| o.connected).map(|o| o.name.clone());
    let previous_fb = parse_current_fb(&query);

    let desired_mode = format!("{w}x{h}");
    let mut chosen_mode = output
        .modes
        .iter()
        .find(|m| m.trim() == desired_mode || m.trim().starts_with(&desired_mode))
        .map(|m| m.trim().to_string());
    let mut added_mode_name = None;

    if chosen_mode.is_none() {
        if let Ok(mode_name) = ensure_mode_with_cvt(&output.name, w, h, &display, xauth.as_deref())
        {
            chosen_mode = Some(mode_name.clone());
            added_mode_name = Some(mode_name);
        }
    }

    wait_for_xrandr_settle(&display, xauth.as_deref());
    let query_after_mode = xrandr_output(&["--query"], &display, xauth.as_deref())
        .map_err(|e| format!("xrandr --query after mode injection failed: {e}"))?;
    let outputs_after_mode = parse_outputs(&query_after_mode);
    if chosen_mode.is_none() {
        if let Some(refreshed) = outputs_after_mode.iter().find(|o| o.name == output.name) {
            chosen_mode = refreshed
                .modes
                .iter()
                .find(|m| m.trim() == desired_mode || m.trim().starts_with(&desired_mode))
                .map(|m| m.trim().to_string());
        }
    }

    let chosen_mode = chosen_mode.ok_or_else(|| {
        format!(
            "failed to find usable mode for output {} (wanted {desired_mode})",
            output.name
        )
    })?;

    let mut args = vec![
        "--output".to_string(),
        output.name.clone(),
        "--mode".to_string(),
        chosen_mode.clone(),
    ];
    if let Some(p) = primary.as_deref() {
        args.push("--right-of".to_string());
        args.push(p.to_string());
    }
    let arg_refs = args.iter().map(|s| s.as_str()).collect::<Vec<_>>();
    xrandr(&arg_refs, &display, xauth.as_deref())
        .map_err(|e| format!("failed to enable output {}: {e}", output.name))?;

    wait_for_xrandr_settle(&display, xauth.as_deref());

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

    let mirrored_with = outputs_after
        .iter()
        .filter(|o| o.name != output.name && o.connected)
        .filter_map(|o| {
            o.geometry.and_then(|(ox, oy, ow, oh)| {
                if ox == x && oy == y && ow == aw && oh == ah {
                    Some(o.name.clone())
                } else {
                    None
                }
            })
        })
        .collect::<Vec<_>>();

    if !mirrored_with.is_empty() {
        warn!(
            output = %output.name,
            mirrors = %mirrored_with.join(","),
            "x11 real-output create: enabled output mirrors active output(s)"
        );
        return Err(format!(
            "output {} enabled but mirrors active output(s): {} at {}x{}+{}+{}",
            output.name,
            mirrored_with.join(","),
            aw,
            ah,
            x,
            y
        ));
    }

    Ok(X11RealOutputHandle {
        backend_kind: classify_backend_kind(&output.name, &source.name),
        provider_source_id: Some(source.id.clone()),
        provider_sink_id: Some(sink.id.clone()),
        output_name: output.name.clone(),
        primary_output_name: primary,
        added_mode_name,
        previous_fb,
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

    let _ = xrandr(
        &["--output", &handle.output_name, "--off"],
        &display,
        xauth.as_deref(),
    );

    if let Some(mode_name) = handle.added_mode_name.as_deref() {
        let _ = xrandr(
            &["--delmode", &handle.output_name, mode_name],
            &display,
            xauth.as_deref(),
        );
        let _ = xrandr(&["--rmmode", mode_name], &display, xauth.as_deref());
    }

    if let Some(sink_id) = handle.provider_sink_id.as_deref() {
        let _ = xrandr(
            &["--setprovideroutputsource", sink_id, "0x0"],
            &display,
            xauth.as_deref(),
        );
    }

    if let Some((w, h)) = handle.previous_fb {
        let _ = xrandr(&["--fb", &format!("{w}x{h}")], &display, xauth.as_deref());
    }
    info!(output = %handle.output_name, "x11 real-output destroy: cleanup complete");

    Ok(())
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

fn choose_source_provider<'a>(providers: &'a [ProviderInfo]) -> Option<&'a ProviderInfo> {
    let strict = providers
        .iter()
        .filter(|p| has_cap(p.caps, CAP_SOURCE_OUTPUT))
        .filter(|p| looks_virtual_provider_name(&p.name))
        .max_by_key(|p| score_source_provider(p));
    if strict.is_some() {
        return strict;
    }

    let non_gpu = providers
        .iter()
        .filter(|p| has_cap(p.caps, CAP_SOURCE_OUTPUT))
        .filter(|p| !looks_gpu_provider_name(&p.name))
        .max_by_key(|p| score_source_provider(p));
    if non_gpu.is_some() {
        return non_gpu;
    }

    if has_aux_sink_only_provider(providers) {
        return providers
            .iter()
            .filter(|p| has_cap(p.caps, CAP_SOURCE_OUTPUT))
            .max_by_key(|p| score_source_provider(p));
    }

    None
}

fn has_aux_sink_only_provider(providers: &[ProviderInfo]) -> bool {
    providers.len() >= 2
        && providers
            .iter()
            .any(|p| has_cap(p.caps, CAP_SINK_OUTPUT) && !has_cap(p.caps, CAP_SOURCE_OUTPUT))
}

fn choose_sink_provider<'a>(
    providers: &'a [ProviderInfo],
    source: &ProviderInfo,
) -> Option<&'a ProviderInfo> {
    let strict = providers
        .iter()
        .filter(|p| p.id != source.id)
        .filter(|p| has_cap(p.caps, CAP_SINK_OUTPUT))
        .max_by_key(|p| score_sink_provider(p));
    if strict.is_some() {
        return strict;
    }

    providers
        .iter()
        .filter(|p| p.id != source.id)
        .filter(|p| is_modesetting_name(&p.name) || looks_gpu_provider_name(&p.name))
        .max_by_key(|p| score_sink_provider(p))
}

fn choose_candidate_output(outputs: &[OutputInfo]) -> Option<&OutputInfo> {
    let strict = outputs
        .iter()
        .filter(|o| !o.connected)
        .filter(|o| output_looks_virtual(&o.name))
        .max_by_key(|o| score_output(o));
    if strict.is_some() {
        return strict;
    }

    outputs
        .iter()
        .filter(|o| !o.connected)
        .filter(|o| o.geometry.is_none())
        .filter(|o| !output_looks_likely_physical(&o.name))
        .max_by_key(|o| score_output(o))
}

fn score_source_provider(provider: &ProviderInfo) -> i32 {
    let mut score = 0;
    if has_cap(provider.caps, CAP_SOURCE_OUTPUT) {
        score += 30;
    }
    if is_evdi_name(&provider.name) {
        score += 100;
    }
    if is_vkms_name(&provider.name) {
        score += 60;
    }
    if looks_gpu_provider_name(&provider.name) {
        score -= 40;
    }
    score
}

fn score_sink_provider(provider: &ProviderInfo) -> i32 {
    let mut score = 0;
    if has_cap(provider.caps, CAP_SINK_OUTPUT) {
        score += 40;
    }
    if is_modesetting_name(&provider.name) {
        score += 20;
    }
    if is_evdi_name(&provider.name) {
        score -= 50;
    }
    score
}

fn score_output(output: &OutputInfo) -> i32 {
    let mut score = 0;
    if !output.connected {
        score += 80;
    } else {
        score -= 40;
    }
    if output.geometry.is_none() {
        score += 20;
    }
    if output_looks_virtual(&output.name) {
        score += 60;
    }
    if output_looks_likely_physical(&output.name) {
        score -= 20;
    }
    if output.modes.is_empty() {
        score += 10;
    }
    score
}

fn classify_backend_kind(output_name: &str, provider_name: &str) -> X11BackendKind {
    if is_evdi_name(provider_name) || output_name.to_ascii_lowercase().contains("evdi") {
        X11BackendKind::Evdi
    } else if is_vkms_name(provider_name) || output_name.to_ascii_lowercase().contains("vkms") {
        X11BackendKind::Vkms
    } else if output_name.to_ascii_lowercase().contains("dummy") {
        X11BackendKind::Dummy
    } else {
        X11BackendKind::Unknown
    }
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

    let candidates = [
        generate_cvt_modeline(w, h, false),
        generate_cvt_modeline(w, h, true),
    ];
    let uniq = mode_suffix();

    for modeline in candidates.into_iter().flatten() {
        let unique_mode_name = format!("WBEAM_{}x{}_60_{}", w, h, uniq);
        let (base_name, rest) = split_modeline(&modeline)?;
        let mode_name = if base_name.is_empty() {
            unique_mode_name.clone()
        } else {
            unique_mode_name.clone()
        };

        let mut newmode_args = vec!["--newmode".to_string(), mode_name.clone()];
        newmode_args.extend(rest.split_whitespace().map(|s| s.to_string()));
        let newmode_refs = newmode_args.iter().map(|s| s.as_str()).collect::<Vec<_>>();

        let newmode_result = xrandr(&newmode_refs, display, xauth);
        if let Err(err) = &newmode_result {
            if !err.to_ascii_lowercase().contains("already exists") {
                continue;
            }
        }

        let addmode_result = xrandr(&["--addmode", output, &mode_name], display, xauth);
        if addmode_result.is_ok() {
            return Ok(mode_name);
        }
    }

    Err("failed to inject mode with cvt/cvt -r".to_string())
}

fn split_modeline(modeline_line: &str) -> Result<(String, String), String> {
    let line = modeline_line
        .trim()
        .trim_start_matches("Modeline")
        .trim()
        .to_string();
    let (name_quoted, rest) = line
        .split_once(' ')
        .ok_or_else(|| "invalid cvt modeline format".to_string())?;
    Ok((name_quoted.trim_matches('"').to_string(), rest.to_string()))
}

fn generate_cvt_modeline(w: u32, h: u32, reduced_blanking: bool) -> Option<String> {
    let mut cmd = Command::new("cvt");
    if reduced_blanking {
        cmd.arg("-r");
    }
    cmd.arg(w.to_string()).arg(h.to_string()).arg("60");

    let out = cmd.output().ok()?;
    if !out.status.success() {
        return None;
    }

    let stdout = String::from_utf8_lossy(&out.stdout);
    stdout
        .lines()
        .find(|l| l.trim_start().starts_with("Modeline"))
        .map(|s| s.trim().to_string())
}

fn mode_suffix() -> String {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);
    let short = (nanos % 100_000) as u64;
    format!("{short:05}")
}

fn wait_for_xrandr_settle(display: &str, xauth: Option<&Path>) {
    for _ in 0..10 {
        sleep(Duration::from_millis(150));
        let _ = xrandr_output(&["--query"], display, xauth);
    }
}

fn looks_mode(token: &str) -> bool {
    let mut p = token.split('x');
    p.next().and_then(|v| v.parse::<u32>().ok()).is_some()
        && p.next().and_then(|v| v.parse::<u32>().ok()).is_some()
}

fn output_looks_virtual(name: &str) -> bool {
    let low = name.to_ascii_lowercase();
    low.contains("virtual")
        || low.contains("evdi")
        || low.contains("dummy")
        || low.contains("vkms")
        || low.starts_with("dvi-i-")
        || low.starts_with("dvi-d-")
}

fn output_looks_likely_physical(name: &str) -> bool {
    let low = name.to_ascii_lowercase();
    low.starts_with("edp")
        || low.starts_with("dp-")
        || low.starts_with("hdmi-")
        || low.starts_with("vga-")
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

fn is_vkms_name(name: &str) -> bool {
    name.to_ascii_lowercase().contains("vkms")
}

fn is_modesetting_name(name: &str) -> bool {
    name.to_ascii_lowercase().contains("modesetting")
}

fn looks_gpu_provider_name(name: &str) -> bool {
    let low = name.to_ascii_lowercase();
    low.contains("nvidia")
        || low.contains("amd")
        || low.contains("intel")
        || low.contains("modesetting")
}

fn looks_virtual_provider_name(name: &str) -> bool {
    let low = name.to_ascii_lowercase();
    low.contains("evdi")
        || low.contains("displaylink")
        || low.contains("vkms")
        || low.contains("virtual")
        || low.contains("dummy")
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

fn detect_evdi_module_status() -> EvdiModuleStatus {
    let available = if command_exists("modinfo") {
        Command::new("modinfo")
            .arg("evdi")
            .status()
            .map(|s| s.success())
            .unwrap_or(false)
    } else {
        false
    };

    let loaded = fs::read_to_string("/proc/modules")
        .ok()
        .map(|raw| raw.lines().any(|line| line.starts_with("evdi ")))
        .unwrap_or(false);

    EvdiModuleStatus { available, loaded }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_providers_extracts_caps_and_ids() {
        let raw = r#"
Providers: number : 2
Provider 0: id: 0x6f cap: 0x1, Source Output crtcs: 4 outputs: 1 associated providers: 1 name:evdi
Provider 1: id: 0x48 cap: 0xf, Source Output, Sink Output, Source Offload, Sink Offload crtcs: 4 outputs: 6 associated providers: 1 name:modesetting
"#;
        let providers = parse_providers(raw);
        assert_eq!(providers.len(), 2);
        assert_eq!(providers[0].id, "0x6f");
        assert_eq!(providers[0].name, "evdi");
        assert_eq!(providers[0].caps, 0x1);
        assert_eq!(providers[1].id, "0x48");
        assert_eq!(providers[1].name, "modesetting");
        assert_eq!(providers[1].caps, 0xf);
    }

    #[test]
    fn choose_source_and_sink_prefers_virtual_source_and_modesetting_sink() {
        let providers = vec![
            ProviderInfo {
                id: "0x6f".to_string(),
                name: "evdi".to_string(),
                caps: CAP_SOURCE_OUTPUT,
            },
            ProviderInfo {
                id: "0x48".to_string(),
                name: "modesetting".to_string(),
                caps: CAP_SOURCE_OUTPUT | CAP_SINK_OUTPUT,
            },
        ];
        let source = choose_source_provider(&providers).expect("source");
        assert_eq!(source.name, "evdi");
        let sink = choose_sink_provider(&providers, source).expect("sink");
        assert_eq!(sink.name, "modesetting");
    }

    #[test]
    fn choose_sink_falls_back_to_gpu_name_when_sink_cap_missing() {
        let source = ProviderInfo {
            id: "0x6f".to_string(),
            name: "evdi".to_string(),
            caps: CAP_SOURCE_OUTPUT,
        };
        let providers = vec![
            source.clone(),
            ProviderInfo {
                id: "0x48".to_string(),
                name: "modesetting".to_string(),
                caps: CAP_SOURCE_OUTPUT,
            },
        ];
        let sink = choose_sink_provider(&providers, &source).expect("sink");
        assert_eq!(sink.id, "0x48");
    }

    #[test]
    fn choose_candidate_output_prefers_disconnected_virtual() {
        let outputs = vec![
            OutputInfo {
                name: "eDP-1".to_string(),
                connected: true,
                modes: vec!["1920x1080".to_string()],
                geometry: Some((0, 0, 1920, 1080)),
            },
            OutputInfo {
                name: "HDMI-1".to_string(),
                connected: false,
                modes: vec!["1920x1080".to_string()],
                geometry: None,
            },
            OutputInfo {
                name: "DVI-I-1".to_string(),
                connected: false,
                modes: vec!["1280x800".to_string()],
                geometry: None,
            },
        ];
        let selected = choose_candidate_output(&outputs).expect("candidate");
        assert_eq!(selected.name, "DVI-I-1");
    }

    #[test]
    fn choose_source_rejects_gpu_only_provider() {
        let providers = vec![ProviderInfo {
            id: "0x48".to_string(),
            name: "modesetting".to_string(),
            caps: CAP_SOURCE_OUTPUT | CAP_SINK_OUTPUT,
        }];
        assert!(choose_source_provider(&providers).is_none());
    }

    #[test]
    fn choose_source_accepts_sink_only_secondary_topology() {
        let providers = vec![
            ProviderInfo {
                id: "0x48".to_string(),
                name: "modesetting".to_string(),
                caps: CAP_SOURCE_OUTPUT | CAP_SINK_OUTPUT,
            },
            ProviderInfo {
                id: "0x56a".to_string(),
                name: "modesetting".to_string(),
                caps: CAP_SINK_OUTPUT,
            },
        ];
        let source = choose_source_provider(&providers).expect("source");
        assert_eq!(source.id, "0x48");
    }

    #[test]
    fn parse_connected_geometry_and_fb() {
        let line = "eDP-1 connected primary 1920x1080+0+0 (normal left inverted right x axis y axis)";
        assert_eq!(parse_connected_geometry(line), Some((0, 0, 1920, 1080)));

        let query = "Screen 0: minimum 8 x 8, current 3280 x 1080, maximum 32767 x 32767";
        assert_eq!(parse_current_fb(query), Some((3280, 1080)));
    }
}

fn resolve_xauthority() -> Option<PathBuf> {
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
        candidates.sort_by_key(|p| {
            std::fs::metadata(p)
                .and_then(|m| m.modified())
                .ok()
                .unwrap_or(SystemTime::UNIX_EPOCH)
        });
        if let Some(last) = candidates.pop() {
            return Some(last);
        }
    }

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
    None
}
