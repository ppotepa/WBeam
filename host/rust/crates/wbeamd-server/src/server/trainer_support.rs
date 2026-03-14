use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{SystemTime, UNIX_EPOCH};

use serde_json::Value;

use crate::*;

pub(crate) fn persist_trainer_run_artifacts(run: &TrainerRun) {
    let run_dir = PathBuf::from(&run.run_artifacts_dir);
    if fs::create_dir_all(&run_dir).is_err() {
        return;
    }
    let run_doc = serde_json::json!({
        "run_id": run.run_id,
        "status": run.status,
        "started_at_unix_ms": run.started_at_unix_ms,
        "finished_at_unix_ms": run.finished_at_unix_ms,
        "profile_name": run.profile_name,
        "serial": run.serial,
        "mode": run.mode,
        "engine": run.engine,
        "trials": run.trials,
        "warmup_sec": run.warmup_sec,
        "sample_sec": run.sample_sec,
        "log_path": run.log_path,
        "profile_dir": run.profile_dir,
        "run_artifacts_dir": run.run_artifacts_dir,
        "generations": run.generations,
        "population": run.population,
        "elite_count": run.elite_count,
        "mutation_rate": run.mutation_rate,
        "crossover_rate": run.crossover_rate,
        "bitrate_min_kbps": run.bitrate_min_kbps,
        "bitrate_max_kbps": run.bitrate_max_kbps,
        "encoder_mode": run.encoder_mode,
        "encoders": run.encoders,
        "exit_code": run.exit_code,
        "error": run.error,
    });
    let _ = fs::write(
        run_dir.join("run.json"),
        serde_json::to_vec_pretty(&run_doc).unwrap_or_default(),
    );

    let log_src = PathBuf::from(&run.log_path);
    if log_src.exists() {
        let _ = fs::copy(&log_src, run_dir.join("logs.txt"));
    }

    let profile_name = sanitize_profile_name(&run.profile_name);
    let profile_dir = PathBuf::from(&run.profile_dir);
    let profile_src = profile_dir.join(format!("{profile_name}.json"));
    let params_src = profile_dir.join("parameters.json");
    let preflight_src = profile_dir.join("preflight.json");
    if profile_src.exists() {
        let _ = fs::copy(profile_src, run_dir.join(format!("{profile_name}.json")));
    }
    if params_src.exists() {
        let _ = fs::copy(params_src, run_dir.join("parameters.json"));
    }
    if preflight_src.exists() {
        let _ = fs::copy(preflight_src, run_dir.join("preflight.json"));
    }
}

pub(crate) fn list_adb_devices(base_stream_port: u16) -> Vec<TrainerDeviceInfo> {
    let out = Command::new("adb").arg("devices").output();
    let Ok(output) = out else {
        return Vec::new();
    };
    let stdout = String::from_utf8_lossy(&output.stdout);
    let mut devices = Vec::new();
    let mut idx = 0usize;
    for line in stdout.lines().skip(1) {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        let parts: Vec<&str> = trimmed.split_whitespace().collect();
        if parts.len() < 2 {
            continue;
        }
        let serial = parts[0].to_string();
        let state = parts[1].to_string();
        let model = adb_shell_getprop(&serial, "ro.product.model");
        let api_level =
            adb_shell_getprop(&serial, "ro.build.version.sdk").and_then(|v| v.parse::<u32>().ok());
        let android_release = adb_shell_getprop(&serial, "ro.build.version.release");
        let stream_port = if state == "device" {
            Some(base_stream_port.saturating_add(idx as u16 + 1))
        } else {
            None
        };
        if state == "device" {
            idx += 1;
        }
        devices.push(TrainerDeviceInfo {
            serial,
            state,
            model,
            api_level,
            android_release,
            stream_port,
        });
    }
    devices
}

pub(crate) fn adb_shell_getprop(serial: &str, prop: &str) -> Option<String> {
    let out = Command::new("adb")
        .args(["-s", serial, "shell", "getprop", prop])
        .output()
        .ok()?;
    if !out.status.success() {
        return None;
    }
    let raw = String::from_utf8_lossy(&out.stdout).trim().to_string();
    if raw.is_empty() {
        None
    } else {
        Some(raw)
    }
}

pub(crate) fn now_unix_ms() -> u128 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0)
}

pub(crate) fn trainer_profile_root(root: &Path) -> PathBuf {
    root.join("config").join("training").join("profiles")
}

