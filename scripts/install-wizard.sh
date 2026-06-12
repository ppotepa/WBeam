#!/usr/bin/env bash
set -euo pipefail

SCRIPT_PATH="$(readlink -f "${BASH_SOURCE[0]}")"
ROOT_DIR="$(cd "$(dirname "$SCRIPT_PATH")/.." && pwd)"

BACKEND="${WBEAM_INSTALL_BACKEND:-auto}"
YES=0
DRY_RUN=0
SKIP_SYSTEM_DEPS=0
SKIP_BUILD=0
SKIP_SERVICE=0
SKIP_DEVICE=0
DEVICE_ONLY=0
ANDROID_SERIAL="${WBEAM_ANDROID_SERIAL:-}"
CONTROL_PORT="${WBEAM_CONTROL_PORT:-5001}"
STREAM_PORT="${WBEAM_STREAM_PORT:-5000}"
SERVICE_NAME="${WBEAM_DAEMON_SERVICE_NAME:-wbeam-daemon}"
STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/wbeam"
STATE_FILE="$STATE_DIR/install-state.json"

DISTRO_ID="unknown"
DISTRO_LIKE=""
DISTRO_VERSION="unknown"
PKG_MANAGER="unknown"
ARCH="$(uname -m)"
SESSION_TYPE="${XDG_SESSION_TYPE:-unknown}"
WAYLAND_STATE="missing"
X11_STATE="missing"
PORTAL_STATE="unknown"
PIPEWIRE_STATE="unknown"
EVDI_STATE="unknown"
ENCODER_STATE="unknown"
SERVICE_STATE="skipped"
DEVICE_STATE="pending"
REBOOT_REQUIRED=0
SELECTED_ANDROID_SERIAL=""

usage() {
  cat <<'USAGE'
Usage:
  ./install-wbeam [options]
  ./wbeam install [options]
  ./wbeam device setup [options]

Options:
  --backend wayland     use compositor capture (Wayland portal or X11 fallback)
  --backend evdi        use EVDI virtual display (advanced/risky)
  --backend auto        ask interactively, defaulting to wayland
  -y, --yes             accept the generated plan
  --dry-run             print the plan and commands without changing the system
  --skip-system-deps    skip distro package/bootstrap install
  --skip-build          skip local host build
  --skip-service        skip systemd user service install/start
  --skip-device         skip Android phone onboarding
  --device-only         only run Android phone onboarding
  --android-serial S    target a specific adb serial
  --control-port P      daemon control port (default: 5001)
  --stream-port P       stream port (default: 5000)
  -h, --help            show this help

Notes:
  Wayland mode is the recommended default and also covers X11 fallback.
  EVDI mode installs kernel/displaylink components and may require Secure Boot
  MOK enrollment, group membership refresh, and a reboot.
USAGE
}

log() {
  echo "[install] $*"
}

warn() {
  echo "[install] WARN: $*" >&2
}

err() {
  echo "[install] ERROR: $*" >&2
}

is_true() {
  [[ "${1:-}" =~ ^(1|true|TRUE|yes|YES|on|ON)$ ]]
}

is_interactive() {
  [[ -t 0 && -t 1 ]]
}

run_cmd() {
  if [[ "$DRY_RUN" -eq 1 ]]; then
    printf '[install] DRY-RUN:'
    printf ' %q' "$@"
    printf '\n'
    return 0
  fi
  printf '[install] RUN:'
  printf ' %q' "$@"
  printf '\n'
  "$@"
}

json_escape() {
  local s="${1:-}"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  s="${s//$'\n'/\\n}"
  printf '%s' "$s"
}

