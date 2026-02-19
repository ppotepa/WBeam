# WBeam Master Task Plan

This file is the single source of truth for planning, implementation, and progress.
Every code change must map to a task ID in this file.

## 0) Scope and Product Goal

1. KDE/Wayland host on Ubuntu streams desktop to Android over USB first.
2. UX target is monitor-like feel: low latency, stable frame pacing, no growing lag.
3. MVP performance target:
4. Weak Android: stable `720p60` or `900p60` with bounded drops.
5. Strong Android: `1080p60` best-effort.

## 1) Accepted Bottleneck Hypotheses (from review/logs)

1. Host GStreamer queues were effectively unbounded, causing buffer bloat.
2. Android H264 parse path does heavy memory moves (`System.arraycopy`) in hot loop.
3. Raw AnnexB over TCP forces expensive start-code scanning and weak frame accounting.
4. Decoder input path blocks too long (`dequeueInputBuffer` timeout too high).
5. Metrics/queues are partly NAL-based, not frame/access-unit based.
6. Adaptation with restarts causes reconnect churn and black-screen windows.

## 2) EPIC A - KPI, 16.67 ms Budget, E2E Telemetry (P0)

### A1. KPI and SLO definition

1. Define KPI set: `fps_present`, `frametime_p95`, `e2e_p95`, `decode_p95`, `drops/s`, `queue_depth`.
2. Define hard limits for "green" state:
3. `frametime_p95 <= 20 ms` at 60 FPS target.
4. `decode_p95 <= 8 ms` for 720p/900p profile on weak devices.
5. `e2e_p95 <= 80 ms` USB target (initial), then tighten.

DoD:
1. `docs/perf_targets.md` added.
2. HUD and `/v1/metrics` expose same KPI names and units.

### A2. Frame budget 16.67 ms at 60 FPS

Budget v1 (target, not guarantee):
1. Capture: `2.0 ms`
2. Encode: `4.0 ms`
3. Transport (USB + socket): `1.5 ms`
4. Decode: `6.0 ms`
5. Render/present: `2.0 ms`
6. Margin: `1.17 ms`

DoD:
1. Budget documented in `docs/perf_targets.md`.
2. Each stage has at least one measured metric source.

### A3. E2E telemetry schema

Per-frame timestamps:
1. `capture_ts_us`
2. `encode_ts_us`
3. `send_ts_us`
4. `recv_ts_us`
5. `decode_ts_us`
6. `present_ts_us`
7. `frame_seq`
8. `run_id`

Derived metrics:
1. `capture_to_send_ms`
2. `network_ms`
3. `decode_ms`
4. `present_delay_ms`
5. `e2e_ms`

DoD:
1. Schema in `docs/telemetry_schema.md`.
2. `/v1/metrics` and Android HUD both consume this model.
3. `e2e_p95` is non-zero when framed PTS is enabled.

## 3) EPIC B - Architecture Refactor First (P0)

### B1. API contract freeze (/v1)

Required endpoints:
1. `GET /v1/status`
2. `GET /v1/health`
3. `GET /v1/presets`
4. `GET /v1/metrics`
5. `POST /v1/start`
6. `POST /v1/stop`
7. `POST /v1/apply`
8. `POST /v1/client-metrics`

Response core fields in every response:
1. `state`
2. `active_config`
3. `host_name`
4. `uptime`
5. `run_id`
6. `last_error`

DoD:
1. OpenAPI file exists at `docs/openapi.yaml`.
2. Rust and Android models align with schema.

### B2. Host modularization (Rust)

Layers:
1. `domain`: state machine, config clamp/validation, adaptation policy.
2. `application`: orchestrators (`start/stop/apply/ingest_client_metrics`).
3. `infra`: process manager, pipeline launcher, lock, persistence, logging.
4. `transport`: axum handlers only.

DoD:
1. No ad-hoc restarts outside supervisor.
2. Unit tests for state transitions and adaptation thresholds.

### B3. Android modularization

Modules:
1. `DaemonApiClient`
2. `PreflightController`
3. `StreamSessionManager`
4. `DecoderPipeline`
5. `RenderPipeline`
6. `HudTelemetryPresenter`
7. `SettingsProfileMapper`

