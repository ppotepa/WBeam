use std::f32::consts::PI;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant};

use anyhow::{Context, Result};
use gstreamer as gst;
use gstreamer::prelude::*;
use gstreamer_app as gst_app;

use crate::cli::ResolvedConfig;

const CYCLE_SECONDS: u64 = 12;
const SCENE_COUNT: u64 = 4;
const SCENE_SECONDS: u64 = CYCLE_SECONDS / SCENE_COUNT;

const CUBE_VERTICES: [[f32; 3]; 8] = [
    [-1.0, -1.0, -1.0],
    [1.0, -1.0, -1.0],
    [1.0, 1.0, -1.0],
    [-1.0, 1.0, -1.0],
    [-1.0, -1.0, 1.0],
    [1.0, -1.0, 1.0],
    [1.0, 1.0, 1.0],
    [-1.0, 1.0, 1.0],
];

const CUBE_EDGES: [(usize, usize); 12] = [
    (0, 1),
    (1, 2),
    (2, 3),
    (3, 0),
    (4, 5),
    (5, 6),
    (6, 7),
    (7, 4),
    (0, 4),
    (1, 5),
    (2, 6),
    (3, 7),
];

fn clear_rgba(frame: &mut [u8], rgba: [u8; 4]) {
    for px in frame.chunks_exact_mut(4) {
        px[0] = rgba[0];
        px[1] = rgba[1];
        px[2] = rgba[2];
        px[3] = rgba[3];
    }
}

#[inline]
fn blend_px(frame: &mut [u8], width: usize, height: usize, x: i32, y: i32, rgba: [u8; 4]) {
    if x < 0 || y < 0 {
        return;
    }
    let x = x as usize;
    let y = y as usize;
    if x >= width || y >= height {
        return;
    }
    let idx = (y * width + x) * 4;
    let alpha = rgba[3] as u16;
    if alpha == 255 {
        frame[idx] = rgba[0];
        frame[idx + 1] = rgba[1];
        frame[idx + 2] = rgba[2];
        frame[idx + 3] = 255;
        return;
    }
    let inv = 255u16.saturating_sub(alpha);
    frame[idx] = ((frame[idx] as u16 * inv + rgba[0] as u16 * alpha) / 255) as u8;
    frame[idx + 1] = ((frame[idx + 1] as u16 * inv + rgba[1] as u16 * alpha) / 255) as u8;
    frame[idx + 2] = ((frame[idx + 2] as u16 * inv + rgba[2] as u16 * alpha) / 255) as u8;
    frame[idx + 3] = 255;
}

fn draw_rect(
    frame: &mut [u8],
    width: usize,
    height: usize,
    x: i32,
    y: i32,
    w: i32,
    h: i32,
    rgba: [u8; 4],
) {
    if w <= 0 || h <= 0 {
        return;
    }
    let x0 = x.max(0).min(width as i32);
    let y0 = y.max(0).min(height as i32);
    let x1 = (x + w).max(0).min(width as i32);
    let y1 = (y + h).max(0).min(height as i32);
    if x0 >= x1 || y0 >= y1 {
        return;
    }
    for yy in y0..y1 {
        for xx in x0..x1 {
            blend_px(frame, width, height, xx, yy, rgba);
        }
    }
}

fn draw_line(
    frame: &mut [u8],
    width: usize,
    height: usize,
    mut x0: i32,
    mut y0: i32,
    x1: i32,
    y1: i32,
    rgba: [u8; 4],
) {
    let dx = (x1 - x0).abs();
    let sx = if x0 < x1 { 1 } else { -1 };
    let dy = -(y1 - y0).abs();
    let sy = if y0 < y1 { 1 } else { -1 };
    let mut err = dx + dy;
    loop {
        blend_px(frame, width, height, x0, y0, rgba);
        if x0 == x1 && y0 == y1 {
            break;
        }
        let e2 = err * 2;
        if e2 >= dy {
            err += dy;
            x0 += sx;
        }
        if e2 <= dx {
            err += dx;
            y0 += sy;
        }
    }
}

#[inline]
fn tri_wave(v: f32) -> f32 {
    let x = v.rem_euclid(1.0);
    if x < 0.5 {
        x * 2.0
    } else {
        (1.0 - x) * 2.0
    }
}

