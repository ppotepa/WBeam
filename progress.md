# WBeam Progress

## Session Update (2026-03-10, pending, branch `trainerv2`) - Datasets API wired end-to-end in trainer UI
- Added real dataset API support in Rust host daemon (`wbeamd-server`):
  - new responses/models:
    - `TrainerDatasetSummary`,
    - `TrainerDatasetsResponse`,
    - `TrainerDatasetDetailResponse`,
    - `TrainerDatasetRecomputeResponse`,
  - new endpoints (both plain and `/v1` namespaced):
    - `GET /trainer/datasets`,
    - `GET /trainer/datasets/{run_id}`,
    - `POST /trainer/datasets/{run_id}/find-optimal`,
  - `find-optimal` now performs deterministic ranking from `parameters.json` (`results[]` sorted by score), writes `recompute.json`, and returns best trial/score + alternatives.
- Wired datasets flow in trainer desktop app (`src/apps/trainer-tauri/src/App.tsx`):
  - added dataset state, detail state, and recompute action state,
  - added API calls:
    - refresh datasets list,
    - load dataset detail,
    - trigger `Find Optimal Best`,
  - upgraded `Datasets` tab from runs mirror to actual dataset-backed view with:
    - selected dataset row,
    - best trial and score,
    - last recompute timestamp,
    - action button (`Find Optimal Best`) with busy-state handling,
    - basic dataset detail summary cards.
- Verification:
  - `cd src/host/rust && cargo check -p wbeamd-server` -> OK,
  - `cd src/apps/trainer-tauri && npm ci && npm run build` -> OK,
  - `python3 -m py_compile src/domains/training/wizard.py` -> OK.

## Session Update (2026-03-10, pending, branch `trainerv2`) - Trainer UI implementation pass + richer Android training HUD
- Implemented a substantial trainer desktop UI pass in `src/apps/trainer-tauri`:
  - rewrote `src/apps/trainer-tauri/src/App.tsx` into a workstation-style shell with:
    - top status bar,
    - left navigation rail,
    - dedicated content zones for `Train`, `Live Run`, `Runs`, `Profiles`, `Datasets`, `Compare`, `Devices`, `Validation`, `Diagnostics`, and `Settings`,
  - added explicit busy/error feedback surfaces:
    - global busy banner,
    - error banner,
    - action-specific busy labeling via guarded async wrappers,
  - added stronger train-form UX:
    - hard blockers and warnings panel,
    - encoder selection semantics (`single` vs `multi`),
    - bitrate min/max with dual sliders + numeric fields,
    - advanced tuning accordion section (GA and overlay controls),
  - improved live screen readability:
    - KPI cards,
    - trend bar panels derived from run-tail metrics,
    - always-visible run context and stop action,
    - dedicated event log column.
- Replaced `src/apps/trainer-tauri/src/styles.css` to match the new UI model:
  - compact futuristic theme with explicit semantic colors,
  - card/grid layout with responsive fallbacks,
  - motion and transition behavior (`animate-on`/`animate-off`),
  - table, chart-bar, chip, and panel styling for dense operator workflows.
- Expanded Android-visible training HUD payload in `src/domains/training/wizard.py`:
  - added ASCII trend lines for:
    - score,
    - FPS,
    - drop behavior,
  - added best-so-far summary in overlay context,
  - added `quality_state` classification (`good`/`warn`/`risk`),
  - extended overlay updates to pass per-run history into snapshots for richer live context.
- Verification:
  - `python3 -m py_compile src/domains/training/wizard.py` -> OK,
  - `cd src/apps/trainer-tauri && npm ci && npm run build` -> OK.

## Session Update (2026-03-10, pending, branch `trainerv2`) - Trainer UI blueprint expanded with field-level interaction contract
- Expanded `trainer.ui.md` from high-level UX blueprint into detailed interaction spec:
  - added global control taxonomy describing where to use:
    - radio cards,
    - segmented controls,
    - dropdowns,
    - comboboxes,
    - chips,
    - steppers,
    - sliders,
    - switches,
    - accordions,
    - drawers,
  - for each control family documented animation, transition, focus, loading, and disabled behavior.
- Added validation severity model and dependency semantics:
  - `hard block`,
  - `soft warning`,
  - `derived override`,
  - plus global field dependency matrix for the trainer UI.
- Added screen-by-screen interaction contract covering:
  - bootstrap / service gate,
  - `Train`,
  - `Preflight Review`,
  - `Live Run`,
  - `Run Results`,
  - `Runs`,
  - `Profiles`,
  - `Datasets`,
  - `Compare`,
  - `Devices`,
  - `Validation`,
  - `Diagnostics`,
  - `Settings`.
- Each screen section now specifies:
  - required inputs,
  - blocking conditions,
  - control types,
  - motion/transition rules,
  - busy/error behavior,
  - rationale for why a given interaction pattern is used.

## Session Update (2026-03-10, pending, branch `trainerv2`) - Comprehensive Trainer UI/UX blueprint document
- Added a dedicated UI/UX specification document:
  - `trainer.ui.md`
- Consolidated the new Trainer desktop product direction into one canonical design document covering:
  - end-to-end user flow from bootstrap and service gate through training, live run, completion, and post-run analysis,
  - complete information architecture (`Train`, `Live Run`, `Runs`, `Profiles`, `Datasets`, `Compare`, `Devices`, `Validation`, `Diagnostics`, `Settings`),
  - workstation-style shell layout (top status bar, left rail, optional right detail pane),
  - per-screen behavior and layout expectations,
  - design system guidance for typography, color tokens, density, backgrounds, charts, tables, async states, tooltips, and motion,
  - device-side HUD overlay consistency requirements,
  - UX acceptance criteria and UI implementation priority order.

## Session Update (2026-03-10, pending, branch `trainerv2`) - Trainer run stability (single portal consent + live HUD visibility)
- Fixed trainer run behavior where each profile apply could trigger another Wayland portal chooser:
  - `src/domains/training/wizard.py` now creates per-session trainer marker file:
    - `/tmp/wbeam-trainer-active-<serial>-<stream_port>.flag`
  - `src/host/rust/crates/wbeamd-core/src/lib.rs` now detects that marker for Wayland sessions and forces legacy Python streamer during active trainer runs, so portal restore-token flow is used across trial restarts.
- Added restore-token support to Rust streamer path as well (defensive consistency):
  - `src/host/rust/crates/wbeamd-streamer/src/cli.rs` new args:
    - `--restore-token-file`
    - `--portal-persist-mode`
  - `src/host/rust/crates/wbeamd-streamer/src/capture.rs` now:
    - loads restore token from file,
    - retries without token when token restore fails,
    - saves new restore token returned by portal start response.
- Implemented training overlay HUD payload generation in wizard:
  - `wizard.py` now writes structured overlay text snapshots (TL/TR/BL/BR sections with live metrics and ASCII bars) to:
    - `/tmp/wbeam-trainer-overlay-<serial>-<stream_port>.txt`
  - overlay + marker files are cleaned up in `finally` block when run exits.
  - core passes `WBEAM_OVERLAY_TEXT_FILE` to streamer process when overlay file exists (Python path consumes it live).
- Fixed trainer desktop HUD “no live updates” symptom:
  - `src/host/rust/crates/wbeamd-server/src/main.rs` now spawns `wbeam train wizard` with `PYTHONUNBUFFERED=1`, so tail polling sees incremental logs instead of delayed buffered output.

## Session Update (2026-03-10, pending, branch `trainerv2`) - Tauri launcher stability on Wayland (desktop + trainer)
- Fixed Linux Tauri launcher stability path to avoid silent/no-window startup on problematic Wayland sessions:
  - `desktop.sh`:
    - added `desktop_apply_tauri_stability_env`,
    - keeps `WEBKIT_DISABLE_DMABUF_RENDERER=1`,
    - when `XDG_SESSION_TYPE=wayland`, now defaults to X11 backend for Tauri (`GDK_BACKEND=x11`, `WINIT_UNIX_BACKEND=x11`) unless explicitly disabled with `WBEAM_TAURI_NATIVE_WAYLAND=1`.
    - auto-resolves `XAUTHORITY` (runtime `xauth_*` or `~/.Xauthority`) when X11 backend is active to avoid GTK init failure.
  - `trainer.sh`:
    - now loads shared WBeam config via `wbeam_config.sh`,
    - added GUI context auto-reexec through `runas-remote` for `--ui` mode (same behavior class as desktop launcher),
    - added Tauri stability env setup identical to desktop path (`WEBKIT_DISABLE_DMABUF_RENDERER`, X11 backend fallback on Wayland),
    - auto-resolves `XAUTHORITY` when X11 fallback is active to prevent `Authorization required` GTK startup crash,
    - added clear runtime log note when fallback is applied.
- Goal of this change:
  - prevent `Gdk-Message: Error 71 (Protocol error) dispatching to Wayland display`,
  - prevent “process starts but no visible app window” in both desktop and trainer launch paths.

## Session Update (2026-03-10, pending, branch `trainerv2`) - Legacy trainer path removed; trainer_v2 only
- Removed legacy trainer execution path from main wizard flow:
  - `src/domains/training/wizard.py` no longer supports `--engine` switching,
  - removed proto/legacy branch and all legacy-engine invocation code,
  - trainer now runs only one path: `trainer_v2` (daemon live API).
- Added non-interactive run mode for API-driven execution:
  - new wizard flags:
    - `--non-interactive`,
    - `--apply-best/--no-apply-best`,
    - `--export-best/--no-export-best`,
  - when non-interactive:
    - no prompts,
    - auto-select defaults,
    - auto-start session if needed.
- Added generation-style progress lines in live trainer output:
  - emits `generation X/Y: population=N (start)` blocks,
  - emits `done trial=...` summary lines for HUD compatibility.
- Host Trainer API updated to run only new path:
  - removed `engine` input from start request contract,
  - backend now always records `engine=trainer_v2`,
  - `post_trainer_start` launches wizard with non-interactive + auto apply/export flags.
- Removed legacy trainer implementation file:
  - deleted `src/domains/training/legacy_engine.py`.
- Updated helper scripts/wrappers:
  - rewrote `src/domains/training/train_max_quality.sh` to invoke `./wbeam train wizard` (new path only),
  - `proto/autotune.py` now explicitly reports deprecation and exits with guidance.
- Updated training domain docs:
  - `src/domains/training/README.md` now documents only `wizard.py` trainer ownership.

## Session Update (2026-03-10, pending, branch `trainerv2`) - Trainer Tauri shell + parameterized run API + proto HUD wiring
- Implemented native Trainer desktop shell for Tauri app:
  - added `src/apps/trainer-tauri/src-tauri/` (`Cargo.toml`, `build.rs`, `src/main.rs`, `tauri.conf.json`, capabilities, icon, schemas),
  - extended `src/apps/trainer-tauri/package.json` with:
    - `tauri:dev`
    - `tauri:build`.
- Upgraded `trainer.sh` launcher:
  - `--ui` (default) now runs Tauri trainer app (`npm run tauri:dev`),
  - `--web` keeps Vite-only mode,
  - command help and mode descriptions updated.
- Extended trainer start contract in host daemon (`wbeamd-server`) with explicit GA/encoder/bitrate controls:
  - new request fields:
    - `generations`, `population`, `elite_count`,
    - `mutation_rate`, `crossover_rate`,
    - `bitrate_min_kbps`, `bitrate_max_kbps`,
    - `encoder_mode` (`single|multi`), `encoders[]`,
  - added validation/sanitization for mode and codec list,
  - preserved encoder order from request (stable dedupe, no alphabetical reordering) so first codec remains priority codec,
  - wired all new parameters into wizard invocation and persisted run artifacts (`run.json` + in-memory run state).
- Extended `src/domains/training/wizard.py` to accept and persist new runtime knobs:
  - new CLI args mirror server contract (GA + encoder mode/list + bitrate range),
  - live-api search space now filters/clamps encoders and bitrate ladder to requested range,
  - proto engine call now forwards GA controls (including crossover rate) and bitrate bounds,
  - fixed proto temp config emission ordering so forced codec (`PROTO_H264`) is written before run,
  - added explicit proto codec selection policy:
    - `single` mode -> selected codec,
    - `multi` mode -> prefers `h265`, fallback `h264`,
    - persisted as `effective_proto_encoder` in run parameters.
- Extended legacy autotune core (`src/domains/training/legacy_engine.py`):
  - added `--crossover-rate`,
  - generation crossover now uses configured probability (instead of hardcoded `0.50`),
  - report metadata now persists `crossover_rate`.
- Trainer UI (`src/apps/trainer-tauri/src/App.tsx`) improvements:
  - added training controls for:
    - generations, population, elite, mutation, crossover,
    - bitrate min/max,
    - encoder mode + codec selection (`h264/h265/rawpng/mjpeg`),
  - start payload now sends full parameter set to `/v1/trainer/start`,
  - added local bitrate range guard (`min <= max`),
  - upgraded HUD parser to handle both:
    - live_api lines (`[tNN] score=...`)
    - proto autotune lines (`done trial=... sender_p50=... pipe_p50=...` + generation progress).
- Verification:
  - `python3 -m py_compile src/domains/training/wizard.py src/domains/training/legacy_engine.py` -> OK
  - `cd src/host/rust && cargo check -p wbeamd-server` -> OK
  - `cd src/apps/trainer-tauri && npm ci && npm run build` -> OK
  - `cd src/apps/trainer-tauri/src-tauri && cargo check` -> OK
  - `bash -n trainer.sh` and `./trainer.sh --help` -> OK

## Session Update (2026-03-10, pending, branch `trainerv2`) - Preflight + mode scoring + Trainer GUI scaffold
- Extended `src/domains/training/wizard.py` with mandatory preflight diagnostics:
  - ADB push throughput benchmark (`adb push` MB/s),
  - ADB shell RTT benchmark (`p50/p95`),
  - stream baseline sampling summary (when daemon is reachable),
  - derived `recommended_bitrate_kbps` seed from measured link quality.
- Integrated preflight into run lifecycle:
  - preflight executes before trial loop,
  - preflight summary is now persisted in `parameters.json`,
  - `preflight.json` is written automatically in profile artifact directory.
- Implemented mode-aware scoring presets and gates in wizard (`quality`, `balanced`, `low_latency`):
  - per-mode score weights,
  - per-mode penalty sensitivity,
  - hard gate style penalties and `gate_failed` notes.
- Added initial Trainer GUI app scaffold:
  - `src/apps/trainer-tauri/`
  - includes Vite/Solid setup, initial tabs (`Train`, `Live HUD`), and HUD placeholder charts,
  - includes app README and run instructions.

