// sonar-disable S3776: Cognitive complexity is essential for domain logic
use std::collections::HashSet;
use std::process::Command;

use serde_json::Value;

use super::trainer_models::TrainerStartRequest;

pub(crate) struct TrainerStartConfig {
    pub(crate) serial: String,
    pub(crate) profile_name: String,
    pub(crate) mode: String,
    pub(crate) trials: u32,
    pub(crate) warmup_sec: u32,
    pub(crate) sample_sec: u32,
    pub(crate) overlay: bool,
    pub(crate) stream_port: u16,
    pub(crate) generations: u32,
    pub(crate) population: u32,
    pub(crate) elite_count: u32,
    pub(crate) mutation_rate: f64,
    pub(crate) crossover_rate: f64,
    pub(crate) bitrate_min_kbps: u32,
    pub(crate) bitrate_max_kbps: u32,
    pub(crate) encoder_mode: String,
    pub(crate) encoders: Vec<String>,
    pub(crate) encoder_tuning_mode: String,
    pub(crate) encoder_params: Value,
    pub(crate) hud_chart_mode: String,
    pub(crate) hud_font_preset: String,
    pub(crate) hud_layout: String,
    pub(crate) warnings: Vec<String>,
}

#[allow(clippy::cognitive_complexity)]
#[allow(clippy::cognitive_complexity)]
pub(crate) fn normalize_start_request( // NOSONAR: S3776
    req: TrainerStartRequest,
    default_stream_port: u16,
    profile_name: String,
) -> Result<TrainerStartConfig, String> {
    let serial = req.serial.trim().to_string();
    if serial.is_empty() {
        return Err("serial is required".to_string());
    }

    let mode = req.mode.as_deref().unwrap_or("quality").trim().to_string();
    if !matches!(mode.as_str(), "quality" | "balanced" | "latency" | "custom") {
        return Err("invalid mode".to_string());
    }

    let trials = req.trials.unwrap_or(18).clamp(1, 128);
    let warmup_sec = req.warmup_sec.unwrap_or(4).clamp(1, 60);
    let sample_sec = req.sample_sec.unwrap_or(12).clamp(4, 180);
    let overlay = req.overlay.unwrap_or(true);
    let generations = req.generations.unwrap_or(2).clamp(1, 32);
    let population = req.population.unwrap_or(trials.max(4)).clamp(2, 256);
    let elite_count = req
        .elite_count
        .unwrap_or((population / 3).max(2))
        .clamp(1, population.saturating_sub(1).max(1));
    let mutation_rate = req.mutation_rate.unwrap_or(0.34_f64).clamp(0.0, 1.0);
    let crossover_rate = req.crossover_rate.unwrap_or(0.50_f64).clamp(0.0, 1.0);
    let bitrate_min_kbps = req.bitrate_min_kbps.unwrap_or(10_000).clamp(4_000, 400_000);
    let bitrate_max_kbps = req
        .bitrate_max_kbps
        .unwrap_or(200_000)
        .clamp(bitrate_min_kbps, 400_000);

    let mut warnings: Vec<String> = Vec::new();
    let requested_encoder_mode = req
        .encoder_mode
        .as_deref()
        .unwrap_or("auto")
        .trim()
        .to_ascii_lowercase();
    if !matches!(requested_encoder_mode.as_str(), "auto" | "single" | "multi") {
        return Err("invalid encoder_mode (use auto|single|multi)".to_string());
    }
    let mut encoders = req
        .encoders
        .unwrap_or_else(|| vec!["h265".to_string(), "h264".to_string()])
        .into_iter()
        .map(|v| v.trim().to_ascii_lowercase())
        .filter_map(|v| match v.as_str() {
            "h264" => Some("h264".to_string()),
            "h265" => Some("h265".to_string()),
            "rawpng" => Some("rawpng".to_string()),
            "jpeg" | "mjpeg" => Some("mjpeg".to_string()),
            _ => None,
        })
        .collect::<Vec<_>>();
    if encoders.is_empty() {
        encoders = vec!["h264".to_string()];
    }
    {
        let mut seen = HashSet::new();
        encoders.retain(|enc| seen.insert(enc.clone()));
    }

    let mut encoder_mode = if requested_encoder_mode == "auto" {
        if encoders.len() > 1 {
            "multi".to_string()
        } else {
            "single".to_string()
        }
    } else {
        requested_encoder_mode
    };
    if encoder_mode == "single" && encoders.len() > 1 {
        warnings.push(format!(
            "encoder_mode=single requested with {} encoders; using first encoder '{}'",
            encoders.len(),
            encoders[0]
        ));
        encoders = vec![encoders[0].clone()];
    }
    if encoder_mode == "multi" && encoders.len() == 1 {
        warnings.push(
            "encoder_mode=multi requested with one encoder; switching to single mode".to_string(),
        );
        encoder_mode = "single".to_string();
    }

    let encoder_tuning_mode = req
        .encoder_tuning_mode
        .as_deref()
        .unwrap_or("auto")
        .trim()
        .to_ascii_lowercase();
    if !matches!(encoder_tuning_mode.as_str(), "auto" | "manual") {
        return Err("invalid encoder_tuning_mode (use auto|manual)".to_string());
    }
    let encoder_params = req.encoder_params.unwrap_or(Value::Null);
    if encoder_tuning_mode == "manual" {
        warnings.push(
            "encoder_tuning_mode=manual is experimental; unsupported params may be ignored by runtime".to_string(),
        );
    }
    if encoder_tuning_mode == "manual" && !encoder_params.is_null() {
        warnings.push(
            "encoder_params are accepted by trainer flow but may not map 1:1 to active streamer backend".to_string(),
        );
    }

    let hud_chart_mode = req
        .hud_chart_mode
        .as_deref()
        .unwrap_or("bars")
        .trim()
        .to_ascii_lowercase();
    if !matches!(hud_chart_mode.as_str(), "bars" | "line") {
        return Err("invalid hud_chart_mode (use bars|line)".to_string());
    }

    let hud_font_preset = req
        .hud_font_preset
        .as_deref()
        .unwrap_or("compact")
        .trim()
        .to_ascii_lowercase();
    if !matches!(
        hud_font_preset.as_str(),
        "compact" | "dense" | "arcade" | "system"
    ) {
        return Err("invalid hud_font_preset (use compact|dense|arcade|system)".to_string());
    }

    let hud_layout = req
        .hud_layout
        .as_deref()
        .unwrap_or("wide")
        .trim()
        .to_ascii_lowercase();
    if !matches!(hud_layout.as_str(), "compact" | "wide") {
        return Err("invalid hud_layout (use compact|wide)".to_string());
    }

    let stream_port = req.stream_port.unwrap_or(default_stream_port);
    Ok(TrainerStartConfig {
        serial,
        profile_name,
        mode,
        trials,
        warmup_sec,
        sample_sec,
        overlay,
        stream_port,
        generations,
        population,
        elite_count,
        mutation_rate,
        crossover_rate,
        bitrate_min_kbps,
        bitrate_max_kbps,
        encoder_mode,
        encoders,
        encoder_tuning_mode,
        encoder_params,
        hud_chart_mode,
        hud_font_preset,
        hud_layout,
        warnings,
    })
}

