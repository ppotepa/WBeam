# WBeam Update Summary (v0.1.1 lane)

Last updated: 2026-03-11  
Branch: `v0.1.1/feature/runtime-hud-layout`

## 1. HUD and Overlay Work

- Split HUD behavior by connection mode to avoid trainer HUD leaking into normal runtime connect flow.
- Added explicit routing through `connection_mode` so trainer/runtime can render different layouts.
- Unified Android overlay rendering path and added dedicated builders:
  - runtime HUD HTML
  - trainer HUD HTML (SOT-based).
- Added trainer HUD structured payload flow:
  - host trainer writes `.txt` + `.json` HUD snapshots in `/tmp`,
  - daemon exposes `trainer_hud_active`, `trainer_hud_text`, `trainer_hud_json` via metrics.
- Added trainer progress rendering (trial counters + percent).
- Added stronger placeholder/fallback behavior when partial HUD data is available.
- Added tone-based thresholds and trend sections for key metrics (score/fps/mbps/latency/drops/queue and recv/decode in trainer path).
- Added canonical trainer HUD SOT documentation and maquette:
  - `docs/ui/hud/trainer-hud.sot.svg`
  - `docs/ui/hud/README.md`

## 2. Trainer and Live Control Improvements

- Hardened trainer startup flow when user-level systemd daemon is unhealthy:
  - fallback to host foreground run path.
- Added/extended trainer live APIs and desktop trainer wiring for:
  - start/apply/save-profile flows,
  - live stats tab split from live control tab.
- Added runtime diagnostics and visibility improvements:
  - better status/error reporting for daemon unreachable states.
- Improved trainer state handling and runtime snapshot visibility in host stack.

## 3. Android/Streaming Telemetry and Runtime Visibility

- Improved Android e2e metrics handling and HUD visibility behavior.
- Added transport/runtime metrics exposure from streamer into daemon metrics path.
- Added deterministic build suffix/versioning behavior based on commit hash segment.
- Improved H264 runtime diagnostics and bitrate floor coherence behavior.

## 4. Desktop and Launcher Stability

- Desktop launcher now handles stale Tauri instances before relaunch.
- Vite/lucide import stability improvements to reduce dev-time crashes.
- Better daemon-state interpretation in desktop app:
  - handles activating/unreachable states more accurately.
- Desktop device list now remains visible even if daemon service is inactive.
- Launch wrappers improved for remote session routing and xauth correctness.
- Host scripts fixed to resolve repo root correctly for daemon service operations.

## 5. Repo Structure, Cleanup, and CI

- Migrated to domain-first layout (`host/`, `desktop/`, `android/`, `shared/`) with compatibility aliases in `src/`.
- Archived legacy prototype lanes under `archive/legacy`.
- Added/expanded CI quality gates:
  - repository layout checks,
  - path-boundary checks,
  - canonical-vs-compat E2E matrix validation.
- Updated workflow/docs for `v0.1.1` lane naming and feature branch policy.
- Expanded AGENTS handbook and architecture maps.
- Cleanup of stale HUD/trainer docs/maquettes; SOT retained.

## 6. Current Known Gaps (still to finish)

- Trainer `LIVE METRICS` area can still render empty in some runs.
- Some live values (especially MBPS/trends) can be delayed/missing depending on metric payload completeness.
- Trainer chart cells do not always fully utilize available panel area.
- Need final hard guarantee that only one overlay surface is active at a time in all paths.

## 7. Key Commits (recent/high-impact)

- `c89c9b53` hud: gate trainer overlay by explicit connection_mode
- `8b08b057` hud: fix trainer metrics routing and split runtime/trainer html layouts
- `afbd3522` android-hud: switch trainer overlay to SOT layout
- `f58c7302` docs(hud): add canonical trainer HUD SOT maquette with data legend
- `ad80e020` fix(launcher): harden trainer daemon startup and adb preflight
- `d6f81e5f` fix(launch): route tauri to active remote session and correct xauth
- `9ebee3e0` fix(trainer): fallback to host-run when systemd start is unhealthy
- `2a401f5c` refactor: move codebase to domain-first paths with src compatibility aliases
