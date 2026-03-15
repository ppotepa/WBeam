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
const EDID_WIDTH: i32 = 1920;
const EDID_HEIGHT: i32 = 1080;

/// Maximum dirty rectangles EVDI reports per frame.
const MAX_RECTS: usize = 16;

/// Hard-wired EDID advertising 1920×1080 @ 60 Hz (DVI-D).
/// Sourced from the EVDI kernel test suite (evdi_fake_user_client.c).
const EDID_1920X1080: [u8; 128] = [
    0x00, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x00, 0x31, 0xd8, 0x2a, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x21, 0x01, 0x03, 0x81, 0xa0, 0x5a, 0x78, 0x0a, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01,
    0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x02, 0x3a, 0x80, 0x18, 0x71, 0x38, 0x2d, 0x40, 0x58, 0x2c,
    0x45, 0x00, 0x40, 0x84, 0x63, 0x00, 0x00, 0x1e, 0x00, 0x00, 0x00, 0xfc, 0x00, 0x54, 0x65, 0x73,
    0x74, 0x20, 0x45, 0x44, 0x49, 0x44, 0x0a, 0x20, 0x20, 0x20, 0x00, 0x00, 0x00, 0xfd, 0x00, 0x32,
    0x46, 0x1e, 0x46, 0x0f, 0x00, 0x0a, 0x20, 0x20, 0x20, 0x20, 0x20, 0x20, 0x00, 0x00, 0x00, 0x10,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xab,
];

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