write_state() {
  if [[ "$DRY_RUN" -eq 1 ]]; then
    log "DRY-RUN: would write install state to $STATE_FILE"
    return 0
  fi

  mkdir -p "$STATE_DIR"
  cat > "$STATE_FILE" <<EOF
{
  "backend": "$(json_escape "$BACKEND")",
  "distro": "$(json_escape "$DISTRO_ID")",
  "distro_version": "$(json_escape "$DISTRO_VERSION")",
  "arch": "$(json_escape "$ARCH")",
  "service": "$(json_escape "$SERVICE_STATE")",
  "device": "$(json_escape "$DEVICE_STATE")",
  "evdi": "$(json_escape "$EVDI_STATE")",
  "reboot_required": $REBOOT_REQUIRED,
  "updated_at": "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
}
EOF
  log "state written: $STATE_FILE"
}

detect_os() {
  if [[ -f /etc/os-release ]]; then
    # shellcheck disable=SC1091
    . /etc/os-release
    DISTRO_ID="${ID:-unknown}"
    DISTRO_LIKE="${ID_LIKE:-}"
    DISTRO_VERSION="${VERSION_ID:-unknown}"
  fi

  if command -v dnf >/dev/null 2>&1; then
    PKG_MANAGER="dnf"
  elif command -v apt-get >/dev/null 2>&1; then
    PKG_MANAGER="apt"
  elif command -v pacman >/dev/null 2>&1; then
    PKG_MANAGER="pacman"
  fi
}

has_wayland_socket() {
  local runtime_dir="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
  compgen -G "$runtime_dir/wayland-*" >/dev/null 2>&1
}

has_x11_socket() {
  compgen -G "/tmp/.X11-unix/X*" >/dev/null 2>&1
}

detect_display_stack() {
  if [[ -n "${WAYLAND_DISPLAY:-}" ]] || has_wayland_socket; then
    WAYLAND_STATE="available"
  fi
  if [[ -n "${DISPLAY:-}" ]] || has_x11_socket; then
    X11_STATE="available"
  fi

  if command -v systemctl >/dev/null 2>&1; then
    local portal pipewire wireplumber
    portal="$(systemctl --user is-active xdg-desktop-portal.service 2>/dev/null || true)"
    pipewire="$(systemctl --user is-active pipewire.service 2>/dev/null || true)"
    wireplumber="$(systemctl --user is-active wireplumber.service 2>/dev/null || true)"
    PORTAL_STATE="${portal:-unknown}"
    PIPEWIRE_STATE="pipewire=${pipewire:-unknown} wireplumber=${wireplumber:-unknown}"
  else
    PORTAL_STATE="systemctl-missing"
    PIPEWIRE_STATE="systemctl-missing"
  fi
}

libevdi_present() {
  if ldconfig -p 2>/dev/null | grep -q 'libevdi\.so'; then
    return 0
  fi
  if compgen -G "/usr/lib*/libevdi.so*" >/dev/null 2>&1; then
    return 0
  fi
  if compgen -G "/usr/libexec/displaylink/libevdi.so*" >/dev/null 2>&1; then
    return 0
  fi
  return 1
}

secure_boot_enabled() {
  command -v mokutil >/dev/null 2>&1 && mokutil --sb-state 2>/dev/null | grep -qi enabled
}

dkms_mok_not_enrolled() {
  local out
  [[ -f /var/lib/dkms/mok.pub ]] || return 1
  secure_boot_enabled || return 1
  out="$(mokutil --test-key /var/lib/dkms/mok.pub 2>&1 || true)"
  printf '%s\n' "$out" | grep -qi 'not enrolled'
}

evdi_pending_marker() {
  echo "$ROOT_DIR/.cache/evdi-mok-import-pending"
}

evdi_mok_pending_reboot() {
  local marker current_sha
  marker="$(evdi_pending_marker)"
  [[ -f /var/lib/dkms/mok.pub && -f "$marker" ]] || return 1
  command -v sha256sum >/dev/null 2>&1 || return 1
  current_sha="$(sha256sum /var/lib/dkms/mok.pub | awk '{print $1}')"
  grep -qx "sha256=${current_sha}" "$marker"
}

