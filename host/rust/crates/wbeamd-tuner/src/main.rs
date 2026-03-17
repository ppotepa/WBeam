use std::collections::VecDeque;
use std::fs;
use std::io::{self, Stdout};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result};
use clap::Parser;
use crossterm::event::{self, Event, KeyCode, KeyEventKind};
use crossterm::execute;
use crossterm::terminal::{
    disable_raw_mode, enable_raw_mode, EnterAlternateScreen, LeaveAlternateScreen,
};
use rand::rngs::StdRng;
use rand::{Rng, SeedableRng};
use ratatui::backend::CrosstermBackend;
use ratatui::layout::{Constraint, Direction, Layout};
use ratatui::style::{Color, Modifier, Style};
use ratatui::text::{Line, Span};
use ratatui::widgets::{Block, Borders, Cell, Paragraph, Row, Table, Wrap};
use ratatui::Terminal;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

#[derive(Debug, Parser)]
#[command(
    name = "wbeam-tuner",
    about = "Interactive tuner wizard for WBeam codecs"
)]
struct Args {
    #[arg(long, default_value = "http://127.0.0.1:5001")]
    host_url: String,
    #[arg(long)]
    serial: Option<String>,
    #[arg(long)]
    stream_port: Option<u16>,
    #[arg(long, default_value_t = 10)]
    generations: u32,
    #[arg(long, default_value_t = 10)]
    children: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Step {
    Backend,
    Target,
    Probe,
    RunConfig,
    Evolution,
    Results,
    Finish,
}

impl Step {
    fn title(self) -> &'static str {
        match self {
            Step::Backend => "1/7 Backend",
            Step::Target => "2/7 Target",
            Step::Probe => "3/7 Probe & Guardrails",
            Step::RunConfig => "4/7 Run Config",
            Step::Evolution => "5/7 Evolution",
            Step::Results => "6/7 Results",
            Step::Finish => "7/7 Finish",
        }
    }

    fn all() -> &'static [Step] {
        &[
            Step::Backend,
            Step::Target,
            Step::Probe,
            Step::RunConfig,
            Step::Evolution,
            Step::Results,
            Step::Finish,
        ]
    }

    fn next(self) -> Self {
        match self {
            Step::Backend => Step::Target,
            Step::Target => Step::Probe,
            Step::Probe => Step::RunConfig,
            Step::RunConfig => Step::Evolution,
            Step::Evolution => Step::Results,
            Step::Results => Step::Finish,
            Step::Finish => Step::Finish,
        }
    }

    fn prev(self) -> Self {
        match self {
            Step::Backend => Step::Backend,
            Step::Target => Step::Backend,
            Step::Probe => Step::Target,
            Step::RunConfig => Step::Probe,
            Step::Evolution => Step::RunConfig,
            Step::Results => Step::Evolution,
            Step::Finish => Step::Results,
        }
    }
}

const WORKLOAD_DESKTOP_TEXT: &str = "desktop/text";
const WORKLOAD_GAME_BENCHMARK: &str = "motion/game:synthetic-120-12s";
const WORKLOAD_MIXED: &str = "mixed";
const CHILD_TRAIN_TIME_DEFAULT_SEC: u64 = 5;
const CHILD_TRAIN_TIME_MIN_SEC: u64 = 1;
const CHILD_TRAIN_TIME_MAX_SEC: u64 = 120;
const SAMPLE_INTERVAL_MS: u64 = 300;

fn nudge_index(index: &mut usize, len: usize, delta: i32) {
    if len == 0 || delta == 0 {
        return;
    }
    if delta < 0 {
        *index = index.saturating_sub((-delta) as usize);
    } else {
        *index = (*index + delta as usize).min(len - 1);
    }
}

fn nudge_u32(value: &mut u32, min: u32, max: u32, delta: i32) {
    if delta == 0 {
        return;
    }
    if delta < 0 {
        *value = value.saturating_sub((-delta) as u32).max(min);
    } else {
        *value = value.saturating_add(delta as u32).min(max);
    }
}

fn nudge_u64(value: &mut u64, min: u64, max: u64, delta: i64) {
    if delta == 0 {
        return;
    }
    if delta < 0 {
        *value = value.saturating_sub((-delta) as u64).max(min);
    } else {
        *value = value.saturating_add(delta as u64).min(max);
    }
}

#[derive(Debug, Clone)]
struct ApiClient {
    base_url: String,
    serial: Option<String>,
    stream_port: Option<u16>,
    client: reqwest::blocking::Client,
}

impl ApiClient {
    fn new(base_url: String, serial: Option<String>, stream_port: Option<u16>) -> Result<Self> {
        let client = reqwest::blocking::Client::builder()
            .timeout(Duration::from_secs(4))
            .build()
            .context("failed to build HTTP client")?;
        Ok(Self {
            base_url: base_url.trim_end_matches('/').to_string(),
            serial,
            stream_port,
            client,
        })
    }

    fn endpoint(&self, path: &str) -> String {
        let clean = path
            .trim_start_matches('/')
            .trim_start_matches("v1/")
            .to_string();
        let mut url = format!("{}/v1/{}", self.base_url, clean);
        if let Some(serial) = self.serial.as_deref() {
            if !serial.trim().is_empty() {
                let sep = if url.contains('?') { '&' } else { '?' };
                url.push(sep);
                url.push_str("serial=");
                url.push_str(serial.trim());
            }
        }
        if let Some(stream_port) = self.stream_port.filter(|p| *p > 0) {
            let sep = if url.contains('?') { '&' } else { '?' };
            url.push(sep);
            url.push_str("stream_port=");
            url.push_str(&stream_port.to_string());
        }
        url
    }

    fn get_json(&self, path: &str) -> Result<Value> {
        let url = self.endpoint(path);
        let resp = self
            .client
            .get(url)
            .send()
            .and_then(|r| r.error_for_status())
            .context("GET request failed")?;
        resp.json::<Value>().context("invalid JSON response")
    }

    fn post_json(&self, path: &str, body: &Value) -> Result<Value> {
        let url = self.endpoint(path);
        let resp = self
            .client
            .post(url)
            .json(body)
            .send()
            .and_then(|r| r.error_for_status())
            .context("POST request failed")?;
        resp.json::<Value>().context("invalid JSON response")
    }
}

#[derive(Debug, Clone, Serialize)]
struct CandidateParams {
    bitrate_kbps: u32,
    fps: u32,
    intra_only: bool,
}

#[derive(Debug, Clone, Serialize)]
struct CandidateResult {
    generation: u32,
    child: u32,
    params: CandidateParams,
    score: f64,
    fail: bool,
    reason: String,
}

#[derive(Debug, Clone)]
struct EvolutionConfig {
    codec: String,
    backend: String,
    generations: u32,
    children: u32,
    objective: String,
    workload: String,
    use_prerendered_scenes: bool,
    child_train_time_sec: u64,
    seed: CandidateParams,
}

#[derive(Debug, Clone)]
struct MetricSample {
    state: String,
    target_fps: f64,
    recv_fps: f64,
    present_fps: f64,
    decode_p95: f64,
    render_p95: f64,
    e2e_p95: f64,
    dropped_frames: f64,
    too_late_frames: f64,
    q_transport: f64,
    q_decode: f64,
    q_render: f64,
    recv_bps: f64,
}

#[derive(Debug, Clone, Default)]
struct ConnectionSnapshot {
    state: String,
    run_id: u64,
    target_serial: String,
    stream_port: u16,
    target_fps: f64,
    recv_fps: f64,
    decode_fps: f64,
    present_fps: f64,
    recv_bps: f64,
    restart_count: u64,
    reconnects: u64,
    last_error: String,
}

#[derive(Debug)]
enum EngineEvent {
    Log(String),
    Connection(ConnectionSnapshot),
    Progress {
        generation: u32,
        child: u32,
        params: CandidateParams,
    },
    Candidate(CandidateResult),
    Completed {
        best: Option<CandidateResult>,
    },
    Failed(String),
}

#[derive(Debug)]
struct EvolutionHandle {
    rx: Receiver<EngineEvent>,
    stop: Arc<AtomicBool>,
    pause: Arc<AtomicBool>,
    join: Option<thread::JoinHandle<()>>,
}

impl EvolutionHandle {
    fn stop_and_join(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        if let Some(join) = self.join.take() {
            let _ = join.join();
        }
    }
}

#[derive(Debug, Clone)]
struct ProbeState {
    ok_health: bool,
    ok_status: bool,
    ok_metrics: bool,
    host_state: String,
    host_error: String,
    build_revision: String,
    target_serial: String,
    run_id: u64,
    stream_port: u16,
}

impl Default for ProbeState {
    fn default() -> Self {
        Self {
            ok_health: false,
            ok_status: false,
            ok_metrics: false,
            host_state: "-".to_string(),
            host_error: "-".to_string(),
            build_revision: "-".to_string(),
            target_serial: "-".to_string(),
            run_id: 0,
            stream_port: 0,
        }
    }
}

#[derive(Debug, Serialize)]
struct ExportPayload {
    codec: String,
    objective: String,
    workload: String,
    generations: u32,
    children: u32,
    best: Option<CandidateResult>,
    results: Vec<CandidateResult>,
    exported_unix_ms: u64,
}

const TRAINED_PROFILES_VERSION: u32 = 1;