## Session Update (2026-03-10, pending, branch `trainerv2`) - TrainerV2 foundation slice
- Started dedicated `trainerv2` branch and implemented first operational TrainerV2 building block.
- Added new launcher script:
  - `trainer.sh`
  - behavior:
    - verifies daemon health (`/v1/health`),
    - optional service bootstrap via `--start-service`,
    - forwards run to `./wbeam train wizard` (bridge mode),
    - writes logs to `logs/trainer/<timestamp>.trainer.log`.
- Extended training wizard input contract with explicit profile identity:
  - new `--profile-name` flag (interactive prompt default: `baseline`).
- Added per-profile artifact persistence required by TrainerV2 spec:
  - output directory: `config/training/profiles/<profile_name>/`
  - generated files:
    - `<profile_name>.json` (trained profile snapshot)
    - `parameters.json` (training inputs/parameters and run context)
- Implemented artifact generation for both engines:
  - `proto` engine path now exports profile artifact + parameters alongside existing canonical files,
  - `live_api` path now exports profile artifact + parameters from ranked trial results.

## Session Update (2026-03-10, pending) - Comprehensive Trainer product/spec document
- Added a full project-level Trainer blueprint in:
  - `trainer.md`
- Document consolidates all agreed direction for the dedicated training app and pipeline, including:
  - app identity and runtime dependency model (`trainer` + `trainer.sh`, daemon-required behavior),
  - IA/tabs (`Train`, `Live HUD`, `Profiles`, `Runs`, `Compare`, `Devices`),
  - formal training input contract,
  - mandatory preflight diagnostics (ADB throughput/latency + baseline stream benchmark),
  - staged optimization design (stability floor -> bitrate ceiling -> fine tuning),
  - hard-gate + weighted scoring model by mode (`max_quality`, `balanced`, `low_latency`),
  - full HUD requirements with live metrics and charts,
  - profile artifact contract with per-profile directory outputs and required `parameters.json`,
  - proposed trainer API surface and event stream,
  - phased implementation plan, acceptance criteria, and open questions.

## Session Update (2026-03-10, pending) - Train proto engine env sanitization for `run.sh`
- Fixed training failure in `./wbeam train wizard` (`proto` engine) caused by inherited shell env vars blocked by `proto/run.py`.
- In `src/domains/training/legacy_engine.py`:
  - added `build_proto_run_env()` to strip blocked runtime prefixes (`PROTO_`, `RUN_`, `WBEAM_`, `HOST_IP`, `SERIAL`, etc.) before invoking proto runner,
  - `prepare_device_once(...)` now runs `proto/run.sh --prepare-only` with sanitized env,
  - full-run trial path (`run.sh --config ...`) now also uses sanitized env.
- Effect:
  - training no longer aborts with `runtime environment overrides are not allowed`,
  - realistic one-time prepare + visible on-device overlay flow can proceed.

## Session Update (2026-03-10, pending) - Train wizard proto engine now runs realistic prepare flow
- Fixed unrealistic `proto` wizard run mode where trials could execute without visible tablet stream/HUD:
  - removed forced `--host-only` from `src/domains/training/wizard.py` when invoking legacy engine.
- Effect:
  - legacy engine now performs one-time `--prepare-only` path (deploy/launch setup) when `--reuse-device` is active,
  - benchmark loop still stays efficient (`reuse-device`), but training starts from a real active stream context,
  - on-screen trial HUD/overlay is now expected to appear again during tuning.

## Session Update (2026-03-10, pending) - Training domain cleanup and compatibility wrappers
- Continued organization of `train` as a first-class app domain:
  - moved legacy autotune engine from `proto/autotune.py` to `src/domains/training/legacy_engine.py`,
  - moved max-quality helper from `proto/train-autotune-max-quality.sh` to `src/domains/training/train_max_quality.sh`,
  - added domain doc `src/domains/training/README.md`.
- Kept backward compatibility for existing tooling:
  - `proto/autotune.py` now acts as a thin wrapper delegating to `src/domains/training/legacy_engine.py`,
  - `proto/train-autotune-max-quality.sh` now forwards to `src/domains/training/train_max_quality.sh`.
- Updated train wizard engine wiring:
  - `src/domains/training/wizard.py` now invokes the engine from the new domain path and validates its presence.
- Training outputs from max-quality helper are now written to main-lane locations:
  - profiles/best config under `config/training/`,
  - reports under `logs/train/`.

## Session Update (2026-03-10, pending) - Training domain reorganization + legacy desktop removal
- Removed legacy desktop UI project:
  - deleted `src/apps/desktop-egui/` from repository.
- Promoted training to an explicit main-project domain:
  - moved trainer entrypoint from `scripts/train_wizard.py` to `src/domains/training/wizard.py`,
  - `./wbeam train wizard` now points to the new domain path.
- Moved canonical training profile storage out of proto lane:
  - new canonical path: `config/training/profiles.json`,
  - seeded with current baseline profile.
- Host daemon preset loading is now aligned with new structure:
  - first reads `config/training/profiles.json`,
  - keeps fallback compatibility with legacy `proto/config/profiles.json`.
- Trainer proto engine export paths updated to main-project training domain:
  - profiles -> `config/training/profiles.json`,
  - best snapshot -> `config/training/autotune-best.json`.
- Docs cleanup:
  - `AGENTS.md` updated (legacy desktop removed, training domain path visible in tree),
  - `proto/README.md` marked desktop-egui as historical.

## Session Update (2026-03-10, pending) - Train wizard switched to legacy proto autotune core by default
- Reworked `./wbeam train wizard` default engine to use the proven proto dynamic autotune loop:
  - new default `--engine proto`,
  - executes `proto/autotune.py` with single-portal-consent + reuse-device + optional on-screen HUD overlay,
  - keeps portal consent stable across trials (old behavior user requested).
- Added engine selection:
  - `--engine proto` (default, legacy dynamic loop with screen HUD),
  - `--engine live_api` (existing direct `/v1/apply` + `/v1/metrics` loop retained as fallback).
- Proto engine integration improvements:
  - seeds temporary base config per selected ADB serial,
  - forces native/current capture size + quality bitrate ladder up to `200000 kbps`,
  - exports baseline to `proto/config/profiles.json` and syncs desktop runtime defaults (`trained-profile-runtime.json`) from generated best config.

## Session Update (2026-03-10, pending) - Trainer wizard metrics null-safety fix
- Fixed `./wbeam train wizard` trial sampling failure (`'NoneType' object has no attribute 'get'`) when daemon metrics payload contains nullable fields (e.g. idle/no-stream state).
- `score_trial(...)` now normalizes `metrics`, `kpi`, and `latest_client_metrics` to dicts before reads, preventing per-trial exceptions and allowing stable scoring fallback.
## Session Update (2026-03-10, pending) - Trainer defaults switched to max-quality baseline
- Updated main-lane trainer wizard defaults (`./wbeam train wizard`) to prioritize highest quality out-of-the-box:
  - default mode is now `quality` (no extra prompt needed),
  - quality search space starts with native/current landscape resolution and a high bitrate ladder up to `200000 kbps` (200 Mbps),
  - quality trial order is now deterministic high-to-low (bitrate -> resolution -> FPS -> encoder) instead of randomized, so first executed trials are max-fidelity candidates.

## Session Update (2026-03-10, pending) - Main-lane trainer wizard (`wbeam train wizard`)
- Added a new interactive trainer in the main path:
  - `./wbeam train wizard` (aliases: `train tui`, `train run`),
  - implemented as `scripts/train_wizard.py` and integrated into `./wbeam`.
- Wizard scope is current production stack (not proto run loop):
  - targets selected ADB serial + per-device stream port,
  - runs live trials against daemon API (`/v1/apply`, `/v1/metrics`, optional `/v1/start`),
  - computes score from real runtime KPIs (present/recv/decode FPS, e2e/decode/render timings, drops, late frames, queue pressure),
  - ranks and applies best config directly to active session.
- Export path is aligned with current app state:
  - writes winning baseline profile to `proto/config/profiles.json` (single `baseline` profile),
  - updates desktop runtime defaults in `src/apps/desktop-tauri/src/config/trained-profile-runtime.json` (baseline encoder/cursor),
  - stores full trial report in `logs/*.train-wizard.json`.
- Added help/docs entry in `./wbeam` usage output for `train wizard`.

## Session Update (2026-03-10, pending) - Android fullscreen+awake hardening and max-quality autotune training flow
- Android app runtime hardening for continuous kiosk-style streaming:
  - forced immersive fullscreen in both debug and release paths (`MainActivity.applyBuildVariantUi -> setFullscreen(true)`),
  - added persistent immersive re-apply on lifecycle events (`onResume`, `onWindowFocusChanged`) to keep nav/system bars hidden,
  - enabled always-on display while app is active (`FLAG_KEEP_SCREEN_ON` + root `setKeepScreenOn(true)`),
  - removed `Back` behavior that previously dropped out of fullscreen.
- Debug/HUD stability improvements to reduce false low-FPS and random “no transmit” dips in overlay:
  - added short metrics grace window before forcing `HUD OFFLINE` (`METRICS_STALE_GRACE_MS`),
  - added present FPS stale-grace smoothing with fallback to decoder/receiver FPS when direct present FPS temporarily drops,
  - adjusted pressure coloring logic to mark red only on real FPS degradation plus timing/queue pressure (less aggressive false-red),
  - relaxed debug loss thresholds to align with real-world stream behavior:
    - overlay text: green `<=20%`, orange `>20%`, red `>55%`,
    - graph coloring in `FpsLossGraphView` updated to the same thresholds.
- Autotune improvements for high-throughput quality training:
  - `proto/autotune.py` now supports runtime mutation overrides:
    - `--fps-values` (comma-separated candidate FPS list),
    - `--bitrate-values` (comma-separated candidate bitrate list in kbps; supports up to 200 Mbps and beyond).
  - added run metadata output for active tunable candidate lists in autotune report.
- Added a ready-to-run 2-stage “max quality + fps” training script:
  - `proto/train-autotune-max-quality.sh`
  - stage1 broad search + stage2 deep refinement,
  - default bitrate ladder reaches `200000 kbps` (200 Mbps),
  - exports final profile as `baseline` to `proto/config/profiles.json` and snapshot to `proto/config/autotune-best.json`.

## Session Update (2026-03-10, pending) - Connect session profile modal + split desktop profile catalogs
- Added per-connect session configuration in desktop Tauri UI:
  - `Connect` now opens a session modal (also on Wayland) with:
    - trained profile selector,
    - resolution preset selector,
    - encoder selector (`From trained profile`, `H.264`, `H.265`, `RAW PNG`).
  - Session config is chosen every time `Connect` is pressed (no per-device auto-persist for profile values).
- Wayland behavior preserved:
  - global `Use experimental virtual mirroring (Wayland only)` checkbox still controls Wayland backend mode,
  - modal now shows active Wayland mode and applies selected profile/resolution/encoder for that connect.
- Split profile naming/runtime metadata into separate desktop JSON catalogs:
  - `src/apps/desktop-tauri/src/config/trained-profile-labels.json`
  - `src/apps/desktop-tauri/src/config/trained-profile-runtime.json`
  - `src/apps/desktop-tauri/src/config/connect-resolution-presets.json`
  - `src/apps/desktop-tauri/src/config/connect-encoder-options.json`
- Extended Tauri backend `device_connect` to accept per-session config payload:
  - new optional args: `connectProfile`, `connectEncoder`, `connectSize`,
  - sanitized and forwarded as JSON `ConfigPatch` body to host `POST /v1/start`,
  - preserves existing display-mode handling (`duplicate`, `virtual_monitor`, `virtual_mirror`).
- Validation:
  - `cd src/apps/desktop-tauri && npm run build` -> OK
  - `cd src/apps/desktop-tauri/src-tauri && cargo check` -> OK

## Session Update (2026-03-10, pending) - Android debug overlay simplified to read-only telemetry
- Simplified Android debug UX in `MainActivity` to remove runtime profile/control editing from the app screen:
  - removed `SettingsRepository` usage (no SharedPreferences read/write for stream profile controls in this path),
  - debug startup now uses fixed defaults (`lowlatency`, preferred codec, embedded cursor, 100% scale, 60 FPS, 25 Mbps),
  - removed UI hooks that changed presets/profile/encoder/bitrate/FPS from debug controls.
- Volume-key debug overlay remains, but now shows telemetry-only content (debug info text + FPS/loss graph) without exposing settings actions.
- App chrome related to on-device control editing is hidden in this flow:
  - top bar, quick actions, settings/log/fullscreen toggles are hidden,
  - settings/debug control panels are not exposed from the overlay.
- Minor UX text update:
  - idle hint changed from manual settings-driven start to desktop-driven flow (`waiting for desktop connect`).
- Validation:
  - `cd android && GRADLE_USER_HOME="/home/ppotepa/git/WBeam/.gradle-user" ./gradlew :app:compileDebugJavaWithJavac --no-daemon --stacktrace` -> BUILD SUCCESSFUL

## Session Update (2026-03-10, pending) - streamer crash guard for key-int-max (exit code 101)
- Investigated `stream start aborted (code=101)` with `key-int-max` hint.
- Added defensive GOP property handling in Rust streamer encoder config:
  - `key-int-max` is now applied only when property exists on the active encoder backend,
  - GOP value is passed as `u32` for safer type matching with GObject property expectations,
  - when property is unavailable, streamer logs warning and continues instead of aborting start.
- Scope:
  - `x264` and `x265` branches in `wbeamd-streamer` encoder setup.
- Validation:
  - `cd src/host/rust && cargo check -p wbeamd-streamer`

## Session Update (2026-03-10, pending) - Wayland-only experimental mirroring toggle in main UI
- Reworked connect UX split by host backend:
  - on `wayland_portal`, `Connect` now skips the middle mode modal and starts connect directly (portal chooser still appears as normal),
  - on non-Wayland hosts, existing connect mode modal remains (virtual monitor / duplicate).
- Moved `Use experimental virtual mirroring` to the main application window (below devices list):
  - toggle is active only on Wayland hosts,
  - toggle is visible but disabled (greyed) on X11/non-Wayland hosts,
  - state is persisted in localStorage (`wbeam.connect.experimental.dup.wayland`).
- Backend wiring for Wayland experimental mode:
  - Tauri `device_connect` now skips virtual-doctor blocking for `wayland_portal` virtual modes,
  - host Wayland backend maps both `virtual_monitor` (legacy flow) and `virtual_mirror` (experimental toggle) to non-failing activation.
- Regression fix after field logs:
  - on Wayland, unchecked toggle now sends `virtual_monitor` again (restores pre-change behavior),
  - checked toggle sends `virtual_mirror`.
- Validation:
  - `cd src/apps/desktop-tauri && npm run build`
  - `cd src/apps/desktop-tauri/src-tauri && cargo check`
  - `cd src/host/rust && cargo check -p wbeamd-core`

