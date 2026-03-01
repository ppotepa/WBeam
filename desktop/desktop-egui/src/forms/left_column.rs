use eframe::egui::{self, Color32, RichText};

use crate::app::DesktopApp;

impl DesktopApp {
    pub(crate) fn render_runtime_form(&self, ui: &mut egui::Ui) {
        egui::Frame::group(ui.style()).show(ui, |ui| {
            ui.label(RichText::new("Runtime").color(Color32::LIGHT_BLUE).strong());
            ui.separator();
            egui::Grid::new("runtime_grid")
                .num_columns(2)
                .striped(true)
                .show(ui, |ui| {
                    ui.label("os");
                    ui.monospace(&self.os_name);
                    ui.end_row();
                    ui.label("session");
                    ui.monospace(&self.session_type);
                    ui.end_row();
                    ui.label("host");
                    ui.monospace(&self.host_name);
                    ui.end_row();
                    ui.label("config");
                    ui.monospace(self.config_path.display().to_string());
                    ui.end_row();
                });
        });
    }

    pub(crate) fn render_settings_form(&mut self, ui: &mut egui::Ui) {
        egui::Frame::group(ui.style()).show(ui, |ui| {
            ui.label(
                RichText::new("Streaming settings")
                    .color(Color32::LIGHT_BLUE)
                    .strong(),
            );
            ui.separator();
            ui.horizontal(|ui| {
                ui.label("backend");
                egui::ComboBox::from_id_source("backend_combo")
                    .selected_text(self.backend_value.clone())
                    .show_ui(ui, |ui| {
                        for item in ["auto", "portal", "kms_drm", "grim", "spectacle", "import"] {
                            ui.selectable_value(&mut self.backend_value, item.to_string(), item);
                        }
                    });
            });
            ui.horizontal(|ui| {
                ui.label("fps");
                ui.add(egui::TextEdit::singleline(&mut self.fps_value).desired_width(90.0));
                ui.label("bitrate kbps");
                ui.add(egui::TextEdit::singleline(&mut self.bitrate_value).desired_width(120.0));
            });
        });
    }

    pub(crate) fn render_health_form(&self, ui: &mut egui::Ui) {
        let sender_fps = self.stats.sender_fps.parse::<f32>().unwrap_or(0.0);
        let pipeline_fps = self.stats.pipeline_fps.parse::<f32>().unwrap_or(0.0);
        let (health_label, health_color) = if sender_fps >= 50.0 && pipeline_fps >= 50.0 {
            ("good", Color32::LIGHT_GREEN)
        } else if sender_fps >= 30.0 {
            ("degraded", Color32::YELLOW)
        } else {
            ("critical", Color32::from_rgb(255, 120, 120))
        };

        egui::Frame::group(ui.style()).show(ui, |ui| {
            ui.label(
                RichText::new("Quick health")
                    .color(Color32::LIGHT_BLUE)
                    .strong(),
            );
            ui.separator();
            ui.horizontal(|ui| {
                ui.label("sender");
                ui.monospace(RichText::new(format!("{sender_fps:.1} fps")).strong());
                ui.separator();
                ui.label("pipeline");
                ui.monospace(RichText::new(format!("{pipeline_fps:.1} fps")).strong());
                ui.separator();
                ui.label("state");
                ui.colored_label(health_color, health_label);
            });
        });
    }
}
