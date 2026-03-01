use eframe::egui;

use crate::app::DesktopApp;
use crate::forms::advanced_form::AdvancedForm;
use crate::forms::basic_form::BasicForm;
use crate::models::UiMode;

impl DesktopApp {
    pub(crate) fn render_main_form(&mut self, ctx: &egui::Context) {
        egui::CentralPanel::default().show(ctx, |ui| {
            ui.spacing_mut().item_spacing = egui::vec2(8.0, 8.0);

            if self.ui_mode == UiMode::Basic {
                BasicForm::new(self).render(ui);
            } else {
                AdvancedForm::new(self).render(ui);
            }
        });
    }
}
