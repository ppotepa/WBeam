# Fedora 43 Local Setup

This guide is for a Fedora 43 Workstation dev machine running WBeam from this
repo with the usual local flow:

```bash
./redeploy-local
```

WBeam has four moving parts on Fedora:

- Rust host daemon and streamer (`host/rust`)
- Tauri desktop app (`desktop/apps/desktop-tauri`)
- Android APK build/deploy (`android`)
- optional EVDI virtual display capture

## 0. Fresh Clone Flow

On Fedora 43, a clean workstation should start with the same command used on
the original dev machine:

```bash
git clone <repo> WBeam
cd WBeam
./redeploy-local
```

`redeploy-local` performs the normal local loop and bootstraps missing Fedora
dependencies as needed:

1. stops old host/desktop service state,
2. runs `scripts/fedora-setup.sh --yes` when native build packages or Android
   SDK components are missing,
3. adds `--with-evdi` automatically when EVDI is not ready,
4. builds the Rust daemon and streamer,
5. builds and deploys the Android APK to connected devices,
6. launches the desktop UI.

The script can install packages and download the Android command-line SDK, but
it cannot bypass OS or device trust prompts. These may still require manual
action:

- Fedora `sudo` authentication for `dnf`, `usermod`, `modprobe`, and `mokutil`.
- Secure Boot MOK enrollment for the DKMS EVDI module. If queued, reboot and
  choose `Enroll MOK -> Continue -> Yes`, then enter the temporary password.
- Logging out and back in after being added to the `video` group.
- Accepting Android USB debugging and allowing APK installs on the device.

Rerun `./redeploy-local` after completing any required reboot, login, or device
prompt.

### Current Fedora Bootstrap Coverage

The Fedora flow now handles these previously manual or dev-machine-only pieces:

- GLib/GObject/GIO and GStreamer development packages required by Rust crates.
- Java 21 selection for Gradle, avoiding Java 25 class-file incompatibility.
- Android command-line SDK installation under `~/Android/Sdk`, SDK licenses,
  platform 35, build tools 35.0.0, and `android/local.properties`.
- GStreamer H.264 runtime fallback through `openh264enc` when H.265 encoders are
  unavailable.
- EVDI package discovery using distro packages first, then DisplayLink RPM
  release assets.
- DisplayLink `libevdi.so` runtime/linker lookup from `/usr/libexec/displaylink`.
- Running-kernel `kernel-devel` mismatch diagnostics before DKMS build.
- Secure Boot MOK import, pending-reboot detection, and firmware enrollment
  instructions.
- `video` group membership checks for EVDI device access.

## 1. Install Fedora Packages

Install the base build, Tauri, GStreamer, Android SDK, and virtual-display
tools with the repo helper:

```bash
scripts/fedora-setup.sh --dry-run
scripts/fedora-setup.sh --yes
```

The equivalent manual command is:

```bash
sudo dnf group install -y "c-development"

sudo dnf install -y \
  git curl wget file pkgconf-pkg-config \
  gcc gcc-c++ make cmake clang openssl-devel \
  rust cargo \
  nodejs npm \
  python3 \
  java-21-openjdk-devel \
  android-tools mokutil \
  glib2-devel \
  gstreamer1-devel gstreamer1-plugins-base-devel \
  gstreamer1-plugins-good gstreamer1-plugins-bad-free \
  gstreamer1-plugin-openh264 gstreamer1-vaapi \
  webkit2gtk4.1-devel libappindicator-gtk3-devel librsvg2-devel libxdo-devel \
  xrandr xorg-x11-server-Xvfb \
  akmods dkms kernel-devel kernel-headers
```

Notes:

- `glib2-devel` fixes host streamer build failures like missing
  `glib-2.0.pc` or `gobject-2.0.pc`.
- `webkit2gtk4.1-devel`, `libappindicator-gtk3-devel`, `librsvg2-devel`, and
  `libxdo-devel` are the Fedora Tauri desktop build dependencies.
- `android-tools` provides Fedora's system `adb`. The setup script also
  installs Google's Android command-line SDK under `~/Android/Sdk` by default.
- If Fedora has installed a new kernel, reboot before building EVDI so
  `kernel-devel` matches `uname -r`.

## 2. Android SDK

The Gradle project uses Android Gradle Plugin `8.5.2`, `compileSdk = 35`, and
`buildToolsVersion = "35.0.0"`.

`scripts/fedora-setup.sh --yes` installs the command-line SDK by default:

```bash
scripts/fedora-setup.sh --yes
```

It downloads Android command-line tools into `~/Android/Sdk`, accepts SDK
licenses, installs `platform-tools`, `platforms;android-35`, and
`build-tools;35.0.0`, then writes `android/local.properties`.

To skip SDK bootstrap for host/desktop-only work:

```bash
scripts/fedora-setup.sh --yes --no-android-sdk
```

Android Studio is also fine:

1. Install Android Studio.
2. Open SDK Manager.
3. Install:
   - Android SDK Platform 35
   - Android SDK Build-Tools 35.0.0
   - Android SDK Platform-Tools
   - Android SDK Command-line Tools

