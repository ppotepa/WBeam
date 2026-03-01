# WBeam Performance Targets v1

Task-IDs: A1, A2

## KPI Set

| KPI | Unit | Green | Yellow | Red |
|-----|------|-------|--------|-----|
| `fps_present` | fps | ≥ 55 | 40–55 | < 40 |
| `frametime_p95` | ms | ≤ 20 | 20–33 | > 33 |
| `frametime_p99` | ms | ≤ 33 | 33–50 | > 50 |
| `e2e_p95` | ms | ≤ 80 | 80–150 | > 150 |
| `decode_p95` | ms | ≤ 8 | 8–16 | > 16 |
| `render_p95` | ms | ≤ 4 | 4–10 | > 10 |
| `drops_per_sec` | /s | ≤ 1 | 1–5 | > 5 |
| `queue_transport` | frames | ≤ 2 | 2–4 | > 4 |
| `queue_decode` | frames | ≤ 2 | 2–4 | > 4 |
| `queue_render` | frames | ≤ 1 | 1–2 | > 2 |

## Frame Budget — 16.67 ms @ 60 FPS

| Stage | Budget | Notes |
|-------|--------|-------|
| Capture (PipeWire/Wayland) | 2.0 ms | GPU DMA path; PipeWire negotiated |
| Encode (NVENC/openh264) | 4.0 ms | zerolatency, B-frames off |
| Transport (USB ADB tunnel) | 1.5 ms | loopback TCP via adb reverse |
| Decode (MediaCodec HW) | 6.0 ms | decode-to-Surface, no CPU copy |
| Render/present (Surface) | 2.0 ms | releaseOutputBuffer(render=true) |
| **Margin** | **1.17 ms** | |
| **Total** | **16.67 ms** | |

## Profiles and Resolution Targets

| Profile | Resolution | FPS | Bitrate | Primary device |
|---------|-----------|-----|---------|----------------|
| `lowlatency` | 1280×720 | 60 | 12 Mbps | Weak Android |
| `balanced` | 1600×900 | 60 | 16 Mbps | Mid Android |
| `ultra` | 1920×1080 | 60 | 24 Mbps | Strong Android |

## Queue Hard Limits

| Queue | Max frames | Policy | Location |
|-------|-----------|--------|----------|
| GStreamer q1 | 2 | leaky=downstream | src/host/scripts/stream_wayland_portal_h264.py |
| GStreamer qmain | 2 | leaky=downstream | src/host/scripts/stream_wayland_portal_h264.py |
| GStreamer qdbg | 1 | leaky=downstream | src/host/scripts/stream_wayland_portal_h264.py |
| Android transport | 3 | drop latest | android/.../MainActivity.java |
| Android decode | 2 | drop latest | android/.../MainActivity.java |
| Android render | 1 | latest-frame-wins | android/.../MainActivity.java |

## Adaptation State Machine (D2)

| Level | Trigger | Action |
|-------|---------|--------|
| L0 (baseline) | normal | — |
| L1 (mild) | `decode_p95 > 10ms` OR `queue_decode > 2` | bitrate −20% |
| L2 (medium) | L1 condition for 2 consecutive samples | bitrate −40% + fps cap 45 |
| L3 (emergency) | L2 condition for 2 consecutive samples | drop to `lowlatency` profile |
| Recover | low-pressure for 8 consecutive samples, cooldown 4s | step up |

## Release Gate (blocking)

1. No restart loops (< 1 watchdog kill per 10 min session).
2. No persistent black screen with active decode (`fps_present > 0` within 5s of stream start).
3. `queue_transport + queue_decode` never exceeds 6 frames in 30-min test.
4. `e2e_p95 ≤ 80ms` on USB path.
5. `frametime_p95 ≤ 20ms` at `lowlatency` profile.
