use std::io::Read;
use std::process::{Child, Command, Output, Stdio};
use std::sync::mpsc;
use std::thread;
use std::time::{Duration, Instant};

#[cfg(unix)]
use std::os::unix::process::CommandExt;

// An external CLI may fork and leave children holding stdout/stderr pipes.
// Give each invocation a distinct Unix process group so a timeout kills its
// descendants, not only the direct child process. On other platforms, the
// direct child is killed; process-tree termination needs a platform-specific
// implementation.
fn terminate_child(child: &mut Child) {
    #[cfg(unix)]
    if let Ok(pgid) = i32::try_from(child.id()) {
        // SAFETY: this child was spawned in a new process group with PGID equal
        // to its PID. A negative PID targets only that process group.
        unsafe {
            libc::kill(-pgid, libc::SIGKILL);
        }
    }
    let _ = child.kill();
    let _ = child.wait();
}

// A JavaScript Promise timeout cannot cancel a native Tauri task. Enforce a
// total native deadline for both process exit AND pipe draining. Detached
// readers prevent blocking forever when an unrelated descendant inherited a
// pipe; the underlying child group is killed on process timeout.
pub(crate) fn command_output_timeout(
    command: &mut Command,
    timeout: Duration,
    label: &str,
) -> Result<Output, String> {
    #[cfg(unix)]
    command.process_group(0);
    let mut child = command
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|e| format!("{label} failed: {e}"))?;

    let mut stdout_pipe = child.stdout.take().ok_or_else(|| "missing stdout pipe".to_string())?;
    let mut stderr_pipe = child.stderr.take().ok_or_else(|| "missing stderr pipe".to_string())?;
    let (out_tx, out_rx) = mpsc::channel();
    let (err_tx, err_rx) = mpsc::channel();
    thread::spawn(move || {
        let mut bytes = Vec::new();
        let _ = stdout_pipe.read_to_end(&mut bytes);
        let _ = out_tx.send(bytes);
    });
    thread::spawn(move || {
        let mut bytes = Vec::new();
        let _ = stderr_pipe.read_to_end(&mut bytes);
        let _ = err_tx.send(bytes);
    });

    let start = Instant::now();
    let deadline = start + timeout;
    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break status,
            Ok(None) if Instant::now() >= deadline => {
                terminate_child(&mut child);
                return Err(format!("{label} timeout after {} ms", timeout.as_millis()));
            }
            Ok(None) => thread::sleep(Duration::from_millis(20)),
            Err(err) => {
                terminate_child(&mut child);
                return Err(format!("{label} status failed: {err}"));
            }
        }
    };

    let stdout = out_rx
        .recv_timeout(deadline.saturating_duration_since(Instant::now()))
        .map_err(|_| format!("{label} output pipes remained open after process exit"))?;
    let stderr = err_rx
        .recv_timeout(deadline.saturating_duration_since(Instant::now()))
        .map_err(|_| format!("{label} error pipes remained open after process exit"))?;
    Ok(Output { status, stdout, stderr })
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;

    #[test]
    fn captures_stdout_stderr() {
        let mut cmd = Command::new("sh");
        cmd.args(["-c", "printf out; printf err >&2"]);
        let out = command_output_timeout(&mut cmd, Duration::from_secs(2), "test").unwrap();
        assert_eq!(out.stdout, b"out");
        assert_eq!(out.stderr, b"err");
    }

    #[test]
    fn kills_hung_process() {
        let mut cmd = Command::new("sleep");
        cmd.arg("3");
        let start = Instant::now();
        let err = command_output_timeout(&mut cmd, Duration::from_millis(100), "test").unwrap_err();
        assert!(err.contains("timeout"));
        assert!(start.elapsed() < Duration::from_secs(2));
    }

    #[test]
    fn kills_shell_with_a_background_child_on_timeout() {
        let mut cmd = Command::new("sh");
        cmd.args(["-c", "sleep 3 & wait"]);
        let start = Instant::now();
        let err = command_output_timeout(&mut cmd, Duration::from_millis(150), "test").unwrap_err();
        assert!(err.contains("timeout"));
        assert!(start.elapsed() < Duration::from_secs(2));
    }

    #[test]
    fn does_not_wait_forever_for_an_orphaned_stdout_pipe() {
        let mut cmd = Command::new("sh");
        cmd.args(["-c", "sleep 3 & exit 0"]);
        let start = Instant::now();
        let err = command_output_timeout(&mut cmd, Duration::from_millis(200), "test").unwrap_err();
        assert!(err.contains("pipes remained open"));
        assert!(start.elapsed() < Duration::from_secs(2));
    }
}
