#!/usr/bin/env bash
set -euo pipefail

FILE_PATH="${1:-}"
PORT="${2:-5000}"
SIZE="${3:-1280x720}"
FPS="${4:-30}"

if [[ -z "$FILE_PATH" ]]; then
  echo "Usage: $0 <VIDEO_FILE> [PORT] [SIZE] [FPS]" >&2
  exit 1
fi

if [[ ! -f "$FILE_PATH" ]]; then
  echo "File not found: $FILE_PATH" >&2
  exit 1
fi

WIDTH="${SIZE%x*}"
HEIGHT="${SIZE#*x}"

echo "Streaming file=$FILE_PATH size=$SIZE fps=$FPS port=$PORT"

ffmpeg -hide_banner -loglevel info \
  -re -stream_loop -1 -i "$FILE_PATH" \
  -an \
  -vf "scale=${WIDTH}:${HEIGHT}:force_original_aspect_ratio=decrease,pad=${WIDTH}:${HEIGHT}:(ow-iw)/2:(oh-ih)/2,fps=${FPS}" \
  -c:v libx264 -preset ultrafast -tune zerolatency \
  -b:v 8M -maxrate 8M -bufsize 8M \
  -x264-params "repeat-headers=1:scenecut=0:slice-max-size=1300" \
  -profile:v baseline -level 4.0 -pix_fmt yuv420p \
  -g 30 -keyint_min 30 -bf 0 \
  -f h264 "tcp://0.0.0.0:${PORT}?listen=1"
