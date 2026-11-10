# VM and hardware testing

This directory contains local QEMU/KVM test tooling. CI runs the Python
**runner/validator unit tests**, not real VM sessions or physical Android
acceptance. A local TCP client receives bytes but does not decode or render
frames; use the [release checklist](../docs/release-checklist.md) for hardware
signoff.

## Start with a headless smoke scenario

Run commands from the repository root. The host needs Python 3, QEMU/KVM,
`qemu-img`, SSH, `ssh-keygen`, rsync and a seed-ISO tool (`genisoimage`, `xorriso`
or `cloud-localds`). Download the distro installer ISOs separately; do not
commit images, generated disks, SSH keys or private reports.

```bash
./e2e/run validate
./e2e/run init-env
./e2e/run iso-sources
# Edit e2e/env.local to point to the downloaded ISOs.
eval "$(./e2e/run env-shell)"
./e2e/scripts/preflight.sh --strict

./e2e/run base-plan --distro fedora-43 --session headless
./e2e/run prepare-base --distro fedora-43 --session headless
./e2e/run run --scenario fedora43-headless-benchmark-h264
./e2e/run report --run-id RUN_ID
```

Replace `RUN_ID` with the ID printed by the run. `preflight.sh --strict` checks
the tools and ISO inputs for the entire matrix;
missing inputs cause failure. Without `--strict` it reports warnings and may
exit successfully despite missing prerequisites. Base preparation installs an
OS, and guest installation installs packages/builds WayBeam: these are active VM
operations, not read-only checks.

## Inputs and paths

[The matrix](matrix.json) defines Fedora 43, Ubuntu 24.04 and Debian 12
scenarios. Use the ISO paths in [env.example](env.example):

| Environment variable | Input |
| --- | --- |
| `WBEAM_E2E_ISO_FEDORA_43` | Fedora 43 installer ISO |
| `WBEAM_E2E_ISO_UBUNTU_24_04` | Ubuntu 24.04 installer ISO |
| `WBEAM_E2E_ISO_DEBIAN_12` | Debian 12 installer ISO |
| `WBEAM_E2E_BASE_DIR` | Optional base disk root; default `e2e/images/base` |
| `WBEAM_E2E_WORK_DIR` | Optional disposable/build disk root; default `e2e/work` |
| `WBEAM_E2E_REPORT_DIR` | Optional report root; default `e2e/reports` |

Base images contain a clean OS and test access. Each run creates a disposable
qcow2 overlay (or `--copy-mode full`), copies the current checkout, builds in
the guest, starts the smoke test and collects reports. Never boot a test
from the base disk itself. See [disk lifecycle and report layout](FLOW.md).

## Inspect and select work

```bash
./e2e/run status
./e2e/run next
./e2e/run list --distro fedora-43 --backend evdi
./e2e/run plan --tag smoke
./e2e/run images
./e2e/run prepare-base --all --missing
./e2e/run run --tag smoke --ready
./e2e/run clean --run-id RUN_ID
```

`status`/`next` show local prerequisites and recorded runner progress, not a
release certification percentage. `--ready` selects scenarios with existing
base disks; it does not validate a desktop session or capture permission.
`run --dry-run` exercises orchestration/report creation without a VM; a
report with `phase: dry-run` is not execution evidence.

`run` removes working disks after success and retains them on failure by
default (`--retain-workdisk on-fail`). `clean` removes a run's disposable work
directory, leaving base images and reports intact.

## Backend expectations

| Backend | Purpose and limits |
| --- | --- |
| `benchmark_game` | First smoke tier: synthetic frames without desktop capture consent. Exercises guest build, daemon startup, encoding and TCP receive. |
| `wayland_portal` | Requires a real GNOME Wayland session and ScreenCast consent. The runner does not automate first consent or seed a restore token; handle consent explicitly. |
| `evdi` | Requires a working guest kernel/module/library stack. Collect diagnostics and keep Secure Boot/MOK testing explicit. A VM pass does not certify a physical host's kernel. |
| `x11_gst` | Retained legacy scenarios and source. Current daemon/streamer entrypoints reject X11 capture, so these rows are not expected to pass on the release path. |

The guest smoke runs for a configured observation interval (60 seconds by
default), requires a TCP connection with some bytes received, and accepts a
final state of `STREAMING`, `STARTING` or `RECONNECTING`. It does not prove
continuous frame delivery, successful decoding, or 60 seconds of visible
Android output. Review `guest/client.json` and the logs, not just `status: pass`.

Only EVDI and Wayland Portal belong to the physical release matrix. Debian
and legacy X11 VM rows remain engineering scenarios; the matrix's existence
is not a claim of certified platform support.

## Unit checks

```bash
python3 -m unittest discover -s e2e/tests -v
```

These tests cover runner behavior, seeds, reports and hardware-report
validation. Keep physical QA evidence separate, with private device data
redacted before sharing.
