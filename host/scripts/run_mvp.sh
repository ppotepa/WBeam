#!/usr/bin/env bash
set -euo pipefail

PORT="${1:-5000}"
SIZE="${2:-1280x720}"
FPS="${3:-30}"

"$(dirname "$0")/usb_reverse.sh" "${PORT}"
"$(dirname "$0")/stream_x11_h264.sh" "${PORT}" "${SIZE}" "${FPS}"
