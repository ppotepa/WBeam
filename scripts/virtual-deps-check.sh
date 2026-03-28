#!/usr/bin/env bash
set -euo pipefail

JSON=0
if [[ "${1:-}" == "--json" ]]; then
  JSON=1
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
if ! command_exists xrandr; then
  missing+=("xrandr")
fi
if ! command_exists Xvfb; then
  missing+=("Xvfb")
fi
if ! command_exists modinfo || ! modinfo evdi >/dev/null 2>&1; then
  missing+=("evdi")
fi
if command_exists modinfo && modinfo evdi >/dev/null 2>&1; then
  if ! command_exists lsmod || ! lsmod | grep -q '^evdi '; then
    missing+=("evdi-module-loaded")
  fi
fi

# For real X11 virtual-monitor mode we also need an EVDI-backed xrandr provider
# visible in the current GUI session.
if command_exists xrandr && command_exists modinfo && modinfo evdi >/dev/null 2>&1; then
  if [[ -n "${DISPLAY:-}" ]]; then
    providers_out="$(xrandr --listproviders 2>/dev/null || true)"
    if [[ -z "$providers_out" ]]; then
      missing+=("x11-display-access")
    elif ! printf '%s\n' "$providers_out" | grep -Eqi 'evdi|displaylink'; then
      missing+=("evdi-provider")
    fi
  fi
fi

install_cmd=""
case "$manager" in
  deb) install_cmd="sudo apt-get update && sudo apt-get install -y dkms linux-headers-generic evdi-dkms xvfb x11-xserver-utils && sudo modprobe evdi initial_device_count=4" ;;
  rpm-dnf) install_cmd="sudo dnf install -y xorg-x11-server-Xvfb xrandr dkms kernel-devel akmod-evdi || sudo dnf install -y xorg-x11-server-Xvfb xrandr dkms kernel-devel evdi-dkms; sudo modprobe evdi initial_device_count=4" ;;
  rpm-yum) install_cmd="sudo yum install -y xorg-x11-server-Xvfb xrandr dkms kernel-devel evdi-dkms && sudo modprobe evdi initial_device_count=4" ;;
  rpm-zypper) install_cmd="sudo zypper install -y xorg-x11-server-Xvfb xrandr dkms kernel-default-devel evdi-dkms && sudo modprobe evdi initial_device_count=4" ;;
  arch) install_cmd="sudo pacman -S --noconfirm xorg-server-xvfb xorg-xrandr dkms linux-headers && $(arch_evdi_install_hint) && sudo modprobe evdi initial_device_count=4" ;;
  *) install_cmd="install package manager dependencies manually: evdi (kernel module), Xvfb, xrandr; then load module: sudo modprobe evdi initial_device_count=4" ;;
esac

ok=true
if [[ "${#missing[@]}" -gt 0 ]]; then
  ok=false
fi

if [[ "$JSON" -eq 1 ]]; then
  print_json "$ok" "linux" "$manager" "$install_cmd" "${missing[@]}"
else
  echo "[virtual-deps] platform=linux"
  echo "[virtual-deps] manager=$manager"
  if [[ "$ok" == true ]]; then
    echo "[virtual-deps] status=ok"
    echo "[virtual-deps] all required dependencies present (evdi, Xvfb, xrandr, evdi-provider)"
  else
    echo "[virtual-deps] status=missing"
    echo "[virtual-deps] missing=${missing[*]}"
    echo "[virtual-deps] install_cmd=$install_cmd"
  fi
fi
