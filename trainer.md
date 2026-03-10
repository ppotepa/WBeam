# Trainer Domain And App Specification (WBeam)

Last updated: 2026-03-10  
Status: Draft for implementation (authoritative planning document)

## 1. Goal

`trainer` is a dedicated training product surface for WBeam that:
- benchmarks host+Android streaming path,
- optimizes stream configuration automatically,
- persists reproducible profiles with full metadata,
- exposes live tuning telemetry with full HUD and graphs,
- enables profile browsing/comparison and reuse across devices.

This document consolidates all decisions made so far and defines the target architecture and behavior.

## 2. Scope

### In scope
- New dedicated GUI app called `trainer`.
- Launch entrypoint script: `./trainer.sh`.
- Requires host daemon service (`wbeamd-server`) to be running.
- Full visual training HUD (parameters + live charts + scoring breakdown).
- Multi-codec training support: `h264`, `h265`, `mjpeg` (and optional `rawpng` for diagnostics).
- Persistent profile artifacts per profile name directory.
- Full run metadata persistence (`parameters.json` + optional telemetry/history files).

### Out of scope (for now)
- Replacing existing `./wbeam train wizard` immediately.
- Cloud training orchestration.
- Cross-host distributed training.
- Automatic profile publishing to external registry.

## 3. Current State (already done)

### Domain reorganization
- Training logic moved to main lane domain:
  - `src/domains/training/wizard.py`
  - `src/domains/training/legacy_engine.py`
  - `src/domains/training/train_max_quality.sh`
- Compatibility wrappers kept under `proto/`:
  - `proto/autotune.py`
  - `proto/train-autotune-max-quality.sh`

### Profile canonicalization
- Main profile file path established:
  - `config/training/profiles.json`

### Wizard/engine fixes
- Wizard now executes realistic training flow (no forced `--host-only` in proto flow).
- Legacy engine sanitizes inherited environment when invoking `proto/run.sh`.
- This prevents `blocked runtime env overrides` errors.

These are foundations; the new Trainer app builds on top of them.

## 4. Product Concept

Trainer is a first-class operational app focused on one domain only: tuning and profile lifecycle.

High-level UX:
1. User selects device and training intent.
2. Trainer runs preflight diagnostics (ADB + stream baseline).
3. Trainer executes staged optimization with full live HUD.
4. Trainer saves profile and full run metadata to deterministic filesystem layout.
5. User reviews/compares profiles and can apply/export them.

## 5. Runtime Dependency Model

Trainer requires daemon availability.

### Hard requirement
- Control API reachable: `http://127.0.0.1:5001/v1/health`.

### Behavior when daemon is down
- Trainer opens blocking “service not running” state.
- Allowed actions:
  - `Start Service`
  - `Retry`
  - `Open logs`
- No training execution allowed until daemon health is `ok`.

## 6. Trainer App IA (Information Architecture)

Recommended app tabs:

1. `Train`
- New training session form.
- Device/codec/goal/budget selection.

2. `Live HUD`
- Real-time training dashboard.
- Full metrics + charts + trial status.

3. `Profiles`
- Browse saved profile directories.
- Search/filter by codec/goal/device/date/score.

4. `Runs`
- Historical run list with status and score.
- Open run details and artifacts.

5. `Compare`
- Compare 2-3 profiles side-by-side.
- Show tradeoffs (fps/latency/drop/bitrate).

6. `Devices`
- Connected ADB devices.
- Capability probe (API, decoder support, transport class).

## 7. Training Input Contract

Each run must have explicit input:
- `serial` (ADB target)
- `profile_name` (user-provided; used as artifact folder id)
- `goal_mode`:
  - `max_quality`
  - `balanced`
  - `low_latency`
- `codec_set` (subset of `h264`, `h265`, `mjpeg`, optionally `rawpng`)
- `target_fps`
- `resolution_mode`:
  - `native`
  - `custom` + explicit size
- `trial_budget`:
  - max trials and/or max runtime
- optional expert overrides:
  - warmup sec, sample sec, mutation rate, elite count, gates/weights preset

## 8. Preflight Diagnostics (mandatory)

Before generation 1 starts, run preflight and print/report results.

### 8.1 ADB link benchmark
- Push throughput test to `/data/local/tmp` (MB/s).
- Optional pull throughput test.
- ADB shell RTT benchmark (`adb shell true` loop) for command latency:
  - p50
  - p95

