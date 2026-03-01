use eframe::egui::{self, Color32, RichText};

use crate::app::DesktopApp;

impl DesktopApp {
    pub(crate) fn render_devices_form(&self, ui: &mut egui::Ui) {
        egui::Frame::group(ui.style()).show(ui, |ui| {
            ui.label(
                RichText::new("Connected devices")
                    .color(Color32::LIGHT_BLUE)
                    .strong(),
            );
            ui.separator();
            if self.devices.is_empty() {
                ui.label("no adb devices");
            } else {
                egui::Grid::new("devices_header")
                    .num_columns(4)
                    .striped(true)
                    .show(ui, |ui| {
                        ui.strong("serial");
                        ui.strong("state");
                        ui.strong("model");
                        ui.strong("transport");
                        ui.end_row();
                        for dev in &self.devices {
                            ui.monospace(&dev.serial);
                            ui.monospace(&dev.state);
                            ui.monospace(&dev.model);
                            ui.monospace(&dev.transport);
                            ui.end_row();
                        }
                    });
            }
        });
    }

    pub(crate) fn render_stats_form(&self, ui: &mut egui::Ui) {
        egui::Frame::group(ui.style()).show(ui, |ui| {
            ui.label(
                RichText::new("Stream stats")
                    .color(Color32::LIGHT_BLUE)
                    .strong(),
            );
            ui.separator();
            egui::Grid::new("stats_basic_grid")
                .num_columns(2)
                .striped(true)
                .show(ui, |ui| {
                    ui.label("pipeline_fps");
                    ui.monospace(&self.stats.pipeline_fps);
                    ui.end_row();
                    ui.label("sender_fps");
                    ui.monospace(&self.stats.sender_fps);
                    ui.end_row();
                    ui.label("timeout_misses");
                    ui.monospace(&self.stats.timeout_misses);
                    ui.end_row();
                    ui.label("stale_dupe");
                    ui.monospace(&self.stats.stale_dupe);
                    ui.end_row();
                    ui.label("seq");
                    ui.monospace(&self.stats.seq);
                    ui.end_row();
                    ui.label("source");
                    ui.monospace(&self.stats.source);
                    ui.end_row();
                });

            if self.show_extended_stats {
                ui.add_space(6.0);
                ui.label(
                    RichText::new("WBH1 transport details")
                        .color(Color32::LIGHT_BLUE)
                        .strong(),
                );
                if let Some(w) = &self.stats.wbh1 {
                    egui::Grid::new("stats_wbh1_grid")
                        .num_columns(2)
                        .striped(true)
                        .show(ui, |ui| {
                            ui.label("units");
                            ui.monospace(&w.units);
                            ui.end_row();
                            ui.label("fps");
                            ui.monospace(&w.fps);
                            ui.end_row();
                            ui.label("mbps");
                            ui.monospace(&w.mbps);
                            ui.end_row();
                            ui.label("avg_kb");
                            ui.monospace(&w.avg_kb);
                            ui.end_row();
                            ui.label("min_kb");
                            ui.monospace(&w.min_kb);
                            ui.end_row();
                            ui.label("max_kb");
                            ui.monospace(&w.max_kb);
                            ui.end_row();
                            ui.label("key_pct");
                            ui.monospace(&w.key_pct);
                            ui.end_row();
                            ui.label("lat_ms");
                            ui.monospace(&w.lat_ms);
                            ui.end_row();
                            ui.label("lat_max_ms");
                            ui.monospace(&w.lat_max_ms);
                            ui.end_row();
                            ui.label("seq");
                            ui.monospace(&w.seq);
                            ui.end_row();
                        });
                } else {
                    ui.monospace(
                        "no WBH1 samples yet (run app from this UI to capture /tmp/proto-runner.log)",
                    );
                }
            }
        });
    }
}
