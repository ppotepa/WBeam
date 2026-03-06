use std::collections::BTreeMap;

use crate::domain::device::DeviceInfo;
use crate::domain::host::HostContext;
use crate::services::probe::adb_probe::ProbeSnapshot;

#[derive(Clone, Debug)]
pub(crate) enum DeviceEvent {
    Added(String),
    Removed(String),
    StateChanged {
        serial: String,
        from: String,
        to: String,
    },
    ProbeError(String),
    ProbeRecovered,
}

#[derive(Clone, Debug, Default)]
pub(crate) struct DeviceManagerSnapshot {
    pub(crate) host: HostContext,
    pub(crate) devices: Vec<DeviceInfo>,
    pub(crate) adb_available: bool,
    pub(crate) adb_responsive: bool,
    pub(crate) probe_error: Option<String>,
}

#[derive(Default)]
pub(crate) struct DeviceManager {
    host: HostContext,
    devices: BTreeMap<String, DeviceInfo>,
    adb_available: bool,
    adb_responsive: bool,
    probe_error: Option<String>,
}

impl DeviceManager {
    pub(crate) fn apply_probe(&mut self, snapshot: ProbeSnapshot) -> Vec<DeviceEvent> {
        let mut events = Vec::new();
        self.host = snapshot.host;
        self.adb_available = snapshot.adb_available;
        self.adb_responsive = snapshot.adb_responsive;

        match (&self.probe_error, &snapshot.error) {
            (None, Some(err)) => events.push(DeviceEvent::ProbeError(err.clone())),
            (Some(_), None) => events.push(DeviceEvent::ProbeRecovered),
            _ => {}
        }
        self.probe_error = snapshot.error;

        let mut incoming = BTreeMap::new();
        for device in snapshot.devices {
            incoming.insert(device.serial.clone(), device);
        }

        for (serial, next) in &incoming {
            match self.devices.get(serial) {
                None => events.push(DeviceEvent::Added(serial.clone())),
                Some(prev) if prev.adb_state != next.adb_state => {
                    events.push(DeviceEvent::StateChanged {
                        serial: serial.clone(),
                        from: prev.adb_state.clone(),
                        to: next.adb_state.clone(),
                    });
                }
                _ => {}
            }
        }

        for serial in self.devices.keys() {
            if !incoming.contains_key(serial) {
                events.push(DeviceEvent::Removed(serial.clone()));
            }
        }

        self.devices = incoming;
        events
    }

    pub(crate) fn snapshot(&self) -> DeviceManagerSnapshot {
        let mut devices: Vec<DeviceInfo> = self.devices.values().cloned().collect();
        devices.sort_by(|a, b| a.serial.cmp(&b.serial));
        DeviceManagerSnapshot {
            host: self.host.clone(),
            devices,
            adb_available: self.adb_available,
            adb_responsive: self.adb_responsive,
            probe_error: self.probe_error.clone(),
        }
    }
}
