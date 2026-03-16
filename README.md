# WBeam

WBeam turns an Android phone/tablet into a USB-connected second screen for Linux.

![WBeam](docs/assets/wbeam.png)

## How it works now (real flow)

WBeam has two sides:
- **Host (Linux)**: daemon/service + desktop app
- **Client (Android APK)**: receives stream over USB/ADB path

Main practical note:
- on some setups, `Wayland` can give lower FPS and transport may feel glitchy/jittery
- if this happens, verify your selected backend/profile and test EVDI path

Android debug menu:
- in the APK, hold `VOL+` and `VOL-` together for about `2 seconds`

## Fast local install / run

This is the simplest local flow currently used in development:

1. Clone repo and enter it.
2. Connect Android by USB (ADB must see device in `device` state).
3. Run full local redeploy:
   ```bash
   ./redeploy-local
   ```
   This builds host + desktop, deploys APK to connected device(s), runs version checks, and launches desktop UI.
4. If needed, start desktop manually:
   ```bash
   ./desktop.sh
   ```
5. Ensure host service is installed and running (from desktop UI or CLI):
   ```bash
   ./wbeam service install
   ./wbeam service start
   ```
6. Click **Connect** in desktop app.
7. Ensure EVDI module is available on host:
   ```bash
   sudo modprobe evdi
   ```

## Minimal commands reference

```bash
./wbeam --help
./wbeam host build
./wbeam android deploy-all
./desktop.sh
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
