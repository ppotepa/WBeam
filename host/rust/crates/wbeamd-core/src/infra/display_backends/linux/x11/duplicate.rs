use super::super::super::super::host_probe::HostProbe;
use super::super::super::Activation;

pub fn activate(
    host_probe: &HostProbe,
) -> Result<Activation, super::super::super::ActivationError> {
    Ok(Activation {
        display_override: host_probe.display.clone(),
        ..Activation::default()
    })
}
