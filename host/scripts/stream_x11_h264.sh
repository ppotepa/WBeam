#!/usr/bin/env bash
set -euo pipefail

PORT="${1:-5000}"
SIZE="${2:-1280x720}"
FPS="${3:-60}"
DISPLAY_NAME="${DISPLAY:-:0.0}"

# Day-1 baseline: X11 capture, low-latency H.264, raw TCP transport.
ffmpeg -hide_banner -loglevel info \
  -f x11grab -video_size "${SIZE}" -framerate "${FPS}" -i "${DISPLAY_NAME}" \
  -an \
  -c:v libx264 -preset ultrafast -tune zerolatency \
  -profile:v baseline -level 4.0 -pix_fmt yuv420p \
  -g 30 -keyint_min 30 -bf 0 \
  -f h264 "tcp://0.0.0.0:${PORT}?listen=1"
