#!/usr/bin/env bash
set -euo pipefail

# Live HUD debug stream from Android app (1s cadence from app side)
adb logcat -v time -s WBeamMain:I | rg --line-buffered "HUDDBG|stream worker failed|daemon poll failed|status=error"
