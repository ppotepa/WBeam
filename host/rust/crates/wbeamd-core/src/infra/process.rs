//! Stream-process lifecycle management.
//!
//! Handles spawning and terminating the `wbeamd-streamer` binary (or the
//! legacy Python fallback), monitoring stdout/stderr and waiting for the
//! child to exit.

use std::path::{Path, PathBuf};
#[cfg(not(unix))]
use std::process::Command as StdCommand;
use std::process::Stdio;
use std::time::Duration;

#[cfg(unix)]
use nix::sys::signal::{kill, Signal};
#[cfg(unix)]
use nix::unistd::Pid;
use tokio::process::Command;
use tokio::time::sleep;
use wbeamd_api::ActiveConfig;
use wbeamd_api::TransportRuntimeSnapshot;

/// Build the command to launch the streamer for `cfg`.
///
/// Prefers the compiled Rust binary at `<root>/host/rust/target/release/wbeamd-streamer`.
/// Falls back to the Python helper when the binary is absent.
pub fn build_streamer_command(
    root: &Path,
    cfg: &ActiveConfig,
    stream_port: u16,
) -> (Command, bool) {
    #[cfg(windows)]
    let rust_bin = root.join("host/rust/target/release/wbeamd-streamer.exe");
    #[cfg(not(windows))]
    let rust_bin = root.join("host/rust/target/release/wbeamd-streamer");
    let use_rust = rust_bin.exists();

    let mut cmd = if use_rust {
        let mut c = Command::new(&rust_bin);
        c.arg("--profile")
            .arg(&cfg.profile)
            .arg("--port")
            .arg(stream_port.to_string())
            .arg("--encoder")
            .arg(&cfg.encoder)
            .arg("--cursor-mode")
            .arg(&cfg.cursor_mode)
            .arg("--size")
            .arg(&cfg.size)
            .arg("--fps")
            .arg(cfg.fps.to_string())
            .arg("--bitrate-kbps")
            .arg(cfg.bitrate_kbps.to_string())
            .arg("--debug-fps")
            .arg(cfg.debug_fps.to_string());
        if cfg.intra_only {
            c.arg("--intra-only");
        }
        c
    } else {
        let script = root.join("host/scripts/stream_wayland_portal_h264.py");
        let mut c = if cfg!(windows) {
            let mut py = Command::new("py");
            py.arg("-3");
            py
        } else {
            Command::new("python3")
        };
        c.arg("-u")
            .arg(script)
            .arg("--profile")
            .arg(&cfg.profile)
            .arg("--port")
            .arg(stream_port.to_string())
            .arg("--encoder")
            .arg(&cfg.encoder)
            .arg("--cursor-mode")
            .arg(&cfg.cursor_mode)
            .arg("--size")
            .arg(&cfg.size)
            .arg("--fps")
            .arg(cfg.fps.to_string())
            .arg("--bitrate-kbps")
            .arg(cfg.bitrate_kbps.to_string())
            .arg("--debug-dir")
            .arg("/tmp/wbeam-frames")
            .arg("--debug-fps")
            .arg(cfg.debug_fps.to_string())
            .arg("--framed")
            .env("PYTHONUNBUFFERED", "1");
        c
    };

    cmd.stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    (cmd, use_rust)
}

/// Send SIGTERM then SIGKILL to `pid` with a short delay in between.
pub async fn terminate_pid(pid: u32) {
    #[cfg(unix)]
    {
        let p = Pid::from_raw(pid as i32);
        let _ = kill(p, Signal::SIGTERM);
        sleep(Duration::from_millis(300)).await;
        let _ = kill(p, Signal::SIGKILL);
    }
    #[cfg(not(unix))]
    {
        let pid_s = pid.to_string();
        let _ = StdCommand::new("taskkill")
            .args(["/PID", &pid_s, "/T"])
            .status();
        sleep(Duration::from_millis(300)).await;
        let _ = StdCommand::new("taskkill")
            .args(["/PID", &pid_s, "/T", "/F"])
            .status();
    }
}

