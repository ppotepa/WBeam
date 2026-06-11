# WBeam Fedora/Installer Handoff

Date: 2026-06-11

Branch: `issue/96-fedora-evdi-bootstrap`

Issue: https://github.com/ppotepa/WBeam/issues/96

Draft PR: https://github.com/ppotepa/WBeam/pull/98

Reporter mentioned on issue: `@sthifen`

Latest pushed commit before this handoff:

```text
a984f6f installer: add source checkout wizard
```

## Goal

The work started because WBeam worked on the original CachyOS development
machine, but a Fedora 43 machine cloned from GitHub could not build or run the
project cleanly. The target is to make the repo usable after clone on a
non-development machine, with Fedora 43 as the first fully supported Linux
bootstrap target.

The product direction changed from only improving `redeploy-local` to adding a
proper installer wizard:

- `redeploy-local` remains the development rebuild/deploy loop.
- `install-wbeam` becomes the fresh-machine setup/onboarding flow.
- The installer lets the user choose Wayland/X11 fallback or EVDI.
- EVDI is treated as advanced/risky because it involves kernel modules,
  DisplayLink/EVDI packages, Secure Boot MOK enrollment, group membership, and
  sometimes reboot.
- The installer includes Android phone onboarding instead of assuming the phone
  is already configured.

## Current User-Facing Flow

Fresh Fedora/source checkout:

```bash
git clone https://github.com/ppotepa/WBeam.git
cd WBeam
./install-wbeam
```

Preview without changing the system:

```bash
./install-wbeam --dry-run
```

Recommended backend:

```bash
./install-wbeam --backend wayland
```

Advanced EVDI backend:

```bash
./install-wbeam --backend evdi
```

Rerun only phone onboarding:

```bash
./wbeam device setup
```

Developer flow still works as before:

```bash
./redeploy-local
```

If EVDI should be skipped in dev flow:

```bash
WBEAM_REDEPLOY_WITH_EVDI=0 ./redeploy-local
```

## What Was Fixed

### Fedora native build dependencies

Initial Rust build failed because pkg-config could not find:

- `glib-2.0`
- `gobject-2.0`
- `gio-2.0`
- `gstreamer-1.0`
- `gstreamer-base-1.0`
- `gstreamer-app-1.0`
- `gstreamer-video-1.0`

Fixes:

- `scripts/fedora-setup.sh` installs Fedora build/runtime packages.
- `redeploy-local` detects missing pkg-config modules and runs the Fedora
  setup script automatically.
- Host build errors now print concrete Fedora package hints.

Important packages now covered include:

- `glib2-devel`
- `gstreamer1-devel`
- `gstreamer1-plugins-base-devel`
- `gstreamer1-plugins-good`
- `gstreamer1-plugins-bad-free`
- `gstreamer1-plugin-openh264`
- `webkit2gtk4.1-devel`
- `libappindicator-gtk3-devel`
- `librsvg2-devel`
- `libxdo-devel`
- `pkgconf-pkg-config`
- `rust`, `cargo`, `nodejs`, `npm`
- `java-21-openjdk-devel`
- `android-tools`

### Java/Gradle Android build issue

Android build failed with:

```text
Unsupported class file major version 69
```

Cause: Gradle path was using too-new Java on Fedora.

Fixes:

- Android build selects Java 21 when available.
- Fedora setup installs `java-21-openjdk-devel`.
- `./wbeam android build` now reports the selected `JAVA_HOME`.

### Android SDK missing

Android build later failed because the SDK was missing.

Fixes:

- `scripts/fedora-setup.sh` bootstraps Android command-line tools under
  `~/Android/Sdk`.
- It installs:
  - `platform-tools`
  - `platforms;android-35`
  - `build-tools;35.0.0`
  - `cmdline-tools;latest`
- It accepts SDK licenses.
- It writes `android/local.properties`.
- `redeploy-local` auto-runs the setup when Android deploy is enabled and SDK is
  missing.

### Android install failure handling

ADB install hit:

```text
INSTALL_FAILED_USER_RESTRICTED
```

Fixes:

- Android install path now stops deploy after restricted install failure instead
  of continuing to launch a non-existent app.
- Error messaging points the user to USB debugging and `Install via USB`,
  especially for Xiaomi/HyperOS/MIUI.

### EVDI bootstrap on Fedora 43

Fedora 43 did not have a simple package path for EVDI. COPR returned 404 for
Fedora 43.

Fixes:

- `redeploy-local` detects EVDI runtime readiness.
- `scripts/fedora-setup.sh --with-evdi` tries:
  1. `akmod-evdi`
  2. `evdi-dkms`
  3. optional COPR if explicitly requested
  4. DisplayLink/EVDI GitHub Release RPM fallback
- DisplayLink GitHub RPM resolution uses the Fedora version and architecture.
- The script registers `/usr/libexec/displaylink` through ldconfig so
  `libevdi.so` can be found by the linker/runtime.
- The script adds the user to the `video` group when needed.
- The script tries `modprobe evdi initial_device_count=4`.

### EVDI Secure Boot/MOK flow

EVDI module load failed with:

```text
Key was rejected by service
```

