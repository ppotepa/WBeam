mod domain;
mod managers;
mod services;

use std::fs;
use std::path::PathBuf;
use std::process::Command;
use std::time::{SystemTime, UNIX_EPOCH};

use eframe::egui::{self, Color32, RichText, Stroke};

use crate::domain::cli::DevtoolCommand;
use crate::domain::device::{DeviceInfo, DiscoverySource};
use crate::managers::device_manager::DeviceManager;
use crate::managers::session_manager::{SessionManager, SessionMode};
use crate::services::cli_runner::run_cli_command;
use crate::services::device_manager::DeviceManagerSnapshot;

const APP_ID: &str = "wbeam-desktop";
const APP_ICON_PNG: &[u8] = include_bytes!("../../../assets/wbeam.png");
const WINDOW_WIDTH: f32 = 1000.0;
const WINDOW_HEIGHT: f32 = 800.0;
const OUTER_PADDING: f32 = 12.0;
const PANEL_GAP: f32 = 8.0;
const HEADER_HEIGHT: f32 = 60.0;
const PANEL_HEIGHT: f32 = WINDOW_HEIGHT - HEADER_HEIGHT - (OUTER_PADDING * 3.0);
const PANEL_WIDTH: f32 = (WINDOW_WIDTH - (OUTER_PADDING * 2.0) - (PANEL_GAP * 2.0)) / 3.0;
const DEVICE_LIST_HEIGHT: f32 = PANEL_HEIGHT - 108.0;
const PANEL_SCROLL_HEIGHT: f32 = PANEL_HEIGHT - 80.0;

const BG: Color32 = Color32::from_rgb(15, 17, 22);
const PANEL_BG: Color32 = Color32::from_rgb(23, 27, 34);
const HEADER_BG: Color32 = Color32::from_rgb(18, 23, 32);
const BORDER: Color32 = Color32::from_rgb(42, 50, 64);
const CARD_BG: Color32 = Color32::from_rgb(27, 33, 43);
const CARD_SELECTED_BG: Color32 = Color32::from_rgb(36, 55, 84);
const ACCENT: Color32 = Color32::from_rgb(143, 208, 255);
const MUTED: Color32 = Color32::from_rgb(127, 144, 174);
const OK: Color32 = Color32::from_rgb(122, 209, 122);
const WARN: Color32 = Color32::from_rgb(255, 211, 110);

struct DesktopApp {
    device_manager: DeviceManager,
    session_manager: SessionManager,
    ui_state: UiState,
}

#[derive(Default)]
struct UiState {
    details_serial: Option<String>,
    manual_profile_error: Option<String>,
    confirm_install: bool,
    confirm_reboot: bool,
}

impl DesktopApp {
    fn new() -> Self {
        Self {
            device_manager: DeviceManager::new(),
            session_manager: SessionManager::default(),
            ui_state: UiState::default(),
        }
    }
}

