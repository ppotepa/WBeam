#!/usr/bin/env bash
# Set the WayBeam capture backend preference for the next daemon launch.
#
# Usage: set-capture-mode.sh [wayland|evdi|x11|auto]
#
# Backends:
#   wayland  - XDG ScreenCast portal (requires a supported Wayland session)
#   evdi     - EVDI virtual display (requires root for modprobe)
#   x11      - Retained legacy value; rejected by current release runtime
#   auto     - Clear the stored preference and use the daemon default
#
# The setting is written to ${XDG_CONFIG_HOME:-$HOME/.config}/wbeam/wbeam.conf.
# Restart the daemon through its launcher to reload the file. An existing
# process environment or a per-session API request can override this preference.

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
