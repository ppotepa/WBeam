use eframe::egui::Color32;

#[derive(Clone, Debug, Default)]
pub(crate) struct DeviceInfo {
    pub(crate) serial: String,
    pub(crate) state: String,
    pub(crate) model: String,
    pub(crate) transport: String,
}

#[derive(Clone, Debug, Default)]
pub(crate) struct StreamStats {
    pub(crate) pipeline_fps: String,
    pub(crate) sender_fps: String,
    pub(crate) timeout_misses: String,
    pub(crate) stale_dupe: String,
    pub(crate) seq: String,
    pub(crate) source: String,
    pub(crate) wbh1: Option<Wbh1Stats>,
}

#[derive(Clone, Debug, Default)]
pub(crate) struct Wbh1Stats {
    pub(crate) units: String,
    pub(crate) fps: String,
    pub(crate) mbps: String,
    pub(crate) avg_kb: String,
    pub(crate) min_kb: String,
    pub(crate) max_kb: String,
    pub(crate) key_pct: String,
    pub(crate) lat_ms: String,
    pub(crate) lat_max_ms: String,
    pub(crate) seq: String,
}

#[derive(Clone, Debug)]
pub(crate) enum EventLevel {
    Info,
    Warn,
    Error,
}

impl EventLevel {
    pub(crate) fn label(&self) -> &'static str {
        match self {
            Self::Info => "INFO",
            Self::Warn => "WARN",
            Self::Error => "ERROR",
        }
    }

    pub(crate) fn color(&self) -> Color32 {
        match self {
            Self::Info => Color32::LIGHT_BLUE,
            Self::Warn => Color32::YELLOW,
            Self::Error => Color32::from_rgb(255, 120, 120),
        }
    }
}

#[derive(Clone, Debug)]
pub(crate) struct StatusEvent {
    pub(crate) ts: String,
    pub(crate) level: EventLevel,
    pub(crate) message: String,
}
