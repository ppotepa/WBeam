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

#[allow(clippy::cognitive_complexity)]
pub fn probe(is_remote: bool) -> X11RealOutputProbe { // NOSONAR: S3776
    if policy_flag_enabled("DISABLE_REAL_OUTPUT_BACKEND") {
        return probe_unsupported_missing(
            format!(
                "real-output backend disabled by policy file ({})",
                policy_file_location_hint()
            ),
            "real-output-disabled",
        );
    }
    if is_remote {
        return probe_unsupported("remote X11 sessions typically cannot expose real virtual outputs");
    }

    let display = detect_x11_display().unwrap_or_default();
    if display.trim().is_empty() {
        return probe_unsupported("DISPLAY is not set for daemon process");
    }
    if !command_exists("xrandr") {
        return probe_unsupported_missing("xrandr is not installed", "xrandr");
    }

    let xauth = resolve_xauthority_for_display(&display);
    let evdi = detect_evdi_module_status();

    let providers = match xrandr_output(&["--listproviders"], &display, xauth.as_deref()) {
        Ok(v) => parse_providers(&v),
        Err(e) => return probe_unsupported(format!("xrandr --listproviders failed: {e}")),
    };
    let disp = display.clone();
    debug!(x_display = %disp, providers = providers.len(), "x11 real-output probe: providers");
    if let Some(reason) = reject_unsafe_provider_topology(&providers) {
        warn!("{reason}");
        return probe_unsupported_with_missing(reason, vec!["xrandr-safe-topology".to_string()]);
    }

    let source_provider = choose_source_provider(&providers);
    if source_provider.is_none() {
        if evdi.available && !evdi.loaded {
            warn!("x11 real-output probe: evdi installed but module not loaded");
            return probe_unsupported_missing(
                "evdi module is installed but not loaded (run: sudo modprobe evdi initial_device_count=1)",
                "evdi-module-loaded",
            );
        }
        warn!("x11 real-output probe: no source provider with virtual output capability");
        return probe_unsupported_missing(
            "no source provider with virtual output capability found",
            "evdi-provider",
        );
    }

    let source_provider = source_provider.unwrap();
    if let Some(reason) = reject_unsafe_source_provider(source_provider) {
        warn!(
            source_provider_id = %source_provider.id,
            source_provider_name = %source_provider.name,
            "{reason}"
        );
        return probe_unsupported_missing(reason, "xrandr-virtual-source-provider");
    }
    let sink_provider = choose_sink_provider(&providers, source_provider);
    if sink_provider.is_none() {
        warn!("x11 real-output probe: no Intel/AMD (non-NVIDIA) sink provider for provider link");
        return probe_unsupported_missing(
            "no Intel/AMD (non-NVIDIA) sink provider found for provider link",
            "xrandr-igpu-sink-provider",
        );
    }
    let sink_provider = sink_provider.unwrap();
    if should_block_real_output_on_nvidia_evdi(sink_provider) {
        return probe_unsupported(format!(
            "real-output backend auto-disabled on NVIDIA+EVDI with NVIDIA sink provider ({})",
            sink_provider.name
        ));
    }

    let query = match xrandr_output(&["--query"], &display, xauth.as_deref()) {
        Ok(v) => v,
        Err(e) => return probe_unsupported(format!("xrandr --query failed: {e}")),
    };
    let outputs = parse_outputs(&query);
    debug!(outputs = outputs.len(), "x11 real-output probe: outputs");
    if outputs.is_empty() {
        return probe_unsupported("xrandr reported no outputs in this session");
    }

    if choose_candidate_output(&outputs).is_none() {
        if has_aux_sink_only_provider(&providers) {
            warn!(
                "x11 real-output probe: no disconnected output pre-link, but sink-only provider exists; deferring output detection to activation"
            );
            return probe_supported("providers detected; output candidate will be resolved during activation");
        }
        warn!("x11 real-output probe: no disconnected virtual-capable output candidate");
        return probe_unsupported_missing("no disconnected virtual-capable output found", "evdi-output");
    }

    probe_supported("X11 virtual output candidates detected")
}

fn probe_supported(reason: impl Into<String>) -> X11RealOutputProbe {
    X11RealOutputProbe {
        supported: true,
        reason: reason.into(),
        missing_deps: Vec::new(),
    }
}

