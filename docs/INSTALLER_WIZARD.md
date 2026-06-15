# WBeam Installer Wizard

`install-wbeam` is the user-facing setup flow for a fresh Linux machine. It is
separate from `redeploy-local`, which remains the developer rebuild/deploy
flow.

The wizard now runs through a modular step engine in `scripts/wizard/`. The
legacy flow still exists in `scripts/install-wizard.sh` and can be reached with
`--legacy`, but the current default path is the Python wizard entrypoint.

In the modular model, each stage is a step with a stable `id` and a shared
contract:

- `probe(ctx)` to inspect readiness
- `plan(ctx)` to describe intended work
- `run(ctx)` to perform the action
- `validate(ctx)` to re-check the result

Each step returns a `StepResult`, which carries the observed status, log path,
timing, next action, and machine-readable evidence. `StepPlan` describes intent;
`StepResult` describes what actually happened.

Current scope on this branch:

- Fedora bootstrap is implemented through `scripts/fedora-setup.sh`.
- Debian/Ubuntu APT installation is implemented through the wizard provider
  layer.
- The E2E wizard is no longer a linear script. It now exposes an explicit
  `READINESS` screen before execution and can keep multiple backends selected at
  once.
- Wayland Portal, EVDI, X11 fallback, and headless benchmark can be selected as
  independent backend checkboxes.
- EVDI is an advanced backend and can require DKMS/akmods, kernel headers,
  DisplayLink/EVDI packages, `video` group membership, Secure Boot MOK
  enrollment, and a reboot.
- Android phone onboarding is built into the wizard and can be rerun separately.
- The E2E runner uses the same `install-wbeam` entrypoint as local installs.

## Commands

Preview without changing the machine:

```bash
./install-wbeam --dry-run
./install-wbeam --dry-run --json-events --backend wayland
./install-wbeam --status-json
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

Resume after a blocked or interrupted run:

```bash
./install-wbeam --resume
./install-wbeam --from-step service_setup
./install-wbeam --only adb_probe
```

## Wizard Stages

1. Probe the machine:
   - distro and package manager
   - architecture
   - Wayland/X11 availability
   - portal, PipeWire, and WirePlumber service state
   - GStreamer encoder availability
   - EVDI library/module/user permission/Secure Boot state

2. Choose distro and tier:
   - Fedora, Ubuntu, Debian
   - smoke or full

3. Choose backends:
   - Wayland Portal
   - EVDI
   - X11 GStreamer fallback
   - headless benchmark

4. Readiness:
   - shows ISO, L0 clean OS image, and L1 installed WBeam image
   - shows whether assets will be reused, downloaded, or built
   - shows shared assets across multiple selected scenarios
   - shows the execution plan before any heavy work starts

5. Install/build:
   - Fedora packages through `scripts/fedora-setup.sh --yes`
   - EVDI packages through `scripts/fedora-setup.sh --yes --with-evdi`
   - host binaries through `./wbeam host build`
   - systemd user service as `wbeam-daemon.service`

6. Validate:
   - host binary exists
   - streamer binary exists
   - systemd user service state
   - local control API reachability

7. Phone onboarding:
   - checks `adb devices`
   - handles missing, unauthorized, offline, multiple, and requested-serial
     states
   - runs `./wbeam android deploy` for the selected serial
   - runs `./wbeam version doctor`

## State

The wizard writes state to:

```text
~/.local/state/wbeam/install-state.json
```

That file records the selected backend, distro, step results, and the last known
state for resume. Per-run data also lives in:

```text
~/.local/state/wbeam/runs/<run-id>/steps.jsonl
~/.local/state/wbeam/runs/<run-id>/summary.json
~/.local/state/wbeam/runs/<run-id>/logs/<step-id>.log
```

`./install-wbeam --json-events` emits JSONL events on stdout. `./install-wbeam
--status-json` reads the last known wizard state and prints a structured JSON
snapshot for UI and tooling.

Run history and recovery helpers:

```bash
./e2e/run history
./e2e/run last-failed
./e2e/run rerun-last-failed --live
```

`history` lists the newest run directories, `last-failed` returns the most
recent failing run as JSON, and `rerun-last-failed` replays the failed
scenarios through the current runner flow.

## Release Direction

For source checkouts, the wizard builds locally. For GitHub Releases, the same
wizard interface can later switch the install stage to package or asset
installation:

- `.rpm` on Fedora
- `.deb` on Debian/Ubuntu
- release APK for Android deploy

The UX stays the same: probe, install dependencies, build, configure the
service, onboard the phone if needed, then run stream smoke.
