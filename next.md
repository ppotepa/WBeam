# NEXT AGENT HANDOFF - WBeam Project State

## 1) Why this file exists
This is a full handoff after resetting the desktop app to a blank starter.
The next agent should use this as the single source of truth before continuing implementation.

Current intent:
- Keep main product lane in `src/` + `android/`.
- Keep `proto/` as a tuning and experimentation sandbox.
- Rebuild desktop UX from scratch with a clean architecture.

## 2) Current state at a glance
- Desktop app (`src/apps/desktop-egui`) was intentionally scraped and replaced with a blank skeleton.
- Launcher `desktop.sh` still points to `src/apps/desktop-egui/Cargo.toml`.
- Android + host + proto lanes remain present.
- Profiles and autotune data exist and are large (historical runs stored in repo).

## 3) High-level architecture

### Main lane (product path)
- Android client: `android/` (`com.wbeam`)
- Host runtime: `src/host/rust/` (workspace)
- Host fallback daemon: `src/host/daemon/wbeamd.py`
- Protocol crates: `src/protocol/rust/`
- Entry scripts: `./devtool` (single), `./runas-remote` (remote launcher)

### Proto lane (sandbox / tuning lane)
- End-to-end runner: `proto/run.py` + `proto/run.sh`
- Prototype host: `proto/host`
- Legacy/experimental Android app: `proto/front`
- Tuning and profile tooling: `proto/autotune.py`, `proto/apply_profile.py`, `proto/regression_smoke.py`

## 4) Repository structure (important folders)

```text
.
|- android/                         # main Android app (com.wbeam)
|- src/
|  |- apps/
|  |  |- desktop-egui/             # desktop control app (now blank starter)
|  |- assets/                      # shared icons/assets (wbeam.png)
|  |- compat/                      # API policy packs / resolver rules
|  |  |- api17/
|  |  |- api21/
|  |  |- api29/
|  |  \- resolver_rules.json
|  |- host/
|  |  |- daemon/wbeamd.py          # python fallback
|  |  \- rust/                     # rust host workspace
|  |     |- crates/wbeamd-api
|  |     |- crates/wbeamd-core
|  |     |- crates/wbeamd-server
|  |     \- crates/wbeamd-streamer
|  \- protocol/
|     \- rust/
|        |- crates/wbtp-core
|        |- crates/wbtp-host
|        |- crates/wbtp-sender
|        \- crates/wbtp-receiver-null
|- proto/                           # tuning sandbox (kept separate)
|  |- config/
|  |- host/
|  |- front/
|  |- run.py / run.sh
|  |- autotune.py
|  |- apply_profile.py
|  |- autotune-results*.json
|  \- autotune-history.json
|- wbeam                            # main CLI orchestrator
|- deploy-android-all.sh            # wrapper over wbeam android deploy-all
\- desktop.sh                       # desktop launcher
```

## 5) Desktop app reset details

### What was done now
`src/apps/desktop-egui` was reduced to a blank app:
- Removed old modules (`app.rs`, `services.rs`, `models.rs`, old forms/platform modules).
- Kept one file: `src/apps/desktop-egui/src/main.rs`.
- `Cargo.toml` dependencies reduced to only `eframe`.
- The app now shows:
  - Top status bar
  - 3 empty panels:
    1. Devices
    2. Device Options
    3. Session
- This is a scaffold for the new architecture, not a functional manager.

### Launcher behavior
`desktop.sh` launches this crate explicitly:
- `--manifest-path src/apps/desktop-egui/Cargo.toml`
- `--package wbeam-desktop-egui`
- `--bin wbeam-desktop-egui`
- uses dedicated target-dir `.build/desktop-egui`

## 6) Android app (main lane) notes

Important files:
- `android/app/build.gradle`
- `android/app/src/main/java/com/wbeam/...`
- Resolver/policy classes:
  - `compat/Api17CompatPolicy.java`
  - `compat/Api21CompatPolicy.java`
  - `compat/Api29CompatPolicy.java`
  - `resolver/ApiLevelResolver.java`
  - `resolver/ClientHelloBuilder.java`

Build config facts:
- `compileSdk = 35`, `targetSdk = 35`
- `minSdk` is configurable via `WBEAM_MIN_SDK` (default in gradle file: `17`)
- So yes: one APK can target multiple API levels via runtime policy/resolver behavior.

Recent compile issue history:
- Previous Java errors about uncaught `JSONException` were addressed by wrapping `JSONObject` writes in `try/catch`.
- In this environment, `gradlew` currently fails due host networking quirk:
  - `Could not determine a usable wildcard IP for this machine` (environment-specific).

## 7) Host/protocol notes

### Main host path
`src/host/rust` workspace:
- `wbeamd-server` (control plane/API)
- `wbeamd-streamer` (capture/encode/transport)
- shared crates in `wbeamd-core`, `wbeamd-api`

