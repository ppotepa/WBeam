#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
failed=0

require_path() {
  local rel="$1"
  if [[ ! -e "${ROOT}/${rel}" ]]; then
    echo "[layout][ERROR] missing required path: ${rel}"
    failed=1
  fi
}

echo "[layout] root=${ROOT}"

required_paths=(
  "android/README.md"
  "host/README.md"
  "desktop/README.md"
  "shared/README.md"
  "archive/legacy/proto/README.md"
  "archive/legacy/proto_x11/README.md"
  "docs/agents.workflow.md"
  "docs/repo-structure.md"
  "wbeam"
)

for rel in "${required_paths[@]}"; do
  require_path "${rel}"
done

legacy_paths=(
  "src"
  "proto"
  "proto_x11"
)

for rel in "${legacy_paths[@]}"; do
  if [[ -e "${ROOT}/${rel}" ]]; then
    echo "[layout][ERROR] legacy compatibility wrapper still present: ${rel}"
    failed=1
  fi
done

if [[ "${failed}" -ne 0 ]]; then
  echo "[layout] FAILED"
  exit 1
fi

echo "[layout] OK"
