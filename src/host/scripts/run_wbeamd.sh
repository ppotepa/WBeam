#!/usr/bin/env bash
set -euo pipefail

CONTROL_PORT="${1:-5001}"
STREAM_PORT="${2:-5000}"
DAEMON_IMPL="${WBEAM_DAEMON_IMPL:-auto}" # auto|rust|python
ANDROID_SERIAL="${WBEAM_ANDROID_SERIAL:-}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DEFAULT_LOCK_FILE=""
if [[ -n "${WBEAM_LOCK_FILE:-}" ]]; then
  DEFAULT_LOCK_FILE="$WBEAM_LOCK_FILE"
elif [[ -n "${INVOCATION_ID:-}" ]]; then
  # User systemd runs should not collide with ad-hoc/debug daemon instances.
  DEFAULT_LOCK_FILE="/tmp/wbeamd-service-${CONTROL_PORT}.lock"
fi

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

run_rust() {
  local args=(
    --control-port "$CONTROL_PORT"
    --stream-port "$STREAM_PORT"
    --root "$ROOT_DIR"
  )
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

  if [[ -n "$build_rev" ]]; then
    exec env WBEAM_BUILD_REV="$build_rev" cargo run \
      --manifest-path "$ROOT_DIR/src/host/rust/Cargo.toml" \
      -p wbeamd-server -- \
      "${args[@]}"
  fi

  exec cargo run \
      --manifest-path "$ROOT_DIR/src/host/rust/Cargo.toml" \
      -p wbeamd-server -- \
      "${args[@]}"
}

run_python() {
  exec "$ROOT_DIR/src/host/daemon/wbeamd.py" --control-port "$CONTROL_PORT" --stream-port "$STREAM_PORT" --root "$ROOT_DIR"
}

if [[ "$DAEMON_IMPL" == "python" ]]; then
  echo "[wbeam] daemon impl=python"
  run_python
fi

if [[ "$DAEMON_IMPL" == "rust" ]]; then
  if ! command -v cargo >/dev/null 2>&1; then
    echo "[wbeam] rust requested but cargo not found" >&2
    exit 1
  fi
  echo "[wbeam] daemon impl=rust"
  run_rust
fi

# auto mode: prefer Rust, fallback to Python.
if command -v cargo >/dev/null 2>&1 && [[ -f "$ROOT_DIR/src/host/rust/Cargo.toml" ]]; then
  echo "[wbeam] daemon impl=auto -> rust"
  run_rust
fi

echo "[wbeam] daemon impl=auto -> python (fallback)"
run_python