DoD:
1. `MainActivity` is orchestration/UI only.
2. Stream, decode, and metrics code moved out of activity.

## 4) EPIC C - P0 Runtime Pipeline Fixes

### C1. Host queue hard limits (GStreamer)

Required defaults for monitor mode:
1. `max-size-buffers=1..2`
2. `max-size-time=20000000..40000000` ns
3. `leaky=downstream`

Apply to:
1. `q1`
2. `qmain`
3. `qdbg` if enabled

DoD:
1. Queue depth remains bounded in 10-minute run.
2. No latency drift during steady desktop motion.

### C2. Android parser rewrite to ring buffer

Tasks:
1. Replace linear shift/memmove with ring buffer (`head/tail`).
2. Incremental parser cursor; no full rescans.
3. Zero allocations in decode hot path.

DoD:
1. No `System.arraycopy` of full residual buffer in steady-state path.
2. Lower CPU spikes and fewer frame drops.

### C3. Stream framing protocol (access-unit packets)

Frame format:
1. `[magic:u32][version:u8][flags:u8][len:u32][pts_us:u64][seq:u32][payload:len]`
2. Payload is complete access unit (frame packet), not arbitrary byte-stream chunk.

Migration:
1. Keep legacy AnnexB mode behind a feature flag.
2. Default to framed mode in debug first, then release.

DoD:
1. Android reads exact packet lengths.
2. Frame-based queues and metrics become accurate.
3. Deterministic reconnect and decoder recovery.

### C4. Decoder input blocking policy

Tasks:
1. Reduce `dequeueInputBuffer` timeout to non-blocking or near-zero (`0..1000 us`).
2. If no input buffer is available, apply drop policy and report backpressure.
3. Never block decode worker long enough to accumulate stale frames.

DoD:
1. No sustained queue growth caused by input wait.
2. `latest-frame-wins` policy observable in metrics.

### C5. Present-path correctness (black screen guard)

Tasks:
1. Ensure only renderable output buffers are promoted to latest frame.
2. Separate codec config buffers from render queue logic.
3. Add "no-present watchdog": decode progressing but no present for X sec -> controlled reset.

DoD:
1. `fps_present` does not stay 0 while decode queue is active.
2. Black-screen regressions closed with explicit error reason.

## 5) EPIC D - Backpressure Android -> Host (D4/G1, P0)

### D1. Client metrics channel

Endpoint:
1. `POST /v1/client-metrics`

Payload:
1. `run_id`
2. `queue_transport`
3. `queue_decode`
4. `queue_render`
5. `decode_p95_ms`
6. `render_p95_ms`
7. `drops_delta`
8. `fps_present`
9. `reason`

Sampling:
1. Emit every `1000 ms` in debug and release.

DoD:
1. Host receives/records metrics once per second.
2. HUD values and host logs are time-correlated by `run_id`.

### D2. Adaptation state machine (host-side)

Levels:
1. `L0` baseline
2. `L1` mild degrade
3. `L2` medium degrade
4. `L3` emergency degrade

Actions by severity:
1. First: bitrate down.
2. Then: fps cap down.
3. Last: resolution step down (deferred or explicit apply).
4. Upgrade quality only after cooldown/hysteresis.

Rules:
1. No pipeline restart for normal adaptation.
2. Restart budget guard per time window.
3. Cooldown and hysteresis mandatory to avoid oscillation.

DoD:
1. No adaptation-induced restart loops.
2. Stable behavior under fluctuating load.

## 6) EPIC E - Android Queue Limits + Latest Frame Wins (E2/F1, P0)

Hard limits:
1. Transport queue: max `3`
2. Decode queue: max `2`
3. Render queue: max `1`

Policies:
1. Bounded queues only.
2. Drop-late over delay-growth.
3. Latest-frame-wins at render boundary.

DoD:
1. Queue depth never grows unbounded.
2. No buffer-bloat pattern over 30-minute test.

## 7) EPIC F - UX and Startup Autodetection

### F1. Preflight on app start

Checks:
1. USB/control link reachable (`/v1/health`).
2. Host daemon reachable and valid state.
3. Surface ready.
4. Hardware AVC decode capability.
5. Stream socket readiness.

Behavior:
1. Show nerd-style busy preflight panel.
2. Show explicit failed check reason.
3. Auto-start flow when checks pass (`connected -> apply -> start -> observe`).

