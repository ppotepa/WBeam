# Source installer

The installer prepares a source checkout, builds the host daemon and streamer,
installs a systemd user service, and onboards an Android device. Run it as your
normal desktop user from the repository root; package operations request `sudo`.
For the build/deploy development loop, use `./redeploy-local` instead.

## Choose a setup path

```bash
./install-waybeam --dry-run                 # inspect the plan
./install-waybeam --backend wayland         # Wayland Portal setup
./install-waybeam --backend evdi            # EVDI virtual display setup
```

`./waybeam install` is an alias for the same wizard.

| Selection | Meaning |
| --- | --- |
| `wayland` | Setup for Wayland Portal capture. Requires a working Wayland session, compositor portal and PipeWire. |
| `evdi` | Setup for EVDI capture. Requires a compatible kernel module, userspace library and DRM device access. |
| `auto` (default) | Prompts interactively; selects `wayland` with `--yes` or outside an interactive terminal. |

The installer writes the selected backend into the service environment:
`evdi` or `wayland_portal`. This fixes the daemon default for that installation;
a per-session desktop/API choice still takes precedence. Explicit control/video
bind variables supplied to the installer are persisted as well; see the
[network policy](SECURITY_RELEASE.md) for the required LAN opt-ins.

X11 code and helpers remain as a legacy path. The current installer rejects
`--backend x11` and the daemon's normal start path accepts only available EVDI
or Wayland Portal backends. X11 is not an automatic fallback.

## What is installed

| Host | Automatic dependency work |
| --- | --- |
| Fedora | Calls [`fedora-setup.sh`](../scripts/fedora-setup.sh) for native build tools, Tauri/GStreamer dependencies and Android SDK. Adds `--with-evdi` when selected. See [Fedora setup](FEDORA_43_SETUP.md). |
| Debian / Ubuntu | Installs native host build libraries with `apt`, including `libevdi0-dev` on Debian 12 and `libevdi-dev` on Ubuntu; adds DKMS, running-kernel headers and `evdi-dkms` for EVDI. |
| Other distributions | Automatic wizard installation stops; prepare dependencies manually and use `--skip-system-deps`. |

The Debian/Ubuntu branch is **not a complete workstation bootstrap**: Rust/Cargo,
ADB, the Android JDK/SDK and desktop Node/Tauri dependencies must already be
available for the stages that use them. Portal runtime services must also be
installed and active. Source builds need network access to fetch dependencies.

The remaining stages are:

1. Build `wbeamd-server` and `wbeamd-streamer` through `./waybeam host build`.
2. Write `~/.config/systemd/user/wbeam-daemon.service` and enable/start it.
3. Report binary, service and local control API status.
4. Select an authorized ADB device, run `./waybeam android deploy`, and report
   host/APK version parity with `./waybeam version doctor`.

EVDI setup may require matching kernel headers, module signing/MOK enrollment,
a new login for group membership, or a reboot. `--yes` accepts the setup plan;
it cannot complete firmware or Android trust prompts.

## Useful options

```bash
./install-waybeam --yes --backend wayland --skip-device
./waybeam device setup                     # retry phone onboarding only
./waybeam device setup --android-serial SERIAL
```

The phone needs USB debugging enabled and its authorization prompt accepted.
Some devices also require permission to install via USB. With multiple devices,
select one interactively or supply `--android-serial`.

Use `--skip-system-deps`, `--skip-build`, `--skip-service` or `--skip-device`
to omit an already completed stage. `--control-port` and `--stream-port`
default to `5001` and `5000`. See `./install-waybeam --help` for all options.

## State and verification

The wizard writes `${XDG_STATE_HOME:-$HOME/.local/state}/wbeam/install-state.json`
with the selected backend, distro, service/device status and reboot indicator.
A missing or unauthorized phone can leave onboarding pending without failing
host setup. The validation stage reports readiness; a successful wizard exit
is not proof that a stream rendered on the device.

After setup:

```bash
systemctl --user status wbeam-daemon.service
./waybeam host probe --json
./waybeam version doctor
```

Open the desktop app with `./desktop.sh`, select the device and capture backend,
and verify a real image on Android. Use the [release checklist](release-checklist.md)
when recording acceptance evidence.

This wizard builds from source. For package readiness and installation checks,
see the [release checklist](release-checklist.md).
