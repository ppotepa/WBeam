//! Stream-process lifecycle management.
//!
//! Handles spawning and terminating the `wbeamd-streamer` binary (or the
//! legacy Python fallback), monitoring stdout/stderr and waiting for the
//! child to exit.

use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::time::Duration;

use nix::sys::signal::{kill, Signal};
use nix::unistd::Pid;
use tokio::process::Command;
use tokio::time::sleep;
use wbeamd_api::ActiveConfig;

/// Build the command to launch the streamer for `cfg`.
///
/// Prefers the compiled Rust binary at `<root>/src/host/rust/target/release/wbeamd-streamer`.
/// Falls back to the Python helper when the binary is absent.
pub fn build_streamer_command(
    root: &Path,
    cfg: &ActiveConfig,
    stream_port: u16,
) -> (Command, bool) {
    let rust_bin = root.join("src/host/rust/target/release/wbeamd-streamer");
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
        let script = root.join("src/host/scripts/stream_wayland_portal_h264.py");
        let mut c = Command::new("python3");
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
    let p = Pid::from_raw(pid as i32);
    let _ = kill(p, Signal::SIGTERM);
    sleep(Duration::from_millis(300)).await;
    let _ = kill(p, Signal::SIGKILL);
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

/// Return a build revision string (injected via `WBEAM_BUILD_REV` env var at
/// compile time, or a default placeholder).
pub fn build_revision() -> String {
    option_env!("WBEAM_BUILD_REV")
        .unwrap_or("0.0.dev0-build")
        .to_string()
}

/// Resolve the path to the Rust streamer binary.
pub fn rust_streamer_bin(root: &Path) -> PathBuf {
    root.join("src/host/rust/target/release/wbeamd-streamer")
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
}
