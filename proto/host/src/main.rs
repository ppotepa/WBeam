use std::collections::HashMap;
use std::sync::{Arc, Mutex, OnceLock};
use std::sync::atomic::{AtomicU8, Ordering};
use std::process::Command;
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use std::fs;
use std::io::{Read, Write};
use std::net::TcpStream;
use std::process::Stdio;
use std::path::PathBuf;
use std::time::Instant;

use log::LevelFilter;
use log::{error, info};
use tiny_http::{Header, Request, Response, Server};

const LISTEN_ADDR: &str = "0.0.0.0:5005";
const JPEG_QUALITY: u8 = 95;
const JPEG_FFMPEG_QSCALE: u8 = 2;
const JPEG_Q_MIN: u8 = 68;
const JPEG_Q_MAX: u8 = 90;
const JPEG_Q_DEFAULT: u8 = 82;
const JPEG_Q_TARGET_KB: usize = 140;
const PORTAL_STREAM_PORT: u16 = 5500;
const PORTAL_JPEG_FILE: &str = "/tmp/proto-portal-frame.jpg";
const PORTAL_DEBUG_DIR: &str = "/dev/shm/proto-portal-frames";
const PORTAL_STREAMER_LOG: &str = "/tmp/proto-portal-streamer.log";
const PORTAL_FFMPEG_LOG: &str = "/tmp/proto-portal-ffmpeg.log";
const MJPEG_BOUNDARY: &str = "frame";
const WBJ1_MAGIC: &[u8; 4] = b"WBJ1";
const WBH1_MAGIC: &[u8; 4] = b"WBH1";
const WBTP_MAGIC: &[u8; 4] = b"WBTP";
const WBTP_HEADER_BYTES: usize = 22;
const WBTP_VERSION: u8 = 1;
const WBS1_MAGIC: &[u8; 4] = b"WBS1";
const WBS1_HEADER_BYTES: usize = 16;
const WBS1_HEADER_MAX_BYTES: usize = 4096;
const ADB_WRITE_TIMEOUT_MS: u64  = 80;
const TCP_SNDBUF_BYTES: i32      = 512 * 1024;
const DEFAULT_ADB_ADDR: &str     = "127.0.0.1:5006";
const DEFAULT_CAPTURE_SIZE: &str = "1280x720";
const DEFAULT_CAPTURE_FPS_STR: &str    = "30";
const CAPTURE_FORMAT_JPEG: &str        = "jpeg";
const CAPTURE_FORMAT_PNG: &str         = "png";
const IMAGEMAGICK_PIPE_JPEG: &str      = "jpeg:-";
const MIME_JPEG: &str                  = "image/jpeg";
const MIME_PNG: &str                   = "image/png";
const DEFAULT_BITRATE_KBPS_STR: &str   = "16000";
const DEFAULT_CURSOR_MODE: &str        = "embedded";
const PORTAL_SOURCE_DEBUG: &str        = "debug";
const PORTAL_SOURCE_FILE: &str         = "file";
const DEFAULT_PORTAL_SOURCE: &str      = "debug";
const DEFAULT_PORTAL_PROFILE: &str     = "lowlatency";
const PORTAL_PIPELINE_SETTLE_MS: u64   = 700;
const FFMPEG_ANALYZEDURATION: &str     = "500000";
const FFMPEG_PROBESIZE: &str           = "32768";
const ADB_RECONNECT_BASE_MS: u64       = 500;
const ADB_RECONNECT_MAX_MS: u64        = 2_000;
const ADB_RECONNECT_MAX_SHIFT: u32     = 2;
const ADB_KEEPALIVE_MIN_MS: u64        = 250;
const ADB_KEEPALIVE_MAX_MS: u64        = 5_000;
const ADB_KEEPALIVE_DEFAULT_MS: u64    = 1_000;
const H264_RX_BUF_BYTES: usize         = 64 * 1024;
const H264_PARSE_BUF_INIT_BYTES: usize = 256 * 1024;
const H264_PARSE_BUF_MAX_BYTES: usize  = 2 * 1024 * 1024;
const H264_PARSE_BUF_KEEP_BYTES: usize = 256 * 1024;
const H264_PARSE_COMPACT_BYTES: usize  = 512 * 1024;
const H264_STARTCODE_TAIL_BYTES: usize = 4;
const DEBUG_FRAMES_DIR_DEFAULT: &str   = "../debugframes";
const DEBUG_FRAMES_FPS_MIN: u64        = 1;
const DEBUG_FRAMES_FPS_MAX: u64        = 30;
const DEBUG_FRAMES_FPS_DEFAULT: u64    = 2;
const DEBUG_FRAMES_SLOTS_MIN: u32      = 8;
const DEBUG_FRAMES_SLOTS_MAX: u32      = 2000;
const DEBUG_FRAMES_SLOTS_DEFAULT: u32  = 120;
const FPS_CAPTURE_MIN: u64             = 5;
const FPS_CAPTURE_MAX: u64             = 60;
const FPS_PUSH_MIN: u64                = 1;
const FPS_PUSH_MAX: u64                = 60;
const MAX_FRAME_BYTES_MIN: usize       = 64 * 1024;
const MAX_FRAME_BYTES_MAX: usize       = 1024 * 1024;
const DEFAULT_MAX_FRAME_BYTES: usize   = 220 * 1024;
const MAX_CHUNK_BYTES_MIN: usize       = 1024;
const MAX_CHUNK_BYTES_MAX: usize       = 256 * 1024;
const DEFAULT_MAX_CHUNK_BYTES: usize   = 14 * 1024;
const ANDROID_LOG_POLL_SECS: u64       = 5;
const ANDROID_LOG_DIR: &str            = "../logs";
static PRIMARY_OUTPUT: OnceLock<Option<String>> = OnceLock::new();
static PORTAL_STARTED: OnceLock<bool> = OnceLock::new();
static CONFIG: OnceLock<HashMap<String, String>> = OnceLock::new();

#[derive(Clone, Default)]
struct Frames {
    jpeg: Arc<Vec<u8>>,
    png: Vec<u8>,
}

