use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{SystemTime, UNIX_EPOCH};

use regex::Regex;
use serde_json::{Map, Value};

use crate::models::{DeviceInfo, StreamStats, Wbh1Stats};

pub(crate) fn detect_runtime() -> (String, String) {
    let os_name = env::consts::OS.to_string();
    if os_name != "linux" {
        return (os_name, "n/a".to_string());
    }
    let mut session = env::var("XDG_SESSION_TYPE")
        .unwrap_or_default()
        .to_ascii_lowercase();
    if session != "wayland" && session != "x11" {
        session = if env::var_os("WAYLAND_DISPLAY").is_some() {
            "wayland".to_string()
        } else if env::var_os("DISPLAY").is_some() {
            "x11".to_string()
        } else {
            "unknown".to_string()
        };
    }
    (os_name, session)
}

pub(crate) fn detect_os_version() -> String {
    if env::consts::OS == "linux" {
        if let Ok(text) = fs::read_to_string("/etc/os-release") {
            for line in text.lines() {
                if let Some(v) = line.strip_prefix("PRETTY_NAME=") {
                    return v.trim_matches('"').to_string();
                }
            }
            let mut name = String::new();
            let mut version = String::new();
            for line in text.lines() {
                if let Some(v) = line.strip_prefix("NAME=") {
                    name = v.trim_matches('"').to_string();
                }
                if let Some(v) = line.strip_prefix("VERSION=") {
                    version = v.trim_matches('"').to_string();
                }
            }
            if !name.is_empty() && !version.is_empty() {
                return format!("{name} {version}");
            }
            if !name.is_empty() {
                return name;
            }
        }
    }
    env::var("OS").unwrap_or_else(|_| env::consts::OS.to_string())
}

pub(crate) fn hostname() -> String {
    env::var("HOSTNAME").unwrap_or_else(|_| "unknown".to_string())
}

pub(crate) fn now_string() -> String {
    let secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    secs.to_string()
}

pub(crate) fn locate_proto_root() -> anyhow::Result<PathBuf> {
    fn search_from(start: &Path) -> Option<PathBuf> {
        for dir in start.ancestors() {
            let proto_in_repo = dir.join("proto/config/proto.json");
            if proto_in_repo.exists() {
                return Some(dir.join("proto"));
            }
            let proto_local = dir.join("config/proto.json");
            if proto_local.exists() {
                return Some(dir.to_path_buf());
            }
        }
        None
    }

    let manifest_dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    if let Some(found) = search_from(&manifest_dir) {
        return Ok(found);
    }
    if let Ok(cwd) = env::current_dir() {
        if let Some(found) = search_from(&cwd) {
            return Ok(found);
        }
    }
    Err(anyhow::anyhow!(
        "could not locate proto root; expected either <repo>/proto/config/proto.json or <cwd>/config/proto.json"
    ))
}

pub(crate) fn read_adb_devices() -> anyhow::Result<Vec<DeviceInfo>> {
    if which("adb").is_none() {
        return Ok(Vec::new());
    }
    let output = Command::new("adb").arg("devices").arg("-l").output()?;
    if !output.status.success() {
        return Ok(Vec::new());
    }
    let text = String::from_utf8_lossy(&output.stdout);
    let mut out = Vec::new();
    for line in text.lines() {
        let raw = line.trim();
        if raw.is_empty()
            || raw.starts_with("List of devices attached")
            || raw.starts_with("* daemon")
        {
            continue;
        }
        let parts: Vec<&str> = raw.split_whitespace().collect();
        if parts.len() < 2 {
            continue;
        }
        let serial = parts[0].to_string();
        let state = parts[1].to_string();
        let mut model = "-".to_string();
        let mut transport = "-".to_string();
        for token in &parts[2..] {
            if let Some(v) = token.strip_prefix("model:") {
                model = v.to_string();
            }
            if let Some(v) = token.strip_prefix("transport_id:") {
                transport = v.to_string();
            }
        }
        out.push(DeviceInfo {
            serial,
            state,
            model,
            transport,
        });
    }
    Ok(out)
}

pub(crate) fn adb_available() -> bool {
    which("adb").is_some()
}

