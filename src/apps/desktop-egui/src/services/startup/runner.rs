use crate::domain::startup::{StartupCheck, StartupEvent, StartupState};

#[derive(Default)]
pub(crate) struct StartupRunner {
    checks: Vec<Box<dyn StartupCheck>>,
}

impl StartupRunner {
    pub(crate) fn with_checks(checks: Vec<Box<dyn StartupCheck>>) -> Self {
        Self { checks }
    }

    pub(crate) fn run_all(&self, state: &mut StartupState) -> Vec<StartupEvent> {
        self.checks.iter().map(|check| check.run(state)).collect()
    }
}