detect_evdi() {
  local issues=()
  if ! libevdi_present; then
    issues+=("libevdi-missing")
  fi
  if ! command -v modinfo >/dev/null 2>&1 || ! modinfo evdi >/dev/null 2>&1; then
    issues+=("module-unavailable")
  elif ! grep -q '^evdi ' /proc/modules 2>/dev/null; then
    issues+=("module-not-loaded")
  fi
  if ! id -nG | tr ' ' '\n' | grep -qx video; then
    issues+=("user-not-in-video")
  fi
  if evdi_mok_pending_reboot; then
    issues+=("secureboot-mok-pending-reboot")
    REBOOT_REQUIRED=1
  elif dkms_mok_not_enrolled; then
    issues+=("secureboot-mok-not-enrolled")
  fi

  if [[ "${#issues[@]}" -eq 0 ]]; then
    EVDI_STATE="ok"
  else
    EVDI_STATE="${issues[*]}"
  fi
}

detect_encoder() {
  local element
  if ! command -v gst-inspect-1.0 >/dev/null 2>&1; then
    ENCODER_STATE="gst-inspect-missing"
    return 0
  fi

  for element in nvh264enc x264enc openh264enc; do
    if gst-inspect-1.0 "$element" >/dev/null 2>&1; then
      ENCODER_STATE="h264:$element"
      return 0
    fi
  done
  ENCODER_STATE="missing-h264"
}

print_probe() {
  log "probe:"
  echo "  distro: ${DISTRO_ID} ${DISTRO_VERSION} (${PKG_MANAGER})"
  echo "  arch: ${ARCH}"
  echo "  session: ${SESSION_TYPE}"
  echo "  wayland: ${WAYLAND_STATE}"
  echo "  x11: ${X11_STATE}"
  echo "  portal: ${PORTAL_STATE}"
  echo "  pipewire: ${PIPEWIRE_STATE}"
  echo "  encoder: ${ENCODER_STATE}"
  echo "  evdi: ${EVDI_STATE}"
}

normalize_backend() {
  case "$1" in
    wayland|x11|portal|wayland-portal|wayland_portal|fallback)
      echo "wayland"
      ;;
    evdi)
      echo "evdi"
      ;;
    auto|"")
      echo "auto"
      ;;
    *)
      err "unknown backend: $1"
      usage >&2
      exit 2
      ;;
  esac
}

choose_backend() {
  BACKEND="$(normalize_backend "$BACKEND")"
  if [[ "$BACKEND" != "auto" ]]; then
    return 0
  fi

  if ! is_interactive || [[ "$YES" -eq 1 ]]; then
    BACKEND="wayland"
    log "backend auto -> wayland (recommended default)"
    return 0
  fi

  echo
  echo "Select capture backend:"
  echo "  1) Wayland/X11 fallback - recommended"
  echo "  2) EVDI virtual display - advanced/risky; may require DKMS, MOK enrollment, reboot"
  printf "Choice [1]: "
  local choice
  IFS= read -r choice
  case "${choice:-1}" in
    2) BACKEND="evdi" ;;
    *) BACKEND="wayland" ;;
  esac
}

print_plan() {
  echo
  log "plan:"
  echo "  backend: $BACKEND"
  if [[ "$DEVICE_ONLY" -eq 1 ]]; then
    echo "  mode: Android device onboarding only"
    return 0
  fi
  if [[ "$SKIP_SYSTEM_DEPS" -eq 0 ]]; then
    case "$DISTRO_ID" in
      fedora)
        if [[ "$BACKEND" == "evdi" ]]; then
          echo "  system deps: scripts/fedora-setup.sh --yes --with-evdi"
        else
          echo "  system deps: scripts/fedora-setup.sh --yes"
        fi
        ;;
      debian|ubuntu)
        echo "  system deps: Debian/Ubuntu installer not implemented yet"
        ;;
      *)
        echo "  system deps: unsupported distro, manual deps required"
        ;;
    esac
  else
    echo "  system deps: skipped"
  fi
  [[ "$SKIP_BUILD" -eq 0 ]] && echo "  build: ./wbeam host build" || echo "  build: skipped"
  [[ "$SKIP_SERVICE" -eq 0 ]] && echo "  service: install/start ${SERVICE_NAME}.service" || echo "  service: skipped"
  [[ "$SKIP_DEVICE" -eq 0 ]] && echo "  phone: adb onboarding and APK deploy" || echo "  phone: skipped"
  if [[ "$BACKEND" == "evdi" ]]; then
    echo "  evdi risk: kernel module, video group, Secure Boot MOK, and reboot may be required"
  fi
}

