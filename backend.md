# WBeam Backend - End-to-End Pipeline, Training, and Investigation Guide

Last updated: 2026-03-11  
Branch reference: `trainerv2`

## 1. Document goal

This document is a complementary technical map of WBeam backend/runtime behavior:

- how the app works step by step (host -> transport -> Android decode),
- where training/autotune fits,
- what parameters are currently active and clamped,
- where likely quality/stability gaps may exist,
- how to run a focused external investigation (prompt-ready section included).

Primary goal: enable deep pipeline review to identify bottlenecks causing artifacts, instability, or quality loss under difficult conditions (low bitrate, multi-device, dynamic content).

---

## 2. System architecture at a glance

WBeam has 4 runtime layers:

1. Orchestration/CLI layer
- `./wbeam` (canonical entrypoint)
- `./trainer.sh`, `./desktop.sh`, `./start-remote`, `./redeploy-local`

2. Host control plane (daemon/API)
- Rust server: `src/host/rust/crates/wbeamd-server`
- Rust core/state machine: `src/host/rust/crates/wbeamd-core`
- API layer + config validation: `src/host/rust/crates/wbeamd-api`

3. Host data plane (capture/encode/send)
- Rust streamer: `src/host/rust/crates/wbeamd-streamer`
- Capture backends: Wayland portal or X11
- Encoder backends: NVENC (if available) else x264/x265 fallback; raw PNG path

4. Android receive/decode/render
- Main activity and session orchestration: `android/app/src/main/java/com/wbeam/MainActivity.java`
- Decoder/transport client: `android/app/src/main/java/com/wbeam/stream/H264TcpPlayer.java`
- Polling/status integration: `android/app/src/main/java/com/wbeam/api/*`

Trainer domain (owned path, no legacy proto ownership):
- `src/domains/training/wizard.py`
- Trainer UI: `src/apps/trainer-tauri`

---

## 3. Core runtime flow (normal connect)

## 3.1 Device discovery and session addressing

- Multiple Android devices are supported using per-device session cores keyed by ADB serial.
- Session-aware endpoints use query parameters:
  - `serial=<adb_serial>`
  - `stream_port=<port>`
- Port mapping is tracked in `.wbeam_device_ports`.

## 3.2 Desktop/Tauri connect action

From desktop side, connect flow is:

1. `adb start-server`
2. readiness probe (`adb -s <serial> get-state`)
3. `adb reverse` for control and stream ports (when available)
4. launch app activity (`adb shell am start -n com.wbeam/.MainActivity`)
5. daemon `POST /v1/start?serial=...&stream_port=...`

Main connect diagnostics:
- `logs/desktop-connect.log`

## 3.3 Daemon start/apply lifecycle

Important endpoints (also available under non-`/v1` aliases):

- `GET /v1/status`
- `GET /v1/health`
- `GET /v1/metrics`
- `GET /v1/host-probe`
- `POST /v1/start`
- `POST /v1/apply`
- `POST /v1/stop`
- `POST /v1/client-metrics`
- `POST /v1/client-hello`

Behavior:
- `start`: resolves session core, validates config, launches streamer process.
- `apply`: validates patch and applies config; for many fields restart is required.
- `stop`: stops stream process and transitions state.

Config validation is centralized in `wbeamd-api`.

---

## 4. Capture/encode/send data plane

## 4.1 Streamer process launch

`wbeamd-core` launches streamer with explicit args:
- `--encoder`
- `--size`
- `--fps`
- `--bitrate-kbps`
- `--cursor-mode`
- `--intra-only` (optional)
- restore token / portal persistence options (Wayland portal path)

It can choose:
- Rust streamer path (`wbeamd-streamer`)
- Python fallback streamer path in specific scenarios (notably some trainer/portal reuse flows)

## 4.2 Capture backend selection

`wbeamd-streamer` capture backends:
- `wayland-portal` (recommended/stable path)
- `x11` (supported but more environment-sensitive)
- `auto` resolves from session/runtime context

## 4.3 Encoder selection

Requested logical encoders:
- `h264`
- `h265`
- `rawpng`

Concrete backend resolution:
- `h264` -> `nvh264enc` if present, else `x264enc`
- `h265` -> `nvh265enc` if present, else `x265enc`
- `rawpng` -> `pngenc`

Current host observation to verify on every machine:
- If `nvh264enc/nvh265enc` are missing, quality behavior at low bitrate differs significantly due to CPU encoder fallback.

## 4.4 Current important clamps/limits

Runtime bitrate floor (effective):
- `>= 4000 kbps` (4 Mbps)

Where enforced:
- `wbeamd-api` config validation
- `wbeamd-streamer` profile resolution

Other key clamps:
- FPS typically clamped to `24..120` in validated runtime config
- size normalized and bounded

Implication:
- Setting 1 Mbps in UI is not physically applied if backend floor is 4 Mbps.

