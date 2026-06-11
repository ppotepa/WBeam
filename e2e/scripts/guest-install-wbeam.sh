#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${WBEAM_E2E_GUEST_ROOT:-$(pwd)}"
BACKEND="${1:-${WBEAM_E2E_BACKEND:-benchmark_game}}"
SKIP_SYSTEM_DEPS="${WBEAM_E2E_SKIP_SYSTEM_DEPS:-0}"

log() {
  echo "[e2e-guest-install] $*"
}

run_sudo() {
  if [[ "${EUID:-$(id -u)}" -eq 0 ]]; then
    "$@"
  else
    sudo "$@"
  fi
}

detect_distro() {
  if [[ -f /etc/os-release ]]; then
    # shellcheck disable=SC1091
    . /etc/os-release
    echo "${ID:-unknown}"
  else
    echo "unknown"
  fi
}

install_apt_deps() {
  export DEBIAN_FRONTEND=noninteractive
  run_sudo apt-get update
  run_sudo apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    git \
    jq \
    pkg-config \
    build-essential \
    python3 \
    libssl-dev \
    libglib2.0-dev \
    libgstreamer1.0-dev \
    libgstreamer-plugins-base1.0-dev \
    gstreamer1.0-tools \
    gstreamer1.0-plugins-base \
    gstreamer1.0-plugins-good \
    gstreamer1.0-plugins-bad \
    gstreamer1.0-libav \
    gstreamer1.0-x \
    libx11-dev \
    libxrandr-dev \
    libxfixes-dev \
    libxext-dev \
    libxrender-dev \
    xvfb \
    x11-xserver-utils \
    dbus-user-session \
    pipewire \
    wireplumber \
    xdg-desktop-portal \
    xdg-desktop-portal-gnome

  if [[ "$BACKEND" == "evdi" ]]; then
    bash "$ROOT_DIR/scripts/virtual-deps-install.sh" --yes
  fi
}

ensure_rustup_toolchain() {
  export PATH="$HOME/.cargo/bin:$PATH"
  if ! command -v rustup >/dev/null 2>&1; then
    curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain stable
    export PATH="$HOME/.cargo/bin:$PATH"
  fi
  rustup default stable
  cargo --version
}

install_fedora_deps() {
  local args=(--yes --no-android-sdk)
  if [[ "$BACKEND" == "evdi" ]]; then
    args+=(--with-evdi)
  fi
  "$ROOT_DIR/scripts/fedora-setup.sh" "${args[@]}"
}

main() {
  cd "$ROOT_DIR"
  distro="$(detect_distro)"
  log "root=$ROOT_DIR"
  log "distro=$distro backend=$BACKEND"

  if [[ "$SKIP_SYSTEM_DEPS" != "1" ]]; then
    case "$distro" in
      fedora)
        install_fedora_deps
        ;;
      ubuntu|debian)
        install_apt_deps
        ensure_rustup_toolchain
        ;;
      *)
        echo "[e2e-guest-install] unsupported distro: $distro" >&2
        exit 2
        ;;
    esac
  fi

  ./wbeam host build
  ./wbeam version current
  log "done"
}

main "$@"