fn main() {
    env_logger::Builder::new().filter_level(LevelFilter::Info).init();
    init_config();
    if h264_mode_enabled() {
        run_h264_loop();
        return;
    }

    let target_fps = cfg_var("PROTO_CAPTURE_FPS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .map(|v| v.clamp(FPS_CAPTURE_MIN, FPS_CAPTURE_MAX))
        .unwrap_or(30);
    let frame_interval_ms = 1000 / target_fps;

    info!("capturing initial desktop frame");
    let initial_jpeg = capture_desktop(CAPTURE_FORMAT_JPEG)
        .and_then(|bytes| maybe_extend_virtual(&bytes, CAPTURE_FORMAT_JPEG))
        .unwrap_or_else(|err| {
            error!("initial jpeg capture failed: {err}; waiting for virtual desktop frame");
            Vec::new()
        });
    let initial_png = fallback_png().to_vec();
    info!("captured initial frame jpeg={} png={}", initial_jpeg.len(), initial_png.len());

    let current_quality = Arc::new(AtomicU8::new(JPEG_Q_DEFAULT));

    let shared = Arc::new(Mutex::new(Frames {
        jpeg: Arc::new(initial_jpeg),
        png: initial_png,
    }));

    let refresh_shared = Arc::clone(&shared);
    let refresh_quality = Arc::clone(&current_quality);
    thread::spawn(move || {
        let mut frame_counter: u64 = 0;
        let mut portal_wait_counter: u64 = 0;
        let mut next_capture = Instant::now();
        loop {
            let cap_now = Instant::now();
            if cap_now < next_capture {
                thread::sleep(next_capture - cap_now);
            }
            next_capture = Instant::now() + Duration::from_millis(frame_interval_ms);
            let q = refresh_quality.load(Ordering::Relaxed);
            let jpeg_result = capture_desktop_with_quality(q).and_then(|bytes| maybe_extend_virtual(&bytes, CAPTURE_FORMAT_JPEG));

            if let Ok(mut guard) = refresh_shared.lock() {
                let jpeg_ok = jpeg_result.is_ok();
                if let Ok(bytes) = jpeg_result {
                    guard.jpeg = Arc::new(bytes);
                    portal_wait_counter = 0;
                } else if let Err(err) = jpeg_result {
                    if err.contains("portal-only mode enabled") {
                        portal_wait_counter += 1;
                        if portal_wait_counter % (target_fps * 5) == 0 {
                            error!("desktop jpeg refresh still waiting for selected portal source");
                        }
                    } else {
                        frame_counter += 1;
                        if frame_counter % target_fps == 0 {
                            error!("desktop jpeg refresh failed: {err}");
                        }
                    }
                }

                if !jpeg_ok {
                    continue;
                }

                frame_counter += 1;
                if frame_counter % target_fps == 0 {
                    info!("refreshed desktop frame jpeg={} fps={}", guard.jpeg.len(), target_fps);
                }
            }
        }
    });

    if adb_push_enabled() {
        start_adb_push_sender(Arc::clone(&shared), Arc::clone(&current_quality));
    }

    if android_log_poller_enabled() {
        start_android_log_poller();
    }

    let server = Server::http(LISTEN_ADDR).expect("bind listener");
    info!("serving on http://{LISTEN_ADDR} endpoints: /image(.jpg|.png)?format=png|jpeg /health");

    for req in server.incoming_requests() {
        let path = req.url();
        if path == "/health" {
            let _ = req.respond(Response::from_string("ok"));
            continue;
        }

        if path.starts_with("/mjpeg") {
            serve_mjpeg(req, Arc::clone(&shared));
            continue;
        }

        if path.starts_with("/image") {
            let wants_png = path.starts_with("/image.png") || path.contains("format=png");
            let (body, mime) = match shared.lock() {
                Ok(guard) => {
                    if wants_png {
                        (guard.png.clone(), MIME_PNG)
                    } else {
                        ((*guard.jpeg).clone(), MIME_JPEG)
                    }
                }
                Err(_) => (Vec::new(), if wants_png { MIME_PNG } else { MIME_JPEG }),
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

fn init_config() {
    let config_path = parse_config_path();
    let map = load_config_file(&config_path);
    if map.is_empty() {
        info!("proto host config is empty or missing: {}", config_path);
    } else {
        info!("proto host loaded config: {}", config_path);
    }
    let _ = CONFIG.set(map);
}

fn parse_config_path() -> String {
    let args: Vec<String> = std::env::args().collect();
    let mut i = 1usize;
    while i < args.len() {
        if args[i] == "--config" && i + 1 < args.len() {
            return args[i + 1].clone();
        }
        i += 1;
    }
    "../config/proto.conf".to_string()
}

fn load_config_file(path: &str) -> HashMap<String, String> {
    let mut out = HashMap::new();
    let content = match fs::read_to_string(path) {
        Ok(v) => v,
        Err(_) => return out,
    };

    for raw_line in content.lines() {
        let line = raw_line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let Some((k, v)) = line.split_once('=') else {
            continue;
        };
        let key = k.trim();
        if key.is_empty() {
            continue;
        }
        let value = v.trim().trim_matches('"').to_string();
        out.insert(key.to_string(), value);
    }
    out
}

fn h264_mode_enabled() -> bool {
    env_truthy("PROTO_H264", false)
}

fn cfg_var(name: &str) -> Result<String, ()> {
    CONFIG
        .get()
        .and_then(|m| m.get(name).cloned())
        .ok_or(())
}

fn env_truthy(name: &str, default: bool) -> bool {
    cfg_var(name)
        .ok()
        .map(|v| matches!(v.as_str(), "1" | "true" | "TRUE" | "yes" | "YES"))
        .unwrap_or(default)
}

fn run_h264_loop() {
    let sink_addr = cfg_var("PROTO_ADB_PUSH_ADDR").unwrap_or_else(|_| DEFAULT_ADB_ADDR.to_string());
    let source_port = cfg_var("PROTO_H264_SOURCE_PORT")
        .ok()
        .and_then(|v| v.parse::<u16>().ok())
        .unwrap_or(PORTAL_STREAM_PORT);
    let source_framed = env_truthy("PROTO_H264_SOURCE_FRAMED", true);

    let started = PORTAL_STARTED.get_or_init(start_portal_pipeline_once);
    if !*started {
        error!("PROTO_H264=1 but portal pipeline is unavailable; set PROTO_PORTAL=1 on Wayland");
    }

    info!(
        "H264 WBH1 mode enabled: source=tcp://127.0.0.1:{} sink={} magic=WBH1 source_framed={}",
        source_port,
        sink_addr,
        source_framed
    );

    let mut seq: u64 = 1;
    let mut reconnects: u64 = 0;
    let mut source_retries: u64 = 0;
    let source_addr = format!("127.0.0.1:{}", source_port);

    loop {
        let mut sink = match TcpStream::connect(&sink_addr) {
            Ok(stream) => stream,
            Err(e) => {
                reconnects = reconnects.saturating_add(1);
                if reconnects % 10 == 1 {
                    info!("WBH1 waiting for sink {} ({})", sink_addr, e);
                }
                thread::sleep(Duration::from_millis(500));
                continue;
            }
        };

        let _ = sink.set_nodelay(true);
        let _ = sink.set_write_timeout(Some(Duration::from_millis(ADB_WRITE_TIMEOUT_MS)));
        set_tcp_sndbuf(&sink, TCP_SNDBUF_BYTES);
        info!("WBH1 connected to sink {}", sink_addr);

        let mut source = loop {
            match TcpStream::connect(&source_addr) {
                Ok(stream) => {
                    source_retries = 0;
                    break stream;
                }
                Err(e) => {
                    source_retries = source_retries.saturating_add(1);
                    if source_retries % 10 == 1 {
                        info!("WBH1 waiting for source {} ({})", source_addr, e);
                    }
                    thread::sleep(Duration::from_millis(350));
                }
            }
        };
        // Keep stream stable across short portal stalls; do not reconnect on minor gaps.
        let _ = source.set_read_timeout(Some(Duration::from_millis(5000)));
        info!("WBH1 connected to source {}", source_addr);

        let pump_result = if source_framed {
            pump_h264_framed_stream(&mut source, &mut sink, &mut seq)
        } else {
            pump_h264_stream(&mut source, &mut sink, &mut seq)
        };

        match pump_result {
            Ok(()) => {}
            Err(e) if is_sink_disconnect_error(&e) => {
                info!("WBH1 sink disconnected from {}", sink_addr);
            }
            Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => {
                info!("WBH1 source EOF");
            }
            Err(e) => {
                info!("WBH1 source read error: {}", e);
            }
        }

        thread::sleep(Duration::from_millis(250));
    }
}

struct H264AnnexBBuffer {
    bytes: Vec<u8>,
    start: usize,
}

impl H264AnnexBBuffer {
    fn new() -> Self {
        Self {
            bytes: Vec::with_capacity(H264_PARSE_BUF_INIT_BYTES),
            start: 0,
        }
    }

    fn push(&mut self, data: &[u8]) {
        self.bytes.extend_from_slice(data);
    }

    fn align_to_start_code(&mut self) -> bool {
        let view = &self.bytes[self.start..];
        if let Some((idx, _)) = find_start_code(view, 0) {
            if idx > 0 {
                self.start += idx;
                self.compact_if_needed();
            }
            return true;
        }

        if view.len() > H264_STARTCODE_TAIL_BYTES {
            self.start = self.bytes.len() - H264_STARTCODE_TAIL_BYTES;
            self.compact_if_needed();
        }
        false
    }

    fn next_unit_range(&self) -> Option<(usize, usize)> {
        let view = &self.bytes[self.start..];
        let (first_idx, first_len) = find_start_code(view, 0)?;
        let search_from = first_idx + first_len;
        let (next_idx, _) = find_start_code(view, search_from)?;
        Some((self.start + first_idx, self.start + next_idx))
    }

    fn unit_slice(&self, start: usize, end: usize) -> &[u8] {
        &self.bytes[start..end]
    }

    fn consume_until(&mut self, end: usize) {
        self.start = end.min(self.bytes.len());
        self.compact_if_needed();
    }

    fn trim_overflow(&mut self) {
        if self.bytes.len() <= H264_PARSE_BUF_MAX_BYTES {
            return;
        }

        let keep_bytes = H264_PARSE_BUF_KEEP_BYTES.min(self.bytes.len());
        let drop_bytes = self.bytes.len().saturating_sub(keep_bytes);
        if drop_bytes == 0 {
            return;
        }

        self.bytes.drain(..drop_bytes);
        self.start = self.start.saturating_sub(drop_bytes).min(self.bytes.len());
    }

    fn compact_if_needed(&mut self) {
        if self.start == 0 {
            return;
        }
        if self.start < H264_PARSE_COMPACT_BYTES && self.start * 2 < self.bytes.len() {
            return;
        }
        self.bytes.drain(..self.start);
        self.start = 0;
    }
}

struct H264UnitStats {
    sent_units: u64,
    sent_bytes: u64,
    last_stats: Instant,
}

impl H264UnitStats {
    fn new() -> Self {
        Self {
            sent_units: 0,
            sent_bytes: 0,
            last_stats: Instant::now(),
        }
    }

    fn record_sent(&mut self, bytes: usize) {
        self.sent_units = self.sent_units.saturating_add(1);
        self.sent_bytes = self.sent_bytes.saturating_add(bytes as u64);
    }

    fn maybe_log(&mut self, seq: u64) {
        if self.last_stats.elapsed() < Duration::from_secs(1) {
            return;
        }
        let avg_kb = if self.sent_units > 0 {
            (self.sent_bytes / self.sent_units) / 1024
        } else {
            0
        };
        info!(
            "WBH1 stats: units={} avg_kb={} seq={}",
            self.sent_units, avg_kb, seq
        );
        self.sent_units = 0;
        self.sent_bytes = 0;
        self.last_stats = Instant::now();
    }
}

fn pump_h264_stream(
    source: &mut TcpStream,
    sink: &mut TcpStream,
    seq: &mut u64,
) -> std::io::Result<()> {
    let mut rx = [0u8; H264_RX_BUF_BYTES];
    let mut parser = H264AnnexBBuffer::new();
    let mut stats = H264UnitStats::new();

    loop {
        let read_n = match source.read(&mut rx) {
            Ok(0) => {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::UnexpectedEof,
                    "source EOF",
                ));
            }
            Ok(n) => n,
            Err(e) if matches!(e.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut) => {
                continue;
            }
            Err(e) => return Err(e),
        };

        parser.push(&rx[..read_n]);
        if !parser.align_to_start_code() {
            parser.trim_overflow();
            continue;
        }

        while let Some((unit_start, unit_end)) = parser.next_unit_range() {
            let unit_len = unit_end.saturating_sub(unit_start);
            if unit_len == 0 {
                parser.consume_until(unit_end);
                continue;
            }
            if unit_len > (u32::MAX as usize) {
                parser.consume_until(unit_end);
                continue;
            }

            let send_result = {
                let unit = parser.unit_slice(unit_start, unit_end);
                send_wbh1_unit(sink, *seq, unit)
            };
            if let Err(e) = send_result {
                return Err(e);
            }

            parser.consume_until(unit_end);
            stats.record_sent(unit_len);
            *seq = seq.wrapping_add(1);
        }

        parser.trim_overflow();
        stats.maybe_log(*seq);
    }
}

fn pump_h264_framed_stream(
    source: &mut TcpStream,
    sink: &mut TcpStream,
    seq: &mut u64,
) -> std::io::Result<()> {
    let mut header = [0u8; WBTP_HEADER_BYTES];
    let mut payload = vec![0u8; H264_PARSE_BUF_KEEP_BYTES];
    let mut stats = H264UnitStats::new();
    read_first_framed_header(source, &mut header)?;

    loop {
        let (capture_ts_ms, payload_len) = parse_wbtp_header(&header)?;
        if payload_len > payload.len() {
            payload.resize(payload_len, 0);
        }
        read_exact_retry(source, &mut payload[..payload_len])?;

        let units_sent = send_annexb_access_unit_as_wbh1(
            sink,
            seq,
            capture_ts_ms,
            &payload[..payload_len],
            &mut stats,
        )?;
        if units_sent == 0 {
            send_wbh1_unit_with_ts(sink, *seq, capture_ts_ms, &payload[..payload_len])?;
            stats.record_sent(payload_len);
            *seq = seq.wrapping_add(1);
        }
        stats.maybe_log(*seq);
        read_exact_retry(source, &mut header)?;
    }
}

fn send_annexb_access_unit_as_wbh1(
    sink: &mut TcpStream,
    seq: &mut u64,
    capture_ts_ms: u64,
    access_unit: &[u8],
    stats: &mut H264UnitStats,
) -> std::io::Result<usize> {
    let mut sent = 0usize;
    let mut cursor = 0usize;

    while let Some((start_idx, start_len)) = find_start_code(access_unit, cursor) {
        let search_from = start_idx + start_len;
        let unit_end = if let Some((next_idx, _)) = find_start_code(access_unit, search_from) {
            next_idx
        } else {
            access_unit.len()
        };

        if unit_end > start_idx {
            let unit = &access_unit[start_idx..unit_end];
            send_wbh1_unit_with_ts(sink, *seq, capture_ts_ms, unit)?;
            stats.record_sent(unit.len());
            *seq = seq.wrapping_add(1);
            sent += 1;
        }

        if unit_end >= access_unit.len() {
            break;
        }
        cursor = unit_end;
    }

    Ok(sent)
}

fn send_wbh1_unit(sink: &mut TcpStream, seq: u64, unit: &[u8]) -> std::io::Result<()> {
    let ts_ms = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0);
    send_wbh1_unit_with_ts(sink, seq, ts_ms, unit)
}

fn send_wbh1_unit_with_ts(
    sink: &mut TcpStream,
    seq: u64,
    ts_ms: u64,
    unit: &[u8],
) -> std::io::Result<()> {
    let mut header = [0u8; 24];
    header[0..4].copy_from_slice(WBH1_MAGIC);
    header[4..12].copy_from_slice(&seq.to_be_bytes());
    header[12..20].copy_from_slice(&ts_ms.to_be_bytes());
    header[20..24].copy_from_slice(&(unit.len() as u32).to_be_bytes());
    write_wbj1_frame(sink, &header, unit)
}

fn parse_wbtp_header(header: &[u8; WBTP_HEADER_BYTES]) -> std::io::Result<(u64, usize)> {
    if &header[0..4] != WBTP_MAGIC {
        return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "bad WBTP magic"));
    }
    if header[4] != WBTP_VERSION {
        return Err(std::io::Error::new(std::io::ErrorKind::InvalidData, "bad WBTP version"));
    }

    let capture_ts_us = u64::from_be_bytes([
        header[10], header[11], header[12], header[13],
        header[14], header[15], header[16], header[17],
    ]);
    let payload_len = u32::from_be_bytes([header[18], header[19], header[20], header[21]]) as usize;
    if payload_len == 0 || payload_len > H264_PARSE_BUF_MAX_BYTES {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            format!("bad WBTP payload len={payload_len}"),
        ));
    }

    Ok((capture_ts_us / 1000, payload_len))
}