pub(crate) fn sanitize_profile_name(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    for ch in raw.trim().chars() {
        if ch.is_ascii_alphanumeric() || ch == '_' || ch == '-' || ch == '.' {
            out.push(ch);
        } else if ch.is_whitespace() {
            out.push('_');
        }
    }
    let out = out
        .trim_matches(|c: char| c == '_' || c == '-' || c == '.')
        .to_string();
    if out.is_empty() {
        "profile".to_string()
    } else {
        out
    }
}

pub(crate) fn session_suffix(raw: &str) -> String {
    let mut out = String::with_capacity(raw.len());
    for ch in raw.trim().chars() {
        if ch.is_ascii_alphanumeric() || ch == '_' || ch == '-' {
            out.push(ch);
        } else {
            out.push('_');
        }
    }
    let out = out.trim_matches('_').to_string();
    if out.is_empty() {
        "default".to_string()
    } else {
        out
    }
}

pub(crate) fn resolve_trainer_overlay_payload(
    serial: Option<&str>,
    stream_port: u16,
) -> (bool, Option<String>, Option<Value>) {
    if let Some(serial_val) = serial {
        let hud_text = read_trainer_overlay_text(serial_val, stream_port);
        let hud_json = read_trainer_overlay_json(serial_val, stream_port);
        let hud_active = trainer_overlay_marker_active(serial_val, stream_port)
            || hud_text.as_ref().is_some_and(|txt| !txt.trim().is_empty())
            || hud_json.is_some();
        return (hud_active, hud_text, hud_json);
    }

    if let Some(suffix) = resolve_active_trainer_suffix(stream_port) {
        let text_path = PathBuf::from(format!(
            "/tmp/wbeam-trainer-overlay-{}-{}.txt",
            suffix, stream_port
        ));
        let json_path = PathBuf::from(format!(
            "/tmp/wbeam-trainer-overlay-{}-{}.json",
            suffix, stream_port
        ));
        let hud_text = fs::read_to_string(&text_path)
            .ok()
            .map(|v| v.trim().to_string())
            .filter(|v| !v.is_empty());
        let hud_json = fs::read_to_string(&json_path)
            .ok()
            .and_then(|raw| serde_json::from_str::<Value>(&raw).ok());
        let hud_active = hud_text.is_some() || hud_json.is_some();
        return (hud_active, hud_text, hud_json);
    }

    (false, None, None)
}

pub(crate) async fn resolve_connection_mode(
    trainer: &TrainerState,
    serial: Option<&str>,
    stream_port: u16,
) -> &'static str {
    let runs = trainer.runs.lock().await;
    let is_training = runs.values().any(|run| {
        if run.status != "running" && run.status != "stopping" {
            return false;
        }
        let serial_ok = serial.map(|s| run.serial == s).unwrap_or(true);
        let port_ok = if stream_port > 0 {
            run.stream_port == stream_port
        } else {
            true
        };
        serial_ok && port_ok
    });
    if is_training {
        "training"
    } else {
        "live"
    }
}

pub(crate) fn resolve_active_trainer_suffix(stream_port: u16) -> Option<String> {
    let entries = fs::read_dir("/tmp").ok()?;
    let mut best: Option<(std::time::SystemTime, String)> = None;
    let tail = format!("-{}.flag", stream_port);
    for entry in entries.flatten() {
        let path = entry.path();
        let Some(file_name) = path.file_name().and_then(|v| v.to_str()) else {
            continue;
        };
        if !file_name.starts_with("wbeam-trainer-active-") || !file_name.ends_with(&tail) {
            continue;
        }
        let marker = file_name
            .strip_prefix("wbeam-trainer-active-")
            .and_then(|v| v.strip_suffix(&tail))
            .unwrap_or("")
            .trim();
        if marker.is_empty() {
            continue;
        }
        let modified = entry
            .metadata()
            .ok()
            .and_then(|m| m.modified().ok())
            .unwrap_or(std::time::SystemTime::UNIX_EPOCH);
        match &best {
            Some((prev, _)) if modified <= *prev => {}
            _ => best = Some((modified, marker.to_string())),
        }
    }
    best.map(|(_, suffix)| suffix)
}