#[derive(Debug, Clone, Serialize, Deserialize)]
struct StoredTrainedProfile {
    key: String,
    name: String,
    #[serde(default = "default_profile_backend")]
    backend: String,
    codec: String,
    objective: String,
    workload: String,
    encoder: String,
    bitrate_kbps: u32,
    fps: u32,
    intra_only: bool,
    created_unix_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct StoredTrainedProfiles {
    #[serde(default = "trained_profiles_version")]
    version: u32,
    #[serde(default)]
    profiles: Vec<StoredTrainedProfile>,
}

fn trained_profiles_version() -> u32 {
    TRAINED_PROFILES_VERSION
}

fn default_profile_backend() -> String {
    "wayland_portal".to_string()
}

impl Default for StoredTrainedProfiles {
    fn default() -> Self {
        Self {
            version: TRAINED_PROFILES_VERSION,
            profiles: Vec::new(),
        }
    }
}

#[derive(Debug, Clone)]
struct RunMetadata {
    profile_name: String,
    backend: String,
    codec: String,
    objective: String,
    workload: String,
    display_mode: String,
    source_label: String,
    child_train_time_sec: u64,
}

#[derive(Debug)]
struct App {
    api: ApiClient,
    step: Step,
    backends: Vec<&'static str>,
    selected_backend: usize,
    backend_focus: usize,
    codecs: Vec<&'static str>,
    objectives: Vec<&'static str>,
    workloads: Vec<&'static str>,
    selected_codec: usize,
    selected_objective: usize,
    selected_workload: usize,
    use_prerendered_scenes: bool,
    child_train_time_sec: u64,
    target_focus: usize,
    probe_focus: usize,
    run_focus: usize,
    evolution_focus: usize,
    results_focus: usize,
    generations: u32,
    children: u32,
    probe: ProbeState,
    probe_ran: bool,
    logs: VecDeque<String>,
    run_log_lines: Vec<String>,
    engine: Option<EvolutionHandle>,
    evolution_running: bool,
    evolution_done: bool,
    current_generation: u32,
    current_child: u32,
    current_params: Option<CandidateParams>,
    connection: Option<ConnectionSnapshot>,
    spinner_phase: usize,
    results: Vec<CandidateResult>,
    best: Option<CandidateResult>,
    winner_applied: bool,
    exported_file: Option<String>,
    profile_name_input: String,
    current_run: Option<RunMetadata>,
    last_saved_profile: Option<String>,
}

impl App {
    fn new(args: Args) -> Result<Self> {
        Ok(Self {
            api: ApiClient::new(args.host_url, args.serial, args.stream_port)?,
            step: Step::Backend,
            backends: vec!["wayland_portal", "evdi"],
            selected_backend: 0,
            backend_focus: 0,
            codecs: vec!["h264", "h265", "rawpng", "mjpeg"],
            objectives: vec!["balanced", "latency-first", "quality-first"],
            workloads: vec![
                WORKLOAD_DESKTOP_TEXT,
                WORKLOAD_GAME_BENCHMARK,
                WORKLOAD_MIXED,
            ],
            selected_codec: 0,
            selected_objective: 0,
            selected_workload: 1,
            use_prerendered_scenes: true,
            child_train_time_sec: CHILD_TRAIN_TIME_DEFAULT_SEC,
            target_focus: 0,
            probe_focus: 0,
            run_focus: 0,
            evolution_focus: 0,
            results_focus: 0,
            generations: args.generations.clamp(1, 30),
            children: args.children.clamp(1, 30),
            probe: ProbeState::default(),
            probe_ran: false,
            logs: VecDeque::new(),
            run_log_lines: Vec::new(),
            engine: None,
            evolution_running: false,
            evolution_done: false,
            current_generation: 0,
            current_child: 0,
            current_params: None,
            connection: None,
            spinner_phase: 0,
            results: Vec::new(),
            best: None,
            winner_applied: false,
            exported_file: None,
            profile_name_input: String::new(),
            current_run: None,
            last_saved_profile: None,
        })
    }

    fn log(&mut self, msg: impl Into<String>) {
        let line = msg.into();
        while self.logs.len() > 120 {
            self.logs.pop_front();
        }
        self.logs.push_back(line.clone());
        self.run_log_lines.push(line);
    }

    fn selected_codec_name(&self) -> &'static str {
        self.codecs[self.selected_codec]
    }

