use std::time::Duration;

use crate::models::DeviceInfo;

#[derive(Clone, Debug, Default)]
pub(crate) struct BootstrapState {
    pub(crate) os_name: String,
    pub(crate) os_version: String,
    pub(crate) session_type: String,
    pub(crate) host_name: String,
    pub(crate) adb_available: bool,
    pub(crate) devices: Vec<DeviceInfo>,
}

#[derive(Clone, Debug)]
pub(crate) enum MonitorEvent {
    Info {
        source: &'static str,
        message: String,
    },
    Warn {
        source: &'static str,
        message: String,
    },
    Error {
        source: &'static str,
        message: String,
    },
    DevicesChanged {
        source: &'static str,
        devices: Vec<DeviceInfo>,
    },
}

pub(crate) trait StartupCheck: Send + Sync {
    fn id(&self) -> &'static str;
    fn run(&self, state: &mut BootstrapState) -> anyhow::Result<Option<MonitorEvent>>;
}

pub(crate) trait BackgroundCheck: Send {
    fn id(&self) -> &'static str;
    fn interval(&self) -> Duration;
    fn tick(&mut self) -> anyhow::Result<Option<MonitorEvent>>;
}

pub(crate) trait PlatformModule: Send + Sync {
    fn id(&self) -> &'static str;
    fn startup_checks(&self) -> Vec<Box<dyn StartupCheck>>;
    fn background_checks(&self) -> Vec<Box<dyn BackgroundCheck>>;
}
