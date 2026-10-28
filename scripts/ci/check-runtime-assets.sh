#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT/scripts/ci/common.sh"
source "$ROOT/scripts/ci/runtime-assets.sh"
if [[ $# -ne 1 ]]; then echo "Usage: $0 <staged-/usr/share/wbeam>" >&2; exit 2; fi
assert_wbeam_runtime_assets "$1"
bash -n "$1/wbeam" "$1/host/scripts/run_wbeamd.sh" \
  "$1/scripts/virtual-deps-check.sh" "$1/scripts/virtual-deps-install.sh"
test -x "$1/scripts/virtual-deps-check.sh"
test -x "$1/scripts/virtual-deps-install.sh"
test -r "$1/android/wbeam-android.apk"
