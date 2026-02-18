#!/usr/bin/env bash
set -euo pipefail

VIDEO_FILE="${1:-}"
PORT="${2:-5000}"
SIZE="${3:-1280x720}"
FPS="${4:-30}"

if [[ -z "$VIDEO_FILE" ]]; then
  echo "Usage: $0 <VIDEO_FILE> [PORT] [SIZE] [FPS]" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
"$SCRIPT_DIR/usb_reverse.sh" "$PORT"
"$SCRIPT_DIR/stream_file_h264.sh" "$VIDEO_FILE" "$PORT" "$SIZE" "$FPS"
