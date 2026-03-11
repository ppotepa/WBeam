use super::super::{Activation, ActivationError, VirtualMonitorProbe};

pub fn probe() -> VirtualMonitorProbe {
    VirtualMonitorProbe {
        supported: false,
        resolver: "windows_backend".to_string(),
        missing_deps: vec!["windows-virtual-monitor-driver".to_string()],
        hint: "Windows virtual monitor backend is planned but not implemented yet. Duplicate mode is available.".to_string(),
    }
}

pub fn activate() -> Result<Activation, ActivationError> {
    Err(ActivationError::Unsupported(
        "Windows virtual monitor backend is not implemented yet".to_string(),
    ))
}
