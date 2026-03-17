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

/// Both session maps are held under a single lock to avoid the double-lock
/// acquisition that the previous design (two separate Mutexes) required when
/// inserting into both maps atomically in `resolve_core`.
struct SessionMaps {
    by_serial: HashMap<String, SessionCore>,
    by_port: HashMap<u16, SessionCore>,
}

pub(crate) struct SessionRegistry {
    pub(crate) root: PathBuf,
    pub(crate) control_port: u16,
    pub(crate) base_stream_port: u16,
    pub(crate) default_core: Arc<DaemonCore>,
    pub(crate) portal_start_gate: Mutex<()>,
    pub(crate) portal_output_by_serial: Mutex<HashMap<String, String>>,
    /// Combined lock for serial→core and port→core maps.  Single acquisition
    /// per `resolve_core` call; no ordering hazards.
    sessions: Mutex<SessionMaps>,
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
            sessions: Mutex::new(SessionMaps {
                by_serial: HashMap::new(),
                by_port: HashMap::new(),
            }),
        }
    }

    // NOSONAR S3776 - Multi-level session routing requires nested conditionals
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
                // Collect the mismatched entry (if any) before acquiring the
                // lock to call stop() — stop() is async and must not be called
                // while holding the sessions lock.
                let mismatched = {
                    let guard = self.sessions.lock().await;
                    guard.by_serial.get(normalized).and_then(|existing| {
                        let port_matches = requested_stream_port
                            .map(|p| p == existing.stream_port)
                            .unwrap_or(true);
                        let is_legacy_alias = requested_stream_port
                            .map(|p| p == legacy_android_port)
                            .unwrap_or(false);
                        if port_matches {
                            return None; // fast path: already registered with matching port
                        }
                        if is_legacy_alias {
                            info!(
                                serial = normalized,
                                requested_stream_port = legacy_android_port,
                                bound_stream_port = existing.stream_port,
                                "legacy stream_port alias query; keeping existing serial-bound core"
                            );
                            return None;
                        }
                        Some(existing.clone())
                    })
                };

                // Fast path: serial already registered with matching port (or alias).
                // Re-acquire to return the core now that we've dropped the lock.
                if mismatched.is_none() {
                    let guard = self.sessions.lock().await;
                    if let Some(existing) = guard.by_serial.get(normalized) {
                        let port_matches = requested_stream_port
                            .map(|p| p == existing.stream_port || p == legacy_android_port)
                            .unwrap_or(true);
                        if port_matches {
                            return existing.core.clone();
                        }
                    }
                }

                if let Some(existing) = mismatched {
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

                // Single lock acquisition for the rest of the resolve logic.
                let mut guard = self.sessions.lock().await;

                // Re-check after the mismatch cleanup.
                if let Some(existing) = guard.by_serial.get(normalized) {
                    if requested_stream_port
                        .map(|p| p == existing.stream_port)
                        .unwrap_or(true)
                    {
                        return existing.core.clone();
                    }
                }

                // Bind to an existing port-keyed core if the requested port is known.
                if let Some(stream_port) = requested_stream_port {
                    if let Some(existing) = guard.by_port.get(&stream_port).cloned() {
                        guard
                            .by_serial
                            .insert(normalized.to_string(), existing.clone());
                        info!(
                            serial = normalized,
                            stream_port,
                            "bound serial to existing daemon session core by requested stream port"
                        );
                        return existing.core.clone();
                    }
                }

                // Create a new core for this serial.
                let stream_port = requested_stream_port.unwrap_or_else(|| {
                    self.base_stream_port
                        .saturating_add(2 + guard.by_serial.len() as u16)
                });
                let core = Arc::new(DaemonCore::new_for_session(
                    self.root.clone(),
                    stream_port,
                    self.control_port,
                    Some(format!("serial-{normalized}")),
                    Some(normalized.to_string()),
                ));
                let entry = SessionCore {
                    core: core.clone(),
                    stream_port,
                };
                guard.by_serial.insert(normalized.to_string(), entry.clone());
                guard.by_port.insert(stream_port, entry);
                info!(serial = normalized, stream_port, "created daemon session core");
                return core;
            }
        }

        // Serial unknown/empty on some Android builds — use stream_port as key.
        {
            let guard = self.sessions.lock().await;
            if guard.by_serial.len() == 1 {
                if let Some((_, session)) = guard.by_serial.iter().next() {
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
        let mut guard = self.sessions.lock().await;
        if let Some(existing) = guard.by_port.get(&stream_port) {
            return existing.core.clone();
        }
        let core = Arc::new(DaemonCore::new_for_session(
            self.root.clone(),
            stream_port,
            self.control_port,
            Some(format!("port-{stream_port}")),
            None,
        ));
        guard.by_port.insert(
            stream_port,
            SessionCore {
                core: core.clone(),
                stream_port,
            },
        );
        info!(stream_port, "created daemon session core (port fallback)");
        core
    }

    // NOSONAR S3776 - Session lookup logic requires nested conditionals
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
                let guard = self.sessions.lock().await;
                if let Some(existing) = guard.by_serial.get(normalized) {
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
                if let Some(stream_port) = requested_stream_port {
                    if stream_port == self.base_stream_port {
                        return Some(self.default_core.clone());
                    }
                    if let Some(existing) = guard.by_port.get(&stream_port) {
                        return Some(existing.core.clone());
                    }
                }
                return None;
            }
        }

        // Single-device shortcut for serial-less APK builds.
        {
            let guard = self.sessions.lock().await;
            if guard.by_serial.len() == 1 {
                if let Some((_, session)) = guard.by_serial.iter().next() {
                    return Some(session.core.clone());
                }
            }
            if let Some(stream_port) = requested_stream_port {
                if stream_port == self.base_stream_port {
                    return Some(self.default_core.clone());
                }
                if let Some(existing) = guard.by_port.get(&stream_port) {
                    return Some(existing.core.clone());
                }
                return None;
            }
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
        let removed_serials = {
            let mut guard = self.sessions.lock().await;
            let serials: Vec<String> = guard
                .by_serial
                .iter()
                .filter(|(_, e)| Arc::ptr_eq(&e.core, core))
                .map(|(k, _)| k.clone())
                .collect();
            for s in &serials {
                guard.by_serial.remove(s);
            }
            guard.by_port.retain(|_, e| !Arc::ptr_eq(&e.core, core));
            serials
        };
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
            let guard = self.sessions.lock().await;
            for entry in guard.by_serial.values() {
                cores.push(entry.core.clone());
            }
            for entry in guard.by_port.values() {
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