impl eframe::App for DesktopApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        ctx.style_mut(|s| {
            s.visuals.panel_fill = BG;
            s.visuals.widgets.noninteractive.bg_stroke = Stroke::new(1.0, BORDER);
            s.spacing.item_spacing = egui::vec2(8.0, 8.0);
            s.spacing.button_padding = egui::vec2(10.0, 6.0);
        });

        self.device_manager.tick();
        ctx.request_repaint_after(std::time::Duration::from_millis(250));

        let snapshot = self.device_manager.snapshot();
        let selected_device = self.device_manager.selected_device(&snapshot);

        egui::TopBottomPanel::top("status_bar")
            .exact_height(HEADER_HEIGHT)
            .show(ctx, |ui| {
                ui.add_space(OUTER_PADDING - 4.0);
                egui::Frame::none()
                    .fill(HEADER_BG)
                    .stroke(Stroke::new(1.0, BORDER))
                    .rounding(8.0)
                    .inner_margin(egui::Margin::symmetric(12.0, 8.0))
                    .show(ui, |ui| {
                        ui.horizontal(|ui| {
                            ui.label(
                                RichText::new("WBeam Desktop")
                                    .strong()
                                    .color(Color32::from_rgb(219, 231, 255)),
                            );
                            ui.add_space(12.0);
                            status_pill(
                                ui,
                                &format!(
                                    "ADB: {}",
                                    if snapshot.adb_available && snapshot.adb_responsive {
                                        "OK"
                                    } else {
                                        "ERROR"
                                    }
                                ),
                                if snapshot.adb_available && snapshot.adb_responsive {
                                    OK
                                } else {
                                    WARN
                                },
                            );
                            status_pill(ui, "LAN: IDLE", MUTED);
                            status_pill(ui, "WI-FI: IDLE", MUTED);
                            ui.with_layout(
                                egui::Layout::right_to_left(egui::Align::Center),
                                |ui| {
                                    ui.colored_label(
                                        MUTED,
                                        format!("DISCOVERED: {}", snapshot.devices.len()),
                                    );
                                },
                            );
                        });
                    });
            });

        egui::CentralPanel::default().show(ctx, |ui| {
            ui.add_space(OUTER_PADDING - 4.0);
            ui.horizontal(|ui| {
                ui.spacing_mut().item_spacing.x = PANEL_GAP;

                ui.allocate_ui_with_layout(
                    egui::vec2(PANEL_WIDTH, PANEL_HEIGHT),
                    egui::Layout::top_down(egui::Align::Min),
                    |ui| {
                        panel_container(ui, |ui| {
                            discovery_panel(
                                ui,
                                &snapshot,
                                &mut self.device_manager,
                                &mut self.ui_state,
                            );
                        });
                    },
                );
                ui.allocate_ui_with_layout(
                    egui::vec2(PANEL_WIDTH, PANEL_HEIGHT),
                    egui::Layout::top_down(egui::Align::Min),
                    |ui| {
                        panel_container(ui, |ui| {
                            properties_panel(
                                ui,
                                selected_device,
                                &snapshot,
                                &mut self.device_manager,
                                &mut self.session_manager,
                                &mut self.ui_state,
                            );
                        });
                    },
                );
                ui.allocate_ui_with_layout(
                    egui::vec2(PANEL_WIDTH, PANEL_HEIGHT),
                    egui::Layout::top_down(egui::Align::Min),
                    |ui| {
                        panel_container(ui, |ui| {
                            session_panel(
                                ui,
                                selected_device,
                                &mut self.session_manager,
                                self.device_manager.runtime_events(),
                            );
                        });
                    },
                );
            });
        });

        if let Some(serial) = self.ui_state.details_serial.clone() {
            let mut open = true;
            egui::Window::new(format!("Device Details - {serial}"))
                .open(&mut open)
                .resizable(true)
                .default_width(440.0)
                .show(ctx, |ui| {
                    if let Some(device) = snapshot.devices.iter().find(|d| d.serial == serial) {
                        ui.label(RichText::new(device_title(device)).strong());
                        ui.separator();
                        ui.monospace(format!(
                            "Discovery source: {} ({})",
                            source_label(&device.discovery_source),
                            fallback(&device.source_identity, "?")
                        ));
                        ui.monospace(format!("ADB serial: {}", device.serial));
                        ui.monospace(format!("ADB state: {}", device.adb_state));
                        ui.monospace(format!(
                            "Platform: Android {} (API {})",
                            fallback(&device.android_release, "?"),
                            fallback(&device.api_level, "?")
                        ));
                        ui.monospace(format!("ABI: {}", fallback(&device.abi, "unknown")));
                        ui.monospace(format!(
                            "Battery: {}% (status {})",
                            fallback(&device.battery_level, "?"),
                            fallback(&device.battery_status, "?")
                        ));
                    }
                });
            if !open {
                self.ui_state.details_serial = None;
            }
        }

        if self.ui_state.confirm_install {
            confirm_install_dialog(
                ctx,
                selected_device,
                &mut self.device_manager,
                &mut self.ui_state.confirm_install,
            );
        }
        if self.ui_state.confirm_reboot {
            confirm_reboot_dialog(
                ctx,
                selected_device,
                &mut self.device_manager,
                &mut self.ui_state.confirm_reboot,
            );
        }
    }
}

