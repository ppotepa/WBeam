# Desktop Domain

Canonical desktop domain boundary.

## Purpose

`desktop/` owns desktop UX clients:

- main desktop control app,
- trainer desktop app,
- desktop-side service wrappers/integration glue.

Desktop remains a client of host daemon API.

## Migration Status

Active implementation still lives in:

- `src/apps/desktop-tauri`
- `src/apps/trainer-tauri`

Target location for migrated code:

- `desktop/apps/desktop-tauri`
- `desktop/apps/trainer-tauri`

## Contract

- Do not move stream/session business logic into desktop UI.
- Desktop sends intent and renders status/metrics from host authority.
