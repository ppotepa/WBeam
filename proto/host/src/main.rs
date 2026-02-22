use std::sync::{Arc, OnceLock, RwLock};
use std::process::Command;
use std::thread;
use std::time::Duration;
use std::fs;
use std::io::Write;
use std::process::Stdio;
use std::path::PathBuf;

use env_logger::Env;
use log::{error, info};
use tiny_http::{Header, Response, Server};

const LISTEN_ADDR: &str = "0.0.0.0:5005";
const TARGET_FPS: u64 = 30;
const FRAME_INTERVAL_MS: u64 = 1000 / TARGET_FPS;
const JPEG_QUALITY: u8 = 95;
const JPEG_FFMPEG_QSCALE: u8 = 2;
const PORTAL_STREAM_PORT: u16 = 5500;
const PORTAL_JPEG_FILE: &str = "/tmp/proto-portal-frame.jpg";
const PORTAL_STREAMER_LOG: &str = "/tmp/proto-portal-streamer.log";
const PORTAL_FFMPEG_LOG: &str = "/tmp/proto-portal-ffmpeg.log";
static PRIMARY_OUTPUT: OnceLock<Option<String>> = OnceLock::new();
static PORTAL_STARTED: OnceLock<bool> = OnceLock::new();

#[derive(Clone, Default)]
struct Frames {
    jpeg: Vec<u8>,
    png: Vec<u8>,
}

fn main() {
    env_logger::Builder::from_env(Env::default().default_filter_or("info")).init();

    info!("capturing initial desktop frame");
    let initial_png = capture_desktop("png")
        .and_then(|bytes| maybe_extend_virtual(&bytes, "png"))
        .unwrap_or_else(|err| {
        error!("initial png capture failed: {err}; using fallback image");
        fallback_png().to_vec()
    });
    let initial_jpeg = capture_desktop("jpeg")
        .and_then(|bytes| maybe_extend_virtual(&bytes, "jpeg"))
        .unwrap_or_else(|err| {
        error!("initial jpeg capture failed: {err}; using fallback image bytes");
        initial_png.clone()
    });
    info!("captured initial frame jpeg={} png={}", initial_jpeg.len(), initial_png.len());

    let shared = Arc::new(RwLock::new(Frames {
        jpeg: initial_jpeg,
        png: initial_png,
    }));

    let refresh_shared = Arc::clone(&shared);
    thread::spawn(move || {
        let mut frame_counter: u64 = 0;
        loop {
        thread::sleep(Duration::from_millis(FRAME_INTERVAL_MS));
        let jpeg_result = capture_desktop("jpeg").and_then(|bytes| maybe_extend_virtual(&bytes, "jpeg"));
        let png_result = capture_desktop("png").and_then(|bytes| maybe_extend_virtual(&bytes, "png"));

        if let Ok(mut guard) = refresh_shared.write() {
            let jpeg_ok = jpeg_result.is_ok();
            if let Ok(bytes) = jpeg_result {
                guard.jpeg = bytes;
            } else if let Err(err) = jpeg_result {
                frame_counter += 1;
                if frame_counter % TARGET_FPS == 0 {
                    error!("desktop jpeg refresh failed: {err}");
                }
            }

            if !jpeg_ok {
                // In portal-only mode, avoid duplicate/noisy png errors while waiting for picker selection.
                continue;
            }

            if let Ok(bytes) = png_result {
                guard.png = bytes;
            } else if let Err(err) = png_result {
                if frame_counter % TARGET_FPS == 0 {
                    error!("desktop png refresh failed: {err}");
                }
            }

            frame_counter += 1;
            if frame_counter % TARGET_FPS == 0 {
                info!("refreshed desktop frame jpeg={} png={} fps={}", guard.jpeg.len(), guard.png.len(), TARGET_FPS);
            }
        }
        }
    });
    let server = Server::http(LISTEN_ADDR).expect("bind listener");
    info!("serving on http://{LISTEN_ADDR} endpoints: /image(.jpg|.png)?format=png|jpeg /health");

    for req in server.incoming_requests() {
        let path = req.url();
        if path == "/health" {
            let _ = req.respond(Response::from_string("ok"));
            continue;
        }

        if path.starts_with("/image") {
            let wants_png = path.starts_with("/image.png") || path.contains("format=png");
            let (body, mime) = match shared.read() {
                Ok(guard) => {
                    if wants_png {
                        (guard.png.clone(), "image/png")
                    } else {
                        (guard.jpeg.clone(), "image/jpeg")
                    }
                }
                Err(_) => (Vec::new(), if wants_png { "image/png" } else { "image/jpeg" }),
            };

            let mut resp = Response::from_data(body);
            let content_type = Header::from_bytes(&b"Content-Type"[..], mime.as_bytes())
                .expect("valid header");
            let cache_control = Header::from_bytes(&b"Cache-Control"[..], &b"no-store"[..])
                .expect("valid header");
            resp.add_header(content_type);
            resp.add_header(cache_control);
            let _ = req.respond(resp);
            continue;
        }

        let _ = req.respond(Response::from_string("not found").with_status_code(404));
    }
}

