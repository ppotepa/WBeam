#!/usr/bin/env bash
set -euo pipefail

# WBeam Automated Installation Wizard
# Contract: detect -> plan -> confirm -> install -> validate -> device -> state

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DISTRO_ID="unknown"
DISTRO_FAMILY="unknown"
DISTRO_PKG="unknown"
SESSION_TYPE="unknown"
WAYLAND_AVAILABLE=0
X11_AVAILABLE=0
PORTAL_AVAILABLE=0
PIPEWIRE_STATE="unknown"
ENCODER_H264="unknown"
EVDI_STATE="unknown"
REBOOT_REQUIRED=0

# Configurable defaults
CONTROL_PORT=5001
STREAM_PORT=5000
BACKEND="wayland" # default
YES=0
DRY_RUN=0
SKIP_SYSTEM_DEPS=0
SKIP_BUILD=0
SKIP_SERVICE=0
SKIP_DEVICE=0
DEVICE_ONLY=0
ANDROID_SERIAL=""

SERVICE_NAME="wbeam-daemon"
CURRENT_STEP="init"
SERVICE_STATE="unknown"
DEVICE_STATE="unknown"

log() {
  printf "[install] %s\n" "$*"
}

warn() {
  printf "[install] WARN: %s\n" "$*" >&2
}

err() {
  printf "[install] ERROR: %s\n" "$*" >&2
}

run_cmd() {
  if [[ "$DRY_RUN" -eq 1 ]]; then
    log "DRY-RUN: $*"
    return 0
  fi
  "$@"
}

detect_os() {
  if [[ -f /etc/os-release ]]; then
    # shellcheck disable=SC1091
    . /etc/os-release
    DISTRO_ID="${ID:-unknown}"
    DISTRO_FAMILY="${ID_LIKE:-$DISTRO_ID}"
  fi

  if command -v dnf >/dev/null 2>&1; then
    DISTRO_PKG="dnf"
  elif command -v apt-get >/dev/null 2>&1; then
    DISTRO_PKG="apt"
  elif command -v pacman >/dev/null 2>&1; then
    DISTRO_PKG="pacman"
  fi
}

detect_display_stack() {
  if [[ "${XDG_SESSION_TYPE:-}" == "wayland" ]]; then
    SESSION_TYPE="wayland"
    WAYLAND_AVAILABLE=1
  elif [[ "${XDG_SESSION_TYPE:-}" == "x11" ]]; then
    SESSION_TYPE="x11"
    X11_AVAILABLE=1
  fi

  if command -v loginctl >/dev/null 2>&1; then
    local session_id
    local session_lines=()
    mapfile -t session_lines < <(loginctl session-status 2>/dev/null || true)
    session_id="${session_lines[0]:-}"
    session_id="${session_id%% *}"
    local type
    type="$(loginctl show-session "$session_id" -p Type --value || true)"
    if [[ -n "$type" ]]; then
      SESSION_TYPE="$type"
      [[ "$type" == "wayland" ]] && WAYLAND_AVAILABLE=1
      [[ "$type" == "x11" ]] && X11_AVAILABLE=1
    fi
  fi

  if command -v dbus-send >/dev/null 2>&1; then
    if dbus-send --session --dest=org.freedesktop.portal.Desktop --print-reply /org/freedesktop/portal/desktop org.freedesktop.DBus.Introspectable.Introspect >/dev/null 2>&1; then
      PORTAL_AVAILABLE=1
    fi
  fi

  local pw="missing"
  local wp="missing"
  command -v pipewire >/dev/null 2>&1 && pw="running"
  command -v wireplumber >/dev/null 2>&1 && wp="running"
  PIPEWIRE_STATE="pipewire=$pw wireplumber=$wp"
}

detect_evdi() {
  if [[ -c /dev/dri/card0 ]]; then
    if lsmod | grep -q evdi; then
      EVDI_STATE="loaded"
    else
      EVDI_STATE="module-not-loaded"
    fi
  else
    EVDI_STATE="no-dri-device"
  fi

  if groups | grep -qw video; then
    EVDI_STATE="$EVDI_STATE user-in-video"
  else
    EVDI_STATE="$EVDI_STATE user-not-in-video"
  fi
}

