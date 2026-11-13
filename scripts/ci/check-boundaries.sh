#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

failed=0
hits="$(mktemp)"
trap 'rm -f "$hits"' EXIT
command -v rg >/dev/null || { echo '[boundaries] ripgrep is required' >&2; exit 1; }

echo "[boundaries] root=${ROOT_DIR}"

targets=(
  "wbeam"
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

legacy_src_pattern='src/host|src/apps|src/protocol|src/compat|src/domains/training'
legacy_wrapper_pattern='\\$ROOT_DIR/proto_x11|\\$ROOT_DIR/proto/|\\./proto_x11/|\\./proto/'

for target in "${targets[@]}"; do
  if [[ ! -e "$target" ]]; then
    echo "[boundaries][ERROR] missing target: $target"
    failed=1
    continue
  fi
  if rg -n "${legacy_src_pattern}" "$target" \
    -g '!scripts/ci/check-repo-layout.sh' \
    -g '!scripts/ci/check-boundaries.sh' \
    >"$hits"; then
    echo "[boundaries][ERROR] legacy src-path reference found in $target"
    sed -n '1,60p' "$hits"
    failed=1
  else
    result=$?
    (( result == 1 )) || { echo "[boundaries][ERROR] search failed for $target" >&2; failed=1; }
  fi
  if rg -n "${legacy_wrapper_pattern}" "$target" \
    -g '!scripts/ci/check-boundaries.sh' \
    >"$hits"; then
    echo "[boundaries][ERROR] legacy wrapper reference found in $target"
    sed -n '1,60p' "$hits"
    failed=1
  else
    result=$?
    (( result == 1 )) || { echo "[boundaries][ERROR] search failed for $target" >&2; failed=1; }
  fi
done

if [[ "$failed" -ne 0 ]]; then
  echo "[boundaries] FAILED"
  exit 1
fi

echo "[boundaries] OK"
