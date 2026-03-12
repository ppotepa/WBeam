#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WBEAM_CONFIG_HELPER="${ROOT_DIR}/host/scripts/wbeam_config.sh"
WBEAM_GUI_HELPER="${ROOT_DIR}/host/scripts/gui_session.sh"
if [[ -f "${WBEAM_CONFIG_HELPER}" ]]; then
  # shellcheck source=host/scripts/wbeam_config.sh
  source "${WBEAM_CONFIG_HELPER}"
  wbeam_load_config "${ROOT_DIR}"
fi
if [[ -f "${WBEAM_GUI_HELPER}" ]]; then
  # shellcheck source=host/scripts/gui_session.sh
  source "${WBEAM_GUI_HELPER}"
fi
CONTROL_PORT="${WBEAM_CONTROL_PORT:-5001}"
START_SERVICE="${WBEAM_TRAINER_AUTO_START_SERVICE:-1}"
VERBOSE=0
MODE="ui"
PASSTHRU=()
ORIGINAL_ARGS=("$@")
BOOT_LOG_DIR="${ROOT_DIR}/logs/trainer"
DAEMON_PID_FILE="${ROOT_DIR}/.logs/trainer-daemon.pid"
HEALTH_WAIT_ATTEMPTS="${WBEAM_TRAINER_HEALTH_WAIT_ATTEMPTS:-80}"
HEALTH_WAIT_MS="${WBEAM_TRAINER_HEALTH_WAIT_MS:-500}"
DAEMON_START_MODE=""

log() {
  printf '[trainer] %s\n' "$*"
}

ensure_graphical_context() {
  if declare -F wbeam_ensure_graphical_context >/dev/null 2>&1; then
    wbeam_ensure_graphical_context \
      "${ROOT_DIR}" \
      "trainer.sh" \
      "trainer" \
      "WBEAM_TRAINER_AUTO_REEXEC" \
      "WBEAM_TRAINER_REEXEC" \
      "$@"
    return $?
  fi
  log "missing GUI helper: ${WBEAM_GUI_HELPER}"
  return 1
}

apply_tauri_stability_env() {
  if declare -F wbeam_apply_tauri_stability_env >/dev/null 2>&1; then
    wbeam_apply_tauri_stability_env "trainer"
    return 0
  fi
  log "missing GUI helper: ${WBEAM_GUI_HELPER}"
  return 1
}

usage() {
  cat <<'EOF'
Usage: ./trainer.sh [options] [-- args...]

Options:
  --start-service          Try to start daemon service if health check fails.
  --no-start-service       Do not attempt daemon auto-start on health failure.
  --control-port <port>   Control API port (default: 5001 or WBEAM_CONTROL_PORT).
  --ui                    Launch Trainer Tauri desktop app (default).
  --web                   Launch web-only Vite dev server.
  --wizard                Run wizard bridge mode.
  --verbose               Print extra diagnostics.
  -h, --help              Show help.

Modes:
  --ui      -> npm run tauri:dev in desktop/apps/trainer-tauri
  --web     -> npm run dev in desktop/apps/trainer-tauri
  --wizard  -> ./wbeam train wizard

Remaining args are forwarded to selected mode command.
EOF
}

wait_for_health() {
  local attempts="${1:-12}"
  local pause_ms="${2:-500}"
  local i=0
  while (( i < attempts )); do
    if check_health >/dev/null 2>&1; then
      return 0
    fi
    i=$((i + 1))
    sleep "$(awk "BEGIN { printf \"%.3f\", ${pause_ms}/1000 }")"
  done
  return 1
}

start_daemon_via_systemd() {
  local unit="${WBEAM_DAEMON_SERVICE_NAME:-wbeam-daemon}"
  local runtime_dir="/run/user/$(id -u)"
  if ! command -v systemctl >/dev/null 2>&1; then
    return 1
  fi
  if [[ ! -d "${runtime_dir}" ]]; then
    return 1
  fi
  if [[ "$unit" != *.service ]]; then
    unit="${unit}.service"
  fi
  XDG_RUNTIME_DIR="${runtime_dir}" DBUS_SESSION_BUS_ADDRESS="unix:path=${runtime_dir}/bus" \
    systemctl --user start "${unit}" >/dev/null 2>&1 || return 1
  return 0
}

