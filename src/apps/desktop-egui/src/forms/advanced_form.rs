use eframe::egui;

use crate::app::DesktopApp;
use crate::forms::base_form::FormBase;

pub(crate) struct AdvancedForm<'a> {
    app: &'a mut DesktopApp,
}

impl<'a> AdvancedForm<'a> {
    pub(crate) fn new(app: &'a mut DesktopApp) -> Self {
        Self { app }
    }

    pub(crate) fn render(&mut self, ui: &mut egui::Ui) {
        ui.columns(2, |cols| {
            cols[0].vertical(|ui| {
                self.app().render_runtime_form(ui);
                self.app_mut().render_settings_form(ui);
                self.app().render_health_form(ui);
            });

            cols[1].vertical(|ui| {
                self.app().render_devices_form(ui);
                self.app().render_stats_form(ui);
            });
        });
    }
}

impl FormBase for AdvancedForm<'_> {
    fn app(&self) -> &DesktopApp {
        self.app
    }

    fn app_mut(&mut self) -> &mut DesktopApp {
        self.app
    }
}