/// Parse a GStreamer/libx264 bitrate output line such as
/// `[libx264 @ 0x…] kb/s:58.61` and return bits per second.
pub fn parse_kbps_line_to_bps(line: &str) -> Option<u64> {
    let marker = "kb/s:";
    let idx = line.find(marker)?;
    let part = line[idx + marker.len()..].trim();
    let value = part.split_whitespace().next()?;
    let kbps: f64 = value.parse().ok()?;
    Some((kbps * 1000.0) as u64)
}

/// Parse streamer transport line:
/// `[wbeam-framed] pipeline_fps=.. sender_fps=.. ... queue_depth=.. queue_peak=.. queue_drops=.. seq=..`
pub fn parse_transport_runtime_line(line: &str) -> Option<TransportRuntimeSnapshot> {
    if !line.contains("[wbeam-framed]") || !line.contains("pipeline_fps=") {
        return None;
    }
    let mut out = TransportRuntimeSnapshot::default();
    for token in line.split_whitespace() {
        let Some((key, value)) = token.split_once('=') else {
            continue;
        };
        match key {
            "pipeline_fps" => out.pipeline_fps = value.parse().ok()?,
            "sender_fps" => out.sender_fps = value.parse().ok()?,
            "timeout_misses" => out.timeout_misses = value.parse().ok()?,
            "send_timeouts" => out.send_timeouts = value.parse().ok()?,
            "timeout_key" => out.timeout_key = value.parse().ok()?,
            "timeout_delta" => out.timeout_delta = value.parse().ok()?,
            "key_retry_ok" => out.key_retry_ok = value.parse().ok()?,
            "key_retry_fail" => out.key_retry_fail = value.parse().ok()?,
            "queue_depth" => out.queue_depth = value.parse().ok()?,
            "queue_peak" => out.queue_peak = value.parse().ok()?,
            "queue_drops" => out.queue_drops = value.parse().ok()?,
            "seq" => out.seq = value.parse().ok()?,
            _ => {}
        }
    }
    Some(out)
}

/// Return a build revision string (injected via `WBEAM_BUILD_REV` env var at
/// compile time, or a default placeholder).
pub fn build_revision() -> String {
    if let Ok(runtime) = std::env::var("WBEAM_BUILD_REV") {
        let trimmed = runtime.trim();
        if !trimmed.is_empty() {
            return trimmed.to_string();
        }
    }

    if let Ok(root) = std::env::var("WBEAM_ROOT") {
        let path = Path::new(&root).join(".wbeam_build_version");
        if let Ok(content) = std::fs::read_to_string(path) {
            let trimmed = content.trim();
            if !trimmed.is_empty() {
                return trimmed.to_string();
            }
        }
    }

    option_env!("WBEAM_BUILD_REV")
        .unwrap_or("0.1.0.0.dev")
        .to_string()
}

/// Resolve the path to the Rust streamer binary.
pub fn rust_streamer_bin(root: &Path) -> PathBuf {
    #[cfg(windows)]
    {
        return root.join("host/rust/target/release/wbeamd-streamer.exe");
    }
    #[cfg(not(windows))]
    root.join("host/rust/target/release/wbeamd-streamer")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_kbps_known_line() {
        let line = "[libx264 @ 0xdeadbeef] kb/s:58.61";
        assert_eq!(parse_kbps_line_to_bps(line), Some(58610));
    }

    #[test]
    fn parse_kbps_no_match() {
        assert_eq!(parse_kbps_line_to_bps("unrelated log line"), None);
    }

    #[test]
    fn parse_transport_line_known() {
        let line = "[wbeam-framed] pipeline_fps=59 sender_fps=58.8 timeout_misses=1 send_timeouts=2 timeout_key=0 timeout_delta=2 key_retry_ok=0 key_retry_fail=0 queue_depth=1 queue_peak=2 queue_drops=3 seq=42";
        let parsed = parse_transport_runtime_line(line).expect("transport line should parse");
        assert_eq!(parsed.pipeline_fps, 59);
        assert_eq!(parsed.sender_fps, 58.8);
        assert_eq!(parsed.timeout_misses, 1);
        assert_eq!(parsed.send_timeouts, 2);
        assert_eq!(parsed.queue_depth, 1);
        assert_eq!(parsed.queue_peak, 2);
        assert_eq!(parsed.queue_drops, 3);
        assert_eq!(parsed.seq, 42);
    }
}
