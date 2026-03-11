use super::super::super::VirtualMonitorProbe;

pub fn probe() -> VirtualMonitorProbe {
    VirtualMonitorProbe {
        supported: false,
        resolver: "linux_wayland_backend".to_string(),
        missing_deps: vec!["wayland-virtual-monitor-backend".to_string()],
        hint:
            "Wayland virtual monitor backend is not implemented yet. Duplicate mode is available."
                .to_string(),
    }
}