pub(crate) fn read_trainer_overlay_text(serial: &str, stream_port: u16) -> Option<String> {
    if !trainer_overlay_marker_active(serial, stream_port) {
        return None;
    }
    let suffix = session_suffix(serial);
    let overlay = PathBuf::from(format!(
        "/tmp/wbeam-trainer-overlay-{}-{}.txt",
        suffix, stream_port
    ));
    let text = fs::read_to_string(overlay).ok()?;
    let trimmed = text.trim().to_string();
    if trimmed.is_empty() {
        None
    } else {
        Some(trimmed)
    }
}

pub(crate) fn read_trainer_overlay_json(serial: &str, stream_port: u16) -> Option<Value> {
    if !trainer_overlay_marker_active(serial, stream_port) {
        return None;
    }
    let suffix = session_suffix(serial);
    let overlay = PathBuf::from(format!(
        "/tmp/wbeam-trainer-overlay-{}-{}.json",
        suffix, stream_port
    ));
    let raw = fs::read_to_string(overlay).ok()?;
    serde_json::from_str::<Value>(&raw).ok()
}

pub(crate) fn trainer_overlay_marker_active(serial: &str, stream_port: u16) -> bool {
    let suffix = session_suffix(serial);
    PathBuf::from(format!(
        "/tmp/wbeam-trainer-active-{}-{}.flag",
        suffix, stream_port
    ))
    .exists()
}

pub(crate) fn live_snapshot_score(metrics: &Value) -> f64 {
    let kpi = metrics.get("kpi").cloned().unwrap_or(Value::Null);
    let present = kpi
        .get("present_fps")
        .and_then(|v| v.as_f64())
        .unwrap_or(0.0);
    let e2e_p95 = kpi
        .get("e2e_latency_ms_p95")
        .and_then(|v| v.as_f64())
        .unwrap_or(0.0);
    let drops = kpi.get("drop_rate").and_then(|v| v.as_f64()).unwrap_or(0.0);
    // Weighted utility for live snapshot ranking.
    ((present * 2.0) - (e2e_p95 * 0.08) - (drops * 120.0)).max(0.0)
}

pub(crate) fn read_json_value(path: &Path) -> Value {
    let Ok(raw) = fs::read_to_string(path) else {
        return Value::Null;
    };
    serde_json::from_str(&raw).unwrap_or(Value::Null)
}

pub(crate) fn list_trainer_profiles(root: &Path) -> Vec<TrainerProfileSummary> {
    let mut out = Vec::new();
    let entries = match fs::read_dir(root) {
        Ok(v) => v,
        Err(_) => return out,
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if !path.is_dir() {
            continue;
        }
        let Some(name) = path.file_name().and_then(|v| v.to_str()) else {
            continue;
        };
        let profile_name = sanitize_profile_name(name);
        let profile_path = path.join(format!("{profile_name}.json"));
        let params_path = path.join("parameters.json");
        let preflight_path = path.join("preflight.json");
        let params = read_json_value(&params_path);
        let profile = read_json_value(&profile_path);
        let best_score = params
            .get("best")
            .and_then(|v| v.get("score"))
            .and_then(|v| v.as_f64())
            .or_else(|| {
                profile
                    .get("profile")
                    .and_then(|v| v.get("origin"))
                    .and_then(|v| v.get("score"))
                    .and_then(|v| v.as_f64())
            });
        let engine = params
            .get("engine")
            .and_then(|v| v.as_str())
            .map(str::to_string)
            .or_else(|| {
                profile
                    .get("engine")
                    .and_then(|v| v.as_str())
                    .map(str::to_string)
            });
        let serial = params
            .get("serial")
            .and_then(|v| v.as_str())
            .map(str::to_string)
            .or_else(|| {
                profile
                    .get("device")
                    .and_then(|v| v.get("serial"))
                    .and_then(|v| v.as_str())
                    .map(str::to_string)
            });
        let updated_at_unix_ms = fs::metadata(&path)
            .ok()
            .and_then(|m| m.modified().ok())
            .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
            .map(|d| d.as_millis());
        out.push(TrainerProfileSummary {
            profile_name,
            path: path.to_string_lossy().to_string(),
            has_profile: profile_path.exists(),
            has_parameters: params_path.exists(),
            has_preflight: preflight_path.exists(),
            best_score,
            engine,
            serial,
            updated_at_unix_ms,
        });
    }
    out
}