fn probe_unsupported(reason: impl Into<String>) -> X11RealOutputProbe {
    X11RealOutputProbe {
        supported: false,
        reason: reason.into(),
        missing_deps: Vec::new(),
    }
}

fn probe_unsupported_missing(reason: impl Into<String>, dep: impl Into<String>) -> X11RealOutputProbe {
    probe_unsupported_with_missing(reason, vec![dep.into()])
}

fn probe_unsupported_with_missing(reason: impl Into<String>, missing_deps: Vec<String>) -> X11RealOutputProbe {
    X11RealOutputProbe {
        supported: false,
        reason: reason.into(),
        missing_deps,
    }
}

fn policy_flag_enabled(key: &str) -> bool {
    policy_flag_value(key).unwrap_or(false)
}

fn policy_flag_value(key: &str) -> Option<bool> {
    let path = policy_file_path();
    let Some(path) = path else {
        return None;
    };
    let Ok(raw) = fs::read_to_string(path) else {
        return None;
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
        return Some(low == "1" || low == "true" || low == "on" || low == "yes");
    }
    None
}

fn require_virtual_source_provider() -> bool {
    policy_flag_value("REQUIRE_VIRTUAL_SOURCE_PROVIDER").unwrap_or(true)
}

fn block_provider_link_when_nvidia_present() -> bool {
    policy_flag_value("BLOCK_PROVIDER_LINK_WHEN_NVIDIA_PRESENT").unwrap_or(true)
}

fn has_nvidia_provider(providers: &[ProviderInfo]) -> bool {
    providers.iter().any(|p| is_nvidia_provider_name(&p.name))
}

fn should_block_provider_topology(providers: &[ProviderInfo], nvidia_evdi_combo: bool) -> bool {
    nvidia_evdi_combo && has_nvidia_provider(providers)
}

fn reject_unsafe_provider_topology(providers: &[ProviderInfo]) -> Option<String> {
    if !block_provider_link_when_nvidia_present() {
        return None;
    }
    if should_block_provider_topology(providers, is_nvidia_evdi_combo()) {
        return Some(
            "refusing provider-link: detected NVIDIA+EVDI provider topology (known Xorg crash path)"
                .to_string(),
        );
    }
    None
}

fn reject_unsafe_source_provider(source: &ProviderInfo) -> Option<String> {
    if is_nvidia_provider_name(&source.name) {
        return Some(format!(
            "refusing provider-link: source provider is NVIDIA ({})",
            source.name
        ));
    }
    if require_virtual_source_provider() && !looks_virtual_provider_name(&source.name) {
        return Some(format!(
            "refusing provider-link: source provider is not virtual-capable ({})",
            source.name
        ));
    }
    None
}

fn policy_file_path() -> Option<PathBuf> {
    if let Ok(xdg) = std::env::var("XDG_CONFIG_HOME") {
        let trimmed = xdg.trim();
        if !trimmed.is_empty() {
            return Some(PathBuf::from(trimmed).join("wbeam/x11-virtual-policy.conf"));
        }
    }
    if let Ok(home) = std::env::var("HOME") {
        let trimmed = home.trim();
        if !trimmed.is_empty() {
            return Some(PathBuf::from(trimmed).join(".config/wbeam/x11-virtual-policy.conf"));
        }
    }
    if let Ok(user) = std::env::var("USER") {
        let trimmed = user.trim();
        if !trimmed.is_empty() {
            return Some(
                PathBuf::from(format!("/home/{trimmed}"))
                    .join(".config/wbeam/x11-virtual-policy.conf"),
            );
        }
    }
    None
}

fn policy_file_location_hint() -> String {
    policy_file_path()
        .map(|p| p.display().to_string())
        .unwrap_or_else(|| "<unknown-policy-path>".to_string())
}

fn is_nvidia_evdi_combo() -> bool {
    has_nvidia_kernel_driver() && is_evdi_loaded()
}

fn should_block_real_output_on_nvidia_evdi(sink: &ProviderInfo) -> bool {
    is_nvidia_evdi_combo() && is_nvidia_provider_name(&sink.name)
}

