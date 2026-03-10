# WBeam Progress

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

## In Progress (2026-03-10) [commit: pending] - sectioned `wbeam.conf` layout + x11 section
- Converted `config/wbeam.conf` to INI-like sectioned format (`[service]`, `[android]`, `[version]`, `[x11]`).
- Kept runtime keys as `WBEAM_*` to preserve compatibility with existing loaders.
- Added `x11` section with `WBEAM_X11_ALLOW_MONITOR_OBJECT` and placeholders for future policy unification.
- Confirmed shell loader accepts section headers and still reads values:
  - `source src/host/scripts/wbeam_config.sh && wbeam_load_config ...` -> `control=5001 x11_allow=0`
