#!/usr/bin/env bash
set -euo pipefail

X11_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STREAM_PORT="${WBEAM_X11_STREAM_PORT:-5002}"
SKIP_SERVICE_RESTART=0
CONTROL_PORT="${WBEAM_CONTROL_PORT:-5001}"
CONTROL_WAIT_SEC="${WBEAM_X11_CONTROL_WAIT_SEC:-20}"
AUTO_LOAD_EVDI=1

usage() {
  cat <<USAGE
Usage:
  ./proto_x11/deploy-and-start.sh [--stream-port <port>] [--control-port <port>] [--skip-service-restart] [--auto-load-evdi|--no-auto-load-evdi]

Behavior:
  - requires exactly one adb device in 'device' state
  - deploys APK com.wbeam.x11
  - (optionally) restarts user daemon service
  - starts proto_x11 virtual monitor session on detected serial
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --stream-port)
      shift
      STREAM_PORT="${1:-}"
      if [[ -z "$STREAM_PORT" ]]; then
        echo "[proto_x11] missing value for --stream-port" >&2
        exit 2
      fi
      ;;
    --skip-service-restart)
      SKIP_SERVICE_RESTART=1
      ;;
    --auto-load-evdi)
      AUTO_LOAD_EVDI=1
      ;;
    --no-auto-load-evdi)
      AUTO_LOAD_EVDI=0
      ;;
    --control-port)
      shift
      CONTROL_PORT="${1:-}"
      if [[ -z "$CONTROL_PORT" ]]; then
        echo "[proto_x11] missing value for --control-port" >&2
        exit 2
      fi
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "[proto_x11] unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

wait_control_api() {
  if ! command -v curl >/dev/null 2>&1; then
    echo "[proto_x11] curl not found; skipping control API wait"
    return 0
  fi
  local url="http://127.0.0.1:${CONTROL_PORT}/health"
  local elapsed=0
  while [[ "$elapsed" -lt "$CONTROL_WAIT_SEC" ]]; do
    if curl -fsS --max-time 2 "$url" >/dev/null 2>&1; then
      echo "[proto_x11] control API is ready on :${CONTROL_PORT}"
      return 0
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  echo "[proto_x11] control API not ready after ${CONTROL_WAIT_SEC}s: $url" >&2
  return 1
}

doctor_json() {
  local url="http://127.0.0.1:${CONTROL_PORT}/v1/virtual/doctor?serial=${SERIAL}&stream_port=${STREAM_PORT}"
  curl -fsS --max-time 5 "$url"
}

read_doctor_state() {
  DOCTOR_RAW="$(doctor_json)"
  DOCTOR_OK="$(printf '%s' "$DOCTOR_RAW" | python3 -c 'import json,sys; print("1" if json.load(sys.stdin).get("ok") else "0")')"
  DOCTOR_HINT="$(printf '%s' "$DOCTOR_RAW" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("install_hint",""))')"
  DOCTOR_MISSING="$(printf '%s' "$DOCTOR_RAW" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(",".join(d.get("missing_deps") or []))')"
}

if ! command -v adb >/dev/null 2>&1; then
  echo "[proto_x11] adb not found in PATH" >&2
  exit 1
fi

mapfile -t SERIALS < <(adb devices | awk '$2=="device" {print $1}')
if [[ "${#SERIALS[@]}" -eq 0 ]]; then
  echo "[proto_x11] no adb devices in 'device' state" >&2
  exit 1
fi
if [[ "${#SERIALS[@]}" -gt 1 ]]; then
  echo "[proto_x11] more than one adb device detected; connect one device only" >&2
  printf '%s\n' "${SERIALS[@]}" >&2
  exit 1
fi

SERIAL="${SERIALS[0]}"
echo "[proto_x11] detected serial: $SERIAL"

echo "[proto_x11] deploy android apk (com.wbeam.x11)"
WBEAM_ANDROID_SERIAL="$SERIAL" \
WBEAM_STREAM_PORT="$STREAM_PORT" \
WBEAM_CONTROL_PORT="$CONTROL_PORT" \
  "$X11_DIR/run" android-deploy

if [[ "$SKIP_SERVICE_RESTART" != "1" ]]; then
  echo "[proto_x11] restart user daemon service"
  "$X11_DIR/run" service-restart
fi

wait_control_api

echo "[proto_x11] preflight doctor"
"$X11_DIR/run" doctor --serial "$SERIAL" --stream-port "$STREAM_PORT"

read_doctor_state
if [[ "$DOCTOR_OK" != "1" ]]; then
  if [[ "$DOCTOR_MISSING" == *"evdi-module-loaded"* && "$AUTO_LOAD_EVDI" == "1" ]]; then
    echo "[proto_x11] auto-load evdi requested; running: sudo modprobe evdi initial_device_count=1"
    sudo modprobe evdi initial_device_count=1
    if [[ "$SKIP_SERVICE_RESTART" != "1" ]]; then
      echo "[proto_x11] restart user daemon service (after evdi load)"
      "$X11_DIR/run" service-restart
      wait_control_api
    fi
    echo "[proto_x11] re-check doctor after evdi load"
    "$X11_DIR/run" doctor --serial "$SERIAL" --stream-port "$STREAM_PORT"
    read_doctor_state
  fi
fi

if [[ "$DOCTOR_OK" != "1" ]]; then
  echo "[proto_x11] doctor reports virtual backend NOT ready." >&2
  if [[ -n "$DOCTOR_MISSING" ]]; then
    echo "[proto_x11] missing_deps: $DOCTOR_MISSING" >&2
  fi
  if [[ "$DOCTOR_MISSING" == *"evdi-module-loaded"* ]]; then
    echo "[proto_x11] fix: run 'sudo modprobe evdi initial_device_count=1' and retry." >&2
  fi
  if [[ -n "$DOCTOR_HINT" ]]; then
    echo "[proto_x11] hint: $DOCTOR_HINT" >&2
  fi
  exit 1
fi

echo "[proto_x11] start session"
"$X11_DIR/run" start --serial "$SERIAL" --stream-port "$STREAM_PORT"

echo "[proto_x11] done (serial=$SERIAL stream_port=$STREAM_PORT)"