pub(crate) fn adb_push_benchmark(serial: &str, size_mb: u32) -> Value {
    let size_mb = size_mb.clamp(1, 64);
    let bytes = (size_mb as usize) * 1024 * 1024;
    let tmp_path = std::env::temp_dir().join(format!(
        "wbeam-trainer-preflight-{}-{}.bin",
        serial,
        now_unix_ms()
    ));
    let remote_path = "/data/local/tmp/wbeam-trainer-preflight.bin";
    let write_ok = fs::write(&tmp_path, vec![0u8; bytes]).is_ok();
    if !write_ok {
        return serde_json::json!({
            "ok": false,
            "error": "failed to write temp benchmark file",
        });
    }
    let started = std::time::Instant::now();
    let out = Command::new("adb")
        .args(["-s", serial, "push"])
        .arg(&tmp_path)
        .arg(remote_path)
        .output();
    let elapsed = started.elapsed().as_secs_f64().max(0.001);
    let _ = Command::new("adb")
        .args(["-s", serial, "shell", "rm", "-f", remote_path])
        .status();
    let _ = fs::remove_file(&tmp_path);
    let output = match out {
        Ok(v) => v,
        Err(err) => {
            return serde_json::json!({
                "ok": false,
                "size_mb": size_mb,
                "error": format!("adb push failed to execute: {err}"),
            });
        }
    };
    let ok = output.status.success();
    let stdout = String::from_utf8_lossy(&output.stdout).to_string();
    let stderr = String::from_utf8_lossy(&output.stderr).to_string();
    let mut throughput = ((bytes as f64) / elapsed) / (1024.0 * 1024.0);
    if let Some((parsed_bytes, parsed_secs)) = parse_adb_push_bytes_and_secs(&stdout) {
        if parsed_secs > 0.0 {
            throughput = ((parsed_bytes as f64) / parsed_secs) / (1024.0 * 1024.0);
        }
    }
    serde_json::json!({
        "ok": ok,
        "size_mb": size_mb,
        "elapsed_sec": elapsed,
        "throughput_mb_s": throughput,
        "stdout": stdout.trim(),
        "stderr": stderr.trim(),
    })
}

fn parse_adb_push_bytes_and_secs(stdout: &str) -> Option<(u64, f64)> {
    let start = stdout.find('(')?;
    let end = stdout[start..].find(')')?;
    let inner = &stdout[start + 1..start + end];
    let mut bytes: Option<u64> = None;
    let mut secs: Option<f64> = None;
    for token in inner.split_whitespace() {
        if token == "bytes" {
            continue;
        }
        if token.ends_with('s') {
            let clean = token.trim_end_matches('s');
            if let Ok(v) = clean.parse::<f64>() {
                secs = Some(v);
            }
        } else if bytes.is_none() {
            if let Ok(v) = token.parse::<u64>() {
                bytes = Some(v);
            }
        }
    }
    Some((bytes?, secs?))
}

pub(crate) fn adb_shell_rtt_benchmark(serial: &str, loops: u32) -> Value {
    let loops = loops.clamp(1, 50);
    let mut samples = Vec::<f64>::new();
    let mut failures = 0u32;
    for _ in 0..loops {
        let started = std::time::Instant::now();
        let status = Command::new("adb")
            .args(["-s", serial, "shell", "true"])
            .status();
        let ms = started.elapsed().as_secs_f64() * 1000.0;
        match status {
            Ok(s) if s.success() => samples.push(ms),
            _ => failures += 1,
        }
    }
    if samples.is_empty() {
        return serde_json::json!({
            "ok": false,
            "loops": loops,
            "failures": failures,
            "error": "no successful adb shell samples",
        });
    }
    samples.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    let avg = samples.iter().sum::<f64>() / samples.len() as f64;
    let p50 = percentile_sorted(&samples, 0.5);
    let p95 = percentile_sorted(&samples, 0.95);
    serde_json::json!({
        "ok": true,
        "loops": loops,
        "failures": failures,
        "rtt_avg_ms": avg,
        "rtt_p50_ms": p50,
        "rtt_p95_ms": p95,
    })
}

fn percentile_sorted(sorted: &[f64], q: f64) -> f64 {
    if sorted.is_empty() {
        return 0.0;
    }
    let qq = q.clamp(0.0, 1.0);
    let idx = qq * ((sorted.len() - 1) as f64);
    let lo = idx.floor() as usize;
    let hi = idx.ceil() as usize;
    if lo == hi {
        return sorted[lo];
    }
    let frac = idx - lo as f64;
    sorted[lo] * (1.0 - frac) + sorted[hi] * frac
}
