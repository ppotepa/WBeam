# WBeam Agent Handbook

Last updated: 2026-03-11
Primary entrypoint: `./wbeam`

## 1. Project Purpose

WBeam turns Android tablets/phones (including legacy API 17 devices) into USB-connected external displays for Linux hosts.

This repository contains:
- host daemon/runtime,
- Android decode client,
- desktop control application,
- trainer application and tuning domain.

## 2. Workflow Source Of Truth

Canonical workflow doc:
- `docs/agents.workflow.md`

If process rules conflict elsewhere, follow `docs/agents.workflow.md`.

Canonical repository layout and migration map:
- `docs/repo-structure.md`

Branch naming note:
- repo currently contains mixed naming conventions from transition periods (`0.1.1`, `feature/...`, `ver/...`),
- for the active sprint, follow current team instruction and keep branch usage explicit in PR/MR descriptions.

## 3. Canonical Entrypoints

- `./wbeam` -> main operational CLI (host/android/version/watch/train flows)
- `./wbgui` -> terminal UI wrapper around core workflows
- `./devtool` -> local dev convenience wrapper
- `./trainer.sh` -> trainer desktop app launcher
- `./start-remote` -> remote user/session bootstrap (host + desktop + optional redeploy)
- `./runas-remote` -> execute command inside active graphical session of selected user

Rules:
- do not hardcode usernames, IPs, ports in committed scripts,
- prefer config + explicit args + per-session discovery.

## 4. Architecture Map (Key Files And Classes)

### 4.1 Host Daemon/API

- Server router and trainer endpoints:
  - `host/rust/crates/wbeamd-server/src/main.rs`
- Shared API models and response contracts:
  - `host/rust/crates/wbeamd-api/src/lib.rs`
- Core session/state/adaptation logic:
  - `host/rust/crates/wbeamd-core/src/lib.rs`
  - `host/rust/crates/wbeamd-core/src/domain/state.rs`
  - `host/rust/crates/wbeamd-core/src/domain/policy.rs`
  - `host/rust/crates/wbeamd-core/src/infra/process.rs`

### 4.2 Streamer Pipeline

- Pipeline assembly:
  - `host/rust/crates/wbeamd-streamer/src/pipeline.rs`
- Capture/runtime source:
  - `host/rust/crates/wbeamd-streamer/src/capture.rs`
- Framed transport:
  - `host/rust/crates/wbeamd-streamer/src/transport.rs`
- Encoder modular split:
  - `host/rust/crates/wbeamd-streamer/src/encoder/mod.rs`
  - `host/rust/crates/wbeamd-streamer/src/encoder/selector.rs`
  - `host/rust/crates/wbeamd-streamer/src/encoder/h264.rs`
  - `host/rust/crates/wbeamd-streamer/src/encoder/h265.rs`
  - `host/rust/crates/wbeamd-streamer/src/encoder/rawpng.rs`

### 4.3 Desktop Apps

- Main desktop controller:
  - `desktop/apps/desktop-tauri`
- Trainer desktop app:
  - `desktop/apps/trainer-tauri`
  - primary UI composition: `desktop/apps/trainer-tauri/src/App.tsx`

### 4.4 Android Client

Key classes:
- `android/app/src/main/java/com/wbeam/MainActivity.java`
- `android/app/src/main/java/com/wbeam/StreamService.java`
- `android/app/src/main/java/com/wbeam/stream/StreamSessionController.java`
- `android/app/src/main/java/com/wbeam/stream/H264TcpPlayer.java`
- `android/app/src/main/java/com/wbeam/stream/DecoderSupport.java`
- `android/app/src/main/java/com/wbeam/stream/StreamNalUtils.java`

### 4.5 Training Domain

- Trainer orchestrator script:
  - `host/training/wizard.py`
- Helper launcher script:
  - `host/training/train_max_quality.sh`
- Training profile artifacts (main-lane):
  - `config/training/profiles.json`
  - `config/training/autotune-best.json`

## 5. Runtime Ownership Model

- Desktop apps are clients, daemon is authority.
- Stream lifecycle/state is daemon-owned, UI-owned state is view/control only.
- Service scope: user-level systemd (`--user`), not root/global service.
- Unit path:
  - `~/.config/systemd/user/wbeam-daemon.service`

## 6. Multi-Device Session Model

Session identity:
- ADB serial + stream_port

Per-device calls:
- `/v1/status?serial=<serial>&stream_port=<port>`
- `/v1/metrics?serial=<serial>&stream_port=<port>`
- `/v1/start?serial=<serial>&stream_port=<port>`
- `/v1/stop?serial=<serial>&stream_port=<port>`

Device port map file:
- `.wbeam_device_ports`

## 7. API Route Quick Map

Core runtime:
- `GET /v1/health`
- `GET /v1/host-probe`
- `GET /v1/status`
- `GET /v1/metrics`
- `POST /v1/start`
- `POST /v1/stop`

Trainer run management:
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
- `POST /v1/trainer/datasets/{run_id}/find-optimal`
- `GET /v1/trainer/devices`
- `GET /v1/trainer/diagnostics`

Trainer live mode:
- `GET /v1/trainer/live/status`
- `POST /v1/trainer/live/start`
- `POST /v1/trainer/live/apply`
- `POST /v1/trainer/live/save-profile`
- compatibility aliases also exist under non-`/v1` prefix: `/trainer/live/*`

## 8. Configuration And Environment

Runtime model is config-first with env override compatibility.