fn panel_container(ui: &mut egui::Ui, add_contents: impl FnOnce(&mut egui::Ui)) {
    egui::Frame::none()
        .fill(PANEL_BG)
        .stroke(Stroke::new(1.0, BORDER))
        .rounding(10.0)
        .inner_margin(egui::Margin::symmetric(12.0, 10.0))
        .show(ui, |ui| {
            ui.set_min_height(PANEL_HEIGHT);
            add_contents(ui);
        });
}

fn section_title(ui: &mut egui::Ui, text: &str) {
    ui.label(RichText::new(text).strong().color(ACCENT));
}

fn discovery_panel(
    ui: &mut egui::Ui,
    snapshot: &DeviceManagerSnapshot,
    manager: &mut DeviceManager,
    ui_state: &mut UiState,
) {
    section_title(ui, "1. DISCOVERY");
    ui.add_space(8.0);

    ui.horizontal_wrapped(|ui| {
        chip(ui, "ADB", true);
        chip(ui, "LAN", false);
        chip(ui, "Wi-Fi", false);
        ui.colored_label(MUTED, format!("Probe: {}", unix_now()));
    });

    if let Some(err) = &snapshot.probe_error {
        ui.colored_label(WARN, format!("Probe error: {err}"));
    }

    ui.add_space(8.0);
    divider(ui);

    egui::ScrollArea::vertical()
        .max_height(DEVICE_LIST_HEIGHT)
        .show(ui, |ui| {
            if snapshot.devices.is_empty() {
                ui.colored_label(MUTED, "No devices discovered");
                return;
            }

            for device in &snapshot.devices {
                let selected = manager.is_selected(&device.serial);
                let card_fill = if selected { CARD_SELECTED_BG } else { CARD_BG };
                let card_stroke = if selected {
                    Stroke::new(1.2, Color32::from_rgb(99, 179, 255))
                } else {
                    Stroke::new(1.0, Color32::from_rgb(51, 64, 86))
                };

                let inner = egui::Frame::none()
                    .fill(card_fill)
                    .stroke(card_stroke)
                    .rounding(8.0)
                    .inner_margin(egui::Margin::symmetric(12.0, 10.0))
                    .show(ui, |ui| {
                        ui.set_min_height(86.0);
                        ui.horizontal(|ui| {
                            ui.label(
                                RichText::new(source_label(&device.discovery_source)).color(MUTED),
                            );
                            ui.separator();
                            ui.label(RichText::new(device_title(device)).strong());
                        });
                        ui.colored_label(
                            MUTED,
                            format!(
                                "Android {} (API {})",
                                fallback(&device.android_release, "?"),
                                fallback(&device.api_level, "?")
                            ),
                        );
                        ui.colored_label(
                            MUTED,
                            format!(
                                "{} id: {}",
                                source_label(&device.discovery_source),
                                fallback(&device.source_identity, "?")
                            ),
                        );
                        ui.colored_label(
                            MUTED,
                            format!(
                                "state: {} | abi: {}",
                                fallback(&device.adb_state, "?"),
                                fallback(&device.abi, "unknown")
                            ),
                        );
                    });

                let response = ui.interact(
                    inner.response.rect,
                    ui.id().with(format!("device_row_{}", device.serial)),
                    egui::Sense::click(),
                );
                if response.clicked() {
                    manager.select(&device.serial);
                }
                if response.double_clicked() {
                    manager.select(&device.serial);
                    ui_state.details_serial = Some(device.serial.clone());
                }
                ui.add_space(8.0);
            }
        });
}

