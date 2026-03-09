mod duplicate;
mod virtual_isolated;
mod virtual_monitor;

use super::super::{Activation, ActivationError, DisplayMode, VirtualMonitorProbe};
use super::super::super::host_probe::HostProbe;

pub fn probe_virtual_monitor(host_probe: &HostProbe) -> VirtualMonitorProbe {
    virtual_monitor::probe(host_probe)
}

pub fn activate(
    host_probe: &HostProbe,
    mode: DisplayMode,
    serial_hint: &str,
    size: &str,
) -> Result<Activation, ActivationError> {
    match mode {
        DisplayMode::Duplicate => duplicate::activate(host_probe),
        DisplayMode::VirtualMonitor => virtual_monitor::activate(host_probe, serial_hint, size),
        DisplayMode::VirtualIsolated => virtual_isolated::activate(serial_hint, size),
    }
}
