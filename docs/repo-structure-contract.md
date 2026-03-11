# Repository Structure Contract

This document defines the current repository layout, target layout, and
migration rules for keeping the project understandable for external
contributors.

## Current Active Production Paths

- `android/` - Android app (`com.wbeam`)
- `src/host/rust/` - host daemon/core/streamer crates
- `src/host/scripts/` - host runtime scripts
- `src/apps/desktop-tauri/` - desktop UI app
- `src/apps/trainer-tauri/` - trainer desktop app
- `wbeam`, `wbgui`, `devtool`, `start-remote` - operational entrypoints

## Experimental / Historical Paths

- `proto/` - historical R&D lane
- `proto_x11/` - X11 prototype lane

These are not the default production path.

## Target App Layout (Desktop Apps)

For each app under `src/apps/<app>`:

- `frontend/` - TypeScript/Vite/Solid frontend
- `backend/` - Tauri Rust backend

During migration, legacy paths may temporarily coexist:

- `src/` (legacy frontend path)
- `src-tauri/` (legacy backend path)

## Path Policy

1. New production code should not be added under `proto*` paths.
2. New app code should prefer `frontend/` and `backend/` naming.
3. New nested `src/.../src` patterns should be avoided unless explicitly justified.
4. Legacy paths should be removed incrementally, not all-at-once.

## Migration Sequence

1. Introduce structural checks in CI (warn mode).
2. Migrate app paths with compatibility handling in scripts.
3. Isolate experimental lanes under archive or external repo.
4. Flip CI checks to enforce mode.

