use eframe::egui::{self, Color32, RichText};

use crate::app::DesktopApp;
use crate::models::EventLevel;

impl DesktopApp {
    pub(crate) fn render_toolbar(&mut self, ctx: &egui::Context, status_color: Color32) {
        egui::TopBottomPanel::top("toolbar").show(ctx, |ui| {
            ui.add_space(3.0);
            ui.horizontal(|ui| {
                ui.heading(RichText::new("WBeam Desktop").strong());
                ui.separator();
                ui.label(
                    RichText::new(format!("status: {}", self.status_line)).color(status_color),
                );
                ui.separator();
                ui.monospace(format!("updated: {}", self.updated_at));
            });
            ui.add_space(4.0);
            ui.horizontal_wrapped(|ui| {
                if ui.button("Refresh").clicked() {
                    self.refresh_data();
                    self.add_event(EventLevel::Info, "manual refresh");
                }
                if ui.button("Run").clicked() {
                    self.run_proto();
                }
                if ui.button("Run host only").clicked() {
                    self.run_host_only_rust();
                }
                if ui.button("Save settings").clicked() {
                    self.save_settings();
                }
                ui.separator();
                ui.checkbox(&mut self.show_extended_stats, "extended stats");
            });
            ui.add_space(3.0);
        });
    }
}
