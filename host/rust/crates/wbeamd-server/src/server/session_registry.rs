use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::sync::Arc;

use serde::Deserialize;
use tokio::sync::Mutex;
use tracing::info;
use wbeamd_core::DaemonCore;

pub(crate) struct SessionCore {
    pub(crate) core: Arc<DaemonCore>,
}

#[derive(Debug, Deserialize, Default, Clone)]
pub(crate) struct SessionQuery {
    pub(crate) serial: Option<String>,
    pub(crate) stream_port: Option<u16>,
    pub(crate) display_mode: Option<String>,
}

pub(crate) struct SessionRegistry {
    pub(crate) root: PathBuf,
    pub(crate) control_port: u16,
    pub(crate) base_stream_port: u16,
    pub(crate) default_core: Arc<DaemonCore>,
    pub(crate) portal_start_gate: Mutex<()>,
    pub(crate) portal_output_by_serial: Mutex<HashMap<String, String>>,
    pub(crate) serial_cores: Mutex<HashMap<String, SessionCore>>,
    pub(crate) port_cores: Mutex<HashMap<u16, SessionCore>>,
}

impl SessionRegistry {
    pub(crate) fn new(
        root: PathBuf,
        base_stream_port: u16,
        control_port: u16,
        default_core: Arc<DaemonCore>,
    ) -> Self {
        Self {
            root,
            control_port,
            base_stream_port,
            default_core,
            portal_start_gate: Mutex::new(()),
            portal_output_by_serial: Mutex::new(HashMap::new()),
            serial_cores: Mutex::new(HashMap::new()),
            port_cores: Mutex::new(HashMap::new()),
        }
    }

    fn normalized_serial(serial: Option<&str>) -> Option<&str> {
        serial.map(str::trim).filter(|value| !value.is_empty())
    }

    fn requested_stream_port(requested_stream_port: Option<u16>) -> Option<u16> {
        requested_stream_port.filter(|port| *port > 0)
    }

    fn safe_stream_port(&self, stream_port: u16) -> u16 {
        if stream_port == self.control_port {
            stream_port.saturating_add(1)
        } else {
            stream_port
        }
    }

    fn create_core(
        &self,
        stream_port: u16,
        session_label: Option<String>,
        target_serial: Option<String>,
    ) -> Arc<DaemonCore> {
        Arc::new(DaemonCore::new_for_session(
            self.root.clone(),
            stream_port,
            self.control_port,
            session_label,
            target_serial,
        ))
    }

    async fn serial_core(&self, serial: &str) -> Option<Arc<DaemonCore>> {
        let guard = self.serial_cores.lock().await;
        guard.get(serial).map(|entry| entry.core.clone())
    }

    async fn port_core(&self, stream_port: u16) -> Option<Arc<DaemonCore>> {
        let guard = self.port_cores.lock().await;
        guard.get(&stream_port).map(|entry| entry.core.clone())
    }

    async fn single_serial_core(&self) -> Option<Arc<DaemonCore>> {
        let guard = self.serial_cores.lock().await;
        (guard.len() == 1)
            .then(|| guard.values().next().map(|entry| entry.core.clone()))
            .flatten()
    }

    async fn requested_core_readonly(
        &self,
        requested_stream_port: Option<u16>,
    ) -> Option<Arc<DaemonCore>> {
        let stream_port = Self::requested_stream_port(requested_stream_port)?;
        if stream_port == self.base_stream_port {
            return Some(self.default_core.clone());
        }
        self.port_core(stream_port).await
    }

    async fn resolve_serial_core(
        &self,
        serial: &str,
        requested_stream_port: Option<u16>,
    ) -> Arc<DaemonCore> {
        if let Some(existing) = self.serial_core(serial).await {
            return existing;
        }

        let mut guard = self.serial_cores.lock().await;
        if let Some(existing) = guard.get(serial) {
            return existing.core.clone();
        }

        let requested_port = Self::requested_stream_port(requested_stream_port)
            .unwrap_or_else(|| self.base_stream_port.saturating_add(2 + guard.len() as u16));
        let stream_port = self.safe_stream_port(requested_port);
        let core = self.create_core(
            stream_port,
            Some(format!("serial-{serial}")),
            Some(serial.to_string()),
        );
        guard.insert(serial.to_string(), SessionCore { core: core.clone() });
        drop(guard);

        let mut port_guard = self.port_cores.lock().await;
        port_guard.insert(stream_port, SessionCore { core: core.clone() });
        info!(serial, stream_port, "created daemon session core");
        core
    }

