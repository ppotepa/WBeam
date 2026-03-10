# WBeam Trainer Tauri (Scaffold)

Initial TrainerV2 GUI scaffold.

## Purpose

This app is the dedicated frontend for training workflows:
- configure training runs,
- observe full live HUD telemetry,
- browse and compare generated profiles.

Current state is scaffold-only and uses placeholder telemetry.

## Run

```bash
cd src/apps/trainer-tauri
npm install
npm run dev
```

## Current tabs

- `Train`
- `Live HUD`

## Next planned wiring

- connect to trainer API endpoints,
- consume live event stream,
- profile browser and compare views,
- service bootstrap/status integration.
