mod duplicate;
mod virtual_monitor;

use super::super::super::host_probe::HostProbe;
use super::super::{Activation, ActivationError, DisplayMode, VirtualMonitorProbe};

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
        DisplayMode::VirtualMonitor | DisplayMode::VirtualMirror => {
            virtual_monitor::activate(host_probe, mode, serial_hint, size)
        }
    }
}
