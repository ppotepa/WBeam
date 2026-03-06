use std::env;

#[derive(Clone, Debug, Default)]
pub(crate) struct HostContext {
    pub(crate) os_name: String,
    pub(crate) session_type: String,
    pub(crate) desktop_env: String,
}

impl HostContext {
    pub(crate) fn probe() -> Self {
        let os_name = env::consts::OS.to_string();
        if os_name != "linux" {
            return Self {
                os_name,
                session_type: "n/a".to_string(),
                desktop_env: "n/a".to_string(),
            };
        }

        let session_type = env::var("XDG_SESSION_TYPE")
            .ok()
            .map(|s| s.to_ascii_lowercase())
            .filter(|s| s == "wayland" || s == "x11")
            .unwrap_or_else(|| {
                if env::var_os("WAYLAND_DISPLAY").is_some() {
                    "wayland".to_string()
                } else if env::var_os("DISPLAY").is_some() {
                    "x11".to_string()
                } else {
                    "unknown".to_string()
                }
            });

        let desktop_raw = env::var("XDG_CURRENT_DESKTOP")
            .or_else(|_| env::var("DESKTOP_SESSION"))
            .unwrap_or_else(|_| "unknown".to_string());
        let desktop_upper = desktop_raw.to_ascii_uppercase();
        let desktop_env =
            if desktop_upper.contains("KDE") || env::var_os("KDE_FULL_SESSION").is_some() {
                "kde".to_string()
            } else if desktop_upper.contains("GNOME")
                || env::var_os("GNOME_DESKTOP_SESSION_ID").is_some()
            {
                "gnome".to_string()
            } else if desktop_upper.contains("XFCE") {
                "xfce".to_string()
            } else if desktop_upper.contains("SWAY") {
                "sway".to_string()
            } else {
                desktop_raw.to_ascii_lowercase()
            };

        Self {
            os_name,
            session_type,
            desktop_env,
        }
    }
}
