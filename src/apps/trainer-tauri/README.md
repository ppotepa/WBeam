# WBeam Trainer Tauri

Dedicated Trainer V2 GUI for WBeam training domain.

## Purpose

The app provides a workstation-style UI for:
- running preflight and starting/stopping training runs,
- watching live HUD/log tail for active run,
- browsing profiles and run artifacts,
- comparing profile runtime parameters,
- checking device list and diagnostics.

## Runtime dependency

Requires host daemon API on `http://127.0.0.1:5001` (or proxied equivalent).

## Run

```bash
./trainer.sh --ui
```

or directly:

```bash
cd src/apps/trainer-tauri/frontend
npm install
npm run dev
```

## Structure

- `frontend/` Solid + TypeScript UI
- `backend/` Tauri Rust backend and app config
