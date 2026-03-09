use super::super::{Activation, ActivationError, RuntimeHandle};
use super::super::super::virtual_display;

pub fn activate(serial_hint: &str, size: &str) -> Result<Activation, ActivationError> {
    let handle =
        virtual_display::spawn_xvfb_for_serial(serial_hint, size).map_err(ActivationError::Failed)?;
    Ok(Activation {
        display_override: Some(handle.display.clone()),
        using_virtual_x11: true,
        runtime_handle: Some(RuntimeHandle::X11VirtualIsolated(handle)),
        ..Activation::default()
    })
}
