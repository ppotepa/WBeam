use eframe::egui::{self, Color32, RichText};

use crate::app::{DesktopApp, APP_ICON_PNG};

impl DesktopApp {
    pub(crate) fn render_splash(&mut self, ctx: &egui::Context) {
        self.ensure_splash_texture(ctx);
        egui::Area::new(egui::Id::new("startup_splash"))
            .order(egui::Order::Foreground)
            .anchor(egui::Align2::CENTER_CENTER, egui::vec2(0.0, 0.0))
            .show(ctx, |ui| {
                egui::Frame::none()
                    .fill(Color32::from_rgb(12, 16, 28))
                    .stroke(egui::Stroke::new(1.0, Color32::from_rgb(60, 80, 120)))
                    .rounding(egui::Rounding::ZERO)
                    .show(ui, |ui| {
                        ui.set_min_size(egui::vec2(360.0, 220.0));
                        ui.vertical_centered(|ui| {
                            ui.add_space(6.0);
                            if let Some(texture) = &self.splash_texture {
                                ui.add(
                                    egui::Image::new(texture)
                                        .fit_to_exact_size(egui::vec2(96.0, 96.0)),
                                );
                                ui.add_space(4.0);
                            }
                            ui.heading(RichText::new("WBeam").strong());
                            ui.label(
                                RichText::new("Starting desktop control panel...")
                                    .color(Color32::LIGHT_BLUE),
                            );
                            ui.add_space(6.0);
                            ui.add(egui::Spinner::new().size(22.0));
                            ui.add_space(4.0);
                        });
                    });
            });
    }

    pub(crate) fn ensure_splash_texture(&mut self, ctx: &egui::Context) {
        if self.splash_texture.is_some() {
            return;
        }
        if let Ok(icon) = eframe::icon_data::from_png_bytes(APP_ICON_PNG) {
            let image = egui::ColorImage::from_rgba_unmultiplied(
                [icon.width as usize, icon.height as usize],
                &icon.rgba,
            );
            self.splash_texture =
                Some(ctx.load_texture("wbeam_splash", image, egui::TextureOptions::LINEAR));
        }
    }
}
