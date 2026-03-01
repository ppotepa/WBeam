use eframe::egui::{self, Color32, RichText};

use crate::app::DesktopApp;
use crate::forms::base_form::FormBase;

pub(crate) struct BasicForm<'a> {
    app: &'a mut DesktopApp,
}

impl<'a> BasicForm<'a> {
    pub(crate) fn new(app: &'a mut DesktopApp) -> Self {
        Self { app }
    }

    pub(crate) fn render(&mut self, ui: &mut egui::Ui) {
        let app = self.app_mut();

        ui.spacing_mut().item_spacing = egui::vec2(10.0, 10.0);

        ui.columns(2, |cols| {
            render_stream_controls(app, &mut cols[0], 210.0);
            render_stream_health(app, &mut cols[1], 210.0);
        });

        ui.columns(2, |cols| {
            render_device_status(app, &mut cols[0], 220.0);
            render_recent_activity(app, &mut cols[1], 220.0);
        });
    }
}

impl FormBase for BasicForm<'_> {
    fn app(&self) -> &DesktopApp {
        self.app
    }

    fn app_mut(&mut self) -> &mut DesktopApp {
        self.app
    }
}

fn render_stream_controls(app: &mut DesktopApp, ui: &mut egui::Ui, min_height: f32) {
    let has_device = !app.devices.is_empty();
    let sender_fps = parse_f32(&app.stats.sender_fps);
    let is_streaming = sender_fps >= 5.0;

    panel_card(ui, "Streaming", min_height, |ui| {
        ui.label("Basic mode keeps only the essential actions.");
        ui.add_space(6.0);

        if ui
            .add_sized(
                [ui.available_width(), 44.0],
                egui::Button::new(RichText::new("Start streaming").strong()),
            )
            .clicked()
        {
            app.run_proto();
        }

        ui.horizontal(|ui| {
            if ui.button("Reconnect").clicked() {
                app.reconnect_stream();
            }
            if ui.button("Refresh").clicked() {
                app.refresh_data();
            }
            if ui.button("Stop request").clicked() {
                app.stop_stream_request();
            }
        });

        ui.add_space(4.0);
        let (dot, label) = if !has_device {
            (Color32::RED, "No device connected")
        } else if is_streaming {
            (Color32::from_rgb(22, 163, 74), "Streaming active")
        } else {
            (
                Color32::from_rgb(202, 138, 4),
                "Connected, waiting for frames",
            )
        };

        ui.horizontal(|ui| {
            ui.colored_label(dot, "●");
            ui.label(RichText::new(label).strong());
        });
    });
}

fn render_device_status(app: &DesktopApp, ui: &mut egui::Ui, min_height: f32) {
    panel_card(ui, "Connection", min_height, |ui| {
        egui::Grid::new("basic_connection_grid")
            .num_columns(2)
            .spacing([8.0, 6.0])
            .show(ui, |ui| {
                ui.monospace("Platform");
                ui.monospace(&app.platform_id);
                ui.end_row();

                ui.monospace("Session");
                ui.monospace(&app.session_type);
                ui.end_row();

                ui.monospace("OS");
                ui.monospace(&app.os_version);
                ui.end_row();
            });

        ui.separator();

        if let Some(device) = app.devices.first() {
            ui.label(RichText::new(&device.model).strong());
            egui::Grid::new("basic_device_grid")
                .num_columns(2)
                .spacing([8.0, 6.0])
                .show(ui, |ui| {
                    ui.label("Serial");
                    ui.monospace(&device.serial);
                    ui.end_row();
                    ui.label("State");
                    ui.monospace(&device.state);
                    ui.end_row();
                    ui.label("Transport");
                    ui.monospace(&device.transport);
                    ui.end_row();
                });
        } else {
            ui.colored_label(Color32::from_rgb(220, 38, 38), "No ADB device detected");
            ui.label("Connect phone via USB and accept debugging prompt.");
        }
    });
}

fn render_stream_health(app: &DesktopApp, ui: &mut egui::Ui, min_height: f32) {
    let sender_fps = parse_f32(&app.stats.sender_fps);
    let pipeline_fps = parse_f32(&app.stats.pipeline_fps);
    let timeouts = parse_u32(&app.stats.timeout_misses);
    let stale = parse_u32(&app.stats.stale_dupe);
    let latency = app
        .stats
        .wbh1
        .as_ref()
        .map(|w| parse_f32(&w.lat_ms))
        .unwrap_or(0.0);

    panel_card(ui, "Health", min_height, |ui| {
        ui.add_space(2.0);

        ui.horizontal(|ui| {
            ui.label(
                RichText::new(format!("{sender_fps:.0}"))
                    .size(42.0)
                    .strong(),
            );
            ui.label(RichText::new("FPS").size(20.0).color(Color32::GRAY));
        });

        ui.separator();
        egui::Grid::new("basic_health_grid")
            .num_columns(2)
            .spacing([8.0, 4.0])
            .show(ui, |ui| {
                ui.monospace("sender_fps");
                ui.monospace(format!("{sender_fps:.1}"));
                ui.end_row();

                ui.monospace("pipeline_fps");
                ui.monospace(format!("{pipeline_fps:.1}"));
                ui.end_row();

                ui.monospace("timeout_misses");
                ui.monospace(format!("{timeouts}"));
                ui.end_row();

                ui.monospace("stale_dupe");
                ui.monospace(format!("{stale}"));
                ui.end_row();

                if latency > 0.0 {
                    ui.monospace("latency_ms");
                    ui.monospace(format!("{latency:.0}"));
                    ui.end_row();
                }
            });

        if latency > 0.0 {
            ui.add_space(2.0);
        }

        ui.add_space(6.0);
        ui.label(RichText::new(format!("Updated {}", app.updated_at)).color(Color32::GRAY));
    });
}

fn render_recent_activity(app: &DesktopApp, ui: &mut egui::Ui, min_height: f32) {
    panel_card(ui, "Recent events", min_height, |ui| {
        ui.add_space(2.0);

        if app.events.is_empty() {
            ui.label(RichText::new("No events yet").color(Color32::GRAY));
            return;
        }

        let start = app.events.len().saturating_sub(8);
        egui::ScrollArea::vertical()
            .max_height(160.0)
            .show(ui, |ui| {
                for event in app.events[start..].iter().rev() {
                    ui.horizontal(|ui| {
                        ui.set_min_width(ui.available_width());
                        ui.colored_label(event.level.color(), event.level.label());
                        ui.monospace(&event.ts);
                        ui.label("-");
                        ui.label(&event.message);
                    });
                }
            });
    });
}

fn panel_card(
    ui: &mut egui::Ui,
    title: &str,
    min_height: f32,
    add_contents: impl FnOnce(&mut egui::Ui),
) {
    egui::Frame::group(ui.style()).show(ui, |ui| {
        ui.set_min_height(min_height);
        ui.heading(title);
        ui.add_space(4.0);
        add_contents(ui);
    });
}

fn parse_f32(value: &str) -> f32 {
    value.trim().parse::<f32>().unwrap_or(0.0)
}

fn parse_u32(value: &str) -> u32 {
    value.trim().parse::<u32>().unwrap_or(0)
}
