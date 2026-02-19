#!/usr/bin/env bash
set -euo pipefail

CONTROL_PORT="${1:-5001}"
STREAM_PORT="${2:-5000}"
LOCK_FILE="${WBEAM_LOCK_FILE:-/tmp/wbeamd.lock}"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

LOG_DIR="${WBEAM_DEBUG_LOG_DIR:-$ROOT_DIR/host/rust/logs}"
mkdir -p "$LOG_DIR"
TS="$(date +%Y%m%d-%H%M%S)"
LOG_FILE="${WBEAM_DEBUG_LOG_FILE:-$LOG_DIR/wbeamd-debug-$TS.log}"
SESSION_RUST_LOG_DIR="$LOG_DIR/runs/$TS"
mkdir -p "$SESSION_RUST_LOG_DIR"

if command -v systemctl >/dev/null 2>&1; then
  systemctl --user stop wbeamd-rust.service >/dev/null 2>&1 || true
fi

export WBEAM_DAEMON_IMPL="${WBEAM_DAEMON_IMPL:-rust}"
export RUST_LOG="${RUST_LOG:-debug}"
export RUST_BACKTRACE="${RUST_BACKTRACE:-1}"
export WBEAM_START_TIMEOUT_SEC="${WBEAM_START_TIMEOUT_SEC:-45}"
export WBEAM_LOCK_FILE="$LOCK_FILE"
export WBEAM_RUST_LOG_DIR="$SESSION_RUST_LOG_DIR"

existing_pid="$(pgrep -f "wbeamd-server --control-port ${CONTROL_PORT} --stream-port ${STREAM_PORT}" | head -n 1 || true)"
if [[ -n "$existing_pid" ]]; then
  kill "$existing_pid" >/dev/null 2>&1 || true
  for _ in {1..20}; do
    if ! kill -0 "$existing_pid" >/dev/null 2>&1; then
      break
    fi
    sleep 0.1
  done
fi

if [[ -f "$LOCK_FILE" ]]; then
  lock_pid="$(tr -cd '0-9' < "$LOCK_FILE" || true)"
  if [[ -n "$lock_pid" ]] && kill -0 "$lock_pid" >/dev/null 2>&1; then
    :
  else
    rm -f "$LOCK_FILE"
  fi
fi

{
  echo "[wbeam-debug] starting at $(date -Iseconds)"
  echo "[wbeam-debug] root=$ROOT_DIR"
  echo "[wbeam-debug] control_port=$CONTROL_PORT stream_port=$STREAM_PORT"
  echo "[wbeam-debug] lock_file=$LOCK_FILE"
  echo "[wbeam-debug] daemon_impl=$WBEAM_DAEMON_IMPL"
  echo "[wbeam-debug] RUST_LOG=$RUST_LOG RUST_BACKTRACE=$RUST_BACKTRACE"
  echo "[wbeam-debug] rust_log_dir=$SESSION_RUST_LOG_DIR"
  echo "[wbeam-debug] logfile=$LOG_FILE"
} | tee -a "$LOG_FILE"

cleanup() {
  [[ -n "${ADB_PID:-}" ]] && kill "$ADB_PID" >/dev/null 2>&1 || true
  [[ -n "${RUST_TAIL_PID:-}" ]] && kill "$RUST_TAIL_PID" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

RUST_TRACE_FILE="$SESSION_RUST_LOG_DIR/wbeamd-rust.log.$(date +%Y-%m-%d)"
tail -n 0 -F "$RUST_TRACE_FILE" 2>/dev/null \
  | sed -u 's/^/[rust] /' \
  | tee -a "$LOG_FILE" \
  | awk '
      BEGIN {
        reset="\033[0m";
        white="\033[37m";
        green="\033[32m";
        yellow="\033[33m";
        red="\033[31m";
      }
      {
        color=white;
        if ($0 ~ / INFO /) color=green;
        if ($0 ~ / WARN /) color=yellow;
        if ($0 ~ / ERROR / || tolower($0) ~ /(fatal|panic|exception)/) color=red;
        printf "%s%s%s\n", color, $0, reset;
        fflush();
      }
    ' &
RUST_TAIL_PID=$!

if command -v adb >/dev/null 2>&1; then
  adb logcat -v time -s WBeamMain:I WBeamService:E WBeamUsbAttach:E AndroidRuntime:E '*:S' \
    | grep -i --line-buffered -E "huddbg|offline|ioexception|io error|stream error|failed|socketexception|connectexception|fatal exception|connected to host|daemon poll failed|androidruntime" \
    | sed -u 's/^/[android] /' \
    | tee -a "$LOG_FILE" \
    | awk '
        BEGIN {
          reset="\033[0m";
          gray="\033[90m";
          dark_green="\033[32m";
          yellow="\033[33m";
          dark_red="\033[31m";
        }
        {
          color=gray;
          if ($0 ~ / [I]\/|\) I\//) color=dark_green;
          if ($0 ~ / [W]\/|\) W\//) color=yellow;
          if ($0 ~ / [EAF]\/|\) E\/|\) A\/|\) F\// || tolower($0) ~ /(fatal exception|ioexception|io error|socketexception|connectexception|offline|failed)/) color=dark_red;
          printf "%s%s%s\n", color, $0, reset;
          fflush();
        }
      ' &
  ADB_PID=$!
else
  echo "[wbeam-debug] adb not found, android live events disabled" | tee -a "$LOG_FILE"
fi

set +e
"$SCRIPT_DIR/run_wbeamd.sh" "$CONTROL_PORT" "$STREAM_PORT" 2>&1 \
  | tee -a "$LOG_FILE" \
  | awk '
      BEGIN {
        reset="\033[0m";
        white="\033[37m";
        green="\033[32m";
        yellow="\033[33m";
        red="\033[31m";
      }
      {
        color=white;
        line=tolower($0);
        if (line ~ /(started|ready|connected|streaming|ok)/) color=green;
        if (line ~ /(warn|warning|retry|timeout)/) color=yellow;
        if (line ~ /(error|fatal|panic|failed|exception)/) color=red;
        printf "%s%s%s\n", color, $0, reset;
        fflush();
      }
    '
host_exit=${PIPESTATUS[0]}
set -e
exit "$host_exit"