fn draw_cube_wireframe(
    frame: &mut [u8],
    width: usize,
    height: usize,
    cx: f32,
    cy: f32,
    scale: f32,
    angle: f32,
) {
    let sin_y = angle.sin();
    let cos_y = angle.cos();
    let sin_x = (angle * 0.72).sin();
    let cos_x = (angle * 0.72).cos();

    let mut projected = [(0i32, 0i32); CUBE_VERTICES.len()];
    for (idx, [x, y, z]) in CUBE_VERTICES.iter().copied().enumerate() {
        let x1 = x * cos_y + z * sin_y;
        let z1 = -x * sin_y + z * cos_y;
        let y2 = y * cos_x - z1 * sin_x;
        let z2 = y * sin_x + z1 * cos_x + 3.6;
        let inv_z = 1.0 / z2.max(0.2);
        projected[idx] = (
            (cx + x1 * scale * inv_z).round() as i32,
            (cy + y2 * scale * inv_z).round() as i32,
        );
    }

    for (edge_idx, (a, b)) in CUBE_EDGES.iter().copied().enumerate() {
        let color = if edge_idx < 4 {
            [0x9f, 0xd8, 0xff, 255]
        } else if edge_idx < 8 {
            [0x7a, 0xff, 0xb4, 255]
        } else {
            [0xff, 0xc5, 0x72, 255]
        };
        let (x0, y0) = projected[a];
        let (x1, y1) = projected[b];
        draw_line(frame, width, height, x0, y0, x1, y1, color);
    }
}

fn scene_cube_and_squares(
    frame: &mut [u8],
    width: usize,
    height: usize,
    global_t: f32,
    scene_t: f32,
) {
    clear_rgba(frame, [12, 18, 30, 255]);
    let spacing = ((width as i32) / 16).max(28);
    let drift = ((global_t * 32.0).round() as i32).rem_euclid(spacing);
    for x in (-(spacing - drift)..(width as i32 + spacing)).step_by(spacing as usize) {
        draw_rect(
            frame,
            width,
            height,
            x,
            0,
            1,
            height as i32,
            [28, 42, 64, 255],
        );
    }
    for y in (0..height as i32).step_by(((height / 12).max(18)) as usize) {
        draw_rect(
            frame,
            width,
            height,
            0,
            y,
            width as i32,
            1,
            [24, 34, 56, 255],
        );
    }

    let cube_x = width as f32 * 0.34;
    let cube_y = height as f32 * 0.52;
    let cube_scale = width.min(height) as f32 * 0.78;
    draw_cube_wireframe(
        frame,
        width,
        height,
        cube_x,
        cube_y,
        cube_scale,
        global_t * 1.95,
    );

    let amp = width as f32 * 0.24;
    let base_x = width as f32 * 0.70;
    let scene_phase = (scene_t / SCENE_SECONDS as f32) * PI * 2.0;
    let rows = 24;
    for i in 0..rows {
        let fi = i as f32;
        let y = (((fi + 0.5) / rows as f32) * (height as f32 * 0.88) + height as f32 * 0.06) as i32;
        let side = 10 + ((i * 7) % 16) as i32;
        let x = (base_x + amp * (scene_phase + fi * 0.43).sin()) as i32;
        let color = [
            (120 + ((i * 11) % 120) as u8),
            (80 + ((i * 17) % 140) as u8),
            (160 + ((i * 7) % 90) as u8),
            215,
        ];
        draw_rect(frame, width, height, x, y, side, side, color);
    }
}

fn scene_parallax_layers(frame: &mut [u8], width: usize, height: usize, scene_t: f32) {
    clear_rgba(frame, [10, 24, 18, 255]);
    let horizon_y = (height as f32 * 0.58) as i32;
    draw_rect(
        frame,
        width,
        height,
        0,
        horizon_y,
        width as i32,
        height as i32 - horizon_y,
        [18, 38, 30, 255],
    );

    let layer_speeds = [90.0, 150.0, 240.0];
    let layer_counts = [10, 16, 24];
    let layer_sizes = [(180, 56), (110, 36), (64, 24)];
    let layer_colors = [
        [68, 168, 196, 130],
        [120, 220, 150, 150],
        [235, 196, 96, 175],
    ];

    for layer in 0..3 {
        let speed = layer_speeds[layer];
        let (rw, rh) = layer_sizes[layer];
        let count = layer_counts[layer];
        let color = layer_colors[layer];
        for i in 0..count {
            let fi = i as f32;
            let span = width as f32 + rw as f32 * 2.0;
            let base = ((i * 97 + layer * 41) % (span as usize)) as f32 - rw as f32;
            let x = (base + scene_t * speed).rem_euclid(span) - rw as f32;
            let y_base = (height as f32 * (0.18 + layer as f32 * 0.2))
                + (fi * 13.0).rem_euclid(height as f32 * 0.42);
            let y = y_base + (scene_t * (1.3 + layer as f32 * 0.9) + fi * 0.37).sin() * 18.0;
            draw_rect(frame, width, height, x as i32, y as i32, rw, rh, color);
        }
    }

    for i in 0..18 {
        let fi = i as f32;
        let y = (height as f32 * 0.62 + fi * 22.0).round() as i32;
        let w = ((width as f32 * 0.35) + 120.0 * (scene_t * 1.2 + fi * 0.25).sin()).abs() as i32;
        let x =
            ((scene_t * 180.0 + fi * 70.0).rem_euclid(width as f32 + w as f32) - w as f32) as i32;
        draw_rect(
            frame,
            width,
            height,
            x,
            y,
            w.max(24),
            3,
            [90, 250, 200, 180],
        );
    }
}