confirm_plan() {
  if [[ "$YES" -eq 1 || "$DRY_RUN" -eq 1 ]]; then
    return 0
  fi
  if ! is_interactive; then
    err "non-interactive install requires --yes or --dry-run"
    return 1
  fi
  echo
  printf "Continue with this plan? [y/N] "
  local answer
  IFS= read -r answer
  if is_true "$answer" || [[ "$answer" == "y" || "$answer" == "Y" ]]; then
    YES=1
    return 0
  fi
  err "cancelled"
  return 1
}

install_system_deps() {
  [[ "$SKIP_SYSTEM_DEPS" -eq 0 ]] || return 0
  [[ "$DEVICE_ONLY" -eq 0 ]] || return 0

  case "$DISTRO_ID" in
    fedora)
      local args=(--yes)
      [[ "$DRY_RUN" -eq 1 ]] && args+=(--dry-run)
      [[ "$BACKEND" == "evdi" ]] && args+=(--with-evdi)
      run_cmd "$ROOT_DIR/scripts/fedora-setup.sh" "${args[@]}"
      ;;
    debian|ubuntu)
      err "Debian/Ubuntu installer backend is not implemented yet on this branch."
      err "This wizard detects Debian/Ubuntu, but package install mapping still needs to be added."
      return 1
      ;;
    *)
      err "automatic install is not implemented for distro '$DISTRO_ID'."
      return 1
      ;;
  esac
}

build_host() {
  [[ "$SKIP_BUILD" -eq 0 ]] || return 0
  [[ "$DEVICE_ONLY" -eq 0 ]] || return 0
  run_cmd "$ROOT_DIR/wbeam" host build
}

service_unit_content() {
  cat <<EOF
[Unit]
Description=WBeam Screen Streaming Daemon
After=graphical-session.target

[Service]
Type=simple
ExecStart=$ROOT_DIR/host/scripts/run_wbeamd.sh $CONTROL_PORT $STREAM_PORT
Restart=on-failure
RestartSec=3
Environment=RUST_LOG=info
Environment=WBEAM_ROOT=$ROOT_DIR
Environment=WBEAM_LOCK_FILE=/tmp/wbeamd-service-${CONTROL_PORT}.lock
EOF
  if [[ "$BACKEND" == "evdi" ]]; then
    echo "Environment=WBEAM_CAPTURE_BACKEND=evdi"
  fi
  echo
  cat <<'EOF'
[Install]
WantedBy=default.target
EOF
}

install_service() {
  [[ "$SKIP_SERVICE" -eq 0 ]] || return 0
  [[ "$DEVICE_ONLY" -eq 0 ]] || return 0

  if ! command -v systemctl >/dev/null 2>&1; then
    warn "systemctl missing; service install skipped"
    SERVICE_STATE="systemctl-missing"
    return 0
  fi

  local unit_dir="$HOME/.config/systemd/user"
  local unit_path="$unit_dir/${SERVICE_NAME}.service"

  if [[ "$DRY_RUN" -eq 1 ]]; then
    log "DRY-RUN: would write $unit_path"
    service_unit_content | sed 's/^/[install]   /'
    log "DRY-RUN: systemctl --user daemon-reload"
    log "DRY-RUN: systemctl --user enable --now ${SERVICE_NAME}.service"
    SERVICE_STATE="dry-run"
    return 0
  fi

  mkdir -p "$unit_dir"
  service_unit_content > "$unit_path"
  run_cmd systemctl --user daemon-reload
  run_cmd systemctl --user enable --now "${SERVICE_NAME}.service"
  SERVICE_STATE="$(systemctl --user is-active "$SERVICE_NAME" 2>/dev/null || true)"
}