Then set the SDK path for shells. Add this to `~/.bashrc` or equivalent:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
```

Reload the shell:

```bash
source ~/.bashrc
adb version
```

## 3. EVDI Capture

EVDI is the preferred low-latency capture path. On Fedora 43 it usually comes
from DisplayLink/EVDI packaging rather than the base Fedora repositories.

The normal `./redeploy-local` path requests EVDI automatically on Fedora. To
run the EVDI setup directly:

```bash
./wbeam deps virtual check
scripts/fedora-setup.sh --yes --with-evdi
```

The setup order is:

1. try `akmod-evdi`,
2. try `evdi-dkms`,
3. optionally try the historical `displaylink-rpm/displaylink` COPR,
4. fall back to the matching `fedora-43-displaylink-*.rpm` asset from
   `displaylink-rpm/displaylink-rpm` GitHub Releases.

To explicitly try COPR before the GitHub RPM fallback:

```bash
scripts/fedora-setup.sh --yes --enable-evdi-copr
```

On Fedora 43 the COPR endpoint may return 404. That is expected; the GitHub
Release RPM fallback is the current automatic path.

Alternatively, enable the DisplayLink/EVDI package source you use on this
machine, then install one of:

```bash
sudo dnf install -y akmod-evdi
# or
sudo dnf install -y evdi-dkms
```

Load the module:

```bash
sudo modprobe evdi initial_device_count=4
```

Verify:

```bash
bash scripts/evdi-diagnose.sh --verbose
./wbeam deps virtual check
```

If Secure Boot is enabled, Fedora may refuse to load unsigned DKMS/akmod kernel
modules with `Key was rejected by service`. Enroll the DKMS MOK key and reboot,
or disable Secure Boot for this dev machine:

```bash
sudo mokutil --import /var/lib/dkms/mok.pub
sudo reboot
sudo dkms autoinstall
```

`scripts/fedora-setup.sh --with-evdi` detects this state and, when run from an
interactive terminal, queues `mokutil --import /var/lib/dkms/mok.pub`
automatically. You still must reboot and complete the firmware MOK manager
screen manually: `Enroll MOK -> Continue -> Yes`, then enter the temporary
password you chose. After the import is queued, `redeploy-local` records this
as `secureboot-mok-pending-reboot` and stops early until you reboot and finish
the firmware enrollment. If `mokutil --import` reports `SKIP: ... already in
the enrollment request`, reboot instead of rerunning setup.

If `modprobe evdi` reports `Module evdi not found`, also check that the running
kernel matches installed kernel headers:

```bash
uname -r
ls -l /lib/modules/$(uname -r)/build
rpm -q kernel-core kernel-devel
```

When the running kernel is older than the installed `kernel-devel`, reboot into
the newer installed kernel and rerun `./redeploy-local`. On this class of
failure DKMS shows `evdi/<version>: added`, but no module is built yet.

The older distro-neutral EVDI helper is still available:

```bash
sudo bash scripts/evdi-setup.sh
```

Prefer `scripts/fedora-setup.sh --yes --with-evdi` on Fedora 43 because it knows
about the DisplayLink RPM fallback, Secure Boot MOK state, `libevdi` linker
path, and the running-kernel `kernel-devel` check.

## 4. Build and Run

From the repo root:

```bash
npm --prefix desktop/apps/desktop-tauri ci
./wbeam host build
```

Connect an Android device with USB debugging enabled:

```bash
adb devices
```

Run the normal local redeploy flow:

```bash
./redeploy-local
```

On Fedora, `redeploy-local` automatically runs `scripts/fedora-setup.sh --yes`
when native host build dependencies are missing. It also tries to make EVDI
ready automatically, using distro packages first and the Fedora DisplayLink/EVDI
GitHub Release RPM if needed.
If Android deploy is enabled and the SDK is missing, `redeploy-local`
automatically runs `scripts/fedora-setup.sh --yes --with-android-sdk`.
To disable auto dependency installation:

```bash
./redeploy-local --no-auto-deps
```

To skip EVDI and force Wayland/X11 fallback only:

```bash
WBEAM_REDEPLOY_WITH_EVDI=0 ./redeploy-local
```

Useful variants:

```bash
./redeploy-local --no-android          # host + desktop only
./redeploy-local --no-desktop-start    # build/deploy, do not launch GUI
./redeploy-local --host-restart        # also start host debug daemon
```

Check the final state:

```bash
./wbeam version doctor
./wbeam host status
./wbeam watch connections --once
```

## 5. Common Fedora Fixes

### `glib-2.0.pc` or `gobject-2.0.pc` Missing

`./wbeam host build` runs a preflight for these native libraries before Cargo
starts compiling the streamer. If it reports missing `glib-2.0`,
`gstreamer-1.0`, or related modules, run the Fedora setup script:

```bash
scripts/fedora-setup.sh --yes
```

Install:

```bash
sudo dnf install -y glib2-devel pkgconf-pkg-config
```

Then rerun:

```bash
./wbeam host build
```

### `adb` Missing

Install Fedora's ADB package or use the Android SDK platform-tools:

```bash
sudo dnf install -y android-tools
adb devices
```

If the device is listed as `unauthorized`, unlock the device and accept the USB
debugging prompt.

### Gradle Cannot Find SDK Platform 35 or Build Tools 35.0.0

`./wbeam android build` checks the Android SDK before invoking Gradle. Install
or repair the SDK with:

```bash
scripts/fedora-setup.sh --yes --with-android-sdk
```

If you already have command-line tools, you can use `sdkmanager` directly:

```bash
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

