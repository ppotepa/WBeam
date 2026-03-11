# WBeam Desktop Tauri Baseline

Clean baseline for the desktop app using:

- Tauri 2 (Rust backend)
- SolidJS + TypeScript (frontend)
- Vite (build/dev server)

## Run

From repo root:

```bash
./devtool gui
```

Or directly:

```bash
cd src/apps/desktop-tauri/frontend
npm install
npm run tauri:dev
```

## Structure

- `frontend/` Solid + TypeScript UI
- `backend/` Tauri Rust backend and app config
