mod duplicate;
mod virtual_monitor;

use super::{Activation, ActivationError, DisplayMode, VirtualMonitorProbe};
use super::super::host_probe::HostProbe;

pub fn probe_virtual_monitor() -> VirtualMonitorProbe {
    virtual_monitor::probe()
}

pub fn activate(
    _host_probe: &HostProbe,
    mode: DisplayMode,
    _serial_hint: &str,
    _size: &str,
) -> Result<Activation, ActivationError> {
    match mode {
        DisplayMode::Duplicate => duplicate::activate(),
        DisplayMode::VirtualMonitor | DisplayMode::VirtualIsolated => virtual_monitor::activate(),
    }
}
