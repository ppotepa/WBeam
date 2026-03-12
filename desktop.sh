#!/usr/bin/env bash
set -euo pipefail

SCRIPT_PATH="$(readlink -f "${BASH_SOURCE[0]}")"
ROOT_DIR="$(cd "$(dirname "$SCRIPT_PATH")" && pwd)"
WBEAM_CONFIG_HELPER="$ROOT_DIR/host/scripts/wbeam_config.sh"
WBEAM_GUI_HELPER="$ROOT_DIR/host/scripts/gui_session.sh"
if [[ -f "$WBEAM_CONFIG_HELPER" ]]; then
  # shellcheck source=host/scripts/wbeam_config.sh
  source "$WBEAM_CONFIG_HELPER"
  wbeam_load_config "$ROOT_DIR"
fi
if [[ -f "$WBEAM_GUI_HELPER" ]]; then
  # shellcheck source=host/scripts/gui_session.sh
  source "$WBEAM_GUI_HELPER"
fi
TAURI_DIR="$ROOT_DIR/desktop/apps/desktop-tauri"
LOG_DIR="$ROOT_DIR/logs"

ensure_log_dir() {
  mkdir -p "$LOG_DIR"
}

next_run_id() {
  local day counter_file n
  ensure_log_dir
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

new_log_file() {
  local domain ts run_id
  domain="$1"
  ts="$(date +%Y%m%d-%H%M%S)"
  run_id="$(next_run_id)"
  echo "$LOG_DIR/${ts}.${domain}.${run_id}.log"
}

usage() {
  cat <<'EOF'
WBeam Desktop GUI launcher

Usage:
  ./desktop.sh
  ./desktop.sh --release
EOF
}

desktop_ensure_graphical_context() {
  if declare -F wbeam_ensure_graphical_context >/dev/null 2>&1; then
    wbeam_ensure_graphical_context \
      "$ROOT_DIR" \
      "desktop.sh" \
      "desktop" \
      "WBEAM_DESKTOP_AUTO_REEXEC" \
      "WBEAM_DESKTOP_REEXEC" \
      "$@"
    return $?
  fi
  echo "[desktop] missing GUI helper: ${WBEAM_GUI_HELPER}" >&2
  return 1
}

ensure_supported_node() {
  if ! command -v node >/dev/null 2>&1; then
    echo "[desktop] node is required (recommended v20 or v22 LTS)." >&2
    return 1
  fi
  local major
  major="$(node -p 'parseInt(process.versions.node.split(".")[0],10)' 2>/dev/null || echo 0)"
  if [[ ! "$major" =~ ^[0-9]+$ ]]; then
    echo "[desktop] unable to detect node version." >&2
    return 1
  fi
  if (( major < 18 )); then
    echo "[desktop] unsupported node version: $(node -v)" >&2
    echo "[desktop] minimum supported is Node 18+, recommended 20.x or 22.x LTS." >&2
    return 1
  fi
  if (( major > 22 )); then
    echo "[desktop] warning: using newer Node version $(node -v); 20.x/22.x LTS is recommended." >&2
    if [[ "${WBEAM_DESKTOP_STRICT_NODE:-0}" == "1" ]]; then
      echo "[desktop] strict mode enabled (WBEAM_DESKTOP_STRICT_NODE=1), refusing to start." >&2
      return 1
    fi
  fi
}

desktop_adb_preflight() {
  if ! command -v adb >/dev/null 2>&1; then
    echo "[desktop] warning: adb not found in PATH; Android device discovery will be unavailable." >&2
    return 0
  fi
  if ! adb start-server >/dev/null 2>&1; then
    echo "[desktop] warning: failed to start adb server; Android device discovery may be unavailable." >&2
    return 0
  fi
}

desktop_dev_port_pid() {
  if command -v lsof >/dev/null 2>&1; then
    lsof -tiTCP:1420 -sTCP:LISTEN 2>/dev/null | head -n1 || true
    return 0
  fi
  if command -v ss >/dev/null 2>&1; then
    ss -lptn 'sport = :1420' 2>/dev/null \
      | sed -n 's/.*pid=\([0-9][0-9]*\).*/\1/p' \
      | head -n1 || true
    return 0
  fi
  echo ""
}

desktop_kill_stale_dev() {
  local pid cmd
  pid="$(desktop_dev_port_pid)"
  if [[ -z "$pid" ]]; then
    return 0
  fi

  cmd="$(ps -p "$pid" -o args= 2>/dev/null || true)"
  if [[ "$cmd" =~ (desktop-tauri|wbeam-desktop-tauri|tauri\ dev|vite\ --port\ 1420) ]]; then
    echo "[desktop] found stale desktop dev process on :1420 (pid=$pid), terminating..."
    kill "$pid" 2>/dev/null || true
    sleep 1
    pid="$(desktop_dev_port_pid)"
    if [[ -n "$pid" ]]; then
      kill -9 "$pid" 2>/dev/null || true
      sleep 1
    fi
    return 0
  fi

  echo "[desktop] port 1420 is in use by non-WBeam process (pid=$pid)." >&2
  echo "[desktop] command: $cmd" >&2
  echo "[desktop] free port 1420 and retry." >&2
  return 1
}

desktop_kill_stale_tauri_app() {
  local pids pid cmd remaining
  pids="$(
    ps -eo pid=,args= 2>/dev/null \
      | awk '/wbeam-desktop-tauri/ && $0 !~ /awk/ {print $1}'
  )"
  if [[ -z "${pids//[[:space:]]/}" ]]; then
    return 0
  fi

  echo "[desktop] found stale desktop tauri process(es): $(echo "$pids" | tr '\n' ' ' | sed 's/[[:space:]]*$//')"
  while read -r pid; do
    [[ -n "${pid:-}" ]] || continue
    cmd="$(ps -p "$pid" -o args= 2>/dev/null || true)"
    [[ "$cmd" == *"wbeam-desktop-tauri"* ]] || continue
    kill "$pid" 2>/dev/null || true
  done <<< "$pids"

  sleep 1
  remaining="$(
    ps -eo pid=,args= 2>/dev/null \
      | awk '/wbeam-desktop-tauri/ && $0 !~ /awk/ {print $1}'
  )"
  if [[ -n "${remaining//[[:space:]]/}" ]]; then
    while read -r pid; do
      [[ -n "${pid:-}" ]] || continue
      cmd="$(ps -p "$pid" -o args= 2>/dev/null || true)"
      [[ "$cmd" == *"wbeam-desktop-tauri"* ]] || continue
      kill -9 "$pid" 2>/dev/null || true
    done <<< "$remaining"
  fi
}

