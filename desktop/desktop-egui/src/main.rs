mod app;
mod forms;
mod models;
mod services;

use app::{DesktopApp, APP_ICON_PNG};
use services::locate_proto_root;

fn main() {
    let proto_root = match locate_proto_root() {
        Ok(path) => path,
        Err(err) => {
            eprintln!("failed to locate proto root: {err}");
            std::process::exit(1);
        }
    };

    let mut viewport = egui::ViewportBuilder::default();
    match eframe::icon_data::from_png_bytes(APP_ICON_PNG) {
        Ok(icon) => {
            viewport = viewport.with_icon(icon);
        }
        Err(err) => {
            eprintln!("warning: failed to load app icon from assets/wbeam.png: {err}");
        }
    }

    let options = eframe::NativeOptions {
        viewport,
        ..Default::default()
    };

    let run = eframe::run_native(
        "WBeam Desktop",
        options,
        Box::new(move |_cc| Box::new(DesktopApp::new(proto_root.clone()))),
    );
    if let Err(err) = run {
        eprintln!("failed to start desktop app: {err}");
        std::process::exit(1);
    }
}
