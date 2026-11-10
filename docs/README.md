# WBeam documentation

Start with the [project overview and quick start](../README.md). The guides below
describe the checked-in implementation; release targets and historical test
results are identified separately from verified hardware support.

## Install and operate

| Need | Guide |
| --- | --- |
| Set up a source checkout and onboard Android | [Installer](INSTALLER_WIZARD.md) |
| Bootstrap Fedora 43 dependencies | [Fedora setup](FEDORA_43_SETUP.md) |
| Configure or diagnose a virtual display | [EVDI setup](EVDI_SETUP_GUIDE.md) |
| Check kernel/library compatibility and evidence | [EVDI compatibility](EVDI_COMPATIBILITY_MATRIX.md) |
| Understand Android versions and transport choices | [Android](../android/README.md) |
| Understand exposed ports and trust boundaries | [Security](SECURITY_RELEASE.md) |

## Develop and inspect

| Need | Guide |
| --- | --- |
| Find components, runtime flow and configuration | [Repository and architecture](repo-structure.md) |
| Build, validate and prepare a contribution | [Development workflow](agents.workflow.md) |
| Inspect frame headers, API metrics and latency limits | [Wire protocol and telemetry](telemetry_schema.md) |
| Work on a component | [Host](../host/README.md) · [Desktop](../desktop/README.md) · [Desktop build](../desktop/apps/desktop-tauri/README.md) · [Shared protocol](../shared/README.md) |

## Test and release

| Need | Guide |
| --- | --- |
| Run VM smoke scenarios and validate hardware evidence | [E2E testing](../e2e/README.md) |
| Understand the E2E execution sequence | [E2E flow](../e2e/FLOW.md) |
| Build and verify a signed APK | [Android signing](ANDROID_RELEASE_SIGNING.md) |
| Assess a release candidate | [Release checklist](release-checklist.md) |

X11 remains a **legacy** implementation. Current release paths are EVDI and
Wayland Portal; legacy source, helper options and test scenarios do not imply
release support. The [repository guide](repo-structure.md) explains the boundary.

For CLI options, use `./wbeam help` from the repository root. Keep setup details
in their owning guide and link to them instead of maintaining parallel recipes.
