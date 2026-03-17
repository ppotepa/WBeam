#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [[ -f "$SCRIPT_DIR/../../wbeam" ]]; then
  ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
elif [[ -f "$SCRIPT_DIR/../../../wbeam" ]]; then
  ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
else
  ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
fi
WBEAM_CONFIG_HELPER="$ROOT_DIR/host/scripts/wbeam_config.sh"
if [[ -f "$WBEAM_CONFIG_HELPER" ]]; then
  # shellcheck source=host/scripts/wbeam_config.sh
  source "$WBEAM_CONFIG_HELPER"
  wbeam_load_config "$ROOT_DIR"
fi

CONTROL_PORT="${1:-${WBEAM_CONTROL_PORT:-5001}}"
STREAM_PORT="${2:-${WBEAM_STREAM_PORT:-5000}}"
DAEMON_IMPL="${WBEAM_DAEMON_IMPL:-auto}" # auto|rust
ANDROID_SERIAL="${WBEAM_ANDROID_SERIAL:-}"
DEFAULT_LOCK_FILE=""
if [[ -n "${WBEAM_LOCK_FILE:-}" ]]; then
  DEFAULT_LOCK_FILE="$WBEAM_LOCK_FILE"
elif [[ -n "${INVOCATION_ID:-}" ]]; then
  # User systemd runs should not collide with ad-hoc/debug daemon instances.
  DEFAULT_LOCK_FILE="/tmp/wbeamd-service-${CONTROL_PORT}.lock"
fi

resolve_display() {
  if [[ -n "${DISPLAY:-}" ]]; then
    printf '%s\n' "$DISPLAY"
    return 0
  fi
  if [[ -d /tmp/.X11-unix ]]; then
    local sock
    sock="$(ls -1 /tmp/.X11-unix/X* 2>/dev/null | sed 's|.*/X||' | sort -n | tail -1 || true)"
    if [[ -n "$sock" ]]; then
      printf ':%s\n' "$sock"
      return 0
    fi
  fi
  printf '\n'
}

list_xauth_candidates() {
  local uid="${UID:-$(id -u 2>/dev/null || echo 1000)}"
  local f owner
  if [[ -f "${XAUTHORITY:-}" ]]; then
    printf '%s\n' "$XAUTHORITY"
  fi
  for f in /tmp/xauth_*; do
    [[ -f "$f" ]] || continue
    owner="$(stat -c '%u' "$f" 2>/dev/null || true)"
    [[ "$owner" == "$uid" ]] || continue
    printf '%s\n' "$f"
  done
  local run_dir="/run/user/${uid}"
  if [[ -d "$run_dir" ]]; then
    for f in "$run_dir"/xauth_*; do
      [[ -f "$f" ]] || continue
      owner="$(stat -c '%u' "$f" 2>/dev/null || true)"
      [[ "$owner" == "$uid" ]] || continue
      printf '%s\n' "$f"
    done
  fi
  if [[ -f "${HOME:-}/.Xauthority" ]]; then
    printf '%s\n' "${HOME}/.Xauthority"
  fi
}

probe_x11_access() {
  local display="$1"
  local xauth="$2"
  if ! command -v xrandr >/dev/null 2>&1; then
    return 0
  fi
  if [[ -z "$display" ]]; then
    return 1
  fi
  if [[ -n "$xauth" ]]; then
    env DISPLAY="$display" XAUTHORITY="$xauth" xrandr --listproviders >/dev/null 2>&1
    return $?
  fi
  env DISPLAY="$display" xrandr --listproviders >/dev/null 2>&1
  return $?
}

prepare_x11_env() {
  local display xauth cand
  display="$(resolve_display)"
  xauth=""
  while IFS= read -r cand; do
    [[ -n "$cand" && -f "$cand" ]] || continue
    xauth="$cand"
    break
  done < <(list_xauth_candidates)

  if [[ -n "$display" ]]; then
    export DISPLAY="$display"
  fi
  if [[ -n "$xauth" ]]; then
    export XAUTHORITY="$xauth"
  fi

  if ! probe_x11_access "${DISPLAY:-}" "${XAUTHORITY:-}"; then
    while IFS= read -r cand; do
      [[ -n "$cand" && -f "$cand" ]] || continue
      if probe_x11_access "${DISPLAY:-}" "$cand"; then
        export XAUTHORITY="$cand"
        break
      fi
    done < <(list_xauth_candidates)
  fi

  echo "[wbeam] x11 env: DISPLAY=${DISPLAY:-<unset>} XAUTHORITY=${XAUTHORITY:-<unset>}" >&2
}

adb_device_cmd() {
  if [[ -n "$ANDROID_SERIAL" ]]; then
    adb -s "$ANDROID_SERIAL" "$@"
  else
    adb "$@"
  fi
}

adb_device_cmd_timeout() {
  local sec="${1:-3}"
  shift || true
  if command -v timeout >/dev/null 2>&1; then
    if [[ -n "$ANDROID_SERIAL" ]]; then
      timeout "$sec" adb -s "$ANDROID_SERIAL" "$@"
    else
      timeout "$sec" adb "$@"
    fi
  else
    adb_device_cmd "$@"
  fi
}

