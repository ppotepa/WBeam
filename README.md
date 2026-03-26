# WBeam

USB display streaming from Linux to Android over ADB.

Captures a virtual display on the host, encodes H.264/H.265, and pushes
frames to an Android device connected via USB. No Wi-Fi, no network config.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    Linux Host                               │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌─────────────────┐           ┌──────────────────┐        │
│  │  Display Capture│           │  WBeam Daemon    │        │
│  │  (EVDI/Wayland) │──────────▶│  • Stream Mgmt   │        │
│  └─────────────────┘           │  • Encoding      │        │
│                                │  • Network Ctrl  │        │
│  ┌─────────────────┐           └──────────────────┘        │
│  │  Desktop UI     │                    │                   │
│  │  (Tauri App)    │◀───────────────────┼───────┐          │
│  └─────────────────┘                    │       │          │
│                                         │       │          │
│                                    ADB over USB  │          │
│                                         │       │          │
│                                         ▼       ▼          │
└─────────────────────────────────────────────────────────────┘
                                          │
                        ┌─────────────────┴──────────────────┐
                        │                                    │
                   ┌────▼────────────┐            ┌──────────▼────┐
                   │   Android Tablet│            │  Android Phone│
                   ├─────────────────┤            ├───────────────┤
                   │ • H.264/H.265   │            │ • H.264/H.265 │
                   │ • Display Sink  │            │ • Display Sink │
                   │ • Telemetry     │            │ • Telemetry   │
                   └─────────────────┘            └───────────────┘
```

---

## Getting Started

Prerequisites: Linux host, Android device (API 17+), USB cable, ADB.

```bash
git clone <repo> && cd WBeam

# Build host daemon, build+install APK, launch desktop UI
./redeploy-local

# Verify everything matches
./wbeam version doctor
```

For EVDI capture (recommended), load the kernel module:

```bash
sudo modprobe evdi initial_device_count=1
```

If EVDI gives trouble, run the setup script:

```bash
sudo bash scripts/evdi-setup.sh
```

See `EVDI_SETUP_INDEX.md` and `docs/EVDI_SETUP_GUIDE.md` for details.

---

## Capture Backends

**EVDI** (recommended) -- Kernel-level virtual display. 1920x1080 fixed EDID,
low latency, bypasses compositor. Setup: `sudo bash scripts/evdi-setup.sh`.

**Wayland portal** -- Fallback for Wayland sessions. Compositor-dependent
performance (~30-60 FPS). Automatic if EVDI is unavailable.

**X11** -- Fallback for X11 sessions. Variable performance.

---

## Commands

### `./wbeam` subcommands

```
host      build | run | debug | status | down/stop | probe | tuner
android   build | install | launch | deploy | deploy-all
          build-release | install-release | deploy-release
version   new | current | doctor
watch     tui | devices | connections | streaming | service
          logs | status | health | doctor
debug     up/start | watch/logs
logs      live | host | adb (start|stop|status|tail)
deps      virtual (check | install)
ip        up | down | status
```

The daemon runs via `./wbeam host run` (foreground) or `./wbeam host debug`
(debug mode). For persistent operation, manage the systemd user service
(`~/.config/systemd/user/wbeam-daemon.service`) directly with `systemctl --user`.

`./wbeam host tuner` launches the interactive Rust TUI autotuner for
benchmarking encoding profiles against your hardware.

### Other entrypoints

| Script | What it does |
|--------|-------------|
| `./wbeam` | Main CLI |
| `./wbgui` | Interactive TUI menu |
| `./devtool` | Dev convenience (gui, deps install) |
| `./desktop.sh` | Desktop app launcher |
| `./redeploy-local` | Full rebuild + deploy + launch |
| `./start-remote` | Remote session bootstrap |
| `./runas-remote` | Run command as another desktop user |

The trainer GUI is the desktop trainer-tauri app (`desktop/apps/trainer-tauri`).

---

## Repository Layout

```
WBeam/
  android/       Android client (APK)
  host/          Linux daemon, streamer, training
  desktop/       Tauri desktop apps (control + trainer)
  shared/        Shared protocol definitions
  config/        Configuration templates and profiles
  scripts/       Setup and utility scripts
  docs/          Documentation
  logs/          Runtime logs (gitignored)
```

Key docs: `docs/repo-structure.md`, `docs/agents.workflow.md`,
`docs/EVDI_SETUP_GUIDE.md`.

---

## Troubleshooting

**No stream** -- Check `adb devices` shows your device. Check daemon is
running (`./wbeam host status`). Check version parity (`./wbeam version doctor`).
Inspect `logs/` and `desktop-connect.log`.

**EVDI not loading** -- Run `bash scripts/evdi-diagnose.sh`. Try the automated
setup: `sudo bash scripts/evdi-setup.sh`.

**Low FPS** -- Switch to EVDI backend. Run `./wbeam host tuner` to find
optimal encoding settings for your hardware.

**Desktop UI issues on Wayland** -- Launch via `./desktop.sh`. If that fails,
try `XDG_SESSION_TYPE=x11 ./desktop.sh`.

---

## Supported Platforms

- **Linux:** Arch, Debian, Ubuntu, Fedora, RHEL (kernel 5.10+ for EVDI)
- **Android:** API 17+ (Android 4.2+), USB 2.0+

---

## Development

```bash
./redeploy-local   # build everything, deploy, launch
./wbgui            # interactive menu for common tasks
./devtool          # dev shortcuts
```

See `docs/agents.workflow.md` for branch/PR conventions and commit format.

---

## License

See LICENSE file in repository root.
