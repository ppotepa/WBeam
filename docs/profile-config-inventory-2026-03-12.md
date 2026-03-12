# Profile/Config Source Inventory (Issue #38)

Date: 2026-03-12
Parent epic: #37
Issue: #38
Branch: v0.1.1/e37/i38-inventory

## Objective
Map all active profile/config sources and readers to prepare canonical source contract work.

## Summary
Current state is mixed:
- Canonical intent points to `baseline` profile and `config/training/profiles.json`.
- Active code still includes legacy aliases and fallback paths.
- Multiple components keep local defaults that can drift from canonical training data.

## Source Map

| Source | Current role | Readers/Writers | Classification |
|---|---|---|---|
| `config/training/profiles.json` | Main training profile export | Read in core preset loader (`wbeamd-core`) | Canonical candidate |
| `archive/legacy/proto/config/profiles.json` | Archived fallback | Read as fallback in core preset loader | Legacy fallback (to remove/isolate) |
| `config/training/autotune-best.json` | Latest best trial snapshot | Read by tooling/docs flow, not core preset loader | Supporting artifact |
| `desktop/apps/desktop-tauri/src/config/trained-profile-runtime.json` | Desktop runtime defaults by profile id | Imported in desktop UI (`App.tsx`) | Active duplicate source |
| `host/rust/crates/wbeamd-api::presets()` | Hardcoded baseline preset map | Used by validation paths and defaults | Active duplicate source |
| `host/rust/crates/wbeamd-streamer/src/cli.rs` profile defaults | Large in-code legacy profile matrix | Used by streamer CLI resolution | Active duplicate source |
| `host/rust/config/presets.toml` | Static preset file in repo | No active references found | Dead-candidate |
| `host/daemon/wbeamd.py` `PRESETS` | Python daemon preset model | Python daemon path (legacy lane) | Legacy lane source |

## Key Code References

### Core preset loading
- `host/rust/crates/wbeamd-core/src/lib.rs:349`
  - loads from `config/training/profiles.json` and falls back to `archive/legacy/proto/config/profiles.json`.
- `host/rust/crates/wbeamd-core/src/lib.rs:425`
  - default selection prefers `baseline` key.

### API-level profile canonicalization
- `host/rust/crates/wbeamd-api/src/lib.rs:8`
  - canonical profile constant = `baseline`.
- `host/rust/crates/wbeamd-api/src/lib.rs:10`
  - legacy alias list retained.
- `host/rust/crates/wbeamd-api/src/lib.rs:434`
  - `presets()` returns hardcoded baseline map.

### Streamer CLI profile resolution
- `host/rust/crates/wbeamd-streamer/src/cli.rs:27`
  - CLI accepts legacy profile names.
- `host/rust/crates/wbeamd-streamer/src/cli.rs:116`
  - defaults matrix is encoded in source.
- `host/rust/crates/wbeamd-streamer/src/cli.rs:329`
  - canonicalization maps most legacy names to `baseline`.

### Trainer + desktop profile artifacts
- `host/training/wizard.py:30`
  - writes profile baseline to `config/training/profiles.json`.
- `host/training/wizard.py:33`
  - desktop runtime export path points to old `src/apps/...` tree.
- `desktop/apps/desktop-tauri/src/App.tsx:28`
  - desktop app imports `trained-profile-runtime.json`.

### Trainer profile API surface (separate profile store)
- `host/rust/crates/wbeamd-server/src/main.rs:1825`
  - `/v1/trainer/profiles` reads profile docs from trainer profile root.
- `host/rust/crates/wbeamd-server/src/main.rs:1849`
  - profile detail from `<profile_name>/<profile_name>.json`.

### Documentation drift signals
- `docs/openapi.yaml:196`
  - still enumerates `lowlatency|balanced|ultra` in `ActiveConfig`.
- `docs/openapi.yaml:223`
  - same drift for `ConfigPatch` profile enum.
- `docs/openapi.yaml:436`
  - `PresetsResponse` description still legacy-oriented.

## Inventory Decisions (for Issue #39)

1. Canonical profile identity should remain `baseline` + canonical alias normalization at input boundary.
2. Canonical storage for runtime presets should be formalized as one source (`config/training/profiles.json` candidate).
3. Legacy archived fallback in core loader should be removed or isolated behind explicit compatibility switch.
4. Duplicated in-code preset defaults (`wbeamd-api`, streamer CLI) should be aligned with canonical source or turned into compatibility shim only.
5. Desktop runtime export path in `wizard.py` must be corrected to canonical desktop app path before contract finalization.

## No-behavior-change note
This issue is inventory only (documentation + mapping). No runtime behavior changed.
