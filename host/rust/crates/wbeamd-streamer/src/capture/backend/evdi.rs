/// EVDI direct-capture backend.
///
/// Bypasses the Wayland compositor / PipeWire stack entirely by attaching to
/// the kernel's EVDI (Extensible Virtual Display Interface) DRM device.  The
/// compositor renders into the EVDI framebuffer as if it were a real physical
/// monitor; we receive frame-update callbacks at the kernel level — no portal,
/// no IPC, no 60 Hz compositor cap.
///
/// Prerequisites (one-time setup):
///   sudo modprobe evdi initial_device_count=4
/// or run:
///   scripts/set-capture-mode.sh evdi
use std::ffi::c_void;
use std::time::{Duration, Instant};
use std::{ptr, thread};

use anyhow::{Context, Result};
use gstreamer as gst;
use gstreamer::prelude::*;
use gstreamer_app as gst_app;

use crate::cli::ResolvedConfig;

use super::evdi_ffi as ffi;

// ─── Constants ─────────────────────────────────────────────────────────────

/// EVDI outputs XRGB8888 (4 bytes per pixel, X=padding).
const BYTES_PER_PIXEL: usize = 4;

/// Maximum dirty rectangles EVDI reports per frame.
const MAX_RECTS: usize = 16;
/// If the screen stayed static for this long, mark the first returning frame
/// as DISCONT so downstream can request an IDR on wake-up.
const WAKE_DISCONT_THRESHOLD_MS: u128 = 120;
/// Send a periodic keepalive frame even when idle to prevent Android decoder
/// from stalling and to keep the stream fresh after long inactivity.
const IDLE_KEEPALIVE_INTERVAL_MS: u128 = 5_000;

// ─── Dynamic EDID generation ────────────────────────────────────────────────

