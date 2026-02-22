#!/usr/bin/env bash
set -euo pipefail

CONTROL_PORT="${1:-5001}"
STREAM_PORT="${2:-5000}"
DAEMON_IMPL="${WBEAM_DAEMON_IMPL:-auto}" # auto|rust|python
ANDROID_SERIAL="${WBEAM_ANDROID_SERIAL:-}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

adb_device_cmd() {
  if [[ -n "$ANDROID_SERIAL" ]]; then
    adb -s "$ANDROID_SERIAL" "$@"
  else
    adb "$@"
  fi
}

adb_best_effort_reverse() {
  local port="$1"
  local attempts=4
  local i

  adb start-server >/dev/null 2>&1 || true
  for (( i=1; i<=attempts; i++ )); do
    if adb_device_cmd reverse "tcp:${port}" "tcp:${port}" >/dev/null 2>&1; then
      return 0
    fi
    if (( i < attempts )); then
      sleep 0.3
      adb kill-server >/dev/null 2>&1 || true
      adb start-server >/dev/null 2>&1 || true
    fi
  done
  return 1
}

# Control plane + media plane over USB tunnel.
stream_reverse_ok=0
control_reverse_ok=0

if "$SCRIPT_DIR/usb_reverse.sh" "$STREAM_PORT"; then
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

echo "[wbeam] transport summary: serial=${ANDROID_SERIAL:-default} stream_reverse=${stream_reverse_ok} control_reverse=${control_reverse_ok}" >&2

run_rust() {
  local args=(
    --control-port "$CONTROL_PORT"
    --stream-port "$STREAM_PORT"
    --root "$ROOT_DIR"
  )

  if [[ -n "${WBEAM_LOCK_FILE:-}" ]]; then
    args+=(--lock-file "$WBEAM_LOCK_FILE")
  fi

   if [[ -n "${WBEAM_RUST_LOG_DIR:-}" ]]; then
    args+=(--log-dir "$WBEAM_RUST_LOG_DIR")
  fi

  exec cargo run \
    --manifest-path "$ROOT_DIR/host/rust/Cargo.toml" \
    -p wbeamd-server -- \
    "${args[@]}"
}

run_python() {
  exec "$ROOT_DIR/host/daemon/wbeamd.py" --control-port "$CONTROL_PORT" --stream-port "$STREAM_PORT" --root "$ROOT_DIR"
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
if command -v cargo >/dev/null 2>&1 && [[ -f "$ROOT_DIR/host/rust/Cargo.toml" ]]; then
  echo "[wbeam] daemon impl=auto -> rust"
  run_rust
fi

echo "[wbeam] daemon impl=auto -> python (fallback)"
run_python