    fn selected_backend_name(&self) -> &'static str {
        self.backends[self.selected_backend]
    }

    fn selected_objective_name(&self) -> &'static str {
        self.objectives[self.selected_objective]
    }

    fn selected_workload_name(&self) -> &'static str {
        self.workloads[self.selected_workload]
    }

    fn source_label(&self) -> &'static str {
        if self.use_prerendered_scenes {
            "prerendered_scenes"
        } else {
            "virtual_desktop"
        }
    }

    fn training_display_mode(&self) -> &'static str {
        if self.use_prerendered_scenes {
            "benchmark_game"
        } else {
            "virtual_monitor"
        }
    }

    fn set_use_prerendered_scenes(&mut self, enabled: bool) {
        self.use_prerendered_scenes = enabled;
    }

    fn toggle_use_prerendered_scenes(&mut self) {
        let next = !self.use_prerendered_scenes;
        self.set_use_prerendered_scenes(next);
    }

    fn adjust_run_timing_value(&mut self, delta: i32) {
        match self.run_focus {
            3 => {
                nudge_u64(
                    &mut self.child_train_time_sec,
                    CHILD_TRAIN_TIME_MIN_SEC,
                    CHILD_TRAIN_TIME_MAX_SEC,
                    i64::from(delta),
                );
            }
            _ => {}
        }
    }

    fn normalized_profile_name(&self) -> Option<String> {
        let sanitized = sanitize_profile_display_name(&self.profile_name_input);
        if sanitized.is_empty() {
            None
        } else {
            Some(sanitized)
        }
    }

    fn edit_profile_name(&mut self, code: KeyCode) {
        const MAX_PROFILE_NAME_LEN: usize = 48;
        match code {
            KeyCode::Backspace => {
                self.profile_name_input.pop();
            }
            KeyCode::Char(ch) => {
                if ch.is_ascii_alphanumeric() || matches!(ch, ' ' | '-' | '_' | '.') {
                    if self.profile_name_input.len() < MAX_PROFILE_NAME_LEN {
                        self.profile_name_input.push(ch);
                    }
                }
            }
            _ => {}
        }
    }

    fn persist_best_profile(&mut self) {
        let Some(best) = self.best.clone() else {
            return;
        };
        let Some(run) = self.current_run.clone() else {
            self.log("profile save skipped: missing run metadata");
            return;
        };
        match append_trained_profile(&run, &best.params) {
            Ok(saved) => {
                self.last_saved_profile = Some(saved.name.clone());
                self.log(format!(
                    "profile saved: {} (key={} codec={} bitrate={} fps={} intra={})",
                    saved.name,
                    saved.key,
                    saved.codec,
                    saved.bitrate_kbps,
                    saved.fps,
                    saved.intra_only
                ));
            }
            Err(err) => {
                self.log(format!("profile save failed: {err}"));
            }
        }
    }

    fn seed_params(&self) -> CandidateParams {
        match self.selected_codec_name() {
            "h264" => CandidateParams {
                bitrate_kbps: 12_000,
                fps: 60,
                intra_only: false,
            },
            "h265" => CandidateParams {
                bitrate_kbps: 20_000,
                fps: 60,
                intra_only: false,
            },
            "rawpng" => CandidateParams {
                bitrate_kbps: 12_000,
                fps: 20,
                intra_only: false,
            },
            "mjpeg" => CandidateParams {
                bitrate_kbps: 12_000,
                fps: 30,
                intra_only: false,
            },
            _ => CandidateParams {
                bitrate_kbps: 12_000,
                fps: 60,
                intra_only: false,
            },
        }
    }

    // NOSONAR S3776 - Probe flow combines host capability and status reconciliation
    fn run_probe(&mut self) {
        self.log("probe: checking /v1/health /v1/status /v1/metrics /v1/host-probe");
        self.probe = ProbeState::default();
        self.probe.ok_health = self
            .api
            .get_json("health")
            .map(|health| {
                self.probe.build_revision = health
                    .get("build_revision")
                    .and_then(Value::as_str)
                    .unwrap_or("-")
                    .to_string();
            })
            .is_ok();

        // Refresh backend list from daemon capability probe so the TUI always
        // reflects what the host can actually do, not a hardcoded fallback.
        if let Ok(hp) = self.api.get_json("host-probe") {
            if let Some(backends) = hp.get("available_backends").and_then(|v| v.as_array()) {
                let live: Vec<&'static str> = backends
                    .iter()
                    .filter_map(|b| b.as_str())
                    .filter_map(|b| match b {
                        "wayland_portal" => Some("wayland_portal"),
                        "evdi" => Some("evdi"),
                        _ => None,
                    })
                    .collect();
                if !live.is_empty() {
                    self.backends = live;
                    // Clamp selection index in case the new list is shorter.
                    if self.selected_backend >= self.backends.len() {
                        self.selected_backend = 0;
                    }
                    self.log(format!("probe: available backends = {:?}", self.backends));
                }
            }
        }

        let mut status_snapshot: Option<ConnectionSnapshot> = None;
        self.probe.ok_status = self
            .api
            .get_json("status")
            .map(|status| {
                self.probe.host_state = status
                    .get("state")
                    .and_then(Value::as_str)
                    .unwrap_or("-")
                    .to_string();
                self.probe.host_error = status
                    .get("last_error")
                    .and_then(Value::as_str)
                    .unwrap_or("-")
                    .to_string();
                self.probe.target_serial = status
                    .get("target_serial")
                    .and_then(Value::as_str)
                    .unwrap_or("-")
                    .to_string();
                self.probe.run_id = status.get("run_id").and_then(Value::as_u64).unwrap_or(0);
                self.probe.stream_port = status
                    .get("stream_port")
                    .and_then(Value::as_u64)
                    .map(|v| v as u16)
                    .unwrap_or(0);
                status_snapshot = Some(parse_connection_snapshot(&status));
            })
            .is_ok();

        self.probe.ok_metrics = self
            .api
            .get_json("metrics")
            .map(|metrics| {
                self.connection = Some(parse_connection_snapshot(&metrics));
            })
            .is_ok();

        if !self.probe.ok_metrics {
            self.connection = status_snapshot;
        }
        self.probe_ran = true;
        if let Some(expected_serial) = self.api.serial.as_deref() {
            if self.probe.target_serial != "-" && self.probe.target_serial != expected_serial {
                self.log(format!(
                    "probe: WARN session serial mismatch expected={} got={}",
                    expected_serial, self.probe.target_serial
                ));
            }
        }
        if self.probe.ok_health && self.probe.ok_status && self.probe.ok_metrics {
            self.log("probe: OK");
        } else {
            self.log("probe: failed (host unavailable or endpoint error)");
        }
    }

    fn start_evolution(&mut self) {
        if self.evolution_running {
            return;
        }
        self.results.clear();
        self.best = None;
        self.current_generation = 0;
        self.current_child = 0;
        self.current_params = None;
        self.evolution_done = false;
        self.winner_applied = false;
        self.last_saved_profile = None;
        self.run_log_lines.clear();

        let cfg = EvolutionConfig {
            codec: self.selected_codec_name().to_string(),
            backend: self.selected_backend_name().to_string(),
            generations: self.generations,
            children: self.children,
            objective: self.selected_objective_name().to_string(),
            workload: self.selected_workload_name().to_string(),
            use_prerendered_scenes: self.use_prerendered_scenes,
            child_train_time_sec: self.child_train_time_sec,
            seed: self.seed_params(),
        };

        let (tx, rx) = mpsc::channel::<EngineEvent>();
        let stop = Arc::new(AtomicBool::new(false));
        let pause = Arc::new(AtomicBool::new(false));
        let stop_clone = stop.clone();
        let pause_clone = pause.clone();
        let api = self.api.clone();

        let join =
            thread::spawn(move || run_evolution_worker(api, cfg, tx, stop_clone, pause_clone));
        self.engine = Some(EvolutionHandle {
            rx,
            stop,
            pause,
            join: Some(join),
        });
        self.evolution_running = true;
        self.log("evolution: started");
    }

    fn apply_best(&mut self) {
        let Some(best) = self.best.clone() else {
            self.log("apply-best: no winner");
            return;
        };
        let encoder = map_codec_to_encoder(self.selected_codec_name());
        let body = json!({
            "encoder": encoder,
            "bitrate_kbps": best.params.bitrate_kbps,
            "fps": best.params.fps,
            "intra_only": best.params.intra_only
        });
        match self.api.post_json("apply", &body) {
            Ok(_) => {
                self.winner_applied = true;
                self.log(format!(
                    "apply-best: encoder={} bitrate={} fps={} intra={}",
                    encoder, best.params.bitrate_kbps, best.params.fps, best.params.intra_only
                ));
            }
            Err(err) => {
                self.log(format!("apply-best: failed: {err}"));
            }
        }
    }

    fn rollback_seed(&mut self) {
        let seed = self.seed_params();
        let encoder = map_codec_to_encoder(self.selected_codec_name());
        let body = json!({
            "encoder": encoder,
            "bitrate_kbps": seed.bitrate_kbps,
            "fps": seed.fps,
            "intra_only": seed.intra_only
        });
        match self.api.post_json("apply", &body) {
            Ok(_) => self.log("rollback: seed applied"),
            Err(err) => self.log(format!("rollback failed: {err}")),
        }
    }

    fn export_results(&mut self) {
        if self.results.is_empty() {
            self.log("export: no results");
            return;
        }
        let payload = ExportPayload {
            codec: self.selected_codec_name().to_string(),
            objective: self.selected_objective_name().to_string(),
            workload: self.selected_workload_name().to_string(),
            generations: self.generations,
            children: self.children,
            best: self.best.clone(),
            results: self.results.clone(),
            exported_unix_ms: now_unix_ms(),
        };
        let mut out_dir = PathBuf::from("logs");
        if fs::create_dir_all(&out_dir).is_err() {
            out_dir = std::env::temp_dir();
        }
        let file = out_dir.join(format!("tuner-{}.json", now_unix_ms()));
        match serde_json::to_string_pretty(&payload)
            .ok()
            .and_then(|content| fs::write(&file, content).ok())
        {
            Some(_) => {
                self.exported_file = Some(file.display().to_string());
                self.log(format!("export: {}", file.display()));
            }
            None => self.log("export failed"),
        }
    }

    fn autosave_run_artifacts(&mut self) {
        let ts = now_unix_ms();
        let mut out_dir = PathBuf::from("logs");
        if fs::create_dir_all(&out_dir).is_err() {
            out_dir = std::env::temp_dir();
        }

        // JSON — full results export (same payload as manual 'e')
        let payload = ExportPayload {
            codec: self.selected_codec_name().to_string(),
            objective: self.selected_objective_name().to_string(),
            workload: self.selected_workload_name().to_string(),
            generations: self.generations,
            children: self.children,
            best: self.best.clone(),
            results: self.results.clone(),
            exported_unix_ms: ts,
        };
        let json_file = out_dir.join(format!("tuner-{ts}.json"));
        match serde_json::to_string_pretty(&payload)
            .ok()
            .and_then(|c| fs::write(&json_file, c).ok())
        {
            Some(_) => {
                self.exported_file = Some(json_file.display().to_string());
                self.log(format!("autosave: {}", json_file.display()));
            }
            None => self.log("autosave: JSON write failed"),
        }

        // TXT — full run log (all lines, no 120-line cap)
        let txt_file = out_dir.join(format!("tuner-{ts}.txt"));
        let log_content = self.run_log_lines.join("\n");
        match fs::write(&txt_file, log_content) {
            Ok(_) => self.log(format!("autosave: {}", txt_file.display())),
            Err(_) => self.log("autosave: TXT write failed"),
        }
    }

    fn clear_remote_tuning(&mut self) {
        let _ = self.api.post_json("tuning", &json!({"clear": true}));
    }

    fn stop_engine(&mut self) {
        if let Some(mut engine) = self.engine.take() {
            engine.stop_and_join();
            self.evolution_running = false;
            self.log("evolution: stopped");
        }
    }

    fn adjust_target_value(&mut self, delta: i32) {
        match self.target_focus {
            0 => nudge_index(&mut self.selected_codec, self.codecs.len(), delta),
            1 => nudge_u32(&mut self.generations, 1, 30, delta),
            2 => nudge_u32(&mut self.children, 1, 30, delta),
            _ => {}
        }
    }

    fn adjust_run_value(&mut self, delta: i32) {
        match self.run_focus {
            0 => nudge_index(&mut self.selected_objective, self.objectives.len(), delta),
            1 => nudge_index(&mut self.selected_workload, self.workloads.len(), delta),
            _ => {}
        }
    }

    fn toggle_pause(&mut self) {
        if let Some(engine) = self.engine.as_ref() {
            let paused = engine.pause.load(Ordering::SeqCst);
            engine.pause.store(!paused, Ordering::SeqCst);
            self.log(if paused {
                "evolution: resumed"
            } else {
                "evolution: paused"
            });
        } else {
            self.log("evolution: not started");
        }
    }

    fn activate_target(&mut self) {
        match self.target_focus {
            0..=2 => self.adjust_target_value(1),
            3 => {
                self.step = Step::Probe;
                if !self.probe_ran {
                    self.run_probe();
                }
            }
            _ => {}
        }
    }

    fn activate_probe(&mut self) {
        match self.probe_focus {
            0 => self.run_probe(),
            1 => self.step = Step::RunConfig,
            _ => {}
        }
    }

    fn activate_run_config(&mut self) {
        match self.run_focus {
            0 | 1 => self.adjust_run_value(1),
            2 => {
                self.toggle_use_prerendered_scenes();
            }
            3 => self.adjust_run_timing_value(1),
            4 => {}
            5 => {
                let Some(profile_name) = self.normalized_profile_name() else {
                    self.log("run config: profile name is required");
                    return;
                };
                self.profile_name_input = profile_name.clone();
                self.current_run = Some(RunMetadata {
                    profile_name,
                    backend: self.selected_backend_name().to_string(),
                    codec: self.selected_codec_name().to_string(),
                    objective: self.selected_objective_name().to_string(),
                    workload: self.selected_workload_name().to_string(),
                    display_mode: self.training_display_mode().to_string(),
                    source_label: self.source_label().to_string(),
                    child_train_time_sec: self.child_train_time_sec,
                });
                self.start_evolution();
                self.evolution_focus = 0;
                self.step = Step::Evolution;
            }
            _ => {}
        }
    }

    fn activate_evolution(&mut self) {
        match self.evolution_focus {
            0 => self.toggle_pause(),
            1 => self.rollback_seed(),
            2 => self.stop_engine(),
            3 => {
                if self.evolution_done {
                    self.step = Step::Results;
                } else {
                    self.log("results not ready (wait for done or stop evolution)");
                }
            }
            _ => {}
        }
    }

    fn activate_results(&mut self) {
        match self.results_focus {
            0 => self.apply_best(),
            1 => self.export_results(),
            2 => self.step = Step::Finish,
            _ => {}
        }
    }

    // NOSONAR S3776 - Keymap dispatch intentionally maps many step-specific controls
    fn handle_key(&mut self, code: KeyCode) -> bool {
        match code {
            KeyCode::Char('q') => return true,
            KeyCode::Tab => {
                self.step = self.step.next();
                return false;
            }
            KeyCode::BackTab | KeyCode::Esc => {
                self.step = self.step.prev();
                return false;
            }
            _ => {}
        }

        match self.step {
            Step::Backend => match code {
                KeyCode::Up => {
                    if self.backend_focus > 0 {
                        self.backend_focus -= 1;
                    }
                }
                KeyCode::Down => {
                    if self.backend_focus + 1 < self.backends.len() {
                        self.backend_focus += 1;
                    }
                }
                KeyCode::Enter => {
                    self.selected_backend = self.backend_focus;
                    self.step = Step::Target;
                }
                _ => {}
            },
            Step::Target => match code {
                KeyCode::Up => nudge_index(&mut self.target_focus, 4, -1),
                KeyCode::Down => nudge_index(&mut self.target_focus, 4, 1),
                KeyCode::Left => self.adjust_target_value(-1),
                KeyCode::Right => self.adjust_target_value(1),
                KeyCode::Char('g') => nudge_u32(&mut self.generations, 1, 30, 1),
                KeyCode::Char('G') => nudge_u32(&mut self.generations, 1, 30, -1),
                KeyCode::Char('c') => nudge_u32(&mut self.children, 1, 30, 1),
                KeyCode::Char('C') => nudge_u32(&mut self.children, 1, 30, -1),
                KeyCode::Char('+') | KeyCode::Char('=') => self.adjust_target_value(1),
                KeyCode::Char('-') => self.adjust_target_value(-1),
                KeyCode::Enter => self.activate_target(),
                _ => {}
            },
            Step::Probe => match code {
                KeyCode::Up => nudge_index(&mut self.probe_focus, 2, -1),
                KeyCode::Down => nudge_index(&mut self.probe_focus, 2, 1),
                KeyCode::Char('r') => self.run_probe(),
                KeyCode::Enter => self.activate_probe(),
                _ => {}
            },
            Step::RunConfig => match code {
                KeyCode::Up => nudge_index(&mut self.run_focus, 6, -1),
                KeyCode::Down => nudge_index(&mut self.run_focus, 6, 1),
                KeyCode::Left => {
                    if self.run_focus <= 1 {
                        self.adjust_run_value(-1);
                    } else if self.run_focus == 3 {
                        self.adjust_run_timing_value(-1);
                    }
                }
                KeyCode::Right => {
                    if self.run_focus <= 1 {
                        self.adjust_run_value(1);
                    } else if self.run_focus == 3 {
                        self.adjust_run_timing_value(1);
                    }
                }
                KeyCode::Char('+') | KeyCode::Char('=') => {
                    if self.run_focus == 3 {
                        self.adjust_run_timing_value(1);
                    }
                }
                KeyCode::Char('-') => {
                    if self.run_focus == 3 {
                        self.adjust_run_timing_value(-1);
                    }
                }
                KeyCode::Backspace => {
                    if self.run_focus == 4 {
                        self.edit_profile_name(code);
                    }
                }
                KeyCode::Char(' ') => {
                    if self.run_focus == 2 {
                        self.toggle_use_prerendered_scenes();
                    } else if self.run_focus == 4 {
                        self.edit_profile_name(code);
                    }
                }
                KeyCode::Char(_) => {
                    if self.run_focus == 4 {
                        self.edit_profile_name(code);
                    }
                }
                KeyCode::Enter => self.activate_run_config(),
                _ => {}
            },
            Step::Evolution => match code {
                KeyCode::Up => nudge_index(&mut self.evolution_focus, 4, -1),
                KeyCode::Down => nudge_index(&mut self.evolution_focus, 4, 1),
                KeyCode::Char(' ') => self.toggle_pause(),
                KeyCode::Char('r') => self.rollback_seed(),
                KeyCode::Char('s') => self.stop_engine(),
                KeyCode::Enter => self.activate_evolution(),
                _ => {}
            },
            Step::Results => match code {
                KeyCode::Up => nudge_index(&mut self.results_focus, 3, -1),
                KeyCode::Down => nudge_index(&mut self.results_focus, 3, 1),
                KeyCode::Char('a') => self.apply_best(),
                KeyCode::Char('e') => self.export_results(),
                KeyCode::Enter => self.activate_results(),
                _ => {}
            },
            Step::Finish => {
                if matches!(code, KeyCode::Enter | KeyCode::Esc) {
                    return true;
                }
            }
        }
        false
    }

    fn on_tick(&mut self) {
        self.spinner_phase = (self.spinner_phase + 1) % 4;
        loop {
            let Some(engine) = self.engine.as_ref() else {
                break;
            };
            let event = engine.rx.try_recv();
            let ev = match event {
                Ok(v) => v,
                Err(_) => break,
            };
            match ev {
                EngineEvent::Log(line) => self.log(line),
                EngineEvent::Connection(snapshot) => {
                    self.connection = Some(snapshot);
                }
                EngineEvent::Progress {
                    generation,
                    child,
                    params,
                } => {
                    self.current_generation = generation;
                    self.current_child = child;
                    self.current_params = Some(params);
                }
                EngineEvent::Candidate(res) => {
                    if self
                        .best
                        .as_ref()
                        .map(|b| res.score > b.score && !res.fail)
                        .unwrap_or(!res.fail)
                    {
                        self.best = Some(res.clone());
                    }
                    self.results.push(res);
                }
                EngineEvent::Completed { best } => {
                    self.evolution_running = false;
                    self.evolution_done = true;
                    if let Some(best) = best {
                        self.best = Some(best);
                    }
                    self.persist_best_profile();
                    self.autosave_run_artifacts();
                    self.log("evolution: completed");
                    self.results_focus = 0;
                    self.step = Step::Results;
                }
                EngineEvent::Failed(err) => {
                    self.evolution_running = false;
                    self.evolution_done = true;
                    self.log(format!("evolution failed: {err}"));
                    self.autosave_run_artifacts();
                    self.results_focus = 0;
                    self.step = Step::Results;
                }
            }
        }
    }
}