fn scene_particles_and_bars(frame: &mut [u8], width: usize, height: usize, scene_t: f32) {
    clear_rgba(frame, [24, 10, 34, 255]);
    let cx = width as f32 * 0.5;
    let cy = height as f32 * 0.5;

    for i in 0..260u32 {
        let fi = i as f32;
        let radius = 32.0
            + (fi * 3.7).rem_euclid((width.min(height) as f32 * 0.44).max(40.0))
            + 14.0 * (scene_t * 2.4 + fi * 0.11).sin();
        let angle = scene_t * 3.1 + fi * 0.23;
        let x = cx + radius * angle.cos();
        let y = cy + radius * (angle * 1.17).sin();
        let sz = 2 + (i % 3) as i32;
        let color = [
            (110 + (i * 13 % 140) as u8),
            (90 + (i * 29 % 130) as u8),
            (180 + (i * 17 % 70) as u8),
            215,
        ];
        draw_rect(
            frame,
            width,
            height,
            x.round() as i32,
            y.round() as i32,
            sz,
            sz,
            color,
        );
    }

    let bars = 42usize;
    let bar_w = (width / bars.max(1)).max(2) as i32;
    for i in 0..bars {
        let fi = i as f32;
        let mag = (scene_t * 4.8 + fi * 0.42).sin().abs();
        let bar_h = (mag * height as f32 * 0.42) as i32 + (height as i32 / 12);
        let x = (i as i32 * bar_w).min(width as i32 - 1);
        let y = (height as i32 - bar_h).max(0);
        draw_rect(
            frame,
            width,
            height,
            x,
            y,
            bar_w.max(1),
            bar_h,
            [120, 210, 255, 170],
        );
    }
}

fn scene_stress_mix(frame: &mut [u8], width: usize, height: usize, scene_t: f32) {
    clear_rgba(frame, [30, 12, 10, 255]);

    let span = width as i32 + height as i32;
    for i in 0..26i32 {
        let offset = ((scene_t * 430.0) as i32 + i * 37).rem_euclid(span);
        let x0 = offset - height as i32;
        let y0 = 0;
        let x1 = offset;
        let y1 = height as i32;
        draw_line(frame, width, height, x0, y0, x1, y1, [255, 110, 80, 120]);
    }

    for i in 0..56usize {
        let fi = i as f32;
        let size = 14 + (i % 18) as i32;
        let fx = tri_wave(scene_t * (0.22 + (i % 9) as f32 * 0.07) + fi * 0.11);
        let fy = tri_wave(scene_t * (0.27 + (i % 7) as f32 * 0.09) + fi * 0.17);
        let x = (fx * (width as f32 - size as f32)).round() as i32;
        let y = (fy * (height as f32 - size as f32)).round() as i32;
        let color = [
            (120 + ((i * 5) % 130) as u8),
            (60 + ((i * 17) % 140) as u8),
            (40 + ((i * 29) % 170) as u8),
            220,
        ];
        draw_rect(frame, width, height, x, y, size, size, color);
    }
}

fn render_frame(frame: &mut [u8], width: usize, height: usize, frame_idx: u64, fps: u32) {
    let fps_u64 = u64::from(fps.max(1));
    let cycle_frames = fps_u64 * CYCLE_SECONDS;
    let scene_frames = fps_u64 * SCENE_SECONDS;
    let frame_in_cycle = frame_idx % cycle_frames.max(1);
    let scene_idx = (frame_in_cycle / scene_frames.max(1)).min(SCENE_COUNT - 1) as usize;
    let scene_frame = frame_in_cycle % scene_frames.max(1);

    let global_t = frame_in_cycle as f32 / fps.max(1) as f32;
    let scene_t = scene_frame as f32 / fps.max(1) as f32;

    match scene_idx {
        0 => scene_cube_and_squares(frame, width, height, global_t, scene_t),
        1 => scene_parallax_layers(frame, width, height, scene_t),
        2 => scene_particles_and_bars(frame, width, height, scene_t),
        _ => scene_stress_mix(frame, width, height, scene_t),
    }
}