/// Build a minimal 128-byte EDID block advertising `width`×`height` @ `fps` Hz.
/// The EDID is a base block only (no extensions) with a single Detailed Timing
/// Descriptor.  This is enough for EVDI to expose the desired mode to the
/// compositor.
fn generate_edid(width: i32, height: i32, fps: i32) -> [u8; 128] {
    let mut edid = [0u8; 128];

    // Fixed header
    edid[0..8].copy_from_slice(&[0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x00]);

    // Manufacturer ID "WBM" (W=0x17, B=0x02, M=0x0D → 0x5C 0x2D)
    edid[8] = 0x5C;
    edid[9] = 0x2D;
    // Product code
    edid[10] = 0x01;
    edid[11] = 0x00;
    // Serial
    edid[12..16].copy_from_slice(&[0x01, 0x00, 0x00, 0x00]);
    // Week / Year of manufacture (week 1, 2025)
    edid[16] = 0x01;
    edid[17] = 35; // 2025 - 1990
    // EDID version 1.3
    edid[18] = 0x01;
    edid[19] = 0x03;
    // Digital input, 8 bits per color
    edid[20] = 0x80;
    // Screen size cm (approximate from pixels at ~4 dpi)
    edid[21] = ((width as u32 * 254 / 960).min(255)) as u8;
    edid[22] = ((height as u32 * 254 / 960).min(255)) as u8;
    // Gamma 2.2
    edid[23] = 120; // (gamma * 100) - 100
    // Feature support: RGB color, preferred timing in DTD1
    edid[24] = 0x0A;

    // Chromaticity (sRGB defaults, compact)
    edid[25..35].copy_from_slice(&[
        0xEE, 0x91, 0xA3, 0x54, 0x4C, 0x99, 0x26, 0x0F, 0x50, 0x54,
    ]);

    // Established timings — none
    edid[35] = 0x00;
    edid[36] = 0x00;
    edid[37] = 0x00;

    // Standard timings — unused (0x0101 = not used)
    for i in (38..54).step_by(2) {
        edid[i] = 0x01;
        edid[i + 1] = 0x01;
    }

    // ── Detailed Timing Descriptor (18 bytes at offset 54) ──────────────
    let w = width as u32;
    let h = height as u32;
    let f = fps as u32;

    // Typical blanking for a flat-panel display
    let h_blank: u32 = if w > 1920 { 280 } else { 160 };
    let v_blank: u32 = if h > 1080 { 60 } else { 45 };

    let h_total = w + h_blank;
    let v_total = h + v_blank;
    let pixel_clock_khz = h_total * v_total * f / 1000;
    let pixel_clock_10k = pixel_clock_khz / 10;

    // Sync pulse / porch (reasonable defaults)
    let h_sync_offset: u32 = 48;
    let h_sync_width: u32 = 32;
    let v_sync_offset: u32 = 3;
    let v_sync_width: u32 = 5;

    let dtd = &mut edid[54..72];
    dtd[0] = (pixel_clock_10k & 0xFF) as u8;
    dtd[1] = ((pixel_clock_10k >> 8) & 0xFF) as u8;
    dtd[2] = (w & 0xFF) as u8;
    dtd[3] = (h_blank & 0xFF) as u8;
    dtd[4] = (((w >> 8) & 0x0F) << 4 | ((h_blank >> 8) & 0x0F)) as u8;
    dtd[5] = (h & 0xFF) as u8;
    dtd[6] = (v_blank & 0xFF) as u8;
    dtd[7] = (((h >> 8) & 0x0F) << 4 | ((v_blank >> 8) & 0x0F)) as u8;
    dtd[8] = (h_sync_offset & 0xFF) as u8;
    dtd[9] = (h_sync_width & 0xFF) as u8;
    dtd[10] = ((v_sync_offset & 0x0F) << 4 | (v_sync_width & 0x0F)) as u8;
    dtd[11] = (((h_sync_offset >> 8) & 0x03) << 6
        | ((h_sync_width >> 8) & 0x03) << 4
        | ((v_sync_offset >> 4) & 0x03) << 2
        | ((v_sync_width >> 4) & 0x03)) as u8;
    // Physical image size mm (approximate)
    let h_mm = w * 254 / 960;
    let v_mm = h * 254 / 960;
    dtd[12] = (h_mm & 0xFF) as u8;
    dtd[13] = (v_mm & 0xFF) as u8;
    dtd[14] = (((h_mm >> 8) & 0x0F) << 4 | ((v_mm >> 8) & 0x0F)) as u8;
    dtd[15] = 0; // border pixels H
    dtd[16] = 0; // border pixels V
    dtd[17] = 0x18; // non-interlaced, digital separate sync

    // ── Descriptor 2: Monitor name "WBeam" ──────────────────────────────
    let name_desc = &mut edid[72..90];
    name_desc[0] = 0x00;
    name_desc[1] = 0x00;
    name_desc[2] = 0x00;
    name_desc[3] = 0xFC; // tag: monitor name
    name_desc[4] = 0x00;
    let name_bytes = b"WBeam\n";
    name_desc[5..5 + name_bytes.len()].copy_from_slice(name_bytes);
    for b in &mut name_desc[5 + name_bytes.len()..18] {
        *b = 0x20; // pad with spaces
    }

    // ── Descriptor 3: Monitor range limits ──────────────────────────────
    let range_desc = &mut edid[90..108];
    range_desc[0] = 0x00;
    range_desc[1] = 0x00;
    range_desc[2] = 0x00;
    range_desc[3] = 0xFD; // tag: range limits
    range_desc[4] = 0x00;
    range_desc[5] = 50;  // min V Hz
    range_desc[6] = (fps.max(60) as u8).min(120).max(60); // max V Hz
    range_desc[7] = 30;  // min H kHz
    range_desc[8] = ((pixel_clock_khz / h_total + 5) as u8).max(70); // max H kHz
    range_desc[9] = ((pixel_clock_10k / 1000 + 1) as u8).max(15); // max pixel clock / 10 MHz
    range_desc[10] = 0x00; // GTF
    for b in &mut range_desc[11..18] {
        *b = 0x0A;
    }

    // ── Descriptor 4: Dummy ─────────────────────────────────────────────
    let dummy = &mut edid[108..126];
    dummy[0] = 0x00;
    dummy[1] = 0x00;
    dummy[2] = 0x00;
    dummy[3] = 0x10; // tag: dummy
    dummy[4] = 0x00;
    for b in &mut dummy[5..18] {
        *b = 0x00;
    }

    // Extension count
    edid[126] = 0;

    // Checksum: make all 128 bytes sum to 0 mod 256
    let sum: u32 = edid[0..127].iter().map(|&b| b as u32).sum();
    edid[127] = (256 - (sum % 256)) as u8;

    edid
}

