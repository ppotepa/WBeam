//! Host environment probing used to select or validate streaming mode.
//!
//! The current streamer implementation supports Wayland ScreenCast portal.
//! This module centralizes host/session/desktop detection so startup checks
//! and future backend routing can use one abstraction.

use std::env;
use std::fs;
use std::path::Path;
use std::process::Command;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HostOs {
    Linux,
    Windows,
    MacOs,
    Unknown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SessionKind {
    Wayland,
    X11,
    Tty,
    Unknown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DesktopFlavor {
    Kde,
    Gnome,
    Sway,
    Hyprland,
    Xfce,
    Cinnamon,
    Mate,
    Lxde,
    Lxqt,
    Unknown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CaptureMode {
    WaylandPortal,
    X11Gst,
    /// Kernel-level EVDI virtual display (user-requested override, not auto-detected).
    Evdi,
    UnsupportedHost,
}

/// Raw host characteristics detected from environment and runtime.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HostFacts {
    pub os: HostOs,
    pub session: SessionKind,
    pub desktop: DesktopFlavor,
    pub is_remote: bool,
    pub display: Option<String>,
    pub wayland_display: Option<String>,
}

/// Policy interface mapping host facts to streaming backend.
pub trait HostBackendResolver: Send + Sync {
    fn resolve_capture_mode(&self, facts: &HostFacts) -> CaptureMode;
}

/// Release default capture policy:
/// - Wayland -> Wayland Portal
/// - X11 / tty / unknown -> no automatic capture backend
/// EVDI is independently advertised when a real kernel DRM card is present.
/// Legacy X11 types and implementation remain in source but are not defaults.
#[derive(Debug, Default, Clone, Copy)]
pub struct DefaultHostBackendResolver;

impl HostBackendResolver for DefaultHostBackendResolver {
    fn resolve_capture_mode(&self, facts: &HostFacts) -> CaptureMode {
        match (facts.os, facts.session) {
            (HostOs::Linux, SessionKind::Wayland) => CaptureMode::WaylandPortal,
            _ => CaptureMode::UnsupportedHost,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HostProbe {
    pub os: HostOs,
    pub session: SessionKind,
    pub desktop: DesktopFlavor,
    pub capture_mode: CaptureMode,
    pub is_remote: bool,
    pub display: Option<String>,
    pub wayland_display: Option<String>,
}

impl HostProbe {
    pub fn detect() -> Self {
        let facts = HostFacts::detect();
        Self::from_facts(&facts, &DefaultHostBackendResolver)
    }

    pub fn detect_with_resolver(resolver: &dyn HostBackendResolver) -> Self {
        let facts = HostFacts::detect();
        Self::from_facts(&facts, resolver)
    }

    pub fn from_facts(facts: &HostFacts, resolver: &dyn HostBackendResolver) -> Self {
        Self {
            os: facts.os,
            session: facts.session,
            desktop: facts.desktop,
            capture_mode: resolver.resolve_capture_mode(facts),
            is_remote: facts.is_remote,
            display: facts.display.clone(),
            wayland_display: facts.wayland_display.clone(),
        }
    }

    pub fn supports_streaming(&self) -> bool {
        !self.available_backends().is_empty()
    }

    pub fn supports_capture_backend(&self, backend: &str) -> bool {
        self.available_backends().contains(&backend)
    }

    pub fn unsupported_reason(&self) -> String {
        match self.capture_mode {
            CaptureMode::WaylandPortal => "streaming is supported".to_string(),
            CaptureMode::X11Gst => "Legacy X11 capture is unavailable in this release; use EVDI or Wayland Portal".to_string(),
            CaptureMode::Evdi => "streaming is supported".to_string(),
            CaptureMode::UnsupportedHost => format!(
                "unsupported host environment: os={} session={} desktop={}",
                self.os_name(),
                self.session_name(),
                self.desktop_name()
            ),
        }
    }

    pub fn os_name(&self) -> &'static str {
        match self.os {
            HostOs::Linux => "linux",
            HostOs::Windows => "windows",
            HostOs::MacOs => "macos",
            HostOs::Unknown => "unknown",
        }
    }

    pub fn session_name(&self) -> &'static str {
        match self.session {
            SessionKind::Wayland => "wayland",
            SessionKind::X11 => "x11",
            SessionKind::Tty => "tty",
            SessionKind::Unknown => "unknown",
        }
    }

    pub fn desktop_name(&self) -> &'static str {
        match self.desktop {
            DesktopFlavor::Kde => "kde",
            DesktopFlavor::Gnome => "gnome",
            DesktopFlavor::Sway => "sway",
            DesktopFlavor::Hyprland => "hyprland",
            DesktopFlavor::Xfce => "xfce",
            DesktopFlavor::Cinnamon => "cinnamon",
            DesktopFlavor::Mate => "mate",
            DesktopFlavor::Lxde => "lxde",
            DesktopFlavor::Lxqt => "lxqt",
            DesktopFlavor::Unknown => "unknown",
        }
    }

    pub fn capture_mode_name(&self) -> &'static str {
        match self.capture_mode {
            CaptureMode::WaylandPortal => "wayland_portal",
            CaptureMode::X11Gst => "x11_gst",
            CaptureMode::Evdi => "evdi",
            CaptureMode::UnsupportedHost => "unsupported_host",
        }
    }

    /// Returns only the release-supported capture backends. X11 remains a
    /// dormant legacy implementation and must not be advertised or selected.
    pub fn available_backends(&self) -> Vec<&'static str> {
        release_backends(self.capture_mode, Self::evdi_device_available())
    }

    /// Reports usable EVDI capability only after a DRM card node exists.
    pub fn evdi_device_available() -> bool {
        // EVDI reuses /dev/dri/cardN, not /dev/dri/evdiN. Merely exposing
        // /sys/devices/evdi/add does not imply a non-root app can create one.
        evdi_card_exists(Path::new("/sys/bus/platform/devices"), Path::new("/dev/dri"))
    }
}


