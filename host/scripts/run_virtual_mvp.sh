#!/usr/bin/env bash
set -euo pipefail

PORT="${1:-5000}"
SIZE="${2:-1280x720}"
FPS="${3:-30}"
RATE="${4:-60}"
OUTPUT="${5:-}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
STATE_FILE="/tmp/wbeam_virtual_monitor.env"

if [[ -z "$OUTPUT" && -f "$STATE_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$STATE_FILE"
  OUTPUT="${OUTPUT:-}"
fi

if [[ -z "$OUTPUT" ]]; then
  OUTPUT="$(xrandr --query | awk '$2=="disconnected" {print $1; exit}')"
fi

if [[ -z "$OUTPUT" ]]; then
  echo "No disconnected output found. Pass output explicitly as 5th arg (e.g. HDMI-1)." >&2
  exit 1
fi

"$SCRIPT_DIR/virtual_monitor_x11.sh" up "$OUTPUT" "$SIZE" "$RATE"
"$SCRIPT_DIR/usb_reverse.sh" "$PORT"
"$SCRIPT_DIR/stream_output_x11_h264.sh" "$OUTPUT" "$PORT" "$FPS"
