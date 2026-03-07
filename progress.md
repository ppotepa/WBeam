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
