#!/usr/bin/env bash
set -euo pipefail

# Simple helper to build the prototype APK, install it on a device, and start the host image server.
# Environment:
#   HOST_IP         Host IP the device should hit (default 192.168.42.170 — USB tether)
#   SERIAL          Android serial (optional, defaults to adb default device)
#   GRADLEW         Path to gradlew (defaults to ./gradlew inside proto/front)
#   CARGO_BIN       Host server binary (optional, will `cargo run --release` if not provided)

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
HOST_IP="${HOST_IP:-192.168.42.170}"
SERIAL_FLAG=()
[[ -n "${SERIAL:-}" ]] && SERIAL_FLAG=(-s "$SERIAL")

FRONT_DIR="$ROOT_DIR/front"
HOST_DIR="$ROOT_DIR/host"
GRADLEW="${GRADLEW:-$FRONT_DIR/gradlew}"
APK_PATH="$FRONT_DIR/app/build/outputs/apk/debug/app-debug.apk"
CARGO_BIN="${CARGO_BIN:-}" # if set, we exec that instead of cargo run

log() { printf '[proto] %s\n' "$*"; }

# Build APK (fallback to system gradle if wrapper missing)
if [[ ! -x "$GRADLEW" ]]; then
  if command -v gradle >/dev/null 2>&1; then
    log "gradlew not found at $GRADLEW; using system gradle"
    GRADLEW="gradle"
  else
    log "gradlew not found at $GRADLEW and no system gradle in PATH; set GRADLEW or install gradle"
    exit 1
  fi
fi
log "building APK (debug)…"
( cd "$FRONT_DIR" && "$GRADLEW" :app:assembleDebug )

# Install APK
if [[ ! -f "$APK_PATH" ]]; then
  log "APK not found at $APK_PATH"
  exit 1
fi
log "installing APK to device ${SERIAL:-<default>}…"
adb "${SERIAL_FLAG[@]}" install -r "$APK_PATH"

# Start host server first, then launch app so auto-start stream has endpoint ready.
log "starting host server on 0.0.0.0:5005 (desktop frame each second)…"
cd "$HOST_DIR"
if [[ -n "$CARGO_BIN" ]]; then
  PROTO_FORCE_PORTAL=1 PROTO_PORTAL_ONLY=1 PROTO_EXTEND_RIGHT_PX=0 "$CARGO_BIN" &
else
  PROTO_FORCE_PORTAL=1 PROTO_PORTAL_ONLY=1 PROTO_EXTEND_RIGHT_PX=0 cargo run --release &
fi
HOST_PID=$!

cleanup() {
  kill "$HOST_PID" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

sleep 1
log "if Wayland prompt appears, pick the virtual/extended screen source"

# Launch app
log "launching app on device…"
adb "${SERIAL_FLAG[@]}" shell am force-stop com.proto.demo >/dev/null 2>&1 || true
adb "${SERIAL_FLAG[@]}" shell am start -n com.proto.demo/.MainActivity

wait "$HOST_PID"
