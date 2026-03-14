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
use serde::Serialize;
use serde_json::{json, Value};

#[derive(Debug, Parser)]
#[command(name = "wbeam-tuner", about = "Interactive tuner wizard for WBeam codecs")]
struct Args {
    #[arg(long, default_value = "http://127.0.0.1:5001")]
    host_url: String,
    #[arg(long)]
    serial: Option<String>,
    #[arg(long, default_value_t = 10)]
    generations: u32,
    #[arg(long, default_value_t = 10)]
    children: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Step {
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
            Step::Target => "1/6 Target",
            Step::Probe => "2/6 Probe & Guardrails",
            Step::RunConfig => "3/6 Run Config",
            Step::Evolution => "4/6 Evolution",
            Step::Results => "5/6 Results",
            Step::Finish => "6/6 Finish",
        }
    }

    fn all() -> &'static [Step] {
        &[
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
            Step::Target => Step::Probe,
            Step::Probe => Step::RunConfig,
            Step::RunConfig => Step::Evolution,
            Step::Evolution => Step::Results,
            Step::Results => Step::Finish,
            Step::Finish => Step::Finish,
        }
    }
}

#[derive(Debug, Clone)]
struct ApiClient {
    base_url: String,
    serial: Option<String>,
    client: reqwest::blocking::Client,
}