## Session Update (2026-03-10, pending) - connect modal now visible on Wayland (checkbox discoverability fix)
- Fixed missing experimental-duplication checkbox visibility in desktop Tauri app:
  - removed Wayland fast-path that skipped connect modal and directly triggered duplicate connect,
  - connect modal now opens for all hosts, so mode controls and checkbox are always visible.
- Tightened virtual mode availability guard in UI:
  - `virtual monitor` is now marked available only for supported resolver path (`linux_x11_real_output`),
  - prevents misleading virtual selection on `wayland_portal` hosts.
- Validation:
  - `cd src/apps/desktop-tauri && npm run build` -> OK

## Session Update (2026-03-10, pending) - GUI checkbox for experimental virtual duplication (virtual mirror)
- Added desktop connect-modal checkbox:
  - label: `Use experimental duplication (virtual mirror)`,
  - visible in connect dialog and enabled only for X11 real-output resolver (`linux_x11_real_output`),
  - persisted per device serial in localStorage.
- Added new connect mode plumbing:
  - frontend/session/backend now support `virtual_mirror` mode in addition to `virtual_monitor` and `duplicate`,
  - Tauri backend accepts aliases `virtual-duplicate` / `virtual_duplicate` and maps them to host `display_mode=virtual_mirror`.
- Host core display backend now supports new `DisplayMode::VirtualMirror`:
  - routed as virtual mode (not duplicate fallback),
  - X11 virtual backend receives mirror intent.
- X11 real-output backend behavior:
  - when mirror mode is requested and primary output exists, virtual output is activated with `xrandr --same-as <primary>`,
  - mirrored geometry is now accepted in this mode (previously treated as error),
  - if mirror is requested but not achieved, host logs a warning.
- Validation:
  - `cd src/apps/desktop-tauri && npm run build` -> OK
  - `cd src/host/rust && cargo check -p wbeamd-core` -> OK
  - `cd src/apps/desktop-tauri/src-tauri && cargo check` -> OK

## Session Update (2026-03-10, pending) - autotune: default capture size auto-detected from target device
- Updated `proto/autotune.py` so `--capture-size` is now truly optional:
  - when `--capture-size` is omitted, autotune auto-detects full physical device resolution via ADB (`wm size`, fallback `dumpsys display`),
  - selected size is written into runtime `PROTO_CAPTURE_SIZE` before trials start,
  - preserves existing config value only when auto-detection is unavailable.
- Serial resolution follows existing proto behavior:
  - uses configured `SERIAL` when present,
  - otherwise uses the same ADB selection strategy as `proto/run.py` (prefer physical + Lenovo/S6000 heuristic).
- Added explicit run metadata in autotune report output:
  - `capture_size_source` (`arg|device_auto|config`),
  - `capture_size_auto_detected`,
  - `capture_size_auto_serial`.
- Validation:
  - `python -m py_compile proto/autotune.py` -> OK
  - `python proto/autotune.py --help` -> OK

## Session Update (2026-03-10, pending) - Force Android app landscape orientation
- Enforced horizontal app orientation for Android client UI:
  - `MainActivity` now uses `android:screenOrientation="sensorLandscape"` in manifest.
  - Effect: app stays in landscape (including reverse-landscape), matching horizontal desktop streaming targets.
- Validation:
  - `./gradlew :app:compileDebugJavaWithJavac --no-daemon --stacktrace` -> BUILD SUCCESSFUL.

## Session Update (2026-03-10, pending) - Wayland portal output auto-layout (non-overlap)
- Added host-side post-start layout pass for `wayland_portal` sessions in `wbeamd-server`:
  - after successful `/start`, server now inspects KDE output topology via `kscreen-doctor -j`,
  - tracks serial -> output mapping when a new enabled output appears after a connect,
  - auto-repositions overlapping managed outputs into a horizontal non-overlapping layout (`output.<name>.position.x,y`) to reduce “app looks broken” confusion.
- Safety guards:
  - logic is best-effort and non-fatal (stream start succeeds even if layout command fails),
  - ignores cloned/replicated outputs and disabled outputs,
  - can be disabled with `WBEAM_WAYLAND_PORTAL_AUTO_LAYOUT=0`.
- Known limitation:
  - KDE `kscreen-doctor` does not expose output rename in this path, so connector naming in Display Configuration is compositor-managed and cannot be set to Android serial directly from this integration.
- Validation:
  - `cargo check -p wbeamd-server` -> OK.

## Session Update (2026-03-10, pending) - Unified debug overlay toggle via volume-key hold
- Android debug overlay toggle input was unified across API levels in `MainActivity`:
  - replaced separate single-key behavior (`Vol+` ON, `Vol-` OFF) with one consistent chord:
    hold `Vol+` + `Vol-` together for ~650ms to toggle overlay `ON/OFF`,
  - toggle remains gated by `BuildConfig.DEBUG` (debug builds only),
  - added key-up/key-down state tracking and debouncing so one hold produces one toggle until keys are released.
- Lifecycle safety:
  - added cleanup for pending toggle runnable in `onDestroy`.
- Validation:
  - `./gradlew :app:compileDebugJavaWithJavac --no-daemon --stacktrace` -> BUILD SUCCESSFUL.

## Session Update (2026-03-10, pending) - Vite/esbuild EPIPE mitigation
- Hardened desktop Vite dev config against sporadic esbuild `write EPIPE` failures:
  - disabled HMR error overlay (`server.hmr.overlay = false`) so desktop UI is not blocked by modal overlay during transient dev worker crashes,
  - excluded `lucide-solid` from Vite optimizeDeps pre-bundle to avoid heavy esbuild pre-transform path that was repeatedly hitting EPIPE.
- Validation:
  - `npm run build` in `src/apps/desktop-tauri` -> OK
  - `npm run dev` runtime smoke could not be fully validated in this sandbox because binding `:1420` is blocked (`listen EPERM`), but static config is applied and build passes.

## Session Update (2026-03-10, pending) - Multi-device Wayland stability (API34/35 focus)
- Desktop device list refresh tightened for multi-device workflows:
  - lowered adaptive devices polling floor from `5000ms` to `1200ms`,
  - added forced fast refresh on window focus and on `visibilitychange` resume,
  - kept adaptive backoff to reduce unnecessary polling when nothing changes.
- Wayland portal connect flow now blocks parallel connect starts across different devices:
  - prevents overlapping portal prompt races that produced duplicated selection windows.
- Tauri backend connect/disconnect now resolves a canonical stream port per target serial before action:
  - guards against stale UI port values and per-device session collisions,
  - adds explicit requested/effective port trace logging for diagnostics.
- Host daemon (`wbeamd-server`) now serializes `/start` operations for `wayland_portal` capture mode:
  - enforces one portal start handshake at a time across sessions to reduce prompt duplication/mixups.
- Legacy Python Wayland portal fallback now uses per-session restore-token files:
  - token path includes serial + stream port to avoid cross-device token interference.
- Validation run:
  - `npm run build` in `src/apps/desktop-tauri` -> OK
  - `cargo check -p wbeamd-server` in `src/host/rust` -> OK
  - `cargo check` in `src/apps/desktop-tauri/src-tauri` -> OK

## Session Update (2026-03-09, 4e63a732) - Display backend separation (X11/Wayland/Windows)
- Separated host display-mode responsibilities into a dedicated backend layer:
  - new module tree: `src/host/rust/crates/wbeamd-core/src/infra/display_backends/`
  - backends: `x11`, `wayland`, `windows`
  - unified router in `display_backends/mod.rs`
- Unified runtime contract per backend:
  - `virtual_monitor_probe(...)`
  - `activate(...)` with explicit mode (`duplicate` / `virtual_monitor` / `virtual_isolated`)
  - runtime handle lifecycle with centralized `stop_runtime(...)`
- `DaemonCore` now delegates display-mode activation/probing to backend router instead of embedding X11 logic directly in `lib.rs`.
- X11 implementation now encapsulates:
  - duplicate mode activation
  - virtual monitor activation via real output (`x11_real_output`)
  - optional isolated fallback (`Xvfb`) as explicit mode
- Wayland and Windows backends now have explicit stubs for both duplicate and virtual-monitor paths (virtual monitor returns clear `not implemented` contract instead of implicit fallthrough).

## In Progress (2026-03-09, 51b91379) - X11 real virtual output path (EVDI-first)
- Reworked X11 `virtual_monitor` semantics to target **real output backend** instead of logical RandR monitor objects:
  - Added new infra module: `src/host/rust/crates/wbeamd-core/src/infra/x11_real_output.rs`.
  - New probe path checks for X11 real-output capability (provider/output expectations for EVDI-like path).
  - New create/destroy path attempts to enable a real X11 output and returns active geometry for capture region binding.
- Core integration:
  - `virtual_probe` now prioritizes resolver `linux_x11_evdi_real_output`.
  - `virtual_doctor` now exposes `resolver` in API model.
  - `virtual_monitor` start path now calls `x11_real_output::create(...)` (strict real-output intent).
  - Added `real_output` lifecycle ownership in daemon state and cleanup path.
- Desktop UI gating:
  - Connect modal now treats virtual monitor as available only for resolver `linux_x11_evdi_real_output` (or wayland portal path).
  - Prevents false-positive enablement of pseudo-virtual paths.
- Dependency scripts updated for real-output prep:
  - `scripts/virtual-deps-check.sh` now checks `evdi` + `xrandr` + `Xvfb`.
  - `scripts/virtual-deps-install.sh` now includes package-manager install paths for `evdi`/dkms/tooling (best-effort distro matrix) plus fallback deps.
- Validation:
  - `cargo check` passed for host (`wbeamd-core`, `wbeamd-server`, `wbeamd-api`) and Tauri backend.
  - `npm run build` passed for desktop-tauri frontend.
  - release build passed for `wbeamd-server` + `wbeamd-streamer`.

## Session Update (2026-03-09, 51b91379) - Fixes from “Virtual Monitor retries” investigation
- Fixed host probing mis-detecting GUI session when daemon is launched without `DISPLAY`:
  - SSH no longer forces `remote=true` unless X11 forwarding is detected (`DISPLAY=localhost:*`).
  - `XDG_SESSION_TYPE=tty` is now overridden by detected X11 sockets (`/tmp/.X11-unix/X*`), preventing `capture_mode=unsupported_host`.
- Prevented Android-side `/apply` failures when the UI picks built-in presets:
  - daemon now merges built-in presets (`wbeamd_api::presets`) with proto-trained presets (`proto/config/profiles.json`).
- Logging improvements:
  - Tauri backend now writes per-run logs: `logs/YYYYMMDD-HHMMSS.ui.NNNN.log` and `logs/YYYYMMDD-HHMMSS.connect.NNNN.log`.
  - `desktop.sh` now tees output to `logs/YYYYMMDD-HHMMSS.desktop.NNNN.log`.
- Android diagnostics:
  - added `startLiveView` logs printing stream cfg vs SurfaceView size vs surface frame to debug “small centered video”.

Date: 2026-03-07
Branch: `master`
Status: active

## Session Update (2026-03-08)
- Stabilized desktop UI refresh model to reduce flicker and inconsistent button states:
  - separated internal polling sync from manual UI refresh semantics,
  - reduced unnecessary signal updates by applying state only on real data change.
- Fixed service lifecycle persistence across remote startup:
  - `start-remote` no longer removes desktop service unit by default,
  - added explicit `--fresh-service` flag for clean reinstall flow,
  - improved Tauri service detection using `systemctl --user show ... LoadState`.
- Added connect mode UX for per-device action:
  - connect dialog now asks for `Create virtual desktop` vs `Duplicate current screen`,
  - selection is persisted per ADB serial in desktop UI storage.
- Current compatibility behavior:
  - `display_mode` is now forwarded from desktop connect flow to daemon `/v1/start`.
  - Added first host implementation of `virtual desktop` for X11 backend:
    - daemon now supports per-session requested display mode (`duplicate` / `virtual`),
    - on `virtual` + `x11_gst`, host spawns dedicated `Xvfb` display per device serial,
    - streamer captures from that virtual display (`DISPLAY=:<n>`) instead of host main desktop.
  - Safety behavior:
    - `virtual` is rejected on non-X11 capture backends with explicit error,
    - on stop, daemon terminates spawned virtual display process.
  - Requirement:
    - host must have `Xvfb` installed for virtual mode.

## Session Update (2026-03-08) - virtual resolver + doctor
- Added host-level virtual display resolver/doctor API:
  - `GET /v1/virtual/probe`
  - `GET /v1/virtual/doctor`
- Resolver behavior:
  - `x11_gst` -> resolver `linux_x11_xvfb`, requires `Xvfb`
  - `wayland_portal` -> reports unsupported/pending virtual backend
  - includes machine-readable `missing_deps[]` + `install_hint`.
- Added desktop GUI preflight for `Connect -> Virtual`:
  - UI now calls `virtual_doctor` before connect on selected device serial/port.
  - If dependencies are missing, user gets clear prompt with install hint and can fallback to `Duplicate`.
  - Prevents opaque 500 failures when virtual backend is not ready.

## Session Update (2026-03-08) - startup virtual checks
- Added first-start virtual capability check in desktop GUI startup flow:
  - after initial snapshot, GUI runs `virtual_doctor` automatically,
  - if virtual mode is actionable-but-missing (e.g. `Xvfb` absent), it shows setup modal immediately.
- Added one-time dismissal memory:
  - user can choose `Later` and suppress repeated startup prompts for the same host-backend+missing-deps signature.
- Connect guard remains active:
  - `Connect -> Virtual` still runs per-device doctor preflight and offers Duplicate fallback.

## Current Baseline (Authoritative)
- Desktop app is now based on Tauri 2 + SolidJS + TypeScript.
- Primary desktop target is a compact `400x800` dialog UX.
- Device list uses card rows (one device = one tile) with vertical scrolling.
- Service lifecycle is now controllable from desktop UI (install/uninstall/start/stop + status probe).

## Latest Completed Commits
- `2928bdcd` - `feat(versioning): switch local version source to file-based buildno (0.1.N)`
  - Added file-based build number source: `.wbeam_buildno` (ignored in git).
  - `wbeam` build version generation now uses `0.1.<buildno>` instead of git-sha timestamp.
  - Added `WBEAM_VERSION_BASE` support (default `0.1`) to keep future branch/version lanes simple.
  - `devtool` build/deploy path now uses the same file-based build number logic.
  - `version doctor` now reports `.wbeam_buildno` value alongside `.wbeam_build_version`.
