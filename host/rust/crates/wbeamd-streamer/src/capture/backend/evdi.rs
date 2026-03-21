/// EVDI direct-capture backend.
///
/// Bypasses the Wayland compositor / PipeWire stack entirely by attaching to
/// the kernel's EVDI (Extensible Virtual Display Interface) DRM device.  The
/// compositor renders into the EVDI framebuffer as if it were a real physical
/// monitor; we receive frame-update callbacks at the kernel level — no portal,
/// no IPC, no 60 Hz compositor cap.
///
/// Prerequisites (one-time setup):
///   sudo modprobe evdi initial_device_count=1
/// or run:
///   scripts/set-capture-mode.sh evdi
use std::ffi::c_void;
use std::sync::Mutex;
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

// ─── Dynamic EDID generation ───────────────────────────────────────────────

/// Build a 128-byte EDID block for the requested resolution and refresh rate.
/// Uses CVT Reduced-Blanking (v1) timings so the compositor creates a virtual
/// display mode that matches the stream output exactly — no videoscale needed.
fn generate_edid(width: u32, height: u32, refresh_hz: u32) -> [u8; 128] {
    // CVT-RB blanking intervals
    let h_blank: u32 = 160;
    let h_front: u32 = 48;
    let h_sync: u32 = 32;
    let v_blank: u32 = 30;
    let v_front: u32 = 3;
    let v_sync: u32 = 5;

    let h_total = width + h_blank;
    let v_total = height + v_blank;
    let pixel_clock_hz: u64 = h_total as u64 * v_total as u64 * refresh_hz as u64;
    let pixel_clock_10khz = (pixel_clock_hz / 10_000).min(65535) as u16;

    // Approximate physical size at ~100 DPI → mm → cm for EDID fields.
    let h_mm = (width * 254 + 500) / 1000;
    let v_mm = (height * 254 + 500) / 1000;

    let mut edid = [0u8; 128];

    // Header
    edid[0..8].copy_from_slice(&[0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x00]);
    // Manufacturer "WBM" (5-bit packed: W=23 B=2 M=13)
    edid[8] = 0x5C;
    edid[9] = 0x4D;
    // Product code
    edid[10] = 0x01;
    // Week 1, year 2026
    edid[16] = 0x01;
    edid[17] = 0x24;
    // EDID 1.4
    edid[18] = 0x01;
    edid[19] = 0x04;
    // Digital input, 8 bpc, DisplayPort-compatible
    edid[20] = 0xA5;
    edid[21] = (h_mm / 10).min(255) as u8;
    edid[22] = (v_mm / 10).min(255) as u8;
    edid[23] = 0x78; // gamma 2.2
    edid[24] = 0x0A; // RGB, preferred timing in DTD1

    // Standard timings (unused: 0x0101 pairs)
    for i in (38..54).step_by(2) {
        edid[i] = 0x01;
        edid[i + 1] = 0x01;
    }

    // ── Detailed Timing Descriptor (bytes 54-71) ────────────────────────────
    edid[54] = (pixel_clock_10khz & 0xFF) as u8;
    edid[55] = (pixel_clock_10khz >> 8) as u8;
    edid[56] = (width & 0xFF) as u8;
    edid[57] = (h_blank & 0xFF) as u8;
    edid[58] = (((width >> 8) & 0xF) << 4 | ((h_blank >> 8) & 0xF)) as u8;
    edid[59] = (height & 0xFF) as u8;
    edid[60] = (v_blank & 0xFF) as u8;
    edid[61] = (((height >> 8) & 0xF) << 4 | ((v_blank >> 8) & 0xF)) as u8;
    edid[62] = (h_front & 0xFF) as u8;
    edid[63] = (h_sync & 0xFF) as u8;
    edid[64] = ((v_front & 0xF) << 4 | (v_sync & 0xF)) as u8;
    edid[66] = (h_mm & 0xFF) as u8;
    edid[67] = (v_mm & 0xFF) as u8;
    edid[68] = (((h_mm >> 8) & 0xF) << 4 | ((v_mm >> 8) & 0xF)) as u8;
    edid[71] = 0x1E; // non-interlaced, digital separate, H+V positive

    // ── Monitor Name (bytes 72-89) ──────────────────────────────────────────
    edid[75] = 0xFC;
    edid[77..91].copy_from_slice(b"WBeam EVDI\n   ");

    // ── Monitor Range Limits (bytes 90-107) ─────────────────────────────────
    let max_pclk_mhz_div10 = ((pixel_clock_hz / 1_000_000 + 9) / 10).max(1).min(255) as u8;
    let max_h_freq_khz = ((pixel_clock_hz / h_total as u64 + 999) / 1000).min(255) as u8;
    edid[93] = 0xFD; // range-limits tag
    edid[95] = 24;   // min V freq Hz
    edid[96] = refresh_hz.min(255) as u8; // max V freq Hz
    edid[97] = 30;   // min H freq kHz
    edid[98] = max_h_freq_khz;
    edid[99] = max_pclk_mhz_div10;
    edid[101] = 0x0A;
    for i in 102..108 {
        edid[i] = 0x20;
    }

    // ── Dummy Descriptor (bytes 108-125) ────────────────────────────────────
    edid[111] = 0x10;

    // ── Checksum (sum of all 128 bytes ≡ 0 mod 256) ────────────────────────
    let sum: u8 = edid[..127].iter().copied().fold(0u8, |a, b| a.wrapping_add(b));
    edid[127] = 0u8.wrapping_sub(sum);

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

/// Shared state written by `on_mode_changed` callback during init negotiation.
struct NegotiatedMode {
    width: i32,
    height: i32,
}

unsafe extern "C" fn on_mode_changed(mode: ffi::EvdiMode, user_data: *mut c_void) {
    if user_data.is_null() {
        return;
    }
    let state = &*(user_data as *const Mutex<Option<NegotiatedMode>>);
    if let Ok(mut guard) = state.lock() {
        println!(
            "[wbeam-evdi] compositor mode: {}×{} @ {}Hz bpp={}",
            mode.width, mode.height, mode.refresh_rate, mode.bits_per_pixel
        );
        *guard = Some(NegotiatedMode {
            width: mode.width,
            height: mode.height,
        });
    }
}

// ─── Device discovery ───────────────────────────────────────────────────────

fn find_evdi_device() -> Result<i32> {
    for idx in 0..16i32 {
        let status = unsafe { ffi::evdi_check_device(idx) };
        if status == ffi::EvdiDeviceStatus::Available {
            return Ok(idx);
        }
    }
    // Try to create a new EVDI device (requires the evdi module loaded
    // with at least initial_device_count=1, or root for sysfs write).
    let new_idx = unsafe { ffi::evdi_add_device() };
    if new_idx >= 0 {
        return Ok(new_idx);
    }
    Err(anyhow::anyhow!(
        "No EVDI device found. Run: sudo modprobe evdi initial_device_count=1\n\
         or: scripts/set-capture-mode.sh evdi"
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
    let frame_duration_ns = 1_000_000_000u64 / fps as u64;

    let edid = generate_edid(cfg.width, cfg.height, cfg.fps);

    // ── 1. Find/open EVDI device ────────────────────────────────────────────
    let device_idx = find_evdi_device()?;
    let handle = unsafe { ffi::evdi_open(device_idx) };
    if handle.is_null() {
        return Err(anyhow::anyhow!(
            "Failed to open EVDI device {device_idx}. \
             Check permissions (video group) and that evdi module is loaded."
        ));
    }
    println!("[wbeam-evdi] Opened /dev/dri/card{device_idx}");

    // ── 2. Connect with dynamic EDID matching requested resolution + fps ────
    unsafe {
        ffi::evdi_connect2(
            handle,
            edid.as_ptr(),
            edid.len() as u32,
            (requested_width * requested_height) as u32,
            (requested_width as u64 * requested_height as u64 * fps as u64)
                .min(u32::MAX as u64) as u32,
        );
        ffi::evdi_enable_cursor_events(handle, false);
    }
    println!(
        "[wbeam-evdi] Requested virtual display {}×{} @ {fps} fps",
        requested_width, requested_height
    );

    // ── 3. Wait for mode negotiation ────────────────────────────────────────
    // The compositor reads our EDID and may pick a different mode.  Capture
    // the actual negotiated dimensions via the on_mode_changed callback so the
    // grab buffer matches what the compositor renders.
    let negotiated = Mutex::new(None::<NegotiatedMode>);
    let fd = unsafe { ffi::evdi_get_event_ready(handle) };
    let mut ctx = ffi::EvdiEventContext {
        dpms_handler: None,
        mode_changed_handler: Some(on_mode_changed),
        update_ready_handler: None,
        crtc_state_handler: None,
        cursor_set_handler: None,
        cursor_move_handler: None,
        ddcci_data_handler: None,
        user_data: &negotiated as *const Mutex<Option<NegotiatedMode>> as *mut c_void,
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

    // Use actual compositor mode; fall back to requested if negotiation silent.
    let (width, height) = if let Some(mode) = negotiated.lock().unwrap().as_ref() {
        if mode.width != requested_width || mode.height != requested_height {
            eprintln!(
                "[wbeam-evdi] WARN: compositor chose {}×{} (requested {}×{}); adapting grab buffer",
                mode.width, mode.height, requested_width, requested_height
            );
        }
        (mode.width, mode.height)
    } else {
        eprintln!(
            "[wbeam-evdi] WARN: no mode_changed event; assuming {}×{}",
            requested_width, requested_height
        );
        (requested_width, requested_height)
    };

    let frame_size = width as usize * height as usize * BYTES_PER_PIXEL;
    println!(
        "[wbeam-evdi] Capture geometry {}×{} @ {fps} fps (frame_size={})",
        width, height, frame_size
    );

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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn edid_checksum_valid() {
        let edid = generate_edid(1920, 1080, 60);
        let sum: u8 = edid.iter().copied().fold(0u8, |a, b| a.wrapping_add(b));
        assert_eq!(sum, 0, "EDID checksum must make byte-sum ≡ 0 mod 256");
    }

    #[test]
    fn edid_header_and_version() {
        let edid = generate_edid(2560, 1600, 120);
        assert_eq!(&edid[0..8], &[0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x00]);
        assert_eq!(edid[18], 0x01); // EDID v1
        assert_eq!(edid[19], 0x04); // .4
    }

    #[test]
    fn edid_resolution_encoded_correctly() {
        let edid = generate_edid(1920, 1080, 120);
        // Detailed timing: h_active
        let h_lo = edid[56] as u32;
        let h_hi = ((edid[58] >> 4) & 0xF) as u32;
        assert_eq!((h_hi << 8) | h_lo, 1920);
        // v_active
        let v_lo = edid[59] as u32;
        let v_hi = ((edid[61] >> 4) & 0xF) as u32;
        assert_eq!((v_hi << 8) | v_lo, 1080);
        // Max V freq in range limits matches requested
        assert_eq!(edid[96], 120);
    }

    #[test]
    fn edid_high_res_120hz() {
        let edid = generate_edid(2560, 1600, 120);
        let sum: u8 = edid.iter().copied().fold(0u8, |a, b| a.wrapping_add(b));
        assert_eq!(sum, 0);
        let h_lo = edid[56] as u32;
        let h_hi = ((edid[58] >> 4) & 0xF) as u32;
        assert_eq!((h_hi << 8) | h_lo, 2560);
        assert_eq!(edid[96], 120);
    }
}
