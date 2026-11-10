# Release acceptance and publication

This checklist defines the evidence required to publish WBeam. It is not a
record of completed QA. Source checks, VM smoke and physical Android acceptance
are separate gates; a green TCP receiver cannot certify Android rendering.

EVDI and Wayland Portal are the release acceptance backends. X11 code and VM
scenarios are retained as legacy material and do not count toward this matrix.
The original scope is in [epic #101](https://github.com/ppotepa/WBeam/issues/101);
an issue being closed does not establish that its acceptance checks passed.

## 1. Validate the source revision

GitLab defines quality, build and publication jobs in
[`.gitlab-ci.yml`](../.gitlab-ci.yml). GitHub's
[integration workflow](../.github/workflows/release-integration-review.yml)
is currently manual (`workflow_dispatch`). A queued or skipped job is not a
passing test.

From the repository root, the local runner can execute the available gates:

```bash
bash scripts/ci/run-local-verification.sh --list
bash scripts/ci/run-local-verification.sh --quick
bash scripts/ci/run-local-verification.sh --full --report-dir /path/to/private/qa
```

`--quick` runs shell checks, Python/E2E unit tests and desktop npm install/test/
build. `--full` adds host and Tauri Rust tests/checks plus Android debug tests/
build; it requires their native dependencies and Android SDK. It does not
install missing system dependencies or perform physical-device acceptance.

The default report directory is
`${XDG_STATE_HOME:-$HOME/.local/state}/wbeam/verification/<UTC timestamp>/`,
with `summary.tsv`, commit metadata and per-step logs. Exit codes are `0` when
all selected steps pass, `1` for failed steps, and `2` when missing prerequisites
block steps without another failure. Missing commands/directories are detected
as BLOCKED; missing libraries or SDK components can instead cause a build FAIL.
Review `FAIL`/`BLOCKED` and redact logs
before sharing them.

The native EVDI streamer must compile and link, independently of API/core tests:

```bash
cargo check --locked --manifest-path host/rust/Cargo.toml \
  -p wbeamd-streamer --no-default-features --features evdi
cargo test --locked --manifest-path host/rust/Cargo.toml \
  -p wbeamd-streamer --no-default-features --features evdi
```

GitLab's `quality_rust_streamer` gates release artifacts. Use the target
distribution's GStreamer and EVDI development libraries; Bookworm and Noble
have different EVDI ABIs. Compilation does not establish kernel/module or
physical streaming compatibility.

## 2. Test the built packages on physical hardware

| Linux host/session | EVDI | Wayland Portal |
| --- | --- | --- |
| Fedora 43 / GNOME Wayland | Physical Android + EVDI | Physical Android + Portal |
| Ubuntu 24.04 / GNOME Wayland | Physical Android + EVDI | Physical Android + Portal |

Use the built DEB/RPM and signed APK, with a phone and tablet represented across
the four runs. Record the kernel (`uname -r`), Android API, device form factor,
hash of the ADB serial, tested commit, actual host/APK revision and SHA-256 of
the installed package artifacts. Store APK identity/signing details in the
evidence as well as Linux package details.

Every run must complete:

| Check | Required evidence |
| --- | --- |
| Clean package installation | Package identity, dependencies and successful startup as an ordinary user |
| Signed APK installation and update | Approved certificate, same-key upgrade and preserved settings |
| Rendered frames | At least 60 seconds of visible frames on the physical Android display |
| Streaming soak | At least 1,800 seconds of real streaming with telemetry |
| Start/stop | At least 20 cycles |
| USB recovery | Unplug/replug and recover the session |
| Daemon recovery | Restart and recover the session |
| Backend failure handling | Missing EVDI diagnostics, or denied ScreenCast consent for Portal |

A failure or skip blocks acceptance. Keep nonempty logs, screenshots or reports
for each check. The USB soak helper can collect telemetry after a real session
is started; it does not replace visual confirmation:

```bash
WBEAM_SOAK_LOG_DIR=/path/to/private/qa/soak \
  bash host/scripts/soak_usb_30m.sh 1800
```

### Installed service and writable state

The installer and desktop manage `wbeam-daemon.service`. The separate
`host/rust/scripts/install_systemd_user.sh` helper installs
`wbeamd-rust.service`. Inspect the unit actually used by the tested package
workflow; do not start both on the same control port.

Start it with **no Android device attached**, confirm the control API is
listening on loopback, then attach USB, authorize ADB and stream. For the
`wbeamd-rust` template, `ExecStartPre=-...` makes failed ADB preparation
nonfatal; the daemon's `ExecStart` must still succeed.

Package files under `/usr/share/wbeam` are read-only. With `VERSION.txt`
present at the runtime root, default daemon logs and effective-runtime
snapshots belong under an absolute `$XDG_STATE_HOME/wbeam/logs` or
`$HOME/.local/state/wbeam/logs`. The daemon's `--log-dir` overrides its log
destination. Source runs default to `host/rust/logs` for daemon logs and
`logs/effective-runtime` for snapshots. Confirm a nonempty snapshot after a
real session and no attempted writes under `/usr/share/wbeam`.

Source regression guards are available, alongside the real install tests:

```bash
bash scripts/ci/test-daemon-log-location.sh
bash scripts/ci/test-systemd-offline-start.sh
```

Also complete the [security review](SECURITY_RELEASE.md), including the separate
video listener's exposure.

## 3. Collect and review the acceptance report

Copy [the acceptance template](../e2e/release-acceptance.example.json) into a
private QA directory. Create all four matrix entries and replace `pending`
only after performing the checks. Evidence paths must be relative to the
report and remain inside its directory.

```bash
python3 e2e/scripts/validate_hardware_acceptance.py \
  /path/to/private/qa/hardware-acceptance.json --release 0.1.3-rc.1
```

Use the exact artifact revision for `--release` (the value of `WBEAM_VERSION`,
without a tag's `v` prefix). The validator checks metadata formats, required
pairs, recorded durations/cycles, `pass` statuses and nonempty evidence files.
It does not inspect their contents, prove physical execution, or compare the
report's commit and hashes with the built artifacts. A named reviewer must
perform those comparisons, verify the phone/tablet coverage and approve the
actual evidence before publication.

Do not publish raw serials, sensitive logcat, keystores or signing secrets.
Keep the report **and** its evidence accessible to the publication job.

## 4. Publish the approved artifact set

Publication is a manual GitLab job and requires the following inputs:

1. Passing required quality/build jobs for the tested commit and release-signed
   APKs; see [signing instructions](ANDROID_RELEASE_SIGNING.md).
2. A root `LICENSE` containing terms confirmed by the maintainer. Packaging
   metadata declaring MIT is insufficient; the publisher checks for the file.
3. Protected `GH_TOKEN`, `GH_OWNER`, `GH_REPO`, and
   `WBEAM_HARDWARE_ACCEPTANCE_FILE` pointing to the reviewed report.
4. Protected `WBEAM_RELEASE_APPROVED=1`, set only after maintainer review.

The artifact set contains exactly one version-named APK, both ABI-specific
DEBs (`+bookworm` and `+noble`), at least one RPM and `VERSION.txt`. Run the check
in the build workspace with that build's `WBEAM_VERSION` and `CI_COMMIT_SHA`:

```bash
bash scripts/ci/check-release-artifacts.sh
```

This script checks file presence/nonemptiness, naming and manifest metadata,
then generates and verifies `SHA256SUMS.txt`. It does not inspect embedded
package versions, reverify APK signatures or test installation. Review
`dpkg-deb -f`, dependencies/linking, APK metadata and the clean-install evidence
separately. Bookworm's `libevdi0` and Noble's `libevdi1` packages are not
interchangeable.

Trigger `publish_github_release` for that tested commit. Stable tags such as
`v0.1.3` publish as stable; `v0.1.3-rc.1`, `-beta.1` or `-alpha.1` publish as
prereleases. A `release` branch build updates the mutable `release-latest`
prerelease. All of these routes require the approval/report gates above.
Existing versioned releases and their assets are not overwritten; use a new
version for a new artifact set. Windows and aarch64 build outputs are not part
of this publisher's supported upload set.

After publication, download the packages, check `SHA256SUMS.txt`, and repeat
installation and USB recovery on a clean machine. The optional
`ff_release_to_master` job has its own `WBEAM_MASTER_FF_APPROVED=1` gate and
checks that the release branch still points to the tested stable-tag commit.
Publication alone does not authorize integrating an untested newer commit.