validate_install() {
  [[ "$DEVICE_ONLY" -eq 0 ]] || return 0

  log "validation:"
  if [[ -x "$ROOT_DIR/host/rust/target/release/wbeamd-server" ]]; then
    echo "  host binary: ok"
  else
    echo "  host binary: missing"
  fi
  if [[ -x "$ROOT_DIR/host/rust/target/release/wbeamd-streamer" ]]; then
    echo "  streamer binary: ok"
  else
    echo "  streamer binary: missing"
  fi
  if command -v systemctl >/dev/null 2>&1 && [[ "$SKIP_SERVICE" -eq 0 ]]; then
    echo "  service: $(systemctl --user is-active "$SERVICE_NAME" 2>/dev/null || echo unknown)"
  fi
  if command -v curl >/dev/null 2>&1; then
    if curl -fsS --max-time 2 "http://127.0.0.1:${CONTROL_PORT}/v1/health" >/dev/null 2>&1; then
      echo "  control API: ok"
    else
      echo "  control API: not reachable yet"
    fi
  fi
}

adb_rows() {
  adb devices | awk 'NR > 1 && NF >= 2 { print $1 " " $2 }'
}

select_adb_device() {
  local attempt=0
  local rows devices unauthorized offline serial status selected choice
  while true; do
    attempt=$((attempt + 1))
    adb start-server >/dev/null 2>&1 || true
    rows="$(adb_rows || true)"
    devices="$(printf '%s\n' "$rows" | awk '$2 == "device" { print $1 }')"
    unauthorized="$(printf '%s\n' "$rows" | awk '$2 == "unauthorized" { print $1 }')"
    offline="$(printf '%s\n' "$rows" | awk '$2 == "offline" { print $1 }')"

    if [[ -n "$ANDROID_SERIAL" ]]; then
      status="$(printf '%s\n' "$rows" | awk -v s="$ANDROID_SERIAL" '$1 == s { print $2; exit }')"
      if [[ "$status" == "device" ]]; then
        SELECTED_ANDROID_SERIAL="$ANDROID_SERIAL"
        return 0
      fi
      warn "serial $ANDROID_SERIAL is ${status:-not-detected}"
    elif [[ "$(printf '%s\n' "$devices" | sed '/^$/d' | wc -l | tr -d ' ')" -eq 1 ]]; then
      SELECTED_ANDROID_SERIAL="$(printf '%s\n' "$devices" | sed '/^$/d' | head -n 1)"
      return 0
    elif [[ -n "$devices" ]]; then
      if ! is_interactive; then
        err "multiple adb devices detected; rerun with --android-serial"
        return 2
      fi
      echo
      echo "Connected devices:"
      local i=0
      while IFS= read -r serial; do
        [[ -n "$serial" ]] || continue
        i=$((i + 1))
        echo "  $i) $serial"
      done <<< "$devices"
      printf "Choose device [1]: "
      IFS= read -r choice
      choice="${choice:-1}"
      if [[ ! "$choice" =~ ^[0-9]+$ ]]; then
        selected=""
      else
        selected="$(printf '%s\n' "$devices" | sed '/^$/d' | sed -n "${choice}p")"
      fi
      if [[ -n "$selected" ]]; then
        SELECTED_ANDROID_SERIAL="$selected"
        return 0
      fi
    fi

    echo
    warn "No authorized Android device is ready."
    if [[ -n "$unauthorized" ]]; then
      warn "unauthorized: $(echo "$unauthorized" | tr '\n' ' ')"
      echo "  Unlock the phone and accept the USB debugging RSA prompt."
    fi
    if [[ -n "$offline" ]]; then
      warn "offline: $(echo "$offline" | tr '\n' ' ')"
      echo "  Replug USB or run: adb kill-server; adb start-server"
    fi
    if [[ -z "$rows" ]]; then
      echo "  Connect the phone over USB, enable Developer options and USB debugging."
      echo "  On Xiaomi/HyperOS/MIUI also enable Developer options -> Install via USB."
    fi

    if ! is_interactive; then
      return 2
    fi
    printf "Press Enter to retry, or type 'skip' to finish host install without phone: "
    IFS= read -r choice
    if [[ "$choice" == "skip" ]]; then
      return 2
    fi

    if (( attempt % 3 == 0 )); then
      adb kill-server >/dev/null 2>&1 || true
      adb start-server >/dev/null 2>&1 || true
    fi
  done
}