// ─── Wrapper so we can send the opaque EVDI handle across threads ───────────

struct SendHandle(*mut c_void);
unsafe impl Send for SendHandle {}
unsafe impl Sync for SendHandle {}

// ─── C callbacks ────────────────────────────────────────────────────────────

unsafe extern "C" fn on_update_ready(_buf_id: i32, _user_data: *mut c_void) {
    // We call evdi_grab_pixels immediately after evdi_handle_events returns
    // rather than in the callback, so nothing needs to happen here.
}

unsafe extern "C" fn on_mode_changed(_mode: ffi::EvdiMode, _user_data: *mut c_void) {
    // Mode negotiation happens at init time; ignore subsequent changes.
}

// ─── Device discovery ───────────────────────────────────────────────────────

/// Lock file path used to coordinate EVDI device allocation across streamer
/// processes.  Each streamer holds an exclusive (LOCK_EX | LOCK_NB) flock on
/// its claimed device file so concurrent streamers skip already-taken indices.
fn evdi_lock_path(idx: i32) -> String {
    format!("/tmp/wbeam-evdi-{idx}.lock")
}

/// Try to acquire an exclusive, non-blocking flock.  Returns the open File on
/// success (caller must keep it alive for the duration of capture).
fn try_evdi_lock(idx: i32) -> Option<std::fs::File> {
    use std::fs::OpenOptions;
    let path = evdi_lock_path(idx);
    let file = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(false)
        .open(&path)
        .ok()?;
    let fd = std::os::unix::io::AsRawFd::as_raw_fd(&file);
    let rc = unsafe { libc::flock(fd, libc::LOCK_EX | libc::LOCK_NB) };
    if rc == 0 {
        Some(file)
    } else {
        None
    }
}

/// Verify that `idx` is genuinely an EVDI device (not a GPU or phantom index).
fn is_confirmed_evdi(idx: i32) -> bool {
    let status = unsafe { ffi::evdi_check_device(idx) };
    status == ffi::EvdiDeviceStatus::Available
}

/// Check whether /dev/dri/card{idx} already has a DRM connector in "connected"
/// state (e.g. Xwayland already drives it).
///
/// EVDI connector names vary by kernel version (DVI-I-N, Virtual-N, etc.)
/// so we scan all `/sys/class/drm/card{idx}-*/status` entries.
fn is_evdi_card_in_use_by_others(idx: i32) -> bool {
    let prefix = format!("card{idx}-");
    let drm_dir = match std::fs::read_dir("/sys/class/drm") {
        Ok(d) => d,
        Err(_) => return false,
    };
    for entry in drm_dir.flatten() {
        let name = entry.file_name();
        let name_str = name.to_string_lossy();
        if !name_str.starts_with(&prefix) {
            continue;
        }
        let status_path = entry.path().join("status");
        if let Ok(status) = std::fs::read_to_string(&status_path) {
            if status.trim() == "connected" {
                eprintln!(
                    "[wbeam-evdi] card{idx} connector {name_str} status='connected' — already in use"
                );
                return true;
            }
        }
    }
    false
}

/// Try to claim EVDI device `idx`:
/// 1. Must be a confirmed EVDI device
/// 2. Must not already be connected by another DRM client
/// 3. Must win the flock race against other WBeam streamers
fn try_claim_evdi(idx: i32) -> Option<std::fs::File> {
    if !is_confirmed_evdi(idx) {
        return None;
    }
    if is_evdi_card_in_use_by_others(idx) {
        return None;
    }
    try_evdi_lock(idx)
}