// Pure release policy: keep backend advertisement independent of the
// process environment so it can be tested without a DRM device.
fn release_backends(default: CaptureMode, evdi_available: bool) -> Vec<&'static str> {
    let mut backends = Vec::new();
    if default == CaptureMode::WaylandPortal {
        backends.push("wayland_portal");
    }
    if evdi_available {
        backends.push("evdi");
    }
    backends
}

#[cfg(test)]
mod release_capture_policy_tests {
    use super::*;

    fn facts(session: SessionKind) -> HostFacts {
        HostFacts {
            os: HostOs::Linux,
            session,
            desktop: DesktopFlavor::Gnome,
            is_remote: false,
            display: None,
            wayland_display: None,
        }
    }

    #[test]
    fn wayland_default_is_portal() {
        let probe = HostProbe::from_facts(&facts(SessionKind::Wayland), &DefaultHostBackendResolver);
        assert_eq!(probe.capture_mode, CaptureMode::WaylandPortal);
        assert_eq!(release_backends(probe.capture_mode, false), vec!["wayland_portal"]);
    }

    #[test]
    fn x11_default_is_not_legacy_capture() {
        let probe = HostProbe::from_facts(&facts(SessionKind::X11), &DefaultHostBackendResolver);
        assert_eq!(probe.capture_mode, CaptureMode::UnsupportedHost);
        assert!(release_backends(probe.capture_mode, false).is_empty());
        assert_eq!(release_backends(probe.capture_mode, true), vec!["evdi"]);
    }

    #[test]
    fn legacy_resolver_value_is_not_release_advertised() {
        assert!(release_backends(CaptureMode::X11Gst, false).is_empty());
        assert_eq!(release_backends(CaptureMode::X11Gst, true), vec!["evdi"]);
    }
}