## 4.5 H264/H265 quality strategy currently

Current x264 low-latency config is intentionally aggressive:
- `speed-preset=ultrafast`
- `tune=zerolatency`
- option-string includes `bframes=0`, `cabac=0`, `ref=1`, etc.

This improves latency and resilience but can degrade compression efficiency.
At low bitrate and high motion/complex scenes this can produce visible artifacts faster than a quality-oriented preset.

H265 has separate clamps/safety behavior and may perform better in quality-per-bit under suitable decoder/device support.

## 4.6 Transport protocol

Android client receives WBTP framed stream:
- 22-byte header + payload
- codec flags exchanged in HELLO
- sequence/timestamp framing supports metrics/watchdog logic

---

## 5. Android decode/render pipeline

Primary decode path:
- `H264TcpPlayer` handles socket receive, framed parse, codec init, queueing, render.
- Uses `MediaCodec` with reconnect/watchdog ladder for stalls and no-present conditions.

Key runtime characteristics:
- bounded decode/render queue model
- reconnect and flush logic on stalls
- metrics sampling pushed via `/v1/client-metrics`

Codec selection behavior:
- device capability aware (HEVC support detection)
- fallback to H264 when HEVC decode not available

Overlay/HUD:
- runtime and trainer overlays are rendered through unified debug/HUD path in `MainActivity`.

---

## 6. Metrics/adaptation loop

Feedback loop:

1. Host streams frames.
2. Android collects live stats (recv/decode/present fps, decode/render timing, queues, drops).
3. Android posts metrics to daemon (`/v1/client-metrics`).
4. Core policy evaluates pressure (`high` / `low`) and may adapt level/restart config according to policy guards.

Relevant policy logic is in:
- `src/host/rust/crates/wbeamd-core/src/domain/policy/*`

Potential side-effect:
- adaptation/restart strategy can improve stability but may create perceived hitches during aggressive tuning.

---

## 7. Trainer pipeline (owned path)

## 7.1 Main entities

- Trainer API lives in `wbeamd-server` under `/v1/trainer/*`.
- UI client: `src/apps/trainer-tauri`.
- Execution engine: `src/domains/training/wizard.py` (trainer_v2 flow).

## 7.2 Trainer API routes

Main routes:
- `POST /v1/trainer/preflight`
- `POST /v1/trainer/start`
- `POST /v1/trainer/stop`
- `GET /v1/trainer/runs`
- `GET /v1/trainer/runs/{run_id}`
- `GET /v1/trainer/runs/{run_id}/tail`
- `GET /v1/trainer/profiles`
- `GET /v1/trainer/profiles/{profile_name}`
- `GET /v1/trainer/datasets`
- `GET /v1/trainer/datasets/{run_id}`
- recompute endpoint under dataset routes
- `GET /v1/trainer/live/status`
- `POST /v1/trainer/live/start`
- `POST /v1/trainer/live/apply`
- `POST /v1/trainer/live/save-profile`

## 7.3 Training execution summary

Trainer start launches:
- `./wbeam train wizard --non-interactive ...`

The wizard:
- probes device and runtime state,
- builds trial search space,
- iterates trials (apply -> warmup -> sample -> score),
- ranks candidates and exports best config/profile artifacts.

Generated artifacts live under:
- `config/training/profiles/<profile_name>/runs/<run_id>/...`
- plus trainer logs in `logs/trainer`.

## 7.4 Training parameters currently important

- encoder mode (currently constrained to single profile path in API normalization),
- selected encoder,
- bitrate min/max bounds,
- generation/population/elite/mutation/crossover,
- warmup/sample durations,
- HUD output options (`chart`, `layout`, `font preset`),
- manual encoder params payload exists, but practical end-to-end influence should be continuously verified per encoder path.

---

## 8. Quality/stability gap candidates (what to audit)

These are the highest-value suspect areas for artifacts and instability:

1. Encoder fallback mismatch
- Requested logical encoder may run on x264/x265 fallback, not NVENC.
- Performance/quality envelope changes sharply.

2. Low-bitrate expectations vs hard runtime clamps
- UI inputs can suggest values that are not truly applied unless aligned with backend floor.

3. Restart-heavy patch model
- Some "live" changes still require restart by design.
- Perceived interruptions may be interpreted as instability.

4. H264 low-latency default profile
- Current H264 software path prioritizes latency over compression efficiency.
- May need parallel "quality mode" parameter set for artifact-sensitive use cases.

5. Dynamic-content robustness
- Static desktop scenes and dynamic/high-motion scenes need separate scoring emphasis.
- single-score optimization may hide artifacts that appear only in burst/scene-change windows.

6. Device/API diversity
- API17/legacy and modern API34+ have different network/decode behavior.
- one-size tuning may underfit one class.

