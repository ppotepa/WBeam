use super::super::super::VirtualMonitorProbe;

pub fn probe() -> VirtualMonitorProbe {
    VirtualMonitorProbe {
        supported: true,
        resolver: "linux_wayland_portal_virtual".to_string(),
        missing_deps: Vec::new(),
        hint:
            "Wayland virtual monitor uses XDG ScreenCast portal virtual source requests. Runtime support depends on compositor/portal implementation."
                .to_string(),
    }
}
