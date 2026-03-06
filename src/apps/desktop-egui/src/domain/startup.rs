#[derive(Clone, Debug)]
pub(crate) enum StartupStatus {
    Ok,
    Warn,
    Fail,
}

#[derive(Clone, Debug)]
pub(crate) struct StartupEvent {
    pub(crate) check_id: &'static str,
    pub(crate) status: StartupStatus,
    pub(crate) message: String,
}

#[derive(Clone, Debug, Default)]
pub(crate) struct StartupState {
    pub(crate) adb_available: bool,
    pub(crate) adb_responsive: bool,
    pub(crate) first_probe_ok: bool,
    pub(crate) first_probe_error: Option<String>,
}

pub(crate) trait StartupCheck: Send + Sync {
    fn id(&self) -> &'static str;
    fn run(&self, state: &mut StartupState) -> StartupEvent;
}
