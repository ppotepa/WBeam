use std::path::Path;
use std::process::{Command, Stdio};
use std::time::{Duration, Instant};

#[derive(Debug, Clone)]
pub struct VirtualDisplayHandle {
    pub pid: u32,
    pub display: String,
}

pub fn has_xvfb() -> bool {
    command_exists("Xvfb")
}

pub fn install_hint() -> String {
    "Install Xvfb: Debian/Ubuntu `sudo apt install xvfb`, Fedora `sudo dnf install xorg-x11-server-Xvfb`, Arch `sudo pacman -S xorg-server-xvfb`".to_string()
}

pub fn spawn_xvfb_for_serial(serial: &str, size: &str) -> Result<VirtualDisplayHandle, String> {
    if !has_xvfb() {
        return Err(
            "Xvfb binary not found on host (required for virtual desktop mode)".to_string(),
        );
    }
    let (width, height) = parse_size(size);
    let base = 100 + (stable_hash(serial) % 200) as u16;

    for offset in 0..80u16 {
        let num = base.saturating_add(offset);
        if display_in_use(num) {
            continue;
        }
        let display = format!(":{num}");
        let mut cmd = Command::new("Xvfb");
        cmd.arg(&display)
            .arg("-screen")
            .arg("0")
            .arg(format!("{width}x{height}x24"))
            .arg("-nolisten")
            .arg("tcp")
            .arg("-ac")
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null());

        let child = cmd
            .spawn()
            .map_err(|e| format!("failed to start Xvfb on {display}: {e}"))?;
        let pid = child.id();
        drop(child);

        if wait_for_display_socket(num, pid, Duration::from_secs(2)) {
            return Ok(VirtualDisplayHandle { pid, display });
        }

        let _ = Command::new("kill")
            .args(["-TERM", &pid.to_string()])
            .status();
    }

    Err("failed to allocate free Xvfb display number".to_string())
}

fn parse_size(raw: &str) -> (u32, u32) {
    let mut parts = raw.split('x');
    let w = parts
        .next()
        .and_then(|v| v.parse::<u32>().ok())
        .unwrap_or(1280);
    let h = parts
        .next()
        .and_then(|v| v.parse::<u32>().ok())
        .unwrap_or(720);
    (w.max(320), h.max(240))
}

fn stable_hash(input: &str) -> u32 {
    let mut h: u32 = 2166136261;
    for b in input.as_bytes() {
        h ^= *b as u32;
        h = h.wrapping_mul(16777619);
    }
    h
}

fn wait_for_display_socket(display_num: u16, pid: u32, timeout: Duration) -> bool {
    let socket = format!("/tmp/.X11-unix/X{display_num}");
    let start = Instant::now();
    while start.elapsed() < timeout {
        if Path::new(&socket).exists() && process_exists(pid) {
            return true;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    false
}

fn display_in_use(display_num: u16) -> bool {
    Path::new(&format!("/tmp/.X11-unix/X{display_num}")).exists()
        || Path::new(&format!("/tmp/.X{display_num}-lock")).exists()
}

fn process_exists(pid: u32) -> bool {
    Command::new("kill")
        .args(["-0", &pid.to_string()])
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

fn command_exists(name: &str) -> bool {
    Command::new("sh")
        .args(["-c", &format!("command -v {name} >/dev/null 2>&1")])
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}