fn capture_desktop(format: &str) -> Result<Vec<u8>, String> {
    let portal_only = portal_only_mode();

    if format == "jpeg" {
        if let Ok(bytes) = capture_portal_jpeg() {
            if !bytes.is_empty() {
                return Ok(bytes);
            }
        }
    }

    if portal_only {
        return Err("portal-only mode enabled; waiting for selected portal source".to_string());
    }

    let primary_output = PRIMARY_OUTPUT.get_or_init(detect_primary_output);

    let mut grim_cmd = Command::new("grim");
    if let Some(output_name) = primary_output.as_ref() {
        grim_cmd.arg("-o").arg(output_name);
    }
    if format == "jpeg" {
        grim_cmd.arg("-q").arg(JPEG_QUALITY.to_string());
    }
    if let Ok(output) = grim_cmd.arg("-t").arg(format).arg("-").output() {
        if output.status.success() && !output.stdout.is_empty() {
            return Ok(output.stdout);
        }
    }

    if let Ok(bytes) = capture_with_spectacle(format) {
        if !bytes.is_empty() {
            return Ok(bytes);
        }
    }

    if let Ok(output) = Command::new("import")
        .arg("-window")
        .arg("root")
        .arg("-quality")
        .arg(JPEG_QUALITY.to_string())
        .arg(format!("{}:-", format))
        .output()
    {
        if output.status.success() && !output.stdout.is_empty() {
            return Ok(output.stdout);
        }
    }

    Err(format!(
        "could not capture desktop as {} (install grim for Wayland or import for X11)",
        format
    ))
}

fn capture_portal_jpeg() -> Result<Vec<u8>, String> {
    let started = PORTAL_STARTED.get_or_init(start_portal_pipeline_once);
    if !*started {
        return Err("portal pipeline unavailable".to_string());
    }

    let path = PathBuf::from(PORTAL_JPEG_FILE);
    if path.exists() {
        let bytes = fs::read(&path).map_err(|e| e.to_string())?;
        if !bytes.is_empty() {
            return Ok(bytes);
        }
    }

    Err("portal frame file not ready yet".to_string())
}

fn start_portal_pipeline_once() -> bool {
    let force = std::env::var("PROTO_FORCE_PORTAL")
        .ok()
        .map(|v| matches!(v.as_str(), "1" | "true" | "TRUE" | "yes"))
        .unwrap_or(false);
    let is_wayland = std::env::var("XDG_SESSION_TYPE")
        .ok()
        .map(|v| v.eq_ignore_ascii_case("wayland"))
        .unwrap_or(false);

    if !is_wayland && !force {
        return false;
    }

    let script = PathBuf::from("../../host/scripts/stream_wayland_portal_h264.py");
    if !script.exists() {
        error!("portal helper script not found at {}", script.display());
        return false;
    }

    let _ = fs::remove_file(PORTAL_JPEG_FILE);
    let _ = fs::remove_file(PORTAL_STREAMER_LOG);
    let _ = fs::remove_file(PORTAL_FFMPEG_LOG);

    let streamer_log = fs::File::create(PORTAL_STREAMER_LOG);
    let ffmpeg_log = fs::File::create(PORTAL_FFMPEG_LOG);
    let (Ok(streamer_log), Ok(ffmpeg_log)) = (streamer_log, ffmpeg_log) else {
        error!("failed to create portal log files");
        return false;
    };

    let streamer = Command::new("python3")
        .arg(script)
        .arg("--port")
        .arg(PORTAL_STREAM_PORT.to_string())
        .arg("--fps")
        .arg(TARGET_FPS.to_string())
        .arg("--bitrate-kbps")
        .arg("35000")
        .arg("--size")
        .arg("1920x1080")
        .arg("--cursor-mode")
        .arg("embedded")
        .env("PYTHONUNBUFFERED", "1")
        .env("WBEAM_FRAMED", "0")
        .stdin(Stdio::null())
        .stdout(Stdio::from(streamer_log.try_clone().expect("clone streamer log")))
        .stderr(Stdio::from(streamer_log))
        .spawn();

    if streamer.is_err() {
        error!("failed to start portal streamer helper");
        return false;
    }

    thread::spawn(move || loop {
        let ffmpeg = Command::new("ffmpeg")
            .arg("-hide_banner")
            .arg("-loglevel")
            .arg("error")
            .arg("-fflags")
            .arg("nobuffer")
            .arg("-flags")
            .arg("low_delay")
            .arg("-f")
            .arg("h264")
            .arg("-i")
            .arg(format!("tcp://127.0.0.1:{}", PORTAL_STREAM_PORT))
            .arg("-q:v")
            .arg(JPEG_FFMPEG_QSCALE.to_string())
            .arg("-update")
            .arg("1")
            .arg("-f")
            .arg("image2")
            .arg(PORTAL_JPEG_FILE)
            .stdin(Stdio::null())
            .stdout(Stdio::from(ffmpeg_log.try_clone().expect("clone ffmpeg log")))
            .stderr(Stdio::from(ffmpeg_log.try_clone().expect("clone ffmpeg log")))
            .spawn();

        match ffmpeg {
            Ok(mut child) => {
                let _ = child.wait();
            }
            Err(e) => {
                error!("failed to start ffmpeg portal decoder: {e}");
            }
        }
        thread::sleep(Duration::from_millis(700));
    });

    info!(
        "portal capture started; screen picker should appear (select virtual screen if needed). logs: {PORTAL_STREAMER_LOG}, {PORTAL_FFMPEG_LOG}"
    );
    true
}