pub(crate) fn read_stream_stats(
    runner_log: &Path,
    portal_log: &Path,
    kms_log: &Path,
    android_log: &Path,
) -> StreamStats {
    if let Ok(text) = fs::read_to_string(runner_log) {
        let wbh1 = parse_wbh1_stats(&text);
        if let Some(mut stats) = parse_portal_stats(&text, runner_log) {
            stats.wbh1 = wbh1;
            return stats;
        }
        if let Some(w) = wbh1 {
            return StreamStats {
                source: runner_log.display().to_string(),
                seq: w.seq.clone(),
                wbh1: Some(w),
                ..StreamStats::default()
            };
        }
    }

    if let Ok(text) = fs::read_to_string(portal_log) {
        let wbh1 = parse_wbh1_stats(&text);
        if let Some(mut stats) = parse_portal_stats(&text, portal_log) {
            stats.wbh1 = wbh1;
            return stats;
        }
        if let Some(w) = wbh1 {
            return StreamStats {
                source: portal_log.display().to_string(),
                seq: w.seq.clone(),
                wbh1: Some(w),
                ..StreamStats::default()
            };
        }
    }

    if kms_log.exists() {
        return StreamStats {
            source: format!("kms log: {}", kms_log.display()),
            ..StreamStats::default()
        };
    }
    if android_log.exists() {
        return StreamStats {
            source: format!("android log: {}", android_log.display()),
            ..StreamStats::default()
        };
    }
    StreamStats {
        source: "no samples".to_string(),
        ..StreamStats::default()
    }
}

pub(crate) fn read_json(path: &Path) -> anyhow::Result<Value> {
    let text = fs::read_to_string(path)?;
    let value = serde_json::from_str::<Value>(&text)?;
    Ok(value)
}

pub(crate) fn write_json(path: &Path, value: &Value) -> anyhow::Result<()> {
    let mut out = value.clone();
    if !out.is_object() {
        out = Value::Object(Map::new());
    }
    let payload = serde_json::to_string_pretty(&out)?;
    fs::write(path, format!("{payload}\n"))?;
    Ok(())
}

fn parse_portal_stats(text: &str, source_path: &Path) -> Option<StreamStats> {
    let re = Regex::new(
        r"pipeline_fps=([0-9.]+)\s+sender_fps=([0-9.]+)\s+timeout_misses=(\d+)\s+stale_dupe=(\d+).*?\sseq=(\d+)",
    )
    .ok()?;
    let captures = re.captures_iter(text).last()?;
    Some(StreamStats {
        pipeline_fps: captures.get(1)?.as_str().to_string(),
        sender_fps: captures.get(2)?.as_str().to_string(),
        timeout_misses: captures.get(3)?.as_str().to_string(),
        stale_dupe: captures.get(4)?.as_str().to_string(),
        seq: captures.get(5)?.as_str().to_string(),
        source: source_path.display().to_string(),
        wbh1: None,
    })
}

fn parse_wbh1_stats(text: &str) -> Option<Wbh1Stats> {
    let re = Regex::new(
        r"WBH1 stats:\s+units=(\d+)\s+fps=([0-9.]+)\s+mbps=([0-9.]+)\s+avg_kb=(\d+)\s+min_kb=(\d+)\s+max_kb=(\d+)\s+key_pct=(\d+)\s+lat_ms=([0-9.]+)\s+lat_max_ms=([0-9.]+)\s+seq=(\d+)",
    )
    .ok()?;
    let captures = re.captures_iter(text).last()?;
    Some(Wbh1Stats {
        units: captures.get(1)?.as_str().to_string(),
        fps: captures.get(2)?.as_str().to_string(),
        mbps: captures.get(3)?.as_str().to_string(),
        avg_kb: captures.get(4)?.as_str().to_string(),
        min_kb: captures.get(5)?.as_str().to_string(),
        max_kb: captures.get(6)?.as_str().to_string(),
        key_pct: captures.get(7)?.as_str().to_string(),
        lat_ms: captures.get(8)?.as_str().to_string(),
        lat_max_ms: captures.get(9)?.as_str().to_string(),
        seq: captures.get(10)?.as_str().to_string(),
    })
}

fn which(bin: &str) -> Option<PathBuf> {
    let paths = env::var_os("PATH")?;
    env::split_paths(&paths)
        .map(|p| p.join(bin))
        .find(|candidate| candidate.exists())
}
