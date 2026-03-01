use std::fs;
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

use eframe::egui::{self, Color32};
use serde_json::{json, Value};

use crate::models::{DeviceInfo, EventLevel, StatusEvent, StreamStats, UiMode};
use crate::platform::traits::{MonitorEvent, PlatformModule};
use crate::platform::{run_startup_checks, start_background_checks, BackgroundMonitor};
use crate::services::{now_string, read_adb_devices, read_json, read_stream_stats, write_json};

pub(crate) const APP_ICON_PNG: &[u8] = include_bytes!("../../../assets/wbeam.png");

pub(crate) struct DesktopApp {
    pub(crate) proto_root: PathBuf,
    pub(crate) config_path: PathBuf,
    pub(crate) run_script: PathBuf,
    pub(crate) run_py: PathBuf,
    pub(crate) runner_log: PathBuf,
    pub(crate) portal_log: PathBuf,
    pub(crate) kms_log: PathBuf,
    pub(crate) android_log: PathBuf,
    pub(crate) os_name: String,
    pub(crate) os_version: String,
    pub(crate) session_type: String,
    pub(crate) host_name: String,
    pub(crate) platform_id: String,
    pub(crate) ui_mode: UiMode,
    pub(crate) current_mode_line: String,
    pub(crate) status_line: String,
    pub(crate) updated_at: String,
    pub(crate) devices: Vec<DeviceInfo>,
    pub(crate) stats: StreamStats,
    pub(crate) backend_value: String,
    pub(crate) fps_value: String,
    pub(crate) bitrate_value: String,
    pub(crate) show_extended_stats: bool,
    pub(crate) events: Vec<StatusEvent>,
    pub(crate) last_event_signature: String,
    pub(crate) last_event_at: Instant,
    pub(crate) next_refresh_at: Instant,
    pub(crate) launched_at: Instant,
    pub(crate) splash_texture: Option<egui::TextureHandle>,
    background_monitor: BackgroundMonitor,
}

impl DesktopApp {
    pub(crate) fn new(proto_root: PathBuf, platform_module: Box<dyn PlatformModule>) -> Self {
        let config_path = proto_root.join("config/proto.json");
        let cfg = read_json(&config_path).unwrap_or_else(|_| json!({}));
        let backend_value = cfg
            .get("PROTO_CAPTURE_BACKEND")
            .and_then(Value::as_str)
            .unwrap_or("auto")
            .to_string();
        let fps_value = cfg
            .get("PROTO_CAPTURE_FPS")
            .map(|v| v.to_string())
            .unwrap_or_else(|| "60".to_string());
        let bitrate_value = cfg
            .get("PROTO_CAPTURE_BITRATE_KBPS")
            .map(|v| v.to_string())
            .unwrap_or_else(|| "10000".to_string());
        let current_mode_line = build_mode_line_from_cfg(&cfg, &backend_value, &fps_value);
        let platform_id = platform_module.id().to_string();
        let (bootstrap, startup_events) = run_startup_checks(platform_module.as_ref());
        let background_monitor = start_background_checks(platform_module.as_ref());

        let mut app = Self {
            run_script: proto_root.join("run.sh"),
            run_py: proto_root.join("run.py"),
            runner_log: PathBuf::from("/tmp/proto-runner.log"),
            proto_root,
            config_path,
            portal_log: PathBuf::from("/tmp/proto-portal-streamer.log"),
            kms_log: PathBuf::from("/tmp/proto-kms-ffmpeg.log"),
            android_log: PathBuf::from("/tmp/proto-android.log"),
            os_name: bootstrap.os_name,
            os_version: bootstrap.os_version,
            session_type: bootstrap.session_type,
            host_name: bootstrap.host_name,
            platform_id: platform_id.clone(),
            ui_mode: UiMode::Basic,
            current_mode_line,
            status_line: "ready".to_string(),
            updated_at: "-".to_string(),
            devices: bootstrap.devices,
            stats: StreamStats::default(),
            backend_value,
            fps_value,
            bitrate_value,
            show_extended_stats: false,
            events: Vec::new(),
            last_event_signature: String::new(),
            last_event_at: Instant::now(),
            next_refresh_at: Instant::now(),
            launched_at: Instant::now(),
            splash_texture: None,
            background_monitor,
        };
        for event in startup_events {
            app.apply_monitor_event(event);
        }
        app.refresh_data();
        app.refresh_mode_line("current mode", true);
        app.add_event(
            EventLevel::Info,
            format!(
                "desktop app started (mode={} platform={} os={} session={} host={})",
                app.ui_mode.label(),
                app.platform_id,
                app.os_version,
                app.session_type,
                app.host_name
            ),
        );
        app
    }