fn find_evdi_device() -> Result<(i32, Option<std::fs::File>)> {
    // 1. Try creating a brand-new EVDI device (requires root / sysfs write).
    //    evdi_add_device() can return 0 instead of -1 on permission-denied,
    //    so we verify the returned index is actually EVDI before trusting it.
    let new_idx = unsafe { ffi::evdi_add_device() };
    if new_idx >= 0 && is_confirmed_evdi(new_idx) {
        eprintln!("[wbeam-evdi] evdi_add_device created new device at index {new_idx}");
        if let Some(lock) = try_evdi_lock(new_idx) {
            return Ok((new_idx, Some(lock)));
        }
        eprintln!("[wbeam-evdi] WARNING: newly created EVDI {new_idx} already locked; scanning");
    }
    if new_idx >= 0 {
        eprintln!(
            "[wbeam-evdi] evdi_add_device returned {new_idx} but it is not a free EVDI device; ignoring"
        );
    }

    // 2. Scan existing devices — skip non-EVDI, already-connected, and locked.
    for idx in 0..16i32 {
        if let Some(lock) = try_claim_evdi(idx) {
            eprintln!("[wbeam-evdi] claimed free EVDI device at index {idx}");
            return Ok((idx, Some(lock)));
        }
    }

    // 3. Last resort — try add_device once more (module may have been
    //    loaded between first attempt and now).
    let retry = unsafe { ffi::evdi_add_device() };
    if retry >= 0 && is_confirmed_evdi(retry) {
        eprintln!("[wbeam-evdi] evdi_add_device retry created device at index {retry}");
        if let Some(lock) = try_evdi_lock(retry) {
            return Ok((retry, Some(lock)));
        }
        eprintln!("[wbeam-evdi] WARNING: retry EVDI {retry} lock failed");
    }

    Err(anyhow::anyhow!(
        "No free EVDI device found (all locked by other streamers or in use by compositor). \
         Ensure enough EVDI devices are available: \
         sudo modprobe -r evdi && sudo modprobe evdi initial_device_count=4\n\
         or add more at runtime: echo 1 | sudo tee /sys/devices/evdi/add"
    ))
}

// ─── EVDI event loop (runs on a dedicated thread) ───────────────────────────

struct EvdiLoopStats {
    published_frames: u64,
    pushed_frames: u64,
    wake_discont_marks: u64,
    zero_rect_polls: u64,
    event_poll_timeouts: u64,
    idle_keepalive_frames: u64,
    last_frame_at: Instant,
    last_wait_log_at: Instant,
    last_pts_ns: u64,
    capture_started_at: Instant,
}

impl EvdiLoopStats {
    fn new() -> Self {
        Self {
            published_frames: 0,
            pushed_frames: 0,
            wake_discont_marks: 0,
            zero_rect_polls: 0,
            event_poll_timeouts: 0,
            idle_keepalive_frames: 0,
            last_frame_at: Instant::now(),
            last_wait_log_at: Instant::now(),
            last_pts_ns: 0,
            capture_started_at: Instant::now(),
        }
    }
}

fn register_evdi_buffer(
    handle: *mut c_void,
    pixels: &mut [u8],
    rects: &mut [ffi::EvdiRect],
    width: i32,
    height: i32,
) {
    let evdi_buf = ffi::EvdiBuffer {
        id: 0,
        buffer: pixels.as_mut_ptr() as *mut c_void,
        width,
        height,
        stride: width * BYTES_PER_PIXEL as i32,
        rects: rects.as_mut_ptr(),
        rect_count: rects.len() as i32,
    };
    unsafe { ffi::evdi_register_buffer(handle, evdi_buf) };
}

fn evdi_event_context() -> ffi::EvdiEventContext {
    ffi::EvdiEventContext {
        dpms_handler: None,
        mode_changed_handler: Some(on_mode_changed),
        update_ready_handler: Some(on_update_ready),
        crtc_state_handler: None,
        cursor_set_handler: None,
        cursor_move_handler: None,
        ddcci_data_handler: None,
        user_data: ptr::null_mut(),
    }
}

fn wait_for_frame(
    handle: *mut c_void,
    fd: i32,
    ctx: &mut ffi::EvdiEventContext,
    stats: &mut EvdiLoopStats,
    width: i32,
    height: i32,
    poll_timeout_ms: i32,
) -> bool {
    let immediate = unsafe { ffi::evdi_request_update(handle, 0) };
    if immediate {
        return true;
    }

    let mut pollfd = libc::pollfd {
        fd,
        events: libc::POLLIN,
        revents: 0,
    };
    let polled = unsafe { libc::poll(&mut pollfd as *mut libc::pollfd, 1, poll_timeout_ms) };
    if polled <= 0 {
        stats.event_poll_timeouts = stats.event_poll_timeouts.saturating_add(1);
        maybe_log_event_wait(stats, width, height);
        return false;
    }

    stats.event_poll_timeouts = 0;
    unsafe { ffi::evdi_handle_events(handle, ctx) };
    unsafe { ffi::evdi_request_update(handle, 0) }
}

