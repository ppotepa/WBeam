#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
common="$ROOT_DIR/scripts/ci/common.sh"

version="$(CI_COMMIT_TAG=v0.1.3-rc.1 CI_COMMIT_SHA=abcdef123456 bash -c 'source "$1"; printf "%s" "$WBEAM_VERSION"' _ "$common")"
[[ "$version" == "0.1.3-rc.1" ]] || { echo "RC tag mismatch: $version" >&2; exit 1; }

version="$(CI_COMMIT_TAG=v0.1.3 CI_COMMIT_SHA=abcdef123456 bash -c 'source "$1"; printf "%s" "$WBEAM_VERSION"' _ "$common")"
[[ "$version" == "0.1.3" ]] || { echo "stable tag mismatch: $version" >&2; exit 1; }

version="$(CI_COMMIT_SHA=abcdef123456 bash -c 'source "$1"; printf "%s" "$WBEAM_VERSION"' _ "$common")"
[[ "$version" == "0.1.3.abcde" ]] || { echo "development version mismatch: $version" >&2; exit 1; }

if CI_COMMIT_TAG=v0.1.3-rc.1.invalid bash -c 'source "$1"' _ "$common" >/dev/null 2>&1; then
  echo "[version] invalid tag was accepted" >&2
  exit 1
fi
echo "[version] semantic stable, RC and dev versions: OK"