fn properties_panel(
    ui: &mut egui::Ui,
    selected_device: Option<&DeviceInfo>,
    snapshot: &DeviceManagerSnapshot,
    device_manager: &mut DeviceManager,
    session_manager: &mut SessionManager,
    ui_state: &mut UiState,
) {
    section_title(ui, "2. DEVICE PROPERTIES & OPTIONS");
    ui.add_space(8.0);

    if let Some(device) = selected_device {
        ui.colored_label(
            OK,
            format!("Selected: {} ({})", device_title(device), device.serial),
        );
    } else {
        ui.colored_label(MUTED, "Select a device in panel 1");
    }

    egui::ScrollArea::vertical()
        .max_height(PANEL_SCROLL_HEIGHT)
        .show(ui, |ui| {
            ui.add_enabled_ui(selected_device.is_some(), |ui| {
                divider(ui);
                section_title(ui, "DEVICE / HOST INFO");
                ui.add_space(6.0);

                if let Some(device) = selected_device {
                    ui.colored_label(
                        MUTED,
                        format!(
                            "Manufacturer: {}",
                            fallback(&device.manufacturer, "unknown")
                        ),
                    );
                    ui.colored_label(
                        MUTED,
                        format!("Model: {}", fallback(&device.model, "unknown")),
                    );
                    ui.colored_label(
                        MUTED,
                        format!(
                            "Source: {} ({})",
                            source_label(&device.discovery_source),
                            fallback(&device.source_identity, "?")
                        ),
                    );
                    ui.colored_label(
                        MUTED,
                        format!(
                            "Android: {} (API {})",
                            fallback(&device.android_release, "?"),
                            fallback(&device.api_level, "?")
                        ),
                    );
                    ui.colored_label(MUTED, format!("ABI: {}", fallback(&device.abi, "unknown")));
                    ui.colored_label(
                        MUTED,
                        format!(
                            "Battery: {}% (status {})",
                            fallback(&device.battery_level, "?"),
                            fallback(&device.battery_status, "?")
                        ),
                    );
                }
                ui.colored_label(MUTED, format!("Host OS: {}", snapshot.host.os_name));
                if snapshot.host.os_name == "linux" {
                    ui.colored_label(MUTED, format!("Desktop: {}", snapshot.host.desktop_env));
                    ui.colored_label(MUTED, format!("Session: {}", snapshot.host.session_type));
                }

                ui.add_space(10.0);
                divider(ui);
                section_title(ui, "PROFILE");
                ui.add_space(6.0);
                render_mode_switch(ui, session_manager);
                ui.add_space(10.0);

                ui.horizontal(|ui| {
                    ui.label("Profile:");
                    let mut profile = session_manager.config().profile.clone();
                    if session_manager.config().mode == SessionMode::Automatic {
                        ui.add_enabled(false, egui::TextEdit::singleline(&mut profile));
                    } else if ui.text_edit_singleline(&mut profile).changed() {
                        session_manager.set_manual_profile(profile);
                        ui_state.manual_profile_error = session_manager.validate_config().err();
                    }
                });

                ui.horizontal(|ui| {
                    ui.label("Bitrate (kbps):");
                    let mut bitrate = session_manager.config().bitrate_kbps.to_string();
                    if session_manager.config().mode == SessionMode::Automatic {
                        ui.add_enabled(false, egui::TextEdit::singleline(&mut bitrate));
                    } else if ui.text_edit_singleline(&mut bitrate).changed() {
                        if let Ok(parsed) = bitrate.parse::<u32>() {
                            session_manager.set_manual_bitrate(parsed);
                            ui_state.manual_profile_error = session_manager.validate_config().err();
                        } else {
                            ui_state.manual_profile_error =
                                Some("manual bitrate must be a number".to_string());
                        }
                    }
                });

                if let Some(err) = &ui_state.manual_profile_error {
                    ui.colored_label(WARN, format!("Manual config warning: {err}"));
                }

                ui.add_space(10.0);
                divider(ui);
                section_title(ui, "ACTIONS");
                ui.add_space(6.0);

                let can_adb_actions = selected_device
                    .map(|d| d.adb_state == "device")
                    .unwrap_or(false);
                ui.horizontal_wrapped(|ui| {
                    if ui.button("Refresh Properties").clicked() {
                        device_manager.refresh_now();
                    }
                    if ui.button("Details").clicked() {
                        if let Some(device) = selected_device {
                            ui_state.details_serial = Some(device.serial.clone());
                        }
                    }
                    if ui.button("Capture Screenshot").clicked() {
                        run_adb_screenshot(device_manager, selected_device);
                    }
                });

                ui.add_enabled_ui(can_adb_actions, |ui| {
                    ui.horizontal_wrapped(|ui| {
                        if ui.button("ADB Reverse :5005").clicked() {
                            run_adb_action(
                                device_manager,
                                selected_device,
                                &["reverse", "tcp:5005", "tcp:5005"],
                            );
                        }
                        if ui.button("ADB Forward :5006").clicked() {
                            run_adb_action(
                                device_manager,
                                selected_device,
                                &["forward", "tcp:5006", "tcp:5006"],
                            );
                        }
                        if ui.button("Install/Update APK").clicked() {
                            ui_state.confirm_install = true;
                        }
                        if ui.button("Reboot Device").clicked() {
                            ui_state.confirm_reboot = true;
                        }
                    });
                });

                if !can_adb_actions {
                    ui.colored_label(MUTED, "ADB actions unavailable for current device state");
                }
            });
        });
}

