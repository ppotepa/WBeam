use std::time::{Duration, Instant};

use crate::domain::device::DeviceInfo;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum SessionMode {
    Automatic,
    Manual,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum SessionState {
    Idle,
    Preparing,
    Running,
    Error,
}

#[derive(Clone, Debug)]
pub(crate) struct SessionConfig {
    pub(crate) mode: SessionMode,
    pub(crate) profile: String,
    pub(crate) bitrate_kbps: u32,
}

impl Default for SessionConfig {
    fn default() -> Self {
        Self {
            mode: SessionMode::Automatic,
            profile: "modern".to_string(),
            bitrate_kbps: 14000,
        }
    }
}

#[derive(Clone, Debug, Default)]
pub(crate) struct SessionTelemetry {
    pub(crate) fps: f32,
    pub(crate) latency_ms: u32,
    pub(crate) drops: u32,
    pub(crate) bitrate_kbps: u32,
}

pub(crate) struct SessionManager {
    state: SessionState,
    config: SessionConfig,
    selected_device: Option<String>,
    started_at: Option<Instant>,
    last_error: Option<String>,
    event_log: Vec<String>,
}

impl Default for SessionManager {
    fn default() -> Self {
        Self {
            state: SessionState::Idle,
            config: SessionConfig::default(),
            selected_device: None,
            started_at: None,
            last_error: None,
            event_log: Vec::new(),
        }
    }
}

impl SessionManager {
    pub(crate) fn state(&self) -> SessionState {
        self.state
    }

    pub(crate) fn config(&self) -> &SessionConfig {
        &self.config
    }

    pub(crate) fn last_error(&self) -> Option<&str> {
        self.last_error.as_deref()
    }

    pub(crate) fn event_log(&self) -> &[String] {
        &self.event_log
    }

    pub(crate) fn set_mode(&mut self, mode: SessionMode) {
        if self.config.mode == mode {
            return;
        }
        self.config.mode = mode;
        if mode == SessionMode::Automatic {
            self.config.profile = "modern".to_string();
            self.config.bitrate_kbps = 14000;
        }
        self.push_event(format!("mode switched to {:?}", mode));
    }

    pub(crate) fn set_manual_profile(&mut self, profile: String) {
        if self.config.mode == SessionMode::Manual {
            self.config.profile = profile;
        }
    }

    pub(crate) fn set_manual_bitrate(&mut self, bitrate_kbps: u32) {
        if self.config.mode == SessionMode::Manual {
            self.config.bitrate_kbps = bitrate_kbps.clamp(1000, 100_000);
        }
    }

    pub(crate) fn start(&mut self, device: Option<&DeviceInfo>) {
        self.last_error = None;

        let Some(device) = device else {
            self.state = SessionState::Error;
            self.last_error = Some("no device selected".to_string());
            self.push_event("session start failed: no device selected".to_string());
            return;
        };

        if device.adb_state != "device" {
            self.state = SessionState::Error;
            self.last_error = Some(format!("device state is {}", device.adb_state));
            self.push_event(format!(
                "session start failed: {} state {}",
                device.serial, device.adb_state
            ));
            return;
        }

        if let Err(err) = self.validate_config() {
            self.state = SessionState::Error;
            self.last_error = Some(err.clone());
            self.push_event(format!("session start failed: {err}"));
            return;
        }

        self.state = SessionState::Preparing;
        self.selected_device = Some(device.serial.clone());
        self.push_event(format!("session preparing for {}", device.serial));

        self.state = SessionState::Running;
        self.started_at = Some(Instant::now());
        self.push_event(format!("session started for {}", device.serial));
    }

    pub(crate) fn stop(&mut self) {
        if self.state == SessionState::Running || self.state == SessionState::Preparing {
            self.push_event("session stopped".to_string());
        }
        self.state = SessionState::Idle;
        self.selected_device = None;
        self.started_at = None;
        self.last_error = None;
    }

    pub(crate) fn uptime(&self) -> Duration {
        self.started_at.map(|t| t.elapsed()).unwrap_or_default()
    }

    pub(crate) fn telemetry(&self) -> SessionTelemetry {
        if self.state != SessionState::Running {
            return SessionTelemetry {
                bitrate_kbps: self.config.bitrate_kbps,
                ..Default::default()
            };
        }

        let secs = self.uptime().as_secs() as u32;
        SessionTelemetry {
            fps: 58.0 + (secs % 3) as f32,
            latency_ms: 20 + (secs % 7),
            drops: secs / 45,
            bitrate_kbps: self.config.bitrate_kbps,
        }
    }

    pub(crate) fn validate_config(&self) -> Result<(), String> {
        if self.config.mode == SessionMode::Automatic {
            return Ok(());
        }

        if self.config.profile.trim().is_empty() {
            return Err("manual profile cannot be empty".to_string());
        }
        if !(1000..=100_000).contains(&self.config.bitrate_kbps) {
            return Err("manual bitrate must be within 1000..100000 kbps".to_string());
        }
        Ok(())
    }

    fn push_event(&mut self, message: String) {
        self.event_log.push(message);
        if self.event_log.len() > 80 {
            let drop_count = self.event_log.len() - 80;
            self.event_log.drain(0..drop_count);
        }
    }
}
