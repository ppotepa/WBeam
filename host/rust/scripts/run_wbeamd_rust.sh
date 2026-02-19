#!/usr/bin/env bash
set -euo pipefail

CONTROL_PORT="${1:-5001}"
STREAM_PORT="${2:-5000}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"

"$ROOT_DIR/host/scripts/usb_reverse.sh" "$STREAM_PORT"
adb reverse "tcp:${CONTROL_PORT}" "tcp:${CONTROL_PORT}"

exec cargo run \
  --manifest-path "$ROOT_DIR/host/rust/Cargo.toml" \
  -p wbeamd-server -- \
  --control-port "$CONTROL_PORT" \
  --stream-port "$STREAM_PORT" \
  --root "$ROOT_DIR"