    pub(crate) fn refresh_data(&mut self) {
        let prev_stats = self.stats.clone();
        self.devices = read_adb_devices().unwrap_or_default();
        self.stats = read_stream_stats(
            &self.runner_log,
            &self.portal_log,
            &self.kms_log,
            &self.android_log,
        );
        self.track_health_events(&prev_stats);
        self.updated_at = now_string();
        self.status_line = "ok".to_string();
        self.refresh_mode_line("mode changed", false);
        self.next_refresh_at = Instant::now() + Duration::from_millis(2200);
    }

    pub(crate) fn save_settings(&mut self) {
        let backend = self.backend_value.trim().to_string();
        let fps = self.fps_value.trim().parse::<u32>();
        let bitrate = self.bitrate_value.trim().parse::<u32>();

        let allowed = ["auto", "portal", "kms_drm", "grim", "spectacle", "import"];
        if !allowed.contains(&backend.as_str()) {
            self.status_line = "invalid backend".to_string();
            self.add_event(EventLevel::Error, "save rejected: invalid backend");
            return;
        }
        let Ok(fps) = fps else {
            self.status_line = "invalid fps".to_string();
            self.add_event(EventLevel::Error, "save rejected: invalid fps");
            return;
        };
        let Ok(bitrate) = bitrate else {
            self.status_line = "invalid bitrate".to_string();
            self.add_event(EventLevel::Error, "save rejected: invalid bitrate");
            return;
        };
        if !(1..=240).contains(&fps) {
            self.status_line = "fps out of range".to_string();
            self.add_event(EventLevel::Error, "save rejected: fps out of range");
            return;
        }
        if !(100..=200_000).contains(&bitrate) {
            self.status_line = "bitrate out of range".to_string();
            self.add_event(EventLevel::Error, "save rejected: bitrate out of range");
            return;
        }

        let mut cfg = read_json(&self.config_path).unwrap_or_else(|_| json!({}));
        if !cfg.is_object() {
            cfg = json!({});
        }
        cfg["PROTO_CAPTURE_BACKEND"] = Value::String(backend.clone());
        cfg["PROTO_CAPTURE_FPS"] = Value::Number(fps.into());
        cfg["PROTO_CAPTURE_BITRATE_KBPS"] = Value::Number(bitrate.into());

        match write_json(&self.config_path, &cfg) {
            Ok(()) => {
                self.refresh_mode_line("profile updated", true);
                self.status_line = format!(
                    "saved {} ({})",
                    self.config_path.display(),
                    self.current_mode_line
                );
                self.add_event(
                    EventLevel::Info,
                    format!(
                        "settings saved: backend={} fps={} bitrate={}",
                        backend, fps, bitrate
                    ),
                );
            }
            Err(err) => {
                self.status_line = format!("save failed: {err}");
                self.add_event(EventLevel::Error, format!("save failed: {err}"));
            }
        }
    }

    pub(crate) fn run_proto(&mut self) {
        self.refresh_mode_line("launch mode", true);
        match self.spawn_runner() {
            Ok(mode) => {
                self.status_line = format!("launched via {mode} ({})", self.current_mode_line);
                self.add_event(
                    EventLevel::Info,
                    format!("run launched via {mode} ({})", self.current_mode_line),
                );
            }
            Err(err) => {
                self.status_line = format!("run failed: {err}");
                self.add_event(EventLevel::Error, format!("run failed: {err}"));
            }
        }
    }

    pub(crate) fn run_host_only_rust(&mut self) {
        self.refresh_mode_line("launch mode", true);
        let manifest = self.proto_root.join("host/Cargo.toml");
        let config = self.proto_root.join("config/proto.conf");
        let mut cmd = Command::new("cargo");
        cmd.arg("run")
            .arg("--manifest-path")
            .arg(manifest)
            .arg("--release")
            .arg("--")
            .arg("--config")
            .arg(config)
            .current_dir(&self.proto_root);
        match self.spawn_with_log(&mut cmd) {
            Ok(_) => {
                self.status_line = format!(
                    "host (rust) launched ({}) log: {}",
                    self.current_mode_line,
                    self.runner_log.display()
                );
                self.add_event(
                    EventLevel::Info,
                    format!(
                        "host only launched ({}) log: {}",
                        self.current_mode_line,
                        self.runner_log.display()
                    ),
                );
            }
            Err(err) => {
                self.status_line = format!("host launch failed: {err}");
                self.add_event(EventLevel::Error, format!("host launch failed: {err}"));
            }
        }
    }

