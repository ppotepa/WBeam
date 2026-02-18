#!/usr/bin/env bash
set -euo pipefail

PORT="${1:-5000}"
SIZE="${2:-2560x1440}"
FPS="${3:-60}"
BITRATE_KBPS="${4:-30000}"
DEBUG_DIR="${5:-/tmp/wbeam-frames}"
DEBUG_FPS="${6:-0}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

"$SCRIPT_DIR/usb_reverse.sh" "$PORT"
"$SCRIPT_DIR/stream_wayland_portal_h264.py" \
  --port "$PORT" \
  --size "$SIZE" \
  --fps "$FPS" \
  --bitrate-kbps "$BITRATE_KBPS" \
  --debug-dir "$DEBUG_DIR" \
  --debug-fps "$DEBUG_FPS"