fn read_first_framed_header(
    source: &mut TcpStream,
    header: &mut [u8; WBTP_HEADER_BYTES],
) -> std::io::Result<()> {
    let mut prefix = [0u8; 4];
    read_exact_retry(source, &mut prefix)?;

    if &prefix == WBS1_MAGIC {
        consume_wbs1_hello_rest(source)?;
        read_exact_retry(source, header)?;
        return Ok(());
    }

    header[0..4].copy_from_slice(&prefix);
    read_exact_retry(source, &mut header[4..])?;
    Ok(())
}

fn consume_wbs1_hello_rest(source: &mut TcpStream) -> std::io::Result<()> {
    let mut rest = [0u8; WBS1_HEADER_BYTES - 4];
    read_exact_retry(source, &mut rest)?;

    let version = rest[0];
    let flags = rest[1];
    let declared_len = u16::from_be_bytes([rest[2], rest[3]]) as usize;
    let session_id = u64::from_be_bytes([
        rest[4], rest[5], rest[6], rest[7],
        rest[8], rest[9], rest[10], rest[11],
    ]);

    if declared_len < WBS1_HEADER_BYTES || declared_len > WBS1_HEADER_MAX_BYTES {
        return Err(std::io::Error::new(
            std::io::ErrorKind::InvalidData,
            format!("bad WBS1 len={declared_len}"),
        ));
    }

    let extra = declared_len - WBS1_HEADER_BYTES;
    if extra > 0 {
        discard_exact(source, extra)?;
    }

    info!(
        "WBH1 source hello: magic=WBS1 version={} flags={} len={} session=0x{:016x}",
        version, flags, declared_len, session_id
    );
    Ok(())
}

