# WBeam Agent Handbook

Last updated: 2026-03-11
Primary entrypoint: `./wbeam`

## 1. Project Goal

WBeam turns Android devices (including legacy API 17 tablets) into USB-connected external displays for Linux hosts.

Core product surfaces:
- Host daemon/runtime (`src/host/rust/`)
- Android client (`android/`)
- Desktop control UI (`src/apps/desktop-tauri/`)
- Trainer UI + trainer domain (`src/apps/trainer-tauri/`, `src/domains/training/`)

## 2. Canonical Operational Entry Points

- `./wbeam` -> canonical CLI orchestration
- `./wbgui` -> terminal UI
- `./devtool` -> developer convenience wrapper
- `./trainer.sh` -> trainer desktop launcher
- `./start-remote` -> remote session bootstrap (host + UI + optional deploy)
- `./runas-remote` -> run a command in active graphical session

Rule:
- Never hardcode usernames/IPs in scripts or docs.
- Use explicit params or config/env inputs.

## 3. Workflow Source Of Truth

Canonical branch/release workflow lives in:
- `docs/agents.workflow.md`

If branching process changes, update that file first.

Current practical model in repo:
- long-lived: `master`, `release`, version lanes (for example `0.1.1`)
- short-lived: feature branches for issue work

## 4. Repository Lanes

- Main lane (production): `android/`, `src/host/rust/`, `src/apps/desktop-tauri/`, `src/apps/trainer-tauri/`, `src/domains/training/`
- Proto lane (historical/R&D): `proto/`, `proto_x11/` (kept for experiments and history)

## 5. High-Level Architecture

### Host

- API server: `src/host/rust/crates/wbeamd-server`
- Core/state machine: `src/host/rust/crates/wbeamd-core`
- Streamer pipeline/transport: `src/host/rust/crates/wbeamd-streamer`
- Python fallback daemon: `src/host/daemon/wbeamd.py`
- Host scripts: `src/host/scripts/`

### Desktop

- Main desktop app: `src/apps/desktop-tauri`
- Trainer app: `src/apps/trainer-tauri`

### Android

- Main package: `com.wbeam`
- Stream decode path centered around:
  - `H264TcpPlayer`
  - `StreamService`
  - `StreamSessionController`

### Protocol

- WBTP crates under `src/protocol/rust/crates/*`

## 6. Runtime Ownership Model

- Desktop UIs are clients.
- Streaming lifecycle authority is the daemon service.
- Service is user-scoped systemd (`--user`), not system-wide root service.

Service unit path:
- `~/.config/systemd/user/wbeam-daemon.service`

## 7. Multi-Device Session Model

Sessions are keyed by ADB serial and stream port.

Per-device API pattern:
- `/v1/status?serial=<serial>&stream_port=<port>`
- `/v1/start?serial=<serial>&stream_port=<port>`
- `/v1/stop?serial=<serial>&stream_port=<port>`

Device port mapping:
- `.wbeam_device_ports`

## 8. Android Pipeline Compatibility

Pipeline selection via `./wbeam`:
- `legacy` -> minSdk 17 (API <= 18)
- `modern` -> minSdk 19+
- `auto` -> selected from detected device API

Practical behavior:
- API 17 often needs tether/LAN fallback (reverse may be limited)
- modern devices usually work with `adb reverse` and loopback host

## 9. Configuration And Environment

Runtime model is config-first with env override compatibility.

Defaults and precedence:
1. Process env (explicit override)
2. User config `~/.config/wbeam/wbeam.conf`
3. Repo template `config/wbeam.conf` (used for bootstrap/defaults)

Helper:
- `src/host/scripts/wbeam_config.sh`

Important env groups:

### Core service/ports
- `WBEAM_CONTROL_PORT`
- `WBEAM_STREAM_PORT`
- `WBEAM_DAEMON_SERVICE_NAME`
- `WBEAM_DAEMON_BIN`

### Versioning
- `WBEAM_BUILD_REV`
- `WBEAM_VERSION_BASE`

### Android deploy/build
- `WBEAM_ANDROID_SERIAL`
- `WBEAM_ANDROID_PIPELINE`
- `WBEAM_ANDROID_HOST`
- `WBEAM_ANDROID_API_HOST`
- `WBEAM_ANDROID_STREAM_HOST`
- `WBEAM_ANDROID_FORCE_INSTALL`
- `WBEAM_ANDROID_SKIP_LAUNCH`
- `WBEAM_MIN_SDK`

