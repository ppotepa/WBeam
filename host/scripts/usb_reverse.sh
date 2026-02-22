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

# Reset adbd state upfront — this is the pattern proven to work on legacy
# devices (API 17) where adb reverse returns "error: closed" on a stale session.
adb kill-server >/dev/null 2>&1 || true
sleep 0.5
adb start-server >/dev/null 2>&1 || true
adb_device_cmd reconnect >/dev/null 2>&1 || true
sleep 0.3

if ! adb_device_cmd wait-for-device >/dev/null 2>&1; then
	echo "[usb-reverse] warning: adb wait-for-device failed" >&2
	exit 1
fi

for (( i=1; i<=4; i++ )); do
	if adb_device_cmd reverse "tcp:${PORT}" "tcp:${PORT}" >/dev/null 2>&1; then
		echo "ADB reverse active: device 127.0.0.1:${PORT} -> host 127.0.0.1:${PORT}"
		exit 0
	fi
	if (( i < 4 )); then
		echo "[usb-reverse] attempt ${i} failed, resetting adb…" >&2
		adb kill-server >/dev/null 2>&1 || true
		sleep 0.5
		adb start-server >/dev/null 2>&1 || true
		adb_device_cmd wait-for-device >/dev/null 2>&1 || true
	fi
done

echo "[usb-reverse] warning: adb reverse failed for tcp:${PORT} (${ANDROID_SERIAL:-default})" >&2
echo "[usb-reverse] info: common on legacy Android (API<21) or unstable adbd; caller may continue with fallback" >&2
exit 1