fn session_panel(
    ui: &mut egui::Ui,
    selected_device: Option<&DeviceInfo>,
    session_manager: &mut SessionManager,
    runtime_events: &[String],
) {
    section_title(ui, "3. SESSION CONTROL & TELEMETRY");
    ui.add_space(8.0);

    ui.colored_label(
        MUTED,
        format!(
            "Device: {}",
            selected_device.map(|d| d.serial.as_str()).unwrap_or("none")
        ),
    );

    let can_start = selected_device
        .map(|d| d.adb_state == "device")
        .unwrap_or(false);

    ui.horizontal(|ui| {
        ui.add_enabled_ui(can_start, |ui| {
            if ui.button("Start Session").clicked() {
                session_manager.start(selected_device);
            }
        });
        if ui.button("Stop Session").clicked() {
            session_manager.stop();
        }
    });

    if !can_start {
        ui.colored_label(
            MUTED,
            "Start unavailable: choose device with state 'device'",
        );
    }

    ui.add_space(10.0);
    divider(ui);
    section_title(ui, "SESSION STATUS");
    ui.add_space(6.0);
    ui.colored_label(MUTED, format!("State: {:?}", session_manager.state()));
    ui.colored_label(
        MUTED,
        format!("Uptime: {}s", session_manager.uptime().as_secs()),
    );
    if let Some(err) = session_manager.last_error() {
        ui.colored_label(WARN, format!("Error: {err}"));
    }

    let telem = session_manager.telemetry();
    ui.add_space(10.0);
    divider(ui);
    section_title(ui, "LIVE TELEMETRY");
    ui.add_space(6.0);
    ui.colored_label(MUTED, format!("FPS: {:.1}", telem.fps));
    ui.colored_label(MUTED, format!("Latency: {} ms", telem.latency_ms));
    ui.colored_label(MUTED, format!("Drops: {}", telem.drops));
    ui.colored_label(MUTED, format!("Bitrate: {} kbps", telem.bitrate_kbps));

    ui.add_space(10.0);
    divider(ui);
    section_title(ui, "RECENT EVENTS");
    ui.add_space(6.0);
    let start_runtime = runtime_events.len().saturating_sub(4);
    for line in &runtime_events[start_runtime..] {
        ui.colored_label(MUTED, line);
    }
    let session_events = session_manager.event_log();
    let start_session = session_events.len().saturating_sub(4);
    for line in &session_events[start_session..] {
        ui.colored_label(MUTED, format!("[{}] {}", unix_now(), line));
    }
}