fn maybe_log_event_wait(stats: &mut EvdiLoopStats, width: i32, height: i32) {
    if stats.last_wait_log_at.elapsed() < Duration::from_secs(2) {
        return;
    }
    println!(
        "[wbeam-evdi] waiting for compositor events: no POLLIN for {} ms (poll_timeouts={}, published_frames={}, configured={}x{})",
        stats.last_frame_at.elapsed().as_millis(),
        stats.event_poll_timeouts,
        stats.published_frames,
        width,
        height
    );
    stats.last_wait_log_at = Instant::now();
}

fn maybe_log_zero_rect_wait(stats: &mut EvdiLoopStats, width: i32, height: i32) {
    if stats.last_wait_log_at.elapsed() < Duration::from_secs(2) {
        return;
    }
    println!(
        "[wbeam-evdi] waiting for dirty rects: no updates for {} ms (polls={}, published_frames={}, configured={}x{})",
        stats.last_frame_at.elapsed().as_millis(),
        stats.zero_rect_polls,
        stats.published_frames,
        width,
        height
    );
    stats.last_wait_log_at = Instant::now();
}

fn acquire_pooled_buffer(pool: &gst::BufferPool, pixels: &[u8]) -> Option<gst::Buffer> {
    let mut buf = pool.acquire_buffer(None).ok()?;
    {
        let bm = buf.get_mut()?;
        let mut map = bm.map_writable().ok()?;
        map.as_mut_slice()[..pixels.len()].copy_from_slice(pixels);
    }
    Some(buf)
}

fn push_frame(
    appsrc: &gst_app::AppSrc,
    pixels: &[u8],
    rects: &[ffi::EvdiRect],
    num_rects: i32,
    frame_duration_ns: u64,
    stats: &mut EvdiLoopStats,
    pool: &gst::BufferPool,
) -> bool {
    let idle_before_frame_ms = stats.last_frame_at.elapsed().as_millis();
    let wake_discont = stats.published_frames > 0 && idle_before_frame_ms >= WAKE_DISCONT_THRESHOLD_MS;

    stats.zero_rect_polls = 0;
    stats.last_frame_at = Instant::now();
    stats.published_frames = stats.published_frames.saturating_add(1);
    log_dirty_rect_update(stats, rects, num_rects);

    let mut buf = match acquire_pooled_buffer(pool, pixels) {
        Some(b) => b,
        None => gst::Buffer::from_slice(pixels.to_vec()),
    };
    if let Some(bm) = buf.get_mut() {
        let pts_ns = stats.capture_started_at.elapsed().as_nanos() as u64;
        let dur_ns = if stats.last_pts_ns > 0 {
            pts_ns.saturating_sub(stats.last_pts_ns).max(1_000_000)
        } else {
            frame_duration_ns
        };
        stats.last_pts_ns = pts_ns;
        if wake_discont {
            bm.set_flags(gst::BufferFlags::DISCONT);
            stats.wake_discont_marks = stats.wake_discont_marks.saturating_add(1);
            if stats.wake_discont_marks <= 3 || stats.wake_discont_marks % 60 == 0 {
                println!(
                    "[wbeam-evdi] wake-discont mark: idle={}ms marks={} frame={}",
                    idle_before_frame_ms, stats.wake_discont_marks, stats.published_frames
                );
            }
        }
        bm.set_pts(gst::ClockTime::from_nseconds(pts_ns));
        bm.set_duration(gst::ClockTime::from_nseconds(dur_ns));
    }

    stats.pushed_frames = stats.pushed_frames.saturating_add(1);
    let flow = appsrc.push_buffer(buf);
    if stats.pushed_frames <= 3 || stats.pushed_frames % 120 == 0 {
        println!(
            "[wbeam-evdi] appsrc push: pushed_frames={} published_frames={} flow={flow:?}",
            stats.pushed_frames, stats.published_frames
        );
    }
    if let Err(ref err) = flow {
        eprintln!(
            "[wbeam-evdi] ERROR: appsrc push failed at pushed_frames={}: {err:?}",
            stats.pushed_frames
        );
        return false;
    }
    true
}

