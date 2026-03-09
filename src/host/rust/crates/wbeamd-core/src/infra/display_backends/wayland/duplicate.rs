use super::super::{Activation, ActivationError};

pub fn activate() -> Result<Activation, ActivationError> {
    Ok(Activation::default())
}