    pub(crate) fn reconnect_stream(&mut self) {
        self.refresh_data();
        self.add_event(EventLevel::Info, "reconnect requested");
    }

    pub(crate) fn stop_stream_request(&mut self) {
        self.status_line = "stop requested (soft stop)".to_string();
        self.add_event(
            EventLevel::Warn,
            "stop requested; restart stream session from host if needed",
        );
    }

    pub(crate) fn add_event(&mut self, level: EventLevel, message: impl Into<String>) {
        let message = message.into();
        let signature = format!("{}|{}", level.label(), message);
        if signature == self.last_event_signature
            && self.last_event_at.elapsed() < Duration::from_secs(5)
        {
            return;
        }
        self.last_event_signature = signature;
        self.last_event_at = Instant::now();
        self.events.push(StatusEvent {
            ts: now_string(),
            level,
            message,
        });
        if self.events.len() > 250 {
            let drain = self.events.len() - 250;
            self.events.drain(0..drain);
        }
    }

    fn apply_monitor_event(&mut self, event: MonitorEvent) {
        match event {
            MonitorEvent::Info { source, message } => {
                self.add_event(EventLevel::Info, format!("[{source}] {message}"));
            }
            MonitorEvent::Warn { source, message } => {
                self.add_event(EventLevel::Warn, format!("[{source}] {message}"));
            }
            MonitorEvent::Error { source, message } => {
                self.add_event(EventLevel::Error, format!("[{source}] {message}"));
            }
            MonitorEvent::DevicesChanged { source, devices } => {
                let count = devices.len();
                self.devices = devices;
                self.add_event(
                    EventLevel::Info,
                    format!("[{source}] device list changed (count={count})"),
                );
            }
        }
    }

    fn drain_background_events(&mut self) {
        loop {
            match self.background_monitor.try_recv() {
                Ok(event) => self.apply_monitor_event(event),
                Err(std::sync::mpsc::TryRecvError::Empty) => break,
                Err(std::sync::mpsc::TryRecvError::Disconnected) => {
                    self.add_event(EventLevel::Warn, "background monitor disconnected");
                    break;
                }
            }
        }
    }

    fn spawn_runner(&self) -> Result<&'static str, String> {
        if self.run_py.exists() {
            if let Some(py) = which("python3").or_else(|| which("python")) {
                let mut cmd = Command::new(py);
                cmd.arg(self.run_py.as_os_str())
                    .current_dir(&self.proto_root);
                self.spawn_with_log(&mut cmd)?;
                return Ok("rust launcher -> run.py");
            }
        }
        if self.run_script.exists() {
            let mut cmd = Command::new("bash");
            cmd.arg(self.run_script.as_os_str())
                .current_dir(&self.proto_root);
            self.spawn_with_log(&mut cmd)?;
            return Ok("fallback run.sh");
        }
        Err("no runner found (missing run.py and run.sh)".to_string())
    }

    fn spawn_with_log(&self, cmd: &mut Command) -> Result<(), String> {
        let log_out = fs::File::create(&self.runner_log).map_err(|e| e.to_string())?;
        let log_err = log_out.try_clone().map_err(|e| e.to_string())?;
        cmd.stdout(Stdio::from(log_out))
            .stderr(Stdio::from(log_err))
            .spawn()
            .map_err(|e| e.to_string())?;
        Ok(())
    }

    fn track_health_events(&mut self, prev: &StreamStats) {
        if !prev.source.is_empty() && self.stats.source != prev.source {
            self.add_event(
                EventLevel::Info,
                format!(
                    "stats source changed: {} -> {}",
                    prev.source, self.stats.source
                ),
            );
        }

        if !prev.seq.is_empty() && !self.stats.seq.is_empty() && self.stats.seq == prev.seq {
            self.add_event(
                EventLevel::Warn,
                format!("stream seq not moving (seq={})", self.stats.seq),
            );
        }

        if let Ok(sender_fps) = self.stats.sender_fps.parse::<f32>() {
            if sender_fps < 15.0 {
                self.add_event(
                    EventLevel::Warn,
                    format!("low sender fps detected ({sender_fps:.1})"),
                );
            }
        }

        if let Ok(timeout_misses) = self.stats.timeout_misses.parse::<u32>() {
            if timeout_misses >= 45 {
                self.add_event(
                    EventLevel::Warn,
                    format!("high timeout_misses detected ({timeout_misses})"),
                );
            }
        }
    }

    fn refresh_mode_line(&mut self, reason: &str, force_log: bool) {
        let next = self.compute_mode_line();
        let changed = next != self.current_mode_line;
        self.current_mode_line = next.clone();

        if force_log || changed {
            let msg = format!("{reason}: {next}");
            self.add_event(EventLevel::Info, msg.clone());
            println!("[wbeam-desktop] {msg}");
        }
    }

    fn compute_mode_line(&self) -> String {
        let cfg = read_json(&self.config_path).unwrap_or_else(|_| json!({}));
        build_mode_line_from_cfg(&cfg, &self.backend_value, &self.fps_value)
    }
}

