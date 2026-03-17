#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
export LANG=C

DURATION_SEC="${1:-1800}"
CONTROL_PORT="${2:-5001}"
STREAM_PORT="${3:-5000}"
POLL_SEC="${POLL_SEC:-1}"
MAX_NO_PRESENT_SEC="${MAX_NO_PRESENT_SEC:-8}"
MIN_RECV_FPS="${MIN_RECV_FPS:-10}"
MAX_PRESENT_FPS="${MAX_PRESENT_FPS:-1}"
MAX_METRICS_MISSING_SEC="${MAX_METRICS_MISSING_SEC:-15}"
MIN_SAMPLES="${MIN_SAMPLES:-1}"
MIN_STREAMING_SAMPLES="${MIN_STREAMING_SAMPLES:-1}"
WARMUP_SEC="${WARMUP_SEC:-20}"
MIN_PRESENT_FPS_FLOOR="${MIN_PRESENT_FPS_FLOOR:-12}"
LOW_FPS_MAX_SEC="${LOW_FPS_MAX_SEC:-20}"
MIN_RECV_FPS_FLOOR="${MIN_RECV_FPS_FLOOR:-6}"
LOW_RECV_MAX_SEC="${LOW_RECV_MAX_SEC:-20}"
MAX_STARTING_SEC="${MAX_STARTING_SEC:-60}"
ACTIVE_SCENE_MIN_BPS="${ACTIVE_SCENE_MIN_BPS:-1500000}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [[ -f "$SCRIPT_DIR/../../wbeam" ]]; then
  ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
elif [[ -f "$SCRIPT_DIR/../../../wbeam" ]]; then
  ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
else
  ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
fi
LOG_DIR="${WBEAM_SOAK_LOG_DIR:-$ROOT_DIR/host/rust/logs/soak}"
mkdir -p "$LOG_DIR"
TS="$(date +%Y%m%d-%H%M%S)"
LOG_FILE="$LOG_DIR/soak-usb-$TS.log"

if ! command -v jq >/dev/null 2>&1; then
  echo "[soak] jq is required" | tee -a "$LOG_FILE"
  exit 1
fi

echo "[soak] started at $(date -Iseconds)" | tee -a "$LOG_FILE"
echo "[soak] duration=${DURATION_SEC}s poll=${POLL_SEC}s max_no_present=${MAX_NO_PRESENT_SEC}s" | tee -a "$LOG_FILE"
echo "[soak] thresholds: min_recv_fps=${MIN_RECV_FPS} max_present_fps=${MAX_PRESENT_FPS}" | tee -a "$LOG_FILE"
echo "[soak] max_metrics_missing=${MAX_METRICS_MISSING_SEC}s" | tee -a "$LOG_FILE"
echo "[soak] min_samples=${MIN_SAMPLES}" | tee -a "$LOG_FILE"
echo "[soak] min_streaming_samples=${MIN_STREAMING_SAMPLES}" | tee -a "$LOG_FILE"
echo "[soak] warmup=${WARMUP_SEC}s min_present_fps_floor=${MIN_PRESENT_FPS_FLOOR} low_fps_max=${LOW_FPS_MAX_SEC}s" | tee -a "$LOG_FILE"
echo "[soak] min_recv_fps_floor=${MIN_RECV_FPS_FLOOR} low_recv_max=${LOW_RECV_MAX_SEC}s" | tee -a "$LOG_FILE"
echo "[soak] max_starting_sec=${MAX_STARTING_SEC}s" | tee -a "$LOG_FILE"
echo "[soak] active_scene_min_bps=${ACTIVE_SCENE_MIN_BPS}" | tee -a "$LOG_FILE"

"$SCRIPT_DIR/usb_reverse.sh" "$STREAM_PORT" >/dev/null
adb reverse "tcp:${CONTROL_PORT}" "tcp:${CONTROL_PORT}" >/dev/null

deadline=$(( $(date +%s) + DURATION_SEC ))
start_ts="$(date +%s)"
samples=0
no_present_streak=0
max_no_present_streak=0
max_reconnects=0
max_drops=0
metrics_missing_streak=0
streaming_samples=0
low_fps_streak=0
low_recv_streak=0
starting_streak=0