impl ApiClient {
    fn new(base_url: String, serial: Option<String>) -> Result<Self> {
        let client = reqwest::blocking::Client::builder()
            .timeout(Duration::from_secs(4))
            .build()
            .context("failed to build HTTP client")?;
        Ok(Self {
            base_url: base_url.trim_end_matches('/').to_string(),
            serial,
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
    generations: u32,
    children: u32,
    objective: String,
    workload: String,
    seed: CandidateParams,
}

#[derive(Debug, Clone)]
struct MetricSample {
    state: String,
    target_fps: f64,
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

#[derive(Debug)]
enum EngineEvent {
    Log(String),
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

#[derive(Debug)]
struct App {
    api: ApiClient,
    step: Step,
    codecs: Vec<&'static str>,
    objectives: Vec<&'static str>,
    workloads: Vec<&'static str>,
    selected_codec: usize,
    selected_objective: usize,
    selected_workload: usize,
    generations: u32,
    children: u32,
    probe: ProbeState,
    probe_ran: bool,
    logs: VecDeque<String>,
    engine: Option<EvolutionHandle>,
    evolution_running: bool,
    evolution_done: bool,
    current_generation: u32,
    current_child: u32,
    current_params: Option<CandidateParams>,
    results: Vec<CandidateResult>,
    best: Option<CandidateResult>,
    winner_applied: bool,
    exported_file: Option<String>,
}

impl App {
    fn new(args: Args) -> Result<Self> {
        Ok(Self {
            api: ApiClient::new(args.host_url, args.serial)?,
            step: Step::Target,
            codecs: vec!["h264", "h265", "rawpng", "mjpeg"],
            objectives: vec!["balanced", "latency-first", "quality-first"],
            workloads: vec!["desktop/text", "motion/video", "mixed"],
            selected_codec: 0,
            selected_objective: 0,
            selected_workload: 0,
            generations: args.generations.clamp(1, 30),
            children: args.children.clamp(1, 30),
            probe: ProbeState::default(),
            probe_ran: false,
            logs: VecDeque::new(),
            engine: None,
            evolution_running: false,
            evolution_done: false,
            current_generation: 0,
            current_child: 0,
            current_params: None,
            results: Vec::new(),
            best: None,
            winner_applied: false,
            exported_file: None,
        })
    }

    fn log(&mut self, msg: impl Into<String>) {
        while self.logs.len() > 120 {
            self.logs.pop_front();
        }
        self.logs.push_back(msg.into());
    }

    fn selected_codec_name(&self) -> &'static str {
        self.codecs[self.selected_codec]
    }

    fn selected_objective_name(&self) -> &'static str {
        self.objectives[self.selected_objective]
    }

    fn selected_workload_name(&self) -> &'static str {
        self.workloads[self.selected_workload]
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

    fn run_probe(&mut self) {
        self.log("probe: checking /v1/health /v1/status /v1/metrics");
        self.probe = ProbeState::default();
        self.probe.ok_health = self.api.get_json("health").map(|health| {
            self.probe.build_revision = health
                .get("build_revision")
                .and_then(Value::as_str)
                .unwrap_or("-")
                .to_string();
        }).is_ok();

        self.probe.ok_status = self.api.get_json("status").map(|status| {
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
        }).is_ok();

        self.probe.ok_metrics = self.api.get_json("metrics").is_ok();
        self.probe_ran = true;
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

        let cfg = EvolutionConfig {
            codec: self.selected_codec_name().to_string(),
            generations: self.generations,
            children: self.children,
            objective: self.selected_objective_name().to_string(),
            workload: self.selected_workload_name().to_string(),
            seed: self.seed_params(),
        };

        let (tx, rx) = mpsc::channel::<EngineEvent>();
        let stop = Arc::new(AtomicBool::new(false));
        let pause = Arc::new(AtomicBool::new(false));
        let stop_clone = stop.clone();
        let pause_clone = pause.clone();
        let api = self.api.clone();

        let join = thread::spawn(move || run_evolution_worker(api, cfg, tx, stop_clone, pause_clone));
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

    fn handle_key(&mut self, code: KeyCode) -> bool {
        match code {
            KeyCode::Char('q') => return true,
            KeyCode::Tab => {
                self.step = self.step.next();
            }
            _ => {}
        }

        match self.step {
            Step::Target => match code {
                KeyCode::Left => {
                    if self.selected_codec > 0 {
                        self.selected_codec -= 1;
                    }
                }
                KeyCode::Right => {
                    if self.selected_codec + 1 < self.codecs.len() {
                        self.selected_codec += 1;
                    }
                }
                KeyCode::Char('g') => self.generations = (self.generations + 1).min(30),
                KeyCode::Char('G') => self.generations = self.generations.saturating_sub(1).max(1),
                KeyCode::Char('c') => self.children = (self.children + 1).min(30),
                KeyCode::Char('C') => self.children = self.children.saturating_sub(1).max(1),
                KeyCode::Enter => {
                    self.step = Step::Probe;
                    if !self.probe_ran {
                        self.run_probe();
                    }
                }
                _ => {}
            },
            Step::Probe => match code {
                KeyCode::Char('r') => self.run_probe(),
                KeyCode::Enter => self.step = Step::RunConfig,
                _ => {}
            },
            Step::RunConfig => match code {
                KeyCode::Left => {
                    if self.selected_objective > 0 {
                        self.selected_objective -= 1;
                    }
                }
                KeyCode::Right => {
                    if self.selected_objective + 1 < self.objectives.len() {
                        self.selected_objective += 1;
                    }
                }
                KeyCode::Up => {
                    if self.selected_workload > 0 {
                        self.selected_workload -= 1;
                    }
                }
                KeyCode::Down => {
                    if self.selected_workload + 1 < self.workloads.len() {
                        self.selected_workload += 1;
                    }
                }
                KeyCode::Enter => {
                    self.start_evolution();
                    self.step = Step::Evolution;
                }
                _ => {}
            },
            Step::Evolution => match code {
                KeyCode::Char(' ') => {
                    if let Some(engine) = self.engine.as_ref() {
                        let paused = engine.pause.load(Ordering::SeqCst);
                        engine.pause.store(!paused, Ordering::SeqCst);
                        self.log(if paused {
                            "evolution: resumed"
                        } else {
                            "evolution: paused"
                        });
                    }
                }
                KeyCode::Char('r') => self.rollback_seed(),
                KeyCode::Char('s') => self.stop_engine(),
                KeyCode::Enter => {
                    if self.evolution_done {
                        self.step = Step::Results;
                    }
                }
                _ => {}
            },
            Step::Results => match code {
                KeyCode::Char('a') => self.apply_best(),
                KeyCode::Char('e') => self.export_results(),
                KeyCode::Enter => self.step = Step::Finish,
                _ => {}
            },
            Step::Finish => {
                if matches!(code, KeyCode::Enter) {
                    return true;
                }
            }
        }
        false
    }

    fn on_tick(&mut self) {
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
                    self.log("evolution: completed");
                }
                EngineEvent::Failed(err) => {
                    self.evolution_running = false;
                    self.evolution_done = true;
                    self.log(format!("evolution failed: {err}"));
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

fn parse_metric_sample(metrics_payload: &Value) -> Option<MetricSample> {
    let metrics = metrics_payload.get("metrics")?;
    let kpi = metrics.get("kpi").cloned().unwrap_or(Value::Null);
    let latest = metrics
        .get("latest_client_metrics")
        .cloned()
        .unwrap_or(Value::Null);
    Some(MetricSample {
        state: metrics_payload
            .get("state")
            .and_then(Value::as_str)
            .unwrap_or("-")
            .to_string(),
        target_fps: kpi.get("target_fps").and_then(Value::as_f64).unwrap_or(60.0),
        present_fps: kpi.get("present_fps").and_then(Value::as_f64).unwrap_or(0.0),
        decode_p95: kpi
            .get("decode_time_ms_p95")
            .and_then(Value::as_f64)
            .unwrap_or(0.0),
        render_p95: kpi
            .get("render_time_ms_p95")
            .and_then(Value::as_f64)
            .unwrap_or(0.0),
        e2e_p95: kpi
            .get("e2e_latency_ms_p95")
            .and_then(Value::as_f64)
            .unwrap_or(0.0),
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
        recv_bps: latest.get("recv_bps").and_then(Value::as_f64).unwrap_or(0.0),
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
    let min = |f: fn(&MetricSample) -> f64| {
        samples
            .iter()
            .map(f)
            .fold(f64::INFINITY, |a, b| a.min(b))
    };

    let target = mean(|s| s.target_fps).max(1.0);
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

    if streaming_ratio < 0.7 {
        return (-9_000.0, true, "streaming state unstable".to_string());
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
    (
        score,
        false,
        format!(
            "p95={e2e_p95:.1}ms present={present:.1}/{target:.1} q={q_t:.0}/{q_d:.0} dropΔ={dropped_delta:.1}"
        ),
    )
}

fn run_evolution_worker(
    api: ApiClient,
    cfg: EvolutionConfig,
    tx: Sender<EngineEvent>,
    stop: Arc<AtomicBool>,
    pause: Arc<AtomicBool>,
) {
    let mut rng = StdRng::from_entropy();
    let codec = cfg.codec.clone();
    let mut parent = cfg.seed.clone();
    let mut best: Option<CandidateResult> = None;
    let encoder = map_codec_to_encoder(&codec);

    if codec == "mjpeg" {
        let _ = tx.send(EngineEvent::Log(
            "codec=mjpeg not implemented yet on host; falling back to rawpng".to_string(),
        ));
    }
    let _ = tx.send(EngineEvent::Log(format!(
        "run: codec={} objective={} workload={}",
        cfg.codec, cfg.objective, cfg.workload
    )));

    let start_body = json!({
        "encoder": encoder,
        "fps": cfg.seed.fps,
        "bitrate_kbps": cfg.seed.bitrate_kbps,
        "intra_only": cfg.seed.intra_only
    });
    if let Err(err) = api.post_json("start", &start_body) {
        let _ = tx.send(EngineEvent::Failed(format!("start failed: {err}")));
        return;
    }

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

            let candidate = mutate_candidate(&parent, &codec, &mut rng, generation);
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

            let apply_result = api.post_json("apply", &apply_body);
            if let Err(err) = apply_result {
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

            thread::sleep(Duration::from_millis(1200));
            let mut samples = Vec::new();
            for _ in 0..4 {
                if stop.load(Ordering::SeqCst) {
                    break 'gens;
                }
                if let Ok(payload) = api.get_json("metrics") {
                    if let Some(sample) = parse_metric_sample(&payload) {
                        samples.push(sample);
                    }
                }
                thread::sleep(Duration::from_millis(300));
            }

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
                parent = candidate.clone();
            }

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

fn init_terminal() -> Result<Terminal<CrosstermBackend<Stdout>>> {
    enable_raw_mode().context("failed to enable raw mode")?;
    let mut stdout = io::stdout();
    execute!(stdout, EnterAlternateScreen).context("failed to enter alternate screen")?;
    let backend = CrosstermBackend::new(stdout);
    Terminal::new(backend).context("failed to init terminal")
}

fn restore_terminal(mut terminal: Terminal<CrosstermBackend<Stdout>>) -> Result<()> {
    disable_raw_mode().context("failed to disable raw mode")?;
    execute!(terminal.backend_mut(), LeaveAlternateScreen).context("failed to leave alternate screen")?;
    terminal.show_cursor().context("failed to show cursor")
}

fn ui(frame: &mut ratatui::Frame, app: &App) {
    let root = Layout::default()
        .direction(Direction::Vertical)
        .constraints([Constraint::Length(3), Constraint::Min(10), Constraint::Length(7)])
        .split(frame.area());

    let header = Paragraph::new(Line::from(vec![
        Span::styled("WBeam Tuner  ", Style::default().fg(Color::Cyan).add_modifier(Modifier::BOLD)),
        Span::raw(app.step.title()),
        Span::raw("  "),
        Span::styled(
            format!(
                "codec={} gen={} child={}",
                app.selected_codec_name(),
                app.current_generation,
                app.current_child
            ),
            Style::default().fg(Color::Yellow),
        ),
    ]))
    .block(Block::default().borders(Borders::ALL).title("Header"));
    frame.render_widget(header, root[0]);

    match app.step {
        Step::Target => draw_target(frame, root[1], app),
        Step::Probe => draw_probe(frame, root[1], app),
        Step::RunConfig => draw_run_config(frame, root[1], app),
        Step::Evolution => draw_evolution(frame, root[1], app),
        Step::Results => draw_results(frame, root[1], app),
        Step::Finish => draw_finish(frame, root[1], app),
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
        .wrap(Wrap { trim: true })
        .block(Block::default().borders(Borders::ALL).title("Log"));
    frame.render_widget(footer, root[2]);
}

fn draw_target(frame: &mut ratatui::Frame, area: ratatui::layout::Rect, app: &App) {
    let body = Layout::default()
        .direction(Direction::Horizontal)
        .constraints([Constraint::Length(28), Constraint::Min(20)])
        .split(area);

    let steps = Step::all()
        .iter()
        .map(|s| {
            let marker = if *s == app.step { ">" } else { " " };
            Line::from(format!("{marker} {}", s.title()))
        })
        .collect::<Vec<_>>();
    let wizard = Paragraph::new(steps).block(Block::default().borders(Borders::ALL).title("Wizard"));
    frame.render_widget(wizard, body[0]);

    let form = vec![
        Line::from(format!("Host URL:      {}", app.api.base_url)),
        Line::from(format!(
            "Serial:        {}",
            app.api.serial.clone().unwrap_or_else(|| "<default>".to_string())
        )),
        Line::from(format!("Codec:         {}", app.selected_codec_name())),
        Line::from(format!("Generations:   {}", app.generations)),
        Line::from(format!("Children/gen:  {}", app.children)),
        Line::from(""),
        Line::from("Keys: <- -> codec, g/G generations, c/C children"),
        Line::from("Enter -> Probe"),
    ];
    frame.render_widget(
        Paragraph::new(form)
            .wrap(Wrap { trim: true })
            .block(Block::default().borders(Borders::ALL).title("Target")),
        body[1],
    );
}

fn draw_probe(frame: &mut ratatui::Frame, area: ratatui::layout::Rect, app: &App) {
    let check = |ok: bool| if ok { "✓" } else { "✗" };
    let lines = vec![
        Line::from(format!("{} /v1/health", check(app.probe.ok_health))),
        Line::from(format!("{} /v1/status", check(app.probe.ok_status))),
        Line::from(format!("{} /v1/metrics", check(app.probe.ok_metrics))),
        Line::from(""),
        Line::from(format!("Host state:     {}", app.probe.host_state)),
        Line::from(format!("Last error:     {}", app.probe.host_error)),
        Line::from(format!("Build revision: {}", app.probe.build_revision)),
        Line::from(""),
        Line::from("r = re-probe, Enter = continue"),
    ];
    frame.render_widget(
        Paragraph::new(lines)
            .wrap(Wrap { trim: true })
            .block(Block::default().borders(Borders::ALL).title("Probe")),
        area,
    );
}

fn draw_run_config(frame: &mut ratatui::Frame, area: ratatui::layout::Rect, app: &App) {
    let lines = vec![
        Line::from(format!("Objective: {}", app.selected_objective_name())),
        Line::from(format!("Workload:  {}", app.selected_workload_name())),
        Line::from(format!("Seed cfg:  {:?}", app.seed_params())),
        Line::from(""),
        Line::from("Left/Right -> objective"),
        Line::from("Up/Down    -> workload"),
        Line::from("Enter      -> start evolution"),
    ];
    frame.render_widget(
        Paragraph::new(lines)
            .wrap(Wrap { trim: true })
            .block(Block::default().borders(Borders::ALL).title("Run Config")),
        area,
    );
}

fn draw_evolution(frame: &mut ratatui::Frame, area: ratatui::layout::Rect, app: &App) {
    let chunks = Layout::default()
        .direction(Direction::Vertical)
        .constraints([Constraint::Length(4), Constraint::Min(8), Constraint::Length(3)])
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
                .map(|p| format!("bitrate={} fps={} intra={}", p.bitrate_kbps, p.fps, p.intra_only))
                .unwrap_or_else(|| "-".to_string())
        )),
    ])
    .block(Block::default().borders(Borders::ALL).title("Evolution status"));
    frame.render_widget(status, chunks[0]);

    let rows = app
        .results
        .iter()
        .rev()
        .take(10)
        .rev()
        .map(|r| {
            Row::new(vec![
                Cell::from(format!("{}-{}", r.generation, r.child)),
                Cell::from(r.params.bitrate_kbps.to_string()),
                Cell::from(r.params.fps.to_string()),
                Cell::from(if r.params.intra_only { "on" } else { "off" }),
                Cell::from(format!("{:.2}", r.score)),
                Cell::from(if r.fail { "FAIL" } else { "OK" }),
                Cell::from(r.reason.clone()),
            ])
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
        Row::new(vec!["id", "bitrate", "fps", "intra", "score", "state", "reason"])
            .style(Style::default().fg(Color::Cyan).add_modifier(Modifier::BOLD)),
    )
    .block(Block::default().borders(Borders::ALL).title("Candidates"));
    frame.render_widget(table, chunks[1]);

    let control = Paragraph::new("Space pause/resume | r rollback seed | s stop | Enter results")
        .block(Block::default().borders(Borders::ALL).title("Controls"));
    frame.render_widget(control, chunks[2]);
}

fn draw_results(frame: &mut ratatui::Frame, area: ratatui::layout::Rect, app: &App) {
    let best = app
        .best
        .as_ref()
        .map(|r| {
            format!(
                "Best: score={:.2} gen={} child={} bitrate={} fps={} intra={}",
                r.score, r.generation, r.child, r.params.bitrate_kbps, r.params.fps, r.params.intra_only
            )
        })
        .unwrap_or_else(|| "Best: -".to_string());
    let lines = vec![
        Line::from(best),
        Line::from(format!("Total candidates: {}", app.results.len())),
        Line::from(format!("Winner applied: {}", app.winner_applied)),
        Line::from(format!(
            "Export file: {}",
            app.exported_file
                .clone()
                .unwrap_or_else(|| "<none>".to_string())
        )),
        Line::from(""),
        Line::from("a = apply best, e = export, Enter = finish"),
    ];
    frame.render_widget(
        Paragraph::new(lines)
            .wrap(Wrap { trim: true })
            .block(Block::default().borders(Borders::ALL).title("Results")),
        area,
    );
}

fn draw_finish(frame: &mut ratatui::Frame, area: ratatui::layout::Rect, app: &App) {
    let lines = vec![
        Line::from("Done."),
        Line::from(format!("Best score: {:.2}", app.best.as_ref().map(|b| b.score).unwrap_or(0.0))),
        Line::from(format!(
            "Winner applied: {}",
            if app.winner_applied { "yes" } else { "no" }
        )),
        Line::from("Enter = exit, q = quit"),
    ];
    frame.render_widget(
        Paragraph::new(lines)
            .wrap(Wrap { trim: true })
            .block(Block::default().borders(Borders::ALL).title("Finish")),
        area,
    );
}

fn main() -> Result<()> {
    let args = Args::parse();
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