pub(crate) fn configure_trainer_command(
    cmd: &mut Command,
    config: &TrainerStartConfig,
    run_id: &str,
    control_port: u16,
) {
    cmd.arg("train")
        .arg("wizard")
        .arg("--non-interactive")
        .arg("--apply-best")
        .arg("--export-best")
        .arg("--serial")
        .arg(&config.serial)
        .arg("--profile-name")
        .arg(&config.profile_name)
        .arg("--mode")
        .arg(&config.mode)
        .arg("--run-id")
        .arg(run_id)
        .arg("--trials")
        .arg(config.trials.to_string())
        .arg("--warmup-sec")
        .arg(config.warmup_sec.to_string())
        .arg("--sample-sec")
        .arg(config.sample_sec.to_string())
        .arg("--generations")
        .arg(config.generations.to_string())
        .arg("--population")
        .arg(config.population.to_string())
        .arg("--elite-count")
        .arg(config.elite_count.to_string())
        .arg("--mutation-rate")
        .arg(format!("{:.4}", config.mutation_rate))
        .arg("--crossover-rate")
        .arg(format!("{:.4}", config.crossover_rate))
        .arg("--bitrate-min-kbps")
        .arg(config.bitrate_min_kbps.to_string())
        .arg("--bitrate-max-kbps")
        .arg(config.bitrate_max_kbps.to_string())
        .arg("--encoder-mode")
        .arg(&config.encoder_mode)
        .arg("--encoders")
        .arg(config.encoders.join(","))
        .arg("--encoder-tuning-mode")
        .arg(&config.encoder_tuning_mode)
        .arg("--encoder-params-json")
        .arg(config.encoder_params.to_string())
        .arg("--overlay-chart")
        .arg(&config.hud_chart_mode)
        .arg("--overlay-layout")
        .arg(&config.hud_layout)
        .arg("--stream-port")
        .arg(config.stream_port.to_string())
        .arg("--control-port")
        .arg(control_port.to_string())
        .env("PYTHONUNBUFFERED", "1")
        .env(
            "WBEAM_OVERLAY_FONT_DESC",
            match config.hud_font_preset.as_str() {
                "dense" => "JetBrains Mono SemiBold 12",
                "arcade" => "IBM Plex Mono SemiBold 14",
                "system" => "Monospace Semi-Bold 13",
                _ => "JetBrains Mono SemiBold 13",
            },
        );
    if config.overlay {
        cmd.arg("--overlay");
    } else {
        cmd.arg("--no-overlay");
    }
}
