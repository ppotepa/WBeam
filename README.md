# WBeam

WBeam turns an Android phone/tablet into a USB-connected second screen for Linux.

![WBeam](docs/assets/wbeam.png)

## Current Status

`Wayland`:
- recommended path
- works end-to-end on current hosts
- best stability right now

`X11`:
- support is under active redevelopment in the main codebase
- not feature-parity with Wayland yet

## What Works

- host daemon + control API (`/v1/status`, `/v1/start`, `/v1/stop`)
- Android app deploy and connect flow over USB/ADB
- version/build compatibility checks
- runtime diagnostics and logs
- desktop control tooling

## What Is Experimental

- mixed GPU topologies (NVIDIA + EVDI on X11)
- fallback monitor-object path on X11

## Recommended Path

If you just want it to work now:
1. use `Wayland`
2. run main tooling (`./wbeam`, `./wbgui`)
3. treat Wayland as primary runtime path

## Quick Start

```bash
./wbeam --help
./wbeam service install
./wbeam service start
./wbeam android deploy-all
./wbgui
```

## Trainer (autotune) quick usage

Use the interactive tuner to benchmark and generate a reusable profile:

```bash
./wbeam host tuner
```

In **Run Config**:

- set `Objective` and `Workload`
- choose **Use prerendered scenes for training**
  - enabled: trains on deterministic synthetic scenes (`display_mode=benchmark_game`)
  - disabled: trains on virtual desktop (`display_mode=virtual_monitor`)
  - **Note:** Wayland virtual monitor capture is capped at ~60 fps by the compositor (KDE/GNOME ScreenCast limitation). For training at higher frame rates use prerendered scenes.
- set **Child train time** (seconds)
  - this is the full time budget per child
  - default is 5s (e.g. set 10 => each child runs for 10s)
- provide a profile name and start evolution

When a run completes, the tuner shows a **Final profile settings** summary box with score and winner details (bitrate/fps/intra, source mode, child train time, reason). Saved profiles are written to:

`~/.config/wbeam/trained_profiles.json`

Committed example profiles are available in:

`config/trainer-profiles/examples/`

## Repo Layout

- `android/` - Android domain (client app + decode runtime)
- `host/` - host domain boundary
- `desktop/` - desktop domain boundary
- `shared/` - shared contracts/protocol boundary

Structure source of truth:
- `docs/repo-structure.md`

## Main Entrypoints

- `./wbeam` - canonical CLI
- `./wbgui` - terminal UI wrapper
- `./devtool` - developer helper
- `./start-remote` - remote session bootstrap
