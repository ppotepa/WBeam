use super::{Activation, ActivationError, DisplayMode, VirtualMonitorProbe};
use super::super::host_probe::HostProbe;

pub fn probe_virtual_monitor() -> VirtualMonitorProbe {
    VirtualMonitorProbe {
        supported: false,
        resolver: "windows_backend".to_string(),
        missing_deps: vec!["windows-virtual-monitor-driver".to_string()],
        hint: "Windows virtual monitor backend is planned but not implemented yet. Duplicate mode is available.".to_string(),
    }
}

pub fn activate(
    _host_probe: &HostProbe,
    mode: DisplayMode,
    _serial_hint: &str,
    _size: &str,
) -> Result<Activation, ActivationError> {
    match mode {
        DisplayMode::Duplicate => Ok(Activation::default()),
        DisplayMode::VirtualMonitor | DisplayMode::VirtualIsolated => {
            Err(ActivationError::Unsupported(
                "Windows virtual monitor backend is not implemented yet".to_string(),
            ))
        }
    }
}