- `81162863` - `fix(versioning): unify host/android build rev and add version doctor`
  - Added `./wbeam version doctor` with structured diagnostics and log output (`logs/YYYYMMDD-HHMMSS.version.NNNN.log`):
    - host `/health` build revision
    - `.wbeam_build_version` value
    - `WBEAM_BUILD_REV` env status
    - per-device APK version + match/mismatch result
  - Added `./wbeam version new` and `./wbeam version current` helpers.
  - `start-remote` now generates one shared build revision and passes it to both host build and Android deploy-all.
  - `start-remote` now runs `wbeam version doctor` at the end of deploy for immediate mismatch diagnostics.
  - `run_wbeamd.sh` now reuses `WBEAM_BUILD_REV` (or `.wbeam_build_version`) for debug `cargo run`, preventing host debug fallback revision (`0.0.<sha>-build`) drift.
  - Android `StatusPoller` now logs build mismatch transitions (app vs host revision) to `adb logcat` for automatic root-cause visibility.
- `5e811955` - `fix(remote): fresh start flow and robust multi-device deploy-all`
  - `start-remote` now performs full fresh cycle: host down, remove desktop user service, uninstall APK on all connected adb devices, host build, host start in remote user session, deploy-all, GUI launch.
  - `start-remote` wait logic hardened: longer control API wait and explicit host-probe readiness check (`supported=true` best effort).
  - `android deploy-all` no longer fails legacy devices when `adb reverse` is unavailable; reverse is now best-effort per device and launch determines success.
  - Verified on mixed API lane (API17 + API34): deploy summary `ok=2 failed=0`.
- `d1a3e321` - `fix(versioning): align host/app revision sources and host build stamping`
  - Host Rust build now receives `WBEAM_BUILD_REV` during compile in both `wbeam host build` and `devtool host build`.
  - Desktop Tauri expected host/APK version now first reads daemon `/health` `build_revision` (runtime source), then env/file fallback.
  - Fixes false “match” in desktop when Android HUD reports host mismatch.
- `0b2bb174` - `fix(android): make deploy-all build and resolve hosts per device serial`
  - `android deploy-all` now resolves pipeline/hosts per serial (instead of one shared build for all devices).
  - For each device: selects serial, resolves deploy hosts, builds APK with per-device config, installs, applies reverse, and launches.
  - Prevents mixed-device misdeploy (e.g., API17 + API34 receiving incompatible baked host/pipeline).
- `9bb5a393` - `fix(start-remote): launch desktop even when android deploy-all fails`
  - Root-cause for “desktop app not visible” in `start-remote`: deploy failure aborted script before GUI launch.
  - `start-remote` now continues to `runas-remote ./devtool` even when `android deploy-all` fails.
  - Adds explicit warning/note that GUI starts with last known Android state when deploy failed.
- `c2a6f683` - `refactor(remote): align runas-remote UX with start-remote user-driven flow`
  - `start-remote` is now user-only oriented (no remote host IP forcing), while still doing full `deploy-all`.
  - `runas-remote` now supports default user (`WBEAM_DEV_REMOTE_USER` fallback to `ppotepa`) and default app (`desktop.sh`).
  - Added compatibility parser for common app-first typo (`./runas-remote ./start-remote <user>`), with warning.
  - Both scripts now follow the same user/session-first model; `start-remote` remains the full-deploy wrapper.
- `0c3dbee8` - `fix(remote): enforce dev-remote host/serial and harden gradle metadata cache recovery`
  - `start-remote` now clears legacy env keys and stale host override vars before deploy.
  - Added `WBEAM_DEV_REMOTE_SERIAL` support to force specific target when multiple devices are connected.
  - `start-remote` keeps host override in one place (`WBEAM_DEV_REMOTE_HOST_IP`, default `192.168.100.208`).
  - Gradle recovery in `wbeam` now rotates both `transforms` and `groovy-dsl` cache dirs on workspace metadata corruption.
- `6010890e` - `fix(wbeam): auto-select adb serial to avoid multi-device deploy ambiguity`
  - Auto-selects a concrete serial when multiple `adb` devices are connected and `WBEAM_ANDROID_SERIAL` is not set.
  - Prevents `more than one device/emulator` failures in single-device flows (`./wbeam android deploy`, `start-remote`).
  - Prints warning with selected serial and override hint.
- `e5d81d48` - `feat(versioning): unify build version across deploy lanes`
  - Added shared build version source file: `.wbeam_build_version` (ignored in git).
  - `wbeam` now generates a fresh build version for build actions and uses it as expected Android version.
  - `wbeam` now passes `-PWBEAM_BUILD_REV=<version>` to Gradle debug/release builds.
  - Android build stamp now records version together with host/pipeline config.
  - `android/app/build.gradle` now accepts `WBEAM_BUILD_REV` from Gradle/env as `versionName`.
  - `devtool` now uses the same build version flow for Android deploy/build variants.
  - Desktop Tauri backend now reads expected host/APK version from `.wbeam_build_version` (fallback when env is not set), so UI can show mismatch state reliably.
- `7f8bb6de` - `fix(host): handle adb reverse per-serial for multi-device setups`
  - Root cause fixed for multi-device ADB environments (`more than one emulator/device`).
  - Host reverse mapping now targets explicit serials instead of global `adb reverse`.
  - `wbeamd-core` now resolves connected serials from `adb devices` and applies reverse per device.
  - Honors `WBEAM_ANDROID_SERIAL` when explicitly provided (single-target override).
- `edf5ff33` - `feat(desktop-tauri): basic 400x800 device cards with service controls`
  - Added runtime title with host name (`WBeam - <hostname>`).
  - Added Basic/Advanced mode toggle (settings button placeholder).
  - Added dark theme as default for full dialog.
  - Added device cards with fields: model, platform, OS, API, current resolution, max resolution, battery (dynamic icon), APK installed/version, version match status.
  - Added form-factor detection (`Phone`/`Tablet`) and counts in status bar.
  - Added tooltips/text fallbacks for icon-driven rows.
  - Added service controls below list: Install, Uninstall, Start, Stop.
  - Added backend commands in Tauri Rust:
    - `host_name`
    - `service_status`
    - `service_install`
    - `service_uninstall`
    - `service_start`
    - `service_stop`
  - Added systemd user unit management for `wbeam-daemon` from desktop backend.
- `aecf9d14` - `docs: record latest pushed commits in progress ledger`
- `1060a3f5` - `chore: track logs directory sentinel`
- `8b8db0c8` - `docs: refresh progress.md to current baseline and verified state`
- `3d632425` - `refactor: drop service scripts and reset desktop-tauri to Solid/TS baseline`

## Runtime Verification (2026-03-07)
- `bash -n start-remote wbeam` -> OK
- `bash -n wbeam` -> OK
- `bash -n wbeam devtool` -> OK
- `timeout 220 env WBEAM_ANDROID_FORCE_INSTALL=1 ./wbeam android deploy-all` -> OK (`ok=2 failed=0`)
- `timeout 240 ./start-remote ppotepa` -> OK through host up + deploy + desktop launch path
- `cargo check --manifest-path src/host/rust/Cargo.toml -p wbeamd-core` -> OK
- `cd src/apps/desktop-tauri && npm run build` -> OK
- `cargo check --manifest-path src/apps/desktop-tauri/src-tauri/Cargo.toml` -> OK

## Current Start Commands
- Desktop GUI: `./devtool gui`
- Host debug: `./wbeam host debug`
- Android deploy: `./wbeam android deploy`
- Remote runner: `./start-remote [desktop_user]`

## Known Next Steps
- Wire real Advanced mode behavior (now: UI toggle placeholder).
- Connect APK host-version source to a canonical host pipeline/version endpoint.
- Expand platform probes beyond Android path (future iOS lane integration).
- Add service action feedback to command/event logs panel.

## In Progress (2026-03-07) - Multi-session groundwork (method #2)
- Added per-serial ADB reverse targeting in host core:
  - `wbeamd-core` now passes optional target serial to reverse refresh logic.
  - `infra/adb` now supports `target_serial` filtering (instead of always iterating all devices).
- Added session-aware runtime config path in core:
  - New constructor `DaemonCore::new_for_session(...)`.
  - Runtime config file can be isolated per session label: `runtime_state.<session>.json`.
- Added multi-session registry in `wbeamd-server`:
  - Server accepts `?serial=<adb_serial>` on session-bound endpoints (`status/health/presets/metrics/start/stop/apply/client-metrics` and `/v1/*` equivalents).
  - Creates one `DaemonCore` per serial on-demand.
  - Assigns dedicated stream ports per session (`base_stream_port + 1 + index`), while keeping default core for legacy no-serial calls.
  - Graceful shutdown now stops all session cores, not only the default one.
- Validation:
  - `cargo check -p wbeamd-server` -> OK.

## In Progress (2026-03-07) - Multi-session wiring (method #2) - extended
- Android build/runtime can now target a dedicated host session:
  - Added build fields: `WBEAM_ANDROID_SERIAL`, `WBEAM_CONTROL_PORT`, `WBEAM_STREAM_PORT`.
  - `HostApiClient` now appends `?serial=<...>&stream_port=<...>` to host API calls when serial is baked in.
  - Stream endpoint port is no longer hardcoded to `5000` in client runtime (`H264TcpPlayer`, `StreamService`, startup hints).
- `wbeam android deploy-all` now assigns deterministic per-device stream ports for parallel sessions:
  - Device #1 -> `5001`, device #2 -> `5002`, etc (base + index).
  - Each per-device build now passes serial + stream/control ports into Gradle build config.
  - ADB reverse setup now accepts explicit stream/control ports per device.
- Host session registry now supports explicit stream port request via query:
  - `?serial=<adb_serial>&stream_port=<port>` creates/resolves a matching daemon session.
  - Status payload now includes `stream_port`, `control_port`, and optional `target_serial`.
- Validation:
  - `bash -n wbeam` -> OK.
  - `cargo check` (host/rust workspace) -> OK.
  - `./wbeam android build` with custom `WBEAM_STREAM_PORT` -> OK.
  - `./wbeam android deploy-all` on 2 devices -> OK (`ok=2 failed=0`).
  - Isolated server test on `:5101` confirms per-serial session resolution and stream port reporting in `/v1/status`.

## In Progress (2026-03-07) - start-remote hardening
- `start-remote` now force-cleans stale desktop dev listeners before launching GUI:
  - kills stale listener on `tcp/1420` (Vite dev server),
  - kills stale `wbeam-desktop-tauri` user process by exact command name.
- Added `.gitignore` guard for accidental nested runtime log path:
  - `src/src/` (prevents noisy untracked runtime logs from debug runs).
- Re-validation (live):
  - `./start-remote ppotepa` now passes cleanup step and continues through:
    host build/start -> `android deploy-all` (2 devices OK) -> `version doctor` (both `match`) -> GUI launch.
  - Desktop launch no longer fails on `Error: Port 1420 is already in use`.

## In Progress (2026-03-07) - start-remote order + probes
- `start-remote` flow reordered to launch desktop before Android deploy:
  - host up in remote GUI session (`x11/wayland`) with detached start (`setsid + nohup`),
  - desktop GUI launch (background via `runas-remote`),
  - then APK uninstall + `android deploy-all`.
- Added startup observability:
  - ADB startup snapshot + post-deploy snapshot (single log file per run),
  - USB probing monitor based on `udevadm monitor --subsystem-match=usb` with PID tracking (`logs/udev-monitor.pid`),
  - run summary prints paths to `desktop`, `adb`, `udev` logs.
- Fixed ADB snapshot parser:
  - robust per-serial loop (`mapfile` from `adb devices`) to avoid mixed-output API parsing.
- Validation:
  - `bash -n start-remote` -> OK
  - full `./start-remote ppotepa` -> OK (`deploy-all ok=2 failed=0`, host `supported=true`, versions `match` on both devices)
  - follow-up `./wbeam version doctor` after run confirms host remains reachable.

## In Progress (2026-03-07) - live diagnostics watch modes
- Added `wbeam watch` group with 1s refresh loops for quick live diagnostics:
  - `./wbeam watch devices`
  - `./wbeam watch connections`
  - `./wbeam watch status`
  - `./wbeam watch health`
  - `./wbeam watch doctor`
- Optional watch log generation:
  - `WBEAM_WATCH_LOG=1 ./wbeam watch <topic>` writes to `logs/<ts>.watch-<topic>.<run>.log`.
  - refresh interval override: `WBEAM_WATCH_INTERVAL=<seconds>`.
- Validation:
  - `bash -n wbeam` -> OK
  - `timeout 3 ./wbeam watch devices` -> OK
  - `timeout 3 ./wbeam watch connections` -> OK

## In Progress (2026-03-07) - watch UX improvements (change-only + colors)
- Enhanced `wbeam watch` output:
  - ANSI colorized headers/status (auto-enabled on TTY, disabled with `NO_COLOR=1`).
  - Screen redraw happens only when snapshot content changes.
  - If no change, watch prints compact `no changes` heartbeat line.
- Added one-shot mode for native Linux `watch` integration:
  - `./wbeam watch <topic> --once`
  - Example: `watch -n 1 -d --color './wbeam watch connections --once'`
- Normalized volatile host JSON fields in `watch connections`:
  - strips `uptime` so unchanged state does not trigger constant redraw.
- Avoided per-second version doctor log spam in watch mode:
  - `watch doctor` now uses lightweight snapshot function (no file write each tick).

## In Progress (2026-03-07) - human-readable watch formatting
- Replaced raw JSON in watch screens with concise key diagnostics:
  - host/service/build/state,
  - host support/capture/session/remote/display,
  - endpoint HTTP health,
  - ADB reverse summary per serial.
- Added severity coloring in watch values (`ok/warn/error`) and compact device rows.
- Validation:
  - `bash -n wbeam` -> OK
  - `./wbeam watch connections --once` -> structured summary output
  - `./wbeam watch health --once` -> endpoint + host summary output
  - `./wbeam watch devices --once` -> compact per-device rows

## In Progress (2026-03-07) - watch devices/streaming extensions
- `watch devices` now includes:
  - per-device assigned stream `port`,
  - `max_res` (wm/dumpsys fallback),
  - `streaming` state from session-aware host status.
- Added new `watch streaming` view:
  - per-device table with `state`, `profile`, `target/recv/present/decode fps`,
  - configured `kbps`, `encoder`, and reverse tunnel status (`yes/no`).
- Validation:
  - `./wbeam watch devices --once` -> shows port/max_res/streaming
  - `./wbeam watch streaming --once` -> shows fps/profile/connection summary

## In Progress (2026-03-07) - versioning format unification (`0.1.x+build`)
- Removed legacy `0.0.` prefixing from runtime/deploy path.
- New generated build format in tooling:
  - `wbeam version new` now emits `0.1.<n>+build`.
  - runtime fallback now also uses `+build` suffix.
- Host runner no longer rewrites build revision to `0.0.*`:
  - `src/host/scripts/run_wbeamd.sh` keeps `WBEAM_BUILD_REV` as-is.
- Android build config no longer rewrites to `0.0.*`:
  - `android/app/build.gradle` uses provided `WBEAM_BUILD_REV` directly.
  - fallback defaults to `0.1.0+<git-short>`.
