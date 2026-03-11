# Host Domain

Canonical host domain boundary.

## Purpose

`host/` owns runtime authority:

- daemon lifecycle and API,
- capture/encode/transport pipeline,
- session/adaptation state machine,
- host-side deployment/runtime scripts.

## Migration Status

Active implementation lives in:

- `host/rust`
- `host/scripts`
- `host/daemon`

Compatibility aliases remain under `src/host` during transition.

## Contract

- UI clients must treat host as authority.
- Runtime decisions must be observable through host API and logs.
