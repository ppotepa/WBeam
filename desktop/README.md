# Desktop Client

The desktop client lives in
[`apps/desktop-tauri`](apps/desktop-tauri/README.md). It provides device discovery,
connect/disconnect controls, host service integration, profiles and status.

SolidJS/TypeScript renders the UI. The Tauri Rust bridge handles ADB, service
wrappers and host API requests. The daemon remains the authority for stream
lifecycle and session state.

Run `./desktop.sh` from the repository root after
[setup](../docs/INSTALLER_WIZARD.md). See the
[app guide](apps/desktop-tauri/README.md) for direct development commands and the
[architecture map](../docs/repo-structure.md) for domain boundaries.
