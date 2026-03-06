# WBeam Progress Log

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