### Protocol path
`src/protocol/rust` contains WBTP-related crates:
- sender, host, core, null receiver

### Proto host path
`proto/host/src/main.rs` currently contains a very large single-file implementation with many constants and runtime branches.
There is also a newer architecture scaffold in:
- `proto/host/src/application/*`
- `proto/host/src/domain/*`
- `proto/host/src/infrastructure/*`

This indicates partial migration: architecture folders exist, but runtime is still largely monolithic in `main.rs`.

## 8) Profiles and autotune system

### Canonical config files
- `proto/config/proto.json` (canonical runtime config)
- `proto/config/proto.conf` (derived/synced legacy conf)
- `proto/config/profiles.json` (versioned profiles)
- `proto/config/autotune-best.json` (best snapshot)

### Profile application
- `proto/apply_profile.py <profile>` merges sections:
  - `values`
  - `quality`
  - `latency`
- Writes merged output to `proto/config/proto.json` and regenerated `proto.conf`.

### Autotune data
Main artifacts in `proto/`:
- `autotune-history.json` (aggregate history)
- `autotune-results-fps60-*.json` (timestamped run outputs)
- `autotune-results.json` (latest/general)

Observed profile set currently includes (examples):
- `safe_60`
- `aggressive_60`
- `quality_60`
- `debug_60`
- auto-generated: `fast60`, `balanced60`, `quality60`, `fast60_2`, `fast60_3`

## 9) Operational problems repeatedly observed

### Streaming quality/runtime problems
- FPS instability (good at start, then significant drop).
- Freeze symptom: cursor keeps moving but background frame/video freezes.
- Artifacts and temporary quality dips under load.
- Browser-specific behavior: Firefox smoother, Chrome more freeze-prone in some scenarios.

### Wayland capture flow pain points
- Screen picker / portal selection UX friction (repeated selection prompts).
- Need for one-time selection and persistent capture token behavior.

### Build/deploy pain points
- Permission issues were seen in the past (`gradlew` executable, SDK write access, ownership).
- Multi-device deployment is now routed through `deploy-android-all.sh` -> `wbeam android deploy-all`.

### Repo hygiene pain points
- Build artifacts previously leaked into git status (notably proto host `target/*`).
- Large historical logs/results in repo can add noise.

## 10) Logging and diagnostics map
Common files used in workflows:
- `/tmp/proto-portal-streamer.log`
- `/tmp/proto-portal-ffmpeg.log`
- `/tmp/proto-kms-ffmpeg.log`
- `/tmp/proto-android.log`
- `/tmp/proto-runner.log`
- `/tmp/proto-effective-config-host.json`
- `/tmp/proto-effective-config-runner.json`

## 11) What the next agent should do first

### A) Desktop rewrite (from blank skeleton)
Target UX flow:
1. Left panel: connected devices list (ADB + background probing)
2. Middle panel: device-scoped settings (disabled until selection)
3. Right panel: connect/disconnect + live session telemetry
4. Top status bar always visible

Architecture recommendation:
- Keep UI and logic separate:
  - `ui/` for rendering
  - `domain/` for types/state
  - `services/adb`, `services/probe`, `services/config`, `services/session`
- Use message/event reducer pattern to avoid UI-triggered side effects spread everywhere.

### B) Device model requirements
Each device row should include at minimum:
- serial
- adb state
- model/manufacturer
- api level + android release
- ABI
- capability flags (policy/profile hint)
- last probe timestamp

### C) Session control requirements
- per-device connect/disconnect/reconnect
- explicit display of active mode line:
  - `<BACKEND> [<RESOLUTION>@<FPS>] <PROFILE>`
- event timeline with severity and timestamps

### D) Deployment integration
- Keep `deploy-android-all.sh` as the path for all connected devices.
- Add UI action to invoke this flow asynchronously and stream progress to event panel.

## 12) Suggested near-term cleanup checklist
- Add/verify `.gitignore` coverage for all `target/`, logs, temp generated files.
- Decide which autotune artifacts should stay versioned vs archived externally.
- Normalize profile naming convention (example: `KMS_30FPS_ADAPTIVE`, `PORTAL_60FPS_BALANCED`).
- Split proto host monolith (`proto/host/src/main.rs`) into architecture layers already scaffolded.

## 13) Open questions to resolve
- Keep one APK for all APIs or split by flavor for API17-specific optimizations?
- How strongly should proto and main lane converge now vs later?
- Which capture backends will be first-class in production UI (portal/kms/grim/import/x11/win)?

## 14) Final note
Desktop app was intentionally reset. Any missing desktop behavior is expected right now.
Use the blank scaffold as the controlled restart point, then rebuild feature-by-feature with tests and clear ownership boundaries.
