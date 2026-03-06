use std::sync::mpsc::{self, Receiver};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use crate::domain::device::DeviceInfo;
use crate::services::device_manager::{
    DeviceEvent, DeviceManager as CoreDeviceManager, DeviceManagerSnapshot,
};
use crate::services::probe::adb_probe::{ProbeService, ProbeSnapshot, ProbeWorker};

pub(crate) struct DeviceManager {
    core: CoreDeviceManager,
    probe_service: ProbeService,
    selected_device_serial: Option<String>,
    runtime_events: Vec<String>,
    probe_rx: Receiver<ProbeSnapshot>,
    _probe_worker: ProbeWorker,
}

impl DeviceManager {
    pub(crate) fn new() -> Self {
        let probe_service = ProbeService::new(Duration::from_secs(2));
        let first_snapshot = probe_service.probe_once();

        let mut core = CoreDeviceManager::default();
        let initial_events = core.apply_probe(first_snapshot);
        let mut runtime_events: Vec<String> = initial_events
            .into_iter()
            .map(device_event_message)
            .collect();
        runtime_events.push(format!("[{}] discovery started", unix_now()));

        let (probe_tx, probe_rx) = mpsc::channel();
        let probe_worker = probe_service.start_background(probe_tx);

        Self {
            core,
            probe_service,
            selected_device_serial: None,
            runtime_events,
            probe_rx,
            _probe_worker: probe_worker,
        }
    }

    pub(crate) fn tick(&mut self) {
        loop {
            match self.probe_rx.try_recv() {
                Ok(snapshot) => {
                    let events = self.core.apply_probe(snapshot);
                    for event in events {
                        self.runtime_events.push(format!(
                            "[{}] {}",
                            unix_now(),
                            device_event_message(event)
                        ));
                    }
                }
                Err(std::sync::mpsc::TryRecvError::Empty) => break,
                Err(std::sync::mpsc::TryRecvError::Disconnected) => {
                    self.runtime_events
                        .push(format!("[{}] probe worker disconnected", unix_now()));
                    break;
                }
            }
        }

        if self.runtime_events.len() > 120 {
            let drop_count = self.runtime_events.len() - 120;
            self.runtime_events.drain(0..drop_count);
        }

        let snapshot = self.core.snapshot();
        if let Some(serial) = self.selected_device_serial.as_deref() {
            if !snapshot.devices.iter().any(|d| d.serial == serial) {
                self.selected_device_serial = None;
            }
        }
    }

    pub(crate) fn snapshot(&self) -> DeviceManagerSnapshot {
        self.core.snapshot()
    }

    pub(crate) fn select(&mut self, serial: &str) {
        self.selected_device_serial = Some(serial.to_string());
        self.runtime_events
            .push(format!("[{}] selected {}", unix_now(), serial));
    }

    pub(crate) fn selected_serial(&self) -> Option<&str> {
        self.selected_device_serial.as_deref()
    }

    pub(crate) fn selected_device<'a>(
        &self,
        snapshot: &'a DeviceManagerSnapshot,
    ) -> Option<&'a DeviceInfo> {
        self.selected_serial()
            .and_then(|serial| snapshot.devices.iter().find(|d| d.serial == serial))
    }

    pub(crate) fn runtime_events(&self) -> &[String] {
        &self.runtime_events
    }

    pub(crate) fn refresh_now(&mut self) {
        let snapshot = self.probe_service.probe_once();
        let events = self.core.apply_probe(snapshot);
        for event in events {
            self.runtime_events
                .push(format!("[{}] {}", unix_now(), device_event_message(event)));
        }
        self.runtime_events
            .push(format!("[{}] manual refresh completed", unix_now()));
    }

    pub(crate) fn push_ui_event(&mut self, msg: impl Into<String>) {
        self.runtime_events
            .push(format!("[{}] {}", unix_now(), msg.into()));
    }

    pub(crate) fn is_selected(&self, serial: &str) -> bool {
        self.selected_serial() == Some(serial)
    }
}

fn device_event_message(event: DeviceEvent) -> String {
    match event {
        DeviceEvent::Added(serial) => format!("device connected: {serial}"),
        DeviceEvent::Removed(serial) => format!("device disconnected: {serial}"),
        DeviceEvent::StateChanged { serial, from, to } => {
            format!("device state changed: {serial} {from} -> {to}")
        }
        DeviceEvent::ProbeError(err) => format!("probe error: {err}"),
        DeviceEvent::ProbeRecovered => "probe recovered".to_string(),
    }
}

fn unix_now() -> String {
    let secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    secs.to_string()
}