### 8.2 Stream baseline benchmark
- Start baseline stream for 60-90 seconds.
- Gather:
  - present/recv/decode fps (mean, p50, p10)
  - drop/late/timeout stats
  - e2e latency p95
  - decode/render p95

### 8.3 Bound derivation
- Compute initial bitrate ceiling from measured throughput (safe fraction).
- If baseline unstable:
  - reduce fps/resolution before full tuning.

### 8.4 Preflight output
- Console + HUD + persisted JSON summary.
- Must classify link quality tier: poor/ok/good/excellent.

## 9. Optimization Pipeline (staged)

### Stage A: Stability floor
- Objective: find stable region for selected device/codec/fps/resolution.
- Aggressive reject of unstable configs using hard gates.

### Stage B: Bitrate ceiling search
- Objective: discover highest stable bitrate.
- Strategy:
  - coarse ramp up,
  - binary search near failure boundary,
  - identify `max_stable_kbps`.

### Stage C: Fine tuning
- Objective: optimize quality/latency/smoothness around stable bitrate.
- Tune parameters such as:
  - encoder-specific knobs,
  - GOP/key-int,
  - reorder,
  - queue/pull/keepalive,
  - appsink buffers,
  - duplicate/drop policy.

### Finalization
- Select winner + top alternatives using mode-aware score.
- Apply safety headroom for production profile:
  - e.g. `bitrate_kbps = floor(max_stable_kbps * 0.92)`.

## 10. Scoring Model

Use two-layer evaluation:

### 10.1 Hard gates (must pass)
Example gate set:
- `present_fps_p10 >= 0.85 * target_fps`
- `timeout_mean <= max_timeout_threshold`
- `drop_ratio_p50 <= max_drop_threshold`
- `sample_count >= min_samples`

Failure => strong negative score / rejected trial.

### 10.2 Weighted objective
Score components (weights vary by `goal_mode`):
- Stability:
  - fps consistency (p10/p50), jitter penalties
- Quality:
  - bitrate utilization and frame delivery quality
- Latency:
  - e2e/decode/render p95
- Efficiency:
  - queue pressure, timeout trend, backpressure indicators

### 10.3 Mode profiles
- `max_quality`: quality high weight, latency medium, stability hard-gated.
- `balanced`: balanced weights across all axes.
- `low_latency`: latency + stability dominate, quality secondary.

### 10.4 Rank output
Persist:
- winner,
- top-3 alternatives,
- reason codes for rejected candidates.

## 11. Full HUD Requirements (mandatory)

HUD must be visible during training on streamed output and in Trainer UI.

Refresh target: at least every 1s.

### 11.1 HUD content blocks
A. Run context
- profile name, goal mode, serial, target fps/size
- generation/trial index, total trials, elapsed time, ETA

B. Active config
- codec, bitrate, size, fps
- key transport/queue params
- encoder special params (GOP/reorder/etc.)

C. Live metrics
- sender fps (raw/effective)
- pipe fps
- present/recv/decode fps
- timeout/drop/late live counters and rates
- latency p95 values

D. Score breakdown
- total score
- component scores
- penalties
- gate pass/fail matrix

E. Optimization state
- best-so-far score/config
- current rank estimate
- failure/reject reason (if applicable)

### 11.2 HUD charts
Must include line charts for:
- fps timeline (present/recv/decode/sender)
- latency p95 timeline
- drop/timeout timeline
- bitrate utilization timeline
- queue depth timeline

If overlay capacity is limited, rotate pages every ~2s (never omit critical state).

## 12. Profile Artifact Contract

Every training run for profile `<profile_name>` writes to:
- `config/training/profiles/<profile_name>/<profile_name>.json`
- `config/training/profiles/<profile_name>/parameters.json`

Recommended additional artifacts:
- `config/training/profiles/<profile_name>/preflight.json`
- `config/training/profiles/<profile_name>/results.json`
- `config/training/profiles/<profile_name>/hud-timeseries.json`
- `config/training/profiles/<profile_name>/top-candidates.json`

If directory exists and overwrite not requested:
- append timestamp suffix to run folder to avoid silent clobber.

## 13. `parameters.json` schema (required fields)

