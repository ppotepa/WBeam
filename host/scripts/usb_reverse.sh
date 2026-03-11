#!/usr/bin/env bash
set -euo pipefail

PORT="${1:-5000}"
HOST_PORT="${2:-$PORT}"
ANDROID_SERIAL="${WBEAM_ANDROID_SERIAL:-}"
HARD_RESET="${WBEAM_ADB_HARD_RESET:-0}"
ADB_RECONNECT="${WBEAM_ADB_RECONNECT:-0}"

lock_name="$(printf '%s' "${ANDROID_SERIAL:-default}.${PORT}.${HOST_PORT}" | tr -c '[:alnum:]._-' '_')"
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

adb_device_cmd_timeout() {
	local sec="${1:-3}"
	shift || true
	if command -v timeout >/dev/null 2>&1; then
		if [[ -n "$ANDROID_SERIAL" ]]; then
			timeout "$sec" adb -s "$ANDROID_SERIAL" "$@"
		else
			timeout "$sec" adb "$@"
		fi
	else
		adb_device_cmd "$@"
	fi
}

adb_wait_ready() {
	for (( i=1; i<=30; i++ )); do
		if [[ "$(adb_device_cmd_timeout 2 get-state 2>/dev/null || true)" == "device" ]]; then
			return 0
		fi
		sleep 0.15
	done
	return 1
}

# Legacy hard reset can be enabled explicitly, but default path must be non-disruptive.
if [[ "$HARD_RESET" == "1" ]]; then
	adb_device_cmd_timeout 3 kill-server >/dev/null 2>&1 || true
	sleep 0.4
fi
adb_device_cmd_timeout 3 start-server >/dev/null 2>&1 || true
if [[ "$ADB_RECONNECT" == "1" ]]; then
	adb_device_cmd_timeout 3 reconnect >/dev/null 2>&1 || true
fi

if ! adb_wait_ready; then
	echo "[usb-reverse] warning: adb wait-for-device failed" >&2
	exit 1
fi

for (( i=1; i<=4; i++ )); do
	if adb_device_cmd_timeout 3 reverse "tcp:${PORT}" "tcp:${HOST_PORT}" >/dev/null 2>&1; then
		echo "ADB reverse active: device 127.0.0.1:${PORT} -> host 127.0.0.1:${HOST_PORT}"
		exit 0
	fi
	if (( i < 4 )); then
		echo "[usb-reverse] attempt ${i} failed, retrying adb reverse…" >&2
		adb_device_cmd_timeout 3 start-server >/dev/null 2>&1 || true
		if [[ "$ADB_RECONNECT" == "1" ]]; then
			adb_device_cmd_timeout 3 reconnect >/dev/null 2>&1 || true
		fi
		adb_wait_ready >/dev/null 2>&1 || true
	fi
done

echo "[usb-reverse] warning: adb reverse failed for tcp:${PORT}->tcp:${HOST_PORT} (${ANDROID_SERIAL:-default})" >&2
echo "[usb-reverse] info: common on legacy Android (API<21) or unstable adbd; caller may continue with fallback" >&2
exit 1