adb_best_effort_reverse() {
  local port="$1"
  local attempts=4
  local i

  adb_device_cmd_timeout 3 start-server >/dev/null 2>&1 || true
  for (( i=1; i<=attempts; i++ )); do
    if adb_device_cmd_timeout 3 reverse "tcp:${port}" "tcp:${port}" >/dev/null 2>&1; then
      return 0
    fi
    if (( i < attempts )); then
      sleep 0.3
      adb_device_cmd_timeout 3 kill-server >/dev/null 2>&1 || true
      adb_device_cmd_timeout 3 start-server >/dev/null 2>&1 || true
    fi
  done
  return 1
}

# Control plane + media plane over USB tunnel.
stream_reverse_ok=0
control_reverse_ok=0

if [[ "${WBEAM_DAEMON_PRESTART_REVERSE:-0}" == "1" ]]; then
  if command -v timeout >/dev/null 2>&1; then
    if timeout 10 "$SCRIPT_DIR/usb_reverse.sh" "$STREAM_PORT"; then
      stream_reverse_ok=1
    fi
  else
    if "$SCRIPT_DIR/usb_reverse.sh" "$STREAM_PORT"; then
      stream_reverse_ok=1
    fi
  fi
  if (( stream_reverse_ok == 1 )); then
    stream_reverse_ok=1
  else
    echo "[wbeam] warning: stream adb reverse setup failed (continuing)" >&2
    echo "[wbeam] info: this is expected on some old Android/adbd versions; daemon startup is not blocked" >&2
  fi

  if adb_best_effort_reverse "$CONTROL_PORT"; then
    control_reverse_ok=1
  else
    echo "[wbeam] warning: control adb reverse setup failed (continuing)" >&2
    echo "[wbeam] info: if reverse stays unavailable, use LAN host/IP fallback for API/stream" >&2
  fi
else
  echo "[wbeam] prestart adb reverse skipped (WBEAM_DAEMON_PRESTART_REVERSE=0)" >&2
fi

echo "[wbeam] transport summary: serial=${ANDROID_SERIAL:-default} stream_reverse=${stream_reverse_ok} control_reverse=${control_reverse_ok}" >&2

prepare_x11_env

run_rust() {
  local args=(
    --control-port "$CONTROL_PORT"
    --stream-port "$STREAM_PORT"
    --root "$ROOT_DIR"
  )
  local daemon_bin=""
  local build_rev="${WBEAM_BUILD_REV:-}"
  local build_rev_file="$ROOT_DIR/.wbeam_build_version"

  if [[ -n "$DEFAULT_LOCK_FILE" ]]; then
    args+=(--lock-file "$DEFAULT_LOCK_FILE")
  fi

  if [[ -n "${WBEAM_RUST_LOG_DIR:-}" ]]; then
    args+=(--log-dir "$WBEAM_RUST_LOG_DIR")
  fi

  if [[ -z "$build_rev" && -f "$build_rev_file" ]]; then
    build_rev="$(tr -d '\r[:space:]' < "$build_rev_file" 2>/dev/null || true)"
  fi

  if [[ -n "${WBEAM_DAEMON_BIN:-}" && -x "${WBEAM_DAEMON_BIN}" ]]; then
    daemon_bin="${WBEAM_DAEMON_BIN}"
  elif [[ -x "$ROOT_DIR/host/rust/target/release/wbeamd-server" ]]; then
    daemon_bin="$ROOT_DIR/host/rust/target/release/wbeamd-server"
  elif [[ -x "/usr/local/bin/wbeamd-server" ]]; then
    daemon_bin="/usr/local/bin/wbeamd-server"
  fi

  if [[ -n "$daemon_bin" ]]; then
    if [[ -n "$build_rev" ]]; then
      exec env WBEAM_BUILD_REV="$build_rev" "$daemon_bin" "${args[@]}"
    fi
    exec "$daemon_bin" "${args[@]}"
  fi

  if command -v cargo >/dev/null 2>&1 && [[ -f "$ROOT_DIR/host/rust/Cargo.toml" ]]; then
    if [[ -n "$build_rev" ]]; then
      exec env WBEAM_BUILD_REV="$build_rev" cargo run \
        --manifest-path "$ROOT_DIR/host/rust/Cargo.toml" \
        -p wbeamd-server -- \
        "${args[@]}"
    fi

    exec cargo run \
        --manifest-path "$ROOT_DIR/host/rust/Cargo.toml" \
        -p wbeamd-server -- \
        "${args[@]}"
  fi

  echo "[wbeam] rust daemon requested but no executable/cargo source available" >&2
  exit 1
}

if [[ "$DAEMON_IMPL" == "python" ]]; then
  echo "[wbeam] python daemon mode has been removed; use WBEAM_DAEMON_IMPL=rust or auto" >&2
  exit 1
fi

if [[ "$DAEMON_IMPL" == "rust" ]]; then
  if ! command -v cargo >/dev/null 2>&1; then
    echo "[wbeam] rust requested but cargo not found" >&2
    exit 1
  fi
  echo "[wbeam] daemon impl=rust"
  run_rust
fi

echo "[wbeam] daemon impl=auto -> rust"
run_rust
