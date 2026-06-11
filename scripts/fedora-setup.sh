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
  --with-evdi           also try to install akmod-evdi or evdi-dkms
  --enable-evdi-copr    enable displaylink-rpm/displaylink COPR before EVDI install
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

print_post_check() {
  echo
  echo "[fedora-setup] Post-install checks:"
  echo "  command -v cargo node npm java adb pkg-config"
  echo "  pkg-config --exists glib-2.0 gobject-2.0 gstreamer-1.0"
  echo "  ./wbeam deps virtual check"
  echo "  ./wbeam host build"
  echo "  ./wbeam android build"
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
  java-21-openjdk-devel
  android-tools
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
  dkms
  kernel-devel
  kernel-headers
)

echo "[fedora-setup] Installing Fedora dependencies for WBeam"

if [[ "$INSTALL_GROUP" -eq 1 ]]; then
  dnf_group_install "c-development"
fi

dnf_install "${BASE_PACKAGES[@]}"

if [[ "$WITH_ANDROID_SDK" -eq 1 ]]; then
  ensure_android_sdk
else
  echo "[fedora-setup] Android SDK bootstrap skipped (--no-android-sdk)."
fi

if [[ "$WITH_EVDI" -eq 1 ]]; then
  if [[ "$ENABLE_EVDI_COPR" -eq 1 ]]; then
    copr_args=()
    mapfile -t copr_args < <(dnf_args)
    dnf_install dnf-plugins-core || true
    with_sudo dnf copr enable "${copr_args[@]}" displaylink-rpm/displaylink
  fi

  if ! dnf_install akmod-evdi; then
    echo "[fedora-setup] akmod-evdi install failed; trying evdi-dkms"
    dnf_install evdi-dkms
  fi

  if [[ "$LOAD_EVDI" -eq 1 ]]; then
    with_sudo modprobe evdi initial_device_count=4 || {
      echo "[fedora-setup] WARN: evdi module did not load." >&2
      echo "[fedora-setup] Check Secure Boot, dkms/akmods status, and kernel-devel matching uname -r." >&2
    }
  fi
else
  echo "[fedora-setup] EVDI package install skipped. Use --with-evdi or --enable-evdi-copr to try it."
fi

print_post_check
