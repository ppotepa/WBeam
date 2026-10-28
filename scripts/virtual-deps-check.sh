#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=evdi-compat.sh
source "$SCRIPT_DIR/evdi-compat.sh"

JSON=0
BACKEND="evdi"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --json) JSON=1; shift ;;
    --backend)
      [[ $# -ge 2 ]] || { echo "[virtual-deps] --backend requires a value" >&2; exit 2; }
      BACKEND="$2"; shift 2 ;;
    *) echo "[virtual-deps] unknown argument: $1" >&2; exit 2 ;;
  esac
done
if [[ "$BACKEND" != "evdi" && "$BACKEND" != "wayland_portal" ]]; then
  echo "[virtual-deps] supported backends: evdi, wayland_portal" >&2
  exit 2
fi

command_exists() {
  command -v "$1" >/dev/null 2>&1
}

arch_evdi_install_hint() {
  if command_exists pacman && pacman -Si evdi-dkms >/dev/null 2>&1; then
    echo "sudo pacman -S --noconfirm evdi-dkms"
    return 0
  fi
  if command_exists yay; then
    echo "yay -S --noconfirm evdi-dkms"
    return 0
  fi
  if command_exists paru; then
    echo "paru -S --noconfirm evdi-dkms"
    return 0
  fi
  echo "install AUR helper (yay/paru) and run: yay -S evdi-dkms"
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

json_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

print_json() {
  local ok="$1"
  local platform="$2"
  local manager="$3"
  local install_cmd="$4"
  shift 4
  local missing=("$@")
  local missing_json=""
  local i
  for i in "${!missing[@]}"; do
    local item
    item="$(json_escape "${missing[$i]}")"
    if [[ "$i" -gt 0 ]]; then
      missing_json+=","
    fi
    missing_json+="\"$item\""
  done
  cat <<EOF
{"ok":$ok,"platform":"$(json_escape "$platform")","manager":"$(json_escape "$manager")","missing":[${missing_json}],"install_cmd":"$(json_escape "$install_cmd")"}
EOF
}

platform="$(detect_os)"

if [[ "$platform" == "windows" ]]; then
  msg="windows virtual display dependency installer is not implemented yet"
  if [[ "$JSON" -eq 1 ]]; then
    print_json false "windows" "none" "$msg"
  else
    echo "[virtual-deps] platform=windows"
    echo "[virtual-deps] status=not-implemented"
    echo "[virtual-deps] $msg"
  fi
  exit 2
fi

if [[ "$platform" != "linux" ]]; then
  msg="unsupported host platform: $platform"
  if [[ "$JSON" -eq 1 ]]; then
    print_json false "$platform" "unknown" "$msg"
  else
    echo "[virtual-deps] platform=$platform"
    echo "[virtual-deps] status=unsupported"
    echo "[virtual-deps] $msg"
  fi
  exit 2
fi

manager="$(detect_linux_manager)"
missing=()
install_cmd=""

if [[ "$BACKEND" == "evdi" ]]; then
  # Kernel DRM EVDI capture does NOT require Xvfb or xrandr.
  # Check the actual running kernel instead of a hardcoded headers package.
  kernel="$(uname -r)"
  if [[ ! -e "/lib/modules/${kernel}/build/Makefile" ]]; then
    missing+=("kernel-headers-for-${kernel}")
  fi
  evdi_module_found=0
  if ! command_exists modinfo || ! modinfo evdi >/dev/null 2>&1; then
    missing+=("evdi-kernel-module")
  else
    evdi_module_found=1
    if ! command_exists lsmod || ! lsmod | grep -q '^evdi '; then
      missing+=("evdi-module-loaded")
      if command_exists mokutil && mokutil --sb-state 2>/dev/null | grep -qi enabled; then
        # A Secure Boot / MOK check is required; it is not automatically the cause.
        missing+=("evdi-secureboot-mok-check")
      fi
    fi
  fi
  if ! evdi_find_linkable_library >/dev/null; then
    missing+=("libevdi-linker-library")
  fi
  # Classify the latest DKMS log only when no module was found. A stale
  # historical log must not block a successfully installed EVDI module.
  if [[ "$evdi_module_found" -eq 0 ]]; then
    log_file="$(evdi_find_build_log "$kernel" || true)"
    if [[ -n "$log_file" ]]; then
      log_reason="$(evdi_build_failure_reason "$log_file")"
      if [[ "$log_reason" != "dkms-failure-unclassified" ]]; then
        missing+=("$log_reason")
      fi
    fi
  fi
  case "$manager" in
    deb) install_cmd="Install matching Linux kernel headers and evdi-dkms; then load: sudo modprobe evdi initial_device_count=4" ;;
    rpm-dnf) install_cmd="Install matching kernel-devel and a compatible EVDI DKMS/akmod version; verify libevdi.so and Secure Boot MOK. If DKMS reports sign-compare failures on kernel ${kernel}, use a compatible upstream EVDI revision or Wayland Portal; do not disable compiler warnings globally." ;;
    rpm-yum|rpm-zypper) install_cmd="Install matching kernel-devel and evdi-dkms, then load EVDI for this kernel" ;;
    arch) install_cmd="$(arch_evdi_install_hint) ; install headers for ${kernel}; sudo modprobe evdi initial_device_count=4" ;;
    *) install_cmd="Install EVDI and matching kernel headers manually, then load the evdi module" ;;
  esac
else
  # Portal is a user-session service with no X11, Xvfb or EVDI requirement.
  if ! command_exists pipewire; then
    missing+=("pipewire")
  fi
  if ! command_exists xdg-desktop-portal && [[ ! -x /usr/libexec/xdg-desktop-portal && ! -x /usr/lib/xdg-desktop-portal ]]; then
    missing+=("xdg-desktop-portal")
  fi
  install_cmd="Install PipeWire and the XDG desktop portal for your compositor, then log back into the Wayland session"
fi

ok=true
if [[ "${#missing[@]}" -gt 0 ]]; then
  ok=false
fi

if [[ "$JSON" -eq 1 ]]; then
  print_json "$ok" "linux" "$manager" "$install_cmd" "${missing[@]}"
else
  echo "[virtual-deps] platform=linux"
  echo "[virtual-deps] manager=$manager"
  echo "[virtual-deps] backend=$BACKEND"
  if [[ "$ok" == true ]]; then
    echo "[virtual-deps] status=ok"
    echo "[virtual-deps] all required dependencies present for $BACKEND"
  else
    echo "[virtual-deps] status=missing"
    echo "[virtual-deps] missing=${missing[*]}"
    echo "[virtual-deps] install_cmd=$install_cmd"
  fi
fi
