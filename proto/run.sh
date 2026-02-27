#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

if ! command -v python3 >/dev/null 2>&1; then
  printf '[proto] %s\n' "python3 not found in PATH"
  exit 1
fi

exec python3 "$ROOT_DIR/run.py" "$@"