fn discard_exact(stream: &mut TcpStream, mut len: usize) -> std::io::Result<()> {
    let mut scratch = [0u8; 256];
    while len > 0 {
        let take = len.min(scratch.len());
        read_exact_retry(stream, &mut scratch[..take])?;
        len -= take;
    }
    Ok(())
}

fn read_exact_retry(stream: &mut TcpStream, dst: &mut [u8]) -> std::io::Result<()> {
    let mut off = 0usize;
    while off < dst.len() {
        match stream.read(&mut dst[off..]) {
            Ok(0) => {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::UnexpectedEof,
                    "EOF",
                ));
            }
            Ok(n) => off += n,
            Err(e) if matches!(e.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut) => continue,
            Err(e) => return Err(e),
        }
    }
    Ok(())
}

fn is_sink_disconnect_error(err: &std::io::Error) -> bool {
    matches!(
        err.kind(),
        std::io::ErrorKind::BrokenPipe
            | std::io::ErrorKind::ConnectionAborted
            | std::io::ErrorKind::ConnectionReset
            | std::io::ErrorKind::NotConnected
            | std::io::ErrorKind::WriteZero
    )
}

fn find_start_code(bytes: &[u8], from: usize) -> Option<(usize, usize)> {
    if bytes.len() < 3 || from >= bytes.len().saturating_sub(2) {
        return None;
    }

    let mut i = from;
    while i + 3 <= bytes.len() {
        if i + 4 <= bytes.len()
            && bytes[i] == 0
            && bytes[i + 1] == 0
            && bytes[i + 2] == 0
            && bytes[i + 3] == 1
        {
            return Some((i, 4));
        }
        if bytes[i] == 0 && bytes[i + 1] == 0 && bytes[i + 2] == 1 {
            return Some((i, 3));
        }
        i += 1;
    }

    None
}

fn serve_mjpeg(req: Request, shared: Arc<Mutex<Frames>>) {
    thread::spawn(move || {
        let mut writer = req.into_writer();
        let mut last_sig: u64 = 0;
        let mut has_last_sig = false;
        let fps = cfg_var("PROTO_MJPEG_FPS")
            .ok()
            .and_then(|v| v.parse::<u64>().ok())
            .map(|v| v.clamp(FPS_PUSH_MIN, FPS_PUSH_MAX))
            .unwrap_or(15);
        let frame_delay = Duration::from_millis(1000 / fps);

        let header = format!(
            "HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary={}\r\nCache-Control: no-store\r\nPragma: no-cache\r\nConnection: close\r\n\r\n",
            MJPEG_BOUNDARY
        );
        if writer.write_all(header.as_bytes()).is_err() {
            return;
        }

        loop {
            let jpeg = match shared.lock() {
                Ok(guard) => Arc::clone(&guard.jpeg),
                Err(_) => {
                    thread::sleep(frame_delay);
                    continue;
                }
            };

            if jpeg.is_empty() {
                thread::sleep(frame_delay);
                continue;
            }

            let sig = frame_signature(&jpeg);
            if has_last_sig && sig == last_sig {
                thread::sleep(frame_delay);
                continue;
            }

            let part_header = format!(
                "--{}\r\nContent-Type: image/jpeg\r\nContent-Length: {}\r\n\r\n",
                MJPEG_BOUNDARY,
                jpeg.len()
            );

            if writer.write_all(part_header.as_bytes()).is_err() {
                break;
            }
            if writer.write_all(&jpeg).is_err() {
                break;
            }
            if writer.write_all(b"\r\n").is_err() {
                break;
            }
            if writer.flush().is_err() {
                break;
            }

            last_sig = sig;
            has_last_sig = true;

            thread::sleep(frame_delay);
        }
    });
}

fn adb_push_enabled() -> bool {
    cfg_var("PROTO_ADB_PUSH")
        .ok()
        .map(|v| matches!(v.as_str(), "1" | "true" | "TRUE" | "yes" | "YES"))
        .unwrap_or(false)
}

fn android_log_poller_enabled() -> bool {
    cfg_var("PROTO_ANDROID_LOG_POLLER")
        .ok()
        .map(|v| matches!(v.as_str(), "1" | "true" | "TRUE" | "yes" | "YES"))
        .unwrap_or(false)
}

