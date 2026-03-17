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
    fn single_serial_core(guard: &SessionMaps) -> Option<Arc<DaemonCore>> {
        (guard.by_serial.len() == 1)
            .then(|| guard.by_serial.iter().next().map(|(_, s)| s.core.clone()))
            .flatten()
    }

    fn should_reuse_serial_binding(
        requested_stream_port: Option<u16>,
        existing_stream_port: u16,
        legacy_android_port: u16,
    ) -> bool {
        requested_stream_port
            .map(|p| p == existing_stream_port || p == legacy_android_port)
            .unwrap_or(true)
    }

    fn normalize_requested_port(&self, requested_stream_port: Option<u16>) -> Option<u16> {
        requested_stream_port.filter(|p| *p > 0).map(|port| {
            if port == self.control_port {
                port.saturating_add(1)
            } else {
                port
            }
        })
    }

    fn serial_key(serial: Option<&str>) -> Option<&str> {
        serial.map(str::trim).filter(|s| !s.is_empty())
    }

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

    pub(crate) async fn resolve_core(
        &self,
        serial: Option<&str>,
        requested_stream_port: Option<u16>,
    ) -> Arc<DaemonCore> {
        let legacy_android_port = self.base_stream_port.saturating_add(2);
        let requested_stream_port = self.normalize_requested_port(requested_stream_port);

        if let Some(normalized) = Self::serial_key(serial) {
            return self
                .resolve_core_with_serial(normalized, requested_stream_port, legacy_android_port)
                .await;
        }

        self.resolve_core_without_serial(requested_stream_port)
            .await
    }

    async fn resolve_core_with_serial(
        &self,
        normalized: &str,
        requested_stream_port: Option<u16>,
        legacy_android_port: u16,
    ) -> Arc<DaemonCore> {
        let mismatched = {
            let guard = self.sessions.lock().await;
            self.find_serial_mismatch(
                &guard,
                normalized,
                requested_stream_port,
                legacy_android_port,
            )
        };

        if mismatched.is_none() {
            if let Some(core) = self
                .reuse_serial_core(normalized, requested_stream_port, legacy_android_port)
                .await
            {
                return core;
            }
        }

        if let Some(existing) = mismatched {
            self.release_mismatched_serial_core(normalized, requested_stream_port, existing)
                .await;
        }

        let mut guard = self.sessions.lock().await;
        if let Some(core) = Self::reuse_serial_core_from_guard(
            &guard,
            normalized,
            requested_stream_port,
            legacy_android_port,
        ) {
            return core;
        }
        if let Some(core) =
            Self::bind_serial_to_requested_port(&mut guard, normalized, requested_stream_port)
        {
            return core;
        }

        self.create_serial_core(&mut guard, normalized, requested_stream_port)
    }

    async fn resolve_core_without_serial(
        &self,
        requested_stream_port: Option<u16>,
    ) -> Arc<DaemonCore> {
        {
            let guard = self.sessions.lock().await;
            if let Some(core) = Self::single_serial_core(&guard) {
                return core;
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

    fn find_serial_mismatch(
        &self,
        guard: &SessionMaps,
        normalized: &str,
        requested_stream_port: Option<u16>,
        legacy_android_port: u16,
    ) -> Option<SessionCore> {
        guard.by_serial.get(normalized).and_then(|existing| {
            let port_matches = requested_stream_port
                .map(|p| p == existing.stream_port)
                .unwrap_or(true);
            if port_matches {
                return None;
            }
            if requested_stream_port == Some(legacy_android_port) {
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
    }

    async fn reuse_serial_core(
        &self,
        normalized: &str,
        requested_stream_port: Option<u16>,
        legacy_android_port: u16,
    ) -> Option<Arc<DaemonCore>> {
        let guard = self.sessions.lock().await;
        Self::reuse_serial_core_from_guard(
            &guard,
            normalized,
            requested_stream_port,
            legacy_android_port,
        )
    }

    fn reuse_serial_core_from_guard(
        guard: &SessionMaps,
        normalized: &str,
        requested_stream_port: Option<u16>,
        legacy_android_port: u16,
    ) -> Option<Arc<DaemonCore>> {
        let existing = guard.by_serial.get(normalized)?;
        Self::should_reuse_serial_binding(
            requested_stream_port,
            existing.stream_port,
            legacy_android_port,
        )
        .then(|| existing.core.clone())
    }

    async fn release_mismatched_serial_core(
        &self,
        normalized: &str,
        requested_stream_port: Option<u16>,
        existing: SessionCore,
    ) {
        info!(
            serial = normalized,
            from_stream_port = existing.stream_port,
            requested_stream_port = requested_stream_port.unwrap_or(existing.stream_port),
            "releasing mismatched daemon session core for serial"
        );
        self.forget_core(&existing.core).await;
        let _ = existing.core.stop().await;
    }

    fn bind_serial_to_requested_port(
        guard: &mut SessionMaps,
        normalized: &str,
        requested_stream_port: Option<u16>,
    ) -> Option<Arc<DaemonCore>> {
        let stream_port = requested_stream_port?;
        let existing = guard.by_port.get(&stream_port).cloned()?;
        guard
            .by_serial
            .insert(normalized.to_string(), existing.clone());
        info!(
            serial = normalized,
            stream_port, "bound serial to existing daemon session core by requested stream port"
        );
        Some(existing.core)
    }

    fn create_serial_core(
        &self,
        guard: &mut SessionMaps,
        normalized: &str,
        requested_stream_port: Option<u16>,
    ) -> Arc<DaemonCore> {
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
        guard
            .by_serial
            .insert(normalized.to_string(), entry.clone());
        guard.by_port.insert(stream_port, entry);
        info!(
            serial = normalized,
            stream_port, "created daemon session core"
        );
        core
    }

    // NOSONAR S3776 - Session lookup logic requires nested conditionals
    pub(crate) async fn resolve_core_readonly(
        &self,
        serial: Option<&str>,
        requested_stream_port: Option<u16>,
    ) -> Option<Arc<DaemonCore>> {
        let requested_stream_port = self.normalize_requested_port(requested_stream_port);

        if let Some(normalized) = Self::serial_key(serial) {
            return self
                .resolve_core_readonly_by_serial(normalized, requested_stream_port)
                .await;
        }

        self.resolve_core_readonly_without_serial(requested_stream_port)
            .await
    }

    async fn resolve_core_readonly_without_serial(
        &self,
        requested_stream_port: Option<u16>,
    ) -> Option<Arc<DaemonCore>> {
        let guard = self.sessions.lock().await;
        if let Some(core) = Self::single_serial_core(&guard) {
            return Some(core);
        }
        if let Some(stream_port) = requested_stream_port {
            return self.lookup_by_port_or_default(&guard, stream_port);
        }
        Some(self.default_core.clone())
    }

    async fn resolve_core_readonly_by_serial(
        &self,
        normalized: &str,
        requested_stream_port: Option<u16>,
    ) -> Option<Arc<DaemonCore>> {
        let guard = self.sessions.lock().await;
        if let Some(existing) = guard.by_serial.get(normalized) {
            if let Some(port) = requested_stream_port.filter(|p| *p != existing.stream_port) {
                info!(
                    serial = normalized,
                    requested_stream_port = port,
                    bound_stream_port = existing.stream_port,
                    "readonly session query stream_port mismatch; using serial-bound core"
                );
            }
            return Some(existing.core.clone());
        }
        requested_stream_port.and_then(|port| self.lookup_by_port_or_default(&guard, port))
    }

    fn lookup_by_port_or_default(
        &self,
        guard: &SessionMaps,
        stream_port: u16,
    ) -> Option<Arc<DaemonCore>> {
        if stream_port == self.base_stream_port {
            return Some(self.default_core.clone());
        }
        guard
            .by_port
            .get(&stream_port)
            .map(|existing| existing.core.clone())
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
