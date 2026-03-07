# WBeam Progress

Date: 2026-03-07
Branch: `master`
Status: active

## Current Baseline (Authoritative)
- Desktop lane was reset to a clean Tauri 2 + SolidJS + TypeScript starter.
- Service/systemd control was removed from primary operator scripts.
- Host flow is now process-based from scripts (`host run/debug/down`), not `wbeam service ...`.
- Log naming in debug host scripts uses host domains (`*.host.*.log`, `*.host-rust.*.d`).

## Latest Completed Commit
- `1060a3f5` - `chore: track logs directory sentinel`
- `8b8db0c8` - `docs: refresh progress.md to current baseline and verified state`
- `3d632425` - `refactor: drop service scripts and reset desktop-tauri to Solid/TS baseline`
- Scope:
  - scripts cleanup: `wbeam`, `wbgui`, `devtool`, `start-remote`, `src/host/scripts/run_wbeamd_debug.sh`, `src/host/scripts/watch_live_debug_logs.sh`
  - desktop reset: `src/apps/desktop-tauri/*` (new Solid/TS scaffold), minimal Tauri backend in `src-tauri/src/main.rs`
  - cleanup: removed legacy static UI files under `src/apps/desktop-tauri/ui/`
  - gitignore update for Tauri frontend artifacts (`node_modules`, `dist`)
  - repo hygiene: `logs/.gitkeep` tracked to keep logs directory stable and avoid accidental log commits

## Runtime Verification (2026-03-07)
- `bash -n wbeam devtool wbgui start-remote src/host/scripts/run_wbeamd_debug.sh src/host/scripts/watch_live_debug_logs.sh` -> OK
- `cd src/apps/desktop-tauri && npm install` -> OK
- `cd src/apps/desktop-tauri && npm run build` -> OK
- `cargo check --manifest-path src/apps/desktop-tauri/src-tauri/Cargo.toml` -> OK

## Current Start Commands
- Desktop GUI: `./devtool gui`
- Host debug: `./wbeam host debug`
- Android deploy: `./wbeam android deploy`
- One-shot remote path: `./start-remote [desktop_user]`

## Known Next Steps
- Rebuild desktop features on top of the new Solid/TS baseline (device discovery, deploy, stream controls).
- Add platform-host abstraction layer incrementally (Linux X11/Wayland first, Windows next).
- Keep this file updated after each commit with exact scope and verification results.