fn start_adb_push_sender(shared: Arc<Mutex<Frames>>, current_quality: Arc<AtomicU8>) {
    let addr = cfg_var("PROTO_ADB_PUSH_ADDR").unwrap_or_else(|_| DEFAULT_ADB_ADDR.to_string());
    let fps = cfg_var("PROTO_ADB_PUSH_FPS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .or_else(|| cfg_var("PROTO_MJPEG_FPS").ok().and_then(|v| v.parse::<u64>().ok()))
        .map(|v| v.clamp(FPS_PUSH_MIN, FPS_PUSH_MAX))
        .unwrap_or(24);
    let frame_delay = Duration::from_millis(1000 / fps);
    let max_frame_bytes = cfg_var("PROTO_MAX_FRAME_BYTES")
        .ok()
        .and_then(|v| v.parse::<usize>().ok())
        .map(|v| v.clamp(MAX_FRAME_BYTES_MIN, MAX_FRAME_BYTES_MAX))
        .unwrap_or(DEFAULT_MAX_FRAME_BYTES);
    let jpeg_target_kb = cfg_var("PROTO_JPEG_TARGET_KB")
        .ok()
        .and_then(|v| v.parse::<usize>().ok())
        .map(|v| v.clamp(24, 240))
        .unwrap_or(JPEG_Q_TARGET_KB);
    let skip_sig_check = cfg_var("PROTO_SKIP_SIG_CHECK")
        .ok()
        .map(|v| matches!(v.as_str(), "1" | "true" | "TRUE"))
        .unwrap_or(true);
    let keepalive_ms = cfg_var("PROTO_ADB_KEEPALIVE_MS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .map(|v| v.clamp(ADB_KEEPALIVE_MIN_MS, ADB_KEEPALIVE_MAX_MS))
        .unwrap_or(ADB_KEEPALIVE_DEFAULT_MS);
    let keepalive_interval = Duration::from_millis(keepalive_ms);
    let debug_frames_enabled = cfg_var("PROTO_DEBUG_FRAMES")
        .ok()
        .map(|v| matches!(v.as_str(), "1" | "true" | "TRUE" | "yes" | "YES"))
        .unwrap_or(false);
    let debug_frames_dir = cfg_var("PROTO_DEBUG_FRAMES_DIR")
        .unwrap_or_else(|_| DEBUG_FRAMES_DIR_DEFAULT.to_string());
    let debug_frames_fps = cfg_var("PROTO_DEBUG_FRAMES_FPS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .map(|v| v.clamp(DEBUG_FRAMES_FPS_MIN, DEBUG_FRAMES_FPS_MAX))
        .unwrap_or(DEBUG_FRAMES_FPS_DEFAULT);
    let debug_frames_slots = cfg_var("PROTO_DEBUG_FRAMES_SLOTS")
        .ok()
        .and_then(|v| v.parse::<u32>().ok())
        .map(|v| v.clamp(DEBUG_FRAMES_SLOTS_MIN, DEBUG_FRAMES_SLOTS_MAX))
        .unwrap_or(DEBUG_FRAMES_SLOTS_DEFAULT);
    let debug_frame_interval = Duration::from_millis(1000 / debug_frames_fps);
    let debug_dir_path = PathBuf::from(debug_frames_dir);

    thread::spawn(move || {
        set_thread_realtime_priority();
        info!(
            "ADB PUSH tune: fps={} max_frame_bytes={} jpeg_target_kb={} skip_sig_check={}",
            fps, max_frame_bytes, jpeg_target_kb, skip_sig_check
        );
        let mut connect_failures: u32 = 0;
        let mut seq: u64 = 1;
        let mut next_tick = Instant::now();
        let mut debug_enabled = debug_frames_enabled;
        let mut last_debug_dump = Instant::now()
            .checked_sub(debug_frame_interval)
            .unwrap_or_else(Instant::now);

        if debug_enabled {
            match fs::create_dir_all(&debug_dir_path) {
                Ok(_) => info!(
                    "ADB PUSH debug frame dump enabled: dir={} fps={} slots={}",
                    debug_dir_path.display(),
                    debug_frames_fps,
                    debug_frames_slots
                ),
                Err(e) => {
                    error!(
                        "ADB PUSH debug frame dump disabled (cannot create {}): {}",
                        debug_dir_path.display(),
                        e
                    );
                    debug_enabled = false;
                }
            }
        }

        loop {
            match TcpStream::connect(&addr) {
                Ok(mut stream) => {
                    connect_failures = 0;
                    let mut last_sig: u64 = 0;
                    let mut has_last_sig = false;
                    let mut sent_frames: u64 = 0;
                    let mut sent_bytes: u64 = 0;
                    let mut skip_same_sig: u64 = 0;
                    let mut late_ticks: u64 = 0;
                    let mut drop_oversize: u64 = 0;
                    let mut send_errs: u64 = 0;
                    let mut last_stats = Instant::now();
                    let mut last_send = Instant::now();
                    let _ = stream.set_nodelay(true);
                    let _ = stream.set_write_timeout(Some(Duration::from_millis(ADB_WRITE_TIMEOUT_MS)));
                    set_tcp_sndbuf(&stream, TCP_SNDBUF_BYTES);
                    info!("ADB PUSH connected to {}", addr);

                    loop {
                        let now = Instant::now();
                        if now < next_tick {
                            precise_sleep(next_tick - now);
                        }
                        if now > next_tick + frame_delay {
                            late_ticks = late_ticks.saturating_add(1);
                        }
                        next_tick += frame_delay;
                        if Instant::now().duration_since(next_tick) > frame_delay {
                            next_tick = Instant::now() + frame_delay;
                        }

                        let jpeg = match shared.lock() {
                            Ok(guard) => Arc::clone(&guard.jpeg),
                            Err(_) => continue,
                        };

                        if jpeg.is_empty() || jpeg.len() > (u32::MAX as usize) {
                            continue;
                        }
                        if jpeg.len() > max_frame_bytes {
                            drop_oversize = drop_oversize.saturating_add(1);
                            let q = current_quality.load(Ordering::Relaxed);
                            let new_q = q.saturating_sub(4).max(JPEG_Q_MIN);
                            if new_q != q {
                                current_quality.store(new_q, Ordering::Relaxed);
                                info!(
                                    "ADB PUSH oversize drop: size={} max={} q={} -> {}",
                                    jpeg.len(),
                                    max_frame_bytes,
                                    q,
                                    new_q
                                );
                            }
                            continue;
                        }

                        let sig = if skip_sig_check { seq } else { frame_signature(&jpeg) };
                        // Periodic keepalive re-send avoids idle timeout disconnects
                        // when desktop content is unchanged.
                        if has_last_sig && sig == last_sig && last_send.elapsed() < keepalive_interval {
                            skip_same_sig = skip_same_sig.saturating_add(1);
                            continue;
                        }

                        let ts_ms = SystemTime::now()
                            .duration_since(UNIX_EPOCH)
                            .map(|d| d.as_millis() as u64)
                            .unwrap_or(0);

                        let mut header = [0u8; 24];
                        header[0..4].copy_from_slice(WBJ1_MAGIC);
                        header[4..12].copy_from_slice(&seq.to_be_bytes());
                        header[12..20].copy_from_slice(&ts_ms.to_be_bytes());
                        header[20..24].copy_from_slice(&(jpeg.len() as u32).to_be_bytes());

                        if write_wbj1_frame(&mut stream, &header, &jpeg).is_err() {
                            info!("ADB PUSH disconnected from {}", addr);
                            break;
                        }

                        last_sig = sig;
                        has_last_sig = true;
                        last_send = Instant::now();
                        maybe_dump_sent_frame(
                            &mut debug_enabled,
                            &debug_dir_path,
                            &mut last_debug_dump,
                            debug_frame_interval,
                            debug_frames_slots,
                            seq,
                            &jpeg,
                        );
                        sent_frames = sent_frames.saturating_add(1);
                        sent_bytes = sent_bytes.saturating_add(jpeg.len() as u64);
                        seq = seq.wrapping_add(1);

                        let elapsed = last_stats.elapsed();
                        if elapsed >= Duration::from_secs(1) {
                            let avg_kb = if sent_frames > 0 {
                                (sent_bytes / sent_frames) / 1024
                            } else {
                                0
                            };
                            info!(
                                "ADB PUSH stats: sent={} avg_kb={} skip_same={} late_ticks={} drop_oversize={} send_errs={} seq={}",
                                sent_frames,
                                avg_kb,
                                skip_same_sig,
                                late_ticks,
                                drop_oversize,
                                send_errs,
                                seq
                            );
                            let q = current_quality.load(Ordering::Relaxed);
                            let new_q = if late_ticks > 0 || avg_kb as usize > jpeg_target_kb {
                                q.saturating_sub(2).max(JPEG_Q_MIN)
                            } else if (avg_kb as usize) < jpeg_target_kb * 7 / 10 {
                                (q + 1).min(JPEG_Q_MAX)
                            } else {
                                q
                            };
                            if new_q != q {
                                current_quality.store(new_q, Ordering::Relaxed);
                                info!("Quality governor: q={} -> {} avg_kb={} late_ticks={}", q, new_q, avg_kb, late_ticks);
                            }

                            sent_frames = 0;
                            sent_bytes = 0;
                            skip_same_sig = 0;
                            late_ticks = 0;
                            drop_oversize = 0;
                            send_errs = 0;
                            last_stats = Instant::now();
                        }
                    }
                }
                Err(e) => {
                    connect_failures = connect_failures.saturating_add(1);
                    if connect_failures % 10 == 1 {
                        info!("ADB PUSH waiting for {} ({})", addr, e);
                    }
                    let backoff_ms = (ADB_RECONNECT_BASE_MS * (1u64 << connect_failures.min(ADB_RECONNECT_MAX_SHIFT))).min(ADB_RECONNECT_MAX_MS);
                    thread::sleep(Duration::from_millis(backoff_ms));
                }
            }
        }
    });
}

fn maybe_dump_sent_frame(
    enabled: &mut bool,
    dir: &PathBuf,
    last_dump: &mut Instant,
    interval: Duration,
    slots: u32,
    seq: u64,
    jpeg: &[u8],
) {
    if !*enabled || jpeg.is_empty() {
        return;
    }
    if last_dump.elapsed() < interval {
        return;
    }
    *last_dump = Instant::now();

    let slot = (seq % slots as u64) as u32;
    let slot_path = dir.join(format!("sent-{:04}.jpg", slot));
    if let Err(e) = fs::write(&slot_path, jpeg) {
        error!("failed to write debug frame {}: {}", slot_path.display(), e);
        *enabled = false;
        return;
    }

    let latest_path = dir.join("latest-sent.jpg");
    let _ = fs::write(&latest_path, jpeg);

    let ts_ms = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0);
    let meta = format!("seq={} bytes={} ts_ms={}\n", seq, jpeg.len(), ts_ms);
    let meta_path = dir.join("latest-sent.txt");
    let _ = fs::write(meta_path, meta);
}

fn start_android_log_poller() {
    let serial = cfg_var("PROTO_SERIAL").unwrap_or_default();
    let log_dir = PathBuf::from(ANDROID_LOG_DIR);

    if let Err(e) = fs::create_dir_all(&log_dir) {
        error!("android log poller: could not create log dir {}: {}", log_dir.display(), e);
        return;
    }

    // One file per session, named by start time.
    let session_ts = {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default();
        let secs = now.as_secs();
        let h = (secs % 86400) / 3600;
        let m = (secs % 3600) / 60;
        let s = secs % 60;
        // days since epoch → rough YYYYMMDD
        let days = secs / 86400;
        let mut y = 1970u32;
        let mut d = days as u32;
        loop {
            let dy = if y % 4 == 0 && (y % 100 != 0 || y % 400 == 0) { 366 } else { 365 };
            if d < dy { break; }
            d -= dy;
            y += 1;
        }
        let month_days: [u32; 12] = [31,
            if y % 4 == 0 && (y % 100 != 0 || y % 400 == 0) { 29 } else { 28 },
            31,30,31,30,31,31,30,31,30,31];
        let mut mo = 1u32;
        for md in &month_days {
            if d < *md { break; }
            d -= md;
            mo += 1;
        }
        format!("{:04}{:02}{:02}-{:02}{:02}{:02}", y, mo, d + 1, h, m, s)
    };

    let log_path = log_dir.join(format!("android-{}.log", session_ts));
    info!("android log poller: writing to {}", log_path.display());

    // Clear stale logcat buffer once at startup so the first poll is fresh.
    let mut clear_cmd = Command::new("adb");
    if !serial.is_empty() { clear_cmd.arg("-s").arg(&serial); }
    let _ = clear_cmd.args(["logcat", "-c"]).output();

    thread::spawn(move || {
        loop {
            thread::sleep(Duration::from_secs(ANDROID_LOG_POLL_SECS));

            // Dump current buffer.
            let mut dump_cmd = Command::new("adb");
            if !serial.is_empty() { dump_cmd.arg("-s").arg(&serial); }
            let dump = dump_cmd
                .args(["logcat", "-d"])
                .stderr(Stdio::null())
                .output();

            let output = match dump {
                Ok(o) if !o.stdout.is_empty() => o.stdout,
                _ => continue,
            };

            // Clear immediately after dump so next poll is incremental.
            let mut clear_cmd = Command::new("adb");
            if !serial.is_empty() { clear_cmd.arg("-s").arg(&serial); }
            let _ = clear_cmd.args(["logcat", "-c"]).stderr(Stdio::null()).output();

            // Append to session log file.
            if let Ok(mut f) = fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(&log_path)
            {
                let _ = f.write_all(&output);
                // Separator between polls.
                let sep = format!(
                    "\n--- poll @ {} ---\n",
                    SystemTime::now()
                        .duration_since(UNIX_EPOCH)
                        .map(|d| d.as_secs())
                        .unwrap_or(0)
                );
                let _ = f.write_all(sep.as_bytes());
            }
        }
    });
}

fn capture_desktop(format: &str) -> Result<Vec<u8>, String> {
    capture_desktop_with_quality(JPEG_QUALITY)
        .or_else(|_| Err(format!("could not capture desktop as {}", format)))
}

fn capture_desktop_with_quality(jpeg_quality: u8) -> Result<Vec<u8>, String> {
    let portal_only = portal_only_mode();

    if portal_enabled() {
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
    let capture_size = cfg_var("PROTO_CAPTURE_SIZE").ok();
    let grim_scale = capture_size
        .as_deref()
        .and_then(|target| compute_grim_scale(target, primary_output.as_deref()));

    let mut grim_cmd = Command::new("grim");
    if let Some(output_name) = primary_output.as_ref() {
        grim_cmd.arg("-o").arg(output_name);
    }
    if let Some(scale) = grim_scale {
        grim_cmd.arg("-s").arg(format!("{:.3}", scale));
    }
    grim_cmd
        .arg("-t")
        .arg(CAPTURE_FORMAT_JPEG)
        .arg("-q")
        .arg(jpeg_quality.to_string());
    if let Ok(output) = grim_cmd.arg("-").output() {
        if output.status.success() && !output.stdout.is_empty() {
            // When grim scale is available, avoid extra ffmpeg transcode per frame.
            if grim_scale.is_some() {
                return Ok(output.stdout);
            }
            return Ok(resize_jpeg_if_requested(output.stdout, capture_size.as_deref(), jpeg_quality));
        }
    }

    if let Ok(bytes) = capture_with_spectacle(CAPTURE_FORMAT_JPEG) {
        if !bytes.is_empty() {
            return Ok(resize_jpeg_if_requested(bytes, capture_size.as_deref(), jpeg_quality));
        }
    }

    let mut import_cmd = Command::new("import");
    import_cmd.arg("-window").arg("root").arg("-quality").arg(jpeg_quality.to_string());
    if let Some(ref sz) = capture_size {
        import_cmd.arg("-resize").arg(format!("{}!", sz));
    }
    if let Ok(output) = import_cmd.arg(IMAGEMAGICK_PIPE_JPEG).output() {
        if output.status.success() && !output.stdout.is_empty() {
            return Ok(output.stdout);
        }
    }

    Err("could not capture desktop (install grim for Wayland or import for X11)".to_string())
}

fn resize_jpeg_if_requested(input: Vec<u8>, capture_size: Option<&str>, _jpeg_quality: u8) -> Vec<u8> {
    let Some(sz) = capture_size else {
        return input;
    };
    if !sz.contains('x') {
        return input;
    }

    let mut cmd = Command::new("ffmpeg");
    cmd.arg("-hide_banner")
        .arg("-loglevel")
        .arg("error")
        .arg("-f")
        .arg("image2pipe")
        .arg("-i")
        .arg("-")
        .arg("-vf")
        .arg(format!("scale={}:flags=fast_bilinear", sz))
        .arg("-f")
        .arg("image2pipe")
        .arg("-vcodec")
        .arg("mjpeg")
        .arg("-q:v")
        .arg(JPEG_FFMPEG_QSCALE.to_string())
        .arg("-")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null());

    let Ok(mut child) = cmd.spawn() else {
        return input;
    };

    if let Some(stdin) = child.stdin.as_mut() {
        if stdin.write_all(&input).is_err() {
            let _ = child.kill();
            return input;
        }
    }

    match child.wait_with_output() {
        Ok(out) if out.status.success() && !out.stdout.is_empty() => out.stdout,
        _ => input,
    }
}

fn compute_grim_scale(target_size: &str, preferred_output: Option<&str>) -> Option<f64> {
    let (target_w, target_h) = parse_size_pair(target_size)?;
    let (src_w, src_h) = detect_output_geometry(preferred_output)?;
    if src_w == 0 || src_h == 0 {
        return None;
    }

    let sw = target_w as f64 / src_w as f64;
    let sh = target_h as f64 / src_h as f64;
    let scale = sw.min(sh);
    if (0.05..=4.0).contains(&scale) {
        Some(scale)
    } else {
        None
    }
}

fn parse_size_pair(value: &str) -> Option<(u32, u32)> {
    let (w, h) = value.split_once('x')?;
    let w = w.trim().parse::<u32>().ok()?;
    let h = h.trim().parse::<u32>().ok()?;
    if w > 0 && h > 0 {
        Some((w, h))
    } else {
        None
    }
}

fn detect_output_geometry(preferred_output: Option<&str>) -> Option<(u32, u32)> {
    let output = Command::new("kscreen-doctor")
        .arg("-o")
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }

    let mut clean = String::with_capacity(output.stdout.len());
    let mut i = 0usize;
    let bytes = output.stdout.as_slice();
    while i < bytes.len() {
        if bytes[i] == 0x1B && i + 1 < bytes.len() && bytes[i + 1] == b'[' {
            i += 2;
            while i < bytes.len() && !(bytes[i] >= b'@' && bytes[i] <= b'~') {
                i += 1;
            }
            i += 1;
            continue;
        }
        clean.push(bytes[i] as char);
        i += 1;
    }

    let mut current_output: Option<String> = None;
    let mut fallback: Option<(u32, u32)> = None;

    for raw in clean.lines() {
        let line = raw.trim();
        if line.starts_with("Output:") {
            let parts: Vec<&str> = line.split_whitespace().collect();
            current_output = if parts.len() >= 3 {
                Some(parts[2].to_string())
            } else {
                None
            };
            continue;
        }
        if !line.starts_with("Geometry:") {
            continue;
        }
        let Some(size_token) = line.split_whitespace().last() else {
            continue;
        };
        let Some(wh) = parse_size_pair(size_token) else {
            continue;
        };

        if fallback.is_none() {
            fallback = Some(wh);
        }
        if let (Some(pref), Some(cur)) = (preferred_output, current_output.as_deref()) {
            if pref == cur {
                return Some(wh);
            }
        }
    }

    fallback
}

fn portal_enabled() -> bool {
    cfg_var("PROTO_PORTAL")
        .ok()
        .map(|v| {
            let value = v.trim();
            !matches!(value, "0" | "false" | "FALSE" | "no" | "NO")
        })
        .unwrap_or(true)
}

fn capture_portal_jpeg() -> Result<Vec<u8>, String> {
    let started = PORTAL_STARTED.get_or_init(start_portal_pipeline_once);
    if !*started {
        return Err("portal pipeline unavailable".to_string());
    }

    let source_mode = cfg_var("PROTO_PORTAL_JPEG_SOURCE")
        .unwrap_or_else(|_| DEFAULT_PORTAL_SOURCE.to_string());

    if source_mode.eq_ignore_ascii_case(PORTAL_SOURCE_DEBUG) {
        if let Ok(bytes) = read_latest_portal_debug_jpeg() {
            if !bytes.is_empty() {
                return Ok(bytes);
            }
        }
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
    let force = cfg_var("PROTO_FORCE_PORTAL")
        .ok()
        .map(|v| matches!(v.as_str(), "1" | "true" | "TRUE" | "yes"))
        .unwrap_or(false);
    let is_wayland = cfg_var("PROTO_SESSION_TYPE")
        .ok()
        .map(|v| v.eq_ignore_ascii_case("wayland"))
        .unwrap_or(false);

    if !is_wayland && !force {
        return false;
    }
    if !has_gst_element("pipewiresrc") {
        error!("missing GStreamer element 'pipewiresrc' (install package: gst-plugin-pipewire)");
        return false;
    }

    let script = PathBuf::from("../../host/scripts/stream_wayland_portal_h264.py");
    if !script.exists() {
        error!("portal helper script not found at {}", script.display());
        return false;
    }

    let _ = fs::remove_file(PORTAL_JPEG_FILE);
    let _ = fs::remove_dir_all(PORTAL_DEBUG_DIR);
    let _ = fs::create_dir_all(PORTAL_DEBUG_DIR);
    let _ = fs::remove_file(PORTAL_STREAMER_LOG);
    let _ = fs::remove_file(PORTAL_FFMPEG_LOG);

    let streamer_log = fs::File::create(PORTAL_STREAMER_LOG);
    let ffmpeg_log = fs::File::create(PORTAL_FFMPEG_LOG);
    let (Ok(streamer_log), Ok(ffmpeg_log)) = (streamer_log, ffmpeg_log) else {
        error!("failed to create portal log files");
        return false;
    };

    let capture_size = cfg_var("PROTO_CAPTURE_SIZE").unwrap_or_else(|_| DEFAULT_CAPTURE_SIZE.to_string());
    let capture_bitrate = cfg_var("PROTO_CAPTURE_BITRATE_KBPS").unwrap_or_else(|_| DEFAULT_BITRATE_KBPS_STR.to_string());
    let capture_fps = cfg_var("PROTO_CAPTURE_FPS").unwrap_or_else(|_| DEFAULT_CAPTURE_FPS_STR.to_string());
    let cursor_mode = cfg_var("PROTO_CURSOR_MODE").unwrap_or_else(|_| DEFAULT_CURSOR_MODE.to_string());
    let videorate_drop_only = cfg_var("WBEAM_VIDEORATE_DROP_ONLY").unwrap_or_else(|_| "0".to_string());
    let framed_send_timeout_s = cfg_var("WBEAM_FRAMED_SEND_TIMEOUT_S").unwrap_or_else(|_| "0".to_string());
    let framed_duplicate_stale = cfg_var("WBEAM_FRAMED_DUPLICATE_STALE").unwrap_or_else(|_| "0".to_string());
    let pipewire_keepalive_ms = cfg_var("WBEAM_PIPEWIRE_KEEPALIVE_MS").unwrap_or_default();
    let pipewire_always_copy = cfg_var("WBEAM_PIPEWIRE_ALWAYS_COPY").unwrap_or_else(|_| "1".to_string());
    let framed_pull_timeout_ms = cfg_var("WBEAM_FRAMED_PULL_TIMEOUT_MS").unwrap_or_default();
    let queue_max_buffers = cfg_var("WBEAM_QUEUE_MAX_BUFFERS").unwrap_or_else(|_| "1".to_string());
    let queue_max_time_ms = cfg_var("WBEAM_QUEUE_MAX_TIME_MS").unwrap_or_else(|_| "12".to_string());
    let appsink_max_buffers = cfg_var("WBEAM_APPSINK_MAX_BUFFERS").unwrap_or_else(|_| "2".to_string());
    let source_mode = cfg_var("PROTO_PORTAL_JPEG_SOURCE").unwrap_or_else(|_| DEFAULT_PORTAL_SOURCE.to_string());
    let source_framed = env_truthy("PROTO_H264_SOURCE_FRAMED", h264_mode_enabled());
    let framed_env = if source_framed { "1" } else { "0" };

    let mut streamer_child = match Command::new("python3")
        .arg(script)
        .arg("--profile")
        .arg(DEFAULT_PORTAL_PROFILE)
        .arg("--port")
        .arg(PORTAL_STREAM_PORT.to_string())
        .arg("--fps")
        .arg(&capture_fps)
        .arg("--bitrate-kbps")
        .arg(capture_bitrate)
        .arg("--size")
        .arg(capture_size)
        .arg("--cursor-mode")
        .arg(cursor_mode)
        .arg("--debug-dir")
        .arg(PORTAL_DEBUG_DIR)
        .arg("--debug-fps")
        .arg(&capture_fps)
        .env("PYTHONUNBUFFERED", "1")
        .env("WBEAM_FRAMED", framed_env)
        .env("WBEAM_VIDEORATE_DROP_ONLY", videorate_drop_only)
        .env("WBEAM_FRAMED_SEND_TIMEOUT_S", framed_send_timeout_s)
        .env("WBEAM_FRAMED_DUPLICATE_STALE", framed_duplicate_stale)
        .env("WBEAM_PIPEWIRE_KEEPALIVE_MS", pipewire_keepalive_ms)
        .env("WBEAM_PIPEWIRE_ALWAYS_COPY", pipewire_always_copy)
        .env("WBEAM_FRAMED_PULL_TIMEOUT_MS", framed_pull_timeout_ms)
        .env("WBEAM_QUEUE_MAX_BUFFERS", queue_max_buffers)
        .env("WBEAM_QUEUE_MAX_TIME_MS", queue_max_time_ms)
        .env("WBEAM_APPSINK_MAX_BUFFERS", appsink_max_buffers)
        .stdin(Stdio::null())
        .stdout(Stdio::from(streamer_log.try_clone().expect("clone streamer log")))
        .stderr(Stdio::from(streamer_log))
        .spawn() {
            Ok(child) => child,
            Err(_) => {
                error!("failed to start portal streamer helper");
                return false;
            }
        };

    // Fail fast on obvious startup issues (missing plugins/deps).
    thread::sleep(Duration::from_millis(250));
    if let Ok(Some(status)) = streamer_child.try_wait() {
        error!(
            "portal streamer exited immediately with status {} (see {})",
            status,
            PORTAL_STREAMER_LOG
        );
        return false;
    }

    if source_mode.eq_ignore_ascii_case(PORTAL_SOURCE_FILE) && source_framed {
        error!(
            "PROTO_PORTAL_JPEG_SOURCE=file is incompatible with PROTO_H264_SOURCE_FRAMED=1; set PROTO_H264_SOURCE_FRAMED=0 for file mode"
        );
    } else if source_mode.eq_ignore_ascii_case(PORTAL_SOURCE_FILE) {
        thread::spawn(move || loop {
            let ffmpeg = Command::new("ffmpeg")
                .arg("-y")
                .arg("-nostdin")
                .arg("-hide_banner")
                .arg("-loglevel")
                .arg("error")
                .arg("-fflags")
                .arg("nobuffer")
                .arg("-avioflags")
                .arg("direct")
                .arg("-analyzeduration")
                .arg(FFMPEG_ANALYZEDURATION)
                .arg("-probesize")
                .arg(FFMPEG_PROBESIZE)
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
            thread::sleep(Duration::from_millis(PORTAL_PIPELINE_SETTLE_MS));
        });
    }

    info!(
        "portal capture started; framed_source={} screen picker should appear (select virtual screen if needed). logs: {PORTAL_STREAMER_LOG}, {PORTAL_FFMPEG_LOG}",
        source_framed
    );
    true
}

fn has_gst_element(name: &str) -> bool {
    Command::new("gst-inspect-1.0")
        .arg(name)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .map(|s| s.success())
        .unwrap_or(false)
}

fn read_latest_portal_debug_jpeg() -> Result<Vec<u8>, String> {
    let dir = PathBuf::from(PORTAL_DEBUG_DIR);
    if !dir.exists() {
        return Err("portal debug dir not ready".to_string());
    }

    let mut latest_path: Option<PathBuf> = None;
    let mut latest_ts = std::time::SystemTime::UNIX_EPOCH;

    for entry in fs::read_dir(&dir).map_err(|e| e.to_string())? {
        let entry = entry.map_err(|e| e.to_string())?;
        let path = entry.path();
        if path.extension().and_then(|e| e.to_str()) != Some("jpg") {
            continue;
        }

        let modified = entry
            .metadata()
            .ok()
            .and_then(|m| m.modified().ok())
            .unwrap_or(std::time::SystemTime::UNIX_EPOCH);
        if modified >= latest_ts {
            latest_ts = modified;
            latest_path = Some(path);
        }
    }

    let Some(path) = latest_path else {
        return Err("no portal debug frames yet".to_string());
    };

    fs::read(path).map_err(|e| e.to_string())
}

fn portal_only_mode() -> bool {
    cfg_var("PROTO_PORTAL_ONLY")
        .ok()
        .map(|v| matches!(v.as_str(), "1" | "true" | "TRUE" | "yes" | "YES"))
        .unwrap_or(false)
}

#[cfg(unix)]
#[inline(always)]
fn set_tcp_sndbuf(stream: &std::net::TcpStream, size: i32) {
    use std::os::unix::io::AsRawFd;
    extern "C" {
        fn setsockopt(
            sockfd: i32, level: i32, optname: i32,
            optval: *const std::ffi::c_void, optlen: u32,
        ) -> i32;
    }
    unsafe {
        setsockopt(
            stream.as_raw_fd(),
            1,
            7,
            &size as *const i32 as *const std::ffi::c_void,
            4,
        );
    }
}
#[cfg(not(unix))]
#[inline(always)]
fn set_tcp_sndbuf(_stream: &std::net::TcpStream, _size: i32) {}

#[inline(always)]
fn precise_sleep(duration: Duration) {
    const SPIN_WINDOW: Duration = Duration::from_micros(1500);
    if duration > SPIN_WINDOW {
        thread::sleep(duration - SPIN_WINDOW);
    }
    let deadline = Instant::now() + SPIN_WINDOW.min(duration);
    while Instant::now() < deadline { std::hint::spin_loop(); }
}

#[cfg(unix)]
#[cold]
fn set_thread_realtime_priority() {
    extern "C" {
        fn sched_setscheduler(pid: i32, policy: i32, param: *const u8) -> i32;
        fn nice(inc: i32) -> i32;
    }
    let sched_param: [u8; 4] = 1u32.to_ne_bytes();
    let ret = unsafe { sched_setscheduler(0, 1, sched_param.as_ptr()) };
    if ret != 0 {
        unsafe { nice(-10); }
    }
}
#[cfg(not(unix))]
#[cold]
fn set_thread_realtime_priority() {}

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
    let ext = if format == CAPTURE_FORMAT_JPEG { "jpg" } else { CAPTURE_FORMAT_PNG };
    let path = format!("/dev/shm/proto-wbeam.{}", ext);

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
    let extend_right_px = cfg_var("PROTO_EXTEND_RIGHT_PX")
        .ok()
        .and_then(|v| v.parse::<u32>().ok())
        .unwrap_or(0);

    if extend_right_px == 0 {
        return Ok(bytes.to_vec());
    }

    let codec = if format == CAPTURE_FORMAT_PNG { CAPTURE_FORMAT_PNG } else { "mjpeg" };
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

#[inline(always)]
fn frame_signature(bytes: &[u8]) -> u64 {
    const WINDOW: usize = 512;
    let len = bytes.len();
    let mut hash: u64 = 0xcbf29ce484222325;
    let offsets = [
        0usize,
        (len / 4).saturating_sub(WINDOW / 2),
        (len / 2).saturating_sub(WINDOW / 2),
        (len * 3 / 4).saturating_sub(WINDOW / 2),
        len.saturating_sub(WINDOW),
    ];
    for start in offsets {
        let end = (start + WINDOW).min(len);
        for &b in &bytes[start..end] {
            hash ^= b as u64;
            hash = hash.wrapping_mul(0x100000001b3);
        }
    }
    ((len as u64) << 32) ^ hash
}

#[inline(always)]
fn write_wbj1_frame(stream: &mut TcpStream, header: &[u8], payload: &[u8]) -> std::io::Result<()> {
    let max_chunk_bytes = cfg_var("PROTO_MAX_CHUNK_BYTES")
        .ok()
        .and_then(|v| v.parse::<usize>().ok())
        .map(|v| v.clamp(MAX_CHUNK_BYTES_MIN, MAX_CHUNK_BYTES_MAX))
        .unwrap_or(DEFAULT_MAX_CHUNK_BYTES);
    let mut header_off = 0usize;
    while header_off < header.len() {
        let n = stream.write(&header[header_off..])?;
        if n == 0 {
            return Err(std::io::Error::new(std::io::ErrorKind::WriteZero, "header write returned 0"));
        }
        header_off += n;
    }

    let mut payload_off = 0usize;
    while payload_off < payload.len() {
        let end = (payload_off + max_chunk_bytes).min(payload.len());
        let n = stream.write(&payload[payload_off..end])?;
        if n == 0 {
            return Err(std::io::Error::new(std::io::ErrorKind::WriteZero, "payload write returned 0"));
        }
        payload_off += n;
    }

    Ok(())
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
