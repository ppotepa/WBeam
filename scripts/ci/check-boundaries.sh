#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

failed=0

echo "[boundaries] root=${ROOT_DIR}"

targets=(
  "wbeam"
  "trainer.sh"
  "desktop.sh"
  "devtool"
  "start-remote"
  "runas-remote"
  "redeploy-local"
  "host/scripts"
  "host/rust/scripts"
  "scripts/ci"
  "scripts/diagnostics"
)

legacy_pattern='src/host|src/apps|src/protocol|src/compat|src/domains/training'

for target in "${targets[@]}"; do
  if [[ ! -e "$target" ]]; then
    echo "[boundaries][ERROR] missing target: $target"
    failed=1
    continue
  fi
  if rg -n "${legacy_pattern}" "$target" \
    -g '!scripts/ci/check-repo-layout.sh' \
    -g '!scripts/ci/check-boundaries.sh' \
    >/tmp/wbeam-boundary-hits.txt 2>/dev/null; then
    echo "[boundaries][ERROR] legacy path reference found in $target"
    sed -n '1,60p' /tmp/wbeam-boundary-hits.txt
    failed=1
  fi
done

rm -f /tmp/wbeam-boundary-hits.txt

if [[ "$failed" -ne 0 ]]; then
  echo "[boundaries] FAILED"
  exit 1
fi

echo "[boundaries] OK"