Make sure `ANDROID_HOME` points at the SDK root.

### Gradle Fails with `Unsupported class file major version 69`

That means Gradle was launched with Java 25. WBeam selects Java 21
automatically when `/usr/lib/jvm/java-21-openjdk` is installed:

```bash
sudo dnf install -y java-21-openjdk-devel
```

To override explicitly:

```bash
export WBEAM_ANDROID_JAVA_HOME=/usr/lib/jvm/java-21-openjdk
```

### Tauri Desktop Build Fails on WebKit/AppIndicator

Install the Fedora Tauri dependencies:

```bash
sudo dnf install -y webkit2gtk4.1-devel libappindicator-gtk3-devel librsvg2-devel libxdo-devel
```

Then rebuild:

```bash
npm --prefix desktop/apps/desktop-tauri run build
```

### No Supported Encoder Found

The host streamer needs at least one supported H.264 encoder. On Fedora, the
normal fallback is `openh264enc` from `gstreamer1-plugin-openh264`.

Check encoders:

```bash
gst-inspect-1.0 openh264enc x264enc nvh264enc
```

Repair the Fedora install:

```bash
scripts/fedora-setup.sh --yes --no-android-sdk
```

H.265 is optional. If `nvh265enc` or `x265enc` is unavailable, use H.264 in the
Android app or desktop UI. Current streamer builds also fall back from requested
`h265` to H.264 automatically when a supported H.264 encoder is available. The
effective runtime log should then show `requested_encoder=h265` and
`resolved_backend=openh264`, `x264`, or `nvenc264`, with `parse_mode=h264_*`.

### EVDI Builds but Does Not Load

Check the kernel/module state:

```bash
uname -r
dkms status
modinfo evdi
sudo modprobe evdi initial_device_count=4
dmesg | tail -80 | grep -i evdi
```

If `kernel-devel` does not match `uname -r`, update/reboot and rebuild the
module.

### Streamer Linker Cannot Find libevdi

The DisplayLink RPM installs `libevdi.so` under `/usr/libexec/displaylink`.
WBeam's streamer build script detects that path automatically. If a custom EVDI
package installs it elsewhere and the build fails with `cannot find -levdi`,
point the build at that directory:

```bash
WBEAM_EVDI_LIB_DIR=/path/to/libevdi-dir ./wbeam host build
```

### Wayland Fallback

If EVDI is not ready yet, WBeam can still use the Wayland portal fallback. It is
slower and compositor-dependent, but it is enough to verify the rest of the
stack:

```bash
./redeploy-local --no-host-build
./desktop.sh
```

Choose the Wayland portal capture backend in the desktop UI.

## 6. Quick Preflight

Run this before `./redeploy-local` on a fresh Fedora install:

```bash
command -v cargo node npm java adb pkg-config
pkg-config --exists glib-2.0 gobject-2.0 gstreamer-1.0
./wbeam deps virtual check
adb devices
```

Expected result:

- all commands resolve,
- `pkg-config` exits successfully,
- virtual deps are either OK or only EVDI is knowingly deferred,
- at least one Android device is in `device` state if Android deploy is wanted.

## 7. Clean Fedora Validation Checklist

Use this when validating a newly cloned repo on a Fedora machine that was not
used for WBeam development before:

```bash
git clone <repo> WBeam
cd WBeam
./redeploy-local
```

Record the first stop point exactly. The expected recoverable stops are:

- `secureboot-mok-pending-reboot`: reboot, enroll MOK in firmware, log back in,
  rerun `./redeploy-local`.
- `user-not-in-video`: log out and back in, rerun `./redeploy-local`.
- Android `INSTALL_FAILED_USER_RESTRICTED`: allow APK installs/USB debugging on
  the device, rerun `./redeploy-local`.

After the final run, verify:

```bash
./wbeam version doctor
./wbeam host build
./wbeam android build
bash scripts/evdi-diagnose.sh --verbose
gst-inspect-1.0 openh264enc x264enc nvh264enc nvh265enc x265enc
ldd host/rust/target/release/wbeamd-streamer | grep -E 'evdi|not found'
```

Expected result:

- `./wbeam host build` builds `wbeamd-server` and `wbeamd-streamer`.
- If DisplayLink RPM provides EVDI, `libevdi.so.1` resolves from
  `/usr/libexec/displaylink` or another installed EVDI library directory.
- At least one H.264 encoder exists. H.265 may be absent; WBeam should fall
  back to H.264.
- `./redeploy-local` reaches Android deploy and desktop launch unless a device
  trust prompt remains.
