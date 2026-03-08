#!/usr/bin/env bash
set -euo pipefail

SCRIPT_PATH="$(readlink -f "${BASH_SOURCE[0]}")"
ROOT_DIR="$(cd "$(dirname "$SCRIPT_PATH")" && pwd)"
TAURI_DIR="$ROOT_DIR/src/apps/desktop-tauri"

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
  (
    cd "$TAURI_DIR"
    if [[ ! -d node_modules ]]; then
      npm install
    fi
    exec npm run tauri dev
  )
}

run_release() {
  (
    cd "$TAURI_DIR"
    if [[ ! -d node_modules ]]; then
      npm install
    fi
    npm run build
    exec cargo run --release --manifest-path src-tauri/Cargo.toml
  )
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

