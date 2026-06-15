#!/usr/bin/env bash
# guest-usb-stream-smoke.sh — runs INSIDE the QEMU guest.
# The Android phone is passed through via QEMU USB passthrough, so ADB sees it
# as a locally attached USB device. This script:
#   1. Waits for ADB to see the phone.
#   2. Deploys the WBeam APK.
#   3. Starts the WBeam host daemon.
#   4. Starts a stream session (wayland or evdi backend).
#   5. Monitors for DURATION_SEC seconds via the control API.
#   6. Verifies the phone received frames via ADB telemetry.
#   7. Writes a summary JSON report.
set -euo pipefail

ROOT_DIR="${WBEAM_E2E_GUEST_ROOT:-$(pwd)}"
BACKEND="${1:-${WBEAM_E2E_BACKEND:-wayland_portal}}"
DURATION_SEC="${2:-${WBEAM_E2E_DURATION_SEC:-60}}"
CONTROL_PORT="${WBEAM_E2E_CONTROL_PORT:-5001}"
STREAM_PORT="${WBEAM_E2E_STREAM_PORT:-5000}"
DISPLAY_MODE="${WBEAM_E2E_DISPLAY_MODE:-duplicate}"
SIZE="${WBEAM_E2E_SIZE:-1280x800}"
FPS="${WBEAM_E2E_FPS:-30}"
BITRATE_KBPS="${WBEAM_E2E_BITRATE_KBPS:-10000}"
ENCODER="${WBEAM_E2E_ENCODER:-h264}"
ANDROID_SERIAL="${WBEAM_E2E_ANDROID_SERIAL:-}"
REPORT_DIR="${WBEAM_E2E_REPORT_DIR:-$ROOT_DIR/e2e/reports/usb-smoke-$(date -u +%Y%m%dT%H%M%SZ)}"
# Minimum bytes the phone must have received to count as a passing stream.
MIN_BYTES_THRESHOLD="${WBEAM_E2E_MIN_BYTES:-65536}"

DAEMON_PID=""

log() { echo "[e2e-usb-stream] $*"; }

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[e2e-usb-stream] missing command: $1" >&2
    exit 2
  fi
}