- Rust default fallback revision updated:
  - `wbeamd-core` default changed from `0.0.dev0-build` to `0.1.0+dev`.
- Validation:
  - `bash -n wbeam` and `bash -n src/host/scripts/run_wbeamd.sh` -> OK
  - `./wbeam version new` / `./wbeam version current` -> `0.1.14+build`

## In Progress (2026-03-07) - desktop single-instance + mismatch regression fix
- Desktop Tauri now enforces single-instance behavior:
  - added `tauri-plugin-single-instance`,
  - second launch focuses/restores existing `main` window instead of opening duplicate.
- Fixed host build revision source to avoid stale compile-time mismatch:
  - `wbeamd-core::build_revision()` now prefers runtime env `WBEAM_BUILD_REV` first,
  - compile-time `option_env!` remains fallback only.
- Verified `start-remote` end-to-end after version-format changes:
  - build/deploy uses `0.1.16+build`,
  - `version doctor` reports `host build_revision=0.1.16+build`,
  - both devices report `apk=0.1.16+build` and `result=match`.

## In Progress (2026-03-07) - version metadata suffix cleanup
- Replaced ambiguous suffix `+build` with explicit numeric metadata `+b<buildno>`.
- Current generated format:
  - `0.1.<n>+b<n>` (example: `0.1.17+b17`).
- Validation:
  - `./wbeam version new` and `./wbeam version current` return `0.1.17+b17`.

## In Progress (2026-03-07) - service-first UX and handshake diagnostics
- `start-remote` now deploys Android APKs without auto-launch:
  - `WBEAM_ANDROID_SKIP_LAUNCH=1` wired for `android deploy-all`.
  - prevents immediate red handshake screen before user installs/starts desktop service.
- Desktop GUI improvements (Tauri):
  - version status is now service-aware:
    - `Install desktop service first` / `Start desktop service to verify` (instead of immediate mismatch),
    - mismatch shown only when service is active.
  - added host fingerprint probe in status bar:
    - `os/session/desktop/capture_mode/supported`.
- Android overlay messaging improved:
  - control-link/stream hints now explicitly mention checking desktop service status.
- Validation:
  - full `./start-remote ppotepa` -> deploy `ok=2 failed=0`, both devices `OK (launch skipped)`,
  - `version doctor` -> host/APK match on both devices (`0.1.19+b19`).

## In Progress (2026-03-07) - strict service-owned probing contract
- Enforced "service-first" contract in desktop Tauri backend:
  - `list_devices_basic` now returns immediately with empty list and does **not** run ADB when service is unavailable, not installed, or inactive.
  - This moves probing responsibility to the host service lifecycle only.
- Updated GUI empty-state message for this contract:
  - when service is inactive: `Probing paused until desktop service is running.`
  - when service is active and no devices: `No connected ADB devices.`
- Validation:
  - `cargo check --manifest-path src/apps/desktop-tauri/src-tauri/Cargo.toml` -> OK
  - `cd src/apps/desktop-tauri && npm run build` -> OK

## In Progress (2026-03-07) - version format switch to `1.<build>.<hash>`
- Unified local build version shape to:
  - `1.<build_number>.<hash8>`
  - example: `1.21.0d2084b0`
- Updated generator in `wbeam`:
  - `version new` now increments `.wbeam_buildno` and emits random 8-hex suffix.
  - runtime fallback without explicit version now uses `1.<build>.dev`.
- Updated fallback defaults in code paths:
  - Android Gradle fallback -> `1.0.<git-short>`
  - host Rust fallback -> `1.0.dev`
- Validation:
  - `bash -n wbeam` -> OK
  - `./wbeam version new` / `./wbeam version current` -> `1.21.0d2084b0`

## In Progress (2026-03-07) - watch service/logs + interactive TUI screens
- Extended `wbeam watch` topics:
  - new: `service`, `logs`
  - existing unchanged: `devices`, `connections`, `streaming`, `status`, `health`, `doctor`
- Added interactive `./wbeam watch tui` mode:
  - screen switching with keys: `1..5`
  - `1=devices`, `2=connections`, `3=streaming`, `4=service`, `5=logs`
  - `r` refresh, `q` quit
  - bottom navigation/status bar rendered every tick.
- `watch service` output includes:
  - user service unit state (`installed`, `active`, `enabled`, `substate`, `main_pid`, `uptime`)
  - runtime host/probe summary (`build_revision`, `supported`, `capture`, `session`, `display`)
  - per-device activity snapshot (`serial`, `port`, `state`)
- `watch logs` output includes:
  - latest host log tail,
  - latest adb log tail,
  - recent `journalctl --user -u wbeam-daemon` tail.
- Validation:
  - `bash -n wbeam` -> OK
  - `./wbeam watch service --once` -> structured service screen
  - `./wbeam watch logs --once` -> combined host/adb/service logs
  - `printf 'q' | ./wbeam watch tui` -> TUI renders and exits

## In Progress (2026-03-07) - service alert severity + auto-refresh probing
- Desktop Tauri service alert now has severity variants:
  - `missing service` -> red critical card
  - `installed but stopped` -> amber warning card
- Added color status indicator badge in footer:
  - `running` (green), `stopped` (amber), `not installed` (red).
- Added automatic refresh loop (1s):
  - refreshes service/probe continuously,
  - fetches devices only when service is active,
  - updates list automatically on USB plug/unplug without manual refresh.
- Validation:
  - `cd src/apps/desktop-tauri && npm run build` -> OK

## In Progress (2026-03-07) - lock-conflict diagnostics in GUI service alert
- Root-cause visibility for empty ADB list after "service start":
  - when service is inactive and `/tmp/wbeamd.lock` is held by a live `wbeamd-server` PID,
  - backend now appends lock hint to `service_status.summary`.
- GUI shows this hint directly in the service alert card (monospace small line),
  so user can immediately see lock conflict without opening journal logs.
- Validation:
  - `cargo check --manifest-path src/apps/desktop-tauri/src-tauri/Cargo.toml` -> OK
  - `cd src/apps/desktop-tauri && npm run build` -> OK

## In Progress (2026-03-07) - auto-resolve daemon lock conflict on service start
- Added conflict cleanup in desktop-tauri backend `service_start`:
  - before `systemctl --user start`, app checks `/tmp/wbeamd.lock`,
  - if lock PID is a live `wbeamd-server`, sends `TERM` then `KILL` fallback,
  - removes stale lock file once process exits.
- Goal:
  - prevent `wbeam-daemon` restart loop (`single-instance lock already held`) when debug daemon was started manually.
- Validation:
  - `cargo check --manifest-path src/apps/desktop-tauri/src-tauri/Cargo.toml` -> OK
  - `cd src/apps/desktop-tauri && npm run build` -> OK

## In Progress (2026-03-07) - unified version scheme + bottom-only service status
- Finalized version tuple format:
  - `0.major=0`, `minor=1`, `hotfix=0`, `buildnumber`, `hash`
  - concrete shape: `0.1.0.<build>.<hash>`
  - example generated: `0.1.0.25.6047e698`
- Single-source-of-truth direction hardened:
  - build version file `.wbeam_build_version` is now read by host runtime via `WBEAM_ROOT` fallback when `WBEAM_BUILD_REV` is absent.
  - GUI-managed systemd unit now exports `Environment=WBEAM_ROOT=<repo-root>`.
  - Android fallback version shape aligned to `0.1.0.0.<git-short>`.
- UI layout update:
  - removed standalone service alert block from content area,
  - service status is now represented in a full-width bottom status strip (`width: 100%`) with state coloring.
- Validation:
  - `bash -n wbeam` -> OK
  - `./wbeam version new` / `current` -> `0.1.0.25.6047e698`
  - `cargo check --manifest-path src/apps/desktop-tauri/src-tauri/Cargo.toml` -> OK
  - `cd src/apps/desktop-tauri && npm run build` -> OK

## In Progress (2026-03-07) - status bar dedupe + UI version SSOT order
- Removed duplicated service state rendering in footer:
  - kept single full-width status strip only,
  - removed extra service badge line.
- Desktop Tauri version source order adjusted for mismatch reduction:
  - `hostExpectedApkVersion` now prefers `.wbeam_build_version` first (single source for UI),
  - then `WBEAM_HOST_APK_VERSION`,
  - then daemon `/health` `build_revision`.
- Validation:
  - `cargo check --manifest-path src/apps/desktop-tauri/src-tauri/Cargo.toml` -> OK
  - `cd src/apps/desktop-tauri && npm run build` -> OK

## In Progress (2026-03-07) - fix host build revision corruption (`0.0.*-build`)
- Root cause of persistent mismatch identified in Rust build script:
  - `src/host/rust/crates/wbeamd-core/build.rs` was force-normalizing any revision to `0.0.<rev>-build`.
- Fixed by pass-through strategy:
  - `WBEAM_BUILD_REV` is now emitted unchanged into build env.
  - fallback format aligned to project scheme: `0.1.0.0.<git-short>`.
- Effect:
  - host daemon `/health.build_revision` no longer gets rewritten to legacy `0.0.*-build`.
- Validation:
  - `cargo check --manifest-path src/host/rust/Cargo.toml -p wbeamd-core -p wbeamd-server` -> OK

## In Progress (2026-03-07) - per-device tile actions + daemon-based version gating
- Desktop Tauri device tiles now include per-device action footer:
  - `Refresh`, `Connect`, `Disconnect` buttons.
- Added backend commands:
  - `device_connect(serial, stream_port)` -> POST `/v1/start`
  - `device_disconnect(serial, stream_port)` -> POST `/v1/stop`
- Device data now includes:
  - `stream_port` (session-assigned),
  - `stream_state` (from `/v1/status`),
  - `apk_matches_daemon` (APK compared to daemon `build_revision`).
- Version gating in GUI updated:
  - connect/mismatch logic now checks `APK vs daemon` compatibility (not GUI build).
- Validation:
  - `cargo check --manifest-path src/apps/desktop-tauri/src-tauri/Cargo.toml` -> OK
  - `cd src/apps/desktop-tauri && npm run build` -> OK

## In Progress (2026-03-07) - device tile UX polish (disabled reasons + update wording)
- Device action buttons now expose explicit disabled reasons via tooltips:
  - service inactive,
  - APK missing,
  - APK/daemon version mismatch,
  - already connecting/streaming,
  - another action in progress.
- Version badge wording changed from generic mismatch to user action:
  - `Update required` (when APK != daemon).
- Validation:
  - `cd src/apps/desktop-tauri && npm run build` -> OK

## In Progress (2026-03-07) - start-remote mode polish for faster loops
- `start-remote` now supports explicit startup modes for faster iteration:
  - `--quick` (default): no host rebuild, no Android deploy,
  - `--redeploy`: full host rebuild + force APK deploy-all,
  - `--rebuild-host`: rebuild host only,
  - `--no-host-restart`: keep current daemon process and relaunch desktop side only.
- Improved usage/help output and final mode summary line.
- Validation:
  - `bash -n start-remote` -> OK
  - `./start-remote --help` -> shows new mode flags

## In Progress (2026-03-07) - frontend managers for session/connection state
- Introduced manager layer in desktop frontend:
  - `HostApiManager` for host command/invoke calls and error normalization,
  - `SessionManager` for component/session state, polling, device actions and service actions.
- Refactored `App.tsx` to use managers instead of inline imperative state logic.
- Improved connect/disconnect diagnostics from Tauri backend:
  - `device_connect`/`device_disconnect` now parse daemon HTTP status explicitly,
  - avoid raw opaque curl failures in UI and return clearer action errors.
- Validation:
  - `cd src/apps/desktop-tauri && npm run build` -> OK
  - `cargo check --manifest-path src/apps/desktop-tauri/src-tauri/Cargo.toml` -> OK

## In Progress (2026-03-07) - connect reliability + button interactivity polish
- Stream connect reliability improvements:
  - `wbeam android deploy-all` now writes persistent serial->stream_port map to `.wbeam_device_ports`.
  - desktop-tauri backend resolves per-device stream port from this map (fallback to index-based ports).
  - `device_connect` now runs ADB preflight (`wait-for-device`, reverse control/stream ports, app launch) before daemon start.
- UI interaction polish:
  - stronger hover/active feedback for device action buttons,
  - per-button busy indication with spinner and contextual labels (`Connecting...`, `Stopping...`).
- Runtime artifacts:
  - `.wbeam_device_ports` added to `.gitignore`.
- Validation:
  - `cd src/apps/desktop-tauri && npm run build` -> OK
  - `cargo check --manifest-path src/apps/desktop-tauri/src-tauri/Cargo.toml` -> OK
  - `bash -n wbeam start-remote runas-remote` -> OK

## In Progress (2026-03-08) [commit: 87ec6e55] - virtual desktop deps flow (Linux-first)
- Added host dependency probe script for virtual desktop prerequisites:
  - `scripts/virtual-deps-check.sh`
  - detects platform + package manager and checks required binaries (`Xvfb`, `xrandr`),
  - supports machine-readable output with `--json`,
  - prints manager-specific install command hint.
- Added installer script:
  - `scripts/virtual-deps-install.sh`
  - supports `--dry-run`, `--yes`,
  - uses `sudo` when needed,
  - installs deps via detected package manager (`apt/dnf/yum/zypper/pacman`).
- Wired both scripts into main entrypoint `./wbeam`:
  - `./wbeam deps virtual check [--json]`
  - `./wbeam deps virtual install [--dry-run] [--yes]`
- Windows path intentionally returns `not implemented` for now (explicit exit code 2) to keep scope Linux-first.
- Validation:
  - `bash -n wbeam scripts/virtual-deps-check.sh scripts/virtual-deps-install.sh` -> OK
  - `./wbeam deps virtual check --json` -> OK
  - `./wbeam deps virtual check` -> OK
  - `./wbeam deps virtual install --dry-run --yes` -> OK

## In Progress (2026-03-08) [commit: a606a78f] - startup virtual setup modal UX hardening
- Updated startup "Virtual desktop setup" modal actions to match strict flow:
  - removed `Later` and `Use duplicate`,
  - now only `Install deps` and `Cancel`.
- Added busy state for dependency installation in modal:
  - `Installing...` with spinner,
  - buttons disabled while install is in progress.
- Added Tauri backend command `virtual_install_deps`:
  - executes `./wbeam deps virtual install --yes` from repo root,
  - returns stdout/stderr error details to UI.
- Updated connect flow guard for `virtual` mode:
  - no automatic fallback confirm to duplicate,
  - when doctor fails, UI opens setup modal and reports actionable error.
- Validation:
  - `cd src/apps/desktop-tauri && npm run build` -> OK
  - `cargo check --manifest-path src/apps/desktop-tauri/src-tauri/Cargo.toml` -> OK