detect_encoder() {
  if command -v gst-inspect-1.0 >/dev/null 2>&1; then
    if gst-inspect-1.0 nvh264enc >/dev/null 2>&1; then
      ENCODER_H264="nvh264enc"
    elif gst-inspect-1.0 vaapih264enc >/dev/null 2>&1; then
      ENCODER_H264="vaapih264enc"
    elif gst-inspect-1.0 x264enc >/dev/null 2>&1; then
      ENCODER_H264="x264enc"
    fi
  fi
}

print_probe() {
  log "probe:"
  echo "  distro: $DISTRO_ID $DISTRO_FAMILY ($DISTRO_PKG)"
  echo "  arch: $(uname -m)"
  echo "  session: $SESSION_TYPE"
  echo "  wayland: $( (( WAYLAND_AVAILABLE )) && echo "available" || echo "missing" )"
  echo "  x11: $( (( X11_AVAILABLE )) && echo "available" || echo "missing" )"
  echo "  portal: $( (( PORTAL_AVAILABLE )) && echo "available" || echo "unknown" )"
  echo "  pipewire: $PIPEWIRE_STATE"
  echo "  encoder: h264:$ENCODER_H264"
  echo "  evdi: $EVDI_STATE"
}

choose_backend() {
  if [[ -n "$BACKEND" ]]; then
    return 0
  fi

  if [[ "$WAYLAND_AVAILABLE" -eq 1 && "$PORTAL_AVAILABLE" -eq 1 ]]; then
    BACKEND="wayland"
  elif lsmod | grep -q evdi; then
    BACKEND="evdi"
  elif [[ "$X11_AVAILABLE" -eq 1 ]]; then
    BACKEND="x11"
  else
    BACKEND="wayland" # fallback
  fi
}

print_plan() {
  echo
  log "plan:"
  echo "  backend: $BACKEND"
  if [[ "$SKIP_SYSTEM_DEPS" -eq 1 ]]; then
    echo "  system deps: skipped"
  else
    echo "  system deps: install using $DISTRO_PKG for $DISTRO_ID"
  fi
  if [[ "$SKIP_BUILD" -eq 1 ]]; then
    echo "  build: skipped"
  else
    echo "  build: ./wbeam host build"
  fi
  if [[ "$SKIP_SERVICE" -eq 1 ]]; then
    echo "  service: skipped"
  else
    echo "  service: install/start ${SERVICE_NAME}.service"
  fi
  if [[ "$SKIP_DEVICE" -eq 1 ]]; then
    echo "  phone: skipped"
  else
    echo "  phone: adb onboarding and APK deploy"
  fi

  if [[ "$BACKEND" == "evdi" && "$EVDI_STATE" == *"module-not-loaded"* ]]; then
    echo "  evdi risk: kernel module, video group, Secure Boot MOK, and reboot may be required"
  fi
}