cleanup() {
  if [[ -n "$DAEMON_PID" ]]; then
    kill "$DAEMON_PID" >/dev/null 2>&1 || true
    wait "$DAEMON_PID" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT INT TERM

# ── ADB helpers ────────────────────────────────────────────────────────────────

adb_cmd() {
  if [[ -n "$ANDROID_SERIAL" ]]; then
    adb -s "$ANDROID_SERIAL" "$@"
  else
    adb "$@"
  fi
}

wait_for_adb_device() {
  local attempts=0
  log "waiting for ADB device (USB passthrough)…"
  while (( attempts < 60 )); do
    if adb_cmd get-state 2>/dev/null | grep -q 'device'; then
      log "ADB device ready: $(adb_cmd get-serialno 2>/dev/null || echo unknown)"
      return 0
    fi
    sleep 2
    (( attempts++ ))
  done
  echo "[e2e-usb-stream] timed out waiting for ADB device" >&2
  exit 3
}

adb_device_info() {
  local serial model android_ver api
  serial="$(adb_cmd get-serialno 2>/dev/null || echo unknown)"
  model="$(adb_cmd shell getprop ro.product.model 2>/dev/null | tr -d '\r' || echo unknown)"
  android_ver="$(adb_cmd shell getprop ro.build.version.release 2>/dev/null | tr -d '\r' || echo ?)"
  api="$(adb_cmd shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || echo ?)"
  jq -n \
    --arg serial "$serial" \
    --arg model "$model" \
    --arg android_ver "$android_ver" \
    --arg api "$api" \
    '{serial:$serial,model:$model,android_version:$android_ver,sdk_api:$api}'
}

# ── Daemon helpers ─────────────────────────────────────────────────────────────

health_url()  { echo "http://127.0.0.1:${CONTROL_PORT}/v1/health"; }
status_url()  { echo "http://127.0.0.1:${CONTROL_PORT}/v1/status"; }
metrics_url() { echo "http://127.0.0.1:${CONTROL_PORT}/v1/metrics"; }
start_url() {
  echo "http://127.0.0.1:${CONTROL_PORT}/v1/start?display_mode=${DISPLAY_MODE}&capture_backend=${BACKEND}"
}

wait_health() {
  local i
  for i in $(seq 1 80); do
    if curl -fsS --max-time 1 "$(health_url)" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.5
  done
  return 1
}

start_daemon_if_needed() {
  if curl -fsS --max-time 1 "$(health_url)" >/dev/null 2>&1; then
    log "daemon already reachable"
    return 0
  fi
  log "starting local daemon"
  (
    cd "$ROOT_DIR"
    WBEAM_RUST_LOG_DIR="$REPORT_DIR" \
      ./host/scripts/run_wbeamd.sh "$CONTROL_PORT" "$STREAM_PORT"
  ) >"$REPORT_DIR/daemon.stdout.log" 2>"$REPORT_DIR/daemon.stderr.log" &
  DAEMON_PID="$!"
  if ! wait_health; then
    echo "[e2e-usb-stream] daemon did not become healthy" >&2
    exit 4
  fi
}

# ── Deploy APK ────────────────────────────────────────────────────────────────

deploy_apk() {
  log "building and deploying APK to phone…"
  cd "$ROOT_DIR"
  # Build + install + launch the Android app on the connected device.
  if [[ -n "$ANDROID_SERIAL" ]]; then
    WBEAM_ANDROID_SERIAL="$ANDROID_SERIAL" ./wbeam android deploy
  else
    ./wbeam android deploy
  fi
  log "APK deployed"
}

# ── Phone telemetry check via ADB ──────────────────────────────────────────────
# Reads a file the app writes to /sdcard/wbeam-e2e-metrics.json while streaming.
# Falls back to checking logcat for frame-received events.

check_phone_received_stream() {
  local metrics_path="/sdcard/wbeam-e2e-metrics.json"
  local tmp_metrics="$REPORT_DIR/phone-metrics.json"

  # Try pulling the metrics file first.
  if adb_cmd pull "$metrics_path" "$tmp_metrics" >/dev/null 2>&1; then
    local bytes_received
    bytes_received="$(jq -r '.bytes_received // 0' "$tmp_metrics" 2>/dev/null || echo 0)"
    log "phone metrics: bytes_received=$bytes_received"
    echo "$bytes_received"
    return 0
  fi

  # Fallback: scan logcat for WBeam frame events.
  local frame_count
  frame_count="$(
    adb_cmd logcat -d -s WBeam:* 2>/dev/null \
      | grep -c 'frame\|decoded\|rendered' 2>/dev/null \
      || echo 0
  )"
  log "phone logcat frame events: $frame_count"
  # Convert frame count to synthetic byte estimate (one H264 P-frame ≈ 16 KiB).
  echo $(( frame_count * 16384 ))
}

# ── Main ──────────────────────────────────────────────────────────────────────

