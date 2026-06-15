#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${WBEAM_E2E_GUEST_ROOT:-$(pwd)}"
BACKEND="${1:-${WBEAM_E2E_BACKEND:-benchmark_game}}"
shift || true

cd "$ROOT_DIR"
exec ./install-wbeam --yes --backend "$BACKEND" --skip-device "$@"
