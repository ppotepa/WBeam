# E2E Flow

The E2E VM workflow has two separate lifecycles.

## 1. Base Image Lifecycle

Base images are clean installed operating systems created from ISO. They are
inputs to tests, not test artifacts.

```text
installer ISO
  -> empty install disk
  -> unattended OS install
  -> first boot sanity checks
  -> shutdown
  -> immutable base qcow2
```

Default path:

```text
e2e/images/base/<distro>/<session>.qcow2
```

Examples:

```text
e2e/images/base/fedora-43/headless.qcow2
e2e/images/base/fedora-43/gnome-wayland.qcow2
e2e/images/base/fedora-43/gnome-xorg.qcow2
e2e/images/base/ubuntu-24.04/gnome-wayland.qcow2
e2e/images/base/debian-12/gnome-xorg.qcow2
```

The base image must contain only the OS, SSH access, base desktop/session setup,
and generic VM agent dependencies. It must not contain build output from the
tested WBeam checkout.

Required base variants come from `e2e/matrix.json` scenarios:

- `headless`
- `gnome-wayland`
- `gnome-xorg`

## 2. Test Run Lifecycle

Every scenario runs from a disposable working disk.

```text
base qcow2
  -> qcow2 overlay or full copy
  -> boot VM
  -> copy current checkout
  -> guest-install-wbeam.sh
  -> guest-stream-smoke.sh
  -> collect reports
  -> delete or retain working disk
```

Default working disk path:

```text
e2e/work/runs/<run-id>/<scenario-id>/disk.qcow2
```

Overlay creation:

```bash
qemu-img create -f qcow2 \
  -F qcow2 \
  -b e2e/images/base/fedora-43/gnome-wayland.qcow2 \
  e2e/work/runs/<run-id>/fedora43-gnome-wayland-evdi-h264/disk.qcow2
```

This is the default mode because it is fast and preserves the base image.

Full copy creation:

```bash
qemu-img convert -O qcow2 \
  e2e/images/base/fedora-43/gnome-wayland.qcow2 \
  e2e/work/runs/<run-id>/fedora43-gnome-wayland-evdi-h264/disk.qcow2
```

Use full copy when the base image is on slow/shared storage or when the runner
must be independent of the base path after start.

## Commands

```bash
./e2e/run images
./e2e/run base-plan --distro fedora-43 --session gnome-wayland
./e2e/run prepare-base --distro fedora-43 --session gnome-wayland
./e2e/run workdisk-create --scenario fedora43-gnome-wayland-evdi-h264
./e2e/run workdisk-create --tag smoke --run-id local-smoke-1
./e2e/run workdisk-create --scenario fedora43-headless-benchmark-h264 --copy-mode full
./e2e/run run --scenario fedora43-headless-benchmark-h264
```

`base-plan` prints the deterministic paths and install contract before the
actual VM boot.

`prepare-base` builds the unattended install disk, waits for SSH on the first
installed boot, validates `/var/lib/wbeam-e2e/base-ready.json`, and promotes the
result to `e2e/images/base/<distro>/<session>.qcow2`.

`workdisk-create` refuses to run when the matching base image does not exist.

`run` creates a disposable work disk, boots it, copies the local checkout,
executes `guest-install-wbeam.sh`, executes `guest-stream-smoke.sh`, collects
guest and system logs, and writes `summary.json` plus `junit.xml`.

## Report Contract

Every scenario report should include:

```text
summary.json
install.log
stream.log
health-before.json
host-probe.json
virtual-probe.json
apply.json
start.json
status-after.json
metrics.jsonl
metrics-after.json
client.json
daemon.stdout.log
daemon.stderr.log
```

The top-level run report should include:

```text
matrix.json
summary.json
junit.xml
host.json
```