start_daemon_background_host_run() {
  mkdir -p "${BOOT_LOG_DIR}" "${ROOT_DIR}/.logs"
  local daemon_log="${BOOT_LOG_DIR}/$(date -u +%Y%m%d-%H%M%S).trainer-daemon.log"
  log "starting daemon via background host run (log=${daemon_log})"
  nohup "${ROOT_DIR}/wbeam" host run >"${daemon_log}" 2>&1 &
  local pid=$!
  printf '%s\n' "${pid}" > "${DAEMON_PID_FILE}"
  disown "${pid}" >/dev/null 2>&1 || true
  return 0
}

start_daemon_best_effort() {
  if start_daemon_via_systemd; then
    DAEMON_START_MODE="systemd"
    log "daemon start requested via systemd user service"
    return 0
  fi
  log "systemd user service start unavailable; falling back to background host run"
  DAEMON_START_MODE="host-run"
  start_daemon_background_host_run
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --start-service)
      START_SERVICE=1
      shift
      ;;
    --no-start-service)
      START_SERVICE=0
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
    --web)
      MODE="web"
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

if [[ "${MODE}" == "ui" ]]; then
  ensure_graphical_context "${ORIGINAL_ARGS[@]}"
fi

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
    if start_daemon_best_effort; then
      if ! wait_for_health "${HEALTH_WAIT_ATTEMPTS}" "${HEALTH_WAIT_MS}"; then
        log "daemon start attempted but health is still unreachable"
        if [[ "${DAEMON_START_MODE}" == "systemd" ]]; then
          log "systemd start did not reach healthy API; retrying via background host run fallback"
          if start_daemon_background_host_run; then
            DAEMON_START_MODE="host-run"
            if ! wait_for_health "${HEALTH_WAIT_ATTEMPTS}" "${HEALTH_WAIT_MS}"; then
              log "fallback host run attempted but health is still unreachable"
            fi
          else
            log "fallback host run start failed"
          fi
        fi
      fi
    else
      log "failed to start daemon service"
    fi
  else
    log "auto-start disabled (use --start-service or WBEAM_TRAINER_AUTO_START_SERVICE=1)"
  fi
fi

if ! health_msg="$(check_health 2>/dev/null)"; then
  log "service is not healthy on ${health_url}"
  log "hint: run './wbeam host run' (foreground) or retry './trainer.sh --start-service'"
  exit 1
fi

log "service health: ${health_msg}"

LOG_DIR="${ROOT_DIR}/logs/trainer"
mkdir -p "${LOG_DIR}"
STAMP="$(date -u +%Y%m%d-%H%M%S)"
LOG_FILE="${LOG_DIR}/${STAMP}.trainer.log"

if [[ "${MODE}" == "ui" || "${MODE}" == "web" ]]; then
  log "launching trainer UI (desktop/apps/trainer-tauri, mode=${MODE})"
  if [[ ! -f "${ROOT_DIR}/desktop/apps/trainer-tauri/package.json" ]]; then
    log "trainer UI app is missing"
    exit 1
  fi
  if ! command -v npm >/dev/null 2>&1; then
    log "npm is required to run trainer UI"
    exit 1
  fi
  if [[ ! -d "${ROOT_DIR}/desktop/apps/trainer-tauri/node_modules" ]]; then
    log "node_modules missing; running npm ci"
    (cd "${ROOT_DIR}/desktop/apps/trainer-tauri" && npm ci >/dev/null 2>&1 || npm install >/dev/null 2>&1)
  fi
  log "log=${LOG_FILE}"
  set +e
  (
    cd "${ROOT_DIR}/desktop/apps/trainer-tauri"
    if [[ "${MODE}" == "ui" ]]; then
      apply_tauri_stability_env
      npm run tauri:dev -- "${PASSTHRU[@]}"
    else
      npm run dev -- "${PASSTHRU[@]}"
    fi
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
