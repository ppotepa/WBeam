#!/usr/bin/env bash
set -euo pipefail

OUTPUT="${1:-}"
PORT="${2:-5000}"
FPS="${3:-30}"
DISPLAY_NAME="${DISPLAY:-:0.0}"

if [[ -z "$OUTPUT" ]]; then
  echo "Usage: $0 <OUTPUT> [PORT] [FPS]" >&2
  exit 1
fi

GEOM="$(xrandr --query | awk -v out="$OUTPUT" '$1==out && $2=="connected" {for(i=1;i<=NF;i++) if($i ~ /^[0-9]+x[0-9]+\+[0-9]+\+[0-9]+$/){print $i; exit}}')"
if [[ -z "$GEOM" ]]; then
  echo "Could not find geometry for output '$OUTPUT'. Is it connected and active?" >&2
  xrandr --query >&2
  exit 1
fi

SIZE="${GEOM%%+*}"
REST="${GEOM#*+}"
X="${REST%%+*}"
Y="${REST#*+}"

echo "Streaming output=$OUTPUT size=$SIZE offset=${X},${Y} display=$DISPLAY_NAME port=$PORT fps=$FPS"

ffmpeg -hide_banner -loglevel info \
  -f x11grab -video_size "$SIZE" -framerate "$FPS" -i "${DISPLAY_NAME}+${X},${Y}" \
  -an \
  -c:v libx264 -preset ultrafast -tune zerolatency \
  -b:v 8M -maxrate 8M -bufsize 8M \
  -x264-params "repeat-headers=1:scenecut=0:slice-max-size=1300" \
  -profile:v baseline -level 4.0 -pix_fmt yuv420p \
  -g 30 -keyint_min 30 -bf 0 \
  -f h264 "tcp://0.0.0.0:${PORT}?listen=1"
