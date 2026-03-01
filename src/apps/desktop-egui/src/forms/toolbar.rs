use eframe::egui::{self, Color32, RichText};

use crate::app::DesktopApp;
use crate::models::{EventLevel, UiMode};

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
                ui.monospace(format!("mode: {}", self.current_mode_line));
                ui.separator();
                ui.monospace(format!("updated: {}", self.updated_at));
            });
            ui.add_space(4.0);
            ui.horizontal_wrapped(|ui| {
                ui.label("mode");
                ui.selectable_value(&mut self.ui_mode, UiMode::Basic, UiMode::Basic.label());
                ui.selectable_value(
                    &mut self.ui_mode,
                    UiMode::Advanced,
                    UiMode::Advanced.label(),
                );
                ui.separator();
                if ui.button("Refresh").clicked() {
                    self.refresh_data();
                    self.add_event(EventLevel::Info, "manual refresh");
                }
                if ui.button("Run").clicked() {
                    self.run_proto();
                }
                if self.ui_mode == UiMode::Advanced {
                    if ui.button("Run host only").clicked() {
                        self.run_host_only_rust();
                    }
                    if ui.button("Save settings").clicked() {
                        self.save_settings();
                    }
                    ui.separator();
                    ui.checkbox(&mut self.show_extended_stats, "extended stats");
                }
            });
            if self.ui_mode == UiMode::Basic {
                ui.add_space(2.0);
                ui.horizontal_wrapped(|ui| {
                    ui.label(
                        RichText::new("BASIC MODE")
                            .color(Color32::LIGHT_BLUE)
                            .strong(),
                    );
                    ui.separator();
                    let session = if self.session_type.eq_ignore_ascii_case("wayland") {
                        "CONNECTED TO WAYLAND".to_string()
                    } else if self.session_type.eq_ignore_ascii_case("x11") {
                        "CONNECTED TO X11".to_string()
                    } else {
                        format!("SESSION {}", self.session_type.to_uppercase())
                    };
                    ui.monospace(session);
                    ui.separator();
                    ui.monospace(format!("OS {}", self.os_version));
                });
            }
            ui.add_space(3.0);
        });
    }
}