main() {
  need_cmd curl
  need_cmd jq
  need_cmd adb

  mkdir -p "$REPORT_DIR"
  cd "$ROOT_DIR"
  log "root=$ROOT_DIR"
  log "report=$REPORT_DIR"
  log "backend=$BACKEND display_mode=$DISPLAY_MODE duration=${DURATION_SEC}s"

  # ── Step 1: wait for phone ──────────────────────────────────────────────────
  adb start-server >/dev/null 2>&1 || true
  wait_for_adb_device
  adb_device_info | tee "$REPORT_DIR/phone-info.json"

  # ── Step 2: deploy APK ─────────────────────────────────────────────────────
  deploy_apk 2>&1 | tee "$REPORT_DIR/deploy.log"

  # ── Step 3: start host daemon ───────────────────────────────────────────────
  start_daemon_if_needed

  # ── Step 4: collect pre-stream health ──────────────────────────────────────
  curl -fsS "$(health_url)" | jq . >"$REPORT_DIR/health-before.json"
  curl -fsS "http://127.0.0.1:${CONTROL_PORT}/v1/host-probe" \
    | jq . >"$REPORT_DIR/host-probe.json" || true
  curl -fsS "http://127.0.0.1:${CONTROL_PORT}/v1/virtual/probe" \
    | jq . >"$REPORT_DIR/virtual-probe.json" || true

  # ── Step 5: apply encode config ────────────────────────────────────────────
  apply_json="$(jq -nc \
    --arg encoder "$ENCODER" \
    --arg size "$SIZE" \
    --argjson fps "$FPS" \
    --argjson bitrate "$BITRATE_KBPS" \
    '{encoder:$encoder,size:$size,fps:$fps,bitrate_kbps:$bitrate}')"
  curl -fsS -X POST "http://127.0.0.1:${CONTROL_PORT}/v1/apply" \
    -H 'Content-Type: application/json' \
    -d "$apply_json" | jq . >"$REPORT_DIR/apply.json"

  # ── Step 6: start stream ────────────────────────────────────────────────────
  log "starting stream (backend=$BACKEND, display_mode=$DISPLAY_MODE)…"
  curl -fsS -X POST "$(start_url)" \
    -H 'Content-Type: application/json' \
    -d '{}' | jq . >"$REPORT_DIR/start.json"

  # ── Step 7: monitor for DURATION_SEC seconds ────────────────────────────────
  log "monitoring for ${DURATION_SEC}s…"
  deadline=$(( $(date +%s) + DURATION_SEC ))
  while [[ "$(date +%s)" -lt "$deadline" ]]; do
    curl -fsS --max-time 2 "$(metrics_url)" >>"$REPORT_DIR/metrics.jsonl" || true
    printf '\n' >>"$REPORT_DIR/metrics.jsonl"
    sleep 2
  done

  # ── Step 8: collect post-stream state ──────────────────────────────────────
  curl -fsS "$(status_url)" | jq . >"$REPORT_DIR/status-after.json"
  curl -fsS "$(metrics_url)" | jq . >"$REPORT_DIR/metrics-after.json"

  # ── Step 9: check phone received stream ─────────────────────────────────────
  bytes_received="$(check_phone_received_stream)"

  # ── Step 10: collect phone logcat ───────────────────────────────────────────
  adb_cmd logcat -d >"$REPORT_DIR/phone-logcat.log" 2>/dev/null || true

  # ── Step 11: validate ───────────────────────────────────────────────────────
  state="$(jq -r '.state // .base.state // empty' "$REPORT_DIR/status-after.json")"
  daemon_ok=false
  phone_ok=false
  [[ "$state" == "STREAMING" || "$state" == "STARTING" || "$state" == "RECONNECTING" ]] && daemon_ok=true
  (( bytes_received >= MIN_BYTES_THRESHOLD )) && phone_ok=true

  # ── Step 12: write summary ──────────────────────────────────────────────────
  phone_info_file="$REPORT_DIR/phone-info.json"
  phone_info="{}"
  [[ -f "$phone_info_file" ]] && phone_info="$(cat "$phone_info_file")"

  jq -n \
    --arg backend "$BACKEND" \
    --arg display_mode "$DISPLAY_MODE" \
    --arg state "$state" \
    --argjson duration_sec "$DURATION_SEC" \
    --argjson bytes_received "$bytes_received" \
    --argjson daemon_ok "$daemon_ok" \
    --argjson phone_ok "$phone_ok" \
    --argjson phone "$phone_info" \
    '{
      ok: ($daemon_ok and $phone_ok),
      backend: $backend,
      display_mode: $display_mode,
      daemon_state: $state,
      daemon_ok: $daemon_ok,
      phone_ok: $phone_ok,
      bytes_received_from_phone: $bytes_received,
      duration_sec: $duration_sec,
      phone: $phone
    }' \
    >"$REPORT_DIR/summary.json"

  log "daemon_ok=$daemon_ok  phone_ok=$phone_ok  bytes_received=$bytes_received"

  if [[ "$daemon_ok" == "true" && "$phone_ok" == "true" ]]; then
    log "PASS"
  else
    echo "[e2e-usb-stream] FAIL: daemon_ok=$daemon_ok phone_ok=$phone_ok bytes=$bytes_received" >&2
    exit 5
  fi
}

main "$@"
