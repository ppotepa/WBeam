#!/usr/bin/env bash
set -euo pipefail

SCRIPT_PATH="$(readlink -f "${BASH_SOURCE[0]}")"
ROOT_DIR="$(cd "$(dirname "$SCRIPT_PATH")" && pwd)"
TAURI_DIR="$ROOT_DIR/src/apps/desktop-tauri"
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

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

run_dev() {
  local log_file
  log_file="$(new_log_file "desktop")"
  echo "[desktop] log=$log_file"
  (
    cd "$TAURI_DIR"
    if [[ ! -d node_modules ]]; then
      npm install
    fi
    exec npm run tauri dev
  ) 2>&1 | tee -a "$log_file"
}

run_release() {
  local log_file
  log_file="$(new_log_file "desktop")"
  echo "[desktop] log=$log_file"
  (
    cd "$TAURI_DIR"
    if [[ ! -d node_modules ]]; then
      npm install
    fi
    npm run build
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
