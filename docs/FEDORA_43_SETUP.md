# Fedora 43 source setup

Run these commands as your normal user in a graphical desktop session. Fedora
43 with GNOME Wayland is a release acceptance target; that does not certify every
kernel/EVDI combination. See the [EVDI evidence matrix](EVDI_COMPATIBILITY_MATRIX.md).

## First installation

```bash
git clone https://github.com/ppotepa/WBeam.git
cd WBeam
./install-waybeam --dry-run
./install-waybeam --backend wayland
```

The [installer](INSTALLER_WIZARD.md) probes the machine, installs Fedora
prerequisites, builds the host, starts a systemd user service and onboards the
Android device. Wayland Portal needs a working compositor portal and PipeWire;
accept the screen-sharing prompt when starting a stream.

For an EVDI virtual display, use `./install-waybeam --backend evdi` instead.
EVDI setup can require a kernel-module build, MOK enrollment, a new login and a
reboot. X11 helpers remain legacy code and are not the runtime fallback.

## Dependency helper

```bash
scripts/fedora-setup.sh --yes
scripts/fedora-setup.sh --yes --no-android-sdk  # host/desktop work only
```

The helper installs Rust/Cargo, Node/npm, Java 21, ADB, native Tauri/GStreamer
build dependencies and an H.264 encoder package. Its package list in
[`scripts/fedora-setup.sh`](../scripts/fedora-setup.sh) is the source of truth.
It also installs Android command-line tools unless skipped.

`--dry-run` prints package commands, but the script still probes the current
Fedora installation and checks installed encoders. On a fresh machine it can
stop before printing every later stage; this is not an installation failure.

### Android build tools

The project uses Android Gradle Plugin 8.5.2, compile SDK 35 and Build Tools
35.0.0. The helper installs these under `~/Android/Sdk` by default, accepts SDK
licenses and writes `android/local.properties`. A custom SDK root can be passed
with `--android-sdk-root PATH`.

For interactive shells:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
adb devices
```

The device must appear as `device`, not `unauthorized` or `offline`. Unlock it
and accept USB debugging. Retry onboarding with `./waybeam device setup`.

## EVDI setup

```bash
scripts/fedora-setup.sh --yes --with-evdi
bash scripts/virtual-deps-check.sh --json --backend evdi
bash scripts/evdi-diagnose.sh --verbose
```

The helper tries `akmod-evdi`, then `evdi-dkms`, then a matching
`fedora-<version>-displaylink-*.rpm` from the DisplayLink RPM project's latest
GitHub release. A suitable asset may be absent. `--enable-evdi-copr` additionally
tries the historical COPR before package installation; do not assume that
repository exists for a particular Fedora version.

The helper checks the running kernel's build tree, builds a missing module via
DKMS, checks signing, adds the user to `video`, and attempts to load EVDI. The
DisplayLink library directory `/usr/libexec/displaylink` is registered when
needed. Package installation or `modinfo` success alone does not prove capture
works; finish with a real Android stream.

### Kernel or Secure Boot stops

```bash
uname -r
rpm -q kernel-core kernel-devel
dkms status
modinfo evdi
mokutil --sb-state
```

If matching `kernel-devel` is unavailable, boot a kernel with matching headers
and rerun setup. If Secure Boot rejects the module, enroll the key used to sign
it. The helper can queue the DKMS key from `/var/lib/dkms/mok.pub` in an
interactive terminal. Complete `Enroll MOK → Continue → Yes` on reboot, using
the temporary password you selected. Rerun setup after logging back in.

`secureboot-mok-pending-reboot` means the enrollment request still needs that
firmware step. `user-not-in-video` means the active session needs the new group
membership; log out and back in. Do not repeatedly rebuild to solve either.

Kernel API/compiler failures, including the reported Fedora 43 kernel
6.19.11/GCC 15 `-Werror=sign-compare` failure, are separate from missing
`libevdi.so` linker errors. Follow the [compatibility matrix](EVDI_COMPATIBILITY_MATRIX.md)
to collect evidence for the exact driver/kernel pair.

## Development loop

```bash
./redeploy-local
```

This stops old processes, removes the desktop-managed user service, builds the
host and desktop, deploys the Android APK to authorized connected devices, and
launches the desktop UI. The desktop then manages its service lifecycle.
Missing Fedora dependencies trigger the setup helper automatically; EVDI
bootstrap is requested by default on Fedora.

| Command | Effect |
| --- | --- |
| `WBEAM_REDEPLOY_WITH_EVDI=0 ./redeploy-local` | Skip automatic EVDI setup; select Wayland Portal in the UI. This variable does not pin runtime capture. |
| `./redeploy-local --no-auto-deps` | Do not install dependencies automatically. |
| `./redeploy-local --no-android` | Skip Android deploy. |
| `./redeploy-local --no-desktop-start` | Build/deploy without launching the desktop. |
| `./redeploy-local --host-restart` | Also start the host debug daemon. |

`--no-host-build` reuses existing binaries; it is not a way to fix missing or
outdated host binaries. A redeploy can finish without an Android install if no
authorized device is connected, so inspect its output.

## Troubleshooting

| Symptom | Check or repair |
| --- | --- |
| Missing `glib-2.0.pc`, `gobject-2.0.pc` or GStreamer development files | Run `scripts/fedora-setup.sh --yes --no-android-sdk`, then `./waybeam host build`. |
| Missing SDK platform/build tools | Run `scripts/fedora-setup.sh --yes --with-android-sdk`; verify `android/local.properties` or `ANDROID_HOME`. |
| Gradle reports class-file major version 69 | Install Java 21 (`sudo dnf install java-21-openjdk-devel`); override selection with `WBEAM_ANDROID_JAVA_HOME=/usr/lib/jvm/java-21-openjdk` if necessary. |
| Tauri native dependency errors | Rerun the helper for WebKit/AppIndicator dependencies, then launch `./desktop.sh`. |
| No H.264 encoder | Check `openh264enc`, `x264enc`, `nvh264enc` individually with `gst-inspect-1.0`; rerun the helper to install the Fedora OpenH264 fallback. |
| EVDI module or library failure | Use the [EVDI setup guide](EVDI_SETUP_GUIDE.md#diagnostics); separate module build/load, runtime library and source linker failures. |
| Android install is blocked | Accept USB debugging and the device's install-via-USB permission, then rerun `./waybeam device setup`. |

H.265 is optional. When a requested H.265 encoder is unavailable, the streamer
can resolve to H.264 if a supported encoder exists. Inspect the effective
configuration/logs rather than assuming the requested codec was selected.

## Verify the result

```bash
./waybeam host status
curl -fsS http://127.0.0.1:5001/v1/host-probe
./waybeam version doctor
./waybeam watch connections --once
```

For a source build, `./waybeam host build` should produce the daemon and streamer;
`./waybeam android build` should produce the debug APK. For EVDI, also inspect
`ldd host/rust/target/release/wbeamd-streamer` for a resolved, matching libevdi
ABI and missing libraries.

Start a stream in the desktop UI and verify actual Android frames. Record the
selected backend, effective codec, kernel, driver/package versions and observed
results using the [release checklist](release-checklist.md).