Precedence:
1. process env (explicit override),
2. user config: `~/.config/wbeam/wbeam.conf`,
3. repo defaults: `config/wbeam.conf` (bootstrap template).

Loader helper:
- `host/scripts/wbeam_config.sh`

Important env keys (non-exhaustive):

Service/ports:
- `WBEAM_CONTROL_PORT`
- `WBEAM_STREAM_PORT`
- `WBEAM_DAEMON_SERVICE_NAME`
- `WBEAM_DAEMON_BIN`

Versioning:
- `WBEAM_BUILD_REV`
- `WBEAM_VERSION_BASE`

Android build/deploy:
- `WBEAM_ANDROID_SERIAL`
- `WBEAM_ANDROID_PIPELINE`
- `WBEAM_ANDROID_HOST`
- `WBEAM_ANDROID_API_HOST`
- `WBEAM_ANDROID_STREAM_HOST`
- `WBEAM_ANDROID_FORCE_INSTALL`
- `WBEAM_ANDROID_SKIP_LAUNCH`
- `WBEAM_MIN_SDK`

Remote/watch:
- `WBEAM_DEV_REMOTE_USER`
- `WBEAM_WATCH_INTERVAL`
- `WBEAM_WATCH_LOG`
- `WBEAM_WATCH_COLOR`

Advanced streamer:
- `WBEAM_USE_RUST_STREAMER`
- `WBEAM_RUST_STREAMER_BIN`
- `WBEAM_START_TIMEOUT_SEC`
- `WBEAM_ALLOW_LIVE_ADAPTIVE_RESTART`

Guidance:
- use config/API payloads for reproducible tuning and deploys,
- use env for tactical one-off override.

## 9. Android Pipeline Compatibility

Pipeline selector in `./wbeam`:
- `legacy` -> minSdk 17 (API <= 18),
- `modern` -> minSdk 19+,
- `auto` -> selected from device API.

Operational reality:
- API17 often needs tether/LAN fallback path,
- modern API usually works with `adb reverse` loopback strategy.

## 10. Versioning Contract

Compatibility version must match:
- Android APK `versionName`,
- Host daemon `/health.build_revision`.

Desktop UI app version is not compatibility gate.

Local revision files:
- `.wbeam_build_version`
- `.wbeam_buildno`

Diagnostic command:
- `./wbeam version doctor`

## 11. Observability And Runtime Artifacts

Logs root:
- `logs/`

Typical generated logs:
- `*.desktop.*.log`
- `*.adb.*.log`
- `*.udev.*.log`
- `*.host.*.log`
- `*.version.*.log`
- `desktop-connect.log`

Effective runtime snapshots:
- `logs/effective-runtime/<serial>-<stream_port>.jsonl`

Trainer HUD temporary files (session-scoped):
- `/tmp/wbeam-trainer-active-<serial>-<stream_port>.flag`
- `/tmp/wbeam-trainer-overlay-<serial>-<stream_port>.txt`
- `/tmp/wbeam-trainer-overlay-<serial>-<stream_port>.json`

Git hygiene:
- generated artifacts/logs/build outputs must remain ignored.

## 12. Commands Cheat Sheet

Daily:
- `./wbeam host debug`
- `./wbeam android deploy-all`
- `./wbgui` or `./devtool`
- `./trainer.sh`
- `./wbeam train wizard`
- `./wbeam version doctor`

Watch/diagnostics:
- `./wbeam watch devices`
- `./wbeam watch connections`
- `./wbeam watch streaming`
- `./wbeam watch service`
- `./wbeam watch logs`
- `./wbeam watch doctor`

Remote loop:
- `./start-remote <user> --redeploy`

## 13. Known Failure Modes

Multiple ADB devices:
- symptom: `more than one device/emulator`,
- fix: set serial or use serial-aware deploy-all.

Build mismatch host/APK:
- symptom: startup mismatch/handshake block,
- fix: rebuild + redeploy with shared revision, verify via `version doctor`.

API17 stuck on link/tether checks:
- cause: legacy control/stream reachability path,
- fix: validate tether/LAN host path + review logs.

Foreground app but no stream:
- cause: partial connect success, start/session mapping failure,
- fix: inspect `desktop-connect.log`, `/v1/status`, `/v1/metrics`, watch screens.

Wayland desktop launch issues:
- cause: backend/session constraints and compositor differences,
- fix: use wrappers (`desktop.sh`, `trainer.sh`, `runas-remote`) and inspect logs.

## 14. Practical Tree

```text
WBeam/
  wbeam
  wbgui
  devtool
  trainer.sh
  start-remote
  runas-remote
  android/
  host/
  desktop/
  shared/
  config/
  docs/
  logs/
  proto/
  proto_x11/
  scripts/
  src/                  # compatibility aliases only
    apps -> ../desktop/apps
    host -> ../host
    protocol -> ../shared/protocol
    compat -> ../shared/compat
    domains/training -> ../../host/training
```

## 15. Agent Guardrails

- Keep `./wbeam` as main operational entrypoint.
- Keep daemon as runtime authority; do not move stream/session ownership into UI.
- Keep host/APK compatibility revision coherent.
- Prefer explicit probes/start checks for risky features (capture backend, session type, device readiness).
- Never leak secrets/tokens/internal credentials to commits, logs, release notes, or assets.
- Keep this file aligned with `progress.md`, `docs/agents.workflow.md`, and `docs/repo-structure.md`.