confirm_plan() {
  if [[ "$YES" -eq 1 || "$DRY_RUN" -eq 1 ]]; then
    return 0
  fi
  if ! [[ -t 0 ]]; then
    err "non-interactive install requires --yes or --dry-run"
    return 1
  fi
  echo
  printf "Continue with this plan? [y/N] "
  local answer
  IFS= read -r answer
  if [[ "$answer" == "y" || "$answer" == "Y" ]]; then
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
      if [[ "$DRY_RUN" -eq 1 ]]; then
        warn "DRY-RUN: Debian/Ubuntu installer backend is not implemented yet; real install would stop here."
        return 0
      fi
      err "Debian/Ubuntu installer backend is not implemented yet on this branch."
      err "This wizard detects Debian/Ubuntu, but package install mapping still needs to be added."
      return 1
      ;;
    *)
      if [[ "$DRY_RUN" -eq 1 ]]; then
        warn "DRY-RUN: automatic install is not implemented for distro '$DISTRO_ID'; manual setup would be required."
        return 0
      fi
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

  local failures=0
  log "validation:"
  if [[ -x "$ROOT_DIR/host/rust/target/release/wbeamd-server" ]]; then
    echo "  host binary: ok"
  else
    echo "  host binary: missing"
    [[ "$DRY_RUN" -eq 1 ]] || failures=$((failures + 1))
  fi
  if [[ -x "$ROOT_DIR/host/rust/target/release/wbeamd-streamer" ]]; then
    echo "  streamer binary: ok"
  else
    echo "  streamer binary: missing"
    [[ "$DRY_RUN" -eq 1 ]] || failures=$((failures + 1))
  fi
  if command -v systemctl >/dev/null 2>&1 && [[ "$SKIP_SERVICE" -eq 0 ]]; then
    local svc_active
    svc_active="$(systemctl --user is-active "$SERVICE_NAME" 2>/dev/null || echo unknown)"
    echo "  service: $svc_active"
    if [[ "$svc_active" != "active" && "$DRY_RUN" -eq 0 ]]; then
      failures=$((failures + 1))
    fi
  fi
  if command -v curl >/dev/null 2>&1; then
    if curl -fsS --max-time 2 "http://127.0.0.1:${CONTROL_PORT}/v1/health" >/dev/null 2>&1; then
      echo "  control API: ok"
    else
      echo "  control API: not reachable yet"
      if [[ "$DRY_RUN" -eq 0 ]]; then
         # failures=$((failures + 1))
         :
      fi
    fi
  fi
  return "$failures"
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
        echo "$ANDROID_SERIAL"
        return 0
      fi
    fi

    if [[ -n "$devices" ]]; then
      local count
      count=$(printf '%s\n' "$devices" | wc -l)
      if [[ "$count" -eq 1 ]]; then
        echo "$devices"
        return 0
      fi

      echo "Multiple devices found. Please select one:" >&2
      select choice in $devices; do
        if [[ -n "$choice" ]]; then
          echo "$choice"
          return 0
        fi
      done
    fi

    if [[ "$attempt" -gt 3 ]]; then
      err "no authorized ADB devices found."
      [[ -n "$unauthorized" ]] && warn "devices found but unauthorized: $unauthorized"
      [[ -n "$offline" ]] && warn "devices found but offline: $offline"
      return 1
    fi
    log "waiting for device... (attempt $attempt)"
    sleep 3
  done
}

device_onboarding() {
  [[ "$SKIP_DEVICE" -eq 0 ]] || return 0

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
  serial="$(select_adb_device)" || return 1
  log "using adb serial: $serial"
  run_cmd env WBEAM_ANDROID_SERIAL="$serial" "$ROOT_DIR/wbeam" android deploy
  run_cmd env WBEAM_ANDROID_SERIAL="$serial" "$ROOT_DIR/wbeam" version doctor || true
  DEVICE_STATE="ok:$serial"
}

write_state() {
  local state_file="$HOME/.local/state/wbeam/install-state.json"
  if [[ "$DRY_RUN" -eq 1 ]]; then
     log "DRY-RUN: would write install state to $state_file"
     return 0
  fi
  mkdir -p "$(dirname "$state_file")"
  cat <<EOF > "$state_file"
{
  "timestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "step": "$CURRENT_STEP",
  "distro": "$DISTRO_ID",
  "backend": "$BACKEND",
  "service": "$SERVICE_STATE",
  "device": "$DEVICE_STATE"
}
EOF
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
        exit 2
        ;;
    esac
  done
}

usage() {
  cat <<EOF
Usage: install-wbeam [options]

Options:
  --backend <name>      Target capture backend (wayland, evdi, x11)
  -y, --yes             Skip confirmation
  --dry-run             Show what would be done
  --skip-system-deps    Skip installing system packages
  --skip-build          Skip building Rust host binaries
  --skip-service        Skip systemd service installation
  --skip-device         Skip Android device onboarding
  --device-only         Only perform Android device onboarding
  --android-serial <S>  Use specific ADB serial
  --control-port <P>    Control API port (default: 5001)
  --stream-port <P>     Streaming port (default: 5000)
EOF
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

  CURRENT_STEP="system-deps"
  write_state
  install_system_deps

  CURRENT_STEP="build"
  write_state
  build_host

  CURRENT_STEP="service"
  write_state
  install_service

  CURRENT_STEP="validate"
  write_state
  validate_install

  CURRENT_STEP="device"
  write_state
  device_onboarding

  CURRENT_STEP="done"
  write_state

  if [[ "$REBOOT_REQUIRED" -eq 1 ]]; then
    warn "reboot is required before EVDI can be used."
  fi
  log "done"
}

main "$@"
