#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
export LANG=C

# Runs USB soak test without systemd user service.
# Starts host daemon via run_wbeamd.sh if /v1/health is not reachable.
#
# Usage:
#   ./src/host/scripts/run_soak_local.sh [duration_sec] [control_port] [stream_port]
#
# Example:
#   ./src/host/scripts/run_soak_local.sh 1800 5001 5000

DURATION_SEC="${1:-1800}"
CONTROL_PORT="${2:-5001}"
STREAM_PORT="${3:-5000}"
WAIT_STREAM_READY_SEC="${WAIT_STREAM_READY_SEC:-45}"
AUTO_START_STREAM="${AUTO_START_STREAM:-1}"
REQUIRE_STREAM_READY="${REQUIRE_STREAM_READY:-1}"
SOAK_PROFILE="${SOAK_PROFILE:-lowlatency}"
SOAK_APPLY_PROFILE="${SOAK_APPLY_PROFILE:-1}"
SOAK_ENABLE_LIVE_ADAPTIVE_RESTART="${SOAK_ENABLE_LIVE_ADAPTIVE_RESTART:-0}"
SOAK_SIZE="${SOAK_SIZE:-}"
SOAK_FPS="${SOAK_FPS:-}"
SOAK_BITRATE_KBPS="${SOAK_BITRATE_KBPS:-}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
LOG_DIR="${WBEAM_SOAK_LOG_DIR:-$ROOT_DIR/src/host/rust/logs/soak}"
mkdir -p "$LOG_DIR"
TS="$(date +%Y%m%d-%H%M%S)"
DAEMON_LOG="$LOG_DIR/wbeamd-local-$TS.log"

started_local_daemon=0
daemon_pid=""

cleanup() {
  if [[ "$started_local_daemon" == "1" ]] && [[ -n "$daemon_pid" ]]; then
    kill "$daemon_pid" >/dev/null 2>&1 || true
    wait "$daemon_pid" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT INT TERM

health_url="http://127.0.0.1:${CONTROL_PORT}/v1/health"
status_url="http://127.0.0.1:${CONTROL_PORT}/v1/status"
start_url="http://127.0.0.1:${CONTROL_PORT}/v1/start"
if ! curl -fsS --max-time 1 "$health_url" >/dev/null 2>&1; then
  echo "[soak-local] health unavailable, starting local daemon via run_wbeamd.sh"
  (
    cd "$ROOT_DIR"
    if [[ "$SOAK_ENABLE_LIVE_ADAPTIVE_RESTART" == "1" ]]; then
      export WBEAM_ALLOW_LIVE_ADAPTIVE_RESTART=1
    fi
    "$SCRIPT_DIR/run_wbeamd.sh" "$CONTROL_PORT" "$STREAM_PORT"
  ) >"$DAEMON_LOG" 2>&1 &
  daemon_pid="$!"
  started_local_daemon=1
  echo "[soak-local] daemon pid=$daemon_pid log=$DAEMON_LOG"

  ready=0
  for _ in {1..40}; do
    if curl -fsS --max-time 1 "$health_url" >/dev/null 2>&1; then
      ready=1
      break
    fi
    sleep 0.5
  done
  if [[ "$ready" != "1" ]]; then
    echo "[soak-local] daemon failed to become ready (see $DAEMON_LOG)" >&2
    exit 1
  fi
else
  echo "[soak-local] daemon already reachable at $health_url"
fi

if [[ "$SOAK_APPLY_PROFILE" == "1" ]] && [[ -n "$SOAK_PROFILE" ]]; then
  apply_url="http://127.0.0.1:${CONTROL_PORT}/v1/apply"
  apply_json="$(python3 - <<PY
import json
obj = {"profile": "${SOAK_PROFILE}"}
size = "${SOAK_SIZE}".strip()
fps = "${SOAK_FPS}".strip()
bitrate = "${SOAK_BITRATE_KBPS}".strip()
if size:
    obj["size"] = size
if fps:
    obj["fps"] = int(fps)
if bitrate:
    obj["bitrate_kbps"] = int(bitrate)
print(json.dumps(obj))
PY
)"
  echo "[soak-local] applying soak config: ${apply_json}"
  curl -fsS --max-time 4 -X POST "$apply_url" \
    -H 'Content-Type: application/json' \
    -d "$apply_json" >/dev/null || true
fi
echo "[soak-local] live_adaptive_restart=${SOAK_ENABLE_LIVE_ADAPTIVE_RESTART}"

if [[ "$AUTO_START_STREAM" == "1" ]]; then
  current_state="$(curl -fsS --max-time 2 "$status_url" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("state",""))' || true)"
  case "$current_state" in
    STREAMING|STARTING|RECONNECTING)
      echo "[soak-local] stream state=$current_state (no explicit start needed)"
      ;;
    *)
      echo "[soak-local] stream state=$current_state -> POST /v1/start"
      curl -fsS --max-time 4 -X POST "$start_url" \
        -H 'Content-Type: application/json' -d '{}' >/dev/null || true
      ;;
  esac
fi

ready_stream=0
for _ in $(seq 1 "$WAIT_STREAM_READY_SEC"); do
  current_state="$(curl -fsS --max-time 2 "$status_url" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("state",""))' || true)"
  if [[ "$current_state" == "STREAMING" || "$current_state" == "STARTING" || "$current_state" == "RECONNECTING" ]]; then
    ready_stream=1
    break
  fi
  sleep 1
done
if [[ "$ready_stream" != "1" ]]; then
  echo "[soak-local] stream did not reach STARTING/RECONNECTING/STREAMING within ${WAIT_STREAM_READY_SEC}s (state=${current_state:-unknown})" >&2
  echo "[soak-local] daemon log: $DAEMON_LOG" >&2
  if [[ "$REQUIRE_STREAM_READY" == "1" ]]; then
    exit 2
  fi
fi

cd "$ROOT_DIR"
"$SCRIPT_DIR/soak_usb_30m.sh" "$DURATION_SEC" "$CONTROL_PORT" "$STREAM_PORT"