Cause: Secure Boot rejected the DKMS module signing key.

Fixes:

- Setup detects Secure Boot.
- Setup checks `/var/lib/dkms/mok.pub`.
- Setup queues MOK enrollment with `mokutil --import /var/lib/dkms/mok.pub`
  when possible.
- Setup writes `.cache/evdi-mok-import-pending`.
- `redeploy-local` detects pending MOK enrollment and stops early with a clear
  reboot instruction.
- After reboot/MOK enrollment, setup can continue and load EVDI.

Manual step that cannot be automated:

```text
Reboot -> Enroll MOK -> Continue -> Yes -> enter temporary password
```

### Kernel-devel mismatch diagnostics

Fedora may have `kernel-devel` installed for a different kernel than
`uname -r`.

Fixes:

- `scripts/fedora-setup.sh` checks `/lib/modules/$(uname -r)/build`.
- If missing, it tries `kernel-devel-$(uname -r)`.
- If still unavailable, it prints installed kernel/kernel-devel versions and
  tells the user to reboot into the matching kernel or update kernel packages.
- `redeploy-local` reports `kernel-devel-mismatch`.

### EVDI linker issue

Streamer build failed with:

```text
/usr/bin/ld: cannot find -levdi
```

Cause: DisplayLink RPM placed `libevdi.so` under `/usr/libexec/displaylink`.

Fixes:

- `host/rust/crates/wbeamd-streamer/build.rs` now searches:
  - `WBEAM_EVDI_LIB_DIR`
  - `/usr/libexec/displaylink`
  - `/usr/lib64`
  - `/usr/lib`
  - `/usr/local/lib64`
  - `/usr/local/lib`
- It emits link-search and rpath metadata for the discovered EVDI library dir.
- `scripts/fedora-setup.sh` registers `/usr/libexec/displaylink` with ldconfig.

Local validation confirmed:

```text
ldd host/rust/target/release/wbeamd-streamer
```

finds `libevdi.so.1` from `/usr/libexec/displaylink`.

### Encoder fallback

Runtime showed "no supported encoder" because Fedora had OpenH264 but not H.265
encoders.

Fixes:

- The streamer now treats `openh264enc` as a supported H.264 encoder.
- If H.265 is requested but no H.265 encoder exists, streamer falls back to
  H.264 instead of failing.
- The resolved backend now drives HEVC/H.264 parsing decisions.
- Logs warn when requested H.265 falls back to H.264.

Validated locally with only `openh264enc` available.

### New installer wizard

Added files:

- `install-wbeam`
- `scripts/install-wizard.sh`
- `docs/INSTALLER_WIZARD.md`

Added CLI:

- `./wbeam install`
- `./wbeam device setup`

Wizard stages:

1. Probe machine:
   - distro/package manager
   - architecture
   - session type
   - Wayland socket
   - X11 socket
   - portal service state
   - PipeWire/WirePlumber service state
   - GStreamer encoder availability
   - EVDI library/module/user/Secure Boot state

2. Backend selection:
   - `wayland`: recommended default, covers Wayland portal and X11 fallback
   - `evdi`: advanced/risky

3. Plan:
   - prints what will be installed/built
   - explains EVDI risk
   - supports `--dry-run`
   - supports `--yes`

4. Install/build:
   - Fedora deps through `scripts/fedora-setup.sh --yes`
   - EVDI deps through `scripts/fedora-setup.sh --yes --with-evdi`
   - host build through `./wbeam host build`
   - systemd user service as `wbeam-daemon.service`

5. Validation:
   - host binary
   - streamer binary
   - service state
   - local control API reachability

6. Phone onboarding:
   - asks user to connect phone
   - runs/uses ADB
   - handles:
     - no device
     - unauthorized device
     - offline device
     - multiple devices
   - runs `./wbeam android deploy`
   - runs `./wbeam version doctor`
   - can be rerun with `./wbeam device setup`

Wizard state file:

```text
~/.local/state/wbeam/install-state.json
```

### Documentation updates

Updated:

- `README.md`
- `docs/FEDORA_43_SETUP.md`
- `docs/EVDI_SETUP_GUIDE.md`
- `EVDI_SETUP_INDEX.md`
- `scripts/EVDI_TOOLS_README.md`
- `docs/repo-structure.md`

New:

- `docs/INSTALLER_WIZARD.md`

The docs now distinguish:

- `install-wbeam`: fresh machine/source checkout setup
- `redeploy-local`: development rebuild/deploy loop

## Important Commands

Fedora dry-run:

```bash
scripts/fedora-setup.sh --dry-run --yes --with-evdi --no-android-sdk --no-group
```

Fresh-machine installer preview:

```bash
./install-wbeam --dry-run
./install-wbeam --dry-run --backend wayland --skip-device
./install-wbeam --dry-run --backend evdi --skip-device
```

Phone onboarding only:

```bash
./wbeam device setup
./wbeam device setup --dry-run
```

Developer redeploy:

```bash
./redeploy-local
```

Bypass EVDI in dev redeploy:

```bash
WBEAM_REDEPLOY_WITH_EVDI=0 ./redeploy-local
```

