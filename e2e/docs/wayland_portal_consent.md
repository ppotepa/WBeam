# WBeam E2E Wayland Portal Consent

## Problem
GNOME Wayland ScreenCast portal requires one-time user approval. Without it, the portal-backed stream stays blocked and the scenario reports `portal_consent_required`.

## Asset Layers
- L0: clean OS image
- L1: installed WBeam image
- L1P: portal-consented image
- L2: disposable run overlay

## Prepare Consent

```bash
./e2e/run prepare-portal-consent \
  --distro fedora-43 \
  --session gnome-wayland \
  --backend wayland_portal \
  --live \
  --promote
```

Approve the GNOME ScreenCast / Virtual Monitor prompt in the VM window.

## Run After Consent

```bash
./e2e/run run \
  --scenario fedora43-gnome-wayland-portal-h264 \
  --use-installed \
  --run-id FEDORA-WAYLAND-CONSENTED-LIVE-001 \
  --live
```

## Diagnose

```bash
./e2e/run portal-diagnose --run-id <RUN_ID>
```

## Expected Files
- `e2e/images/base/fedora-43/gnome-wayland-portal-consented.qcow2`
- `e2e/images/base/fedora-43/gnome-wayland-portal-consented.json`
