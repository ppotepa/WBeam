# Repository Structure

## Top-Level Layout

```
WBeam/
├── wbeam                  # main CLI entrypoint
├── wbgui                  # interactive TUI menu
├── devtool                # dev convenience wrapper
├── desktop.sh             # desktop app launcher
├── redeploy-local         # full rebuild + deploy + launch
├── start-remote           # remote session bootstrap
├── runas-remote           # run command as another desktop user
│
├── android/               # Android client (APK)
├── host/                  # Linux host (daemon, streamer, tuner)
├── desktop/               # Desktop UI apps (Tauri)
├── shared/                # Cross-domain contracts and protocol
├── config/                # Config templates and trainer profiles
├── scripts/               # Setup, diagnostics, CI scripts
├── proto/                 # Protocol buffer definitions
├── docs/                  # Documentation
└── logs/                  # Runtime logs (gitignored)
```

## Host (`host/`)

Rust workspace at `host/rust/` with 5 crates:

| Crate | Role |
|-------|------|
| `wbeamd-api` | Shared API models and response contracts |
| `wbeamd-core` | Session state, adaptation policy, process supervision |
| `wbeamd-server` | HTTP router (axum), trainer endpoints, session registry |
| `wbeamd-streamer` | Capture → encode → packetize → transport pipeline |
| `wbeamd-tuner` | Interactive TUI autotuner (ratatui) |

Shell helpers in `host/scripts/`: config loader, debug launcher, soak tests.

## Android (`android/`)

Single Gradle module: `com.wbeam`

Key packages:
- `stream/` — video decode loops (H.264, PNG, framed protocol)
- `api/` — host API client, status polling
- `hud/` — on-device metrics overlay
- `telemetry/` — client metrics reporter
- `startup/` — preflight checks, transport probe
- `ui/` — activity coordinators, settings, status binding

Supports API 17+ (legacy) and 19+ (modern) pipelines.

## Desktop (`desktop/`)

Two Tauri 2 apps under `desktop/apps/`:

| App | Purpose |
|-----|---------|
| `desktop-tauri` | Main control UI (SolidJS + Rust backend) |
| `trainer-tauri` | Trainer GUI for profile tuning |

Desktop apps are clients of the host daemon API — no stream/session logic lives here.

## Shared (`shared/`)

`shared/protocol/` — cross-domain protocol definitions and scripts.

## Config (`config/`)

- `wbeam.conf` — bootstrap config template (overridden by `~/.config/wbeam/wbeam.conf`)
- `trainer-profiles/examples/` — example trained profiles

## Scripts (`scripts/`)

| Script | Purpose |
|--------|---------|
| `evdi-setup.sh` | Automated EVDI installation |
| `evdi-diagnose.sh` | EVDI diagnostic checks |
| `virtual-deps-check.sh` | Check virtual display dependencies |
| `virtual-deps-install.sh` | Install virtual display dependencies |
| `android_pipeline_set.sh` | Set Android pipeline mode |
| `android_pipeline_show.sh` | Show current pipeline mode |
| `set-capture-mode.sh` | Set capture backend |

CI scripts under `scripts/ci/`: layout checks, boundary checks, package builds (deb/rpm/aarch64/apk), Sonar integration.

## Domain Boundaries

Each top-level directory owns its domain:
- **host** is runtime authority — daemon owns stream lifecycle and state
- **android** owns decode/render pipeline and on-device UX
- **desktop** sends intent, renders status — never owns session state
- **shared** stays domain-neutral