fn has_nvidia_kernel_driver() -> bool {
    if !command_exists("lspci") {
        return false;
    }
    Command::new("lspci")
        .arg("-k")
        .output()
        .ok()
        .map(|o| String::from_utf8_lossy(&o.stdout).to_string())
        .map(|s| s.contains("Kernel driver in use: nvidia"))
        .unwrap_or(false)
}

fn is_evdi_loaded() -> bool {
    fs::read_to_string("/proc/modules")
        .ok()
        .map(|raw| raw.lines().any(|l| l.starts_with("evdi ")))
        .unwrap_or(false)
}

pub fn create(
    _serial: &str,
    size: &str,
    mirror_to_primary: bool,
) -> Result<X11RealOutputHandle, String> {
    let display = detect_x11_display().unwrap_or_default();
    if display.trim().is_empty() {
        return Err("DISPLAY is not set for daemon process".to_string());
    }
    let xauth = resolve_xauthority_for_display(&display);
    let (source, sink) = select_provider_pair(&display, xauth.as_deref())?;

    let _ = xrandr(
        &["--setprovideroutputsource", &sink.id, &source.id],
        &display,
        xauth.as_deref(),
    );
    wait_for_xrandr_settle(&display, xauth.as_deref());

    let (query, outputs) = query_outputs(&display, xauth.as_deref())?;
    let output = choose_candidate_output(&outputs)
        .ok_or_else(|| "no disconnected output available for virtual monitor".to_string())?
        .clone();
    info!(output = %output.name, "x11 real-output create: selected output candidate");

    let (w, h) = parse_size(size);
    let primary = outputs.iter().find(|o| o.connected).map(|o| o.name.clone());
    let previous_fb = parse_current_fb(&query);
    let (chosen_mode, added_mode_name) =
        resolve_mode_for_output(&output, w, h, &display, xauth.as_deref())?;
    enable_output_mode(
        &output.name,
        &chosen_mode,
        primary.as_deref(),
        mirror_to_primary,
        &display,
        xauth.as_deref(),
    )?;

    let (x, y, aw, ah, mirrored_with) =
        inspect_active_output(&output.name, &display, xauth.as_deref())?;
    validate_mirroring_state(
        &output.name,
        mirror_to_primary,
        &mirrored_with,
        x,
        y,
        aw,
        ah,
    )?;

    Ok(X11RealOutputHandle {
        backend_kind: classify_backend_kind(&output.name, &source.name),
        provider_source_id: Some(source.id),
        provider_sink_id: Some(sink.id),
        output_name: output.name,
        primary_output_name: primary,
        added_mode_name,
        previous_fb,
        x,
        y,
        width: aw,
        height: ah,
    })
}

fn select_provider_pair(
    display: &str,
    xauth: Option<&Path>,
) -> Result<(ProviderInfo, ProviderInfo), String> {
    let providers_raw = xrandr_output(&["--listproviders"], display, xauth)
        .map_err(|e| format!("xrandr --listproviders failed: {e}"))?;
    let providers = parse_providers(&providers_raw);
    debug!(
        providers = providers.len(),
        "x11 real-output create: parsed providers"
    );
    if let Some(reason) = reject_unsafe_provider_topology(&providers) {
        return Err(reason);
    }
    let source = choose_source_provider(&providers)
        .ok_or_else(|| "no source provider found for virtual output".to_string())?
        .clone();
    if let Some(reason) = reject_unsafe_source_provider(&source) {
        return Err(reason);
    }
    let sink = choose_sink_provider(&providers, &source)
        .ok_or_else(|| {
            "no Intel/AMD (non-NVIDIA) sink provider found for source provider".to_string()
        })?
        .clone();
    if should_block_real_output_on_nvidia_evdi(&sink) {
        return Err(format!(
            "refusing provider-link on NVIDIA+EVDI with NVIDIA sink provider ({})",
            sink.name
        ));
    }
    info!(
        source_provider_id = %source.id,
        source_provider_name = %source.name,
        sink_provider_id = %sink.id,
        sink_provider_name = %sink.name,
        "x11 real-output create: selected providers"
    );
    Ok((source, sink))
}

