use super::super::super::super::{host_probe::HostProbe, x11_backend};
use super::super::super::{Activation, ActivationError, DisplayMode, RuntimeHandle, VirtualMonitorProbe};
use tracing::info;

pub fn probe(host_probe: &HostProbe) -> VirtualMonitorProbe {
    let mut backends = x11_backend::backends_for_virtual_monitor();
    let real = backends.remove(0);
    let real_probe = real.probe(host_probe);
    if real_probe.supported {
        return VirtualMonitorProbe {
            supported: true,
            resolver: real.key().to_string(),
            missing_deps: Vec::new(),
            hint: "X11 real-output backend is available. Virtual monitor should create a true additional output.".to_string(),
        };
    }

    let mut missing = real_probe.missing_deps;
    let mut fallback_resolver: Option<String> = None;
    let mut fallback_hint: Option<String> = None;

    for backend in &backends {
        let p = backend.probe(host_probe);
        for dep in p.missing_deps {
            if !missing.iter().any(|m| m == &dep) {
                missing.push(dep);
            }
        }
        if fallback_resolver.is_none() && p.supported {
            fallback_resolver = Some(backend.key().to_string());
            fallback_hint = Some(format!(
                "Primary X11 real-output backend unavailable: {}. Using monitor-object fallback (xrandr --setmonitor). This is not a true hardware output but works in many local X11 sessions.",
                real_probe.reason
            ));
        }
    }

    VirtualMonitorProbe {
        supported: fallback_resolver.is_some(),
        resolver: fallback_resolver.unwrap_or_else(|| real.key().to_string()),
        missing_deps: missing,
        hint: fallback_hint.unwrap_or_else(|| {
            format!(
                "Primary X11 real-output backend unavailable: {}. No fallback backend passed probe.",
                real_probe.reason
            )
        }),
    }
}

pub fn activate(
    host_probe: &HostProbe,
    mode: DisplayMode,
    serial_hint: &str,
    size: &str,
) -> Result<Activation, ActivationError> {
    let mirror_to_primary = matches!(mode, DisplayMode::VirtualMirror);
    let mut errors = Vec::new();
    for backend in x11_backend::backends_for_virtual_monitor() {
        let probe = backend.probe(host_probe);
        if !probe.supported {
            errors.push(format!("{}: {}", backend.key(), probe.reason));
            continue;
        }

        match backend.create(serial_hint, size, mirror_to_primary) {
            Ok(x11_backend::BackendHandle::RealOutput(handle)) => {
                info!(
                    serial = serial_hint,
                    backend = backend.key(),
                    mirror_to_primary = mirror_to_primary,
                    output = %handle.output_name,
                    x = handle.x,
                    y = handle.y,
                    width = handle.width,
                    height = handle.height,
                    "x11 virtual monitor created"
                );
                return Ok(Activation {
                    display_override: host_probe.display.clone(),
                    capture_region: Some((handle.x, handle.y, handle.width, handle.height)),
                    runtime_handle: Some(RuntimeHandle::X11RealOutput(handle)),
                    ..Activation::default()
                });
            }
            Ok(x11_backend::BackendHandle::MonitorObject(handle)) => {
                info!(
                    serial = serial_hint,
                    backend = backend.key(),
                    mirror_to_primary = mirror_to_primary,
                    monitor = %handle.name,
                    x = handle.x,
                    y = handle.y,
                    width = handle.width,
                    height = handle.height,
                    "x11 monitor-object fallback created"
                );
                return Ok(Activation {
                    display_override: host_probe.display.clone(),
                    capture_region: Some((handle.x, handle.y, handle.width, handle.height)),
                    runtime_handle: Some(RuntimeHandle::X11MonitorObject(handle)),
                    ..Activation::default()
                });
            }
            Err(e) => {
                errors.push(format!("{}: {e}", backend.key()));
            }
        }
    }

    Err(ActivationError::Failed(format!(
        "no X11 virtual backend could be activated: {}",
        errors.join(" | ")
    )))
}
