#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_FILE="$ROOT_DIR/.wbeam_android_pipeline"
mode="${1:-}"

case "$mode" in
  modern|legacy)
    printf '%s\n' "$mode" > "$STATE_FILE"
    echo "[wbeam] android pipeline set: $mode"
    echo "[wbeam] state file: $STATE_FILE"
    ;;
  "")
    echo "usage: $(basename "$0") <modern|legacy>" >&2
    exit 1
    ;;
  *)
    echo "invalid mode: $mode (expected: modern|legacy)" >&2
    exit 1
    ;;
esac