fn query_outputs(display: &str, xauth: Option<&Path>) -> Result<(String, Vec<OutputInfo>), String> {
    let query = xrandr_output(&["--query"], display, xauth)
        .map_err(|e| format!("xrandr --query failed: {e}"))?;
    let outputs = parse_outputs(&query);
    debug!(
        outputs = outputs.len(),
        "x11 real-output create: parsed outputs"
    );
    Ok((query, outputs))
}

fn resolve_mode_for_output(
    output: &OutputInfo,
    w: u32,
    h: u32,
    display: &str,
    xauth: Option<&Path>,
) -> Result<(String, Option<String>), String> {
    let desired_mode = format!("{w}x{h}");
    let mut chosen_mode = output
        .modes
        .iter()
        .find(|m| m.trim() == desired_mode || m.trim().starts_with(&desired_mode))
        .map(|m| m.trim().to_string());
    let mut added_mode_name = None;

    if chosen_mode.is_none() {
        if let Ok(mode_name) = ensure_mode_with_cvt(&output.name, w, h, display, xauth) {
            chosen_mode = Some(mode_name.clone());
            added_mode_name = Some(mode_name);
        }
    }

    wait_for_xrandr_settle(display, xauth);
    if chosen_mode.is_none() {
        let query_after_mode = xrandr_output(&["--query"], display, xauth)
            .map_err(|e| format!("xrandr --query after mode injection failed: {e}"))?;
        let outputs_after_mode = parse_outputs(&query_after_mode);
        if let Some(refreshed) = outputs_after_mode.iter().find(|o| o.name == output.name) {
            chosen_mode = refreshed
                .modes
                .iter()
                .find(|m| m.trim() == desired_mode || m.trim().starts_with(&desired_mode))
                .map(|m| m.trim().to_string());
        }
    }

    let mode = chosen_mode.ok_or_else(|| {
        format!(
            "failed to find usable mode for output {} (wanted {desired_mode})",
            output.name
        )
    })?;
    Ok((mode, added_mode_name))
}

fn enable_output_mode(
    output_name: &str,
    mode: &str,
    primary: Option<&str>,
    mirror_to_primary: bool,
    display: &str,
    xauth: Option<&Path>,
) -> Result<(), String> {
    let mut args = vec![
        "--output".to_string(),
        output_name.to_string(),
        "--mode".to_string(),
        mode.to_string(),
    ];
    if mirror_to_primary {
        if let Some(primary_output) = primary {
            args.push("--same-as".to_string());
            args.push(primary_output.to_string());
        } else {
            warn!(
                output = %output_name,
                "x11 real-output create: mirror requested but no primary output detected; falling back to extended placement"
            );
        }
    }
    if (!mirror_to_primary || primary.is_none()) && primary.is_some() {
        args.push("--right-of".to_string());
        args.push(primary.unwrap_or_default().to_string());
    }
    let arg_refs = args.iter().map(|s| s.as_str()).collect::<Vec<_>>();
    xrandr(&arg_refs, display, xauth)
        .map_err(|e| format!("failed to enable output {output_name}: {e}"))?;
    wait_for_xrandr_settle(display, xauth);
    Ok(())
}

fn inspect_active_output(
    output_name: &str,
    display: &str,
    xauth: Option<&Path>,
) -> Result<(i32, i32, u32, u32, Vec<String>), String> {
    let query_after = xrandr_output(&["--query"], display, xauth)
        .map_err(|e| format!("xrandr --query after output enable failed: {e}"))?;
    let outputs_after = parse_outputs(&query_after);
    let active = outputs_after
        .iter()
        .find(|o| o.name == output_name && o.geometry.is_some())
        .ok_or_else(|| "output did not expose geometry after enable".to_string())?;

    let (x, y, aw, ah) = active
        .geometry
        .ok_or_else(|| "active output has no geometry".to_string())?;
    let mirrored_with = outputs_after
        .iter()
        .filter(|o| o.name != output_name && o.connected)
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
    Ok((x, y, aw, ah, mirrored_with))
}

