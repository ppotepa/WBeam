# Shared Domain

Canonical shared domain boundary.

## Purpose

`shared/` contains reusable cross-domain contracts:

- protocol crates and scripts,
- compatibility layers,
- stable schemas and shared definitions.

## Migration Status

Active implementation still lives in:

- `src/protocol`
- `src/compat`

Target location for migrated code:

- `shared/protocol`
- `shared/compat`

## Contract

- Keep shared modules domain-neutral.
- Avoid host- or desktop-specific runtime logic in shared code.