## In Progress (2026-03-08) [commit: f53edc47] - privileged deps install flow with progress modal
- Added dedicated install progress modal for Virtual Desktop dependencies:
  - opens after `Install deps`,
  - displays live installer terminal output,
  - shows busy/progress state and final success/failure message.
- Startup setup modal behavior:
  - still only `Install deps` + `Cancel`,
  - while install is running, action buttons are disabled.
- Added async installer job in Tauri backend:
  - `virtual_install_deps_start` starts background install job,
  - `virtual_install_deps_status` returns live state (`running/done/success/message/logs`).
- Elevation handling is explicit:
  - if already root -> runs installer directly,
  - otherwise requires `pkexec` (polkit prompt) for privilege escalation,
  - if `pkexec` missing -> friendly error explaining root/elevation requirement.
- Validation:
  - `cd src/apps/desktop-tauri && npm run build` -> OK
  - `cargo check --manifest-path src/apps/desktop-tauri/src-tauri/Cargo.toml` -> OK

## In Progress (2026-03-08) [commit: 4e8e356f] - virtual size override + watch logs multi-source snapshot
- Virtual mode startup now prefers target device resolution for stream size:
  - host resolves ADB `wm size` for target serial,
  - when available, runtime `cfg.size` is overridden to match the connected device.
- Added extra virtual startup diagnostics in host core:
  - logs when size override happens (from/to + serial),
  - logs when Xvfb display is spawned (DISPLAY, PID, size),
  - logs warning when device resolution cannot be detected.
- `./wbeam watch logs` improved for investigation workflow:
  - detects latest numeric run-id from log files with run suffix,
  - shows logs from all available sources for that run,
  - auto-fallback to `latest-per-domain` when run-id spans stale files across time windows,
  - used by both direct `watch logs` and TUI logs screen.
- Validation:
  - `bash -n wbeam` -> OK
  - `cargo check --manifest-path src/host/rust/Cargo.toml -p wbeamd-core` -> OK
  - `./wbeam watch logs --once` -> shows multi-source snapshot (host/adb/desktop/udev/version + service journal)

## In Progress (2026-03-08) [commit: 2723a9b5] - x11 extend capability probe + virtual mode truthfulness
- Added dedicated X11 extend capability probe:
  - new module `infra/x11_extend.rs`,
  - validates `DISPLAY`, `xrandr` presence, RandR version >= 1.5, connected outputs,
  - marks typical remote RDP/xrdp X11 sessions as non-extend-capable.
- Updated virtual backend resolution semantics in `virtual_probe`:
  - `linux_x11_randr_extend` is reported as supported only when extend probe passes,
  - `linux_x11_xvfb_fallback` is no longer advertised as true additional monitor support,
  - fallback hint now explicitly states it is isolated Xvfb space, not KDE monitor extension.
- Updated desktop connect UX for virtual mode:
  - actionable=false doctor failures no longer open install-deps modal,
  - UI shows direct reason/hint (instead of misleading "missing deps" path).
- Result:
  - prevents silent routing into black-screen Xvfb path when user expects real desktop extension.
- Validation:
  - `cargo check --manifest-path src/host/rust/Cargo.toml -p wbeamd-core` -> OK
  - `cargo check --manifest-path src/apps/desktop-tauri/src-tauri/Cargo.toml` -> OK
  - `cd src/apps/desktop-tauri && npm run build` -> OK

## In Progress (2026-03-08) [commit: b9018545] - session-scope start flow (local vs remote)
- Added session scope filtering for remote launcher:
  - `runas-remote` now supports `RUNAS_REMOTE_SESSION_REMOTE=any|no|yes`,
  - session selection uses loginctl `Remote=` property filter.
- Extended `start-remote` with explicit scope flags:
  - `--local-session` (real machine seat, `Remote=no`),
  - `--remote-session` (`Remote=yes`),
  - aliases accepted: `--actual-session`, `--real-machine`.
- Added convenience wrapper:
  - `./start-local-session` -> delegates to `./start-remote --local-session`.
- Validation:
  - `bash -n runas-remote start-remote start-local-session` -> OK
  - `./start-remote --help` -> shows new session flags
  - `./runas-remote --help` -> shows session remote filter env

## In Progress (2026-03-08) [commit: 1b77727b] - runas session selection robustness
- Improved `runas-remote` graphical session selection:
  - still prefers strict `Active=yes && State=active`,
  - now falls back to `State=active|online` graphical sessions when strict match is unavailable.
- This fixes cases where real KDE session exists but is not marked as `Active=yes` (common in remote-control scenarios).
- Validation:
  - `bash -n runas-remote` -> OK

## In Progress (2026-03-08) [commit: 14446f26] - desktop launcher split (`desktop.sh`)
- Reintroduced dedicated desktop GUI launcher:
  - new `./desktop.sh` as single-purpose desktop app entrypoint,
  - supports `--release` mode for local release run.
- Responsibility split restored:
  - `devtool` remains build/deploy utility,
  - GUI startup path delegated to `desktop.sh` (including `devtool gui`).
- Remote flow updated:
  - `start-remote` now launches desktop via `./desktop.sh` (not `devtool`),
  - `runas-remote` default app changed to `./desktop.sh`.
- Validation:
  - `bash -n desktop.sh devtool runas-remote start-remote` -> OK
  - `./desktop.sh --help` -> OK
  - `./runas-remote --help` -> shows default app `./desktop.sh`

## In Progress (2026-03-08) [commit: 36330cbc] - start-remote CLI unification
- Simplified remote start interface to one script + two primary flags:
  - `./start-remote --local <user>`
  - `./start-remote --rdp <user>`
- Default session scope changed to local machine seat (`--local` behavior).
- Kept compatibility aliases for older flows:
  - `--local-session`, `--remote-session`, `--actual-session`, `--real-machine`.
- Removed wrapper script `start-local-session` to keep one canonical entrypoint.
- Validation:
  - `bash -n start-remote runas-remote` -> OK
  - `./start-remote --help` -> shows unified interface

## In Progress (2026-03-08) [commit: 10527086] - precise session targeting (sid/display)
- Added explicit session targeting controls to avoid launching on wrong desktop:
  - `runas-remote --list-sessions [user]` for quick session inventory,
  - `RUNAS_REMOTE_SESSION_ID=<sid>` to force exact loginctl session,
  - `RUNAS_REMOTE_DISPLAY=:N` to force/prefer exact DISPLAY.
- Extended `start-remote` passthrough flags:
  - `--session-id SID`
  - `--display :N`
  - these are forwarded to `runas-remote` selection env.
- Session selection strategy in `runas-remote` now prefers:
  - strict active graphical session,
  - then graphical fallback with display/state match (`active|online`).
- Validation:
  - `bash -n runas-remote start-remote` -> OK
  - `./start-remote --help` -> shows `--session-id` and `--display`
  - `./runas-remote --list-sessions <user>` -> prints session table (when loginctl bus access is available)

## In Progress (2026-03-08) [commit: f2a47ba3] - auto local GUI detection hardening
- Improved no-`sid` auto-detection for local mode:
  - when `loginctl` `Remote=` is empty/unknown (`""`, `-`, `unknown`), it is treated as local for `--local` scope.
- Applied in both:
  - `runas-remote` session filter
  - `start-remote` active-user resolver filter
- Result:
  - `./start-remote --local <user>` is now more likely to auto-pick real GUI session without manual `--session-id`.
- Validation:
  - `bash -n runas-remote start-remote` -> OK

## In Progress (2026-03-08) [commit: 6cfa118e] - session selection UX fixes (`--list-sessions`, stale env guard)
- Fixed `start-remote --list-sessions` command path:
  - handled before general argument parsing so it no longer falls through to unknown-flag error.
- Fixed stale session override inheritance:
  - `start-remote` now always sets `RUNAS_REMOTE_SESSION_ID` and `RUNAS_REMOTE_DISPLAY` explicitly when calling `runas-remote`,
  - prevents accidental reuse of old exported values from parent shell.
- Added manual-display fallback in `runas-remote`:
  - when no loginctl graphical session is found but `RUNAS_REMOTE_DISPLAY=:N` is provided, launcher can proceed in manual mode.
- Validation:
  - `bash -n start-remote runas-remote` -> OK

## In Progress (2026-03-09) [commit: 409789cc] - backend split per platform + mode
- Refactored display backend layout to explicit platform folders:
  - `display_backends/x11/`
  - `display_backends/wayland/`
  - `display_backends/windows/`
- Split mode responsibilities into separate modules in each platform:
  - `duplicate`
  - `virtual_monitor`
  - (X11 additionally keeps `virtual_isolated` as compatibility mode)
- Kept common routing contract unchanged in `display_backends/mod.rs`:
  - platform selection by host probe
  - mode normalization
  - unified activation/probe return types
- Validation:
  - `cargo check -p wbeamd-core -p wbeamd-server` -> OK

## In Progress (2026-03-09) [commit: 664cb92e] - add Linux platform layer in backend tree
- Restructured backend modules to enforce hierarchy:
  - `display_backends/mod.rs` (host router)
  - `display_backends/linux/mod.rs` (linux router by session type)
  - `display_backends/linux/x11/*` and `display_backends/linux/wayland/*` (platform-mode implementations)
  - `display_backends/windows/*` (platform-mode implementations)
- Kept mode boundary explicit inside each backend (`duplicate`, `virtual_monitor`, and `x11/virtual_isolated` compatibility mode).
- Validation:
  - `cargo check -p wbeamd-core -p wbeamd-server` -> OK

## In Progress (2026-03-09) [commit: abe29ade] - virtual stream startup diagnostics and X11 capture fix
- Diagnosed real startup failure from host status/logs:
  - `stream start aborted (code=101): property 'startx' of type 'GstXImageSrc' can't be set from the given type (expected: 'guint', got: 'gint')`
- Fixed Rust streamer X11 source property types:
  - `ximagesrc` `startx/starty/endx/endy` now set as `u32` (with normalization from env values).
- Added stronger X11 virtual monitor validation:
  - if enabled output geometry mirrors another active output exactly, backend now returns a clear error (`extended desktop not active`) instead of proceeding silently.
- Added X11 virtual monitor activation log with output/geometry for easier diagnosis.
- Improved Android startup step-3 messaging:
  - removed hardcoded `./devtool ip up` hint,
  - shows clearer ADB reverse/LAN guidance,
  - surfaces compact host `last_error`,
  - shows explicit `host stream start failed` when daemon reports stream start abort.
- Validation:
  - `cargo check -p wbeamd-streamer -p wbeamd-core -p wbeamd-server` -> OK
  - Android Gradle compile check blocked by local Gradle runtime issue:
    `Could not determine a usable wildcard IP for this machine`.

## In Progress (2026-03-09) [commit: a0af30ab] - connect-mode modal reliability in desktop UI
- Addressed regression where users could miss mode selection prompt before connect:
  - `Connect` now opens mode dialog even when connect preconditions are currently blocked.
  - Dialog shows explicit blocking reason (`Connect blocked: ...`) and disables confirm until preconditions are met.
- Kept virtual/isolated/duplicate mode selection path unchanged; connect still goes through explicit mode validation.
- Validation:
  - `cd src/apps/desktop-tauri && npm run build` -> OK

## Backfill (2026-03-08) [commit: 59fac9a4] - docs hash sync for sid/display targeting
- Recorded progress hash for remote session sid/display targeting milestone.

## Backfill (2026-03-08) [commit: 39876079] - docs hash sync for local auto-detection hardening
- Recorded progress hash for local GUI auto-detection hardening milestone.

## Backfill (2026-03-08) [commit: 1aec1f09] - docs hash sync for session UX fixes
- Recorded progress hash for session UX fixes milestone.

## Backfill (2026-03-09) [commit: e24e789f] - cleanup accidental script artifacts
- Removed unintended helper/script files accidentally added during troubleshooting.

## Backfill (2026-03-09) [commit: e51d8854] - docs update for X11 virtual monitor investigation
- Synced progress notes for X11 virtual monitor investigation milestone.

## Backfill (2026-03-09) [commit: 1b6c9aca] - x265 key-int-max crash and startup error visibility
- Fixed x265 `key-int-max` crash path and surfaced stream startup errors in diagnostics.

## Backfill (2026-03-09) [commit: 0030d0fb] - docs update for backend split
- Synced progress notes for backend split work.

## Backfill (2026-03-09) [commit: 9799b75e] - display backend activation logging
- Added backend activation logs emitted at stream start.

## Backfill (2026-03-09) [commit: c19e0335] - docs update for platform/mode backend split
- Synced progress notes for backend platform/mode split.

## Backfill (2026-03-09) [commit: b56b0f82] - docs update for linux backend layer refactor
- Synced progress notes for linux backend layer refactor.

## Backfill (2026-03-09) [commit: 577e6b4c] - docs for X11 virtual stream diagnosis
- Synced progress notes for X11 capture/type fix diagnostics.

## Backfill (2026-03-09) [commit: c28c9711] - docs for connect-mode modal reliability
- Synced progress notes for connect-mode modal reliability fix.

## Backfill (2026-03-10) [commit: 3cf36804] - separate `proto_x11` virtual-desktop prototype lane
- Added independent `proto_x11` lane for focused X11 virtual output prototyping.

## Backfill (2026-03-10) [commit: f96b09b6] - proto_x11 deploy/start hardening and X11 auth selection
- Hardened `proto_x11` deploy/start orchestration and improved X11 auth candidate selection.

## Backfill (2026-03-10) [commit: 83c36bca] - initial env-to-conf move in proto/domain
- Replaced selected env toggles with policy/config-file driven controls.

## In Progress (2026-03-10) [commit: 4ade624e] - main-lane config-first runtime wiring (no `proto_x11`)
- Added shared config defaults file:
  - `config/wbeam.conf`
- Added shared shell loader:
  - `src/host/scripts/wbeam_config.sh`
  - supports `WBEAM_CONFIG_FILE`, user config (`~/.config/wbeam/wbeam.conf`), repo local (`.wbeam.conf`), repo defaults (`config/wbeam.conf`)
  - keeps ENV override compatibility while moving defaults to conf.
- Wired main scripts to load config defaults before runtime variable resolution:
  - `wbeam`, `devtool`, `desktop.sh`, `start-remote`, `runas-remote`, `redeploy-local`, `src/host/scripts/run_wbeamd.sh`, `src/host/scripts/run_wbeamd_debug.sh`
- Wired Tauri desktop backend to read `WBEAM_*` from config helper instead of direct env-only lookups:
  - control/stream port resolution
  - monitor-object allow flag
  - daemon binary override
  - generated systemd unit now points to `WBEAM_CONFIG_FILE=<repo>/config/wbeam.conf`.