fn validate_mirroring_state(
    output_name: &str,
    mirror_to_primary: bool,
    mirrored_with: &[String],
    x: i32,
    y: i32,
    aw: u32,
    ah: u32,
) -> Result<(), String> {
    if !mirrored_with.is_empty() {
        if mirror_to_primary {
            info!(
                output = %output_name,
                mirrors = %mirrored_with.join(","),
                "x11 real-output create: experimental mirror active"
            );
        } else {
            warn!(
                output = %output_name,
                mirrors = %mirrored_with.join(","),
                "x11 real-output create: enabled output mirrors active output(s)"
            );
            return Err(format!(
                "output {} enabled but mirrors active output(s): {} at {}x{}+{}+{}",
                output_name,
                mirrored_with.join(","),
                aw,
                ah,
                x,
                y
            ));
        }
    } else if mirror_to_primary {
        warn!(
            output = %output_name,
            "x11 real-output create: mirror requested but output is not mirrored after activation"
        );
    }
    Ok(())
}

pub fn destroy(handle: &X11RealOutputHandle) -> Result<(), String> {
    let display = detect_x11_display().unwrap_or_default();
    if display.trim().is_empty() {
        return Ok(());
    }
    let xauth = resolve_xauthority_for_display(&display);

    let _ = xrandr(
        &["--output", &handle.output_name, "--off"],
        &display,
        xauth.as_deref(),
    );

    cleanup_added_mode(handle, &display, xauth.as_deref());
    cleanup_provider_sink(handle, &display, xauth.as_deref());
    restore_previous_fb(handle, &display, xauth.as_deref());
    info!(output = %handle.output_name, "x11 real-output destroy: cleanup complete");

    Ok(())
}

fn cleanup_added_mode(handle: &X11RealOutputHandle, display: &str, xauth: Option<&Path>) {
    let Some(mode_name) = handle.added_mode_name.as_deref() else {
        return;
    };
    let _ = xrandr(
        &["--delmode", &handle.output_name, mode_name],
        display,
        xauth,
    );
    let _ = xrandr(&["--rmmode", mode_name], display, xauth);
}

fn cleanup_provider_sink(handle: &X11RealOutputHandle, display: &str, xauth: Option<&Path>) {
    let Some(sink_id) = handle.provider_sink_id.as_deref() else {
        return;
    };
    let _ = xrandr(
        &["--setprovideroutputsource", sink_id, "0x0"],
        display,
        xauth,
    );
}

fn restore_previous_fb(handle: &X11RealOutputHandle, display: &str, xauth: Option<&Path>) {
    let Some((w, h)) = handle.previous_fb else {
        return;
    };
    let _ = xrandr(&["--fb", &format!("{w}x{h}")], display, xauth);
}

