use std::time::Duration;

use crate::platform::traits::{BackgroundCheck, BootstrapState, MonitorEvent, StartupCheck};
use crate::services::{
    adb_available, detect_os_version, detect_runtime, hostname, read_adb_devices,
};

#[derive(Default)]
pub(crate) struct RuntimeStartupCheck;

impl StartupCheck for RuntimeStartupCheck {
    fn id(&self) -> &'static str {
        "runtime"
    }

    fn run(&self, state: &mut BootstrapState) -> anyhow::Result<Option<MonitorEvent>> {
        let (os_name, session_type) = detect_runtime();
        state.os_name = os_name;
        state.os_version = detect_os_version();
        state.session_type = session_type;
        Ok(None)
    }
}

#[derive(Default)]
pub(crate) struct HostnameStartupCheck;

impl StartupCheck for HostnameStartupCheck {
    fn id(&self) -> &'static str {
        "hostname"
    }

    fn run(&self, state: &mut BootstrapState) -> anyhow::Result<Option<MonitorEvent>> {
        state.host_name = hostname();
        Ok(None)
    }
}

#[derive(Default)]
pub(crate) struct AdbAvailabilityStartupCheck;

impl StartupCheck for AdbAvailabilityStartupCheck {
    fn id(&self) -> &'static str {
        "adb_availability"
    }

    fn run(&self, state: &mut BootstrapState) -> anyhow::Result<Option<MonitorEvent>> {
        state.adb_available = adb_available();
        if state.adb_available {
            Ok(Some(MonitorEvent::Info {
                source: self.id(),
                message: "adb detected on PATH".to_string(),
            }))
        } else {
            Ok(Some(MonitorEvent::Warn {
                source: self.id(),
                message: "adb not available on PATH".to_string(),
            }))
        }
    }
}

#[derive(Default)]
pub(crate) struct AdbDevicesStartupCheck;

impl StartupCheck for AdbDevicesStartupCheck {
    fn id(&self) -> &'static str {
        "adb_devices"
    }

    fn run(&self, state: &mut BootstrapState) -> anyhow::Result<Option<MonitorEvent>> {
        if !state.adb_available {
            state.devices.clear();
            return Ok(None);
        }
        state.devices = read_adb_devices()?;
        Ok(Some(MonitorEvent::Info {
            source: self.id(),
            message: format!("adb devices discovered: {}", state.devices.len()),
        }))
    }
}

#[derive(Default)]
pub(crate) struct AdbDevicesBackgroundCheck {
    last_serials: Vec<String>,
    last_available: Option<bool>,
}

impl AdbDevicesBackgroundCheck {
    fn serials(devices: &[crate::models::DeviceInfo]) -> Vec<String> {
        let mut serials: Vec<String> = devices.iter().map(|d| d.serial.clone()).collect();
        serials.sort();
        serials
    }
}

impl BackgroundCheck for AdbDevicesBackgroundCheck {
    fn id(&self) -> &'static str {
        "adb_devices_watcher"
    }

    fn interval(&self) -> Duration {
        Duration::from_secs(3)
    }

    fn tick(&mut self) -> anyhow::Result<Option<MonitorEvent>> {
        let available = adb_available();
        if self.last_available != Some(available) {
            self.last_available = Some(available);
            if !available {
                return Ok(Some(MonitorEvent::Warn {
                    source: self.id(),
                    message: "adb disappeared from PATH".to_string(),
                }));
            }
            return Ok(Some(MonitorEvent::Info {
                source: self.id(),
                message: "adb available".to_string(),
            }));
        }

        if !available {
            return Ok(None);
        }

        let devices = read_adb_devices()?;
        let serials = Self::serials(&devices);
        if serials != self.last_serials {
            self.last_serials = serials;
            return Ok(Some(MonitorEvent::DevicesChanged {
                source: self.id(),
                devices,
            }));
        }
        Ok(None)
    }
}
