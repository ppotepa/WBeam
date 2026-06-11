#!/usr/bin/env bash
set -euo pipefail

DRY_RUN=0
YES=0
WITH_EVDI=0
ENABLE_EVDI_COPR=0
LOAD_EVDI=1
INSTALL_GROUP=1

usage() {
  cat <<'USAGE'
Usage:
  scripts/fedora-setup.sh [options]

Installs Fedora packages needed for local WBeam development:
  - Rust host build
  - Tauri desktop build
  - GStreamer streamer build/runtime
  - ADB and virtual-display helper tools

Options:
  --dry-run             print commands without running them
  -y, --yes             pass -y to dnf commands
  --with-evdi           also try to install akmod-evdi or evdi-dkms
  --enable-evdi-copr    enable displaylink-rpm/displaylink COPR before EVDI install
  --no-evdi-load        do not run modprobe evdi after EVDI install
  --no-group            skip "c-development" group install
  -h, --help            show this help

Notes:
  This script installs Fedora RPM dependencies. It does not install Android
  Studio or create an Android SDK. After it completes, install Android SDK
  Platform 35 and Build-Tools 35.0.0 if they are not already present.
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
  echo
  echo "[fedora-setup] Android SDK still required for APK builds:"
  echo "  SDK Platform 35"
  echo "  SDK Build-Tools 35.0.0"
  echo "  Platform-Tools"
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
  gcc gcc-c++ make cmake clang openssl-devel
  rust cargo
  nodejs npm
  java-17-openjdk-devel
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
