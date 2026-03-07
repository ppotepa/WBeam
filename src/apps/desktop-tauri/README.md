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
cd src/apps/desktop-tauri
npm install
npm run tauri dev
```

## Structure

- `src/` Solid + TS UI
- `src-tauri/` Tauri Rust backend and app config
