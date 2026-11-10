# EVDI tools

Run from the repository root. See the [setup guide](../docs/EVDI_SETUP_GUIDE.md)
for requirements and the [compatibility matrix](../docs/EVDI_COMPATIBILITY_MATRIX.md)
for validation status.

| Tool | Purpose |
| --- | --- |
| `./install-wbeam --backend evdi` | Source installation, host build, user service and Android onboarding. |
| `scripts/fedora-setup.sh --yes --with-evdi` | Fedora package/bootstrap flow, including library lookup, module build and MOK checks. |
| `bash scripts/virtual-deps-check.sh --json --backend evdi` | Preliminary runtime dependency probe used by desktop setup. Prints JSON and returns nonzero when dependencies are missing. |
| `bash scripts/evdi-diagnose.sh --verbose --fix` | Detailed read-only diagnostics, including build/linker checks and repair suggestions. |
| `scripts/evdi-setup.sh` | Older package-manager-based EVDI helper retained for legacy maintenance. |

`evdi-diagnose.sh --fix` prints suggestions; it does not apply repairs. Its exit
code is the number of reported errors (warnings alone do not fail it). Runtime
readiness, source build readiness and a real frame-stream test are different
checks; see the setup guide before interpreting a failure.

The legacy setup helper accepts `--dry-run` and `--skip-diagnostic` and requires
root, including in dry-run mode. It retains historical assumptions about
`/dev/dri/card0`, library locations and udev rules, and its package-manager
coverage is not a support matrix. Prefer the wizard/Fedora helper or your
distribution's packages for new installations. Do not use its completion banner
as proof of usable EVDI capture.
