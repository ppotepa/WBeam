#!/usr/bin/env bash
set -euo pipefail

PROFILE="balanced"

if [[ "${1:-}" =~ ^(lowlatency|balanced|ultra)$ ]]; then
  PROFILE="$1"
  shift
fi

PORT="${1:-5000}"
DEBUG_DIR="${2:-/tmp/wbeam-frames}"
DEBUG_FPS="${3:-0}"
ENCODER="${4:-auto}"
CURSOR_MODE="${5:-hidden}"
SIZE_OVERRIDE="${6:-}"
FPS_OVERRIDE="${7:-}"
BITRATE_OVERRIDE="${8:-}"
FORCE_PORT_CLEAR="${WBEAM_FORCE_PORT_CLEAR:-0}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

if command -v lsof >/dev/null 2>&1; then
  LISTEN_PIDS="$(lsof -t -iTCP:"${PORT}" -sTCP:LISTEN 2>/dev/null | tr '\n' ' ' || true)"
  if [[ -n "${LISTEN_PIDS// }" ]]; then
    if [[ "$FORCE_PORT_CLEAR" == "1" ]]; then
      echo "Port ${PORT} busy; killing listener PIDs: ${LISTEN_PIDS}"
      kill ${LISTEN_PIDS} 2>/dev/null || true
      sleep 0.2
    else
      echo "Port ${PORT} is already in use by PID(s): ${LISTEN_PIDS}" >&2
      echo "Run: lsof -nP -iTCP:${PORT} -sTCP:LISTEN" >&2
      echo "Then kill them, or rerun with: WBEAM_FORCE_PORT_CLEAR=1 $0 ${PROFILE} ${PORT} ${DEBUG_DIR} ${DEBUG_FPS} ${ENCODER} ${CURSOR_MODE}" >&2
      exit 1
    fi
  fi
fi

"$SCRIPT_DIR/usb_reverse.sh" "$PORT"
CMD=(
  "$SCRIPT_DIR/stream_wayland_portal_h264.py"
  --profile "$PROFILE"
  --port "$PORT"
  --encoder "$ENCODER"
  --cursor-mode "$CURSOR_MODE"
  --debug-dir "$DEBUG_DIR"
  --debug-fps "$DEBUG_FPS"
)

if [[ -n "$SIZE_OVERRIDE" ]]; then
  CMD+=(--size "$SIZE_OVERRIDE")
fi
if [[ -n "$FPS_OVERRIDE" ]]; then
  CMD+=(--fps "$FPS_OVERRIDE")
fi
if [[ -n "$BITRATE_OVERRIDE" ]]; then
  CMD+=(--bitrate-kbps "$BITRATE_OVERRIDE")
fi

"${CMD[@]}"
