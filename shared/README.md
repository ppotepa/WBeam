# Shared Domain

Canonical shared domain boundary.

## Purpose

`shared/` contains reusable cross-domain contracts:

- protocol crates and scripts,
- compatibility layers,
- stable schemas and shared definitions.

## Migration Status

Active implementation lives in:

- `shared/protocol`
- `shared/compat`

## Contract

- Keep shared modules domain-neutral.
- Avoid host- or desktop-specific runtime logic in shared code.
