//! Host environment probing used to select or validate streaming mode.
//!
//! The current streamer implementation supports Wayland ScreenCast portal.
//! This module centralizes host/session/desktop detection so startup checks
//! and future backend routing can use one abstraction.

use std::env;
use std::fs;
use std::path::Path;

use nix::libc;

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

/// Default Linux policy:
/// - Wayland -> portal streamer
/// - X11 -> ximagesrc streamer
/// - other OS/session -> unsupported
#[derive(Debug, Default, Clone, Copy)]
pub struct DefaultHostBackendResolver;

impl HostBackendResolver for DefaultHostBackendResolver {
    fn resolve_capture_mode(&self, facts: &HostFacts) -> CaptureMode {
        match (facts.os, facts.session) {
            (HostOs::Linux, SessionKind::Wayland) => CaptureMode::WaylandPortal,
            (HostOs::Linux, SessionKind::X11) => CaptureMode::X11Gst,
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
        matches!(
            self.capture_mode,
            CaptureMode::WaylandPortal | CaptureMode::X11Gst
        )
    }

    pub fn unsupported_reason(&self) -> String {
        match self.capture_mode {
            CaptureMode::WaylandPortal => "streaming is supported".to_string(),
            CaptureMode::X11Gst => "streaming is supported".to_string(),
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
            CaptureMode::UnsupportedHost => "unsupported_host",
        }
    }
}

impl HostFacts {
    pub fn detect() -> Self {
        let os = detect_os();
        let session = detect_session(os);
        let desktop = detect_desktop();
        let display = detect_x11_display();
        let wayland_display = env::var("WAYLAND_DISPLAY").ok().filter(|v| !v.is_empty());
        let is_remote = env::var_os("XRDP_SESSION").is_some()
            || env::var_os("RDP_SESSION").is_some()
            || env::var_os("SSH_CONNECTION").is_some();

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

    match session.as_str() {
        "wayland" => SessionKind::Wayland,
        "x11" => SessionKind::X11,
        "tty" => SessionKind::Tty,
        _ => SessionKind::Unknown,
    }
}

fn has_wayland_socket() -> bool {
    let uid = unsafe { libc::geteuid() };
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