impl eframe::App for DesktopApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        self.drain_background_events();
        if Instant::now() >= self.next_refresh_at {
            self.refresh_data();
        }
        ctx.request_repaint_after(Duration::from_millis(300));

        let splash_active = self.launched_at.elapsed() < Duration::from_millis(1800);
        let status_color = status_color(&self.status_line);

        self.render_toolbar(ctx, status_color);
        if self.ui_mode == UiMode::Advanced {
            self.render_status_monitor(ctx);
        }
        self.render_main_form(ctx);
        if splash_active {
            self.render_splash(ctx);
            ctx.request_repaint_after(Duration::from_millis(16));
        }
    }
}

fn which(bin: &str) -> Option<PathBuf> {
    let paths = std::env::var_os("PATH")?;
    std::env::split_paths(&paths)
        .map(|p| p.join(bin))
        .find(|candidate| candidate.exists())
}

fn status_color(status_line: &str) -> Color32 {
    let status_lower = status_line.to_ascii_lowercase();
    if status_lower.contains("failed")
        || status_lower.contains("invalid")
        || status_lower.contains("error")
    {
        Color32::from_rgb(255, 120, 120)
    } else if status_lower.contains("warn") {
        Color32::YELLOW
    } else {
        Color32::LIGHT_GREEN
    }
}

fn build_mode_line_from_cfg(cfg: &Value, fallback_backend: &str, fallback_fps: &str) -> String {
    let backend_raw = json_value_as_string(cfg.get("PROTO_CAPTURE_BACKEND"))
        .filter(|v| !v.trim().is_empty())
        .unwrap_or_else(|| fallback_backend.to_string());
    let backend = backend_label(&backend_raw);

    let resolution = json_value_as_string(cfg.get("PROTO_CAPTURE_SIZE"))
        .filter(|v| !v.trim().is_empty())
        .unwrap_or_else(|| "native".to_string());

    let fps = json_value_as_string(cfg.get("PROTO_CAPTURE_FPS"))
        .filter(|v| !v.trim().is_empty())
        .unwrap_or_else(|| fallback_fps.to_string());

    let profile = json_value_as_string(cfg.get("PROTO_PROFILE"))
        .filter(|v| !v.trim().is_empty())
        .unwrap_or_else(|| "manual".to_string());

    format!("{backend} [{resolution}@{fps}] {profile}")
}

fn json_value_as_string(value: Option<&Value>) -> Option<String> {
    let value = value?;
    match value {
        Value::String(v) => Some(v.clone()),
        Value::Number(v) => Some(v.to_string()),
        Value::Bool(v) => Some(if *v { "1".to_string() } else { "0".to_string() }),
        _ => None,
    }
}

fn backend_label(raw: &str) -> String {
    match raw.trim().to_ascii_lowercase().as_str() {
        "kms_drm" => "KMS".to_string(),
        "portal" => "PORTAL".to_string(),
        "grim" => "GRIM".to_string(),
        "spectacle" => "SPECTACLE".to_string(),
        "import" => "IMPORT".to_string(),
        "auto" => "AUTO".to_string(),
        other => other.to_ascii_uppercase(),
    }
}