fn divider(ui: &mut egui::Ui) {
    let (rect, _) =
        ui.allocate_exact_size(egui::vec2(ui.available_width(), 1.0), egui::Sense::hover());
    ui.painter().line_segment(
        [rect.left_top(), rect.right_top()],
        Stroke::new(1.0, BORDER),
    );
    ui.add_space(8.0);
}

fn chip(ui: &mut egui::Ui, text: &str, active: bool) {
    let fill = if active {
        Color32::from_rgb(37, 70, 111)
    } else {
        Color32::from_rgb(33, 44, 61)
    };
    let stroke = if active {
        Stroke::new(1.0, Color32::from_rgb(99, 179, 255))
    } else {
        Stroke::new(1.0, Color32::from_rgb(59, 79, 113))
    };
    egui::Frame::none()
        .fill(fill)
        .stroke(stroke)
        .rounding(10.0)
        .inner_margin(egui::Margin::symmetric(8.0, 2.0))
        .show(ui, |ui| {
            ui.label(RichText::new(text).strong());
        });
}

fn status_pill(ui: &mut egui::Ui, text: &str, color: Color32) {
    egui::Frame::none()
        .fill(Color32::from_rgb(27, 33, 43))
        .stroke(Stroke::new(1.0, BORDER))
        .rounding(8.0)
        .inner_margin(egui::Margin::symmetric(8.0, 4.0))
        .show(ui, |ui| {
            ui.colored_label(color, RichText::new(text).strong());
        });
}

fn render_mode_switch(ui: &mut egui::Ui, session_manager: &mut SessionManager) {
    let width = ui.available_width().max(2.0);
    let half = width / 2.0;
    let auto_selected = session_manager.config().mode == SessionMode::Automatic;
    let manual_selected = session_manager.config().mode == SessionMode::Manual;

    ui.horizontal(|ui| {
        ui.spacing_mut().item_spacing.x = 0.0;

        let auto_fill = if auto_selected {
            Color32::from_rgb(43, 111, 179)
        } else {
            Color32::from_rgb(30, 42, 58)
        };
        let manual_fill = if manual_selected {
            Color32::from_rgb(43, 111, 179)
        } else {
            Color32::from_rgb(30, 42, 58)
        };

        if ui
            .add_sized(
                [half, 28.0],
                egui::Button::new(RichText::new("AUTOMATIC").strong())
                    .fill(auto_fill)
                    .stroke(Stroke::NONE),
            )
            .clicked()
        {
            session_manager.set_mode(SessionMode::Automatic);
        }

        if ui
            .add_sized(
                [half, 28.0],
                egui::Button::new(RichText::new("MANUAL").strong())
                    .fill(manual_fill)
                    .stroke(Stroke::NONE),
            )
            .clicked()
        {
            session_manager.set_mode(SessionMode::Manual);
        }
    });
}

fn run_adb_action(
    device_manager: &mut DeviceManager,
    selected_device: Option<&DeviceInfo>,
    args: &[&str],
) {
    let Some(device) = selected_device else {
        device_manager.push_ui_event("action blocked: no selected device");
        return;
    };

    let output = Command::new("adb")
        .arg("-s")
        .arg(&device.serial)
        .args(args)
        .output();

    match output {
        Ok(out) if out.status.success() => {
            device_manager.push_ui_event(format!("adb {}: ok", args.join(" ")));
        }
        Ok(out) => {
            let stderr = String::from_utf8_lossy(&out.stderr).trim().to_string();
            if stderr.is_empty() {
                device_manager.push_ui_event(format!(
                    "adb {} failed: status {}",
                    args.join(" "),
                    out.status
                ));
            } else {
                device_manager.push_ui_event(format!("adb {} failed: {}", args.join(" "), stderr));
            }
        }
        Err(err) => {
            device_manager.push_ui_event(format!("adb {} failed: {}", args.join(" "), err));
        }
    }
}