pub fn build_source(cfg: &ResolvedConfig) -> Result<gst::Element> {
    let width = cfg.width.max(320);
    let height = cfg.height.max(180);
    let fps = cfg.fps.max(1);
    let frame_duration_ns = 1_000_000_000u64 / u64::from(fps);
    let frame_size = (width as usize)
        .saturating_mul(height as usize)
        .saturating_mul(4usize);

    let bin = gst::Bin::with_name("src");

    let src_element = gst::ElementFactory::make("appsrc")
        .name("bench_src")
        .build()
        .context("appsrc missing")?;
    let src = src_element
        .clone()
        .dynamic_cast::<gst_app::AppSrc>()
        .map_err(|_| anyhow::anyhow!("bench_src is not an appsrc"))?;

    src.set_is_live(true);
    src.set_format(gst::Format::Time);
    src.set_block(true);
    src.set_do_timestamp(false);
    src.set_max_bytes((frame_size as u64).saturating_mul(4));
    src.set_caps(Some(
        &gst::Caps::builder("video/x-raw")
            .field("format", "RGBA")
            .field("width", width as i32)
            .field("height", height as i32)
            .field("framerate", gst::Fraction::new(fps as i32, 1))
            .build(),
    ));

    let frame_counter = Arc::new(AtomicU64::new(0));
    let callback_counter = frame_counter.clone();
    // Wall-clock start time used to pace frame delivery to the target fps.
    // Without this, nvenc drains the appsrc buffer in microseconds and
    // need_data fires thousands of times per second (2000+ fps observed).
    let start_time = Instant::now();
    src.set_callbacks(
        gst_app::AppSrcCallbacks::builder()
            .need_data(move |appsrc, _| {
                let frame_idx = callback_counter.fetch_add(1, Ordering::Relaxed);

                // Pace: sleep until this frame is due on wall clock.
                let due = start_time + Duration::from_nanos(frame_idx.saturating_mul(frame_duration_ns));
                let now = Instant::now();
                if due > now {
                    std::thread::sleep(due - now);
                }

                let mut buffer = match gst::Buffer::with_size(frame_size) {
                    Ok(b) => b,
                    Err(_) => return,
                };
                {
                    let Some(buffer_mut) = buffer.get_mut() else {
                        return;
                    };
                    buffer_mut.set_pts(gst::ClockTime::from_nseconds(
                        frame_idx.saturating_mul(frame_duration_ns),
                    ));
                    buffer_mut.set_duration(gst::ClockTime::from_nseconds(frame_duration_ns));
                    let mut writable = match buffer_mut.map_writable() {
                        Ok(m) => m,
                        Err(_) => return,
                    };
                    render_frame(
                        writable.as_mut_slice(),
                        width as usize,
                        height as usize,
                        frame_idx,
                        fps,
                    );
                }
                let _ = appsrc.push_buffer(buffer);
            })
            .build(),
    );

    bin.add(&src_element)?;
    let mut tail = src_element.clone();

    if let Ok(title) = gst::ElementFactory::make("textoverlay")
        .name("bench_title")
        .build()
    {
        title.set_property("text", "WBeam Benchmark 2D • 12s loop • 4 scenes");
        let _ = title.set_property_from_str("valignment", "top");
        let _ = title.set_property_from_str("halignment", "left");
        let _ = title.set_property_from_str("font-desc", "Sans Bold 24");
        let _ = title.set_property("shaded-background", true);
        bin.add(&title)?;
        tail.link(&title)?;
        tail = title;
    }

    if let Ok(timer) = gst::ElementFactory::make("timeoverlay")
        .name("bench_timer")
        .build()
    {
        let _ = timer.set_property_from_str("valignment", "bottom");
        let _ = timer.set_property_from_str("halignment", "right");
        let _ = timer.set_property("shaded-background", true);
        bin.add(&timer)?;
        tail.link(&timer)?;
        tail = timer;
    }

    let src_pad = tail
        .static_pad("src")
        .context("benchmark source src pad missing")?;
    let ghost = gst::GhostPad::with_target(&src_pad).context("benchmark ghost pad create")?;
    ghost
        .set_active(true)
        .context("benchmark ghost pad activation")?;
    bin.add_pad(&ghost)
        .context("benchmark ghost pad registration")?;

    Ok(bin.upcast::<gst::Element>())
}
