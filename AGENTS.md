# WBeam Agent Handbook

Last updated: 2026-03-07
Primary entrypoint: `./wbeam`

## 1. Project Goal

WBeam turns Android devices (including legacy API 17 tablets) into USB-connected second screens for Linux hosts, with a host daemon, Android client, and desktop control UI.

This repo has two lanes:

- Main lane (production path): `android/`, `src/host/rust/`, `src/apps/desktop-tauri/`, `wbeam`
- Proto lane (historical R&D sandbox): `proto/` (kept for experiments and profile history)

## 2. Source Of Truth And Entry Scripts

- `./wbeam` is the canonical CLI orchestrator.
- `./wbgui` is interactive terminal menu.
- `./devtool` is developer wrapper (GUI-first + build/deploy convenience).
- `./start-remote` is remote-session bootstrap for full host + desktop + Android flow.
- `./runas-remote` executes one command in active graphical session of selected user.

Rule: do not hardcode usernames or host IPs. Pass user explicitly or via `WBEAM_DEV_REMOTE_USER`.

## 3. High-Level Architecture

### Host side

- Rust control daemon HTTP server: `src/host/rust/crates/wbeamd-server`
- Rust daemon core/state machine: `src/host/rust/crates/wbeamd-core`
- Rust streamer (GStreamer capture + transport): `src/host/rust/crates/wbeamd-streamer`
- Python fallback daemon: `src/host/daemon/wbeamd.py`
- Host scripts: `src/host/scripts/`

### Desktop side

- Current UI: Tauri + Solid + TypeScript: `src/apps/desktop-tauri`
- Legacy desktop UI (still in repo): `src/apps/desktop-egui`

### Android side

- Main app package: `com.wbeam` in `android/app/src/main/java/com/wbeam`
- Stream decode path: `H264TcpPlayer`, `StreamService`, `StreamSessionController`
- Startup/diagnostics UI flow in `MainActivity` and `StatusPoller`

### Protocol side

- WBTP-related crates in `src/protocol/rust/crates/*`

## 4. Runtime Model

### Why a daemon service exists

Desktop GUI is a thin client. Streaming control/state belongs to host daemon (`wbeamd-server`).
The service keeps stream lifecycle stable independently of UI restarts.

### Service ownership model

- Service is a user-level systemd unit (`--user`), not system-wide root service.
- Tauri backend can install/uninstall/start/stop service by writing:
  - `~/.config/systemd/user/wbeam-daemon.service`

### Multi-device model (current)

Host supports per-device session cores keyed by ADB serial.
Each device can map to dedicated `stream_port` and daemon session via query args:

- `/v1/status?serial=<serial>&stream_port=<port>`
- `/v1/start?serial=<serial>&stream_port=<port>`
- `/v1/stop?serial=<serial>&stream_port=<port>`

Port mapping file:

- `.wbeam_device_ports` (generated/updated by deploy flow)

## 5. Android Pipelines And API Compatibility

Pipeline selector is in `./wbeam`:

- `legacy` -> minSdk 17 (for API <= 18 devices)
- `modern` -> minSdk 19
- `auto` chooses based on detected device API

Behavior:

- API 17 path usually cannot rely on full `adb reverse` behavior, often needs tether/LAN host IP fallback.
- API 34 path commonly uses `127.0.0.1` through `adb reverse`.

## 6. Versioning And Compatibility Contract

Single compatibility version is shared by:

- Android APK versionName
- Host daemon build revision (`/health -> build_revision`)

Desktop GUI version is separate UI app version and is not compatibility gate.

Version files:

- `.wbeam_build_version`
- `.wbeam_buildno`

Current default generated format in `wbeam`:

- `0.1.0.<build_number>.<hash>`

Mismatch diagnostics:

- `./wbeam version doctor`

Doctor checks:

- daemon `/health` revision
- local build files
- per-device installed APK version
- match/mismatch result

## 7. Connect Flow (Desktop Tauri -> Device)

Frontend action:

- `Connect` button calls Tauri command `device_connect(serial, stream_port)`.

Backend action sequence:

1. `adb start-server`
2. device readiness probe (`adb -s <serial> get-state` polling)
3. `adb reverse` for stream and control ports
4. `adb shell am start -n com.wbeam/.MainActivity`
5. daemon POST `/v1/start?serial=...&stream_port=...`

Diagnostics added:

- `logs/desktop-connect.log` contains step-by-step connect trace.

## 8. Remote Workflows (RDP/Wayland/X11)

`start-remote` exists because development happens from another machine while Android devices are physically attached to host.

Default flow in `start-remote`:

1. stop old host
2. remove desktop user service for clean state
3. optional host rebuild
4. start host in remote graphical session user
5. wait for control API
6. launch desktop GUI in remote session
7. snapshot ADB + start udev monitor logging
8. optional full `android deploy-all` (with `--redeploy`)
9. run version doctor

Use:

- `./start-remote <desktop_user>`
- `./start-remote <desktop_user> --redeploy`

Never assume fixed user/IP in scripts.

## 9. Environment Variables (Important)

### Core ports and service