desktop_apply_tauri_stability_env() {
  local xa

  # Tauri/WebKit can be unstable on some Wayland stacks in dev mode.
  export WEBKIT_DISABLE_DMABUF_RENDERER="${WEBKIT_DISABLE_DMABUF_RENDERER:-1}"

  if [[ "${XDG_SESSION_TYPE:-}" == "wayland" && "${WBEAM_TAURI_NATIVE_WAYLAND:-0}" != "1" ]]; then
    if [[ -n "${DISPLAY:-}" ]]; then
      export GDK_BACKEND="${GDK_BACKEND:-x11}"
      export WINIT_UNIX_BACKEND="${WINIT_UNIX_BACKEND:-x11}"
      echo "[desktop] wayland detected with DISPLAY available; forcing x11 backend for Tauri stability."
    else
      export GDK_BACKEND="${GDK_BACKEND:-wayland}"
      export WINIT_UNIX_BACKEND="${WINIT_UNIX_BACKEND:-wayland}"
      echo "[desktop] wayland-only session detected (DISPLAY missing); using native wayland backend."
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

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

desktop_ensure_graphical_context "$@"

run_dev() {
  local log_file
  ensure_supported_node
  desktop_adb_preflight
  desktop_kill_stale_tauri_app
  desktop_kill_stale_dev
  log_file="$(new_log_file "desktop")"
  echo "[desktop] log=$log_file"
  (
    cd "$TAURI_DIR"
    if [[ ! -d node_modules ]]; then
      npm install
    fi
    desktop_apply_tauri_stability_env
    exec npm run tauri dev
  ) 2>&1 | tee -a "$log_file"
}

run_release() {
  local log_file
  ensure_supported_node
  desktop_adb_preflight
  desktop_kill_stale_tauri_app
  log_file="$(new_log_file "desktop")"
  echo "[desktop] log=$log_file"
  (
    cd "$TAURI_DIR"
    if [[ ! -d node_modules ]]; then
      npm install
    fi
    npm run build
    desktop_apply_tauri_stability_env
    exec cargo run --release --manifest-path src-tauri/Cargo.toml
  ) 2>&1 | tee -a "$log_file"
}

case "${1:-}" in
  ""|--dev)
    run_dev
    ;;
  --release)
    run_release
    ;;
  *)
    echo "[desktop] unknown arg: $1" >&2
    usage >&2
    exit 1
    ;;
esac
