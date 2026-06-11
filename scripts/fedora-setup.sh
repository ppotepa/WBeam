#!/usr/bin/env bash
set -euo pipefail

DRY_RUN=0
YES=0
WITH_EVDI=0
ENABLE_EVDI_COPR=0
LOAD_EVDI=1
INSTALL_GROUP=1
WITH_ANDROID_SDK=1
ANDROID_SDK_ROOT_ARG="${WBEAM_ANDROID_SDK_ROOT:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}"
ANDROID_CMDLINE_TOOLS_URL="${WBEAM_ANDROID_CMDLINE_TOOLS_URL:-https://dl.google.com/android/repository/commandlinetools-linux-14742923_latest.zip}"
SCRIPT_PATH="$(readlink -f "${BASH_SOURCE[0]}")"
ROOT_DIR="$(cd "$(dirname "$SCRIPT_PATH")/.." && pwd)"

usage() {
  cat <<'USAGE'
Usage:
  scripts/fedora-setup.sh [options]

Installs Fedora packages needed for local WBeam development:
  - Rust host build
  - Tauri desktop build
  - GStreamer streamer build/runtime
  - ADB and virtual-display helper tools
  - Android command-line SDK for APK builds

Options:
  --dry-run             print commands without running them
  -y, --yes             pass -y to dnf commands
  --with-android-sdk    install Android command-line tools and SDK packages (default)
  --no-android-sdk      skip Android SDK bootstrap
  --android-sdk-root P  install/use Android SDK root P (default: ~/Android/Sdk)
  --with-evdi           also try to install EVDI/displaylink packages
  --enable-evdi-copr    try displaylink-rpm/displaylink COPR before GitHub RPM fallback
  --no-evdi-load        do not run modprobe evdi after EVDI install
  --no-group            skip "c-development" group install
  -h, --help            show this help

Notes:
  Android SDK bootstrap downloads Google's Android command-line tools, accepts
  SDK licenses, and installs platform-tools, platforms;android-35, and
  build-tools;35.0.0.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    -y|--yes)
      YES=1
      shift
      ;;
    --with-android-sdk)
      WITH_ANDROID_SDK=1
      shift
      ;;
    --no-android-sdk)
      WITH_ANDROID_SDK=0
      shift
      ;;
    --android-sdk-root)
      if [[ -z "${2:-}" ]]; then
        echo "[fedora-setup] --android-sdk-root requires a path" >&2
        exit 2
      fi
      ANDROID_SDK_ROOT_ARG="$2"
      shift 2
      ;;
    --with-evdi)
      WITH_EVDI=1
      shift
      ;;
    --enable-evdi-copr)
      ENABLE_EVDI_COPR=1
      WITH_EVDI=1
      shift
      ;;
    --no-evdi-load)
      LOAD_EVDI=0
      shift
      ;;
    --no-group)
      INSTALL_GROUP=0
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "[fedora-setup] unknown arg: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

command_exists() {
  command -v "$1" >/dev/null 2>&1
}

run_cmd() {
  if [[ "$DRY_RUN" -eq 1 ]]; then
    printf '[fedora-setup] DRY-RUN:'
    printf ' %q' "$@"
    printf '\n'
    return 0
  fi
  printf '[fedora-setup] RUN:'
  printf ' %q' "$@"
  printf '\n'
  "$@"
}

run_shell() {
  if [[ "$DRY_RUN" -eq 1 ]]; then
    echo "[fedora-setup] DRY-RUN: $*"
    return 0
  fi
  echo "[fedora-setup] RUN: $*"
  bash -lc "$*"
}

with_sudo() {
  if [[ "${EUID:-$(id -u)}" -eq 0 ]]; then
    run_cmd "$@"
  else
    run_cmd sudo "$@"
  fi
}

dnf_args() {
  if [[ "$YES" -eq 1 ]]; then
    printf '%s\n' "-y"
  fi
}

dnf_install() {
  local args=()
  mapfile -t args < <(dnf_args)
  with_sudo dnf install "${args[@]}" "$@"
}

dnf_group_install() {
  local args=()
  mapfile -t args < <(dnf_args)
  with_sudo dnf group install "${args[@]}" "$@"
}

target_home() {
  if [[ -n "${SUDO_USER:-}" && "${SUDO_USER}" != "root" ]]; then
    getent passwd "$SUDO_USER" | awk -F: '{print $6}'
    return 0
  fi
  echo "$HOME"
}

