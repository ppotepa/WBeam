use eframe::egui::{self, Color32, RichText};

use crate::app::DesktopApp;

impl DesktopApp {
    pub(crate) fn render_status_monitor(&mut self, ctx: &egui::Context) {
        egui::TopBottomPanel::bottom("status_monitor_docked")
            .resizable(true)
            .default_height(220.0)
            .min_height(140.0)
            .show(ctx, |ui| {
                ui.horizontal(|ui| {
                    ui.label(
                        RichText::new("Status Monitor")
                            .color(Color32::LIGHT_BLUE)
                            .strong(),
                    );
                    ui.separator();
                    ui.monospace(format!("events: {}", self.events.len()));
                    if ui.button("Clear").clicked() {
                        self.events.clear();
                        self.last_event_signature.clear();
                    }
                });
                ui.separator();
                egui::ScrollArea::vertical()
                    .auto_shrink([false, false])
                    .stick_to_bottom(true)
                    .show(ui, |ui| {
                        let start = self.events.len().saturating_sub(200);
                        for event in &self.events[start..] {
                            ui.horizontal_wrapped(|ui| {
                                ui.monospace(RichText::new(&event.ts).color(Color32::GRAY));
                                ui.colored_label(event.level.color(), event.level.label());
                                ui.label(&event.message);
                            });
                        }
                    });
            });
    }
}
