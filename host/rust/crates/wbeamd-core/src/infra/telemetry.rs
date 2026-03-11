//! Telemetry file helpers (JSONL export).
//!
//! Opens a per-run telemetry file in `~/.local/share/wbeam/telemetry/`
//! (or `$XDG_DATA_HOME/wbeam/telemetry/`).  Each run appends one JSONL
//! record per `ClientMetricsRequest` received by the daemon.

use std::fs::{File, OpenOptions};
use std::io::Write;
use std::path::PathBuf;

/// Return the base telemetry directory for this user.
pub fn telemetry_dir() -> PathBuf {
    if let Ok(xdg) = std::env::var("XDG_DATA_HOME") {
        PathBuf::from(xdg).join("wbeam").join("telemetry")
    } else if let Ok(home) = std::env::var("HOME") {
        PathBuf::from(home)
            .join(".local")
            .join("share")
            .join("wbeam")
            .join("telemetry")
    } else {
        PathBuf::from("/tmp/wbeam/telemetry")
    }
}

/// Open (or create) a JSONL telemetry file for `run_id`.
///
/// The file is named `run_<run_id>.jsonl` inside `telemetry_dir()`.
/// Returns `None` if the directory cannot be created or the file cannot be
/// opened.
pub fn open_telemetry_file(run_id: u64) -> Option<File> {
    let dir = telemetry_dir();
    std::fs::create_dir_all(&dir).ok()?;
    OpenOptions::new()
        .create(true)
        .append(true)
        .open(dir.join(format!("run_{run_id}.jsonl")))
        .ok()
}

/// Append a single JSONL record to `file`, silently ignoring write errors.
pub fn append_telemetry_record(file: &mut File, record: &impl serde::Serialize) {
    if let Ok(json) = serde_json::to_string(record) {
        let _ = writeln!(file, "{json}");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn telemetry_dir_contains_wbeam() {
        let dir = telemetry_dir();
        assert!(dir.to_str().unwrap().contains("wbeam"));
    }
}
