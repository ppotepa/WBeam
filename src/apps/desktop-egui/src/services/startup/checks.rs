use crate::domain::startup::{StartupCheck, StartupEvent, StartupState, StartupStatus};
use crate::services::probe::adb_probe::{adb_on_path, adb_responsive};

pub(crate) struct AdbBinaryCheck;

impl StartupCheck for AdbBinaryCheck {
    fn id(&self) -> &'static str {
        "adb_binary"
    }

    fn run(&self, state: &mut StartupState) -> StartupEvent {
        state.adb_available = adb_on_path();
        if state.adb_available {
            StartupEvent {
                check_id: self.id(),
                status: StartupStatus::Ok,
                message: "adb detected on PATH".to_string(),
            }
        } else {
            StartupEvent {
                check_id: self.id(),
                status: StartupStatus::Fail,
                message: "adb not found on PATH".to_string(),
            }
        }
    }
}

pub(crate) struct AdbResponsiveCheck;

impl StartupCheck for AdbResponsiveCheck {
    fn id(&self) -> &'static str {
        "adb_responsive"
    }

    fn run(&self, state: &mut StartupState) -> StartupEvent {
        if !state.adb_available {
            state.adb_responsive = false;
            return StartupEvent {
                check_id: self.id(),
                status: StartupStatus::Warn,
                message: "skipped because adb is unavailable".to_string(),
            };
        }
        state.adb_responsive = adb_responsive();
        if state.adb_responsive {
            StartupEvent {
                check_id: self.id(),
                status: StartupStatus::Ok,
                message: "adb command is responsive".to_string(),
            }
        } else {
            StartupEvent {
                check_id: self.id(),
                status: StartupStatus::Fail,
                message: "adb command did not respond successfully".to_string(),
            }
        }
    }
}

pub(crate) struct FirstProbeCheck;

impl StartupCheck for FirstProbeCheck {
    fn id(&self) -> &'static str {
        "first_probe"
    }

    fn run(&self, state: &mut StartupState) -> StartupEvent {
        if state.first_probe_ok {
            StartupEvent {
                check_id: self.id(),
                status: StartupStatus::Ok,
                message: "initial probe completed".to_string(),
            }
        } else {
            let detail = state
                .first_probe_error
                .clone()
                .unwrap_or_else(|| "unknown probe failure".to_string());
            StartupEvent {
                check_id: self.id(),
                status: StartupStatus::Fail,
                message: format!("initial probe failed: {detail}"),
            }
        }
    }
}
