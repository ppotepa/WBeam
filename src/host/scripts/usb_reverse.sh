#!/usr/bin/env bash
set -euo pipefail

PORT="${1:-5000}"
ANDROID_SERIAL="${WBEAM_ANDROID_SERIAL:-}"
HARD_RESET="${WBEAM_ADB_HARD_RESET:-0}"

lock_name="$(printf '%s' "${ANDROID_SERIAL:-default}.${PORT}" | tr -c '[:alnum:]._-' '_')"
lock_file="/tmp/wbeam-usb-reverse.${lock_name}.lock"

mkdir -p /tmp
exec 9>"${lock_file}"
if command -v flock >/dev/null 2>&1; then
	if ! flock -n 9; then
		echo "[usb-reverse] info: reverse is already in progress for ${ANDROID_SERIAL:-default}:${PORT}" >&2
		exit 0
	fi
fi

adb_device_cmd() {
	if [[ -n "$ANDROID_SERIAL" ]]; then
		adb -s "$ANDROID_SERIAL" "$@"
	else
		adb "$@"
	fi
}

adb_wait_ready() {
	for (( i=1; i<=30; i++ )); do
		if [[ "$(adb_device_cmd get-state 2>/dev/null || true)" == "device" ]]; then
			return 0
		fi
		sleep 0.15
	done
	return 1
}

# Legacy hard reset can be enabled explicitly, but default path must be non-disruptive.
if [[ "$HARD_RESET" == "1" ]]; then
	adb kill-server >/dev/null 2>&1 || true
	sleep 0.4
fi
adb start-server >/dev/null 2>&1 || true
adb_device_cmd reconnect >/dev/null 2>&1 || true

if ! adb_wait_ready; then
	echo "[usb-reverse] warning: adb wait-for-device failed" >&2
	exit 1
fi

for (( i=1; i<=4; i++ )); do
	if adb_device_cmd reverse "tcp:${PORT}" "tcp:${PORT}" >/dev/null 2>&1; then
		echo "ADB reverse active: device 127.0.0.1:${PORT} -> host 127.0.0.1:${PORT}"
		exit 0
	fi
	if (( i < 4 )); then
		echo "[usb-reverse] attempt ${i} failed, retrying adb reverse…" >&2
		adb start-server >/dev/null 2>&1 || true
		adb_device_cmd reconnect >/dev/null 2>&1 || true
		adb_wait_ready >/dev/null 2>&1 || true
	fi
done

echo "[usb-reverse] warning: adb reverse failed for tcp:${PORT} (${ANDROID_SERIAL:-default})" >&2
echo "[usb-reverse] info: common on legacy Android (API<21) or unstable adbd; caller may continue with fallback" >&2
exit 1
