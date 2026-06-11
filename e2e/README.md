# WBeam E2E

This folder contains local VM end-to-end test scaffolding. It is intentionally
not wired into GitLab CI yet.

The model is two-phase:

1. Keep distro ISOs outside git.
2. Build clean base disks from ISO once per distro/session variant.
3. Never boot tests from a base disk directly.
4. For each test, create a qcow2 overlay or full copy from the base disk.
5. Boot the VM from that working disk.
6. Copy this checkout into the guest.
7. Install/build WBeam in the guest.
8. Start a stream for at least 60 seconds.
9. Collect logs and JSON reports.
10. Delete or archive the working disk.

Base disks, runtime disks, and reports are ignored under `e2e/images/base/`,
`e2e/work/`, and `e2e/reports/`.

## Commands

```bash
./e2e/run validate
./e2e/run status
./e2e/run init-env
./e2e/run iso-sources
./e2e/run env-shell
./e2e/run next
./e2e/run list
./e2e/run list --distro fedora-43 --backend evdi
./e2e/run plan --tag smoke
./e2e/run images
./e2e/run base-plan --distro fedora-43 --session gnome-wayland
./e2e/run prepare-base --distro fedora-43 --session headless
./e2e/run prepare-base --all --missing
./e2e/run workdisk-create --scenario fedora43-headless-benchmark-h264
./e2e/run workdisk-create --tag smoke --ready
./e2e/run run --scenario fedora43-headless-benchmark-h264
./e2e/run run --tag smoke --ready
./e2e/run report --run-id <run-id>
./e2e/run clean --run-id <run-id>
./e2e/scripts/preflight.sh
```

The VM runner uses the same matrix and guest scripts:

- `e2e/matrix.json` defines distro/session/backend scenarios.
- `e2e/FLOW.md` defines the base-image and working-disk lifecycle.
- `e2e/scripts/guest-install-wbeam.sh` runs inside the guest and prepares WBeam.
- `e2e/scripts/guest-stream-smoke.sh` runs inside the guest and validates a
  60-second stream through the daemon API plus a local TCP stream client.

## Disk Layout

Default local paths:

```text
e2e/images/base/<distro>/<session>.qcow2
e2e/work/base-build/<distro>/<session>/
e2e/work/runs/<run-id>/<scenario-id>/disk.qcow2
e2e/reports/<run-id>/<scenario-id>/
```

Example:

```bash
# Show the deterministic base image flow:
./e2e/run base-plan --distro fedora-43 --session gnome-wayland

# Build the clean base disk from ISO:
./e2e/run prepare-base --distro fedora-43 --session gnome-wayland

# Or prepare every missing base image in one pass:
./e2e/run prepare-base --all --missing

# Then each test creates an overlay. The base image stays untouched:
./e2e/run workdisk-create --scenario fedora43-gnome-wayland-evdi-h264

# Or run the full scenario end-to-end:
./e2e/run run --scenario fedora43-gnome-wayland-evdi-h264

# Or run every smoke scenario that already has a base image:
./e2e/run run --tag smoke --ready
```

## ISO Inputs

Set these environment variables on the host that runs the VM tests:

```bash
export WBEAM_E2E_ISO_FEDORA_43=/path/to/Fedora-Everything-netinst-x86_64-43-1.6.iso
export WBEAM_E2E_ISO_UBUNTU_24_04=/path/to/ubuntu-24.04-desktop-amd64.iso
export WBEAM_E2E_ISO_DEBIAN_12=/path/to/debian-12-amd64-netinst.iso
```

Or use the checked-in template:

```bash
./e2e/run init-env
./e2e/run iso-sources
$EDITOR e2e/env.local
eval "$(./e2e/run env-shell)"
```

Optional runtime paths:

```bash
export WBEAM_E2E_WORK_DIR=/fast/tmp/wbeam-e2e-work
export WBEAM_E2E_REPORT_DIR=/fast/tmp/wbeam-e2e-reports
export WBEAM_E2E_BASE_DIR=/fast/vm-images/wbeam-base
```

Use `./e2e/run status` at any time to see the current completion percentage and
which evidence is still missing, including the exact ISO environment variables
and exact base image paths that still need to be prepared before a real VM run.
It also prints the next commands to run in order.

Use `./e2e/run next` when you only want the actionable command queue without the
rest of the status report.

## Backend Notes

`benchmark_game` is the first smoke tier. It needs no desktop capture permission
and proves install, build, daemon startup, encode, TCP transport, and report
collection.

`wayland_portal` needs a real GNOME Wayland session. First-run portal consent is
not deterministic yet, so the runner should either seed a restore token or keep
the first consent flow as a separate semi-manual scenario.

`evdi` should run with Secure Boot disabled in the VM unless the scenario is
explicitly testing MOK enrollment. The scenario must collect `dmesg`, `dkms`,
`modinfo evdi`, `/dev/dri`, and `scripts/evdi-diagnose.sh --verbose` output.

`x11_gst` needs a GNOME Xorg session or a deterministic Xvfb/Xorg session with
capture access.
