# WBeam Progress Log

## Progress Update Policy (Required)
- `progress.md` is a mandatory source-of-truth file.
- After every code/docs commit, append/update:
  - date,
  - scope/focus,
  - commit hash + one-line reason,
  - runtime verification status (if executed),
  - known follow-ups/risks.
- No commit should be left without a matching progress note.
- Keep entries chronological and concise, but include enough context to recover workstream after interruption.

Date: 2026-03-07
Focus: Main lane stabilization (API17 path), host abstraction, deploy/runtime reliability

## Current Branch State
- Branch: `master`
- Latest pushed commits:
  - `a3e6802a` - enforce progress update policy + latest changelog in `progress.md`
  - `ed85be41` - full 2026-03-07 integration status added to `progress.md`
  - `2058d5f9` - `runas-remote` secure env passthrough + README note
  - `c04f7986` - proto profile integration + host probing + remote/start/logging flow
  - `1d1d5b21` - baseline consolidation into devtool-centric main lane

## Delivery Milestones Completed (Chronological)
1. Android API17 decoder bootstrap path integrated in main app:
   - delayed decoder init until SPS/PPS
   - in-stream CSD extraction and codec config queueing
   - profile set expanded with proto-trained presets (`fast60*`, `safe_60`, `quality60`, etc.)
2. Host preset migration from proto to main Rust daemon/streamer:
   - daemon loads profiles from `proto/config/profiles.json`
   - `/v1/presets` now returns proto set (verified count: 9)
   - validation and apply/start flow bound to loaded preset map
3. Host runtime abstraction for capture/session probing:
   - Linux session detection (`x11`/`wayland`) + capture mode resolution
   - backend split introduced in streamer (`backend/{x11,wayland}`)
4. Streamer/runtime tuning migration from proto:
   - profile-driven queue/appsink/timeout/keepalive/GOP knobs
   - transport sender behavior moved under profile-resolved config
5. Android deploy and IP path hardening:
   - legacy host IP selection prefers tether gateway from device route
   - release install skip logic fixed to include baked config stamp, not only version
6. Remote one-shot workflow:
   - `start-remote` script added (service restart + android deploy + desktop launch via runas)
   - logs standardized in `logs/` as `YYYYMMDD-HHMMSS.<domain>.<run>.log`
7. Security hardening for remote launcher:
   - `runas-remote` now supports `RUNAS_REMOTE_PASSTHROUGH_ENV`
   - `runas-remote` now supports `RUNAS_REMOTE_ENV_FILE`
   - `RUNAS_REMOTE_QUIET=1` to suppress startup metadata line
   - README note added to keep secrets out of CLI args

## Runtime Verification (2026-03-07)
- `./wbeam service restart` OK, host probe shows `session=x11`, `capture_mode=x11_gst`
- `./wbeam android deploy-release` builds/installs with baked host `192.168.42.239`
- Android no longer uses stale `192.168.100.208`; control API points to `192.168.42.239:5001`
- Host stream enters `STREAMING`; host logs show client connect + stable ~58-60 fps after reconnect
- `./start-remote ppotepa` launches desktop app in remote X11 session (`display=:10`)

## Known Follow-ups
- Continue cross-platform host backend abstraction:
  - keep Linux `x11/wayland` as primary
  - add Windows backend lane next
- Improve final cleanup around local cache/log artifacts in working tree during long debug sessions.

## Incremental Changelog (Latest)
- `a3e6802a` - enforced mandatory `progress.md` update policy and changelog block.
- `ed85be41` - updated `progress.md` with full 2026-03-07 integration status.
- `2058d5f9` - `runas-remote` secure env passthrough (`RUNAS_REMOTE_PASSTHROUGH_ENV`, `RUNAS_REMOTE_ENV_FILE`, quiet mode) + README note.
- `c04f7986` - main integration batch: proto profile migration, host probe/backends, deploy/start/logging flow, API17 path.

## Fast Context Index (Date + Key Files)
- `2026-03-07` `9ccfb110` docs ledger completion:
  - `progress.md`
- `2026-03-07` `a3e6802a` progress policy enforcement:
  - `progress.md`
- `2026-03-07` `ed85be41` full integration status note:
  - `progress.md`
- `2026-03-07` `2058d5f9` secure remote env passthrough:
  - `runas-remote`
  - `README.md`
