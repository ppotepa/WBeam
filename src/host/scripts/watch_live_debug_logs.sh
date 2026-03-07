#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../../.." && pwd)"
LOG_DIR="${WBEAM_DEBUG_LOG_DIR:-$ROOT_DIR/logs}"
mkdir -p "$LOG_DIR"

TS="$(date +%Y%m%d-%H%M%S)"
next_run_id() {
  local day counter_file n
  day="$(date +%Y%m%d)"
  counter_file="$LOG_DIR/.run.${day}.counter"
  n=0
  if [[ -f "$counter_file" ]]; then
    n="$(tr -d '[:space:]' < "$counter_file" 2>/dev/null || echo 0)"
  fi
  if [[ ! "$n" =~ ^[0-9]+$ ]]; then
    n=0
  fi
  n=$((n + 1))
  printf '%s' "$n" > "$counter_file"
  printf '%04d' "$n"
}

RUN_ID="$(next_run_id)"
ANDROID_OUT="$LOG_DIR/${TS}.adb.${RUN_ID}.log"
COMBINED_OUT="$LOG_DIR/${TS}.live.${RUN_ID}.log"

HOST_FILE="${1:-}"
if [[ -z "$HOST_FILE" ]]; then
  HOST_FILE="$(ls -1t "$LOG_DIR"/*.service.*.log 2>/dev/null | head -n 1 || true)"
fi

if [[ -z "$HOST_FILE" ]]; then
  echo "[live] no host debug log found in $LOG_DIR" >&2
  echo "[live] start daemon first: ./devtool service up" >&2
  exit 1
fi

if [[ -f "$HOST_FILE" ]]; then
  now_ts="$(date +%s)"
  file_ts="$(stat -c %Y "$HOST_FILE" 2>/dev/null || echo "$now_ts")"
  age_s=$((now_ts - file_ts))
  if (( age_s > 300 )); then
    echo "[live] warning: host log looks stale (${age_s}s old): $HOST_FILE" >&2
    echo "[live] run fresh daemon first: ./devtool service up" >&2
  fi
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "[live] adb not found" >&2
  exit 1
fi

cleanup() {
  [[ -n "${ADB_PID:-}" ]] && kill "$ADB_PID" >/dev/null 2>&1 || true
  [[ -n "${TAIL_PID:-}" ]] && kill "$TAIL_PID" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

echo "[live] host log: $HOST_FILE" | tee -a "$COMBINED_OUT"
echo "[live] android log: $ANDROID_OUT" | tee -a "$COMBINED_OUT"
echo "[live] combined log: $COMBINED_OUT" | tee -a "$COMBINED_OUT"

tail -n 0 -F "$HOST_FILE" \
  | sed -u 's/^/[host] /' \
  | tee -a "$COMBINED_OUT" \
  | awk '
      BEGIN {
        reset="\033[0m";
        white="\033[37m";
        yellow="\033[33m";
        red="\033[31m";
      }
      {
        color=white;
        line=tolower($0);
        if (line ~ /(warn|warning)/) color=yellow;
        if (line ~ /(error|fatal|panic|exception)/) color=red;
        printf "%s%s%s\n", color, $0, reset;
        fflush();
      }
    ' &
TAIL_PID=$!

adb logcat -v time -s WBeamMain:V WBeamService:V WBeamUsbAttach:V AndroidRuntime:E '*:S' \
  | grep -i --line-buffered -E "WBeam|FATAL EXCEPTION|IO error|IOException|SocketException|ConnectException" \
  | sed -u 's/^/[android] /' \
  | tee -a "$ANDROID_OUT" \
  | tee -a "$COMBINED_OUT" \
  | awk '
      BEGIN {
        reset="\033[0m";
        gray="\033[90m";
        orange="\033[38;5;208m";
        dark_red="\033[31m";
        dark_green="\033[32m";
      }
      {
        color=gray;
        if ($0 ~ / [I]\/|\) I\//) color=dark_green;
        if ($0 ~ / [W]\/|\) W\//) color=orange;
        if ($0 ~ / [EAF]\/|\) E\/|\) A\/|\) F\// || tolower($0) ~ /(fatal exception|ioexception|io error|socketexception|connectexception)/) color=dark_red;
        printf "%s%s%s\n", color, $0, reset;
        fflush();
      }
    ' &
ADB_PID=$!

wait -n "$TAIL_PID" "$ADB_PID"
