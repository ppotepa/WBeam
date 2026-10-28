#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=../evdi-compat.sh
source "$ROOT_DIR/scripts/evdi-compat.sh"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
printf 'warning: comparison of integer expressions of different signedness [-Werror=sign-compare]\n' > "$tmp/sign.log"
[[ "$(evdi_build_failure_reason "$tmp/sign.log")" == "dkms-kernel-api-sign-compare" ]]
printf 'fatal error: linux/version.h: No such file or directory\n' > "$tmp/headers.log"
[[ "$(evdi_build_failure_reason "$tmp/headers.log")" == "dkms-kernel-headers-mismatch" ]]
printf 'error: undefined function evdi_foo\n' > "$tmp/generic.log"
[[ "$(evdi_build_failure_reason "$tmp/generic.log")" == "dkms-build-failed-other" ]]
[[ "$(evdi_build_failure_reason "$tmp/missing.log")" == "dkms-log-not-readable" ]]
WBEAM_EVDI_LIB_DIR="$tmp" ! evdi_find_linkable_library >/dev/null
touch "$tmp/libevdi.so"
WBEAM_EVDI_LIB_DIR="$tmp" evdi_find_linkable_library | grep -qx "$tmp/libevdi.so"
echo "[evdi][PASS] DKMS diagnostic classification and library override"
