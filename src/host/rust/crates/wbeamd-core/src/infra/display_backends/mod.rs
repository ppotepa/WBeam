use super::host_probe::{HostOs, HostProbe};
use super::process as proc;
use super::virtual_display;
use super::x11_real_output;
use super::x11_virtual_monitor;

mod linux;
mod windows;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DisplayMode {
    Duplicate,
    VirtualMonitor,
    VirtualIsolated,
}

impl DisplayMode {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Duplicate => "duplicate",
            Self::VirtualMonitor => "virtual_monitor",
            Self::VirtualIsolated => "virtual_isolated",
        }
    }

    pub fn is_virtual(self) -> bool {
        !matches!(self, Self::Duplicate)
    }
}

#[derive(Debug, Clone)]
pub enum RuntimeHandle {
    X11RealOutput(x11_real_output::X11RealOutputHandle),
    X11VirtualIsolated(virtual_display::VirtualDisplayHandle),
    X11VirtualMonitor(x11_virtual_monitor::X11VirtualMonitorHandle),
}

#[derive(Debug, Clone, Default)]
pub struct Activation {
    pub display_override: Option<String>,
    pub capture_region: Option<(i32, i32, u32, u32)>,
    pub using_virtual_x11: bool,
    pub runtime_handle: Option<RuntimeHandle>,
}

#[derive(Debug, Clone)]
pub enum ActivationError {
    Unsupported(String),
    Failed(String),
}

#[derive(Debug, Clone)]
pub struct VirtualMonitorProbe {
    pub supported: bool,
    pub resolver: String,
    pub missing_deps: Vec<String>,
    pub hint: String,
}

pub fn normalize_requested_mode(mode: Option<&str>) -> DisplayMode {
    match mode.unwrap_or("duplicate").trim().to_lowercase().as_str() {
        "virtual" | "virtual_monitor" => DisplayMode::VirtualMonitor,
        "isolated" | "virtual_isolated" => DisplayMode::VirtualIsolated,
        _ => DisplayMode::Duplicate,
    }
}

pub fn virtual_monitor_probe(host_probe: &HostProbe) -> VirtualMonitorProbe {
    match host_probe.os {
        HostOs::Linux => linux::probe_virtual_monitor(host_probe),
        HostOs::Windows => windows::probe_virtual_monitor(),
        _ => VirtualMonitorProbe {
            supported: false,
            resolver: "unsupported_host_backend".to_string(),
            missing_deps: Vec::new(),
            hint: format!(
                "Virtual monitor is unsupported on os={} session={}",
                host_probe.os_name(),
                host_probe.session_name()
            ),
        },
    }
}

pub fn activate_start(
    host_probe: &HostProbe,
    mode: DisplayMode,
    serial_hint: &str,
    size: &str,
) -> Result<Activation, ActivationError> {
    match host_probe.os {
        HostOs::Linux => linux::activate(host_probe, mode, serial_hint, size),
        HostOs::Windows => windows::activate(host_probe, mode, serial_hint, size),
        _ => {
            if mode.is_virtual() {
                Err(ActivationError::Unsupported(format!(
                    "virtual monitor mode unsupported on os={} session={}",
                    host_probe.os_name(),
                    host_probe.session_name()
                )))
            } else {
                Ok(Activation::default())
            }
        }
    }
}

pub async fn stop_runtime(handle: RuntimeHandle) {
    match handle {
        RuntimeHandle::X11RealOutput(h) => {
            let _ = x11_real_output::destroy(&h);
        }
        RuntimeHandle::X11VirtualIsolated(h) => {
            proc::terminate_pid(h.pid).await;
        }
        RuntimeHandle::X11VirtualMonitor(h) => {
            let _ = x11_virtual_monitor::destroy(&h);
        }
    }
}
