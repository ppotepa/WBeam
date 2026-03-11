#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WBEAM_CONFIG_HELPER="${ROOT_DIR}/src/host/scripts/wbeam_config.sh"
if [[ -f "${WBEAM_CONFIG_HELPER}" ]]; then
  # shellcheck source=src/host/scripts/wbeam_config.sh
  source "${WBEAM_CONFIG_HELPER}"
  wbeam_load_config "${ROOT_DIR}"
fi
CONTROL_PORT="${WBEAM_CONTROL_PORT:-5001}"
TRAINER_APP_DIR="${ROOT_DIR}/src/apps/trainer-tauri"
TRAINER_FRONTEND_DIR="${TRAINER_APP_DIR}"
# Keep one-release fallback for pre-migration layout.
if [[ -f "${TRAINER_APP_DIR}/frontend/package.json" ]]; then
  TRAINER_FRONTEND_DIR="${TRAINER_APP_DIR}/frontend"
fi
START_SERVICE=0
VERBOSE=0
MODE="ui"
PASSTHRU=()
ORIGINAL_ARGS=("$@")

log() {
  printf '[trainer] %s\n' "$*"
}

has_graphical_env() {
  if [[ -n "${WAYLAND_DISPLAY:-}" ]]; then
    return 0
  fi
  if [[ -n "${DISPLAY:-}" ]]; then
    return 0
  fi
  return 1
}

ensure_graphical_context() {
  local -a launch_args
  launch_args=("$@")

  if has_graphical_env && [[ "${XDG_SESSION_TYPE:-}" != "tty" ]]; then
    return 0
  fi

  if [[ "${WBEAM_TRAINER_REEXEC:-0}" == "1" ]]; then
    log "failed to enter graphical session context (DISPLAY/WAYLAND missing)"
    log "run './runas-remote <user> ./trainer.sh --ui' and verify active GUI session"
    return 1
  fi

  if [[ "${WBEAM_TRAINER_AUTO_REEXEC:-1}" != "1" ]]; then
    log "no graphical session in current shell (DISPLAY/WAYLAND missing)"
    log "run './runas-remote <user> ./trainer.sh --ui' or set WBEAM_TRAINER_AUTO_REEXEC=1"
    return 1
  fi

  local target_user="${WBEAM_DEV_REMOTE_USER:-$(id -un)}"
  local runas="${ROOT_DIR}/runas-remote"
  if [[ ! -x "${runas}" ]]; then
    log "missing executable: ${runas}"
    return 1
  fi

  log "no graphical session in current shell; re-launching via runas-remote user=${target_user}"
  exec env \
    RUNAS_REMOTE_QUIET=1 \
    RUNAS_REMOTE_SESSION_REMOTE="${RUNAS_REMOTE_SESSION_REMOTE:-no}" \
    WBEAM_TRAINER_REEXEC=1 \
    "${runas}" "${target_user}" "${ROOT_DIR}/trainer.sh" -- "${launch_args[@]}"
}

apply_tauri_stability_env() {
  local xa

  export WEBKIT_DISABLE_DMABUF_RENDERER="${WEBKIT_DISABLE_DMABUF_RENDERER:-1}"

  if [[ "${XDG_SESSION_TYPE:-}" == "wayland" && "${WBEAM_TAURI_NATIVE_WAYLAND:-0}" != "1" ]]; then
    if [[ -n "${DISPLAY:-}" ]]; then
      export GDK_BACKEND="${GDK_BACKEND:-x11}"
      export WINIT_UNIX_BACKEND="${WINIT_UNIX_BACKEND:-x11}"
      log "wayland detected with DISPLAY available; forcing x11 backend for Tauri stability"
    else
      export GDK_BACKEND="${GDK_BACKEND:-wayland}"
      export WINIT_UNIX_BACKEND="${WINIT_UNIX_BACKEND:-wayland}"
      log "wayland-only session detected (DISPLAY missing); using native wayland backend"
    fi
  fi

  if [[ "${GDK_BACKEND:-}" == "x11" && -z "${XAUTHORITY:-}" ]]; then
    xa=""
    if [[ -n "${XDG_RUNTIME_DIR:-}" ]]; then
      for candidate in "${XDG_RUNTIME_DIR}"/xauth_*; do
        [[ -f "${candidate}" ]] || continue
        xa="${candidate}"
        break
      done
    fi
    if [[ -z "${xa}" && -n "${HOME:-}" && -f "${HOME}/.Xauthority" ]]; then
      xa="${HOME}/.Xauthority"
    fi
    if [[ -n "${xa}" ]]; then
      export XAUTHORITY="${xa}"
    fi
  fi
}

usage() {
  cat <<'EOF'
Usage: ./trainer.sh [options] [-- args...]

Options:
  --start-service          Try to start daemon service if health check fails.
  --control-port <port>   Control API port (default: 5001 or WBEAM_CONTROL_PORT).
  --ui                    Launch Trainer Tauri desktop app (default).
  --web                   Launch web-only Vite dev server.
  --wizard                Run wizard bridge mode.
  --verbose               Print extra diagnostics.
  -h, --help              Show help.

Modes:
  --ui      -> npm run tauri:dev in src/apps/trainer-tauri/frontend
  --web     -> npm run dev in src/apps/trainer-tauri/frontend
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

if [[ "${MODE}" == "ui" || "${MODE}" == "web" ]]; then
  log "launching trainer UI (${TRAINER_FRONTEND_DIR}, mode=${MODE})"
  if [[ ! -f "${TRAINER_FRONTEND_DIR}/package.json" ]]; then
    log "trainer UI app is missing"
    exit 1
  fi
  if ! command -v npm >/dev/null 2>&1; then
    log "npm is required to run trainer UI"
    exit 1
  fi
  if [[ ! -d "${TRAINER_FRONTEND_DIR}/node_modules" ]]; then
    log "node_modules missing; running npm ci"
    (cd "${TRAINER_FRONTEND_DIR}" && npm ci >/dev/null 2>&1 || npm install >/dev/null 2>&1)
  fi
  log "log=${LOG_FILE}"
  set +e
  (
    cd "${TRAINER_FRONTEND_DIR}"
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
