# Development workflow

Run commands below from the repository root. See the [repository guide](repo-structure.md)
for component ownership and the [installer](INSTALLER_WIZARD.md) for prerequisites.

## Branches and review

`master` is the default development branch; `release` is the branch used by the
GitLab packaging rules. Older version-line branches, including `v0.1.2/base`,
remain in Git history; do not assume they are the current integration target.
Confirm the intended base in the task or epic before stacking work.

For issue-driven work, retain the repository convention:

- One issue = one branch = one PR/MR.
- Branch: `v<version>/e<epic>/i<issue>-<slug>`.
- Commit: `e<epic>-i<issue>: <short action>`.
- PR/MR title: `[e<epic>-i<issue>] <short title>`.
- Execute dependent child issues sequentially. Close a child only after its
  merged change and required evidence; close an epic when all children are done.

A merged PR or administratively closed issue does not establish hardware
acceptance. Record executed checks, blockers and evidence in the PR/MR.

## Local development

```bash
./redeploy-local                # rebuild, deploy to connected devices, launch UI
./desktop.sh                    # launch the desktop development app
./wbeam android deploy-all      # build/deploy Android to connected devices
./wbeam watch streaming --once  # inspect runtime state
./wbeam version doctor          # inspect host/APK version parity
```

`redeploy-local` stops daemon processes and removes the existing daemon user
unit so the desktop can recreate/manage it. On Fedora it can install missing
dependencies and EVDI components; inspect `./redeploy-local --help` for skip
options. To skip its EVDI bootstrap, use `WBEAM_REDEPLOY_WITH_EVDI=0`.

For daemon-only debugging, stop the managed service before running
`./wbeam host debug`; otherwise two launch paths can contend for the same ports.
Restore normal operation with `systemctl --user start wbeam-daemon.service`.
`./wbeam host run` runs the release binary in the foreground; `host down` stops
processes and is not a replacement for stopping a managed service.

Use `./wbeam help` for the full command surface and `./wbgui` for the TUI menu.
`./devtool` provides developer shortcuts; `./start-remote` and `./runas-remote`
support remote graphical-session workflows. The profile tuner is
`./wbeam host tuner`; there is no separate trainer desktop app in this checkout.

The CLI still lists `host probe`, but its `host/scripts/probe_host.py` helper is
absent. With the daemon running, use
`curl -fsS http://127.0.0.1:5001/v1/host-probe` for host capabilities.

## Validation

Choose checks for the changed component. The local runner records PASS, FAIL
and BLOCKED results without publishing or requiring signing keys:

```bash
bash scripts/ci/run-local-verification.sh --quick
bash scripts/ci/run-local-verification.sh --full --list
bash scripts/ci/run-local-verification.sh --full
```

`--quick` runs shell checks, Python E2E-runner tests and desktop frontend tests/build.
`--full` adds host Rust, EVDI streamer, native Tauri and Android Gradle checks.
Reports go to `${XDG_STATE_HOME:-$HOME/.local/state}/wbeam/verification/<timestamp>/`;
use `--report-dir PATH` to override. Missing commands or project directories are
reported as BLOCKED. Other missing dependencies can cause FAIL; inspect the
build logs. Neither outcome is a pass.

For individual checks, use paths that do not depend on a previous `cd`:

```bash
cargo test --locked --manifest-path host/rust/Cargo.toml -p wbeamd-api -p wbeamd-core -p wbeamd-server
cargo test --locked --manifest-path host/rust/Cargo.toml -p wbeamd-streamer --no-default-features --features evdi
npm --prefix desktop/apps/desktop-tauri ci
npm --prefix desktop/apps/desktop-tauri test
npm --prefix desktop/apps/desktop-tauri run build
cargo test --locked --manifest-path desktop/apps/desktop-tauri/src-tauri/Cargo.toml
(cd android && bash ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug)
```

Native builds need the libraries documented in the setup guides. E2E runner
tests validate orchestration, not a physical display path. Follow the
[E2E guide](../e2e/README.md) and [release checklist](release-checklist.md) for
Android/EVDI/Portal acceptance.

## CI

The source of truth for jobs and triggers is
[`.gitlab-ci.yml`](../.gitlab-ci.yml) and the
[GitHub workflow](../.github/workflows/release-integration-review.yml):

- GitLab stages are `quality` → `build` → `publish`. Quality covers layout,
  boundaries, E2E matrix, shell regressions, frontend, Rust, Tauri and Android.
  Sonar on `master` requires a configured server/token.
- Package jobs run for tags and `release`: separate Bookworm/Noble DEBs, Fedora
  RPM, an aarch64 tarball and a signed Android APK. Artifact production and
  publication have additional dependencies and gates.
- GitHub Actions is currently **manual (`workflow_dispatch`) only**. A push or
  PR does not automatically run that workflow. A configured job is not evidence
  that a runner executed it.

## Versioning

| Build context | Version source |
| --- | --- |
| Semantic release tag | Tag with leading `v`/`V` removed; prerelease suffix preserved |
| GitLab `release` branch | `0.1.3.<pipeline IID>.<short SHA>` |
| Other GitLab branches | `0.0.0.<default branch>.<pipeline IID>.<short SHA>` |
| Standalone packaging helper | `0.1.3.<first five SHA characters>`; `dev00` if no CI SHA is supplied |
| Local `./wbeam` source build | `0.1.3.<five-character Git revision>` by default |

Explicit build variables can override defaults; see
[`scripts/ci/common.sh`](../scripts/ci/common.sh) and [`wbeam`](../wbeam).
Host and Android compatibility versions must match. Desktop version is not
that handshake gate. Signing and Android `versionCode` requirements belong in
the [signing guide](ANDROID_RELEASE_SIGNING.md).