fn run_adb_screenshot(device_manager: &mut DeviceManager, selected_device: Option<&DeviceInfo>) {
    let Some(device) = selected_device else {
        device_manager.push_ui_event("screenshot blocked: no selected device");
        return;
    };

    let dir = PathBuf::from("/tmp/wbeam-screenshots");
    if let Err(err) = fs::create_dir_all(&dir) {
        device_manager.push_ui_event(format!("screenshot failed: cannot create dir: {err}"));
        return;
    }

    let path = dir.join(format!("{}-{}.png", device.serial, unix_now()));
    let output = Command::new("adb")
        .arg("-s")
        .arg(&device.serial)
        .args(["exec-out", "screencap", "-p"])
        .output();

    match output {
        Ok(out) if out.status.success() => match fs::write(&path, &out.stdout) {
            Ok(_) => device_manager.push_ui_event(format!("screenshot saved: {}", path.display())),
            Err(err) => device_manager.push_ui_event(format!("screenshot save failed: {err}")),
        },
        Ok(out) => {
            let stderr = String::from_utf8_lossy(&out.stderr).trim().to_string();
            device_manager.push_ui_event(format!(
                "screenshot failed: {}",
                if stderr.is_empty() {
                    format!("status {}", out.status)
                } else {
                    stderr
                }
            ));
        }
        Err(err) => device_manager.push_ui_event(format!("screenshot failed: {err}")),
    }
}

fn run_adb_install(device_manager: &mut DeviceManager, selected_device: Option<&DeviceInfo>) {
    let Some(device) = selected_device else {
        device_manager.push_ui_event("install blocked: no selected device");
        return;
    };

    let Some(apk_path) = resolve_apk_path() else {
        device_manager
            .push_ui_event("install failed: APK not found (set WBEAM_APK_PATH or build debug APK)");
        return;
    };

    let output = Command::new("adb")
        .arg("-s")
        .arg(&device.serial)
        .args(["install", "-r"])
        .arg(&apk_path)
        .output();

    match output {
        Ok(out) if out.status.success() => {
            device_manager.push_ui_event(format!("install ok: {}", apk_path.display()));
        }
        Ok(out) => {
            let stderr = String::from_utf8_lossy(&out.stderr).trim().to_string();
            device_manager.push_ui_event(format!(
                "install failed: {}",
                if stderr.is_empty() {
                    format!("status {}", out.status)
                } else {
                    stderr
                }
            ));
        }
        Err(err) => device_manager.push_ui_event(format!("install failed: {err}")),
    }
}

fn run_adb_reboot(device_manager: &mut DeviceManager, selected_device: Option<&DeviceInfo>) {
    run_adb_action(device_manager, selected_device, &["reboot"]);
}

fn confirm_install_dialog(
    ctx: &egui::Context,
    selected_device: Option<&DeviceInfo>,
    device_manager: &mut DeviceManager,
    open: &mut bool,
) {
    let mut keep_open = true;
    egui::Window::new("Confirm Install")
        .open(&mut keep_open)
        .collapsible(false)
        .resizable(false)
        .show(ctx, |ui| {
            ui.label("Install/Update APK on selected device?");
            ui.small("This may restart the app on device.");
            ui.add_space(8.0);
            ui.horizontal(|ui| {
                if ui.button("Install").clicked() {
                    run_adb_install(device_manager, selected_device);
                    *open = false;
                }
                if ui.button("Cancel").clicked() {
                    *open = false;
                }
            });
        });
    if !keep_open {
        *open = false;
    }
}

