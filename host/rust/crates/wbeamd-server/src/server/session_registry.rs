use std::collections::{HashMap, HashSet};
use std::path::PathBuf;
use std::sync::Arc;

use serde::Deserialize;
use tokio::sync::Mutex;
use tracing::info;
use wbeamd_core::DaemonCore;

#[derive(Clone)]
pub(crate) struct SessionCore {
    pub(crate) core: Arc<DaemonCore>,
    pub(crate) stream_port: u16,
}

#[derive(Debug, Deserialize, Default, Clone)]
pub(crate) struct SessionQuery {
    pub(crate) serial: Option<String>,
    pub(crate) stream_port: Option<u16>,
    pub(crate) display_mode: Option<String>,
    pub(crate) capture_backend: Option<String>,
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

    pub(crate) async fn resolve_core(
        &self,
        serial: Option<&str>,
        requested_stream_port: Option<u16>,
    ) -> Arc<DaemonCore> {
        let legacy_android_port = self.base_stream_port.saturating_add(2);
        let requested_stream_port = requested_stream_port.filter(|p| *p > 0).map(|port| {
            if port == self.control_port {
                port.saturating_add(1)
            } else {
                port
            }
        });

        if let Some(raw) = serial {
            let normalized = raw.trim();
            if !normalized.is_empty() {
                let mut existing_serial_core: Option<SessionCore> = None;
                {
                    let guard = self.serial_cores.lock().await;
                    if let Some(existing) = guard.get(normalized) {
                        if requested_stream_port
                            .map(|port| port == existing.stream_port)
                            .unwrap_or(true)
                        {
                            return existing.core.clone();
                        }
                        if requested_stream_port
                            .map(|port| port == legacy_android_port)
                            .unwrap_or(false)
                        {
                            info!(
                                serial = normalized,
                                requested_stream_port = legacy_android_port,
                                bound_stream_port = existing.stream_port,
                                "legacy stream_port alias query; keeping existing serial-bound core"
                            );
                            return existing.core.clone();
                        }
                        existing_serial_core = Some(existing.clone());
                    }
                }

                if let Some(existing) = existing_serial_core {
                    info!(
                        serial = normalized,
                        from_stream_port = existing.stream_port,
                        requested_stream_port =
                            requested_stream_port.unwrap_or(existing.stream_port),
                        "releasing mismatched daemon session core for serial"
                    );
                    self.forget_core(&existing.core).await;
                    let _ = existing.core.stop().await;
                }

                if let Some(stream_port) = requested_stream_port {
                    let maybe_existing = {
                        let guard = self.port_cores.lock().await;
                        guard.get(&stream_port).cloned()
                    };
                    if let Some(existing) = maybe_existing {
                        let mut serial_guard = self.serial_cores.lock().await;
                        serial_guard.insert(normalized.to_string(), existing.clone());
                        info!(
                            serial = normalized,
                            stream_port,
                            "bound serial to existing daemon session core by requested stream port"
                        );
                        return existing.core.clone();
                    }
                }

                let mut guard = self.serial_cores.lock().await;
                if let Some(existing) = guard.get(normalized) {
                    if requested_stream_port
                        .map(|port| port == existing.stream_port)
                        .unwrap_or(true)
                    {
                        return existing.core.clone();
                    }
                }

                let stream_port = requested_stream_port.unwrap_or_else(|| {
                    self.base_stream_port.saturating_add(2 + guard.len() as u16)
                });
                let session_label = Some(format!("serial-{normalized}"));
                let target_serial = Some(normalized.to_string());
                let core = Arc::new(DaemonCore::new_for_session(
                    self.root.clone(),
                    stream_port,
                    self.control_port,
                    session_label,
                    target_serial,
                ));
                guard.insert(
                    normalized.to_string(),
                    SessionCore {
                        core: core.clone(),
                        stream_port,
                    },
                );
                let mut port_guard = self.port_cores.lock().await;
                port_guard.insert(
                    stream_port,
                    SessionCore {
                        core: core.clone(),
                        stream_port,
                    },
                );
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

        let Some(stream_port) = requested_stream_port else {
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
        let session_label = Some(format!("port-{stream_port}"));
        let core = Arc::new(DaemonCore::new_for_session(
            self.root.clone(),
            stream_port,
            self.control_port,
            session_label,
            None,
        ));
        guard.insert(
            stream_port,
            SessionCore {
                core: core.clone(),
                stream_port,
            },
        );
        info!(stream_port, "created daemon session core (port fallback)");
        core
    }

    pub(crate) async fn resolve_core_readonly(
        &self,
        serial: Option<&str>,
        requested_stream_port: Option<u16>,
    ) -> Option<Arc<DaemonCore>> {
        let requested_stream_port = requested_stream_port.filter(|p| *p > 0).map(|port| {
            if port == self.control_port {
                port.saturating_add(1)
            } else {
                port
            }
        });

        if let Some(raw) = serial {
            let normalized = raw.trim();
            if !normalized.is_empty() {
                {
                    let guard = self.serial_cores.lock().await;
                    if let Some(existing) = guard.get(normalized) {
                        if let Some(port) = requested_stream_port {
                            if port != existing.stream_port {
                                info!(
                                    serial = normalized,
                                    requested_stream_port = port,
                                    bound_stream_port = existing.stream_port,
                                    "readonly session query stream_port mismatch; using serial-bound core"
                                );
                            }
                        }
                        return Some(existing.core.clone());
                    }
                }
                if let Some(stream_port) = requested_stream_port {
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

        // Serial can be missing on some APK builds. For single-device sessions,
        // prefer the only serial-bound core over the default idle core.
        {
            let guard = self.serial_cores.lock().await;
            if guard.len() == 1 {
                if let Some((_serial, session)) = guard.iter().next() {
                    return Some(session.core.clone());
                }
            }
        }

        if let Some(stream_port) = requested_stream_port {
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
