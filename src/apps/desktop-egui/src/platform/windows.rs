use crate::platform::shared_checks::{
    AdbAvailabilityStartupCheck, AdbDevicesBackgroundCheck, AdbDevicesStartupCheck,
    HostnameStartupCheck, RuntimeStartupCheck,
};
use crate::platform::traits::{BackgroundCheck, PlatformModule, StartupCheck};

pub(crate) struct WindowsPlatformModule;

impl PlatformModule for WindowsPlatformModule {
    fn id(&self) -> &'static str {
        "windows"
    }

    fn startup_checks(&self) -> Vec<Box<dyn StartupCheck>> {
        vec![
            Box::new(RuntimeStartupCheck),
            Box::new(HostnameStartupCheck),
            Box::new(AdbAvailabilityStartupCheck),
            Box::new(AdbDevicesStartupCheck),
        ]
    }

    fn background_checks(&self) -> Vec<Box<dyn BackgroundCheck>> {
        vec![Box::new(AdbDevicesBackgroundCheck::default())]
    }
}
