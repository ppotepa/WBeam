use crate::app::DesktopApp;

pub(crate) trait FormBase {
    fn app(&self) -> &DesktopApp;
    fn app_mut(&mut self) -> &mut DesktopApp;
}
