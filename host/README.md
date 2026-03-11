# Host Domain

Canonical host domain boundary.

## Purpose

`host/` owns runtime authority:

- daemon lifecycle and API,
- capture/encode/transport pipeline,
- session/adaptation state machine,
- host-side deployment/runtime scripts.

## Migration Status

Active implementation still lives in:

- `src/host/rust`
- `src/host/scripts`
- `src/host/daemon`

Target location for migrated code:

- `host/rust`
- `host/scripts`
- `host/daemon`

## Contract

- UI clients must treat host as authority.
- Runtime decisions must be observable through host API and logs.
