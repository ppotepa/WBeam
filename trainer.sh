#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONTROL_PORT="${WBEAM_CONTROL_PORT:-5001}"
START_SERVICE=0
VERBOSE=0
MODE="ui"
PASSTHRU=()

log() {
  printf '[trainer] %s\n' "$*"
}

usage() {
  cat <<'EOF'
Usage: ./trainer.sh [options] [-- args...]

Options:
  --start-service          Try to start daemon service if health check fails.
  --control-port <port>   Control API port (default: 5001 or WBEAM_CONTROL_PORT).
  --ui                    Launch Trainer UI app (default).
  --wizard                Run wizard bridge mode.
  --verbose               Print extra diagnostics.
  -h, --help              Show help.

Modes:
  --ui      -> npm run dev in src/apps/trainer-tauri
  --wizard  -> ./wbeam train wizard

Remaining args are forwarded to selected mode command.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --start-service)
      START_SERVICE=1
      shift
      ;;
    --control-port)
      CONTROL_PORT="${2:-}"
      if [[ -z "$CONTROL_PORT" ]]; then
        log "missing value for --control-port"
        exit 2
      fi
      shift 2
      ;;
    --verbose)
      VERBOSE=1
      shift
      ;;
    --ui)
      MODE="ui"
      shift
      ;;
    --wizard)
      MODE="wizard"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      PASSTHRU+=("$@")
      break
      ;;
    *)
      PASSTHRU+=("$1")
      shift
      ;;
  esac
done

health_url="http://127.0.0.1:${CONTROL_PORT}/v1/health"

check_health() {
  python3 - "$health_url" <<'PY'
from __future__ import annotations
import json
import sys
from urllib import error, request

url = sys.argv[1]
try:
    with request.urlopen(url, timeout=1.6) as resp:
        body = resp.read().decode('utf-8', errors='replace')
except Exception:
    raise SystemExit(1)

try:
    data = json.loads(body) if body else {}
except Exception:
    raise SystemExit(1)

if not isinstance(data, dict):
    raise SystemExit(1)

ok = data.get('ok')
print(f"ok={ok} state={data.get('state')} build={data.get('build_revision')}")
raise SystemExit(0 if ok else 1)
PY
}

if [[ "$VERBOSE" == "1" ]]; then
  log "root=${ROOT_DIR}"
  log "control_port=${CONTROL_PORT}"
  log "health_url=${health_url}"
fi

if ! health_msg="$(check_health 2>/dev/null)"; then
  log "service health: unreachable"
  if [[ "$START_SERVICE" == "1" ]]; then
    log "starting daemon via ./wbeam daemon up"
    if ! "${ROOT_DIR}/wbeam" daemon up; then
      log "failed to start daemon service"
      exit 1
    fi
    sleep 1
  fi
fi

if ! health_msg="$(check_health 2>/dev/null)"; then
  log "service is not healthy on ${health_url}"
  log "hint: run './wbeam daemon up' or retry with --start-service"
  exit 1
fi

log "service health: ${health_msg}"

LOG_DIR="${ROOT_DIR}/logs/trainer"
mkdir -p "${LOG_DIR}"
STAMP="$(date -u +%Y%m%d-%H%M%S)"
LOG_FILE="${LOG_DIR}/${STAMP}.trainer.log"

if [[ "${MODE}" == "ui" ]]; then
  log "launching trainer UI (src/apps/trainer-tauri)"
  if [[ ! -f "${ROOT_DIR}/src/apps/trainer-tauri/package.json" ]]; then
    log "trainer UI app is missing"
    exit 1
  fi
  if ! command -v npm >/dev/null 2>&1; then
    log "npm is required to run trainer UI"
    exit 1
  fi
  log "log=${LOG_FILE}"
  set +e
  (
    cd "${ROOT_DIR}/src/apps/trainer-tauri"
    npm run dev -- "${PASSTHRU[@]}"
  ) 2>&1 | tee -a "${LOG_FILE}"
  rc=${PIPESTATUS[0]}
  set -e
  exit "${rc}"
fi

log "launching trainer (wizard bridge mode)"
log "log=${LOG_FILE}"

set +e
"${ROOT_DIR}/wbeam" train wizard --control-port "${CONTROL_PORT}" "${PASSTHRU[@]}" 2>&1 | tee -a "${LOG_FILE}"
rc=${PIPESTATUS[0]}
set -e

exit "${rc}"