fn evdi_card_exists(platform_devices: &Path, dev_dri: &Path) -> bool {
    let Ok(platform) = fs::read_dir(platform_devices) else {
        return false;
    };
    for device in platform.flatten() {
        if !device.file_name().to_string_lossy().starts_with("evdi") {
            continue;
        }
        let Ok(cards) = fs::read_dir(device.path().join("drm")) else {
            continue;
        };
        for card in cards.flatten() {
            let name = card.file_name();
            let name = name.to_string_lossy();
            if name.starts_with("card")
                && name.len() > 4
                && name[4..].chars().all(|ch| ch.is_ascii_digit())
                && dev_dri.join(name.as_ref()).exists()
            {
                return true;
            }
        }
    }
    false
}

#[cfg(test)]
mod evdi_detection_tests {
    use super::evdi_card_exists;
    use std::{fs, path::Path};

    #[test]
    fn evdi_card_names_are_regular_drm_card_nodes() {
        let root = std::env::temp_dir()
            .join(format!("wbeam-evdi-check-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        let sysfs = root.join("platform");
        let dri = root.join("dev/dri");
        fs::create_dir_all(sysfs.join("evdi.0/drm")).unwrap();
        fs::create_dir_all(&dri).unwrap();
        fs::write(sysfs.join("evdi.0/drm/card7"), "").unwrap();
        fs::write(dri.join("card7"), "").unwrap();
        assert!(evdi_card_exists(&sysfs, &dri));
        fs::remove_file(dri.join("card7")).unwrap();
        assert!(!evdi_card_exists(&sysfs, &dri));
        fs::remove_dir_all(&root).unwrap();
    }

    #[test]
    fn a_generic_gpu_card_is_not_evdi() {
        let root = std::env::temp_dir()
            .join(format!("wbeam-non-evdi-check-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        let sysfs = root.join("platform");
        let dri = root.join("dev/dri");
        fs::create_dir_all(sysfs.join("gpu.0/drm")).unwrap();
        fs::create_dir_all(&dri).unwrap();
        fs::write(sysfs.join("gpu.0/drm/card0"), "").unwrap();
        fs::write(dri.join("card0"), "").unwrap();
        assert!(!evdi_card_exists(Path::new(&sysfs), &dri));
        fs::remove_dir_all(&root).unwrap();
    }
}

impl HostFacts {
    pub fn detect() -> Self {
        let os = detect_os();
        let session = detect_session(os);
        let desktop = detect_desktop();
        let display = detect_x11_display();
        let wayland_display = env::var("WAYLAND_DISPLAY").ok().filter(|v| !v.is_empty());
        // "remote" is used to guard backends that rely on real RandR outputs.
        // SSH by itself is not enough to mark the session as remote: developers often run
        // commands over SSH while targeting the local :0 Xorg session (no forwarding).
        let ssh = env::var_os("SSH_CONNECTION").is_some();
        let forwarded_x11 = display
            .as_deref()
            .map(|d| {
                let low = d.to_ascii_lowercase();
                low.starts_with("localhost:")
                    || low.starts_with("127.0.0.1:")
                    || low.starts_with("::1:")
            })
            .unwrap_or(false);
        let is_remote = env::var_os("XRDP_SESSION").is_some()
            || env::var_os("RDP_SESSION").is_some()
            || (ssh && forwarded_x11);

        Self {
            os,
            session,
            desktop,
            is_remote,
            display,
            wayland_display,
        }
    }
}

fn detect_os() -> HostOs {
    match env::consts::OS {
        "linux" => HostOs::Linux,
        "windows" => HostOs::Windows,
        "macos" => HostOs::MacOs,
        _ => HostOs::Unknown,
    }
}

fn detect_session(os: HostOs) -> SessionKind {
    if os != HostOs::Linux {
        return SessionKind::Unknown;
    }

    let mut session = env::var("XDG_SESSION_TYPE")
        .unwrap_or_default()
        .to_ascii_lowercase();
    if session.is_empty() {
        if env::var_os("WAYLAND_DISPLAY").is_some() {
            session = "wayland".to_string();
        } else if env::var_os("DISPLAY").is_some() {
            session = "x11".to_string();
        } else if has_wayland_socket() {
            session = "wayland".to_string();
        } else if has_x11_socket() {
            session = "x11".to_string();
        }
    }

    // In some remote launch paths (sudo/systemd user service), XDG_SESSION_TYPE can
    // be leaked as "tty" even when DISPLAY/WAYLAND_DISPLAY is correctly set.
    if session == "tty" {
        if env::var_os("WAYLAND_DISPLAY").is_some() || has_wayland_socket() {
            session = "wayland".to_string();
        } else if env::var_os("DISPLAY").is_some() || has_x11_socket() {
            session = "x11".to_string();
        }
    }

    match session.as_str() {
        "wayland" => SessionKind::Wayland,
        "x11" => SessionKind::X11,
        "tty" => SessionKind::Tty,
        _ => SessionKind::Unknown,
    }
}

fn has_wayland_socket() -> bool {
    let Some(uid) = current_uid() else {
        return false;
    };
    if uid == 0 {
        return false;
    }
    let dir = format!("/run/user/{uid}");
    let entries = match fs::read_dir(&dir) {
        Ok(v) => v,
        Err(_) => return false,
    };
    for entry in entries.flatten() {
        let name = entry.file_name();
        if let Some(name) = name.to_str() {
            if name.starts_with("wayland-") && Path::new(&entry.path()).exists() {
                return true;
            }
        }
    }
    false
}

fn current_uid() -> Option<u32> {
    if let Some(uid) = env::var("UID").ok().and_then(|raw| raw.parse::<u32>().ok()) {
        return Some(uid);
    }
    let out = Command::new("id").arg("-u").output().ok()?;
    if !out.status.success() {
        return None;
    }
    String::from_utf8_lossy(&out.stdout)
        .trim()
        .parse::<u32>()
        .ok()
}

fn has_x11_socket() -> bool {
    let entries = match fs::read_dir("/tmp/.X11-unix") {
        Ok(v) => v,
        Err(_) => return false,
    };
    for entry in entries.flatten() {
        let name = entry.file_name();
        if let Some(name) = name.to_str() {
            if name.starts_with('X') {
                return true;
            }
        }
    }
    false
}

fn detect_x11_display() -> Option<String> {
    if let Some(value) = env::var("DISPLAY").ok().filter(|v| !v.is_empty()) {
        return Some(value);
    }

    let entries = fs::read_dir("/tmp/.X11-unix").ok()?;
    let mut best: Option<u32> = None;
    for entry in entries.flatten() {
        let name = entry.file_name();
        let Some(name) = name.to_str() else {
            continue;
        };
        if !name.starts_with('X') {
            continue;
        }
        let Ok(num) = name[1..].parse::<u32>() else {
            continue;
        };
        best = Some(best.map(|cur| cur.max(num)).unwrap_or(num));
    }
    best.map(|n| format!(":{n}"))
}

fn detect_desktop() -> DesktopFlavor {
    let desktop = env::var("XDG_CURRENT_DESKTOP")
        .or_else(|_| env::var("DESKTOP_SESSION"))
        .unwrap_or_default()
        .to_ascii_lowercase();

    if desktop.contains("kde") || desktop.contains("plasma") {
        DesktopFlavor::Kde
    } else if desktop.contains("gnome") {
        DesktopFlavor::Gnome
    } else if desktop.contains("sway") {
        DesktopFlavor::Sway
    } else if desktop.contains("hypr") {
        DesktopFlavor::Hyprland
    } else if desktop.contains("xfce") {
        DesktopFlavor::Xfce
    } else if desktop.contains("cinnamon") {
        DesktopFlavor::Cinnamon
    } else if desktop.contains("mate") {
        DesktopFlavor::Mate
    } else if desktop.contains("lxde") {
        DesktopFlavor::Lxde
    } else if desktop.contains("lxqt") {
        DesktopFlavor::Lxqt
    } else {
        DesktopFlavor::Unknown
    }
}
