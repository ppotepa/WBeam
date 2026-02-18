#!/usr/bin/env bash
set -euo pipefail

STATE_FILE="/tmp/wbeam_virtual_monitor.env"

usage() {
  cat <<USAGE
Usage:
  $0 up [OUTPUT] [WIDTHxHEIGHT] [RATE]
  $0 down [OUTPUT]
  $0 status
USAGE
}

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing command: $1" >&2
    exit 1
  }
}

first_disconnected_output() {
  xrandr --query | awk '$2=="disconnected" {print $1; exit}'
}

primary_or_first_connected() {
  local primary
  primary="$(xrandr --query | awk '$2=="connected" && $3=="primary" {print $1; exit}')"
  if [[ -n "$primary" ]]; then
    echo "$primary"
    return
  fi
  xrandr --query | awk '$2=="connected" {print $1; exit}'
}

if [[ "${1:-}" == "" ]]; then
  usage
  exit 1
fi

need_cmd xrandr

CMD="$1"
shift || true

case "$CMD" in
  up)
    OUTPUT="${1:-$(first_disconnected_output)}"
    SIZE="${2:-1280x720}"
    RATE="${3:-60}"

    if [[ -z "$OUTPUT" ]]; then
      echo "No disconnected output found. Pass output name explicitly, e.g. HDMI-1." >&2
      exit 1
    fi

    WIDTH="${SIZE%x*}"
    HEIGHT="${SIZE#*x}"
    MODE_NAME="WBEAM_${WIDTH}x${HEIGHT}_${RATE}"

    if ! command -v cvt >/dev/null 2>&1; then
      echo "Missing command: cvt (install package with cvt utility, e.g. x11-xserver-utils/xserver-xorg-core extras)" >&2
      exit 1
    fi

    MODEL_REST="$(cvt -r "$WIDTH" "$HEIGHT" "$RATE" | awk '/Modeline/{for(i=3;i<=NF;i++) printf "%s ", $i}')"
    if [[ -z "$MODEL_REST" ]]; then
      MODEL_REST="$(cvt "$WIDTH" "$HEIGHT" "$RATE" | awk '/Modeline/{for(i=3;i<=NF;i++) printf "%s ", $i}')"
    fi

    if ! xrandr --query | grep -q "^${MODE_NAME}[[:space:]]"; then
      xrandr --newmode "$MODE_NAME" $MODEL_REST || true
    fi

    xrandr --addmode "$OUTPUT" "$MODE_NAME" || true

    RIGHT_OF="$(primary_or_first_connected)"
    if [[ -n "$RIGHT_OF" ]]; then
      xrandr --output "$OUTPUT" --mode "$MODE_NAME" --right-of "$RIGHT_OF"
    else
      xrandr --output "$OUTPUT" --mode "$MODE_NAME"
    fi

    cat > "$STATE_FILE" <<STATE
OUTPUT=$OUTPUT
SIZE=$SIZE
RATE=$RATE
MODE_NAME=$MODE_NAME
STATE

    echo "Virtual monitor ON: output=$OUTPUT size=$SIZE rate=$RATE mode=$MODE_NAME"
    ;;

  down)
    ARG_OUTPUT="${1:-}"
    STATE_OUTPUT=""
    STATE_MODE=""

    if [[ -f "$STATE_FILE" ]]; then
      # shellcheck disable=SC1090
      source "$STATE_FILE"
      STATE_OUTPUT="${OUTPUT:-}"
      STATE_MODE="${MODE_NAME:-}"
    fi

    OUTPUT="${ARG_OUTPUT:-$STATE_OUTPUT}"
    MODE_NAME="$STATE_MODE"

    if [[ -z "$OUTPUT" ]]; then
      echo "No output specified and no state file found." >&2
      exit 1
    fi

    xrandr --output "$OUTPUT" --off || true
    if [[ -n "$MODE_NAME" ]]; then
      xrandr --delmode "$OUTPUT" "$MODE_NAME" || true
      xrandr --rmmode "$MODE_NAME" || true
    fi

    rm -f "$STATE_FILE"
    echo "Virtual monitor OFF: output=$OUTPUT"
    ;;

  status)
    echo "Connected/disconnected outputs:"
    xrandr --query | awk '$2=="connected" || $2=="disconnected" {print}'
    if [[ -f "$STATE_FILE" ]]; then
      echo
      echo "Saved WBeam virtual monitor state:"
      cat "$STATE_FILE"
    fi
    ;;

  *)
    usage
    exit 1
    ;;
esac