Must include:
- run metadata:
  - run_id, started_at, finished_at, duration
  - trainer app version, host build_revision
- device metadata:
  - serial, model, API level, Android release
- user inputs:
  - profile_name, goal_mode, codec_set, target_fps, resolution_mode, trial_budget
- search space:
  - codec/fps/size/bitrate candidates
  - tunable param ranges
- scoring setup:
  - gate thresholds
  - weight vector used
- preflight metrics:
  - adb throughput/latency
  - baseline stream diagnostics
- final decision:
  - selected winner config + score
  - top alternatives + scores
  - reject reasons summary

## 14. API Surface For Trainer (proposed)

Existing endpoints continue to work; add trainer-specific API:

- `POST /v1/trainer/preflight`
- `POST /v1/trainer/start`
- `POST /v1/trainer/stop`
- `GET /v1/trainer/runs`
- `GET /v1/trainer/runs/{run_id}`
- `GET /v1/trainer/profiles`
- `GET /v1/trainer/profiles/{profile_name}`
- `WS /v1/trainer/stream/{run_id}` (live HUD/telemetry events)

Event stream should carry:
- run state transitions,
- trial begin/end,
- live metrics snapshots,
- score updates,
- gate results,
- warnings/errors.

## 15. `trainer.sh` launcher contract

`./trainer.sh` should:
1. verify runtime prerequisites,
2. check daemon health,
3. optionally start daemon (`--start-service`),
4. launch trainer GUI app,
5. write log file under `logs/trainer/`.

Suggested options:
- `--start-service`
- `--control-port <port>`
- `--verbose`
- `--headless` (future)

## 16. Codec Coverage

Trainer must support tuning for:
- `h264`
- `h265`
- `mjpeg`
- optional diagnostics path: `rawpng`

Per-device capability probing is required; unsupported codec combinations must be blocked in UI before run start.

## 17. Failure Handling

Trainer must fail fast with explicit reason when:
- daemon unreachable,
- no ADB device,
- device offline/unauthorized,
- stream cannot start,
- portal/capture consent unavailable,
- insufficient metrics sample.

All failures must be persisted in run artifacts with reason codes.

## 18. Compatibility / Migration

- Keep `./wbeam train wizard` working.
- Keep proto wrapper commands operational.
- New Trainer app can initially call existing domain scripts, then migrate to dedicated API-driven backend.

## 19. Security / Safety

- Do not hardcode usernames, host IPs, or personal paths.
- Sanitize runtime env passed to subprocesses invoking proto runner.
- Validate profile name to prevent path traversal.
- Use deterministic artifact paths and explicit overwrite rules.

## 20. Implementation Plan (phased)

### Phase 1: Foundation
- Add profile directory artifact model.
- Add `parameters.json` persistence.
- Add preflight benchmarks in domain layer.
- Expand scoring to gate + weighted mode model.

### Phase 2: HUD+Telemetry
- Extend live HUD payload and chart telemetry stream.
- Persist hud-timeseries per run.

### Phase 3: Trainer GUI
- Scaffold new app `src/apps/trainer-tauri`.
- Implement tabs: Train / Live HUD / Profiles / Runs / Compare / Devices.
- Wire to trainer API and artifact browser.

### Phase 4: Production hardening
- retry and resume semantics,
- better ETA and confidence scoring,
- richer profile compare/explainability.

## 21. Acceptance Criteria

A run is accepted when:
- full preflight executed and stored,
- full HUD visible and updating during training,
- profile artifacts saved under profile directory,
- `parameters.json` present and complete,
- winner + alternatives produced with explainable scores,
- flow works with required codecs and device capability guards.

## 22. Open Questions

1. Should `mjpeg` be enabled by default in search space or opt-in only?
2. Should profile namespace include device class tags automatically?
3. Should safety factor for `max_stable_kbps` be global or mode/device-specific?
4. How long should baseline warmup be by default (60s vs 90s)?
5. Do we need automatic re-validation of old profiles on app/host version change?

## 23. Final Direction

Trainer becomes a dedicated operational surface for profile intelligence, not just a helper script.

Target outcomes:
- repeatable profile generation,
- transparent optimization (observable HUD and telemetry),
- robust artifacts for audit/replay,
- better quality ceilings with controlled stability.

This document is the canonical blueprint for implementing the Trainer app and upgrading the training pipeline.
