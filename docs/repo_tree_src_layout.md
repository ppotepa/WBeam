# WBeam `src/`-first Repository Layout (Proposed)

This is the target structure to make the repo consistent and modular while keeping legacy entrypoints stable during migration.

## Target Tree

```text
WBeam/
  src/
    apps/
      android/                 # main Android app (current: android/)
      desktop-egui/            # desktop control app (migrated in phase 1)
    host/
      daemon/                  # python fallback daemon (migrated in phase 2)
      rust/                    # rust host crates + scripts + config (migrated in phase 2)
      scripts/                 # capture/adb/runtime scripts (migrated in phase 2)
    protocol/
      rust/                    # WBTP transport crates (migrated in phase 2)
    compat/
      api17/
      api21/
      api29/                   # resolver policy packs (migrated in phase 2)
    proto/
      config/
      front/
      host/
      host-cs/
      scripts/                 # current: proto/
    assets/                    # shared assets (migrated in phase 1)
    tools/
      scripts/                 # current: scripts/
      diagnostics/             # current: scripts/diagnostics/audodaignose, related helpers
  docs/
  scripts/                     # top-level wrappers only (stable UX)
    desktop.sh
    wbeam
    wbgui
    install-deps
    upload-android.sh
```

## Why This Split

- `src/apps`: UI/client applications only.
- `src/host`: host runtime and backend services.
- `src/protocol`: transport/protocol implementation independent of host/app.
- `src/compat`: policy/capability resolvers by API/runtime level.
- `src/proto`: experimentation lane isolated from production lane.
- `src/tools`: operational scripts and diagnostics.

## Migration Rules

1. Keep root wrappers stable (`desktop.sh`, `wbeam`, `wbgui`) while paths move under `src/`.
2. Move directories in small batches and update references in the same commit.
3. Update all hardcoded paths in:
   - shell scripts (`src/host/scripts`, root scripts),
   - Rust path joins (`src/host/rust/crates/*`),
   - Cargo path dependencies (`path = ...`),
   - docs/readme examples.
4. After each batch, run smoke checks:
   - desktop launch,
   - proto run,
   - host daemon run.

## Phase Plan

### Phase 1 (low risk) - Done
- Move:
  - `desktop/desktop-egui` -> `src/apps/desktop-egui`
  - `assets` -> `src/assets`
- Keep `desktop.sh` at root as wrapper to new manifest path.

### Phase 2 (medium risk) - Done
- Move:
  - `host` -> `src/host`
  - `protocol` -> `src/protocol`
  - `compat` -> `src/compat`
- Update all script + Rust path joins.

### Phase 3 (high risk)
- Move:
  - `proto` -> `src/proto`
  - `scripts` + diagnostics -> `src/tools/...`
- Update proto lane references and generated-path assumptions.

### Phase 4 (cleanup)
- Keep only wrappers and meta files in repo root.
- Remove outdated path references in docs and helper scripts.
