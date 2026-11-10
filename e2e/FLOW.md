# VM disk lifecycle and reports

For setup and backend expectations, start with [the E2E guide](README.md).
Run the commands below from the repository root.

## Base images

`prepare-base` installs a clean OS from the selected ISO, configures test SSH
access and the requested session, checks the first boot, shuts down, and
promotes the installed disk to a reusable base image. The base contains OS/test
prerequisites, not WBeam build output.

```bash
./e2e/run base-plan --distro fedora-43 --session gnome-wayland
./e2e/run prepare-base --distro fedora-43 --session gnome-wayland
```

The matrix defines `headless`, `gnome-wayland` and legacy `gnome-xorg` variants.
Preparation validates `/var/lib/wbeam-e2e/base-ready.json` and the session
sanity checks before promotion. Base files live at:

```text
e2e/images/base/<distro>/<session>.qcow2
e2e/images/base/<distro>/<session>.json
```

The adjacent JSON records base-image metadata. Build intermediates/logs remain
under `e2e/work/base-build/<distro>/<session>/`. Treat promoted bases as immutable
inputs; rebuild deliberately if the OS/session setup must change.

## Disposable runs

A test creates a working disk, boots the guest, copies the current checkout,
runs `guest-install-wbeam.sh`, runs `guest-stream-smoke.sh`, and collects reports.
The copy excludes `.git` and build outputs, so record the tested source commit
in the host-side QA evidence rather than relying on a guest git revision.

```bash
# Create a disk without running the scenario:
./e2e/run workdisk-create --scenario fedora43-gnome-wayland-evdi-h264

# Build/run the headless smoke on an overlay:
./e2e/run run --scenario fedora43-headless-benchmark-h264

# Use a full disk copy, independent of the base path after creation:
./e2e/run run --scenario fedora43-headless-benchmark-h264 --copy-mode full
```

The default overlay stores only changed blocks and requires its base to stay at
the same path. `--copy-mode full` copies the whole base and uses more storage.
Both refuse to proceed without the required base image. Working disks live at
`e2e/work/runs/<run-id>/<scenario-id>/disk.qcow2`.

By default, successful runs remove their working directory and failures retain
it. Override with `--retain-workdisk never|on-fail|always`; inspect before using
`./e2e/run clean --run-id RUN_ID` with the actual run ID. Reports survive
work-directory cleanup.

## Report layout

The runner writes reports under `e2e/reports/<run-id>/` (or
`WBEAM_E2E_REPORT_DIR`). The top-level files are `matrix.json`, `host.json`,
`summary.json` and `junit.xml`.

| Location within a run | Contents |
| --- | --- |
| `<scenario>/guest-install.log`, `guest-stream.log` | SSH command output for installation and smoke |
| `<scenario>/workdisk.json` | Working disk metadata |
| `<scenario>/journal-system.log`, `journal-user.log`, `dmesg.log`, `dev-dri.log` | Guest diagnostics collected after a successful smoke |
| `<scenario>/evdi-diagnose.log` | EVDI diagnostics for EVDI scenarios |
| `<scenario>/guest/` | Files copied from the guest smoke report directory |

The guest directory contains `summary.json`, `health-before.json`,
`host-probe.json`, `virtual-probe.json`, `apply.json`, `start.json`,
`status-after.json`, `metrics.jsonl`, `metrics-after.json` and `client.json`.
`daemon.stdout.log` and `daemon.stderr.log` are created when the smoke helper
starts the daemon itself.

A failure before the collection phase may leave guest reports only on the
retained working disk. The top-level summary records the failed phase/reason;
missing artifacts are not evidence of a passing operation. A dry run writes
`dry-run.json` and reports `phase: dry-run`, without VM/stream evidence.

Inspect the summary with `./e2e/run report --run-id RUN_ID`. Review logs and
TCP byte counts against the [smoke test's limits](README.md#backend-expectations),
then use the separate [physical acceptance process](../docs/release-checklist.md)
for a release decision.
