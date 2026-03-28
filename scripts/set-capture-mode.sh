#!/usr/bin/env bash
# Set the WBeam capture backend for the current session.
#
# Usage: set-capture-mode.sh [wayland|evdi|x11|auto]
#
# Backends:
#   wayland  - XDG ScreenCast portal (default on Wayland, 60 fps cap)
#   evdi     - Direct kernel capture via EVDI (requires root for modprobe)
#   x11      - GStreamer X11 capture (XDG/X11 sessions)
#   auto     - Auto-detect (default)
#
# The setting is written to ~/.config/wbeam/wbeam.conf and takes effect on
# the next /v1/start call.  The desktop UI "Capture Backend" dropdown in the
# Connect dialog overrides this file for individual sessions.

set -euo pipefail

MODE="${1:-auto}"
CONFIG_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/wbeam"
CONFIG_FILE="$CONFIG_DIR/wbeam.conf"

case "$MODE" in
  wayland|evdi|x11|auto) ;;
  *)
    echo "Unknown capture mode: $MODE" >&2
    echo "Valid modes: wayland, evdi, x11, auto" >&2
    exit 1
    ;;
esac

mkdir -p "$CONFIG_DIR"

# Remove any existing WBEAM_CAPTURE_BACKEND line and append the new one.
if [ -f "$CONFIG_FILE" ]; then
    sed -i '/^WBEAM_CAPTURE_BACKEND=/d' "$CONFIG_FILE"
fi

if [ "$MODE" != "auto" ]; then
    echo "WBEAM_CAPTURE_BACKEND=$MODE" >> "$CONFIG_FILE"
fi

echo "Capture mode set to: $MODE"

if [ "$MODE" = "evdi" ]; then
    echo "Loading EVDI kernel module..."
    if sudo modprobe evdi initial_device_count=4; then
        echo "EVDI module loaded OK"
    else
        echo "WARNING: modprobe evdi failed — ensure the evdi DKMS package is installed." >&2
    fi
fi