- `2026-03-07` `c04f7986` core integration batch:
  - Android:
    - `android/app/src/main/java/com/wbeam/MainActivity.java`
    - `android/app/src/main/java/com/wbeam/stream/H264TcpPlayer.java`
    - `android/app/build.gradle`
  - Host API/core/server:
    - `src/host/rust/crates/wbeamd-api/src/lib.rs`
    - `src/host/rust/crates/wbeamd-core/src/lib.rs`
    - `src/host/rust/crates/wbeamd-core/src/infra/config_store.rs`
    - `src/host/rust/crates/wbeamd-core/src/infra/host_probe.rs`
    - `src/host/rust/crates/wbeamd-server/src/main.rs`
  - Streamer/backend split:
    - `src/host/rust/crates/wbeamd-streamer/src/backend/mod.rs`
    - `src/host/rust/crates/wbeamd-streamer/src/backend/x11.rs`
    - `src/host/rust/crates/wbeamd-streamer/src/backend/wayland.rs`
    - `src/host/rust/crates/wbeamd-streamer/src/{cli.rs,pipeline.rs,transport.rs,encoder.rs,main.rs}`
  - Ops/scripts:
    - `wbeam`
    - `runas-remote`
    - `start-remote`
    - `src/host/scripts/run_wbeamd.sh`
    - `src/host/scripts/run_wbeamd_debug.sh`
    - `src/host/scripts/watch_live_debug_logs.sh`
    - `wbgui`
- `2026-03-06` `1d1d5b21` baseline consolidation:
  - primary product files across `android/`, `src/host/`, root launchers (`wbeam`, `wbgui`, `devtool` lane)
  - note: commit contains large `.gradle-user` noise; use this index instead of raw file list for fast context

## Commit Ledger (Rolling)
- `a3e6802a` - docs: enforce progress.md update policy and latest changelog
- `ed85be41` - docs: update progress log for 2026-03-07 integration work
- `2058d5f9` - runas-remote: add secure env passthrough and usage note
- `c04f7986` - Integrate proto profiles, host probing, and remote startup/logging flow
- `1d1d5b21` - feat: consolidate post-blank-ui migration into single devtool-centric baseline
- `f1472156` - add full deploy script
- `31c5479b` - move repo to src-first layout (phase 1+2)
- `e155d481` - update repo structure
- `348f1073` - desktop app cleanup keep simple ui + mode print
- `82033f2d` - add svg assets, split forms into SRP

Date: 2026-03-05
Focus: Desktop Tauri lane + Android/Host delivery wiring

## Current Branch State
- Branch: `master`
- Recent work concentrated on: `src/apps/desktop-tauri`
- Desktop egui lane still present, with separate WIP changes not merged in these notes.

## Delivery Milestones Completed (Chronological)
1. `40d025c5` - Tauri preview shell and launcher added.
2. `5aaaa094` - Real ADB probe wired into Tauri frontend.
3. `94b29e0d` - Deploy-all action wired from UI.
4. `41d556c4` - Selected-device ADB actions wired.
5. `c70e10c1` - Session start/stop wired to ADB reverse tunnels.
6. `c4f3f0ac` - Per-device APK install action added.
7. `4313082f` - Command output drawer added.
8. `850c1c95` - Host service controls + host checks added.
9. `4239ac22` - Readiness badges + silent host polling.
10. `56de38aa` - Session preflight checks + remediation hints.
11. `119ab019` - Session wired to host `/v1/start` and `/v1/stop`.
12. `b275551e` - Live `/v1/metrics` polling with KPI states.

## What Works Now (Tauri Lane)
- ADB device discovery and selection from desktop UI.
- Device detail rendering (state, manufacturer, model, API, ABI, battery fields when available).
- Selected-device operations:
  - `get_state`
  - `reverse_default`
  - `forward_default`
  - `clear_forwards`
  - install APK on selected serial
- Deployment operations:
  - deploy-all via existing script
  - per-device install
- Host operations:
  - `service status`
  - `service up`
  - `service down`
- Session lifecycle:
  - readiness checks
  - ADB tunnel setup/teardown
  - host stream `/v1/start` + `/v1/stop`
  - rollback if stream start fails after tunnel setup
- Observability:
  - event timeline
  - full stdout/stderr/exit code drawer
  - live metrics polling while session active
  - KPI coloring and stale-metrics indicator

## Remaining High-Value Work
- Add richer session state mapping from host `/v1/status` (not only local UI flags).
- Add full metrics panel details (queues, adaptive level, reasons, uptime) beyond current compact lines.
- Add stronger action locking/serialization to prevent conflicting commands under heavy clicking.
- Add test coverage for command parsing + UI state reducers.
- Add clearer failure-classification messages (adb missing vs unauthorized vs host not reachable vs API timeout).

## Known Risks / Constraints
- Host service control depends on local daemon lifecycle (`./devtool service ...`).
- Build/deploy reliability is environment-dependent (Android SDK/Gradle host setup).
- ADB quality varies with cable/device state (unauthorized/offline).
- Tauri UI currently owns orchestration logic with shell command wrappers; deeper host API-native orchestration can still improve robustness.

## Local Environment Notes
- User tmux profile cloned to: `/home/ppotepa/git/tmux-profile`
- `wsad.ini` applied to: `~/.tmux.conf`
- Backup created: `~/.tmux.conf.backup-20260305-223509`