- `WBEAM_CONTROL_PORT` (default `5001`)
- `WBEAM_STREAM_PORT` (default `5000`)
- `WBEAM_DAEMON_SERVICE_NAME` (default `wbeam-daemon`)
- `WBEAM_DAEMON_BIN` (Tauri service unit override)

### Versioning

- `WBEAM_BUILD_REV`
- `WBEAM_VERSION_BASE` (default `0.1.0` in `wbeam`, `0.1` in `devtool`)

### Android deploy/build

- `WBEAM_ANDROID_SERIAL`
- `WBEAM_ANDROID_PIPELINE` (`auto|legacy|modern`)
- `WBEAM_ANDROID_HOST`
- `WBEAM_ANDROID_API_HOST`
- `WBEAM_ANDROID_STREAM_HOST`
- `WBEAM_ANDROID_FORCE_INSTALL`
- `WBEAM_ANDROID_SKIP_LAUNCH`
- `WBEAM_MIN_SDK` (Gradle prop path)

### Remote scripts

- `WBEAM_DEV_REMOTE_USER` (default target user for remote session scripts)

### Watch/log behavior

- `WBEAM_WATCH_INTERVAL`
- `WBEAM_WATCH_LOG`
- `WBEAM_WATCH_COLOR`
- `WBEAM_ADB_LOGCAT_AUTO`

### Host streamer internals (advanced)

- `WBEAM_USE_RUST_STREAMER`
- `WBEAM_RUST_STREAMER_BIN`
- `WBEAM_START_TIMEOUT_SEC`
- `WBEAM_ALLOW_LIVE_ADAPTIVE_RESTART`

## 10. Logging Strategy

Main logs directory: `logs/`

Patterns currently used:

- `YYYYMMDD-HHMMSS.desktop.NNNN.log`
- `YYYYMMDD-HHMMSS.adb.NNNN.log`
- `YYYYMMDD-HHMMSS.udev.NNNN.log`
- `YYYYMMDD-HHMMSS.host.NNNN.log`
- `YYYYMMDD-HHMMSS.version.NNNN.log`
- `desktop-connect.log` (connect path trace)

Runtime generated files are ignored in git (`.gitignore` has broad `logs/*`, `*.log`, build artifacts, version temp files).

## 11. Current Key Commands

### Common local loop

- `./wbeam host debug`
- `./wbeam android deploy-all`
- `./wbgui` or `./devtool` (desktop UI)
- `./wbeam version doctor`
- `./wbeam watch tui`

### Fast diagnostics

- `./wbeam daemon status`
- `./wbeam watch devices`
- `./wbeam watch streaming`
- `./wbeam watch service`
- `./wbeam watch logs`

### Remote full loop

- `./start-remote <user> --redeploy`

## 12. Android Startup Step Semantics (MainActivity)

Android startup UI has 3 conceptual steps:

1. Control link: host API reachable
2. Handshake: daemon/service/build revision checks
3. Stream path: transport/decode/frame flow

Typical messages:

- "waiting for control link"
- "build mismatch"
- "connecting to <host>:<port>"
- "check USB tethering / host IP / LAN"

Files:

- `android/app/src/main/java/com/wbeam/MainActivity.java`
- `android/app/src/main/java/com/wbeam/api/StatusPoller.java`
- `android/app/src/main/java/com/wbeam/StreamService.java`

## 13. Known Failure Modes

### "more than one device/emulator"

Cause: no explicit serial when multiple ADB devices are connected.
Fix: set `WBEAM_ANDROID_SERIAL` or use `deploy-all` serial-aware flow.

### Build mismatch (host vs APK)

Cause: host daemon revision and APK versionName differ.
Fix: rebuild + redeploy with one shared `WBEAM_BUILD_REV`; verify with `version doctor`.

### API17 stuck on "check USB tethering"

Cause: control/stream host not reachable from old device path; reverse may be unavailable.
Fix: ensure tethering path and reachable host IP fallback; inspect `desktop-connect.log` + adb logs.

### API34 app foregrounds but no stream

Cause: connect preflight partially succeeds (launch), but daemon start/session/port mapping fails.
Fix: inspect `desktop-connect.log`, `/v1/status` per serial/port, `watch streaming`.

### UI appears frozen on connect

Cause: expensive device polling overlapping with connect action.
Current mitigation: refresh-in-flight guard + slower polling + busy tile state.

## 14. Key Paths (Practical Tree)

```text
WBeam/
  wbeam
  wbgui
  devtool
  start-remote
  runas-remote
  android/
  src/
    apps/
      desktop-tauri/
      desktop-egui/
    host/
      daemon/
      rust/
      scripts/
    protocol/
      rust/
    compat/
      api17/
      api21/
      api29/
  proto/
  docs/
  logs/
```

## 15. Design Rules For Future Work

- Keep `./wbeam` as the primary operational interface.
- Keep daemon as authority; GUI should stay a client, not business-logic owner.
- Do not bake personal usernames/IPs into repo scripts.
- Keep Android-host compatibility strictly tied to shared build revision.
- Preserve API17 compatibility path while extending modern pipeline.
- Add features behind explicit probes/startup checks (host session type, capture backend, device transport readiness).