fn portal_only_mode() -> bool {
    std::env::var("PROTO_PORTAL_ONLY")
        .ok()
        .map(|v| matches!(v.as_str(), "1" | "true" | "TRUE" | "yes"))
        .unwrap_or(true)
}

fn detect_primary_output() -> Option<String> {
    if let Ok(output) = Command::new("sh")
        .arg("-c")
        .arg("xrandr --query 2>/dev/null | awk '/ connected primary / {print $1; exit}'")
        .output()
    {
        if output.status.success() {
            let value = String::from_utf8_lossy(&output.stdout).trim().to_string();
            if !value.is_empty() {
                return Some(value);
            }
        }
    }
    None
}

fn capture_with_spectacle(format: &str) -> Result<Vec<u8>, String> {
    let ext = if format == "jpeg" { "jpg" } else { "png" };
    let path = format!("/tmp/proto-desktop-frame.{}", ext);

    let status = Command::new("spectacle")
        .arg("-b")
        .arg("-n")
        .arg("-o")
        .arg(&path)
        .status()
        .map_err(|e| e.to_string())?;

    if !status.success() {
        return Err("spectacle failed".to_string());
    }

    let bytes = fs::read(&path).map_err(|e| e.to_string())?;
    let _ = fs::remove_file(&path);
    Ok(bytes)
}

fn maybe_extend_virtual(bytes: &[u8], format: &str) -> Result<Vec<u8>, String> {
    let extend_right_px = std::env::var("PROTO_EXTEND_RIGHT_PX")
        .ok()
        .and_then(|v| v.parse::<u32>().ok())
        .unwrap_or(0);

    if extend_right_px == 0 {
        return Ok(bytes.to_vec());
    }

    let codec = if format == "png" { "png" } else { "mjpeg" };
    let mut cmd = Command::new("ffmpeg");
    cmd.arg("-hide_banner")
        .arg("-loglevel")
        .arg("error")
        .arg("-f")
        .arg("image2pipe")
        .arg("-i")
        .arg("-")
        .arg("-vf")
        .arg(format!("pad=iw+{}:ih:0:0:black", extend_right_px))
        .arg("-f")
        .arg("image2pipe")
        .arg("-vcodec")
        .arg(codec);

    if format == "jpeg" {
        cmd.arg("-q:v").arg(JPEG_FFMPEG_QSCALE.to_string());
    }
    cmd.arg("-")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    let mut child = cmd.spawn().map_err(|e| e.to_string())?;
    if let Some(stdin) = child.stdin.as_mut() {
        stdin.write_all(bytes).map_err(|e| e.to_string())?;
    }
    let output = child.wait_with_output().map_err(|e| e.to_string())?;

    if output.status.success() && !output.stdout.is_empty() {
        return Ok(output.stdout);
    }

    let reason = String::from_utf8_lossy(&output.stderr).to_string();
    Err(format!("ffmpeg virtual-extend failed: {}", reason.trim()))
}

fn fallback_png() -> &'static [u8] {
    &[
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
        0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4, 0x89, 0x00, 0x00, 0x00,
        0x0D, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0xF8, 0xCF, 0xC0, 0xF0,
        0x1F, 0x00, 0x05, 0x00, 0x01, 0xFF, 0x89, 0x99, 0x3D, 0x1D, 0x00, 0x00,
        0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82,
    ]
}
