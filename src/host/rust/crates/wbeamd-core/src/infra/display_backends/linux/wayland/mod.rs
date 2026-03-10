mod duplicate;
mod virtual_monitor;

use super::super::super::host_probe::HostProbe;
use super::super::{Activation, ActivationError, DisplayMode, VirtualMonitorProbe};

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
        DisplayMode::VirtualMonitor | DisplayMode::VirtualMirror => virtual_monitor::activate(),
    }
}