fn log_dirty_rect_update(stats: &EvdiLoopStats, rects: &[ffi::EvdiRect], num_rects: i32) {
    if !(stats.published_frames == 1 || stats.published_frames % 120 == 0) {
        return;
    }
    if num_rects <= 0 || rects.is_empty() {
        println!(
            "[wbeam-evdi] frame update: rects=0 (keepalive) published_frames={}",
            stats.published_frames
        );
        return;
    }
    let first = rects[0];
    println!(
        "[wbeam-evdi] frame update: rects={} first=({},{} {}x{}) published_frames={}",
        num_rects,
        first.x1,
        first.y1,
        first.x2.saturating_sub(first.x1),
        first.y2.saturating_sub(first.y1),
        stats.published_frames
    );
}

fn evdi_loop(
    raw_handle: SendHandle,
    width: i32,
    height: i32,
    frame_size: usize,
    frame_duration_ns: u64,
    appsrc: gst_app::AppSrc,
) {
    let handle = raw_handle.0;
    let mut stats = EvdiLoopStats::new();
    let mut pixels = vec![0u8; frame_size];
    let mut rects = vec![ffi::EvdiRect::default(); MAX_RECTS];
    let poll_timeout_ms = ((frame_duration_ns / 1_000_000) as i32).clamp(4, 16);

    // Pre-allocate a GStreamer buffer pool to avoid per-frame malloc/free of 8MB buffers.
    let pool = gst::BufferPool::new();
    {
        let mut config = pool.config();
        config.set_params(None, frame_size as u32, 2, 8);
        pool.set_config(config).expect("EVDI buffer pool config");
    }
    pool.set_active(true).expect("EVDI buffer pool activate");

    register_evdi_buffer(handle, &mut pixels, &mut rects, width, height);

    let fd = unsafe { ffi::evdi_get_event_ready(handle) };
    let mut ctx = evdi_event_context();

    loop {
        if !wait_for_frame(
            handle,
            fd,
            &mut ctx,
            &mut stats,
            width,
            height,
            poll_timeout_ms,
        ) {
            continue;
        }

        let mut num_rects = 0i32;
        unsafe { ffi::evdi_grab_pixels(handle, rects.as_mut_ptr(), &mut num_rects) };

        if num_rects > 0 {
            if !push_frame(
                &appsrc,
                &pixels,
                &rects,
                num_rects,
                frame_duration_ns,
                &mut stats,
                &pool,
            ) {
                break;
            }
            continue;
        }

        stats.zero_rect_polls = stats.zero_rect_polls.saturating_add(1);
        maybe_log_zero_rect_wait(&mut stats, width, height);

        // Push periodic keepalive frames during idle to prevent Android decoder
        // stall and maintain stream freshness after long inactivity.
        if stats.last_frame_at.elapsed().as_millis() >= IDLE_KEEPALIVE_INTERVAL_MS {
            let idle_ms = stats.last_frame_at.elapsed().as_millis();
            stats.idle_keepalive_frames = stats.idle_keepalive_frames.saturating_add(1);
            if stats.idle_keepalive_frames <= 3 || stats.idle_keepalive_frames % 30 == 0 {
                println!(
                    "[wbeam-evdi] idle keepalive: idle={}ms keepalives={} published={}",
                    idle_ms, stats.idle_keepalive_frames, stats.published_frames
                );
            }
            if !push_frame(
                &appsrc,
                &pixels,
                &rects,
                0,
                frame_duration_ns,
                &mut stats,
                &pool,
            ) {
                break;
            }
        }
    }

    let _ = pool.set_active(false);

    unsafe {
        ffi::evdi_unregister_buffer(handle, 0);
        ffi::evdi_disconnect(handle);
        ffi::evdi_close(handle);
    }
}

// ─── Public entry point ──────────────────────────────────────────────────────