    async fn resolve_port_core(&self, requested_stream_port: Option<u16>) -> Arc<DaemonCore> {
        let Some(stream_port) = Self::requested_stream_port(requested_stream_port) else {
            return self.default_core.clone();
        };
        if stream_port == self.base_stream_port {
            return self.default_core.clone();
        }
        if let Some(existing) = self.port_core(stream_port).await {
            return existing;
        }

        let mut guard = self.port_cores.lock().await;
        if let Some(existing) = guard.get(&stream_port) {
            return existing.core.clone();
        }

        let safe_stream_port = self.safe_stream_port(stream_port);
        let core = self.create_core(
            safe_stream_port,
            Some(format!("port-{safe_stream_port}")),
            None,
        );
        guard.insert(safe_stream_port, SessionCore { core: core.clone() });
        info!(
            stream_port = safe_stream_port,
            "created daemon session core (port fallback)"
        );
        core
    }

    async fn resolve_serial_core_readonly(
        &self,
        serial: &str,
        requested_stream_port: Option<u16>,
    ) -> Option<Arc<DaemonCore>> {
        if let Some(existing) = self.serial_core(serial).await {
            return Some(existing);
        }
        self.requested_core_readonly(requested_stream_port).await
    }

    pub(crate) async fn resolve_core(
        &self,
        serial: Option<&str>,
        requested_stream_port: Option<u16>,
    ) -> Arc<DaemonCore> {
        if let Some(serial) = Self::normalized_serial(serial) {
            return self
                .resolve_serial_core(serial, requested_stream_port)
                .await;
        }

        if let Some(existing) = self.single_serial_core().await {
            return existing;
        }

        self.resolve_port_core(requested_stream_port).await
    }

    pub(crate) async fn resolve_core_readonly(
        &self,
        serial: Option<&str>,
        requested_stream_port: Option<u16>,
    ) -> Option<Arc<DaemonCore>> {
        if let Some(serial) = Self::normalized_serial(serial) {
            return self
                .resolve_serial_core_readonly(serial, requested_stream_port)
                .await;
        }

        if Self::requested_stream_port(requested_stream_port).is_some() {
            return self.requested_core_readonly(requested_stream_port).await;
        }

        Some(self.default_core.clone())
    }

    pub(crate) fn default_core(&self) -> Arc<DaemonCore> {
        self.default_core.clone()
    }

    pub(crate) async fn forget_core(&self, core: &Arc<DaemonCore>) {
        if Arc::ptr_eq(core, &self.default_core) {
            return;
        }
        let mut removed_serials = Vec::new();
        {
            let mut guard = self.serial_cores.lock().await;
            for (serial, entry) in guard.iter() {
                if Arc::ptr_eq(&entry.core, core) {
                    removed_serials.push(serial.clone());
                }
            }
            guard.retain(|_, entry| !Arc::ptr_eq(&entry.core, core));
        }
        {
            let mut guard = self.port_cores.lock().await;
            guard.retain(|_, entry| !Arc::ptr_eq(&entry.core, core));
        }
        if !removed_serials.is_empty() {
            let mut guard = self.portal_output_by_serial.lock().await;
            for serial in removed_serials {
                guard.remove(&serial);
            }
        }
    }

    pub(crate) async fn stop_all(&self) {
        let mut cores = vec![self.default_core.clone()];
        {
            let guard = self.serial_cores.lock().await;
            for entry in guard.values() {
                cores.push(entry.core.clone());
            }
        }
        {
            let guard = self.port_cores.lock().await;
            for entry in guard.values() {
                cores.push(entry.core.clone());
            }
        }
        let mut seen = HashSet::new();
        for core in cores {
            let key = Arc::as_ptr(&core) as usize;
            if seen.insert(key) {
                let _ = core.stop().await;
            }
        }
    }

    pub(crate) async fn map_wayland_output_for_serial(&self, serial: &str, output_name: &str) {
        let trimmed_serial = serial.trim();
        let trimmed_output = output_name.trim();
        if trimmed_serial.is_empty() || trimmed_output.is_empty() {
            return;
        }
        let mut guard = self.portal_output_by_serial.lock().await;
        guard.insert(trimmed_serial.to_string(), trimmed_output.to_string());
    }

    pub(crate) async fn mapped_wayland_output_names(&self) -> HashSet<String> {
        let guard = self.portal_output_by_serial.lock().await;
        guard.values().cloned().collect()
    }
}