fn map_codec_to_encoder(codec: &str) -> &'static str {
    match codec {
        "h264" => "h264",
        "h265" => "h265",
        "rawpng" => "rawpng",
        "mjpeg" => "rawpng",
        _ => "h264",
    }
}

fn mutate_candidate(
    parent: &CandidateParams,
    codec: &str,
    rng: &mut StdRng,
    generation: u32,
) -> CandidateParams {
    let (min_bitrate, max_bitrate, max_fps) = match codec {
        "h264" => (4_000u32, 80_000u32, 120u32),
        "h265" => (4_000u32, 100_000u32, 120u32),
        "rawpng" | "mjpeg" => (4_000u32, 25_000u32, 30u32),
        _ => (4_000u32, 80_000u32, 120u32),
    };
    let intensity = (1.0 - (generation as f64 / 20.0)).clamp(0.30, 1.0);
    let scale = rng.gen_range((0.78 * intensity + 0.22)..(1.22 * intensity + 0.78));
    let mut bitrate = (parent.bitrate_kbps as f64 * scale).round() as u32;
    bitrate = bitrate.clamp(min_bitrate, max_bitrate);

    let fps_delta = rng.gen_range(-8i32..=8i32);
    let mut fps = parent.fps as i32 + fps_delta;
    fps = fps.clamp(24, max_fps as i32);

    let toggle_intra = rng.gen_bool(0.15);
    let intra_only = if codec == "h265" {
        if toggle_intra {
            !parent.intra_only
        } else {
            parent.intra_only
        }
    } else {
        false
    };

    CandidateParams {
        bitrate_kbps: bitrate,
        fps: fps as u32,
        intra_only,
    }
}

fn parse_connection_snapshot(payload: &Value) -> ConnectionSnapshot {
    let metrics = payload.get("metrics").cloned().unwrap_or(Value::Null);
    let kpi = metrics.get("kpi").cloned().unwrap_or(Value::Null);
    let latest = metrics
        .get("latest_client_metrics")
        .cloned()
        .unwrap_or(Value::Null);
    ConnectionSnapshot {
        state: payload
            .get("state")
            .and_then(Value::as_str)
            .unwrap_or("-")
            .to_string(),
        run_id: payload.get("run_id").and_then(Value::as_u64).unwrap_or(0),
        target_serial: payload
            .get("target_serial")
            .and_then(Value::as_str)
            .unwrap_or("-")
            .to_string(),
        stream_port: payload
            .get("stream_port")
            .and_then(Value::as_u64)
            .map(|v| v as u16)
            .unwrap_or(0),
        target_fps: kpi.get("target_fps").and_then(Value::as_f64).unwrap_or(0.0),
        recv_fps: kpi.get("recv_fps").and_then(Value::as_f64).unwrap_or(0.0),
        decode_fps: kpi.get("decode_fps").and_then(Value::as_f64).unwrap_or(0.0),
        present_fps: kpi
            .get("present_fps")
            .and_then(Value::as_f64)
            .unwrap_or(0.0),
        recv_bps: latest
            .get("recv_bps")
            .and_then(Value::as_f64)
            .unwrap_or(0.0),
        restart_count: metrics
            .get("restart_count")
            .and_then(Value::as_u64)
            .unwrap_or(0),
        reconnects: metrics
            .get("reconnects")
            .and_then(Value::as_u64)
            .unwrap_or(0),
        last_error: payload
            .get("last_error")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string(),
    }
}

fn parse_metric_sample(metrics_payload: &Value) -> Option<MetricSample> {
    let metrics = metrics_payload.get("metrics")?;
    let kpi = metrics.get("kpi").cloned().unwrap_or(Value::Null);
    let latest = metrics
        .get("latest_client_metrics")
        .cloned()
        .unwrap_or(Value::Null);
    let normalize_metric_ms = |value: f64, soft_cap_ms: f64, hard_drop_ms: f64| -> f64 {
        if !value.is_finite() || value < 0.0 {
            return 0.0;
        }
        if value > hard_drop_ms {
            // Timestamp-like or corrupt telemetry value; ignore for scoring.
            return 0.0;
        }
        value.min(soft_cap_ms)
    };
    Some(MetricSample {
        state: metrics_payload
            .get("state")
            .and_then(Value::as_str)
            .unwrap_or("-")
            .to_string(),
        target_fps: kpi
            .get("target_fps")
            .and_then(Value::as_f64)
            .unwrap_or(60.0),
        recv_fps: kpi.get("recv_fps").and_then(Value::as_f64).unwrap_or(0.0),
        present_fps: kpi
            .get("present_fps")
            .and_then(Value::as_f64)
            .unwrap_or(0.0),
        decode_p95: normalize_metric_ms(
            kpi.get("decode_time_ms_p95")
                .and_then(Value::as_f64)
                .unwrap_or(0.0),
            120.0,
            10_000.0,
        ),
        render_p95: normalize_metric_ms(
            kpi.get("render_time_ms_p95")
                .and_then(Value::as_f64)
                .unwrap_or(0.0),
            80.0,
            10_000.0,
        ),
        e2e_p95: normalize_metric_ms(
            kpi.get("e2e_latency_ms_p95")
                .and_then(Value::as_f64)
                .unwrap_or(0.0),
            500.0,
            10_000.0,
        ),
        dropped_frames: latest
            .get("dropped_frames")
            .and_then(Value::as_f64)
            .unwrap_or(0.0),
        too_late_frames: latest
            .get("too_late_frames")
            .and_then(Value::as_f64)
            .unwrap_or(0.0),
        q_transport: latest
            .get("transport_queue_depth")
            .and_then(Value::as_f64)
            .unwrap_or(0.0),
        q_decode: latest
            .get("decode_queue_depth")
            .and_then(Value::as_f64)
            .unwrap_or(0.0),
        q_render: latest
            .get("render_queue_depth")
            .and_then(Value::as_f64)
            .unwrap_or(0.0),
        recv_bps: latest
            .get("recv_bps")
            .and_then(Value::as_f64)
            .unwrap_or(0.0),
    })
}