- Wired host Rust core to use config-first settings for daemon behavior:
  - `WBEAM_ALLOW_LIVE_ADAPTIVE_RESTART`
  - `WBEAM_START_TIMEOUT_SEC`
  - `WBEAM_USE_RUST_STREAMER`
  - `WBEAM_RUST_STREAMER_BIN`
- Validation:
  - `bash -n wbeam devtool start-remote runas-remote redeploy-local desktop.sh src/host/scripts/run_wbeamd.sh src/host/scripts/run_wbeamd_debug.sh src/host/scripts/wbeam_config.sh` -> OK
  - `cd src/host/rust && cargo check -p wbeamd-core` -> OK
  - `cd src/apps/desktop-tauri/src-tauri && cargo check` -> OK

## In Progress (2026-03-10) [commit: d6cf5644] - sectioned `wbeam.conf` layout + x11 section
- Converted `config/wbeam.conf` to INI-like sectioned format (`[service]`, `[android]`, `[version]`, `[x11]`).
- Kept runtime keys as `WBEAM_*` to preserve compatibility with existing loaders.
- Added `x11` section with `WBEAM_X11_ALLOW_MONITOR_OBJECT` and placeholders for future policy unification.
- Confirmed shell loader accepts section headers and still reads values:
  - `source src/host/scripts/wbeam_config.sh && wbeam_load_config ...` -> `control=5001 x11_allow=0`

## In Progress (2026-03-10) [commit: adb5883b] - bootstrap user config + user config as runtime source-of-truth
- Updated runtime loaders to treat `~/.config/wbeam/wbeam.conf` as canonical source-of-truth.
- Added startup bootstrap:
  - if user config is missing, create `~/.config/wbeam/` and copy from `config/wbeam.conf`.
- Applied in:
  - shell loader (`src/host/scripts/wbeam_config.sh`)
  - desktop tauri backend config cache (`src/apps/desktop-tauri/src-tauri/src/main.rs`)
  - host rust core config loader (`src/host/rust/crates/wbeamd-core/src/lib.rs`)
- Simplified service unit generation:
  - removed hard wiring of `WBEAM_CONFIG_FILE=<repo>/config/wbeam.conf`.
- Validation:
  - `bash -n src/host/scripts/wbeam_config.sh wbeam devtool desktop.sh start-remote runas-remote redeploy-local src/host/scripts/run_wbeamd.sh src/host/scripts/run_wbeamd_debug.sh` -> OK
  - `cd src/host/rust && cargo check -p wbeamd-core` -> OK
  - `cd src/apps/desktop-tauri/src-tauri && cargo check` -> OK
  - bootstrap smoke: `XDG_CONFIG_HOME=$(mktemp -d) ... wbeam_load_config ...` creates `wbeam/wbeam.conf`

## In Progress (2026-03-10) [commit: 54b8daa8] - per-device runtime profile persistence in user config dir
- Moved per-session runtime-state storage to user config space (`~/.config/wbeam`) instead of repo tree.
- Serial-bound sessions now persist at:
  - `~/.config/wbeam/devices/<serial>.json`
- Added first-run persistence:
  - if device runtime config file does not exist, daemon now writes initial default config immediately.
- Kept fallback for non-serial sessions:
  - `runtime_state.<label>.json` / `runtime_state.json` in the same user config base.
- Validation:
  - `cd src/host/rust && cargo check -p wbeamd-core` -> OK
  - `cd src/host/rust && cargo check -p wbeamd-server` -> OK

## In Progress (2026-03-10) [commit: 3b805459] - X11 startup warning as separate window (outside main webview)
- Added startup detection for host session type in desktop Tauri backend.
- On X11 startup, app now opens a separate warning window (not in-app modal overlay):
  - states that X11 virtual desktop path is experimental / not fully supported,
  - recommends Wayland for reliability.
- Added static notice page for dedicated window:
  - `src/apps/desktop-tauri/public/x11-warning.html`
- Added config gate:
  - `WBEAM_X11_STARTUP_NOTICE` (default enabled).
- Validation:
  - `cd src/apps/desktop-tauri/src-tauri && cargo check` -> OK
  - `cd src/apps/desktop-tauri && npm run build` -> OK

## In Progress (2026-03-10) [commit: 1c030d4a] - relax desktop Node version gate for newer runtimes
- Updated `desktop.sh` Node check:
  - keeps hard fail for Node `<18`,
  - allows Node `>22` with warning (instead of blocking),
  - optional strict mode via `WBEAM_DESKTOP_STRICT_NODE=1`.
- Validation:
  - `bash -n desktop.sh` -> OK
  - `./desktop.sh --help` -> OK

## In Progress (2026-03-10) [commit: a44db282] - desktop launcher auto-reexec into graphical session
- Hardened `desktop.sh` against GTK init failures from tty shells:
  - detects missing graphical env (`DISPLAY`/`WAYLAND_DISPLAY`) or `XDG_SESSION_TYPE=tty`,
  - auto re-launches via `runas-remote` into active GUI session (default enabled),
  - loop guard via `WBEAM_DESKTOP_REEXEC=1`.
- Added control flag:
  - `WBEAM_DESKTOP_AUTO_REEXEC=0` to disable auto-reexec and fail fast with actionable message.
- Validation:
  - `bash -n desktop.sh` -> OK
  - `WBEAM_DESKTOP_AUTO_REEXEC=0 ./desktop.sh --dev` -> clear tty/GUI error
  - `WBEAM_DESKTOP_REEXEC=1 ./desktop.sh --dev` -> loop-guard error path

## In Progress (2026-03-10) [commit: 09ab7e60] - Android debug overlay toggle via hardware volume keys
- Implemented debug-only hardware key control in `MainActivity`:
  - `VOL_UP` opens debug overlay controls.
  - `VOL_DOWN` hides debug overlay controls.
- Overlay visibility now controls:
  - top bar (`Settings` / `Fullscreen`),
  - quick action row (`Start` / `Stop` / `Bandwidth Test`),
  - debug info panel.
- Default behavior in debug build now starts with overlay hidden (`debugOverlayVisible=false`), to reduce on-screen clutter.
- Validation:
  - `cd android && GRADLE_USER_HOME=/home/ppotepa/git/WBeam/.gradle-user ./gradlew :app:compileDebugJavaWithJavac --no-daemon --stacktrace` -> OK

## In Progress (2026-03-10) [commit: 3b84944e] - monotonic build-number recovery + all-device redeploy targeting
- Hardened build-number increment logic in `wbeam`:
  - build counter now seeds from max of `.wbeam_buildno` and parseable `.wbeam_build_version`,
  - prevents accidental reset to `0` when one file is stale/corrupted/legacy-formatted.
- Applied same monotonic fallback in `devtool` build-version generation path.
- Added explicit multi-device targeting support:
  - `wbeam` now accepts `WBEAM_ANDROID_SERIALS` (comma/space separated) as an override list for connected serials.
- Updated `redeploy-local` Android deploy stage:
  - retries ADB device discovery until snapshot stabilizes,
  - passes full discovered serial list via `WBEAM_ANDROID_SERIALS`,
  - clears `WBEAM_ANDROID_SERIAL` for this call to avoid single-device pinning.
- Added compatibility wrapper script:
  - `./redeploy_local` now delegates to `./redeploy-local`.
- Validation:
  - `bash -n wbeam devtool redeploy-local redeploy_local` -> OK
  - `./redeploy_local --help` -> OK

## In Progress (2026-03-10) [commit: 8e2cb257] - redeploy all-device verification + preflight build tag
- Hardened `redeploy-local` Android stage with post-deploy verification per discovered serial:
  - checks installed `versionName` on every target device against expected `BUILD_REV`,
  - if mismatch/missing, runs targeted fallback deploy for that serial (`WBEAM_ANDROID_SERIAL=<serial>`),
  - fails fast if any serial still mismatches after recovery attempt.
- Added startup/preflight build badge in Android UI:
  - connection overlay now shows `build <WBEAM_BUILD_REV>` in lower-right corner before streaming starts.
  - wired via `startupBuildVersion` view + `bindStartupBuildVersion()` in `MainActivity`.
- Validation:
  - `bash -n redeploy-local redeploy_local wbeam devtool` -> OK
  - `cd android && GRADLE_USER_HOME=/home/ppotepa/git/WBeam/.gradle-user ./gradlew :app:compileDebugJavaWithJavac --no-daemon --stacktrace` -> OK

## In Progress (2026-03-10) [commit: e1e5642c] - redeploy script naming compatibility cleanup
- Clarified script entrypoints around redeploy naming variants:
  - canonical implementation remains `./redeploy-local`,
  - `./redeploy_local` kept as compatibility wrapper,
  - added `./redeply_local` compatibility wrapper for common typo.
- All wrappers now delegate to the same canonical script path to avoid behavior drift.
- Validation:
  - `bash -n redeploy-local redeploy_local redeply_local` -> OK
  - `./redeploy_local --help` -> OK
  - `./redeply_local --help` -> OK

## In Progress (2026-03-10) [commit: 708e57df] - enforce single redeploy script path
- Reverted wrapper aliases to remove naming ambiguity.
- Removed:
  - `./redeploy_local`
  - `./redeply_local`
- Canonical and only supported entrypoint is now:
  - `./redeploy-local`
- Validation:
  - `bash -n redeploy-local` -> OK