7. Metrics observability completeness
- Ensure every meaningful encoder/decoder/runtime parameter is visible in logs/datasets.
- Missing visibility often causes false conclusions in tuning decisions.

---

## 9. Improvement roadmap (practical)

1. Add explicit "effective runtime config" snapshot after every start/apply
- include selected backend encoder (`x264/x265/nvenc*`), effective GOP, fps, bitrate, queue settings.

2. Split encoder profiles by objective
- `latency-first`
- `quality-first`
- `balanced`
for each codec path (especially H264 software fallback).

3. Introduce dynamic complexity-aware policy
- scale bitrate/fps/size not only by queue pressure but also by scene complexity proxies and sustained decode timing.

4. Build deterministic benchmark scenarios
- static UI text,
- scroll-heavy content,
- video/high-motion patterns,
- mixed workloads.

5. Extend dataset schema for post-run diagnostics
- per-trial effective backend details,
- restart count and reason,
- artifact risk signals,
- "did not apply as requested" flags.

6. Add automatic guardrails
- low Mbps + high resolution/fps combination should trigger warning or guided downscale suggestions.

---

## 10. Key code map for deep audit

Host API and orchestration:
- `src/host/rust/crates/wbeamd-server/src/main.rs`
- `src/host/rust/crates/wbeamd-api/src/lib.rs`

Core lifecycle and adaptation:
- `src/host/rust/crates/wbeamd-core/src/lib.rs`
- `src/host/rust/crates/wbeamd-core/src/domain/policy/*`

Streamer internals:
- `src/host/rust/crates/wbeamd-streamer/src/main.rs`
- `src/host/rust/crates/wbeamd-streamer/src/cli.rs`
- `src/host/rust/crates/wbeamd-streamer/src/pipeline.rs`
- `src/host/rust/crates/wbeamd-streamer/src/encoder.rs`
- `src/host/rust/crates/wbeamd-streamer/src/transport.rs`

Android decode/client telemetry:
- `android/app/src/main/java/com/wbeam/MainActivity.java`
- `android/app/src/main/java/com/wbeam/stream/H264TcpPlayer.java`
- `android/app/src/main/java/com/wbeam/api/HostApiClient.java`
- `android/app/src/main/java/com/wbeam/api/StatusPoller.java`

Trainer:
- `src/domains/training/wizard.py`
- `src/apps/trainer-tauri/src/App.tsx`

---

## 11. Prompt-ready investigation brief (copy/paste)

Use the text below as external review prompt:

```text
You are reviewing WBeam, a Linux host -> Android USB second-screen streaming system.

Goal:
Find technical gaps causing artifacts, instability, and quality loss, and propose concrete fixes ranked by impact.

Scope:
1) End-to-end runtime path:
   - host daemon control plane (start/apply/stop/session/metrics),
   - capture->encode->transport pipeline,
   - Android decode/render/watchdog loop.
2) Training path:
   - trial generation, apply/warmup/sample/score loop,
   - profile export and dataset quality,
   - alignment between UI-configured params and effective runtime params.

Constraints and known facts:
- Wayland portal is current recommended production path.
- Multi-device sessions exist, keyed by adb serial + stream_port.
- Runtime bitrate floor is 4000 kbps.
- Encoder backend may fallback from NVENC to x264/x265 depending on host plugins.
- H264 software path currently uses strict low-latency settings (ultrafast/zerolatency/cabac=0/ref=1/bframes=0).

What to deliver:
1) A prioritized list of top 10 issues (severity, user impact, probability).
2) For each issue:
   - exact suspected root cause,
   - how to reproduce,
   - required instrumentation,
   - recommended fix with tradeoffs.
3) A tuning strategy matrix:
   - per codec (h264/h265/rawpng),
   - per objective (latency/quality/balanced),
   - per hardware class (NVENC vs CPU encode fallback).
4) A verification plan:
   - deterministic test scenes,
   - pass/fail thresholds (fps, drops, decode_p95, render_p95, e2e_p95, visible artifacts),
   - rollback-safe rollout steps.

Code map for review:
- src/host/rust/crates/wbeamd-server/src/main.rs
- src/host/rust/crates/wbeamd-api/src/lib.rs
- src/host/rust/crates/wbeamd-core/src/lib.rs
- src/host/rust/crates/wbeamd-streamer/src/{main,cli,pipeline,encoder,transport}.rs
- android/app/src/main/java/com/wbeam/{MainActivity.java,stream/H264TcpPlayer.java,api/*}
- src/domains/training/wizard.py
- src/apps/trainer-tauri/src/App.tsx

Focus on practical, testable, and incremental fixes.
```

---

## 12. Final note

If the target is "visibly cleaner image with fewer artifacts under dynamic content", the most likely near-term wins are:
- better H264 fallback parameterization (quality profile alongside latency profile),
- tighter runtime/UI parameter alignment and observability,
- deterministic scene-based tuning with objective acceptance thresholds.