while [[ "$(date +%s)" -lt "$deadline" ]]; do
  raw="$(curl -fsS --max-time 2 "http://127.0.0.1:${CONTROL_PORT}/v1/metrics" || true)"
  if [[ -z "$raw" ]]; then
    metrics_missing_streak=$((metrics_missing_streak + POLL_SEC))
    echo "[soak] WARN metrics unavailable streak=${metrics_missing_streak}s" | tee -a "$LOG_FILE"
    if (( metrics_missing_streak == 3 )); then
      echo "[soak] hint: daemon API not reachable on :${CONTROL_PORT}; use ./host/scripts/run_soak_local.sh ${DURATION_SEC} ${CONTROL_PORT} ${STREAM_PORT}" \
        | tee -a "$LOG_FILE"
    fi
    if (( metrics_missing_streak >= MAX_METRICS_MISSING_SEC )); then
      echo "[soak] FAIL: metrics unavailable for ${metrics_missing_streak}s" | tee -a "$LOG_FILE"
      echo "[soak] log: $LOG_FILE" | tee -a "$LOG_FILE"
      exit 3
    fi
    sleep "$POLL_SEC"
    continue
  fi
  metrics_missing_streak=0

  read -r state run_id cfg_size cfg_fps cfg_bitrate recv_fps present_fps recv_bps reconnects drops <<<"$(
    jq -r '
      [
        (.state // ""),
        ((.run_id // 0) | tostring),
        (.active_config.size // "-"),
        ((.active_config.fps // "-") | tostring),
        ((.active_config.bitrate_kbps // "-") | tostring),
        ((.metrics.kpi.recv_fps // 0) | tonumber | tostring),
        ((.metrics.kpi.present_fps // 0) | tonumber | tostring),
        ((.metrics.bitrate_actual_bps // 0) | tostring),
        ((.metrics.reconnects // 0) | tostring),
        ((.metrics.drops // 0) | tostring)
      ] | @tsv
    ' <<<"$raw"
  )"

  samples=$((samples + 1))
  if [[ "$state" == "STREAMING" ]]; then
    streaming_samples=$((streaming_samples + 1))
  fi
  if (( reconnects > max_reconnects )); then
    max_reconnects="$reconnects"
  fi
  if (( drops > max_drops )); then
    max_drops="$drops"
  fi

  if [[ "$state" == "STREAMING" ]] \
    && awk "BEGIN {exit !($recv_fps >= $MIN_RECV_FPS && $present_fps <= $MAX_PRESENT_FPS)}"; then
    no_present_streak=$((no_present_streak + POLL_SEC))
  else
    no_present_streak=0
  fi
  if [[ "$state" == "STARTING" ]]; then
    starting_streak=$((starting_streak + POLL_SEC))
  else
    starting_streak=0
  fi
  elapsed=$(( $(date +%s) - start_ts ))
  scene_active=0
  if (( recv_bps >= ACTIVE_SCENE_MIN_BPS )); then
    scene_active=1
  fi
  if [[ "$state" == "STREAMING" ]] && (( elapsed >= WARMUP_SEC )) && (( scene_active == 1 )); then
    if awk "BEGIN {exit !($present_fps < $MIN_PRESENT_FPS_FLOOR)}"; then
      low_fps_streak=$((low_fps_streak + POLL_SEC))
    else
      low_fps_streak=0
    fi
    if awk "BEGIN {exit !($recv_fps < $MIN_RECV_FPS_FLOOR)}"; then
      low_recv_streak=$((low_recv_streak + POLL_SEC))
    else
      low_recv_streak=0
    fi
  else
    low_fps_streak=0
    low_recv_streak=0
  fi
  if (( no_present_streak > max_no_present_streak )); then
    max_no_present_streak="$no_present_streak"
  fi

  printf '[soak] t=%(%H:%M:%S)T state=%s run_id=%s cfg=%s@%sfps/%sk recv=%s present=%s bps=%s active=%s streak(np/lf/lr/st)=%ss/%ss/%ss/%ss reconn=%s drops=%s\n' \
    -1 "$state" "$run_id" "$cfg_size" "$cfg_fps" "$cfg_bitrate" "$recv_fps" "$present_fps" "$recv_bps" "$scene_active" "$no_present_streak" "$low_fps_streak" "$low_recv_streak" "$starting_streak" "$reconnects" "$drops" \
    | tee -a "$LOG_FILE"

  if (( no_present_streak >= MAX_NO_PRESENT_SEC )); then
    echo "[soak] FAIL: no-present streak reached ${no_present_streak}s (recv_fps>=${MIN_RECV_FPS}, present_fps<=${MAX_PRESENT_FPS})" \
      | tee -a "$LOG_FILE"
    echo "[soak] log: $LOG_FILE" | tee -a "$LOG_FILE"
    exit 2
  fi
  if (( low_fps_streak >= LOW_FPS_MAX_SEC )); then
    echo "[soak] FAIL: low present_fps streak reached ${low_fps_streak}s (present_fps<${MIN_PRESENT_FPS_FLOOR} after warmup ${WARMUP_SEC}s)" \
      | tee -a "$LOG_FILE"
    echo "[soak] log: $LOG_FILE" | tee -a "$LOG_FILE"
    exit 6
  fi
  if (( low_recv_streak >= LOW_RECV_MAX_SEC )); then
    echo "[soak] FAIL: low recv_fps streak reached ${low_recv_streak}s (recv_fps<${MIN_RECV_FPS_FLOOR} after warmup ${WARMUP_SEC}s)" \
      | tee -a "$LOG_FILE"
    echo "[soak] log: $LOG_FILE" | tee -a "$LOG_FILE"
    exit 7
  fi
  if (( starting_streak >= MAX_STARTING_SEC )); then
    echo "[soak] FAIL: STARTING streak reached ${starting_streak}s (run_id=${run_id})" \
      | tee -a "$LOG_FILE"
    echo "[soak] likely stream restart is blocked (e.g. portal prompt / capture init)" | tee -a "$LOG_FILE"
    echo "[soak] log: $LOG_FILE" | tee -a "$LOG_FILE"
    exit 8
  fi

  sleep "$POLL_SEC"
done

if (( samples < MIN_SAMPLES )); then
  echo "[soak] FAIL: collected only ${samples} samples (< ${MIN_SAMPLES})" | tee -a "$LOG_FILE"
  echo "[soak] log: $LOG_FILE" | tee -a "$LOG_FILE"
  exit 4
fi
if (( streaming_samples < MIN_STREAMING_SAMPLES )); then
  echo "[soak] FAIL: collected only ${streaming_samples} STREAMING samples (< ${MIN_STREAMING_SAMPLES})" \
    | tee -a "$LOG_FILE"
  echo "[soak] log: $LOG_FILE" | tee -a "$LOG_FILE"
  exit 5
fi

echo "[soak] PASS duration=${DURATION_SEC}s samples=${samples} max_no_present_streak=${max_no_present_streak}s max_reconnects=${max_reconnects} max_drops=${max_drops}" \
  | tee -a "$LOG_FILE"
echo "[soak] log: $LOG_FILE" | tee -a "$LOG_FILE"
