#!/usr/bin/env bash
set -euo pipefail

PORT="${1:-5000}"
ANDROID_SERIAL="${WBEAM_ANDROID_SERIAL:-}"

adb_device_cmd() {
	if [[ -n "$ANDROID_SERIAL" ]]; then
		adb -s "$ANDROID_SERIAL" "$@"
	else
		adb "$@"
	fi
}

adb_try() {
	local attempts="${1:-3}"
	shift
	local i
	for (( i=1; i<=attempts; i++ )); do
		if adb_device_cmd "$@" >/dev/null 2>&1; then
			return 0
		fi
		if (( i < attempts )); then
			sleep 0.3
			adb kill-server >/dev/null 2>&1 || true
			adb start-server >/dev/null 2>&1 || true
		fi
	done
	return 1
}

adb start-server >/dev/null 2>&1 || true

if ! adb_try 4 wait-for-device; then
	echo "[usb-reverse] warning: adb wait-for-device failed" >&2
	exit 1
fi

if ! adb_try 4 reverse "tcp:${PORT}" "tcp:${PORT}"; then
	echo "[usb-reverse] warning: adb reverse failed for tcp:${PORT} (${ANDROID_SERIAL:-default})" >&2
	echo "[usb-reverse] info: common on legacy Android (API<21) or unstable adbd; caller may continue with fallback" >&2
	exit 1
fi

echo "ADB reverse active: device 127.0.0.1:${PORT} -> host 127.0.0.1:${PORT}"