### F2. UI controls (replace sliders with switches/buttons)

1. Mode:
2. `A: Ultra Low Latency`
3. `B: Smooth Balanced`
4. Quality:
5. `Low`, `Medium`, `High`
6. Advanced debug hidden by default.
7. Green `Apply & Restart` button for explicit changes.

DoD:
1. Two-click basic setup.
2. Settings actually map to host preset and are visible in `/v1/status`.

## 8) Test Matrix and Release Gates

### Functional

1. USB attach/detach with auto recovery.
2. Host daemon restart while app runs.
3. ADB reverse break/recover.
4. Portal permission revoke/regrant.

### Performance

1. `720p60` weak device: stable session 30 min.
2. `900p60` target default profile.
3. `1080p60` best-effort stronger device.

### Release gate (must pass)

1. No restart loops.
2. No persistent black screen with active decode.
3. Bounded queues verified by telemetry.
4. Metrics P50/P95/P99 coherent and non-placeholder.

## 9) Strict Execution Order

1. A1, A2, A3
2. C1, C2, C4
3. C3
4. D1, D2
5. C5, E
6. F
7. P1 optimizations
8. P2 enhancements

## 10) P1 Optimization Pack (after P0 stability)

### P1.1 Thread and socket tuning

1. Decode/render thread priority boost for critical path.
2. Calibrated socket receive buffers for USB mode (small and bounded).
3. Validate no extra jitter introduced by buffering.

### P1.2 MediaCodec mode evaluation

1. Compare polling vs async callback mode under same load.
2. Keep the variant with lower jank and lower CPU on weak devices.

### P1.3 Accurate percentiles

1. Replace pseudo percentile values with rolling histogram/t-digest.
2. Expose `p50/p95/p99` for decode/render/e2e and queue depth.

## 11) P2 Enhancements

1. Optional Wi-Fi path (UDP + bounded jitter buffer + loss strategy).
2. Extended observability (trace id across host and Android).
3. Long-run telemetry export for regression checks.

## 12) Open Product Decisions

1. Release candidate scope:
2. USB-only mandatory?
3. Latency-first (drop frames allowed) or smoothness-first (higher latency)?
4. Is Wi-Fi deferred fully to post-RC?

## 13) Worklog Format (mandatory update after each change)

Template:
1. `YYYY-MM-DD HH:MM | component | task-id | files | summary | verification | next`

Example:
1. `2026-02-19 10:05 | host | C1 | host/scripts/stream_wayland_portal_h264.py | set q1/qmain leaky queue limits | 10 min run no latency drift | start C2`

Entries:
1. `2026-02-19 17:48 | repo | OPS-IGN | .gitignore, task.md | add project-wide ignore rules for Android/Rust/Python logs and build artifacts | git status shows .gitignore tracked and noisy artifacts ignored going forward | continue with A1/B2 execution`

## 14) Worklog

