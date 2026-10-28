#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
runner="$ROOT_DIR/scripts/ci/run-local-verification.sh"
help="$(bash "$runner" --help)"
grep -Fq -- '--quick' <<<"$help"
grep -Fq -- '--full' <<<"$help"
grep -Fq -- 'BLOCKED' <<<"$help"
quick="$(bash "$runner" --list)"
full="$(bash "$runner" --full --list)"
grep -Fxq 'shell_syntax' <<<"$quick"
grep -Fxq 'desktop_build' <<<"$quick"
if grep -Fxq 'host_rust' <<<"$quick"; then
  echo '[verify][FAIL] quick unexpectedly runs Rust' >&2
  exit 1
fi
grep -Fxq 'host_rust' <<<"$full"
grep -Fxq 'host_streamer' <<<"$full"
grep -Fxq 'tauri_check' <<<"$full"
grep -Fxq 'android_debug' <<<"$full"
echo '[verify][PASS] local QA runner commands and modes'