fn score_candidate(samples: &[MetricSample]) -> (f64, bool, String) {
    if samples.is_empty() {
        return (-10_000.0, true, "no metrics samples".to_string());
    }

    let n = samples.len() as f64;
    let mean = |f: fn(&MetricSample) -> f64| samples.iter().map(f).sum::<f64>() / n;
    let max = |f: fn(&MetricSample) -> f64| {
        samples
            .iter()
            .map(f)
            .fold(f64::NEG_INFINITY, |a, b| a.max(b))
    };
    let min =
        |f: fn(&MetricSample) -> f64| samples.iter().map(f).fold(f64::INFINITY, |a, b| a.min(b));

    let target = mean(|s| s.target_fps).max(1.0);
    let recv = mean(|s| s.recv_fps).max(0.0);
    let present = mean(|s| s.present_fps).max(0.0);
    let e2e_p95 = mean(|s| s.e2e_p95).max(0.0);
    let decode_p95 = mean(|s| s.decode_p95).max(0.0);
    let render_p95 = mean(|s| s.render_p95).max(0.0);
    let q_t = max(|s| s.q_transport).max(0.0);
    let q_d = max(|s| s.q_decode).max(0.0);
    let q_r = max(|s| s.q_render).max(0.0);
    let dropped_delta = (max(|s| s.dropped_frames) - min(|s| s.dropped_frames)).max(0.0);
    let too_late_delta = (max(|s| s.too_late_frames) - min(|s| s.too_late_frames)).max(0.0);
    let recv_bps = mean(|s| s.recv_bps).max(0.0);
    let streaming_ratio = samples
        .iter()
        .filter(|s| s.state.eq_ignore_ascii_case("streaming"))
        .count() as f64
        / n;
    let e2e_valid_ratio = samples.iter().filter(|s| s.e2e_p95 > 0.0).count() as f64 / n;
    let decode_valid_ratio = samples.iter().filter(|s| s.decode_p95 > 0.0).count() as f64 / n;
    let render_valid_ratio = samples.iter().filter(|s| s.render_p95 > 0.0).count() as f64 / n;
    let telemetry_valid_ratio = samples
        .iter()
        .filter(|s| s.e2e_p95 > 0.0 && s.decode_p95 > 0.0 && s.render_p95 > 0.0)
        .count() as f64
        / n;

    if recv < 1.0 {
        let approx_mbps = (recv_bps / 1_000_000.0).max(0.0);
        if recv_bps > 150_000.0 {
            return (
                -9_150.0,
                true,
                format!(
                    "transport active (~{approx_mbps:.2} Mb/s) but decode path produced no frames (recv_fps={recv:.1})"
                ),
            );
        }
        return (
            -9_200.0,
            true,
            format!("no client frames received (recv_fps={recv:.1})"),
        );
    }
    if streaming_ratio < 0.7 {
        return (
            -9_000.0,
            true,
            format!(
                "streaming state unstable ({:.0}% streaming)",
                streaming_ratio * 100.0
            ),
        );
    }
    if present < target * 0.6 {
        return (
            -8_000.0,
            true,
            format!("present fps too low ({present:.1}/{target:.1})"),
        );
    }

    let mut score = 1000.0;
    score -= 2.0 * e2e_p95;
    score -= 1.2 * decode_p95;
    score -= 0.8 * render_p95;
    score -= 8.0 * dropped_delta;
    score -= 6.0 * too_late_delta;
    score -= 5.0 * (target - present).abs();
    score -= 3.0 * (q_t - 2.0).max(0.0);
    score -= 2.0 * (q_d - 1.0).max(0.0);
    score -= 1.5 * (q_r - 1.0).max(0.0);
    score -= 0.0000015 * recv_bps;
    if e2e_valid_ratio < 0.5 {
        score -= 180.0;
    }
    if decode_valid_ratio < 0.5 {
        score -= 120.0;
    }
    if render_valid_ratio < 0.5 {
        score -= 80.0;
    }
    if !score.is_finite() {
        return (
            -9_300.0,
            true,
            "invalid score (non-finite telemetry)".to_string(),
        );
    }
    score = score.clamp(-9_500.0, 2_000.0);
    (
        score,
        false,
        format!(
            "p95={e2e_p95:.1}ms present={present:.1}/{target:.1} q={q_t:.0}/{q_d:.0} dropΔ={dropped_delta:.1} telemetry={:.0}%",
            telemetry_valid_ratio * 100.0
        ),
    )
}

fn summarize_samples(samples: &[MetricSample]) -> String {
    if samples.is_empty() {
        return "samples=0".to_string();
    }
    let n = samples.len() as f64;
    let mean = |f: fn(&MetricSample) -> f64| samples.iter().map(f).sum::<f64>() / n;
    let streaming = samples
        .iter()
        .filter(|s| s.state.eq_ignore_ascii_case("streaming"))
        .count();
    let states = samples
        .iter()
        .map(|s| s.state.as_str())
        .collect::<Vec<_>>()
        .join(",");
    format!(
        "samples={} streaming={}/{} recv={:.1} present={:.1} target={:.1} recv≈{:.2}Mb/s states=[{}]",
        samples.len(),
        streaming,
        samples.len(),
        mean(|s| s.recv_fps),
        mean(|s| s.present_fps),
        mean(|s| s.target_fps),
        mean(|s| s.recv_bps) / 1_000_000.0,
        states
    )
}

// NOSONAR S3776 - Evolution worker orchestrates staged control/measurement loops
fn run_evolution_worker(
    api: ApiClient,
    cfg: EvolutionConfig,
    tx: Sender<EngineEvent>,
    stop: Arc<AtomicBool>,
    pause: Arc<AtomicBool>,
) {
    let mut rng = StdRng::from_entropy();
    let codec = cfg.codec.clone();
    let mut parent_pool = vec![cfg.seed.clone()];
    let mut best: Option<CandidateResult> = None;
    let encoder = map_codec_to_encoder(&codec);
    let child_train_time_sec = cfg
        .child_train_time_sec
        .clamp(CHILD_TRAIN_TIME_MIN_SEC, CHILD_TRAIN_TIME_MAX_SEC);
    let child_train_window_ms = child_train_time_sec.saturating_mul(1000);
    let display_mode = if cfg.use_prerendered_scenes {
        "benchmark_game"
    } else {
        "virtual_monitor"
    };
    let source_name = if cfg.use_prerendered_scenes {
        "prerendered_scenes"
    } else {
        "virtual_desktop"
    };

    if codec == "mjpeg" {
        let _ = tx.send(EngineEvent::Log(
            "codec=mjpeg not implemented yet on host; falling back to rawpng".to_string(),
        ));
    }
    let _ = tx.send(EngineEvent::Log(format!(
        "run: codec={} objective={} workload={} source={} display_mode={} child_train={}s sample_interval={}ms",
        cfg.codec,
        cfg.objective,
        cfg.workload,
        source_name,
        display_mode,
        child_train_time_sec,
        SAMPLE_INTERVAL_MS
    )));

    let start_body = json!({
        "encoder": encoder,
        "fps": cfg.seed.fps,
        "bitrate_kbps": cfg.seed.bitrate_kbps,
        "intra_only": cfg.seed.intra_only
    });
    if cfg.use_prerendered_scenes {
        let _ = tx.send(EngineEvent::Log(
            "start: benchmark_game source=synthetic-2d timeline=12s scenes=4".to_string(),
        ));
    } else {
        let _ = tx.send(EngineEvent::Log(
            "start: virtual_monitor source=desktop capture".to_string(),
        ));
    }
    let start_response =
        match api.post_json(&format!("start?display_mode={display_mode}&capture_backend={}", cfg.backend), &start_body) {
            Ok(v) => v,
            Err(err) => {
                let _ = tx.send(EngineEvent::Failed(format!(
                    "start failed ({display_mode}): {err}"
                )));
                return;
            }
        };
    let start_snapshot = parse_connection_snapshot(&start_response);
    let _ = tx.send(EngineEvent::Connection(start_snapshot.clone()));
    let _ = tx.send(EngineEvent::Log(format!(
        "start: state={} run={} serial={} port={} recv={:.1} present={:.1}/{:.1} restarts={} reconnects={}",
        start_snapshot.state,
        start_snapshot.run_id,
        start_snapshot.target_serial,
        start_snapshot.stream_port,
        start_snapshot.recv_fps,
        start_snapshot.present_fps,
        start_snapshot.target_fps,
        start_snapshot.restart_count,
        start_snapshot.reconnects
    )));

    let _ = api.post_json(
        "tuning",
        &json!({
            "active": true,
            "codec": cfg.codec,
            "phase": "warmup",
            "generation": 0,
            "total_generations": cfg.generations,
            "child": 0,
            "children_per_generation": cfg.children,
            "score": 0.0,
            "best_score": 0.0,
            "note": "starting run"
        }),
    );

    'gens: for generation in 1..=cfg.generations {
        let mut generation_parents = parent_pool.clone();
        if generation_parents.is_empty() {
            generation_parents.push(cfg.seed.clone());
        }
        let mut generation_results: Vec<CandidateResult> = Vec::new();
        for child in 1..=cfg.children {
            if stop.load(Ordering::SeqCst) {
                break 'gens;
            }
            while pause.load(Ordering::SeqCst) {
                if stop.load(Ordering::SeqCst) {
                    break 'gens;
                }
                thread::sleep(Duration::from_millis(150));
            }

            let parent_idx = ((child - 1) as usize) % generation_parents.len();
            let base_parent = &generation_parents[parent_idx];
            let candidate = mutate_candidate(base_parent, &codec, &mut rng, generation);
            let _ = tx.send(EngineEvent::Progress {
                generation,
                child,
                params: candidate.clone(),
            });

            let apply_body = json!({
                "encoder": encoder,
                "bitrate_kbps": candidate.bitrate_kbps,
                "fps": candidate.fps,
                "intra_only": candidate.intra_only
            });

            let apply_response = match api.post_json("apply", &apply_body) {
                Ok(v) => v,
                Err(err) => {
                    let failed = CandidateResult {
                        generation,
                        child,
                        params: candidate.clone(),
                        score: -9_500.0,
                        fail: true,
                        reason: format!("apply failed: {err}"),
                    };
                    let _ = tx.send(EngineEvent::Candidate(failed));
                    continue;
                }
            };
            let apply_state = apply_response
                .get("state")
                .and_then(Value::as_str)
                .unwrap_or("-");
            let apply_run_id = apply_response
                .get("run_id")
                .and_then(Value::as_u64)
                .unwrap_or(0);
            let apply_serial = apply_response
                .get("target_serial")
                .and_then(Value::as_str)
                .unwrap_or("<none>");
            let apply_stream_port = apply_response
                .get("stream_port")
                .and_then(Value::as_u64)
                .unwrap_or(0);
            let apply_snapshot = parse_connection_snapshot(&apply_response);
            let _ = tx.send(EngineEvent::Connection(apply_snapshot.clone()));
            let _ = tx.send(EngineEvent::Log(format!(
                "apply: g{generation}/c{child} run_id={apply_run_id} state={apply_state} serial={apply_serial} port={apply_stream_port} recv={:.1} present={:.1}/{:.1} restarts={} reconnects={} bitrate={} fps={} intra={}",
                apply_snapshot.recv_fps,
                apply_snapshot.present_fps,
                apply_snapshot.target_fps,
                apply_snapshot.restart_count,
                apply_snapshot.reconnects,
                candidate.bitrate_kbps,
                candidate.fps,
                candidate.intra_only
            )));

            let _ = api.post_json(
                "tuning",
                &json!({
                    "active": true,
                    "codec": cfg.codec,
                    "phase": "measure",
                    "generation": generation,
                    "total_generations": cfg.generations,
                    "child": child,
                    "children_per_generation": cfg.children,
                    "note": format!("candidate bitrate={} fps={} intra={}", candidate.bitrate_kbps, candidate.fps, candidate.intra_only)
                }),
            );

            let mut samples = Vec::new();
            let sample_window = Duration::from_millis(child_train_window_ms);
            let sample_interval = Duration::from_millis(SAMPLE_INTERVAL_MS);
            let sample_started_at = Instant::now();
            let mut sample_idx: u32 = 0;
            let mut next_tick = sample_started_at;
            loop {
                if stop.load(Ordering::SeqCst) {
                    break 'gens;
                }
                sample_idx = sample_idx.saturating_add(1);
                if let Ok(payload) = api.get_json("metrics") {
                    let snap = parse_connection_snapshot(&payload);
                    let _ = tx.send(EngineEvent::Connection(snap.clone()));
                    if let Some(sample) = parse_metric_sample(&payload) {
                        let elapsed_ms = sample_started_at.elapsed().as_millis() as u64;
                        let _ = tx.send(EngineEvent::Log(format!(
                            "train: g{generation}/c{child} idx={sample_idx} t={elapsed_ms}/{child_train_window_ms}ms state={} run={} recv={:.1} present={:.1}/{:.1} restarts={} reconnects={}",
                            snap.state,
                            snap.run_id,
                            sample.recv_fps,
                            sample.present_fps,
                            sample.target_fps,
                            snap.restart_count,
                            snap.reconnects
                        )));
                        samples.push(sample);
                    }
                }
                if sample_started_at.elapsed() >= sample_window {
                    break;
                }
                next_tick = next_tick
                    .checked_add(sample_interval)
                    .unwrap_or_else(Instant::now);
                let now = Instant::now();
                let sleep_for = next_tick
                    .saturating_duration_since(now)
                    .min(sample_window.saturating_sub(sample_started_at.elapsed()));
                if !sleep_for.is_zero() {
                    thread::sleep(sleep_for);
                }
            }
            let _ = tx.send(EngineEvent::Log(format!(
                "metrics: g{generation}/c{child} {}",
                summarize_samples(&samples)
            )));

            let (score, fail, reason) = score_candidate(&samples);
            let candidate_result = CandidateResult {
                generation,
                child,
                params: candidate.clone(),
                score,
                fail,
                reason,
            };

            if !candidate_result.fail
                && best
                    .as_ref()
                    .map(|current| candidate_result.score > current.score)
                    .unwrap_or(true)
            {
                best = Some(candidate_result.clone());
            }
            generation_results.push(candidate_result.clone());

            let _ = api.post_json(
                "tuning",
                &json!({
                    "active": true,
                    "codec": cfg.codec,
                    "phase": "score",
                    "generation": generation,
                    "total_generations": cfg.generations,
                    "child": child,
                    "children_per_generation": cfg.children,
                    "score": candidate_result.score,
                    "best_score": best.as_ref().map(|b| b.score).unwrap_or(candidate_result.score),
                    "note": candidate_result.reason
                }),
            );

            let _ = tx.send(EngineEvent::Candidate(candidate_result));
        }

        let mut generation_winners = generation_results
            .iter()
            .filter(|r| !r.fail)
            .cloned()
            .collect::<Vec<_>>();
        generation_winners.sort_by(|a, b| {
            b.score
                .partial_cmp(&a.score)
                .unwrap_or(std::cmp::Ordering::Equal)
        });
        generation_winners.truncate(3);
        if generation_winners.is_empty() {
            parent_pool = best
                .as_ref()
                .map(|r| vec![r.params.clone()])
                .unwrap_or_else(|| vec![cfg.seed.clone()]);
            let _ = tx.send(EngineEvent::Log(format!(
                "selection: g{generation} no valid candidates, fallback parents={}",
                parent_pool.len()
            )));
        } else {
            parent_pool = generation_winners
                .iter()
                .map(|r| r.params.clone())
                .collect::<Vec<_>>();
            let summary = generation_winners
                .iter()
                .map(|r| format!("c{}:{:.2}", r.child, r.score))
                .collect::<Vec<_>>()
                .join(", ");
            let _ = tx.send(EngineEvent::Log(format!(
                "selection: g{generation} top={} parents={} [{}]",
                generation_winners.len(),
                parent_pool.len(),
                summary
            )));
        }
    }

    let _ = api.post_json(
        "tuning",
        &json!({
            "active": false,
            "codec": cfg.codec,
            "phase": "done",
            "generation": cfg.generations,
            "total_generations": cfg.generations,
            "child": cfg.children,
            "children_per_generation": cfg.children,
            "best_score": best.as_ref().map(|b| b.score).unwrap_or(0.0),
            "note": "run finished"
        }),
    );
    let _ = tx.send(EngineEvent::Completed { best });
}