EVDI diagnostics:

```bash
scripts/evdi-diagnose.sh --verbose --fix
```

Build checks:

```bash
./wbeam host build
./wbeam android build
cargo test -p wbeamd-streamer
```

## Validation Already Run

Shell syntax:

```bash
bash -n install-wbeam scripts/install-wizard.sh wbeam scripts/fedora-setup.sh redeploy-local
```

Wizard dry-runs:

```bash
./install-wbeam --dry-run --backend wayland --skip-device
./install-wbeam --dry-run --backend evdi --skip-device
./wbeam device setup --dry-run
```

Repo/layout:

```bash
scripts/ci/check-repo-layout.sh
git diff --check
```

Streamer:

```bash
cargo test -p wbeamd-streamer
cargo build --release -p wbeamd-streamer --no-default-features --features evdi
```

Host:

```bash
./wbeam host build
```

EVDI link/runtime:

```bash
ldd host/rust/target/release/wbeamd-streamer | rg 'evdi|not found'
```

## Known Machine-Specific Observations

On the Fedora 43 test machine:

- Fedora 43 was detected.
- OpenH264 was available as `openh264enc`.
- H.265 encoders were not available, which is expected and now falls back to
  H.264.
- DisplayLink RPM installed EVDI.
- `libevdi.so.1` came from `/usr/libexec/displaylink`.
- Secure Boot initially blocked the EVDI module.
- MOK enrollment was required.
- After MOK enrollment/reboot, `modprobe evdi initial_device_count=4` succeeded.
- The user may still need to log out/in for `video` group membership to be
  reflected in the active session.

## Remaining Work

### Debian/Ubuntu

The installer detects Debian/Ubuntu but does not yet install packages for them.
Next work should add a Debian/Ubuntu package mapping equivalent to Fedora:

- build tools
- Rust/Cargo
- Node/npm
- Java 21 or compatible Java
- Android tools / Android SDK bootstrap
- GLib/GStreamer dev packages
- Tauri desktop deps
- GStreamer H.264 encoder packages
- optional EVDI packages if available

### Release packaging

Current `install-wbeam` builds from a source checkout. For GitHub Releases, the
same UX should remain but the install stage should switch to release assets:

- Fedora `.rpm`
- Debian/Ubuntu `.deb`
- release APK
- checksums
- one `install-wbeam` script as the user-facing entrypoint

Important packaging concern:

- A single public Linux package should not make Wayland-only installs depend on
  `libevdi.so`.
- Best future fix is runtime loading of EVDI (`dlopen`) or separate streamer
  binaries/packages for EVDI vs non-EVDI.

### Installer hardening

Recommended next improvements:

- Add non-interactive `--backend wayland --yes` CI smoke tests.
- Add Debian/Ubuntu implementation.
- Add package/release asset install mode.
- Add `wbeam doctor` as a first-class command if desired.
- Make phone onboarding use a release APK when running from GitHub Release
  instead of building debug APK locally.
- Add clearer remediation when ADB server cannot bind in restricted/sandboxed
  environments.

## Branch Commits

Branch commits relative to `origin/master` at handoff time:

```text
a984f6f installer: add source checkout wizard
91d2439 docs: update fedora bootstrap flow
7dbaa25 streamer: fallback to h264 encoder
bd2ca47 streamer: link displaylink evdi library
8143ff0 fedora: detect pending mok enrollment
49cab40 fedora: guide secure boot mok enrollment
eca2610 fedora: detect evdi kernel-devel mismatch
5f3bbf4 redeploy: auto bootstrap evdi on fedora
10c2bd2 fedora: install evdi from displaylink release rpm
517e74a redeploy: detect evdi runtime readiness
59a63fc fedora: improve evdi bootstrap diagnostics
b70e34a android: stop deploy after restricted install failure
b420273 streamer: support OpenH264 fallback on Fedora
```

## Files Changed on Branch

Key files changed relative to `origin/master`:

```text
EVDI_SETUP_INDEX.md
README.md
android/app/src/main/java/com/wbeam/MainActivity.java
android/app/src/main/java/com/wbeam/settings/SettingsRepository.java
docs/EVDI_SETUP_GUIDE.md
docs/FEDORA_43_SETUP.md
docs/INSTALLER_WIZARD.md
docs/repo-structure.md
host/rust/crates/wbeamd-streamer/build.rs
host/rust/crates/wbeamd-streamer/src/encode/h264.rs
host/rust/crates/wbeamd-streamer/src/encode/mod.rs
host/rust/crates/wbeamd-streamer/src/encode/selector.rs
host/rust/crates/wbeamd-streamer/src/main.rs
host/rust/crates/wbeamd-streamer/src/pipeline/builder/mod.rs
host/rust/crates/wbeamd-streamer/src/pipeline/builder/runtime.rs
install-wbeam
redeploy-local
scripts/EVDI_TOOLS_README.md
scripts/evdi-diagnose.sh
scripts/fedora-setup.sh
scripts/install-wizard.sh
wbeam
```

## Local Worktree Note

There is an unrelated local untracked file:

```text
setup-fedora43-rdp.sh
```

It was intentionally not touched or committed.
