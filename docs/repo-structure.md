# Repository Structure

Canonical repository layout and migration map for contributors.

## Active Production Lanes

- `android/` - Android decode client (`com.wbeam`).
- `src/host/` - host runtime (daemon, streamer, scripts).
- `src/apps/desktop-tauri/frontend` - desktop UI frontend.
- `src/apps/desktop-tauri/backend` - desktop UI backend (Tauri).
- `src/apps/trainer-tauri/frontend` - trainer UI frontend.
- `src/apps/trainer-tauri/backend` - trainer UI backend (Tauri).
- `wbeam`, `wbgui`, `devtool`, `trainer.sh`, `start-remote` - main entrypoints.

## Experimental/Historical Lanes

- `archive/experimental/proto/` - historical R&D lane.
- `archive/experimental/proto_x11/` - X11 prototype lane.

Treat these as non-default runtime paths.

## Desktop App Contract

For each app under `src/apps/<app>`:

- `frontend/` owns TypeScript/Vite/Solid code.
- `backend/` owns Tauri Rust code and config.

Legacy `src/` and `src-tauri/` paths are deprecated and should not receive new code.

## Migration Status (as of 2026-03-11)

1. CI structure checker in warn mode: enabled.
2. Desktop app split to `frontend/backend`: completed.
3. Experimental lane isolation (`archive/experimental`): completed.
4. CI enforce mode: pending.
