#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_FILE="$ROOT_DIR/.wbeam_android_pipeline"

if [[ -f "$STATE_FILE" ]]; then
  mode="$(tr -d '[:space:]' < "$STATE_FILE" 2>/dev/null || true)"
else
  mode="modern"
fi

case "$mode" in
  modern|legacy) ;;
  *) mode="modern" ;;
esac

echo "[wbeam] android pipeline: $mode"
echo "[wbeam] state file: $STATE_FILE"