fn now_unix_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

fn sanitize_profile_display_name(raw: &str) -> String {
    raw.trim()
        .chars()
        .filter(|ch| ch.is_ascii_alphanumeric() || matches!(ch, ' ' | '-' | '_' | '.'))
        .collect::<String>()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

fn profile_key_from_name(name: &str) -> String {
    let mut key = String::with_capacity(name.len());
    let mut last_dash = false;
    for ch in name.chars() {
        let c = ch.to_ascii_lowercase();
        if c.is_ascii_alphanumeric() {
            key.push(c);
            last_dash = false;
            continue;
        }
        if !last_dash && !key.is_empty() {
            key.push('-');
            last_dash = true;
        }
    }
    while key.ends_with('-') {
        key.pop();
    }
    key
}

fn unique_profile_key(profiles: &[StoredTrainedProfile], base: &str) -> String {
    if base.is_empty() {
        return format!("profile-{}", now_unix_ms());
    }
    if !profiles.iter().any(|p| p.key == base) {
        return base.to_string();
    }
    let mut idx = 2u32;
    loop {
        let candidate = format!("{base}-{idx}");
        if !profiles.iter().any(|p| p.key == candidate) {
            return candidate;
        }
        idx = idx.saturating_add(1);
    }
}

fn user_wbeam_config_dir() -> Result<PathBuf> {
    let base = std::env::var("XDG_CONFIG_HOME")
        .ok()
        .filter(|v| !v.trim().is_empty())
        .map(PathBuf::from)
        .or_else(|| {
            std::env::var("HOME")
                .ok()
                .filter(|v| !v.trim().is_empty())
                .map(|h| PathBuf::from(h).join(".config"))
        })
        .context("cannot resolve user config directory")?;
    Ok(base.join("wbeam"))
}

fn trained_profiles_path(backend: &str) -> Result<PathBuf> {
    let dir = user_wbeam_config_dir()?.join("profiles").join(backend);
    fs::create_dir_all(&dir).context("failed to create profile dir")?;
    Ok(dir.join("trained_profiles.json"))
}

fn migrate_legacy_profiles_once() {
    let Ok(base) = user_wbeam_config_dir() else { return };
    let legacy = base.join("trained_profiles.json");
    let archive = base.join("trained_profiles.legacy.json");
    if legacy.exists() && !archive.exists() {
        let _ = fs::rename(&legacy, &archive);
    }
}

fn load_trained_profiles(path: &PathBuf) -> Result<StoredTrainedProfiles> {
    if !path.exists() {
        return Ok(StoredTrainedProfiles::default());
    }
    let raw = fs::read_to_string(path)
        .with_context(|| format!("failed to read trained profiles: {}", path.display()))?;
    let parsed = serde_json::from_str::<StoredTrainedProfiles>(&raw)
        .with_context(|| format!("failed to parse trained profiles: {}", path.display()))?;
    Ok(parsed)
}

fn save_trained_profiles(path: &PathBuf, store: &StoredTrainedProfiles) -> Result<()> {
    let json =
        serde_json::to_string_pretty(store).context("failed to serialize trained profiles")?;
    fs::write(path, json)
        .with_context(|| format!("failed to write trained profiles: {}", path.display()))?;
    Ok(())
}

fn append_trained_profile(
    run: &RunMetadata,
    params: &CandidateParams,
) -> Result<StoredTrainedProfile> {
    let path = trained_profiles_path(&run.backend)?;
    let mut store = load_trained_profiles(&path)?;
    store.version = TRAINED_PROFILES_VERSION;

    let base_key = profile_key_from_name(&run.profile_name);
    let key = unique_profile_key(&store.profiles, &base_key);
    let entry = StoredTrainedProfile {
        key,
        name: run.profile_name.clone(),
        backend: run.backend.clone(),
        codec: run.codec.clone(),
        objective: run.objective.clone(),
        workload: run.workload.clone(),
        encoder: map_codec_to_encoder(&run.codec).to_string(),
        bitrate_kbps: params.bitrate_kbps,
        fps: params.fps,
        intra_only: params.intra_only,
        created_unix_ms: now_unix_ms(),
    };
    store.profiles.push(entry.clone());
    save_trained_profiles(&path, &store)?;
    Ok(entry)
}

fn init_terminal() -> Result<Terminal<CrosstermBackend<Stdout>>> {
    enable_raw_mode().context("failed to enable raw mode")?;
    let mut stdout = io::stdout();
    execute!(stdout, EnterAlternateScreen).context("failed to enter alternate screen")?;
    let backend = CrosstermBackend::new(stdout);
    Terminal::new(backend).context("failed to init terminal")
}

fn restore_terminal(mut terminal: Terminal<CrosstermBackend<Stdout>>) -> Result<()> {
    disable_raw_mode().context("failed to disable raw mode")?;
    execute!(terminal.backend_mut(), LeaveAlternateScreen)
        .context("failed to leave alternate screen")?;
    terminal.show_cursor().context("failed to show cursor")
}

const COLOR_MOCHA_BG: Color = Color::Rgb(30, 30, 46);
const COLOR_MOCHA_SURFACE: Color = Color::Rgb(49, 50, 68);
const COLOR_TEXT: Color = Color::Rgb(205, 214, 244);
const COLOR_ORANGE: Color = Color::Rgb(250, 179, 135);
const COLOR_SUCCESS: Color = Color::Rgb(166, 227, 161);
const COLOR_DANGER: Color = Color::Rgb(243, 139, 168);

fn app_body_style() -> Style {
    Style::default().fg(COLOR_TEXT).bg(COLOR_MOCHA_BG)
}

fn app_surface_style() -> Style {
    Style::default().fg(COLOR_TEXT).bg(COLOR_MOCHA_SURFACE)
}

fn muted_style() -> Style {
    app_surface_style().add_modifier(Modifier::DIM)
}

fn pane_block(title: &str) -> Block<'_> {
    Block::default()
        .borders(Borders::ALL)
        .style(app_surface_style())
        .border_style(Style::default().fg(COLOR_ORANGE))
        .title(Span::styled(
            format!(" {title} "),
            Style::default()
                .fg(COLOR_ORANGE)
                .bg(COLOR_MOCHA_SURFACE)
                .add_modifier(Modifier::BOLD),
        ))
}

fn focus_style(active: bool) -> Style {
    if active {
        Style::default()
            .fg(COLOR_MOCHA_BG)
            .bg(COLOR_ORANGE)
            .add_modifier(Modifier::BOLD)
    } else {
        app_surface_style()
    }
}

fn action_style(active: bool) -> Style {
    if active {
        Style::default()
            .fg(COLOR_MOCHA_BG)
            .bg(COLOR_SUCCESS)
            .add_modifier(Modifier::BOLD)
    } else {
        app_surface_style()
    }
}

