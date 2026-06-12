# WBeam Installer Wizard

`install-wbeam` is the user-facing setup flow for a fresh Linux machine. It is
separate from `redeploy-local`, which remains the developer rebuild/deploy flow.

Current scope on this branch:

- Fedora bootstrap is implemented through `scripts/fedora-setup.sh`.
- Wayland/X11 fallback is the recommended default backend.
- EVDI is an advanced backend and can require DKMS/akmods, kernel headers,
  DisplayLink/EVDI packages, `video` group membership, Secure Boot MOK
  enrollment, and a reboot.
- Android phone onboarding is built into the wizard and can be rerun separately.
- Debian/Ubuntu detection exists, but package installation mapping is not
  implemented yet.

## Commands

Preview without changing the machine:

```bash
./install-wbeam --dry-run
./wbeam install --dry-run --backend wayland
./wbeam install --dry-run --backend evdi
```

Install with the recommended compositor backend:

```bash
./install-wbeam --backend wayland
```

Install with EVDI:

```bash
./install-wbeam --backend evdi
```

Rerun only Android phone onboarding:

```bash
./wbeam device setup
```

Non-interactive source checkout install:

```bash
./install-wbeam --yes --backend wayland
```

## Wizard Stages

1. Probe the machine:
   - distro and package manager
   - architecture
   - Wayland/X11 availability
   - portal, PipeWire, and WirePlumber service state
   - GStreamer encoder availability
   - EVDI library/module/user permission/Secure Boot state

2. Choose backend:
   - `wayland`: recommended; uses Wayland portal or X11 fallback according to
     the current session
   - `evdi`: advanced; installs and validates kernel/displaylink pieces

3. Install/build:
   - Fedora packages through `scripts/fedora-setup.sh --yes`
   - EVDI packages through `scripts/fedora-setup.sh --yes --with-evdi`
   - host binaries through `./wbeam host build`
   - systemd user service as `wbeam-daemon.service`

4. Validate:
   - host binary exists
   - streamer binary exists
   - systemd user service state
   - local control API reachability

5. Phone onboarding:
   - asks the user to connect the phone
   - checks `adb devices`
   - handles missing, unauthorized, offline, and multiple devices
   - runs `./wbeam android deploy` for the selected serial
   - runs `./wbeam version doctor`

## State

The wizard writes state to:

```text
~/.local/state/wbeam/install-state.json
```

That file records the selected backend, distro, service state, phone onboarding
state, EVDI status, and whether a reboot is required.

## Release Direction

For source checkouts, the wizard builds locally. For GitHub Releases, the same
wizard interface can later switch the install stage to package or asset
installation:

- `.rpm` on Fedora
- `.deb` on Debian/Ubuntu
- release APK for Android deploy

The UX should stay the same: probe, choose backend, install, validate, onboard
the phone.
