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
  java-21-openjdk-devel \
  android-tools \
  glib2-devel \
  gstreamer1-devel gstreamer1-plugins-base-devel \
  gstreamer1-plugins-good gstreamer1-plugins-bad-free \
  gstreamer1-plugin-openh264 gstreamer1-vaapi \
  webkit2gtk4.1-devel libappindicator-gtk3-devel librsvg2-devel libxdo-devel \
  xrandr xorg-x11-server-Xvfb \
  dkms kernel-devel kernel-headers
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

EVDI is the preferred low-latency capture path, but Fedora usually needs an
external package source for `evdi-dkms` or `akmod-evdi`.

First try the repo helper:

```bash
./wbeam deps virtual check
scripts/fedora-setup.sh --yes --with-evdi
```

If that cannot install EVDI and you want the DisplayLink RPM COPR path, run:

```bash
scripts/fedora-setup.sh --yes --enable-evdi-copr
```

Or enable the DisplayLink/EVDI package source you use on this machine, then
install one of:

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
modules. Either sign the module for Secure Boot or disable Secure Boot for this
dev machine.

The older distro-neutral EVDI helper is still available:

```bash
sudo bash scripts/evdi-setup.sh
```

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
when native host build dependencies are missing. EVDI is optional in the default
flow; without `libevdi.so`, the streamer is built for Wayland/X11 fallback only.
If Android deploy is enabled and the SDK is missing, `redeploy-local`
automatically runs `scripts/fedora-setup.sh --yes --with-android-sdk`.
To disable auto dependency installation:

```bash
./redeploy-local --no-auto-deps
```

To require EVDI during redeploy:

```bash
WBEAM_REDEPLOY_WITH_EVDI=1 ./redeploy-local
```

If EVDI must come from the DisplayLink COPR:

```bash
WBEAM_REDEPLOY_ENABLE_EVDI_COPR=1 ./redeploy-local
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
