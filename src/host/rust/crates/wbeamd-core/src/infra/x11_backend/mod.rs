use super::host_probe::HostProbe;
use super::{x11_monitor_object, x11_real_output};

#[derive(Debug, Clone)]
pub struct BackendProbe {
    pub supported: bool,
    pub reason: String,
    pub missing_deps: Vec<String>,
}

#[derive(Debug, Clone)]
pub enum BackendHandle {
    RealOutput(x11_real_output::X11RealOutputHandle),
    MonitorObject(x11_monitor_object::X11MonitorObjectHandle),
}

pub trait X11VirtualOutputBackend {
    fn key(&self) -> &'static str;
    fn primary(&self) -> bool;
    fn probe(&self, host_probe: &HostProbe) -> BackendProbe;
    fn create(&self, serial: &str, size: &str) -> Result<BackendHandle, String>;
    fn destroy(&self, handle: &BackendHandle) -> Result<(), String>;
}

#[derive(Debug, Default, Clone, Copy)]
pub struct RealOutputBackend;

#[derive(Debug, Default, Clone, Copy)]
pub struct MonitorObjectBackend;

impl X11VirtualOutputBackend for RealOutputBackend {
    fn key(&self) -> &'static str {
        "linux_x11_real_output"
    }

    fn primary(&self) -> bool {
        true
    }

    fn probe(&self, host_probe: &HostProbe) -> BackendProbe {
        let p = x11_real_output::probe(host_probe.is_remote);
        BackendProbe {
            supported: p.supported,
            reason: p.reason,
            missing_deps: p.missing_deps,
        }
    }

    fn create(&self, serial: &str, size: &str) -> Result<BackendHandle, String> {
        x11_real_output::create(serial, size).map(BackendHandle::RealOutput)
    }

    fn destroy(&self, handle: &BackendHandle) -> Result<(), String> {
        if let BackendHandle::RealOutput(h) = handle {
            return x11_real_output::destroy(h);
        }
        Ok(())
    }
}

impl X11VirtualOutputBackend for MonitorObjectBackend {
    fn key(&self) -> &'static str {
        "linux_x11_monitor_object_experimental"
    }

    fn primary(&self) -> bool {
        false
    }

    fn probe(&self, host_probe: &HostProbe) -> BackendProbe {
        if host_probe.is_remote {
            return BackendProbe {
                supported: false,
                reason: "monitor-object fallback is disabled for remote sessions".to_string(),
                missing_deps: Vec::new(),
            };
        }
        let enabled = std::env::var("WBEAM_X11_ENABLE_SETMONITOR_FALLBACK")
            .ok()
            .map(|v| {
                let low = v.trim().to_ascii_lowercase();
                low == "1" || low == "true" || low == "on" || low == "yes"
            })
            .unwrap_or(false);
        if enabled {
            BackendProbe {
                supported: true,
                reason: "xrandr --setmonitor fallback enabled".to_string(),
                missing_deps: Vec::new(),
            }
        } else {
            BackendProbe {
                supported: false,
                reason: "xrandr --setmonitor fallback disabled (set WBEAM_X11_ENABLE_SETMONITOR_FALLBACK=1 to force experimental mode)".to_string(),
                missing_deps: Vec::new(),
            }
        }
    }

    fn create(&self, serial: &str, size: &str) -> Result<BackendHandle, String> {
        x11_monitor_object::create(serial, size).map(BackendHandle::MonitorObject)
    }

    fn destroy(&self, handle: &BackendHandle) -> Result<(), String> {
        if let BackendHandle::MonitorObject(h) = handle {
            return x11_monitor_object::destroy(h);
        }
        Ok(())
    }
}

pub fn backends_for_virtual_monitor() -> Vec<Box<dyn X11VirtualOutputBackend>> {
    vec![
        Box::new(RealOutputBackend),
        Box::new(MonitorObjectBackend),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::infra::host_probe::{CaptureMode, DesktopFlavor, HostOs, HostProbe, SessionKind};
    use std::sync::{Mutex, OnceLock};

    fn env_lock() -> &'static Mutex<()> {
        static ENV_LOCK: OnceLock<Mutex<()>> = OnceLock::new();
        ENV_LOCK.get_or_init(|| Mutex::new(()))
    }

    fn local_x11_probe() -> HostProbe {
        HostProbe {
            os: HostOs::Linux,
            session: SessionKind::X11,
            desktop: DesktopFlavor::Kde,
            capture_mode: CaptureMode::X11Gst,
            is_remote: false,
            display: Some(":0".to_string()),
            wayland_display: None,
        }
    }

    #[test]
    fn monitor_backend_probe_enabled_by_default_for_local_session() {
        let _guard = env_lock().lock().expect("env lock");
        std::env::remove_var("WBEAM_X11_ENABLE_SETMONITOR_FALLBACK");
        let backend = MonitorObjectBackend;
        let probe = backend.probe(&local_x11_probe());
        assert!(!probe.supported);
    }

    #[test]
    fn monitor_backend_probe_can_be_disabled_with_env() {
        let _guard = env_lock().lock().expect("env lock");
        std::env::set_var("WBEAM_X11_ENABLE_SETMONITOR_FALLBACK", "0");
        let backend = MonitorObjectBackend;
        let probe = backend.probe(&local_x11_probe());
        assert!(!probe.supported);
        std::env::remove_var("WBEAM_X11_ENABLE_SETMONITOR_FALLBACK");
    }

    #[test]
    fn monitor_backend_probe_can_be_enabled_with_env() {
        let _guard = env_lock().lock().expect("env lock");
        std::env::set_var("WBEAM_X11_ENABLE_SETMONITOR_FALLBACK", "1");
        let backend = MonitorObjectBackend;
        let probe = backend.probe(&local_x11_probe());
        assert!(probe.supported);
        std::env::remove_var("WBEAM_X11_ENABLE_SETMONITOR_FALLBACK");
    }

    #[test]
    fn monitor_backend_probe_rejects_remote_sessions() {
        let mut host = local_x11_probe();
        host.is_remote = true;
        let backend = MonitorObjectBackend;
        let probe = backend.probe(&host);
        assert!(!probe.supported);
    }
}
