# WayBeam Desktop

Tauri 2 application with SolidJS, TypeScript and Vite. The frontend talks to
the Rust bridge; the bridge calls the host daemon and manages local ADB/service
integration.

## Run

From the repository root, after [setup](../../../docs/INSTALLER_WIZARD.md):

```bash
./desktop.sh
# Build and run the release-mode desktop binary:
./desktop.sh --release
```

The launcher loads WayBeam user configuration and establishes the graphical
session environment. Native builds need Rust, Node/npm and the system Tauri
dependencies installed by the setup flow.

For direct development, run inside this directory:

```bash
npm ci
npm run tauri dev
```

`npm run dev` starts only Vite; browser preview alone does not provide Tauri
commands, ADB or service integration.

## Validate and Navigate

From this directory:

```bash
npm test
npm run build
```

The build command checks TypeScript and produces frontend assets. Native Tauri
compilation is a separate step (`npm run tauri -- build --no-bundle`).

| Path | Responsibility |
|---|---|
| `src/App.tsx`, `src/components/` | Controls, dialogs and device presentation |
| `src/managers/` | Client-side polling, validation and action coordination |
| `src-tauri/src/` | Host API, ADB, service and display integration |
| `src-tauri/tauri.conf.json` | Window, CSP and application configuration |
| `tests/` | Node-based behavior and source-contract checks |

See [security boundaries](../../../docs/SECURITY_RELEASE.md) and the
[repository map](../../../docs/repo-structure.md). X11 helpers and the startup
notice remain for legacy handling; current release capture choices are EVDI
and Wayland Portal.
