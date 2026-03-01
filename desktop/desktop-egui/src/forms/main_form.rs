use eframe::egui;

use crate::app::DesktopApp;

impl DesktopApp {
    pub(crate) fn render_main_form(&mut self, ctx: &egui::Context) {
        egui::CentralPanel::default().show(ctx, |ui| {
            ui.spacing_mut().item_spacing = egui::vec2(8.0, 8.0);

            ui.columns(2, |cols| {
                cols[0].vertical(|ui| {
                    self.render_runtime_form(ui);
                    self.render_settings_form(ui);
                    self.render_health_form(ui);
                });

                cols[1].vertical(|ui| {
                    self.render_devices_form(ui);
                    self.render_stats_form(ui);
                });
            });
        });
    }
}
