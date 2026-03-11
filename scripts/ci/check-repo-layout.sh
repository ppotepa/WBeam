#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STRICT="${WBEAM_LAYOUT_STRICT:-0}"
failed=0

require_path() {
  local rel="$1"
  if [[ ! -e "${ROOT}/${rel}" ]]; then
    echo "[layout][ERROR] missing required path: ${rel}"
    failed=1
  fi
}

echo "[layout] root=${ROOT}"
echo "[layout] strict=${STRICT}"

required_paths=(
  "android/README.md"
  "host/README.md"
  "desktop/README.md"
  "shared/README.md"
  "docs/agents.workflow.md"
  "docs/repo-structure.md"
  "wbeam"
)

for rel in "${required_paths[@]}"; do
  require_path "${rel}"
done

legacy_paths=(
  "src/host"
  "src/apps"
  "src/protocol"
  "src/domains"
  "src/compat"
  "proto"
  "proto_x11"
)

for rel in "${legacy_paths[@]}"; do
  if [[ -d "${ROOT}/${rel}" ]]; then
    if [[ "${STRICT}" == "1" ]]; then
      echo "[layout][ERROR] legacy path still present in strict mode: ${rel}"
      failed=1
    else
      echo "[layout][INFO] legacy migration source still present: ${rel}"
    fi
  fi
done

if [[ "${failed}" -ne 0 ]]; then
  echo "[layout] FAILED"
  exit 1
fi

echo "[layout] OK"
