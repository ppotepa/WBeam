/// Raw FFI bindings for libevdi (Extensible Virtual Display Interface).
/// Only compiled on Linux. Requires libevdi.so to be installed.
use std::ffi::c_void;

#[repr(C)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EvdiDeviceStatus {
    Available,
    Unrecognized,
    NotPresent,
}

#[repr(C)]
#[derive(Debug, Clone, Copy, Default)]
pub struct EvdiRect {
    pub x1: i32,
    pub y1: i32,
    pub x2: i32,
    pub y2: i32,
}

#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct EvdiMode {
    pub width: i32,
    pub height: i32,
    pub refresh_rate: i32,
    pub bits_per_pixel: i32,
    pub pixel_format: u32,
}

#[repr(C)]
pub struct EvdiBuffer {
    pub id: i32,
    pub buffer: *mut c_void,
    pub width: i32,
    pub height: i32,
    pub stride: i32,
    pub rects: *mut EvdiRect,
    pub rect_count: i32,
}

/// Event context passed to evdi_handle_events.
/// Handlers we don't use are set to None (NULL function pointer).
#[repr(C)]
pub struct EvdiEventContext {
    pub dpms_handler: Option<unsafe extern "C" fn(dpms_mode: i32, user_data: *mut c_void)>,
    pub mode_changed_handler: Option<unsafe extern "C" fn(mode: EvdiMode, user_data: *mut c_void)>,
    pub update_ready_handler: Option<unsafe extern "C" fn(buffer_id: i32, user_data: *mut c_void)>,
    pub crtc_state_handler: Option<unsafe extern "C" fn(state: i32, user_data: *mut c_void)>,
    // cursor_set / cursor_move / ddcci are unused — set to None.
    // All fn-pointer slots have the same ABI size regardless of signature.
    pub cursor_set_handler: Option<unsafe extern "C" fn()>,
    pub cursor_move_handler: Option<unsafe extern "C" fn()>,
    pub ddcci_data_handler: Option<unsafe extern "C" fn()>,
    pub user_data: *mut c_void,
}

// Safety: user_data is managed by the caller who ensures exclusivity.
unsafe impl Send for EvdiEventContext {}

#[link(name = "evdi")]
extern "C" {
    pub fn evdi_check_device(device: i32) -> EvdiDeviceStatus;
    pub fn evdi_open(device: i32) -> *mut c_void; // returns evdi_handle (opaque ptr)
    pub fn evdi_add_device() -> i32;
    pub fn evdi_close(handle: *mut c_void);
    pub fn evdi_connect2(
        handle: *mut c_void,
        edid: *const u8,
        edid_length: u32,
        pixel_area_limit: u32,
        pixel_per_second_limit: u32,
    );
    pub fn evdi_disconnect(handle: *mut c_void);
    pub fn evdi_enable_cursor_events(handle: *mut c_void, enable: bool);
    pub fn evdi_register_buffer(handle: *mut c_void, buffer: EvdiBuffer);
    pub fn evdi_unregister_buffer(handle: *mut c_void, buffer_id: i32);
    pub fn evdi_request_update(handle: *mut c_void, buffer_id: i32) -> bool;
    pub fn evdi_grab_pixels(handle: *mut c_void, rects: *mut EvdiRect, num_rects: *mut i32);
    pub fn evdi_get_event_ready(handle: *mut c_void) -> i32; // returns fd
    pub fn evdi_handle_events(handle: *mut c_void, ctx: *mut EvdiEventContext);
}
