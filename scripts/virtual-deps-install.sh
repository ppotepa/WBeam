#!/usr/bin/env bash
set -euo pipefail

DRY_RUN=0
YES=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --yes|-y) YES=1; shift ;;
    *) echo "[virtual-deps] unknown arg: $1" >&2; exit 2 ;;
  esac
done

command_exists() {
  command -v "$1" >/dev/null 2>&1
}

detect_os() {
  local u
  u="$(uname -s 2>/dev/null | tr '[:upper:]' '[:lower:]')"
  case "$u" in
    linux*) echo "linux" ;;
    msys*|mingw*|cygwin*) echo "windows" ;;
    *) echo "unknown" ;;
  esac
}

detect_linux_manager() {
  if command_exists apt-get; then
    echo "deb"
    return 0
  fi
  if command_exists dnf; then
    echo "rpm-dnf"
    return 0
  fi
  if command_exists yum; then
    echo "rpm-yum"
    return 0
  fi
  if command_exists zypper; then
    echo "rpm-zypper"
    return 0
  fi
  if command_exists pacman; then
    echo "arch"
    return 0
  fi
  echo "unknown"
}

run_cmd() {
  if [[ "$DRY_RUN" -eq 1 ]]; then
    echo "[virtual-deps] DRY-RUN: $*"
    return 0
  fi
  echo "[virtual-deps] RUN: $*"
  "$@"
}

with_sudo() {
  if [[ "${EUID:-$(id -u)}" -eq 0 ]]; then
    run_cmd "$@"
  else
    run_cmd sudo "$@"
  fi
}

platform="$(detect_os)"
if [[ "$platform" == "windows" ]]; then
  echo "[virtual-deps] platform=windows"
  echo "[virtual-deps] installer path not implemented yet (driver strategy pending)"
  exit 2
fi
if [[ "$platform" != "linux" ]]; then
  echo "[virtual-deps] unsupported platform: $platform"
  exit 2
fi

manager="$(detect_linux_manager)"
if [[ "$manager" == "unknown" ]]; then
  echo "[virtual-deps] unsupported Linux package manager"
  exit 2
fi

if [[ "$YES" -ne 1 ]]; then
  echo "[virtual-deps] This will install dependencies for virtual desktop mode."
  echo "[virtual-deps] Continue? [y/N]"
  read -r answer
  if [[ ! "$answer" =~ ^[Yy]$ ]]; then
    echo "[virtual-deps] aborted by user"
    exit 1
  fi
fi

case "$manager" in
  deb)
    with_sudo apt-get update
    with_sudo apt-get install -y xvfb x11-xserver-utils
    ;;
  rpm-dnf)
    with_sudo dnf install -y xorg-x11-server-Xvfb xrandr
    ;;
  rpm-yum)
    with_sudo yum install -y xorg-x11-server-Xvfb xrandr
    ;;
  rpm-zypper)
    with_sudo zypper install -y xorg-x11-server-Xvfb xrandr
    ;;
  arch)
    with_sudo pacman -S --noconfirm xorg-server-xvfb xorg-xrandr
    ;;
esac

echo "[virtual-deps] install done"

