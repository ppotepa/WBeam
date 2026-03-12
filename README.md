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
