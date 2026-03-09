use super::super::Activation;
use super::super::super::host_probe::HostProbe;

pub fn activate(host_probe: &HostProbe) -> Result<Activation, super::super::ActivationError> {
    Ok(Activation {
        display_override: host_probe.display.clone(),
        ..Activation::default()
    })
}
