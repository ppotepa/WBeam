# Repository Structure Source Of Truth

Last updated: 2026-03-11

## Goal

WBeam repository should be easy to navigate by domain first:

1. `android/` -> Android app and runtime decode path
2. `host/` -> host daemon, streamer, runtime scripts
3. `desktop/` -> desktop UI apps and desktop-side integration
4. `shared/` -> protocol/contracts/compat shared by domains

The old `src/*` tree is still present during migration. It remains build-valid but is now treated as a migration source, not a long-term layout.

## Canonical Top-Level Layout (Target)

```text
WBeam/
  android/
  host/
  desktop/
  shared/
  docs/
  scripts/
  config/
  wbeam
  trainer.sh
  desktop.sh
  redeploy-local
```

## Current Migration State (Phase 1)

- Domain boundary folders are introduced:
  - `android/README.md`
  - `host/README.md`
  - `desktop/README.md`
  - `shared/README.md`
- Legacy source paths still host active implementation:
  - `src/host/...`
  - `src/apps/...`
  - `src/protocol/...`
  - `src/domains/...`
  - `src/compat/...`

## Old -> New Mapping

- `src/host/rust` -> `host/rust`
- `src/host/scripts` -> `host/scripts`
- `src/host/daemon` -> `host/daemon`
- `src/apps/desktop-tauri` -> `desktop/apps/desktop-tauri`
- `src/apps/trainer-tauri` -> `desktop/apps/trainer-tauri`
- `src/protocol/rust` -> `shared/protocol/rust`
- `src/compat/*` -> `shared/compat/*`
- `src/domains/training` -> `host/training` (runtime-owned domain with desktop clients)

## Migration Rules

1. New code goes to target domain paths whenever practical.
2. If a change must touch legacy `src/*`, add a note in PR/commit explaining why.
3. Keep wrappers (`./wbeam`, `./trainer.sh`, `./desktop.sh`) stable during migration.
4. Migrate in small, build-safe slices.
5. Remove compatibility shims only after scripts/CI/docs are rewired.

## Structure Validation

Use:

```bash
scripts/ci/check-repo-layout.sh
```

Strict mode (fails while legacy paths still exist):

```bash
WBEAM_LAYOUT_STRICT=1 scripts/ci/check-repo-layout.sh
```