## In Progress (2026-03-10) [commit: ee9702df] - robust adb install failure detection for certificate mismatch
- Fixed Android install handling in `wbeam`:
  - `adb install` output containing `Failure [...]` is now treated as a hard failure even if command exit code is zero.
  - Added recovery trigger for `INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES` in addition to `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.
- On signature/certificate mismatch:
  - script now force-uninstalls existing package and retries install,
  - retry result is also validated against `Failure [...]`.
- This prevents false-positive per-device `OK` status in `deploy-all` when install actually failed.
- Validation:
  - `bash -n wbeam redeploy-local` -> OK

## In Progress (2026-03-10) [commit: f3598eb5] - unique stream-port assignment per connected ADB device in desktop UI
- Fixed port-collision behavior in desktop Tauri backend device listing:
  - `list_devices_basic()` now enforces unique `streamPort` across all currently connected serials in a single refresh cycle.
  - Existing `.wbeam_device_ports` values are still used as preferred hints, but collisions are automatically resolved to the next free port.
  - Daemon-reported ports are now also de-duplicated before persisting back to `.wbeam_device_ports`.
- Added helpers:
  - `default_stream_port_for_index(...)`
  - `pick_unique_stream_port(...)`
- Validation:
  - `cargo check -p wbeam-desktop-tauri` -> OK

## In Progress (2026-03-10) [commit: a4b83431] - redeploy-local build revision bump only when rebuild inputs changed
- Updated `redeploy-local` build-revision strategy:
  - no longer always calls `./wbeam version new`,
  - now reuses current version when host/APK rebuild is not required.
- Added compatibility input fingerprint tracking:
  - new state file: `.wbeam_redeploy_compat.state`,
  - fingerprint built from git tree + dirty status of `android`, `src/host/rust`, and `wbeam`,
  - host/APK artifact presence also gates bump decision.
- Behavior:
  - bump (`version new`) only when rebuild inputs changed, baseline is missing, fingerprint is unavailable, or required artifacts are missing.
  - otherwise keep existing shared `BUILD_REV` for host+android redeploy.
- Validation:
  - `bash -n redeploy-local` -> OK
  - `./redeploy-local --help` -> OK

## In Progress (2026-03-10) [commit: c85b8606] - multi-device session isolation hardening (API17 + API34)
- Host daemon (`wbeamd-server`) session resolution was hardened to avoid creating per-serial cores on read-only polls:
  - added read-only resolver for `/status`, `/health`, `/metrics`, `/presets`, `/virtual/*`,
  - prevents accidental session/port creation during background polling before explicit `/start`.
- Added per-session cleanup on `/stop`:
  - when a specific session is stopped, registry mappings (`serial_cores`, `port_cores`) are now forgotten,
  - reduces stale session reuse and helps virtual-monitor lifecycle per device.
- Desktop Tauri device listing hardening:
  - `daemon_stream_state_and_port(...)` now ignores daemon responses not bound to the queried serial (`target_serial` mismatch/empty),
  - prevents default-session stream port from being incorrectly applied to per-device tiles.
  - stale `.wbeam_device_ports` entries for disconnected serials are pruned during refresh.
- Legacy/API17 host-IP detection improved in `wbeam`:
  - added fallback route probe (`route -n`) when `ip route` is unavailable on old Android,
  - broadened USB-interface private-IP detection beyond only `192.168.42/43`.
- Validation:
  - `cargo check -p wbeamd-server` -> OK
  - `cargo check -p wbeam-desktop-tauri` -> OK
  - `bash -n wbeam` -> OK

## In Progress (2026-03-10) - baseline-only profile unification + autotune scoring v2
- Profile catalog was simplified to a single trained profile named `baseline`.
- Updated profile sources and defaults:
  - `proto/config/profiles.json` now contains only `baseline`.
  - `proto/config/proto.json`, `proto/config/proto.conf`, and `proto/config/autotune-best.json` now point to `PROTO_PROFILE=baseline`.
  - Desktop Tauri connect profile list now exposes only `baseline` (`trained-profile-labels.json` + `trained-profile-runtime.json`).
  - Android profile defaults and stored settings now normalize to `baseline`.
- Added backward-compatibility mapping for legacy profile names to `baseline`:
  - Rust API validation canonicalizes legacy names to `baseline`.
  - Rust streamer CLI accepts legacy names but resolves them to baseline defaults.
  - Python fallback daemon canonicalizes legacy names to `baseline`.
- Autotune scoring upgrade (reward model v2):
  - added explicit drop-ratio penalty (`pipeline_fps` vs effective `sender_fps`),
  - added FPS target gap penalty (median/tail under target),
  - kept timeout + jitter penalties and combined them in final score,
  - extended logs/reports/profile metadata with `drop_ratio_p50`, `fps_gap_p50`, `drop_penalty`, `target_penalty`.
- Autotune profile export behavior now rewrites `profiles.json` with current generated profiles (no accumulation of stale profile variants).
- Default autotune single-profile export name is now `baseline`.
- Validation:
  - `python3 -m py_compile proto/autotune.py src/host/daemon/wbeamd.py` -> OK
  - `cargo check -p wbeamd-api -p wbeamd-core -p wbeamd-streamer` -> OK
  - `cd src/apps/desktop-tauri && npm run build` -> OK
  - `cd android && GRADLE_USER_HOME=/home/ppotepa/git/WBeam/.gradle-user ./gradlew :app:compileDebugJavaWithJavac --no-daemon --stacktrace` -> OK

## In Progress (2026-03-10) - TrainerV2 domain implementation (API + GUI + run artifacts)
- Extended Rust host daemon (`wbeamd-server`) with trainer API surface:
  - `POST /v1/trainer/preflight`
  - `POST /v1/trainer/start`
  - `POST /v1/trainer/stop`
  - `GET /v1/trainer/runs`
  - `GET /v1/trainer/runs/{run_id}`
  - `GET /v1/trainer/runs/{run_id}/tail`
  - `GET /v1/trainer/profiles`
  - `GET /v1/trainer/profiles/{profile_name}`
  - `GET /v1/trainer/devices`
  - `GET /v1/trainer/diagnostics`
- Added trainer run state registry in daemon:
  - in-memory run catalog with PID/status/lifecycle fields,
  - stop support via signal,
  - deterministic run IDs.
- Added deterministic per-run artifact persistence:
  - run artifact directory under `config/training/profiles/<profile>/runs/<run_id>/`,
  - persisted `run.json`, copied trainer log (`logs.txt`), and profile/parameters/preflight snapshots.
- Updated wizard (`src/domains/training/wizard.py`) for run-id aware artifact layout:
  - new `--run-id`,
  - writes run-level artifacts for both `proto` and `live_api` paths.
- Upgraded Trainer GUI (`src/apps/trainer-tauri`):
  - tabs: `Train`, `Live HUD`, `Profiles`, `Runs`, `Compare`, `Devices`, `Validation`, `Diagnostics`,
  - wired to live trainer API endpoints,
  - run tail rendering for live HUD/event log,
  - profile compare panel and diagnostics snapshot views.
- Updated launcher and dev wiring:
  - `trainer.sh` now defaults to GUI mode (`--ui`) with `--wizard` fallback,
  - Vite proxy routes `/v1` to local daemon.
- Validation:
  - `python3 -m py_compile src/domains/training/wizard.py` -> OK
  - `cd src/host/rust && cargo check -p wbeamd-server` -> OK
  - `cd src/apps/trainer-tauri && npm run build` -> OK

## In Progress (2026-03-10) - TrainerV2 update specification document
- Added comprehensive consolidation document:
  - `trainer.update.md`
- Document includes complete agreed direction for:
  - Tauri-first launcher/app model,
  - dataset-first run storage and deterministic `Find Optimal Best`,
  - encoder-parameter genetic search (per encoder + global genes),
  - required min/max bitrate training contract and visualization implications,
  - full GUI information architecture (`Train`, `Live HUD`, `Profiles`, `Runs`, `Compare`, `Devices`, `Validation`, `Diagnostics`, `Datasets`),
  - required API/data/artifact contracts and DoD for first complete Trainer app.

## In Progress (2026-03-10) - TrainerV2 spec extension in Jira/engineering-task format
- Expanded `trainer.update.md` with formal implementation addendum matching engineering task template:
  - technical context and impacted modules,
  - Jira-style task definition (`title`, `goal`, `scope`, `acceptance criteria`),
  - target architecture and integration model,
  - file/module layout proposal,
  - API contracts and data structures,
  - pseudo-code for core flows and recompute path,
  - error handling, validation, fallbacks,
  - risks, performance constraints, and test focus areas.

## In Progress (2026-03-10) - TrainerV2 top-tier completeness iteration (UI/backend/scalability)
- Extended `trainer.update.md` with dedicated iteration focused on end-to-end UI/backend complementarity and scalability:
  - strict UI->API->engine->dataset parameter mapping matrix,
  - fully parameterized training model (global + per-encoder),
  - strict config contract and validation behavior,
  - scalable execution/data/API planes (`queue`, `worker`, `lock manager`, `recompute engine`),
  - SLO/SLA expectations for trainer runtime and live HUD,
  - observability, testing matrix, and commercial-grade readiness criteria.

## In Progress (2026-03-10) - TrainerV2 dynamic workload adaptation addendum
- Extended `trainer.update.md` with explicit strategy for unknown/dynamic screen usage:
  - workload segmentation model (`text_static`, `ui_interactive`, `video_motion`, `mixed`),
  - segment-aware scoring and worst-case preference,
  - runtime adaptive policy switching with hysteresis/cooldown,
  - charts and telemetry dimensions dedicated to adaptation behavior.
- Added explicit implementation note that code snippets are pseudocode architecture references and must be mapped to current backend contracts/flow rather than copied 1:1.

## In Progress (2026-03-10) - Trainer UI/UX responsiveness + live readability + Wayland launch stability
- Upgraded `src/apps/trainer-tauri` UX flow for faster feedback and clearer live-state visibility:
  - added blocking `busy-overlay` with spinner card for long actions,
  - added topbar live sync timestamp badge (`Last sync`) for operator confidence,
  - added dynamic polling interval hot-apply (no restart needed).
- Improved `Live Run` ergonomics:
  - added inferred `Live health` pill (`idle`/`active`/`gated`/`degraded`) from run tail,
  - added empty-state card when no samples are available yet,
  - added `Stage Timeline` panel parsing warmup/sampling/generation/health-gate/failure/completion lines.
- Enhanced trainer visual system in `styles.css`:
  - busy overlay + spinner animation,
  - timeline cards with severity-coded left rails,
  - compact readability improvements for pills and live states,
  - smoother transitions for KPI and timeline cards.
- Stabilized Tauri launcher backend selection for Wayland sessions:
  - `trainer.sh` and `desktop.sh` now force X11 backend only when `DISPLAY` exists,
  - in Wayland-only sessions (`DISPLAY` missing), scripts keep native wayland backend,
  - this avoids protocol errors caused by forcing unavailable X11 path.
- Validation:
  - `cd src/apps/trainer-tauri && npm run build` -> OK
  - `bash -n trainer.sh` -> OK
  - `bash -n desktop.sh` -> OK

## In Progress (2026-03-10) - Trainer run stability: live reset + wayland portal profile/encoder normalization
- Fixed `stream_wayland_portal_h264.py` argument robustness to prevent hard aborts during trainer-driven restarts:
  - added `baseline` profile defaults,
  - removed strict argparse `choices` for `--profile` and `--encoder` in favor of runtime normalization,
  - added profile alias mapping (`safe_60`, `balanced60`, `quality60`, etc.) to valid defaults,
  - unknown profile now warns and falls back to `baseline` instead of exiting with code 2,
  - unsupported encoder names (`h265`, `rawpng`, `mjpeg`, etc.) now warn and fallback to `auto` for portal-h264 helper.
- Improved Trainer UI run transition behavior (`src/apps/trainer-tauri/src/App.tsx`):
  - live tail is cleared immediately when a new run starts,
  - selected run is explicitly switched to the newly created `run_id` returned by API,
  - this prevents stale Live View data from previous runs.
- Validation:
  - `python3 -m py_compile src/host/scripts/stream_wayland_portal_h264.py src/domains/training/wizard.py` -> OK
  - `cd src/apps/trainer-tauri && npm run build` -> OK

## In Progress (2026-03-10) - HUD V2 readability + Mbps-first trainer UX + dataset metadata
- Reworked Android training HUD payload in `src/domains/training/wizard.py` to be more game-like and structured:
  - section boxes with compact grid-style framing (`TL/TR/BL/BR`),
  - clearer status hierarchy (`RUN/PROFILE/TRIAL/GEN/NOTE`),
  - richer live metrics block + trend lines,
  - bitrate displayed in **Mbps** (`x.y Mbps`) instead of raw kbps text.
- Updated quality trial-space defaults in wizard:
  - quality mode now prioritizes only native detected device resolution when available,
  - removed fallback mix that could reintroduce lower-res legacy sizes in quality default flow.
- Improved portal overlay rendering defaults in streamer helper (`stream_wayland_portal_h264.py`):
  - default overlay font changed to compact monospace style (`Monospace Semi-Bold 12`) for better technical HUD readability.
- Trainer Desktop UI (`src/apps/trainer-tauri`) enhancements:
  - train form bitrate labels switched to Mbps input semantics (still converted to kbps for backend contract),
  - live chart cards now show numeric summaries (`last/min/max`) and per-sample tooltips,
  - datasets list now includes run `started` and `finished` timestamps,
  - dataset detail panel now surfaces best trial config (`encoder`, `size`, `fps`, `bitrate` in Mbps),
  - compare panel bitrate now rendered in Mbps.
- Backend dataset API enrichment (`wbeamd-server`):
  - added `started_at_unix_ms` and `finished_at_unix_ms` to dataset summaries.
- Validation:
  - `python3 -m py_compile src/domains/training/wizard.py src/host/scripts/stream_wayland_portal_h264.py` -> OK
  - `cd src/host/rust && cargo check -p wbeamd-server` -> OK
  - `cd src/apps/trainer-tauri && npm run build` -> OK

## In Progress (2026-03-10) - HUD V3 chart mode + live Mbps telemetry plumbing
- Added configurable Android HUD trend rendering mode (`bars` / `line`) end-to-end:
  - GUI control in Trainer advanced section,
  - API contract field `hud_chart_mode` in `/v1/trainer/start`,
  - server forwards mode to wizard via `--overlay-chart`,
  - wizard renders trend lines using selected style.
- Extended trainer trial scoring telemetry with measured live bitrate:
  - `bitrate_mbps_mean` computed from daemon metrics (`metrics.bitrate_actual_bps` fallback to `latest_client_metrics.recv_bps`),
  - included in best/result artifacts in `parameters.json`,
  - printed in trial logs (`mbps=...`) for live parsing.
- Enhanced Android HUD content density:
  - added `live_mbps` panel metric,
  - added `mbps_tr` trend section,
  - compact framed box style retained for game-like readability.
- Desktop Live Run HUD improved:
  - parses and shows `Live Mbps` KPI card from run tail logs,
  - keeps chart summary annotations (`last/min/max`) and per-sample tooltips.
- Validation:
  - `python3 -m py_compile src/domains/training/wizard.py src/host/scripts/stream_wayland_portal_h264.py` -> OK
  - `cd src/host/rust && cargo check -p wbeamd-server` -> OK
  - `cd src/apps/trainer-tauri && npm run build` -> OK

## In Progress (2026-03-10) - Dataset timeline analytics in Trainer UI
- Extended `Datasets` detail view in `src/apps/trainer-tauri/src/App.tsx` with parsed per-trial analytics from `parameters.results[]`:
  - added deterministic trial ordering (`t01`, `t02`, ...),
  - added chart panels for:
    - score per trial,
    - present FPS per trial,
    - measured Mbps per trial,
    - drops/s per trial.
- Added numerical chart summaries (`last/min/max`) and per-bar tooltips with trial IDs.
- Added per-trial table under charts with key telemetry columns:
  - trial id, score, present FPS, pipe FPS, Mbps, drops/s, notes/state.
- Added supporting dataset analytics styling in `styles.css` to keep chart/table block compact and readable.
- Validation:
  - `cd src/apps/trainer-tauri && npm run build` -> OK
  - `cd src/host/rust && cargo check -p wbeamd-server` -> OK
  - `python3 -m py_compile src/domains/training/wizard.py` -> OK

## In Progress (2026-03-10) - Profile preview mode + dataset/profile export actions
- Extended `Profiles` tab with actionable preview workflow:
  - added `Preview` action per profile row,
  - added selected-row highlighting for current preview profile,
  - added profile preview panel with runtime summary (`encoder`, `size`, `fps`, `bitrate`) and historical charts from profile `parameters.results[]`.
- Added export controls in `Datasets` timeline section:
  - `Export Dataset JSON` (full selected dataset detail payload),
  - `Export Profile JSON` (profile blob from selected dataset).
- Export uses browser download flow with sanitized filenames.
- Validation:
  - `cd src/apps/trainer-tauri && npm run build` -> OK
  - `cd src/host/rust && cargo check -p wbeamd-server` -> OK

## In Progress (2026-03-10) - Unified full-frame Android HUD + live per-sample updates
- Reworked Wayland overlay rendering path to a single unified overlay element (`hud_main`) in `stream_wayland_portal_h264.py`:
  - replaced legacy 4-corner (`hud_tl/tr/bl/br`) composition with one coherent full-frame text layout,
  - enabled transparent background (`shaded-background=false`) and semi-transparent text color,
  - retained backward compatibility in parser: supports new `[MAIN]` section and merges legacy `[TL/TR/BL/BR]` if needed.
- Reworked trainer HUD payload (`wizard.py`) into one grid-aligned block:
  - single rectangular layout with fixed-width line justification,
  - consistent table-like sections for run/config/live/quality/trends,
  - explicit threshold labels (`OK/WARN/RISK`) per metric area (fps/latency/drop health).
- Added true live HUD refresh during sampling:
  - `collect_metrics_samples` now supports per-sample callback,
  - during each trial sampling pass HUD updates continuously with partial metrics,
  - `live_mbps` and other values now evolve during the sample window instead of appearing static until trial end.
- Validation:
  - `python3 -m py_compile src/domains/training/wizard.py src/host/scripts/stream_wayland_portal_h264.py` -> OK
  - `cd src/apps/trainer-tauri && npm run build` -> OK
  - `cd src/host/rust && cargo check -p wbeamd-server` -> OK

## In Progress (2026-03-10) - HUD threshold color rendering (markup + fallback)
- Extended unified Wayland HUD overlay path to render colored threshold states from trainer markup:
  - `wizard.py` now emits threshold tags as Pango markup (`OK/WARN/RISK`) with explicit green/amber/red tones,
  - `stream_wayland_portal_h264.py` enables `textoverlay` markup mode (`use-markup=true`) for `hud_main`.
- Added robust runtime fallback in overlay updater:
  - first attempts `set_property("markup", ...)`,
  - if markup property is unavailable, strips tags and falls back to plain `text` so HUD remains readable.
- Result: single full-screen HUD keeps transparent style while now visually highlighting metric severity levels.
- Validation:
  - `python3 -m py_compile src/domains/training/wizard.py src/host/scripts/stream_wayland_portal_h264.py` -> OK
  - `cd src/apps/trainer-tauri && npm run build` -> OK
  - `cd src/host/rust && cargo check -p wbeamd-server` -> OK

## In Progress (2026-03-10) - HUD extended thresholds + readability hardening
- Extended Android unified HUD scoring semantics in `wizard.py`:
  - added colorized threshold states for additional runtime signals:
    - `LIVE Mbps` (target-relative, low-throughput detection),
    - `QUEUE` depth,
    - `LATE/s` (late frame cadence),
    - note/health state mapping.
  - added in-HUD legend row (`OK/WARN/RISK`) for immediate interpretation.
- Improved overlay text layout resilience with markup-aware helpers:
  - added `strip_markup` and `visible_len` for spacing/padding calculations,
  - updated `kv_line`/`box_line` to avoid clipping/invalidating color markup when aligning HUD rows.
- Quality state now incorporates queue pressure in addition to latency/drop thresholds.
- Validation:
  - `python3 -m py_compile src/domains/training/wizard.py` -> OK
  - `cd src/apps/trainer-tauri && npm run build` -> OK
  - `cd src/host/rust && cargo check -p wbeamd-server` -> OK
