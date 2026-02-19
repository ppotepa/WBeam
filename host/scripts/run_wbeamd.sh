#!/usr/bin/env bash
set -euo pipefail

CONTROL_PORT="${1:-5001}"
STREAM_PORT="${2:-5000}"
DAEMON_IMPL="${WBEAM_DAEMON_IMPL:-auto}" # auto|rust|python

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Control plane + media plane over USB tunnel.
"$SCRIPT_DIR/usb_reverse.sh" "$STREAM_PORT"
adb reverse "tcp:${CONTROL_PORT}" "tcp:${CONTROL_PORT}"

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
