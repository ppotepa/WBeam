# WBeam E2E Wayland Portal Consent

## Problem
GNOME ScreenCast portal for `fedora43-gnome-wayland-portal-h264` can stop in a state where transport, SSH, L1, L2, daemon and ports are healthy, but the first portal consent has not been approved yet.

That is not a generic stream failure. It is an operator-blocked state.

## Asset model
- `L0` = clean OS image
- `L1` = installed WBeam image
- `L1C` = portal-consented image
- `L2` = disposable run overlay

Do not mutate `e2e/images/base/fedora-43/gnome-wayland-installed.qcow2` silently.
Manual approval is captured into a separate asset:

`e2e/images/base/fedora-43/gnome-wayland-portal-consented.qcow2`

## Prepare portal consent
Run:

```bash
./e2e/run prepare-portal-consent \
  --distro fedora-43 \
  --session gnome-wayland \
  --backend wayland_portal \
  --live \
  --promote
```

This opens the VM with a visible display, runs the stream smoke helper, and preserves the approval state in a separate portal-consented image when the run succeeds.

## Re-run the scenario
Once the portal-consented image exists:

```bash
./e2e/run run \
  --scenario fedora43-gnome-wayland-portal-h264 \
  --use-installed \
  --run-id FEDORA-WAYLAND-LIVE-CONSENTED-001 \
  --live
```

For diagnostics without the safety gate:

```bash
./e2e/run run \
  --scenario fedora43-gnome-wayland-portal-h264 \
  --use-installed \
  --allow-unconsented-portal \
  --run-id FEDORA-WAYLAND-LIVE-DIAG-001 \
  --live
```

## Inspect logs
Useful commands:

```bash
./e2e/run portal-diagnose --scenario fedora43-gnome-wayland-portal-h264
./e2e/run diagnose-run --run-id FEDORA-WAYLAND-LIVE-001 --scenario fedora43-gnome-wayland-portal-h264
./e2e/run report --run-id FEDORA-WAYLAND-LIVE-001
```

## Invariant
- `gnome-wayland-installed.qcow2` stays immutable as the installed L1 base.
- portal approval is stored in `gnome-wayland-portal-consented.qcow2`.
- every scenario run uses an L2 overlay on top of the selected backing image.
