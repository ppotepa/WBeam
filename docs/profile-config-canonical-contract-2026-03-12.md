# Canonical Profile/Config Contract (Issue #39)

Date: 2026-03-12
Parent epic: #37
Issue: #39
Branch: v0.1.1/e37/i39-canonical-contract

## Decision Summary

1. **Canonical runtime profile identity**: `baseline`.
2. **Canonical runtime preset source**: `config/training/profiles.json` (`profiles.<name>.values.*`).
3. **Canonical alias policy**: legacy names are accepted only as input aliases and normalized to `baseline` at API/CLI boundary.
4. **Archive policy**: `archive/legacy/*` is not an active runtime source for canonical flow.
5. **Desktop runtime profile defaults**: sourced from `desktop/apps/desktop-tauri/src/config/trained-profile-runtime.json`, generated from canonical training source (no alternate path trees).

## Canonical Ownership Model

| Layer | Owns | Reads | Writes |
|---|---|---|---|
| Training wizard | profile search/export outputs | `config/training/profiles.json` | `config/training/profiles.json` and trainer artifacts |
| Daemon core (`wbeamd-core`) | runtime preset resolution | canonical training profiles | runtime state only |
| API (`wbeamd-api/server`) | input normalization + validation | canonical preset model | none (except trainer run artifacts endpoints) |
| Streamer CLI | runtime launch defaults | canonicalized profile id (`baseline`) + explicit overrides | none |
| Desktop app | UX labels/default toggles | trained profile runtime config | none |

## Compatibility Rules

### Accepted input profile names
- Canonical: `baseline`
- Accepted legacy aliases (compat only):
  - `lowlatency`, `balanced`, `ultra`
  - `safe_60`, `aggressive_60`, `quality_60`, `debug_60`
  - `fast60`, `balanced60`, `quality60`, `fast60_2`, `fast60_3`

### Normalization
- Any accepted legacy alias is normalized to `baseline` before config resolution.
- Unknown profile values must return validation error at boundary (API), unless an endpoint explicitly documents fallback behavior.

## Schema Boundary

### Canonical runtime profile data (contract)
Minimal fields expected after mapping from training values:
- `profile` (string)
- `encoder` (`h264|h265|rawpng`)
- `cursor_mode` (`hidden|embedded|metadata`)
- `size` (`WxH`)
- `fps` (u32)
- `bitrate_kbps` (u32)
- `debug_fps` (u32)
- `intra_only` (bool)

### Mapping from training profile values
- `PROTO_CAPTURE_SIZE` -> `size`
- `PROTO_CAPTURE_FPS` -> `fps`
- `PROTO_CAPTURE_BITRATE_KBPS` -> `bitrate_kbps`
- Encoder/cursor defaults are explicit runtime defaults unless exported by trainer payload.

## Explicit Non-Canonical Sources (post-#39 target)
- `archive/legacy/proto/config/profiles.json` as fallback runtime source.
- Hardcoded multi-profile matrices as authoritative source in runtime paths.
- Old compatibility trees like `src/apps/...` as active write targets.

## Implementation Handoff

### For #40 (implementation)
- Remove/isolate archive fallback in core loader.
- Ensure runtime loaders consume canonical source only.
- Fix wizard desktop runtime export path to canonical desktop location.
- Keep compatibility normalization at boundaries.

### For #41 (API alignment)
- Update API/OpenAPI enums and descriptions to canonical model.
- Keep legacy alias acceptance documented as compatibility behavior.

## Acceptance Criteria for #39
- Contract document exists and is reviewable.
- Canonical source/ownership/alias rules are explicit.
- #40 and #41 have clear implementation handoff.