android_sdk_root() {
  if [[ -n "$ANDROID_SDK_ROOT_ARG" ]]; then
    echo "$ANDROID_SDK_ROOT_ARG"
    return 0
  fi
  echo "$(target_home)/Android/Sdk"
}

android_sdkmanager_path() {
  local sdk_root="$1"
  local candidate
  for candidate in \
    "$sdk_root/cmdline-tools/latest/bin/sdkmanager" \
    "$sdk_root/cmdline-tools/bin/sdkmanager" \
    "$sdk_root/tools/bin/sdkmanager"; do
    if [[ -x "$candidate" ]]; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

ensure_android_cmdline_tools() {
  local sdk_root="$1"
  local tmp_dir zip_file extracted
  if android_sdkmanager_path "$sdk_root" >/dev/null 2>&1; then
    return 0
  fi

  tmp_dir="$(mktemp -d)"
  zip_file="$tmp_dir/cmdline-tools.zip"
  extracted="$tmp_dir/extracted"
  run_cmd mkdir -p "$sdk_root/cmdline-tools"
  run_cmd curl -L --fail --show-error "$ANDROID_CMDLINE_TOOLS_URL" -o "$zip_file"
  run_cmd unzip -q "$zip_file" -d "$extracted"
  if [[ "$DRY_RUN" -eq 1 ]]; then
    echo "[fedora-setup] DRY-RUN: install cmdline-tools into $sdk_root/cmdline-tools/latest"
  else
    rm -rf "$sdk_root/cmdline-tools/latest"
    mv "$extracted/cmdline-tools" "$sdk_root/cmdline-tools/latest"
    rm -rf "$tmp_dir"
  fi
}

ensure_android_sdk() {
  local sdk_root sdkmanager missing=()
  sdk_root="$(android_sdk_root)"
  echo "[fedora-setup] Android SDK root: $sdk_root"
  ensure_android_cmdline_tools "$sdk_root"
  if ! sdkmanager="$(android_sdkmanager_path "$sdk_root")"; then
    if [[ "$DRY_RUN" -eq 1 ]]; then
      sdkmanager="$sdk_root/cmdline-tools/latest/bin/sdkmanager"
    else
      echo "[fedora-setup] ERROR: sdkmanager not found after command-line tools install." >&2
      return 1
    fi
  fi

  [[ -d "$sdk_root/platforms/android-35" ]] || missing+=("platforms;android-35")
  [[ -d "$sdk_root/build-tools/35.0.0" ]] || missing+=("build-tools;35.0.0")
  [[ -x "$sdk_root/platform-tools/adb" ]] || missing+=("platform-tools")
  [[ -d "$sdk_root/cmdline-tools/latest" ]] || missing+=("cmdline-tools;latest")

  if [[ "${#missing[@]}" -gt 0 ]]; then
    run_shell "yes | '$sdkmanager' --sdk_root='$sdk_root' --licenses >/dev/null || true"
    run_cmd "$sdkmanager" --sdk_root="$sdk_root" "${missing[@]}"
  else
    echo "[fedora-setup] Android SDK components already installed."
  fi

  if [[ -d "$ROOT_DIR/android" ]]; then
    run_shell "printf 'sdk.dir=%s\n' '$sdk_root' > '$ROOT_DIR/android/local.properties'"
  fi

  if [[ -n "${SUDO_USER:-}" && "${SUDO_USER}" != "root" && "$sdk_root" == "$(target_home)"/* ]]; then
    with_sudo chown -R "$SUDO_USER":"$SUDO_USER" "$sdk_root"
  fi

  echo "[fedora-setup] Android SDK ready. For interactive shells, add:"
  echo "  export ANDROID_HOME=\"$sdk_root\""
  echo "  export ANDROID_SDK_ROOT=\"\$ANDROID_HOME\""
  echo "  export PATH=\"\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/cmdline-tools/latest/bin:\$PATH\""
}

detect_fedora() {
  if [[ ! -f /etc/os-release ]]; then
    return 1
  fi
  # shellcheck disable=SC1091
  . /etc/os-release
  [[ "${ID:-}" == "fedora" ]]
}

fedora_version_id() {
  if [[ -f /etc/os-release ]]; then
    # shellcheck disable=SC1091
    . /etc/os-release
    echo "${VERSION_ID:-unknown}"
    return 0
  fi
  echo "unknown"
}

print_post_check() {
  echo
  echo "[fedora-setup] Post-install checks:"
  echo "  command -v cargo node npm java adb pkg-config"
  echo "  pkg-config --exists glib-2.0 gobject-2.0 gstreamer-1.0"
  echo "  ./wbeam deps virtual check"
  echo "  ./wbeam host build"
  echo "  ./wbeam android build"
}

gst_element_present() {
  local element="$1"
  command_exists gst-inspect-1.0 && gst-inspect-1.0 "$element" >/dev/null 2>&1
}

verify_gstreamer_encoders() {
  local h264_ok=0
  local h265_ok=0

  for element in nvh264enc x264enc openh264enc; do
    if gst_element_present "$element"; then
      h264_ok=1
      echo "[fedora-setup] H.264 encoder available: $element"
      break
    fi
  done

  for element in nvh265enc x265enc; do
    if gst_element_present "$element"; then
      h265_ok=1
      echo "[fedora-setup] H.265 encoder available: $element"
      break
    fi
  done

  if [[ "$h264_ok" -ne 1 ]]; then
    echo "[fedora-setup] ERROR: no supported H.264 GStreamer encoder found." >&2
    echo "[fedora-setup] Expected one of: nvh264enc, x264enc, openh264enc." >&2
    echo "[fedora-setup] Try: sudo dnf install -y gstreamer1-plugin-openh264 gstreamer1-plugins-bad-free" >&2
    return 1
  fi

  if [[ "$h265_ok" -ne 1 ]]; then
    echo "[fedora-setup] WARN: no supported H.265 GStreamer encoder found (nvh265enc/x265enc)." >&2
    echo "[fedora-setup] WBeam will use H.264 unless H.265 support is installed separately." >&2
  fi
}

enable_evdi_copr() {
  local copr_args=()
  mapfile -t copr_args < <(dnf_args)
  dnf_install dnf-plugins-core || true

  if with_sudo dnf copr enable "${copr_args[@]}" displaylink-rpm/displaylink; then
    return 0
  fi

  local version_id
  version_id="$(fedora_version_id)"
  echo "[fedora-setup] ERROR: displaylink-rpm/displaylink COPR is not available for Fedora ${version_id}." >&2
  echo "[fedora-setup] Fedora returned 404 for the COPR repo, so akmod-evdi/evdi-dkms cannot be installed automatically from that source." >&2
  echo "[fedora-setup] Falling back to displaylink-rpm/displaylink-rpm GitHub Releases." >&2
  return 1
}

rpm_arch() {
  case "$(uname -m)" in
    x86_64) echo "x86_64" ;;
    aarch64|arm64) echo "aarch64" ;;
    *)
      echo "[fedora-setup] ERROR: unsupported architecture for DisplayLink RPM: $(uname -m)" >&2
      return 1
      ;;
  esac
}

github_evdi_release_rpm_url() {
  local version_id arch api_url
  version_id="$(fedora_version_id)"
  arch="$(rpm_arch)"
  api_url="${WBEAM_EVDI_GITHUB_RELEASE_API:-https://api.github.com/repos/displaylink-rpm/displaylink-rpm/releases/latest}"

  python3 - "$version_id" "$arch" "$api_url" <<'PY'
import json
import sys
import urllib.request

version_id, arch, api_url = sys.argv[1:]
request = urllib.request.Request(
    api_url,
    headers={
        "Accept": "application/vnd.github+json",
        "User-Agent": "WBeam-fedora-setup",
    },
)

with urllib.request.urlopen(request, timeout=30) as response:
    release = json.load(response)

prefix = f"fedora-{version_id}-displaylink-"
suffix = f".{arch}.rpm"
for asset in release.get("assets", []):
    name = asset.get("name", "")
    if name.startswith(prefix) and name.endswith(suffix) and ".src." not in name:
        print(asset["browser_download_url"])
        raise SystemExit(0)

tag = release.get("tag_name", "latest")
print(
    f"No displaylink-rpm asset found for Fedora {version_id} {arch} in release {tag}",
    file=sys.stderr,
)
raise SystemExit(1)
PY
}

install_evdi_from_github_release() {
  local version_id arch rpm_url yes_args
  version_id="$(fedora_version_id)"
  arch="$(rpm_arch)"

  if [[ "$DRY_RUN" -eq 1 ]]; then
    yes_args="$(dnf_args | tr '\n' ' ')"
    echo "[fedora-setup] DRY-RUN: resolve displaylink-rpm GitHub Release asset for Fedora ${version_id} ${arch}"
    echo "[fedora-setup] DRY-RUN: sudo dnf install ${yes_args}https://github.com/displaylink-rpm/displaylink-rpm/releases/download/<latest>/fedora-${version_id}-displaylink-*.${arch}.rpm"
    return 0
  fi

  echo "[fedora-setup] Resolving DisplayLink/EVDI RPM from GitHub Releases for Fedora ${version_id} ${arch}"
  if ! rpm_url="$(github_evdi_release_rpm_url)"; then
    echo "[fedora-setup] ERROR: no compatible DisplayLink/EVDI RPM found in GitHub Releases." >&2
    echo "[fedora-setup] EVDI mode will not work until a Fedora ${version_id} package exists or EVDI is installed manually." >&2
    return 1
  fi

  echo "[fedora-setup] Installing DisplayLink/EVDI RPM: $rpm_url"
  dnf_install "$rpm_url"
}

running_kernel_devel_ready() {
  local running_kernel build_dir
  running_kernel="$(uname -r)"
  build_dir="/lib/modules/${running_kernel}/build"
  [[ -e "${build_dir}/Makefile" ]]
}

ensure_running_kernel_devel() {
  local running_kernel
  running_kernel="$(uname -r)"
  if running_kernel_devel_ready; then
    return 0
  fi

  echo "[fedora-setup] WARN: kernel-devel for the running kernel is not available: ${running_kernel}" >&2
  echo "[fedora-setup] Missing build tree: /lib/modules/${running_kernel}/build" >&2
  dnf_install "kernel-devel-${running_kernel}" || true
  if running_kernel_devel_ready; then
    return 0
  fi

  echo "[fedora-setup] ERROR: cannot build EVDI for running kernel ${running_kernel}." >&2
  echo "[fedora-setup] Installed kernels/kernel-devel:" >&2
  rpm -q kernel-core kernel-devel 2>/dev/null | sed 's/^/[fedora-setup]   /' >&2 || true
  echo "[fedora-setup] Fix: reboot into a kernel that has matching kernel-devel installed, then rerun:" >&2
  echo "[fedora-setup]   sudo reboot" >&2
  echo "[fedora-setup]   ./redeploy-local" >&2
  echo "[fedora-setup] Or update kernel packages together before rebooting:" >&2
  echo "[fedora-setup]   sudo dnf upgrade --refresh kernel kernel-core kernel-modules kernel-devel" >&2
  return 1
}

latest_evdi_source_version() {
  local dir version best=""
  for dir in /usr/src/evdi-*; do
    [[ -d "$dir" ]] || continue
    version="${dir##*/evdi-}"
    if [[ -z "$best" ]] || [[ "$(printf '%s\n%s\n' "$best" "$version" | sort -V | tail -n 1)" == "$version" ]]; then
      best="$version"
    fi
  done
  [[ -n "$best" ]] && echo "$best"
}

ensure_displaylink_libevdi_ldconfig() {
  if [[ ! -e /usr/libexec/displaylink/libevdi.so ]]; then
    return 0
  fi
  if ldconfig -p 2>/dev/null | grep -q 'libevdi\.so'; then
    return 0
  fi

  echo "[fedora-setup] Registering /usr/libexec/displaylink for libevdi runtime/linker lookup"
  if [[ "${EUID:-$(id -u)}" -eq 0 ]]; then
    run_shell "printf '%s\n' /usr/libexec/displaylink > /etc/ld.so.conf.d/displaylink-evdi.conf"
    run_cmd ldconfig
  else
    run_shell "printf '%s\n' /usr/libexec/displaylink | sudo tee /etc/ld.so.conf.d/displaylink-evdi.conf >/dev/null"
    with_sudo ldconfig
  fi
}

build_evdi_for_running_kernel() {
  local version running_kernel
  running_kernel="$(uname -r)"
  if [[ "$DRY_RUN" -eq 1 ]]; then
    echo "[fedora-setup] DRY-RUN: verify matching kernel-devel for ${running_kernel}"
    echo "[fedora-setup] DRY-RUN: sudo dkms build/install evdi for ${running_kernel}"
    return 0
  fi
  if command_exists modinfo && modinfo evdi >/dev/null 2>&1; then
    return 0
  fi

  ensure_running_kernel_devel
  version="$(latest_evdi_source_version || true)"
  if [[ -z "$version" ]]; then
    echo "[fedora-setup] ERROR: EVDI source not found under /usr/src/evdi-*." >&2
    return 1
  fi

  echo "[fedora-setup] Building EVDI ${version} for running kernel ${running_kernel}"
  with_sudo dkms build -m evdi -v "$version" -k "$running_kernel"
  with_sudo dkms install -m evdi -v "$version" -k "$running_kernel"
}

install_evdi_packages() {
  if [[ "$DRY_RUN" -eq 1 ]]; then
    dnf_install akmod-evdi
    echo "[fedora-setup] DRY-RUN: if akmod-evdi is unavailable, try evdi-dkms"
    dnf_install evdi-dkms
    echo "[fedora-setup] DRY-RUN: if evdi-dkms is unavailable, try displaylink-rpm GitHub Release RPM"
    install_evdi_from_github_release
    return 0
  fi

  if dnf_install akmod-evdi; then
    return 0
  fi

  echo "[fedora-setup] akmod-evdi install failed; trying evdi-dkms"
  if dnf_install evdi-dkms; then
    return 0
  fi

  echo "[fedora-setup] evdi-dkms install failed; trying displaylink-rpm GitHub Release RPM"
  install_evdi_from_github_release
}

if ! detect_fedora; then
  echo "[fedora-setup] ERROR: this script is intended for Fedora." >&2
  echo "[fedora-setup] Detected /etc/os-release:" >&2
  sed -n '1,8p' /etc/os-release >&2 2>/dev/null || true
  exit 2
fi

if ! command_exists dnf; then
  echo "[fedora-setup] ERROR: dnf is required." >&2
  exit 2
fi

BASE_PACKAGES=(
  git curl wget file pkgconf-pkg-config
  unzip
  gcc gcc-c++ make cmake clang openssl-devel
  rust cargo
  nodejs npm
  python3
  java-21-openjdk-devel
  android-tools
  mokutil
  glib2-devel
  gstreamer1-devel
  gstreamer1-plugins-base-devel
  gstreamer1-plugins-good
  gstreamer1-plugins-bad-free
  gstreamer1-plugin-openh264
  gstreamer1-vaapi
  webkit2gtk4.1-devel
  libappindicator-gtk3-devel
  librsvg2-devel
  libxdo-devel
  xrandr
  xorg-x11-server-Xvfb
  akmods
  dkms
  kernel-devel
  kernel-headers
)

echo "[fedora-setup] Installing Fedora dependencies for WBeam"

if [[ "$INSTALL_GROUP" -eq 1 ]]; then
  dnf_group_install "c-development"
fi

dnf_install "${BASE_PACKAGES[@]}"

verify_gstreamer_encoders

if [[ "$WITH_ANDROID_SDK" -eq 1 ]]; then
  ensure_android_sdk
else
  echo "[fedora-setup] Android SDK bootstrap skipped (--no-android-sdk)."
fi

if [[ "$WITH_EVDI" -eq 1 ]]; then
  dnf_install akmods dkms kernel-devel kernel-headers

  if [[ "$ENABLE_EVDI_COPR" -eq 1 ]]; then
    enable_evdi_copr || true
  fi

  install_evdi_packages
  ensure_displaylink_libevdi_ldconfig

  if ! groups "${SUDO_USER:-$(whoami)}" | grep -qw video; then
    with_sudo usermod -a -G video "${SUDO_USER:-$(whoami)}" || true
    if [[ "$DRY_RUN" -eq 1 ]]; then
      echo "[fedora-setup] Would add ${SUDO_USER:-$(whoami)} to video group."
    else
      echo "[fedora-setup] Added ${SUDO_USER:-$(whoami)} to video group. Log out and back in for this to apply."
    fi
  fi

  build_evdi_for_running_kernel

  if [[ "$LOAD_EVDI" -eq 1 ]]; then
    with_sudo modprobe evdi initial_device_count=4 || {
      echo "[fedora-setup] WARN: evdi module did not load." >&2
      echo "[fedora-setup] Check Secure Boot, dkms/akmods status, and kernel-devel matching uname -r." >&2
      if command_exists mokutil && mokutil --sb-state 2>/dev/null | grep -qi enabled; then
        echo "[fedora-setup] Secure Boot is enabled; unsigned EVDI kernel modules may be blocked until MOK signing is configured or Secure Boot is disabled." >&2
        echo "[fedora-setup] If DKMS generated a MOK key, enroll it and reboot:" >&2
        echo "[fedora-setup]   sudo mokutil --import /var/lib/dkms/mok.pub" >&2
        echo "[fedora-setup]   sudo reboot" >&2
        echo "[fedora-setup]   sudo dkms autoinstall" >&2
      fi
    }
  fi
else
  echo "[fedora-setup] EVDI package install skipped. Use --with-evdi or --enable-evdi-copr to try it."
fi

print_post_check
