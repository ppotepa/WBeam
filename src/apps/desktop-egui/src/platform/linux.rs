use crate::platform::shared_checks::{
    AdbAvailabilityStartupCheck, AdbDevicesBackgroundCheck, AdbDevicesStartupCheck,
    HostnameStartupCheck, PortalScreencastStartupCheck, RuntimeStartupCheck,
    SessionProbeStartupCheck,
};
use crate::platform::traits::{BackgroundCheck, PlatformModule, StartupCheck};

pub(crate) struct LinuxPlatformModule;

impl PlatformModule for LinuxPlatformModule {
    fn id(&self) -> &'static str {
        "linux"
    }

    fn startup_checks(&self) -> Vec<Box<dyn StartupCheck>> {
        vec![
            Box::new(RuntimeStartupCheck),
            Box::new(SessionProbeStartupCheck),
            Box::new(HostnameStartupCheck),
            Box::new(PortalScreencastStartupCheck),
            Box::new(AdbAvailabilityStartupCheck),
            Box::new(AdbDevicesStartupCheck),
        ]
    }

    fn background_checks(&self) -> Vec<Box<dyn BackgroundCheck>> {
        vec![Box::new(AdbDevicesBackgroundCheck::default())]
    }
}
