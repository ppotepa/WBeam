# WBeam Progress

Date: 2026-03-07
Branch: `master`
Status: active

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
