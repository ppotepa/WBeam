# WBeam E2E Final Closure

## Current artifacts

- Fedora L1: `e2e/images/base/fedora-43/gnome-wayland-installed.qcow2`
- Fedora L1P: `e2e/images/base/fedora-43/gnome-wayland-portal-consented.qcow2`
- Fedora portal scenario: `fedora43-gnome-wayland-portal-h264`
- Fedora EVDI scenario: `fedora43-gnome-wayland-evdi-h264`
- Fedora X11 scenario: `fedora43-gnome-xorg-x11-h264`
- Android scenario: `fedora43-gnome-wayland-portal-android-h264`

## Green vs blocked vs fail

- `pass`: scenario completed and stream bytes were received.
- `blocked`: missing external prerequisite with machine-readable `reason_code` and `next_action`.
- `fail`: implementation or regression issue.

## Portal consent

Create portal-consented backing:

```bash
./e2e/run prepare-portal-consent \
  --distro fedora-43 \
  --session gnome-wayland \
  --backend wayland_portal \
  --live \
  --promote
```

Known manual step:
- approve the GNOME ScreenCast portal prompt in the VM window.

## Closure commands

Fedora MVP:

```bash
./e2e/run close --profile fedora-mvp --live --json
```

Hardware:

```bash
./e2e/run close --profile hardware --live --json
```

Full:

```bash
./e2e/run close --profile full --live --json
```

## Reason codes

- `missing_portal_consented_image`
- `portal_consent_required`
- `stream_port_not_open`
- `stream_no_bytes`
- `evdi_module_missing`
- `x11_session_missing`
- `android_device_missing`
- `android_device_unauthorized`
- `iso_missing`
- `distro_image_missing`
