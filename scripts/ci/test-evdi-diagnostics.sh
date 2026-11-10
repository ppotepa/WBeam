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
if WBEAM_EVDI_LIB_DIR="$tmp" evdi_find_linkable_library >/dev/null; then
  echo "[evdi] ERROR: accepted missing linker library" >&2
  exit 1
fi
touch "$tmp/libevdi.so"
WBEAM_EVDI_LIB_DIR="$tmp" evdi_find_linkable_library | grep -qx "$tmp/libevdi.so"
echo "[evdi][PASS] DKMS diagnostic classification and library override"

# The user-facing report consumes shared runtime checks, distinguishes build-only
# warnings, and must never compile/open a DRM device as a side effect of --fix.
source "$ROOT_DIR/scripts/evdi-diagnose.sh"
mkdir -p "$tmp/mock-bin"
export WBEAM_TEST_DIAGNOSTIC_MUTATIONS="$tmp/mutations.log"
for tool in gcc cc modprobe sudo usermod; do
  cat > "$tmp/mock-bin/$tool" <<'MOCK'
#!/bin/sh
printf '%s %s\n' "${0##*/}" "$*" >> "$WBEAM_TEST_DIAGNOSTIC_MUTATIONS"
exit 99
MOCK
  chmod +x "$tmp/mock-bin/$tool"
done
cat > "$tmp/mock-bin/lsmod" <<'MOCK'
#!/bin/sh
printf 'evdi 20480 0\n'
MOCK
cat > "$tmp/mock-bin/modinfo" <<'MOCK'
#!/bin/sh
exit 0
MOCK
cat > "$tmp/mock-bin/uname" <<'MOCK'
#!/bin/sh
printf '999.0.0-waybeam-fixture\n'
MOCK
cat > "$tmp/mock-bin/mokutil" <<'MOCK'
#!/bin/sh
printf 'SecureBoot disabled\n'
MOCK
cat > "$tmp/mock-bin/dkms" <<'MOCK'
#!/bin/sh
printf 'evdi/fixture, 999.0.0-waybeam-fixture: installed\n'
MOCK
chmod +x "$tmp/mock-bin/"*
export PATH="$tmp/mock-bin:$PATH"
evdi_find_drm_devices() {
  [[ "${WBEAM_TEST_DIAGNOSTIC_NO_DRM:-0}" == 1 ]] || printf '/dev/null\n'
}
evdi_streamer_runtime_issues() {
  if [[ "${WBEAM_TEST_DIAGNOSTIC_ABI_ERROR:-0}" == 1 ]]; then
    printf 'libevdi-abi-mismatch:libevdi.so.0\n'
  fi
}
evdi_find_linkable_library() { return 1; }
evdi_diagnose_main --verbose --fix > "$tmp/report.log"
grep -Fq '0 runtime error(s)' "$tmp/report.log"
grep -Fq 'Kernel headers for 999.0.0-waybeam-fixture are absent' "$tmp/report.log"
grep -Fq 'Unversioned libevdi.so is absent' "$tmp/report.log"
[[ ! -e "$WBEAM_TEST_DIAGNOSTIC_MUTATIONS" ]]
status=0
WBEAM_TEST_DIAGNOSTIC_NO_DRM=1 evdi_diagnose_main --fix > "$tmp/no-device.log" || status=$?
[[ "$status" -gt 0 ]]
grep -Fq 'No EVDI DRM device exists' "$tmp/no-device.log"
status=0
WBEAM_TEST_DIAGNOSTIC_ABI_ERROR=1 evdi_diagnose_main --fix > "$tmp/abi-error.log" || status=$?
[[ "$status" -gt 0 ]]
grep -Fq 'libevdi-abi-mismatch:libevdi.so.0' "$tmp/abi-error.log"
[[ ! -e "$WBEAM_TEST_DIAGNOSTIC_MUTATIONS" ]]
echo '[evdi][PASS] detailed report remains read-only and separates runtime failures from source-build warnings'