fn evdi_loop(
    raw_handle: SendHandle,
    width: i32,
    height: i32,
    frame_size: usize,
    frame_duration_ns: u64,
    appsrc: gst_app::AppSrc,
) {
    let handle = raw_handle.0;
    let mut published_frames: u64 = 0;
    let mut pushed_frames: u64 = 0;
    let mut zero_rect_polls: u64 = 0;
    let mut event_poll_timeouts: u64 = 0;
    let mut last_frame_at = Instant::now();
    let mut last_wait_log_at = Instant::now();
    let capture_started_at = Instant::now();
    let mut last_pts_ns: u64 = 0;

    // We own the frame buffer; EVDI writes into it via ioctl.
    let mut pixels: Vec<u8> = vec![0u8; frame_size];
    let mut rects: Vec<ffi::EvdiRect> = vec![ffi::EvdiRect::default(); MAX_RECTS];

    let evdi_buf = ffi::EvdiBuffer {
        id: 0,
        buffer: pixels.as_mut_ptr() as *mut c_void,
        width,
        height,
        stride: width * BYTES_PER_PIXEL as i32,
        rects: rects.as_mut_ptr(),
        rect_count: MAX_RECTS as i32,
    };
    unsafe { ffi::evdi_register_buffer(handle, evdi_buf) };

    let fd = unsafe { ffi::evdi_get_event_ready(handle) };

    let mut ctx = ffi::EvdiEventContext {
        dpms_handler: None,
        mode_changed_handler: Some(on_mode_changed),
        update_ready_handler: Some(on_update_ready),
        crtc_state_handler: None,
        cursor_set_handler: None,
        cursor_move_handler: None,
        ddcci_data_handler: None,
        user_data: ptr::null_mut(),
    };

    loop {
        let immediate = unsafe { ffi::evdi_request_update(handle, 0) };

        if !immediate {
            // Wait up to 33 ms for the compositor to push a new frame.
            let mut pollfd = libc::pollfd {
                fd,
                events: libc::POLLIN,
                revents: 0,
            };
            let r = unsafe { libc::poll(&mut pollfd as *mut libc::pollfd, 1, 33) };
            if r <= 0 {
                event_poll_timeouts = event_poll_timeouts.saturating_add(1);
                if last_wait_log_at.elapsed() >= Duration::from_secs(2) {
                    println!(
                        "[wbeam-evdi] waiting for compositor events: no POLLIN for {} ms (poll_timeouts={}, published_frames={}, configured={}x{})",
                        last_frame_at.elapsed().as_millis(),
                        event_poll_timeouts,
                        published_frames,
                        width,
                        height
                    );
                    last_wait_log_at = Instant::now();
                }
                continue; // timeout or error — retry
            }
            event_poll_timeouts = 0;
            unsafe { ffi::evdi_handle_events(handle, &mut ctx) };
            // After handle_events the update_ready callback has fired;
            // we need another request_update to arm the grab.
            if !unsafe { ffi::evdi_request_update(handle, 0) } {
                continue;
            }
        }

        let mut num_rects = 0i32;
        unsafe { ffi::evdi_grab_pixels(handle, rects.as_mut_ptr(), &mut num_rects) };

        if num_rects > 0 {
            zero_rect_polls = 0;
            last_frame_at = Instant::now();
            published_frames = published_frames.saturating_add(1);
            // Sampled diagnostics (not per-frame spam): confirms EVDI producer is
            // actually generating dirty-rect updates.
            if published_frames == 1 || published_frames % 120 == 0 {
                let first = rects[0];
                println!(
                    "[wbeam-evdi] frame update: rects={} first=({},{} {}x{}) published_frames={}",
                    num_rects,
                    first.x1,
                    first.y1,
                    first.x2.saturating_sub(first.x1),
                    first.y2.saturating_sub(first.y1),
                    published_frames
                );
            }
            let frame_copy = pixels.clone();

            let mut buf = gst::Buffer::from_slice(frame_copy);
            if let Some(bm) = buf.get_mut() {
                let pts_ns = capture_started_at.elapsed().as_nanos() as u64;
                let dur_ns = if last_pts_ns > 0 {
                    pts_ns.saturating_sub(last_pts_ns).max(1_000_000)
                } else {
                    frame_duration_ns
                };
                last_pts_ns = pts_ns;
                bm.set_pts(gst::ClockTime::from_nseconds(pts_ns));
                bm.set_duration(gst::ClockTime::from_nseconds(dur_ns));
            }

            pushed_frames = pushed_frames.saturating_add(1);
            let flow = appsrc.push_buffer(buf);
            if pushed_frames <= 3 || pushed_frames % 120 == 0 {
                println!(
                    "[wbeam-evdi] appsrc push: pushed_frames={pushed_frames} published_frames={published_frames} flow={flow:?}"
                );
            }
            if let Err(ref err) = flow {
                eprintln!(
                    "[wbeam-evdi] ERROR: appsrc push failed at pushed_frames={pushed_frames}: {err:?}"
                );
                break;
            }
        } else {
            zero_rect_polls = zero_rect_polls.saturating_add(1);
            // Low-rate warning for black/stale-screen debugging.
            if last_wait_log_at.elapsed() >= Duration::from_secs(2) {
                println!(
                    "[wbeam-evdi] waiting for dirty rects: no updates for {} ms (polls={zero_rect_polls}, published_frames={published_frames}, configured={}x{})",
                    last_frame_at.elapsed().as_millis(),
                    width,
                    height
                );
                last_wait_log_at = Instant::now();
            }
        }
    }

    // Cleanup
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
    // Important: libevdi expects the registered grab buffer geometry to match
    // the negotiated mode from EDID. We currently ship only a 1920x1080 EDID,
    // so forcing capture buffers to EDID size avoids grabpix EINVAL failures
    // when requested stream size is portrait (e.g. 1200x2000). Downstream
    // videoscale/capsfilter still reshape to cfg.width x cfg.height.
    let width = EDID_WIDTH;
    let height = EDID_HEIGHT;
    if requested_width != width || requested_height != height {
        eprintln!(
            "[wbeam-evdi] WARN: requested stream {}×{} but EVDI capture is fixed at EDID {}×{}; scaling downstream.",
            requested_width, requested_height, width, height
        );
    }
    let frame_size = width as usize * height as usize * BYTES_PER_PIXEL;
    let frame_duration_ns = 1_000_000_000u64 / fps as u64;

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

    // ── 2. Connect with 1920×1080 EDID — cursor events disabled ────────────
    unsafe {
        ffi::evdi_connect2(
            handle,
            EDID_1920X1080.as_ptr(),
            EDID_1920X1080.len() as u32,
            (width * height) as u32,
            (width * height * fps as i32) as u32,
        );
        ffi::evdi_enable_cursor_events(handle, false);
    }
    println!("[wbeam-evdi] Connected virtual display {}×{} @ {fps} fps", width, height);

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
