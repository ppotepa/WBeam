#!/usr/bin/env bash
set -euo pipefail

PORT="${1:-5000}"

adb start-server >/dev/null
adb wait-for-device
adb reverse "tcp:${PORT}" "tcp:${PORT}"

echo "ADB reverse active: device 127.0.0.1:${PORT} -> host 127.0.0.1:${PORT}"