1. `2026-02-19 22:00 | docs | A1,A2 | docs/perf_targets.md | KPI table, 16.67ms budget, queue limits, release gate | n/a (doc) | A3`
2. `2026-02-19 22:00 | docs | A3 | docs/telemetry_schema.md | per-frame timestamp schema, /v1/metrics shape, C3 framing header | n/a (doc) | C3`
3. `2026-02-19 22:00 | host | C1 | host/scripts/stream_wayland_portal_h264.py | q1+qmain: max-size-buffers=2, max-size-time=40ms, leaky=downstream; qdbg: max-size-buffers=1, max-size-time=200ms | pipeline smoke test BUILD OK | C3`
4. `2026-02-19 22:00 | android | C4 | android/.../MainActivity.java | dequeueInputBuffer 10ms→1ms; drops NAL instead of blocking 10ms | BUILD SUCCESSFUL | C2`
5. `2026-02-19 22:00 | android | C2 | android/.../MainActivity.java | ring-buffer parser (sHead/sTail): eliminates 6 per-NAL System.arraycopy; buffer 4MB→512KB; compact only on overflow | BUILD SUCCESSFUL 9s | C3, E`
6. `2026-02-19 23:30 | host | C3 | host/scripts/stream_wayland_portal_h264.py | framed_tcp_server_thread: appsink+24-byte header per AU, --framed CLI/env flag, alignment=au | Python syntax OK | C3 Android`
7. `2026-02-19 23:30 | android | C3 | android/.../MainActivity.java | framedDecodeLoop: reads 24-byte magic header, exact per-frame PTS; magic auto-detect in runLoop; PushbackInputStream peek fallback | BUILD SUCCESSFUL | C5`
8. `2026-02-19 23:30 | android | C5 | android/.../MainActivity.java | black-screen watchdog in framedDecodeLoop + legacy decodeLoop: 5s/300-frame threshold, throws IOException to trigger reconnect | BUILD SUCCESSFUL | commit`
9. `2026-02-19 23:30 | host | C3 | host/rust/crates/wbeamd-core/src/lib.rs | pass --framed to Python when WBEAM_FRAMED=1 | cargo check Finished | commit`
10. `2026-02-19 23:55 | android | E | android/.../MainActivity.java | EPIC E: formalize decode queue bounds – add pendingDecodeQueue counter to framedDecodeLoop; gate both LP and AnnexB queueNal calls on pendingDecodeQueue >= DECODE_QUEUE_MAX_FRAMES (2) with explicit drop; gate framed queueNal same; drainLatestFrame decrements counter; render queue already latest-frame-wins via drainLatestFrame | BUILD SUCCESSFUL in 1s | F`
11. `2026-02-19 23:59 | android | F1,F2 | android/.../MainActivity.java + activity_main.xml | F1: pre-flight overlay already complete (usb_link/host_api/surface/hw_decode_avc/stream_ready checks + /v1/health poll + auto-start flow); F2: replaced Profile spinner with ULTRA LOW LATENCY / SMOOTH BALANCED mode buttons (lower profileSpinner hidden, driven programmatically); added LOW/MED/HIGH quality buttons (set res%+bitrate); Advanced panel toggle hides sliders by default (two-click setup); buttons highlight on selection (blue-600); pref save/restore for mode+quality | BUILD SUCCESSFUL in 1s | release-gate check`
12. `2026-02-20 00:10 | android | P1.1,P1.3 | android/.../MainActivity.java | P1.1: decode thread THREAD_PRIORITY_URGENT_AUDIO; socket recv buf 64KB (USB-bounded); SO_TIMEOUT 5s; P1.3: replace pseudo-p95 (decodeNsMax=Math.max) with 128-slot circular buffer in both decodeLoop and framedDecodeLoop; true percentile via Arrays.sort copy (1-2us/sec, O(n log n), called once per stats window); percentilesMs() helper; P1.2 deferred: requires live device measurement for async vs sync MediaCodec comparison | BUILD SUCCESSFUL in 1s | P1.2 live eval`
13. `$(date -u +%Y-%m-%d\ %H:%M) | docs | B1 | docs/openapi.yaml | OpenAPI 3.1.0 spec for all 8 /v1/ endpoints; schemas match wbeamd-api Rust structs exactly; BaseResponse flattened via allOf; ClientMetricsRequest 16 fields; MetricsSnapshot with KpiSnapshot/FrameBudgetMs/QueueLimits; ConfigPatch for /start+/apply | validated yaml.safe_load – 8 paths 15 schemas | B2 unit tests`
14. `2026-02-20 00:30 | android+host+docs | P2.2 | MainActivity.java wbeamd-api/src/lib.rs wbeamd-core/src/lib.rs docs/openapi.yaml | trace_id=(sessionConnectId<<32|sampleSeq) added to ClientMetricsSample.toJson(); trace_id: Option<u64> on Rust ClientMetricsRequest (#[serde(skip_serializing_if=Option::is_none)]); info! log in ingest_client_metrics with hex trace_id+present_fps+decode_p95+e2e_p95; openapi.yaml ClientMetricsRequest schema updated; sessionConnectId++ on each TCP connect, sampleSeq reset | Android BUILD SUCCESSFUL in 1s; cargo check Finished in 0.73s | P2.3 telemetry export`
15. `2026-02-20 00:45 | host | P2.3 | host/rust/crates/wbeamd-core/src/lib.rs | telemetry_dir()=~/.local/share/wbeam; open_telemetry_file(run_id)->Option<File>; telemetry_file field on Inner (None init); opened in start() after run_id++;written in ingest_client_metrics (JSONL: all ClientMetricsRequest fields + run_id); dropped in stop() and handle_child_exit; borrow-fix: telemetry_run_id captured before mut borrow | cargo check Finished in 0.64s | B2 unit tests`
16. `2026-02-20 01:00 | host | B2 | host/rust/crates/wbeamd-core/src/lib.rs | #[cfg(test)] module: 17 unit tests covering is_high_pressure (3), is_low_pressure (4), adaptation state machine (8: degrade-after-streak, single-no-degrade, cooldown-blocks-rapid, cooldown-cleared-allows, clamp-at-max, recover-8xlow, clamp-at-zero, hold-warmup), telemetry_dir (1); uses streaming_core_ready() helper to set STATE_STREAMING + current_pid + stream_started_at-10s + no-cooldown; backdating last_adaptation_at for cooldown control | cargo test 17 passed 0 failed in 5.04s | B3 Android modularization`
17. `2026-02-19 18:00 | repo | OPS-IGN-LOGS | .gitignore, task.md, git index | removed tracked logs from git index with git rm --cached while keeping files on disk; kept host/rust/logs/.gitkeep tracked | git ls-files no longer contains runtime logs | proceed with A1/B2 tasks`
18. `2026-02-19 18:08 | android | C5,E | android/app/src/main/java/com/wbeam/MainActivity.java | fix black-screen deadlock in decode path: DECODE_QUEUE_MAX_FRAMES 4->2 (aligned with host), stale pendingDecodeQueue recovery after 300ms no decode progress, allow SPS/PPS/IDR pass-through under queue pressure, queueNal timeout 10ms->1ms with explicit arg, recovery NAL helpers for legacy/framed | ./gradlew -q :app:assembleDebug succeeded | capture fresh HUD log and verify fps_present>0`
19. `2026-02-19 18:08 | host | C3 | host/rust/crates/wbeamd-core/src/lib.rs | make framed transport default (WBEAM_FRAMED=0 forces legacy) to avoid legacy AnnexB parser stalls and improve deterministic decode path | cargo check -q succeeded | restart daemon and confirm Android logs show [decode/framed]`
20. `2026-02-19 18:21 | android+host | B2,C3 | android/app/src/main/java/com/wbeam/MainActivity.java; host/rust/crates/wbeamd-core/src/lib.rs; host/scripts/run_wayland_mvp.sh | framed-only boundary: remove runtime fallback to legacy parser in runLoop, enforce framed reconnect messaging, daemon now always passes --framed, manual Wayland MVP script also forces --framed to prevent accidental legacy path | ./gradlew -q :app:assembleDebug + cargo check -q succeeded | validate 30min USB stability and fps_present floor`
21. `2026-02-19 18:26 | android | C5 | android/app/src/main/java/com/wbeam/MainActivity.java | add no-present recovery ladder for framed decode path: L1 codec.flush at 1.5s/12 frames, L2 reconnect at 3s/24 frames, L3 hard reconnect at 5s/30 frames; reset flush flag on rendered frame; status/log messages for each ladder level | ./gradlew -q :app:assembleDebug succeeded | run USB soak and verify no sustained fps_present=0`
22. `2026-02-19 18:26 | host+qa | C5,J2 | host/rust/crates/wbeamd-core/src/lib.rs; host/scripts/soak_usb_30m.sh | host self-heal: restart stream when recv_fps high but present_fps~0 for 4 consecutive samples with 15s cooldown; reset no-present counters on start/stop/streaming signal; add 30-min USB soak gate script polling /v1/metrics and failing on prolonged no-present or missing metrics | cargo check -q + bash -n soak script succeeded | execute full 1800s soak on device`
23. `2026-02-19 18:43 | qa | J2,OPS | host/scripts/run_soak_local.sh; task.md | add local wrapper for soak tests without systemd: starts daemon via run_wbeamd.sh only if /v1/health is unavailable, waits for readiness, runs soak_usb_30m.sh, and cleans daemon on exit | chmod +x + bash -n passed | run full 1800s local soak`
24. `2026-02-19 19:12 | qa | J2,OPS | host/scripts/soak_usb_30m.sh; host/scripts/run_soak_local.sh | fix locale-sensitive numeric formatting in soak logs (LC_ALL=C + string printf), add min_streaming_samples gate, add auto-start POST /v1/start and strict stream-ready timeout in local wrapper (REQUIRE_STREAM_READY=1 default) | bash -n both scripts passed; short smoke run shows no printf invalid number and state samples logged | rerun 30m soak`
25. `2026-02-19 19:43 | qa | J2,OPS | host/scripts/soak_usb_30m.sh | improve operator UX when daemon is down: at 3s metrics-missing print explicit hint to use run_soak_local.sh with current args; keep hard fail on max missing streak | bash -n soak script passed | rerun soak via wrapper`
26. `2026-02-19 19:47 | qa | J2 | host/scripts/soak_usb_30m.sh | extend soak gate beyond black-screen detection: add warmup-aware low-FPS and low-recv streak failure conditions (configurable env), include streak(np/lf/lr) in live logs to surface degraded-but-not-black sessions | bash -n soak + wrapper passed | rerun 30m soak with default thresholds`
27. `2026-02-19 19:50 | qa+host | J2,C5 | host/scripts/run_soak_local.sh | make soak runner proactive: optional pre-apply of lowlatency profile (SOAK_PROFILE, default lowlatency) and enable live adaptive restart for local daemon in soak mode (SOAK_ENABLE_LIVE_ADAPTIVE_RESTART=1) before start; keeps start/ready flow automatic | bash -n run_soak_local.sh passed | rerun soak and compare recv/present floors`
28. `2026-02-19 19:53 | qa+host | J2,C5 | host/scripts/run_soak_local.sh; host/scripts/soak_usb_30m.sh | tune soak stability flow from observed logs: disable live adaptive restart by default in soak runner (SOAK_ENABLE_LIVE_ADAPTIVE_RESTART=0) to avoid portal-blocked restart loops; add STARTING streak detector/fail gate (MAX_STARTING_SEC, default 60) and include starting streak in live output | bash -n both scripts passed | rerun soak with default thresholds`
29. `2026-02-19 19:57 | host+qa | C1,J2 | host/rust/crates/wbeamd-api/src/lib.rs; host/scripts/stream_wayland_portal_h264.py; host/scripts/run_soak_local.sh; host/scripts/soak_usb_30m.sh | quality/perf rebalance after soak evidence: lowlatency preset moved to 1280x720@60/16000kbps in API, NVENC lowlatency default tuned to p2+16000kbps, soak runner supports size/fps/bitrate overrides via SOAK_SIZE/SOAK_FPS/SOAK_BITRATE_KBPS, soak logs now print active cfg each sample | cargo check + py_compile + bash -n all passed; short soak smoke shows cfg=1280x720@60fps/16000k in output | run full 30m soak and compare artifacts/drops`
30. `2026-02-19 20:00 | qa | J2 | host/scripts/soak_usb_30m.sh | avoid false failures on static desktop scenes: low-FPS/low-recv gates now only count when scene is active (bitrate_actual_bps >= ACTIVE_SCENE_MIN_BPS, default 1.5 Mbps); log now includes bps and active flag | bash -n soak script passed | rerun soak and inspect active=1 streak behavior`
31. `2026-02-19 20:12 | host+android | A1,F2,J2 | host/rust/crates/wbeamd-server/src/main.rs; host/rust/crates/wbeamd-server/Cargo.toml; android/app/src/main/java/com/wbeam/MainActivity.java; android/app/src/main/res/layout/activity_main.xml | add cable bandwidth speedtest feature: new host endpoints GET /speedtest and /v1/speedtest streaming pseudorandom payload (mb query, default 64), Android "Bandwidth Test" action on quick/debug buttons runs download benchmark over ADB tunnel and displays Mbps/MiB/s in status/hud; long-press keeps legacy public video test | cargo check + assembleDebug passed; grep confirms route and UI wiring | run on-device bandwidth validation`
32. `2026-02-19 20:58 | repo+docs | OPS-NEXT | .gitignore; README.md; next.md; task.md | update markdown ignore policy to keep only README unignored, rewrite README to 100-word casual project summary, add 600-word handoff file next.md for next agent with architecture/status/ordered plan | manual review + wc check for README/next word targets | continue with protocol workspace scaffolding (wbtp-core/sender/receiver-null)`
