#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${WBEAM_E2E_GUEST_ROOT:-$(pwd)}"
BACKEND="${1:-${WBEAM_E2E_BACKEND:-benchmark_game}}"
DURATION_SEC="${2:-${WBEAM_E2E_DURATION_SEC:-60}}"
CONTROL_PORT="${WBEAM_E2E_CONTROL_PORT:-5001}"
STREAM_PORT="${WBEAM_E2E_STREAM_PORT:-5000}"
DISPLAY_MODE="${WBEAM_E2E_DISPLAY_MODE:-}"
SIZE="${WBEAM_E2E_SIZE:-1280x800}"
FPS="${WBEAM_E2E_FPS:-30}"
BITRATE_KBPS="${WBEAM_E2E_BITRATE_KBPS:-10000}"
ENCODER="${WBEAM_E2E_ENCODER:-h264}"
REPORT_DIR="${WBEAM_E2E_REPORT_DIR:-$ROOT_DIR/e2e/reports/guest-smoke-$(date -u +%Y%m%dT%H%M%SZ)}"

DAEMON_PID=""

log() {
  echo "[e2e-guest-stream] $*"
}

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[e2e-guest-stream] missing command: $1" >&2
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

health_url() {
  echo "http://127.0.0.1:${CONTROL_PORT}/v1/health"
}

status_url() {
  echo "http://127.0.0.1:${CONTROL_PORT}/v1/status"
}

metrics_url() {
  echo "http://127.0.0.1:${CONTROL_PORT}/v1/metrics"
}

start_url() {
  local query="display_mode=${DISPLAY_MODE}"
  if [[ "$BACKEND" != "benchmark_game" ]]; then
    query="${query}&capture_backend=${BACKEND}"
  fi
  echo "http://127.0.0.1:${CONTROL_PORT}/v1/start?${query}"
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
    WBEAM_RUST_LOG_DIR="$REPORT_DIR" ./host/scripts/run_wbeamd.sh "$CONTROL_PORT" "$STREAM_PORT"
  ) >"$REPORT_DIR/daemon.stdout.log" 2>"$REPORT_DIR/daemon.stderr.log" &
  DAEMON_PID="$!"

  if ! wait_health; then
    echo "[e2e-guest-stream] daemon did not become healthy" >&2
    exit 3
  fi
}

stream_client() {
  python3 - "$STREAM_PORT" "$DURATION_SEC" "$REPORT_DIR/client.json" <<'PY'
import json
import socket
import sys
import time

port = int(sys.argv[1])
duration = int(sys.argv[2])
out_path = sys.argv[3]
deadline = time.time() + duration
connected_at = None
bytes_read = 0
chunks = 0
last_error = ""

while time.time() < deadline:
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=1.0) as sock:
            connected_at = time.time()
            sock.settimeout(1.0)
            while time.time() < deadline:
                try:
                    data = sock.recv(65536)
                except socket.timeout:
                    continue
                if not data:
                    break
                bytes_read += len(data)
                chunks += 1
            break
    except OSError as exc:
        last_error = str(exc)
        time.sleep(0.5)

payload = {
    "connected": connected_at is not None,
    "bytes_read": bytes_read,
    "chunks": chunks,
    "last_error": last_error,
}
with open(out_path, "w", encoding="utf-8") as fh:
    json.dump(payload, fh, indent=2, sort_keys=True)

if connected_at is None or bytes_read <= 0:
    sys.exit(1)
PY
}

main() {
  need_cmd curl
  need_cmd jq
  need_cmd python3

  if [[ -z "$DISPLAY_MODE" ]]; then
    if [[ "$BACKEND" == "benchmark_game" ]]; then
      DISPLAY_MODE="benchmark_game"
    else
      DISPLAY_MODE="duplicate"
    fi
  fi

  mkdir -p "$REPORT_DIR"
  cd "$ROOT_DIR"
  log "root=$ROOT_DIR"
  log "report=$REPORT_DIR"
  log "backend=$BACKEND display_mode=$DISPLAY_MODE duration=${DURATION_SEC}s"

  start_daemon_if_needed

  curl -fsS "$(health_url)" | jq . >"$REPORT_DIR/health-before.json"
  curl -fsS "http://127.0.0.1:${CONTROL_PORT}/v1/host-probe" | jq . >"$REPORT_DIR/host-probe.json" || true
  curl -fsS "http://127.0.0.1:${CONTROL_PORT}/v1/virtual/probe" | jq . >"$REPORT_DIR/virtual-probe.json" || true

  apply_json="$(jq -nc \
    --arg encoder "$ENCODER" \
    --arg size "$SIZE" \
    --argjson fps "$FPS" \
    --argjson bitrate "$BITRATE_KBPS" \
    '{encoder:$encoder,size:$size,fps:$fps,bitrate_kbps:$bitrate}')"
  curl -fsS -X POST "http://127.0.0.1:${CONTROL_PORT}/v1/apply" \
    -H 'Content-Type: application/json' \
    -d "$apply_json" | jq . >"$REPORT_DIR/apply.json"

  curl -fsS -X POST "$(start_url)" \
    -H 'Content-Type: application/json' \
    -d '{}' | jq . >"$REPORT_DIR/start.json"

  stream_client &
  client_pid="$!"

  deadline=$(( $(date +%s) + DURATION_SEC ))
  while [[ "$(date +%s)" -lt "$deadline" ]]; do
    curl -fsS --max-time 2 "$(metrics_url)" >>"$REPORT_DIR/metrics.jsonl" || true
    printf '\n' >>"$REPORT_DIR/metrics.jsonl"
    sleep 1
  done

  if ! wait "$client_pid"; then
    echo "[e2e-guest-stream] stream client did not receive bytes" >&2
    cat "$REPORT_DIR/client.json" >&2 || true
    exit 4
  fi

  curl -fsS "$(status_url)" | jq . >"$REPORT_DIR/status-after.json"
  curl -fsS "$(metrics_url)" | jq . >"$REPORT_DIR/metrics-after.json"

  state="$(jq -r '.state // .base.state // empty' "$REPORT_DIR/status-after.json")"
  if [[ "$state" != "STREAMING" && "$state" != "STARTING" && "$state" != "RECONNECTING" ]]; then
    echo "[e2e-guest-stream] unexpected final state: ${state:-unknown}" >&2
    exit 5
  fi

  jq -n \
    --arg backend "$BACKEND" \
    --arg display_mode "$DISPLAY_MODE" \
    --arg state "$state" \
    --argjson duration_sec "$DURATION_SEC" \
    --slurpfile client "$REPORT_DIR/client.json" \
    '{ok:true,backend:$backend,display_mode:$display_mode,state:$state,duration_sec:$duration_sec,client:$client[0]}' \
    >"$REPORT_DIR/summary.json"

  log "PASS"
  log "summary=$REPORT_DIR/summary.json"
}

main "$@"
