# WBeam Desktop Tauri Preview

This is a separate desktop UI preview using Tauri so it can be compared against the `desktop-egui` app.

## Run

From repo root:

```bash
./devtool gui
```

## Structure

- `ui/` static frontend (HTML/CSS mock of the 3-panel layout)
- `src-tauri/` Rust Tauri shell and window config

## Notes

- This is intentionally isolated from `desktop-egui`.
- Current UI is a static preview scaffold to validate look-and-feel before wiring real backend state/actions.
