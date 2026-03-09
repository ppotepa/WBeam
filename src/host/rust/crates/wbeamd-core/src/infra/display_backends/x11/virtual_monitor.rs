use super::super::{Activation, ActivationError, RuntimeHandle, VirtualMonitorProbe};
use super::super::super::{host_probe::HostProbe, virtual_display, x11_real_output};

pub fn probe(host_probe: &HostProbe) -> VirtualMonitorProbe {
    let real_probe = x11_real_output::probe(host_probe.is_remote);
    if real_probe.supported {
        return VirtualMonitorProbe {
            supported: true,
            resolver: "linux_x11_evdi_real_output".to_string(),
            missing_deps: Vec::new(),
            hint: "X11 real-output backend is available. Virtual monitor should create a true additional output.".to_string(),
        };
    }

    let mut missing = real_probe.missing_deps;
    let hint = if virtual_display::has_xvfb() {
        format!(
            "X11 real-output backend unavailable: {}. Isolated Xvfb exists, but it is not a KDE additional monitor.",
            real_probe.reason
        )
    } else {
        if !missing.iter().any(|m| m == "Xvfb") {
            missing.push("Xvfb".to_string());
        }
        format!(
            "X11 real-output backend unavailable: {}. {}",
            real_probe.reason,
            virtual_display::install_hint()
        )
    };

    VirtualMonitorProbe {
        supported: false,
        resolver: "linux_x11_evdi_real_output".to_string(),
        missing_deps: missing,
        hint,
    }
}

pub fn activate(
    host_probe: &HostProbe,
    serial_hint: &str,
    size: &str,
) -> Result<Activation, ActivationError> {
    let handle = x11_real_output::create(serial_hint, size).map_err(ActivationError::Failed)?;
    Ok(Activation {
        display_override: host_probe.display.clone(),
        capture_region: Some((handle.x, handle.y, handle.width, handle.height)),
        runtime_handle: Some(RuntimeHandle::X11RealOutput(handle)),
        ..Activation::default()
    })
}
