mod wayland;
mod x11;

use super::super::host_probe::{HostProbe, SessionKind};
use super::{Activation, ActivationError, DisplayMode, VirtualMonitorProbe};

pub fn probe_virtual_monitor(host_probe: &HostProbe) -> VirtualMonitorProbe {
    match host_probe.session {
        SessionKind::X11 => x11::probe_virtual_monitor(host_probe),
        SessionKind::Wayland => wayland::probe_virtual_monitor(),
        _ => VirtualMonitorProbe {
            supported: false,
            resolver: "linux_unknown_session_backend".to_string(),
            missing_deps: Vec::new(),
            hint: format!(
                "Virtual monitor is unsupported in Linux session={}",
                host_probe.session_name()
            ),
        },
    }
}

pub fn activate(
    host_probe: &HostProbe,
    mode: DisplayMode,
    serial_hint: &str,
    size: &str,
) -> Result<Activation, ActivationError> {
    match host_probe.session {
        SessionKind::X11 => x11::activate(host_probe, mode, serial_hint, size),
        SessionKind::Wayland => wayland::activate(host_probe, mode, serial_hint, size),
        _ => {
            if mode.is_virtual() {
                Err(ActivationError::Unsupported(format!(
                    "virtual monitor mode unsupported in Linux session={}",
                    host_probe.session_name()
                )))
            } else {
                Ok(Activation::default())
            }
        }
    }
}
