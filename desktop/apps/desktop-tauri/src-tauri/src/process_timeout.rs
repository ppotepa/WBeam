use std::io::Read;
use std::process::{Command, Output, Stdio};
use std::thread;
use std::time::{Duration, Instant};

// The timeout must be enforced by the native worker, not by a frontend
// Promise.race. Reader threads drain pipes to avoid child pipe-buffer stalls.
pub(crate) fn command_output_timeout(
    command: &mut Command,
    timeout: Duration,
    label: &str,
) -> Result<Output, String> {
    let mut child = command
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|e| format!("{label} failed: {e}"))?;

    let mut stdout_pipe = child.stdout.take().ok_or_else(|| "missing stdout pipe".to_string())?;
    let mut stderr_pipe = child.stderr.take().ok_or_else(|| "missing stderr pipe".to_string())?;
    let out_reader = thread::spawn(move || {
        let mut bytes = Vec::new();
        let _ = stdout_pipe.read_to_end(&mut bytes);
        bytes
    });
    let err_reader = thread::spawn(move || {
        let mut bytes = Vec::new();
        let _ = stderr_pipe.read_to_end(&mut bytes);
        bytes
    });

    let start = Instant::now();
    let (status, timed_out) = loop {
        match child.try_wait() {
            Ok(Some(status)) => break (status, false),
            Ok(None) if start.elapsed() >= timeout => {
                let _ = child.kill();
                let status = child.wait().map_err(|e| format!("{label} wait failed: {e}"))?;
                break (status, true);
            }
            Ok(None) => thread::sleep(Duration::from_millis(20)),
            Err(err) => {
                let _ = child.kill();
                let _ = child.wait();
                let _ = out_reader.join();
                let _ = err_reader.join();
                return Err(format!("{label} status failed: {err}"));
            }
        }
    };
    let stdout = out_reader.join().map_err(|_| format!("{label} stdout reader panicked"))?;
    let stderr = err_reader.join().map_err(|_| format!("{label} stderr reader panicked"))?;
    if timed_out {
        return Err(format!("{label} timeout after {} ms", timeout.as_millis()));
    }
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
        let err = command_output_timeout(&mut cmd, Duration::from_millis(100), "test").unwrap_err();
        assert!(err.contains("timeout"));
    }
}