device_onboarding() {
  [[ "$SKIP_DEVICE" -eq 0 ]] || {
    DEVICE_STATE="skipped"
    return 0
  }

  if ! command -v adb >/dev/null 2>&1; then
    warn "adb missing; phone onboarding pending"
    DEVICE_STATE="adb-missing"
    return 0
  fi

  echo
  log "phone onboarding:"
  echo "  1. Connect the phone over USB."
  echo "  2. Enable Developer options and USB debugging."
  echo "  3. Accept the USB debugging RSA prompt on the phone."
  echo "  4. If install is blocked, enable Install via USB in Developer options."

  if [[ "$DRY_RUN" -eq 1 ]]; then
    log "DRY-RUN: would run adb start-server and wait for an authorized device"
    log "DRY-RUN: would run ./wbeam android deploy for the selected serial"
    DEVICE_STATE="dry-run"
    return 0
  fi

  local serial
  if ! select_adb_device; then
    DEVICE_STATE="pending"
    warn "phone onboarding left pending; rerun: ./wbeam device setup"
    return 0
  fi

  serial="$SELECTED_ANDROID_SERIAL"
  log "using adb serial: $serial"
  run_cmd env WBEAM_ANDROID_SERIAL="$serial" "$ROOT_DIR/wbeam" android deploy
  run_cmd env WBEAM_ANDROID_SERIAL="$serial" "$ROOT_DIR/wbeam" version doctor || true
  DEVICE_STATE="ok:$serial"
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --backend)
        BACKEND="${2:-}"
        shift 2
        ;;
      --backend=*)
        BACKEND="${1#*=}"
        shift
        ;;
      -y|--yes)
        YES=1
        shift
        ;;
      --dry-run)
        DRY_RUN=1
        shift
        ;;
      --skip-system-deps)
        SKIP_SYSTEM_DEPS=1
        shift
        ;;
      --skip-build)
        SKIP_BUILD=1
        shift
        ;;
      --skip-service)
        SKIP_SERVICE=1
        shift
        ;;
      --skip-device)
        SKIP_DEVICE=1
        shift
        ;;
      --device-only)
        DEVICE_ONLY=1
        SKIP_SYSTEM_DEPS=1
        SKIP_BUILD=1
        SKIP_SERVICE=1
        shift
        ;;
      --android-serial)
        ANDROID_SERIAL="${2:-}"
        shift 2
        ;;
      --android-serial=*)
        ANDROID_SERIAL="${1#*=}"
        shift
        ;;
      --control-port)
        CONTROL_PORT="${2:-}"
        shift 2
        ;;
      --control-port=*)
        CONTROL_PORT="${1#*=}"
        shift
        ;;
      --stream-port)
        STREAM_PORT="${2:-}"
        shift 2
        ;;
      --stream-port=*)
        STREAM_PORT="${1#*=}"
        shift
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        err "unknown arg: $1"
        usage >&2
        exit 2
        ;;
    esac
  done
}

main() {
  parse_args "$@"
  cd "$ROOT_DIR"
  detect_os
  detect_display_stack
  detect_evdi
  detect_encoder
  print_probe
  choose_backend
  print_plan
  confirm_plan

  install_system_deps
  build_host
  install_service
  validate_install
  device_onboarding
  write_state

  if [[ "$REBOOT_REQUIRED" -eq 1 ]]; then
    warn "reboot is required before EVDI can be used."
  fi
  log "done"
}

main "$@"
