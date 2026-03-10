use super::host_probe::HostProbe;
use super::{x11_monitor_object, x11_real_output};
use std::fs;
use std::path::PathBuf;

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
        let enabled = read_policy_bool("ENABLE_SETMONITOR_FALLBACK");
        if enabled {
            BackendProbe {
                supported: true,
                reason: format!(
                    "xrandr --setmonitor fallback enabled by policy file ({})",
                    policy_location_hint()
                ),
                missing_deps: Vec::new(),
            }
        } else {
            BackendProbe {
                supported: false,
                reason: format!(
                    "xrandr --setmonitor fallback disabled by policy file ({})",
                    policy_location_hint()
                ),
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

fn policy_file_path() -> Option<PathBuf> {
    if let Ok(xdg) = std::env::var("XDG_CONFIG_HOME") {
        let trimmed = xdg.trim();
        if !trimmed.is_empty() {
            return Some(PathBuf::from(trimmed).join("wbeam/x11-virtual-policy.conf"));
        }
    }
    if let Ok(home) = std::env::var("HOME") {
        let trimmed = home.trim();
        if !trimmed.is_empty() {
            return Some(PathBuf::from(trimmed).join(".config/wbeam/x11-virtual-policy.conf"));
        }
    }
    if let Ok(user) = std::env::var("USER") {
        let trimmed = user.trim();
        if !trimmed.is_empty() {
            return Some(
                PathBuf::from(format!("/home/{trimmed}")).join(".config/wbeam/x11-virtual-policy.conf"),
            );
        }
    }
    None
}

fn read_policy_bool(key: &str) -> bool {
    let Some(path) = policy_file_path() else {
        return false;
    };
    let Ok(raw) = fs::read_to_string(path) else {
        return false;
    };
    for line in raw.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let Some((k, v)) = line.split_once('=') else {
            continue;
        };
        if k.trim() != key {
            continue;
        }
        let low = v.trim().to_ascii_lowercase();
        return low == "1" || low == "true" || low == "on" || low == "yes";
    }
    false
}

fn policy_location_hint() -> String {
    policy_file_path()
        .map(|p| p.display().to_string())
        .unwrap_or_else(|| "<unknown-policy-path>".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::infra::host_probe::{CaptureMode, DesktopFlavor, HostOs, HostProbe, SessionKind};
    use std::fs;
    use std::path::PathBuf;
    use std::sync::{Mutex, OnceLock};
    use std::time::{SystemTime, UNIX_EPOCH};

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

    fn set_test_policy(content: Option<&str>) {
        let stamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("duration")
            .as_nanos();
        let base = std::env::temp_dir().join(format!("wbeam-x11-policy-test-{}-{}", std::process::id(), stamp));
        let policy_dir = base.join("wbeam");
        fs::create_dir_all(&policy_dir).expect("create policy dir");
        let policy_file = policy_dir.join("x11-virtual-policy.conf");
        if let Some(raw) = content {
            fs::write(&policy_file, raw).expect("write policy file");
        }
        std::env::set_var("XDG_CONFIG_HOME", PathBuf::from(&base));
    }

    #[test]
    fn monitor_backend_probe_enabled_by_default_for_local_session() {
        let _guard = env_lock().lock().expect("env lock");
        set_test_policy(None);
        let backend = MonitorObjectBackend;
        let probe = backend.probe(&local_x11_probe());
        assert!(!probe.supported);
    }

    #[test]
    fn monitor_backend_probe_can_be_disabled_with_policy() {
        let _guard = env_lock().lock().expect("env lock");
        set_test_policy(Some("ENABLE_SETMONITOR_FALLBACK=0\n"));
        let backend = MonitorObjectBackend;
        let probe = backend.probe(&local_x11_probe());
        assert!(!probe.supported);
    }

    #[test]
    fn monitor_backend_probe_can_be_enabled_with_policy() {
        let _guard = env_lock().lock().expect("env lock");
        set_test_policy(Some("ENABLE_SETMONITOR_FALLBACK=1\n"));
        let backend = MonitorObjectBackend;
        let probe = backend.probe(&local_x11_probe());
        assert!(probe.supported);
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