### Remote
- `WBEAM_DEV_REMOTE_USER`

### Watch/log
- `WBEAM_WATCH_INTERVAL`
- `WBEAM_WATCH_LOG`
- `WBEAM_WATCH_COLOR`
- `WBEAM_ADB_LOGCAT_AUTO`

### Streamer internals (advanced)
- `WBEAM_USE_RUST_STREAMER`
- `WBEAM_RUST_STREAMER_BIN`
- `WBEAM_START_TIMEOUT_SEC`
- `WBEAM_ALLOW_LIVE_ADAPTIVE_RESTART`

Guidance:
- Prefer config/API payloads for reproducible runs.
- Use env vars for tactical one-off overrides.

## 10. Versioning Contract

Compatibility version is shared by:
- Android APK `versionName`
- Host daemon `build_revision` (`/health`)

Desktop UI app version is not the compatibility gate.

Local version files:
- `.wbeam_build_version`
- `.wbeam_buildno`

Diagnostics:
- `./wbeam version doctor`

## 11. Trainer Domain (Current State)

Canonical trainer path:
- `./wbeam train wizard`
- implementation: `src/domains/training/wizard.py`

Trainer desktop launcher:
- `./trainer.sh` (Trainer Tauri UI)

Trainer and runtime HUDs are active UX areas; keep data mapping explicit and test against both desktop and Android displays.

## 12. Connect Flow (Desktop -> Device)

Typical connect sequence:
1. `adb start-server`
2. device state probe
3. `adb reverse` control + stream ports
4. launch Android activity
5. daemon `/v1/start` for selected serial/port

Trace file:
- `logs/desktop-connect.log`

## 13. Logging Strategy

Main logs directory:
- `logs/`

Typical files:
- `*.desktop.*.log`
- `*.adb.*.log`
- `*.udev.*.log`
- `*.host.*.log`
- `*.version.*.log`
- `desktop-connect.log`

Git hygiene:
- generated runtime/build artifacts should stay ignored (`logs/*`, build outputs, caches)

## 14. Current Key Commands

### Daily local loop
- `./wbeam host debug`
- `./wbeam android deploy-all`
- `./wbgui` or `./devtool`
- `./trainer.sh`
- `./wbeam train wizard`
- `./wbeam version doctor`

### Fast diagnostics
- `./wbeam daemon status`
- `./wbeam watch devices`
- `./wbeam watch streaming`
- `./wbeam watch service`
- `./wbeam watch logs`

### Remote full loop
- `./start-remote <user> --redeploy`

## 15. Known Failure Modes

### Multiple ADB devices and ambiguous target
- Symptom: `more than one device/emulator`
- Fix: set explicit serial or use serial-aware deploy flow

### Build mismatch (host vs APK)
- Symptom: startup mismatch/handshake block
- Fix: rebuild + redeploy with one shared build revision, verify with `version doctor`

### API17 stuck on control/tether checks
- Cause: host path not reachable for legacy path
- Fix: validate tether/LAN host path and check logs

### Foreground app but no stream
- Cause: partial connect succeeded, start/session mapping failed
- Fix: inspect `desktop-connect.log`, per-serial `/v1/status`, and watch metrics

### Wayland desktop launch anomalies
- Cause: GUI backend/session constraints
- Fix: use current launcher wrappers and review logs; avoid bypassing wrappers in remote shells

## 16. Practical Tree

```text
WBeam/
  wbeam
  wbgui
  devtool
  trainer.sh
  start-remote
  runas-remote
  android/
  src/
    apps/
      desktop-tauri/
      trainer-tauri/
    domains/
      training/
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
  config/
  docs/
  proto/
  proto_x11/
  updates/
  logs/
```

## 17. Design Guardrails For Agents

- Keep `./wbeam` as primary operational interface.
- Keep daemon as runtime authority; do not push session ownership into UI.
- Keep compatibility version coherent across host + APK.
- Prefer explicit probes/checks before enabling advanced paths.
- Avoid introducing secret/env leakage into scripts, logs, or release assets.
- Keep docs and workflow rules consistent with `docs/agents.workflow.md`.
