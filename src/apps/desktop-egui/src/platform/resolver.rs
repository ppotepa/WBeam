use crate::platform::linux::LinuxPlatformModule;
use crate::platform::traits::PlatformModule;
use crate::platform::windows::WindowsPlatformModule;

pub(crate) fn resolve_platform_module() -> Box<dyn PlatformModule> {
    match std::env::consts::OS {
        "windows" => Box::new(WindowsPlatformModule),
        _ => Box::new(LinuxPlatformModule),
    }
}
