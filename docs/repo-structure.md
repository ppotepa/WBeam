# Repository Structure Source Of Truth

Last updated: 2026-03-12

## Goal

WBeam repository should be easy to navigate by domain first:

1. `android/` -> Android app and runtime decode path
2. `host/` -> host daemon, streamer, runtime scripts
3. `desktop/` -> desktop UI apps and desktop-side integration
4. `shared/` -> protocol/contracts/compat shared by domains

## Canonical Top-Level Layout

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

## Current Migration State (Phase 3)

- Domain boundary folders are active:
  - `android/README.md`
  - `host/README.md`
  - `desktop/README.md`
  - `shared/README.md`
- Primary implementations are active under domain roots:
  - `host/rust`, `host/scripts`, `host/daemon`, `host/training`
  - `desktop/apps/desktop-tauri`, `desktop/apps/trainer-tauri`
  - `shared/protocol`, `shared/compat`
- Compatibility wrappers are removed:
  - no `src/*` alias layer
  - no root-level `proto` / `proto_x11` aliases

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

1. New code goes to canonical domain paths.
2. Do not add new compatibility wrappers or alias trees.
3. Keep wrappers (`./wbeam`, `./trainer.sh`, `./desktop.sh`) stable.
4. Keep CI guard scripts green for structure and boundary checks.

## Archive Policy

- Legacy prototype lanes were removed from the repository.
- Canonical runtime, CI, and docs must not depend on `archive/legacy/*`.

## Structure Validation

Use:

```bash
scripts/ci/check-repo-layout.sh
scripts/ci/check-boundaries.sh
scripts/ci/validate-e2e-matrix.sh
```
