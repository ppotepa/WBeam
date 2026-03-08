#!/usr/bin/env bash
set -euo pipefail

JSON=0
if [[ "${1:-}" == "--json" ]]; then
  JSON=1
fi

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
if ! command_exists Xvfb; then
  missing+=("Xvfb")
fi
if ! command_exists xrandr; then
  missing+=("xrandr")
fi

install_cmd=""
case "$manager" in
  deb) install_cmd="sudo apt-get update && sudo apt-get install -y xvfb x11-xserver-utils" ;;
  rpm-dnf) install_cmd="sudo dnf install -y xorg-x11-server-Xvfb xrandr" ;;
  rpm-yum) install_cmd="sudo yum install -y xorg-x11-server-Xvfb xrandr" ;;
  rpm-zypper) install_cmd="sudo zypper install -y xorg-x11-server-Xvfb xrandr" ;;
  arch) install_cmd="sudo pacman -S --noconfirm xorg-server-xvfb xorg-xrandr" ;;
  *) install_cmd="install package manager dependencies manually: Xvfb, xrandr" ;;
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
    echo "[virtual-deps] all required dependencies present (Xvfb, xrandr)"
  else
    echo "[virtual-deps] status=missing"
    echo "[virtual-deps] missing=${missing[*]}"
    echo "[virtual-deps] install_cmd=$install_cmd"
  fi
fi