fn focus_line(active: bool, text: impl Into<String>) -> Line<'static> {
    let marker = if active { "▶ " } else { "  " };
    Line::from(Span::styled(
        format!("{marker}{}", text.into()),
        focus_style(active),
    ))
}

fn action_line(active: bool, text: impl Into<String>) -> Line<'static> {
    let marker = if active { "● " } else { "○ " };
    Line::from(Span::styled(
        format!("{marker}{}", text.into()),
        action_style(active),
    ))
}

fn spinner_char(phase: usize) -> &'static str {
    const FRAMES: [&str; 4] = ["|", "/", "-", "\\"];
    FRAMES[phase % FRAMES.len()]
}

// NOSONAR S3776 - Status bar state mapping is explicit for operator clarity
fn draw_status_bar(frame: &mut ratatui::Frame, area: ratatui::layout::Rect, app: &App) {
    let spin = if app.evolution_running {
        spinner_char(app.spinner_phase)
    } else {
        "."
    };
    let mut state_label = "IDLE".to_string();
    let mut state_style = muted_style();
    let mut detail = "no connection snapshot yet".to_string();
    if let Some(conn) = app.connection.as_ref() {
        let connecting = conn.state.eq_ignore_ascii_case("starting")
            || conn.state.eq_ignore_ascii_case("reconnecting");
        let connected = conn.state.eq_ignore_ascii_case("streaming") && conn.recv_fps > 1.0;
        let bytes_no_decode = conn.state.eq_ignore_ascii_case("streaming")
            && conn.recv_fps <= 1.0
            && conn.recv_bps > 150_000.0;
        state_label = if connected {
            "CONNECTED".to_string()
        } else if bytes_no_decode {
            "BYTES-NO-DECODE".to_string()
        } else if connecting {
            "CONNECTING".to_string()
        } else if conn.recv_fps <= 1.0 {
            "NO-DATA".to_string()
        } else {
            conn.state.to_uppercase()
        };
        state_style = if connected {
            Style::default()
                .fg(COLOR_MOCHA_BG)
                .bg(COLOR_SUCCESS)
                .add_modifier(Modifier::BOLD)
        } else if connecting || bytes_no_decode {
            Style::default()
                .fg(COLOR_MOCHA_BG)
                .bg(COLOR_ORANGE)
                .add_modifier(Modifier::BOLD)
        } else {
            Style::default()
                .fg(COLOR_MOCHA_BG)
                .bg(COLOR_DANGER)
                .add_modifier(Modifier::BOLD)
        };
        detail = format!(
            "state={} serial={} port={} run={} recv={:.1} decode={:.1} present={:.1}/{:.1} recv≈{:.2}Mb/s restart={} reconn={} err={}",
            conn.state,
            conn.target_serial,
            conn.stream_port,
            conn.run_id,
            conn.recv_fps,
            conn.decode_fps,
            conn.present_fps,
            conn.target_fps,
            conn.recv_bps / 1_000_000.0,
            conn.restart_count,
            conn.reconnects,
            if conn.last_error.trim().is_empty() {
                "-"
            } else {
                conn.last_error.trim()
            }
        );
    }
    let line = Line::from(vec![
        Span::styled(format!(" {spin} "), Style::default().fg(COLOR_ORANGE)),
        Span::styled(format!(" {state_label} "), state_style),
        Span::styled(format!(" {detail}"), app_surface_style()),
    ]);
    let widget = Paragraph::new(line)
        .style(app_surface_style())
        .wrap(Wrap { trim: true })
        .block(pane_block("Connection status"));
    frame.render_widget(widget, area);
}

fn ui(frame: &mut ratatui::Frame, app: &App) {
    frame.render_widget(Block::default().style(app_body_style()), frame.area());
    let root = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Length(3),
            Constraint::Length(3),
            Constraint::Min(10),
            Constraint::Length(7),
        ])
        .split(frame.area());

    let header = Paragraph::new(Line::from(vec![
        Span::styled(
            "WBeam Tuner  ",
            Style::default()
                .fg(COLOR_ORANGE)
                .bg(COLOR_MOCHA_SURFACE)
                .add_modifier(Modifier::BOLD),
        ),
        Span::styled(app.step.title(), app_surface_style()),
        Span::styled("  ", app_surface_style()),
        Span::styled(
            format!(
                "codec={} gen={} child={}",
                app.selected_codec_name(),
                app.current_generation,
                app.current_child
            ),
            Style::default()
                .fg(COLOR_SUCCESS)
                .bg(COLOR_MOCHA_SURFACE)
                .add_modifier(Modifier::BOLD),
        ),
    ]))
    .style(app_surface_style())
    .block(pane_block("Header"));
    frame.render_widget(header, root[0]);
    draw_status_bar(frame, root[1], app);

    match app.step {
        Step::Backend => draw_backend(frame, root[2], app),
        Step::Target => draw_target(frame, root[2], app),
        Step::Probe => draw_probe(frame, root[2], app),
        Step::RunConfig => draw_run_config(frame, root[2], app),
        Step::Evolution => draw_evolution(frame, root[2], app),
        Step::Results => draw_results(frame, root[2], app),
        Step::Finish => draw_finish(frame, root[2], app),
    }

    let logs = app
        .logs
        .iter()
        .rev()
        .take(6)
        .rev()
        .map(|line| Line::from(line.as_str()))
        .collect::<Vec<_>>();
    let footer = Paragraph::new(logs)
        .style(app_surface_style())
        .wrap(Wrap { trim: true })
        .block(pane_block(
            "Log  (↑↓ focus, ←→ change, Enter action, Tab/Shift+Tab step, q quit)",
        ));
    frame.render_widget(footer, root[3]);
}

fn draw_backend(frame: &mut ratatui::Frame, area: ratatui::layout::Rect, app: &App) {
    let items: Vec<ratatui::widgets::ListItem> = app
        .backends
        .iter()
        .enumerate()
        .map(|(i, b)| {
            let selected = i == app.selected_backend;
            let focused = i == app.backend_focus;
            let prefix = if selected { "● " } else { "○ " };
            let label = format!("{prefix}{b}");
            let style = if focused {
                ratatui::style::Style::default()
                    .fg(ratatui::style::Color::Yellow)
                    .add_modifier(ratatui::style::Modifier::BOLD)
            } else if selected {
                ratatui::style::Style::default().fg(ratatui::style::Color::Green)
            } else {
                ratatui::style::Style::default()
            };
            ratatui::widgets::ListItem::new(label).style(style)
        })
        .collect();

    let list = ratatui::widgets::List::new(items)
        .block(
            ratatui::widgets::Block::default()
                .borders(ratatui::widgets::Borders::ALL)
                .title(" Capture Backend "),
        );
    frame.render_widget(list, area);
}

fn draw_target(frame: &mut ratatui::Frame, area: ratatui::layout::Rect, app: &App) {
    let body = Layout::default()
        .direction(Direction::Horizontal)
        .constraints([Constraint::Length(28), Constraint::Min(20)])
        .split(area);

    let steps = Step::all()
        .iter()
        .map(|s| {
            let active = *s == app.step;
            let marker = if active { "▶" } else { " " };
            let style = if active {
                Style::default()
                    .fg(COLOR_ORANGE)
                    .bg(COLOR_MOCHA_SURFACE)
                    .add_modifier(Modifier::BOLD)
            } else {
                muted_style()
            };
            Line::from(Span::styled(format!("{marker} {}", s.title()), style))
        })
        .collect::<Vec<_>>();
    let wizard = Paragraph::new(steps)
        .style(app_surface_style())
        .block(pane_block("Wizard"));
    frame.render_widget(wizard, body[0]);

    let form = vec![
        Line::from(Span::styled(
            format!("Host URL:      {}", app.api.base_url),
            muted_style(),
        )),
        Line::from(Span::styled(
            format!(
                "Serial:        {}",
                app.api
                    .serial
                    .clone()
                    .unwrap_or_else(|| "<default>".to_string())
            ),
            muted_style(),
        )),
        Line::from(Span::styled(
            format!(
                "Stream port:   {}",
                app.api
                    .stream_port
                    .map(|v| v.to_string())
                    .unwrap_or_else(|| "<auto>".to_string())
            ),
            muted_style(),
        )),
        Line::from(""),
        focus_line(
            app.target_focus == 0,
            format!("Codec:         {}   (←/→)", app.selected_codec_name()),
        ),
        focus_line(
            app.target_focus == 1,
            format!("Generations:   {}   (←/→ or +/-)", app.generations),
        ),
        focus_line(
            app.target_focus == 2,
            format!("Children/gen:  {}   (←/→ or +/-)", app.children),
        ),
        Line::from(""),
        action_line(
            app.target_focus == 3,
            "Run probe and continue to Guardrails",
        ),
        Line::from(""),
        Line::from("Shortcuts: g/G generations, c/C children"),
    ];
    frame.render_widget(
        Paragraph::new(form)
            .style(app_surface_style())
            .wrap(Wrap { trim: true })
            .block(pane_block("Target")),
        body[1],
    );
}

fn draw_probe(frame: &mut ratatui::Frame, area: ratatui::layout::Rect, app: &App) {
    let check_line = |ok: bool, label: &str| {
        let icon = if ok { "✓" } else { "✗" };
        let icon_style = if ok {
            Style::default()
                .fg(COLOR_SUCCESS)
                .bg(COLOR_MOCHA_SURFACE)
                .add_modifier(Modifier::BOLD)
        } else {
            Style::default()
                .fg(COLOR_DANGER)
                .bg(COLOR_MOCHA_SURFACE)
                .add_modifier(Modifier::BOLD)
        };
        Line::from(vec![
            Span::styled(format!("{icon} "), icon_style),
            Span::styled(label.to_string(), app_surface_style()),
        ])
    };
    let lines = vec![
        check_line(app.probe.ok_health, "/v1/health"),
        check_line(app.probe.ok_status, "/v1/status"),
        check_line(app.probe.ok_metrics, "/v1/metrics"),
        Line::from(""),
        Line::from(format!("Host state:     {}", app.probe.host_state)),
        Line::from(format!("Run id:         {}", app.probe.run_id)),
        Line::from(format!("Target serial:  {}", app.probe.target_serial)),
        Line::from(format!(
            "Stream port:    {}",
            if app.probe.stream_port > 0 {
                app.probe.stream_port.to_string()
            } else {
                "-".to_string()
            }
        )),
        Line::from(format!("Last error:     {}", app.probe.host_error)),
        Line::from(format!("Build revision: {}", app.probe.build_revision)),
        Line::from(""),
        action_line(app.probe_focus == 0, "Re-run probe"),
        action_line(app.probe_focus == 1, "Continue to Run Config"),
        Line::from(""),
        Line::from("Shortcut: r = re-probe"),
    ];
    frame.render_widget(
        Paragraph::new(lines)
            .style(app_surface_style())
            .wrap(Wrap { trim: true })
            .block(pane_block("Probe")),
        area,
    );
}