fn confirm_reboot_dialog(
    ctx: &egui::Context,
    selected_device: Option<&DeviceInfo>,
    device_manager: &mut DeviceManager,
    open: &mut bool,
) {
    let mut keep_open = true;
    egui::Window::new("Confirm Reboot")
        .open(&mut keep_open)
        .collapsible(false)
        .resizable(false)
        .show(ctx, |ui| {
            ui.colored_label(WARN, "Reboot selected device now?");
            ui.small("This will interrupt an active session.");
            ui.add_space(8.0);
            ui.horizontal(|ui| {
                if ui.button("Reboot").clicked() {
                    run_adb_reboot(device_manager, selected_device);
                    *open = false;
                }
                if ui.button("Cancel").clicked() {
                    *open = false;
                }
            });
        });
    if !keep_open {
        *open = false;
    }
}

fn resolve_apk_path() -> Option<PathBuf> {
    if let Ok(path) = std::env::var("WBEAM_APK_PATH") {
        let p = PathBuf::from(path);
        if p.exists() {
            return Some(p);
        }
    }

    let candidates = [
        "android/app/build/outputs/apk/debug/app-debug.apk",
        "proto/front/app/build/outputs/apk/debug/app-debug.apk",
    ];
    for c in candidates {
        let p = PathBuf::from(c);
        if p.exists() {
            return Some(p);
        }
    }
    None
}

fn source_label(source: &DiscoverySource) -> &'static str {
    match source {
        DiscoverySource::Adb => "ADB",
        DiscoverySource::Lan => "LAN",
        DiscoverySource::Wifi => "Wi-Fi",
    }
}

fn device_title(device: &DeviceInfo) -> String {
    match (device.manufacturer.trim(), device.model.trim()) {
        ("", "") => "Unknown device".to_string(),
        (m, "") => m.to_string(),
        ("", d) => d.to_string(),
        (m, d) => format!("{m} {d}"),
    }
}

fn fallback<'a>(value: &'a str, missing: &'a str) -> &'a str {
    if value.trim().is_empty() {
        missing
    } else {
        value.trim()
    }
}

fn unix_now() -> String {
    let secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    secs.to_string()
}

fn run_gui() {
    let mut viewport = egui::ViewportBuilder::default()
        .with_title("WBeam Desktop")
        .with_app_id(APP_ID)
        .with_inner_size([WINDOW_WIDTH, WINDOW_HEIGHT])
        .with_min_inner_size([WINDOW_WIDTH, WINDOW_HEIGHT])
        .with_max_inner_size([WINDOW_WIDTH, WINDOW_HEIGHT])
        .with_resizable(false);

    match eframe::icon_data::from_png_bytes(APP_ICON_PNG) {
        Ok(icon) => {
            viewport = viewport.with_icon(icon);
        }
        Err(err) => {
            eprintln!("warning: failed to load app icon from src/assets/wbeam.png: {err}");
        }
    }

    let options = eframe::NativeOptions {
        viewport,
        ..Default::default()
    };

    let run = eframe::run_native(
        "WBeam Desktop",
        options,
        Box::new(move |_cc| Box::new(DesktopApp::new())),
    );
    if let Err(err) = run {
        eprintln!("failed to start desktop app: {err}");
        std::process::exit(1);
    }
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let parsed = match DevtoolCommand::parse(&args) {
        Ok(cmd) => cmd,
        Err(err) => {
            eprintln!("[devtool] {err}");
            DevtoolCommand::print_help();
            std::process::exit(2);
        }
    };

    match parsed {
        DevtoolCommand::Gui => run_gui(),
        DevtoolCommand::Help => DevtoolCommand::print_help(),
        cmd => match run_cli_command(cmd) {
            Ok(code) => std::process::exit(code),
            Err(err) => {
                eprintln!("[devtool] {err}");
                std::process::exit(1);
            }
        },
    }
}
