use std::collections::HashSet;
use std::process::Command;

use serde_json::Value;

#[derive(Debug, Clone)]
pub(crate) struct KscreenOutput {
    pub(crate) name: String,
    pub(crate) enabled: bool,
    pub(crate) connected: bool,
    pub(crate) replication_source: i64,
    pub(crate) x: i32,
    pub(crate) y: i32,
    pub(crate) width: i32,
    pub(crate) height: i32,
}

pub(crate) fn command_exists(name: &str) -> bool {
    Command::new("sh")
        .args(["-c", &format!("command -v {name} >/dev/null 2>&1")])
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

pub(crate) fn kscreen_enabled_output_names() -> Result<HashSet<String>, String> {
    let outputs = kscreen_query_outputs()?;
    Ok(outputs
        .into_iter()
        .filter(output_ready_for_layout)
        .map(|o| o.name)
        .collect())
}

pub(crate) fn kscreen_query_outputs() -> Result<Vec<KscreenOutput>, String> {
    let output = Command::new("kscreen-doctor")
        .arg("-j")
        .output()
        .map_err(|e| format!("failed to execute kscreen-doctor -j: {e}"))?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
        return Err(if stderr.is_empty() {
            format!("kscreen-doctor -j failed with status {}", output.status)
        } else {
            format!("kscreen-doctor -j failed: {stderr}")
        });
    }
    let root: Value = serde_json::from_slice(&output.stdout)
        .map_err(|e| format!("failed to parse kscreen-doctor json: {e}"))?;
    let Some(outputs) = root.get("outputs").and_then(|v| v.as_array()) else {
        return Ok(Vec::new());
    };

    let mut parsed = Vec::new();
    for item in outputs {
        let Some(name) = item.get("name").and_then(|v| v.as_str()) else {
            continue;
        };
        let x = item
            .get("pos")
            .and_then(|v| v.get("x"))
            .and_then(|v| v.as_i64())
            .and_then(|v| i32::try_from(v).ok())
            .unwrap_or(0);
        let y = item
            .get("pos")
            .and_then(|v| v.get("y"))
            .and_then(|v| v.as_i64())
            .and_then(|v| i32::try_from(v).ok())
            .unwrap_or(0);
        let width = item
            .get("size")
            .and_then(|v| v.get("width"))
            .and_then(|v| v.as_i64())
            .and_then(|v| i32::try_from(v).ok())
            .unwrap_or(-1);
        let height = item
            .get("size")
            .and_then(|v| v.get("height"))
            .and_then(|v| v.as_i64())
            .and_then(|v| i32::try_from(v).ok())
            .unwrap_or(-1);
        let replication_source = item
            .get("replicationSource")
            .and_then(|v| v.as_i64())
            .unwrap_or(0);
        parsed.push(KscreenOutput {
            name: name.to_string(),
            enabled: item
                .get("enabled")
                .and_then(|v| v.as_bool())
                .unwrap_or(false),
            connected: item
                .get("connected")
                .and_then(|v| v.as_bool())
                .unwrap_or(false),
            replication_source,
            x,
            y,
            width,
            height,
        });
    }
    Ok(parsed)
}

pub(crate) fn output_ready_for_layout(output: &KscreenOutput) -> bool {
    output.enabled
        && output.connected
        && output.replication_source == 0
        && output.width > 0
        && output.height > 0
}

pub(crate) fn output_name_looks_virtual(name: &str) -> bool {
    let low = name.to_ascii_lowercase();
    low.contains("virtual")
        || low.contains("wbeam")
        || low.contains("headless")
        || low.contains("dummy")
        || low.contains("evdi")
        || low.starts_with("dvi-")
}

pub(crate) fn build_non_overlapping_layout_commands(
    outputs: &[KscreenOutput],
    mapped: Option<&HashSet<String>>,
) -> Vec<String> {
    let managed = outputs
        .iter()
        .filter(|o| output_ready_for_layout(o))
        .filter(|o| {
            if let Some(names) = mapped {
                if !names.is_empty() {
                    return names.contains(&o.name);
                }
            }
            output_name_looks_virtual(&o.name)
        })
        .cloned()
        .collect::<Vec<_>>();
    if managed.len() < 2 {
        return Vec::new();
    }
    if !has_overlap(&managed) {
        return Vec::new();
    }

    let managed_names: HashSet<String> = managed.iter().map(|o| o.name.clone()).collect();
    let mut anchor_x = outputs
        .iter()
        .filter(|o| output_ready_for_layout(o))
        .filter(|o| !managed_names.contains(&o.name))
        .map(|o| o.x.saturating_add(o.width.max(320)))
        .max()
        .unwrap_or(0);
    if anchor_x < 0 {
        anchor_x = 0;
    }

    let mut ordered = managed;
    ordered.sort_by(|a, b| {
        a.x.cmp(&b.x)
            .then_with(|| a.y.cmp(&b.y))
            .then_with(|| a.name.cmp(&b.name))
    });

    let mut commands = Vec::new();
    let mut x = anchor_x;
    for output in ordered {
        commands.push(format!("output.{}.position.{},{}", output.name, x, 0));
        x = x.saturating_add(output.width.max(320));
    }
    commands
}

fn has_overlap(outputs: &[KscreenOutput]) -> bool {
    for (idx, left) in outputs.iter().enumerate() {
        for right in outputs.iter().skip(idx + 1) {
            if rects_overlap(left, right) {
                return true;
            }
        }
    }
    false
}

fn rects_overlap(a: &KscreenOutput, b: &KscreenOutput) -> bool {
    let aw = a.width.max(1);
    let ah = a.height.max(1);
    let bw = b.width.max(1);
    let bh = b.height.max(1);
    let ax2 = a.x.saturating_add(aw);
    let ay2 = a.y.saturating_add(ah);
    let bx2 = b.x.saturating_add(bw);
    let by2 = b.y.saturating_add(bh);
    a.x < bx2 && ax2 > b.x && a.y < by2 && ay2 > b.y
}

pub(crate) fn apply_kscreen_layout(commands: &[String]) -> Result<(), String> {
    if commands.is_empty() {
        return Ok(());
    }
    let status = Command::new("kscreen-doctor")
        .args(commands)
        .status()
        .map_err(|e| format!("failed to execute kscreen-doctor layout: {e}"))?;
    if status.success() {
        Ok(())
    } else {
        Err(format!("kscreen-doctor layout failed with status {status}"))
    }
}