pub fn build_source(cfg: &ResolvedConfig) -> Result<gst::Element> {
    let fps = cfg.fps.max(1);
    let requested_width = cfg.width as i32;
    let requested_height = cfg.height as i32;
    // Ensure landscape orientation and even dimensions for encoder compat.
    let width = std::cmp::max(requested_width, requested_height);
    let height = std::cmp::min(requested_width, requested_height);
    let width = if width % 2 != 0 { width - 1 } else { width };
    let height = if height % 2 != 0 { height - 1 } else { height };
    eprintln!(
        "[wbeam-evdi] EDID target: {}×{} @ {} fps (requested {}×{})",
        width, height, fps, requested_width, requested_height
    );
    let frame_size = width as usize * height as usize * BYTES_PER_PIXEL;
    let frame_duration_ns = 1_000_000_000u64 / fps as u64;
    let edid = generate_edid(width, height, fps as i32);

    // ── 1. Find/open EVDI device ────────────────────────────────────────────
    let (device_idx, evdi_lock) = find_evdi_device()?;
    let handle = unsafe { ffi::evdi_open(device_idx) };
    if handle.is_null() {
        return Err(anyhow::anyhow!(
            "Failed to open EVDI device {device_idx}. \
             Check permissions (video group) and that evdi module is loaded."
        ));
    }
    println!("[wbeam-evdi] Opened /dev/dri/card{device_idx}");

    // ── 2. Connect with dynamic EDID — cursor events disabled ─────────────
    unsafe {
        ffi::evdi_connect2(
            handle,
            edid.as_ptr(),
            edid.len() as u32,
            (width * height) as u32,
            (width * height * fps as i32) as u32,
        );
        ffi::evdi_enable_cursor_events(handle, false);
    }
    println!(
        "[wbeam-evdi] Connected virtual display {}×{} @ {fps} fps",
        width, height
    );

    // ── 3. Wait for mode negotiation ────────────────────────────────────────
    // Give the compositor ~500 ms to process the new connector and set a mode.
    let fd = unsafe { ffi::evdi_get_event_ready(handle) };
    let mut ctx = ffi::EvdiEventContext {
        dpms_handler: None,
        mode_changed_handler: Some(on_mode_changed),
        update_ready_handler: None,
        crtc_state_handler: None,
        cursor_set_handler: None,
        cursor_move_handler: None,
        ddcci_data_handler: None,
        user_data: ptr::null_mut(),
    };
    let deadline = std::time::Instant::now() + Duration::from_millis(1500);
    while std::time::Instant::now() < deadline {
        let mut pollfd = libc::pollfd {
            fd,
            events: libc::POLLIN,
            revents: 0,
        };
        let r = unsafe { libc::poll(&mut pollfd as *mut libc::pollfd, 1, 100) };
        if r > 0 {
            unsafe { ffi::evdi_handle_events(handle, &mut ctx) };
        }
    }

    // ── 4. Build appsrc (EVDI thread pushes frames directly) ────────────────
    let appsrc_el = gst::ElementFactory::make("appsrc")
        .name("evdi_src")
        .build()
        .context("appsrc missing — install gstreamer1-plugins-base")?;
    let appsrc = appsrc_el
        .clone()
        .dynamic_cast::<gst_app::AppSrc>()
        .map_err(|_| anyhow::anyhow!("evdi_src cast failed"))?;

    appsrc.set_is_live(true);
    appsrc.set_format(gst::Format::Time);
    appsrc.set_do_timestamp(false);
    appsrc.set_block(false);
    // Keep queue bounded to ~1 frame for low latency.
    appsrc.set_max_bytes(frame_size as u64);

    // EVDI delivers XRGB8888 which GStreamer calls BGRx.
    appsrc.set_caps(Some(
        &gst::Caps::builder("video/x-raw")
            .field("format", "BGRx")
            .field("width", width)
            .field("height", height)
            .field("framerate", gst::Fraction::new(fps as i32, 1))
            .build(),
    ));

    // ── 5. Spawn the frame-capture thread ───────────────────────────────────
    {
        let raw = SendHandle(handle);
        let appsrc_for_thread = appsrc.clone();
        thread::Builder::new()
            .name("wbeam-evdi".into())
            .spawn(move || {
                // Hold the lock file open for the lifetime of the capture
                // thread so other streamers skip this EVDI device index.
                let _evdi_lock_guard = evdi_lock;
                evdi_loop(
                    raw,
                    width,
                    height,
                    frame_size,
                    frame_duration_ns,
                    appsrc_for_thread,
                )
            })
            .context("spawn evdi thread")?;
    }

    Ok(appsrc_el)
}