#[allow(clippy::cognitive_complexity)]
fn parse_outputs(raw: &str) -> Vec<OutputInfo> { // NOSONAR: S3776
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
        let non_nvidia = providers
            .iter()
            .filter(|p| has_cap(p.caps, CAP_SOURCE_OUTPUT))
            .filter(|p| !is_nvidia_provider_name(&p.name))
            .max_by_key(|p| score_source_provider(p));
        if non_nvidia.is_some() {
            return non_nvidia;
        }
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
        .filter(|p| is_igpu_like_sink_provider_name(&p.name))
        .collect::<Vec<_>>();
    if !strict.is_empty() {
        return strict.into_iter().max_by_key(|p| score_sink_provider(p));
    }

    providers
        .iter()
        .filter(|p| p.id != source.id)
        .filter(|p| is_igpu_like_sink_provider_name(&p.name))
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
    if is_modesetting_name(&provider.name) || is_intel_provider_name(&provider.name) {
        score += 20;
    }
    if is_amd_provider_name(&provider.name) {
        score += 15;
    }
    if is_nvidia_provider_name(&provider.name) {
        score -= 200;
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
    if is_intel_provider_name(&provider.name) {
        score += 35;
    }
    if is_amd_provider_name(&provider.name) {
        score += 35;
    }
    if is_nvidia_provider_name(&provider.name) {
        score -= 200;
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

fn is_nvidia_provider_name(name: &str) -> bool {
    name.to_ascii_lowercase().contains("nvidia")
}

fn is_amd_provider_name(name: &str) -> bool {
    let low = name.to_ascii_lowercase();
    low.contains("amd") || low.contains("radeon") || low.contains("amdgpu")
}

fn is_intel_provider_name(name: &str) -> bool {
    name.to_ascii_lowercase().contains("intel")
}

fn is_igpu_like_sink_provider_name(name: &str) -> bool {
    is_modesetting_name(name) || is_intel_provider_name(name) || is_amd_provider_name(name)
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
    fn choose_sink_prefers_non_nvidia_when_multiple_sink_candidates_exist() {
        let source = ProviderInfo {
            id: "0x6f".to_string(),
            name: "evdi".to_string(),
            caps: CAP_SOURCE_OUTPUT,
        };
        let providers = vec![
            source.clone(),
            ProviderInfo {
                id: "0x48".to_string(),
                name: "NVIDIA-0".to_string(),
                caps: CAP_SINK_OUTPUT | CAP_SOURCE_OUTPUT,
            },
            ProviderInfo {
                id: "0x49".to_string(),
                name: "modesetting".to_string(),
                caps: CAP_SINK_OUTPUT | CAP_SOURCE_OUTPUT,
            },
        ];
        let sink = choose_sink_provider(&providers, &source).expect("sink");
        assert_eq!(sink.name, "modesetting");
    }

    #[test]
    fn choose_sink_rejects_nvidia_only_sink_topology() {
        let source = ProviderInfo {
            id: "0x6f".to_string(),
            name: "evdi".to_string(),
            caps: CAP_SOURCE_OUTPUT,
        };
        let providers = vec![
            source.clone(),
            ProviderInfo {
                id: "0x48".to_string(),
                name: "NVIDIA-0".to_string(),
                caps: CAP_SINK_OUTPUT | CAP_SOURCE_OUTPUT,
            },
        ];
        assert!(choose_sink_provider(&providers, &source).is_none());
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
    fn choose_source_avoids_nvidia_when_aux_sink_only_provider_exists() {
        let providers = vec![
            ProviderInfo {
                id: "0x1b7".to_string(),
                name: "NVIDIA-0".to_string(),
                caps: CAP_SOURCE_OUTPUT,
            },
            ProviderInfo {
                id: "0x205".to_string(),
                name: "modesetting".to_string(),
                caps: CAP_SOURCE_OUTPUT | CAP_SINK_OUTPUT,
            },
            ProviderInfo {
                id: "0x236".to_string(),
                name: "modesetting".to_string(),
                caps: CAP_SINK_OUTPUT,
            },
        ];
        let source = choose_source_provider(&providers).expect("source");
        assert_eq!(source.id, "0x205");
    }

    #[test]
    fn reject_unsafe_source_provider_rejects_nvidia() {
        let source = ProviderInfo {
            id: "0x48".to_string(),
            name: "NVIDIA-0".to_string(),
            caps: CAP_SOURCE_OUTPUT,
        };
        assert!(reject_unsafe_source_provider(&source).is_some());
    }

    #[test]
    fn reject_unsafe_source_provider_accepts_evdi() {
        let source = ProviderInfo {
            id: "0x6f".to_string(),
            name: "evdi".to_string(),
            caps: CAP_SOURCE_OUTPUT,
        };
        assert!(reject_unsafe_source_provider(&source).is_none());
    }

    #[test]
    fn should_block_provider_topology_when_nvidia_present_and_combo_enabled() {
        let providers = vec![
            ProviderInfo {
                id: "0x6f".to_string(),
                name: "evdi".to_string(),
                caps: CAP_SOURCE_OUTPUT,
            },
            ProviderInfo {
                id: "0x48".to_string(),
                name: "NVIDIA-0".to_string(),
                caps: CAP_SOURCE_OUTPUT | CAP_SINK_OUTPUT,
            },
        ];
        assert!(should_block_provider_topology(&providers, true));
    }

    #[test]
    fn should_not_block_provider_topology_without_nvidia_provider() {
        let providers = vec![
            ProviderInfo {
                id: "0x6f".to_string(),
                name: "evdi".to_string(),
                caps: CAP_SOURCE_OUTPUT,
            },
            ProviderInfo {
                id: "0x49".to_string(),
                name: "modesetting".to_string(),
                caps: CAP_SOURCE_OUTPUT | CAP_SINK_OUTPUT,
            },
        ];
        assert!(!should_block_provider_topology(&providers, true));
    }

    #[test]
    fn parse_connected_geometry_and_fb() {
        let line =
            "eDP-1 connected primary 1920x1080+0+0 (normal left inverted right x axis y axis)";
        assert_eq!(parse_connected_geometry(line), Some((0, 0, 1920, 1080)));

        let query = "Screen 0: minimum 8 x 8, current 3280 x 1080, maximum 32767 x 32767";
        assert_eq!(parse_current_fb(query), Some((3280, 1080)));
    }
}

fn resolve_xauthority_for_display(display: &str) -> Option<PathBuf> {
    let uid = std::env::var("UID")
        .ok()
        .filter(|v| !v.trim().is_empty())
        .or_else(|| std::env::var("EUID").ok())
        .unwrap_or_else(|| "1000".to_string());
    let uid_num = uid.parse::<u32>().ok();
    let mut candidates: Vec<PathBuf> = Vec::new();

    collect_env_xauth_candidates(&mut candidates);
    candidates.extend(collect_tmp_xauth_candidates(uid_num));
    candidates.extend(collect_run_dir_xauth_candidates(&uid, uid_num));
    collect_home_xauth_candidate(&mut candidates);

    candidates.dedup();

    first_usable_xauth_candidate(display, &candidates)
}

fn collect_env_xauth_candidates(candidates: &mut Vec<PathBuf>) {
    // Prefer current process env first (set by run_wbeamd.sh after probe),
    // then evaluate discovered candidates.
    if let Ok(path) = std::env::var("XAUTHORITY") {
        let p = PathBuf::from(path);
        if p.exists() {
            candidates.push(p);
        }
    }
}

fn collect_home_xauth_candidate(candidates: &mut Vec<PathBuf>) {
    if let Some(home) = std::env::var_os("HOME") {
        let p = Path::new(&home).join(".Xauthority");
        if p.exists() {
            candidates.push(p);
        }
    }
}

fn collect_tmp_xauth_candidates(uid_num: Option<u32>) -> Vec<PathBuf> {
    let Ok(entries) = fs::read_dir("/tmp") else {
        return Vec::new();
    };
    let mut tmp_candidates: Vec<PathBuf> = entries
        .flatten()
        .map(|e| e.path())
        .filter(|p| is_xauth_file_for_uid(p, uid_num))
        .collect();
    sort_candidates_by_mtime_desc(&mut tmp_candidates);
    tmp_candidates
}

fn collect_run_dir_xauth_candidates(uid: &str, uid_num: Option<u32>) -> Vec<PathBuf> {
    let run_dir = PathBuf::from(format!("/run/user/{uid}"));
    if !run_dir.exists() {
        return Vec::new();
    }
    let Ok(entries) = fs::read_dir(&run_dir) else {
        return Vec::new();
    };
    let mut run_candidates: Vec<PathBuf> = entries
        .flatten()
        .map(|entry| entry.path())
        .filter(|path| is_xauth_file_for_uid(path, uid_num))
        .collect();
    sort_candidates_by_mtime_desc(&mut run_candidates);
    run_candidates
}

fn is_xauth_file_for_uid(path: &Path, uid_num: Option<u32>) -> bool {
    if !path.is_file() {
        return false;
    }
    let is_xauth_name = path
        .file_name()
        .and_then(|n| n.to_str())
        .map(|n| n.starts_with("xauth_"))
        .unwrap_or(false);
    if !is_xauth_name {
        return false;
    }
    if let Some(expect_uid) = uid_num {
        if let Ok(meta) = fs::metadata(path) {
            use std::os::unix::fs::MetadataExt;
            return meta.uid() == expect_uid;
        }
        return false;
    }
    true
}

fn sort_candidates_by_mtime_desc(candidates: &mut [PathBuf]) {
    candidates.sort_by_key(|p| {
        std::fs::metadata(p)
            .and_then(|m| m.modified())
            .ok()
            .unwrap_or(SystemTime::UNIX_EPOCH)
    });
    candidates.reverse();
}

fn first_usable_xauth_candidate(display: &str, candidates: &[PathBuf]) -> Option<PathBuf> {
    candidates.iter().find_map(|candidate| {
        if xrandr_output(&["--listproviders"], display, Some(candidate.as_path())).is_ok() {
            Some(candidate.clone())
        } else {
            None
        }
    })
}