fn draw_run_config(frame: &mut ratatui::Frame, area: ratatui::layout::Rect, app: &App) {
    let profile_name = if app.profile_name_input.trim().is_empty() {
        "<required>".to_string()
    } else {
        app.profile_name_input.clone()
    };
    let source_tip = if app.use_prerendered_scenes {
        "Benchmark source: synthetic in-memory 2D game (12s loop, 4 scenes).".to_string()
    } else {
        "Training source: virtual desktop capture (display_mode=virtual_monitor).".to_string()
    };
    let lines = vec![
        focus_line(
            app.run_focus == 0,
            format!("Objective: {}   (←/→)", app.selected_objective_name()),
        ),
        focus_line(
            app.run_focus == 1,
            format!("Workload:  {}   (←/→)", app.selected_workload_name()),
        ),
        focus_line(
            app.run_focus == 2,
            format!(
                "Use prerendered scenes for training: {}   (Enter/Space)",
                if app.use_prerendered_scenes {
                    "[x]"
                } else {
                    "[ ]"
                }
            ),
        ),
        focus_line(
            app.run_focus == 3,
            format!(
                "Child train time: {} s   (←/→, +/-)",
                app.child_train_time_sec
            ),
        ),
        focus_line(
            app.run_focus == 4,
            format!("Profile name: {}   (type + Backspace)", profile_name),
        ),
        Line::from(format!("Display mode: {}", app.training_display_mode())),
        Line::from(format!("Seed cfg:  {:?}", app.seed_params())),
        Line::from(""),
        action_line(app.run_focus == 5, "Start evolution"),
        Line::from(""),
        Line::from(source_tip),
    ];
    frame.render_widget(
        Paragraph::new(lines)
            .style(app_surface_style())
            .wrap(Wrap { trim: true })
            .block(pane_block("Run Config")),
        area,
    );
}

fn draw_evolution(frame: &mut ratatui::Frame, area: ratatui::layout::Rect, app: &App) {
    let chunks = Layout::default()
        .direction(Direction::Vertical)
        .constraints([
            Constraint::Length(4),
            Constraint::Min(8),
            Constraint::Length(7),
        ])
        .split(area);

    let status = Paragraph::new(vec![
        Line::from(format!(
            "State: {}",
            if app.evolution_running {
                "RUNNING"
            } else if app.evolution_done {
                "DONE"
            } else {
                "IDLE"
            }
        )),
        Line::from(format!(
            "Generation {}/{}  Child {}/{}",
            app.current_generation, app.generations, app.current_child, app.children
        )),
        Line::from(format!(
            "Current params: {}",
            app.current_params
                .as_ref()
                .map(|p| format!(
                    "bitrate={} fps={} intra={}",
                    p.bitrate_kbps, p.fps, p.intra_only
                ))
                .unwrap_or_else(|| "-".to_string())
        )),
    ])
    .style(app_surface_style())
    .block(pane_block("Evolution status"));
    frame.render_widget(status, chunks[0]);

    let rows = app
        .results
        .iter()
        .rev()
        .take(10)
        .rev()
        .map(|r| {
            let row_style = if r.fail {
                Style::default().fg(COLOR_DANGER).bg(COLOR_MOCHA_SURFACE)
            } else {
                Style::default().fg(COLOR_SUCCESS).bg(COLOR_MOCHA_SURFACE)
            };
            Row::new(vec![
                Cell::from(format!("{}-{}", r.generation, r.child)),
                Cell::from(r.params.bitrate_kbps.to_string()),
                Cell::from(r.params.fps.to_string()),
                Cell::from(if r.params.intra_only { "on" } else { "off" }),
                Cell::from(format!("{:.2}", r.score)),
                Cell::from(if r.fail { "FAIL" } else { "OK" }),
                Cell::from(r.reason.clone()),
            ])
            .style(row_style)
        })
        .collect::<Vec<_>>();
    let table = Table::new(
        rows,
        [
            Constraint::Length(6),
            Constraint::Length(8),
            Constraint::Length(5),
            Constraint::Length(7),
            Constraint::Length(8),
            Constraint::Length(6),
            Constraint::Min(20),
        ],
    )
    .header(
        Row::new(vec![
            "id", "bitrate", "fps", "intra", "score", "state", "reason",
        ])
        .style(
            Style::default()
                .fg(COLOR_ORANGE)
                .bg(COLOR_MOCHA_SURFACE)
                .add_modifier(Modifier::BOLD),
        ),
    )
    .style(app_surface_style())
    .block(pane_block("Candidates"));
    frame.render_widget(table, chunks[1]);

    let control_lines = vec![
        action_line(
            app.evolution_focus == 0,
            if app
                .engine
                .as_ref()
                .map(|e| e.pause.load(Ordering::SeqCst))
                .unwrap_or(false)
            {
                "Resume evolution"
            } else {
                "Pause evolution"
            },
        ),
        action_line(app.evolution_focus == 1, "Rollback seed config"),
        action_line(app.evolution_focus == 2, "Stop evolution"),
        action_line(
            app.evolution_focus == 3,
            if app.evolution_done {
                "Go to results"
            } else {
                "Go to results (available when done)"
            },
        ),
        Line::from("Shortcuts: Space pause/resume, r rollback, s stop"),
    ];
    let control = Paragraph::new(control_lines)
        .style(app_surface_style())
        .block(pane_block("Controls"));
    frame.render_widget(control, chunks[2]);
}

fn final_profile_summary_lines(app: &App) -> Vec<Line<'static>> {
    let Some(best) = app.best.as_ref() else {
        return vec![Line::from("No completed winner profile yet.")];
    };

    let mut lines = Vec::new();
    if let Some(run) = app.current_run.as_ref() {
        lines.push(Line::from(format!("Profile: {}", run.profile_name)));
        lines.push(Line::from(format!(
            "Codec/encoder: {}/{}   Objective: {}   Workload: {}",
            run.codec,
            map_codec_to_encoder(&run.codec),
            run.objective,
            run.workload
        )));
        lines.push(Line::from(format!(
            "Source: {}   Display mode: {}",
            run.source_label, run.display_mode
        )));
        lines.push(Line::from(format!(
            "Child train time: {}s   Sample interval: {}ms",
            run.child_train_time_sec, SAMPLE_INTERVAL_MS
        )));
    }
    lines.push(Line::from(format!(
        "Score: {:.2}   Gen/Child: {}/{}   Fail: {}",
        best.score, best.generation, best.child, best.fail
    )));
    lines.push(Line::from(format!(
        "Params: bitrate={} kbps   fps={}   intra_only={}",
        best.params.bitrate_kbps, best.params.fps, best.params.intra_only
    )));
    lines.push(Line::from(format!("Reason: {}", best.reason)));
    lines
}

fn draw_results(frame: &mut ratatui::Frame, area: ratatui::layout::Rect, app: &App) {
    let chunks = Layout::default()
        .direction(Direction::Vertical)
        .constraints([Constraint::Length(9), Constraint::Min(7)])
        .split(area);

    frame.render_widget(
        Paragraph::new(final_profile_summary_lines(app))
            .style(app_surface_style())
            .wrap(Wrap { trim: true })
            .block(pane_block("Final profile settings")),
        chunks[0],
    );

    let lines = vec![
        Line::from(format!("Total candidates: {}", app.results.len())),
        Line::from(format!("Winner applied: {}", app.winner_applied)),
        Line::from(format!(
            "Saved profile: {}",
            app.last_saved_profile
                .clone()
                .unwrap_or_else(|| "<none>".to_string())
        )),
        Line::from(format!(
            "Export file: {}",
            app.exported_file
                .clone()
                .unwrap_or_else(|| "<none>".to_string())
        )),
        Line::from(""),
        action_line(app.results_focus == 0, "Apply best candidate"),
        action_line(app.results_focus == 1, "Export full results"),
        action_line(app.results_focus == 2, "Continue to finish"),
        Line::from(""),
        Line::from("Shortcuts: a apply, e export"),
    ];
    frame.render_widget(
        Paragraph::new(lines)
            .style(app_surface_style())
            .wrap(Wrap { trim: true })
            .block(pane_block("Results")),
        chunks[1],
    );
}

fn draw_finish(frame: &mut ratatui::Frame, area: ratatui::layout::Rect, app: &App) {
    let chunks = Layout::default()
        .direction(Direction::Vertical)
        .constraints([Constraint::Length(9), Constraint::Min(4)])
        .split(area);

    frame.render_widget(
        Paragraph::new(final_profile_summary_lines(app))
            .style(app_surface_style())
            .wrap(Wrap { trim: true })
            .block(pane_block("Final profile settings")),
        chunks[0],
    );

    let lines = vec![
        Line::from("Training run complete."),
        Line::from(format!(
            "Winner applied: {}",
            if app.winner_applied { "yes" } else { "no" }
        )),
        Line::from(""),
        action_line(true, "Exit tuner"),
        Line::from("Enter/q = exit, Esc = back"),
    ];
    frame.render_widget(
        Paragraph::new(lines)
            .style(app_surface_style())
            .wrap(Wrap { trim: true })
            .block(pane_block("Finish")),
        chunks[1],
    );
}

// NOSONAR S3776 - TUI runtime loop intentionally handles nested event/tick flow
fn main() -> Result<()> {
    let args = Args::parse();
    migrate_legacy_profiles_once();
    let mut terminal = init_terminal()?;
    let mut app = App::new(args)?;
    let mut last_tick = Instant::now();
    let tick_rate = Duration::from_millis(150);

    let loop_result: Result<()> = (|| {
        loop {
            terminal.draw(|f| ui(f, &app)).context("draw failed")?;
            let timeout = tick_rate
                .checked_sub(last_tick.elapsed())
                .unwrap_or(Duration::from_millis(0));
            if event::poll(timeout).context("event poll failed")? {
                if let Event::Key(key) = event::read().context("event read failed")? {
                    if key.kind == KeyEventKind::Press && app.handle_key(key.code) {
                        break;
                    }
                }
            }
            if last_tick.elapsed() >= tick_rate {
                app.on_tick();
                last_tick = Instant::now();
            }
        }
        Ok(())
    })();

    app.stop_engine();
    app.clear_remote_tuning();
    let restore_result = restore_terminal(terminal);
    loop_result?;
    restore_result?;
    Ok(())
}
