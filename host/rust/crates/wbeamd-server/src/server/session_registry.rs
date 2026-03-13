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

    #[allow(clippy::cognitive_complexity)]
    #[allow(clippy::cognitive_complexity)]
    pub(crate) async fn resolve_core(
        &self,
        serial: Option<&str>,
        requested_stream_port: Option<u16>,
    ) -> Arc<DaemonCore> {
        if let Some(raw) = serial {
            let normalized = raw.trim();
            if !normalized.is_empty() {
                {
                    let guard = self.serial_cores.lock().await;
                    if let Some(existing) = guard.get(normalized) {
                        return existing.core.clone();
                    }
                }

                let mut guard = self.serial_cores.lock().await;
                if let Some(existing) = guard.get(normalized) {
                    return existing.core.clone();
                }

                let stream_port = requested_stream_port.filter(|p| *p > 0).unwrap_or_else(|| {
                    self.base_stream_port.saturating_add(2 + guard.len() as u16)
                });
                let stream_port = if stream_port == self.control_port {
                    stream_port.saturating_add(1)
                } else {
                    stream_port
                };
                let session_label = Some(format!("serial-{normalized}"));
                let target_serial = Some(normalized.to_string());
                let core = Arc::new(DaemonCore::new_for_session(
                    self.root.clone(),
                    stream_port,
                    self.control_port,
                    session_label,
                    target_serial,
                ));
                guard.insert(normalized.to_string(), SessionCore { core: core.clone() });
                let mut port_guard = self.port_cores.lock().await;
                port_guard.insert(stream_port, SessionCore { core: core.clone() });
                info!(
                    serial = normalized,
                    stream_port, "created daemon session core"
                );
                return core;
            }
        }

        // Serial is unknown/empty on some Android builds; use stream_port as fallback key.
        {
            let guard = self.serial_cores.lock().await;
            if guard.len() == 1 {
                if let Some((_serial, session)) = guard.iter().next() {
                    return session.core.clone();
                }
            }
        }

        let Some(stream_port) = requested_stream_port.filter(|p| *p > 0) else {
            return self.default_core.clone();
        };
        if stream_port == self.base_stream_port {
            return self.default_core.clone();
        }
        {
            let guard = self.port_cores.lock().await;
            if let Some(existing) = guard.get(&stream_port) {
                return existing.core.clone();
            }
        }
        let mut guard = self.port_cores.lock().await;
        if let Some(existing) = guard.get(&stream_port) {
            return existing.core.clone();
        }
        let safe_stream_port = if stream_port == self.control_port {
            stream_port.saturating_add(1)
        } else {
            stream_port
        };
        let session_label = Some(format!("port-{safe_stream_port}"));
        let core = Arc::new(DaemonCore::new_for_session(
            self.root.clone(),
            safe_stream_port,
            self.control_port,
            session_label,
            None,
        ));
        guard.insert(safe_stream_port, SessionCore { core: core.clone() });
        info!(
            stream_port = safe_stream_port,
            "created daemon session core (port fallback)"
        );
        core
    }

    #[allow(clippy::cognitive_complexity)]
    #[allow(clippy::cognitive_complexity)]
    pub(crate) async fn resolve_core_readonly(
        &self,
        serial: Option<&str>,
        requested_stream_port: Option<u16>,
    ) -> Option<Arc<DaemonCore>> {
        if let Some(raw) = serial {
            let normalized = raw.trim();
            if !normalized.is_empty() {
                {
                    let guard = self.serial_cores.lock().await;
                    if let Some(existing) = guard.get(normalized) {
                        return Some(existing.core.clone());
                    }
                }
                if let Some(stream_port) = requested_stream_port.filter(|p| *p > 0) {
                    if stream_port == self.base_stream_port {
                        return Some(self.default_core.clone());
                    }
                    let guard = self.port_cores.lock().await;
                    if let Some(existing) = guard.get(&stream_port) {
                        return Some(existing.core.clone());
                    }
                }
                return None;
            }
        }

        if let Some(stream_port) = requested_stream_port.filter(|p| *p > 0) {
            if stream_port == self.base_stream_port {
                return Some(self.default_core.clone());
            }
            let guard = self.port_cores.lock().await;
            if let Some(existing) = guard.get(&stream_port) {
                return Some(existing.core.clone());
            }
            return None;
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
