#!/usr/bin/env bash
# Hermetic EVDI runtime, ABI and active-user DRM regression tests.
# No kernel/package changes, physical EVDI devices or elevated privileges.
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT_DIR/scripts/virtual-deps-check.sh"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/mock-bin" "$tmp/lib" "$tmp/sys/evdi.7/drm/card7" "$tmp/dri" "$tmp/config"
ln -s /dev/null "$tmp/dri/card7"
# A physical card0 alone must never satisfy EVDI detection.
ln -s /dev/null "$tmp/dri/card0"
[[ "$(evdi_find_drm_devices "$tmp/sys" "$tmp/dri")" == "$tmp/dri/card7" ]]
rm "$tmp/dri/card7"
if evdi_find_drm_devices "$tmp/sys" "$tmp/dri"; then
  echo '[evdi][FAIL] physical card0 was accepted as EVDI' >&2
  exit 1
fi
ln -s /dev/null "$tmp/dri/card7"

cat > "$tmp/mock-bin/uname" <<'MOCK'
#!/bin/sh
if [ "${1:-}" = -s ]; then printf 'Linux\n'; else printf '999.0.0-wbeam-fixture\n'; fi
MOCK
cat > "$tmp/mock-bin/modinfo" <<'MOCK'
#!/bin/sh
exit "${WBEAM_TEST_MODINFO_CODE:-0}"
MOCK
cat > "$tmp/mock-bin/lsmod" <<'MOCK'
#!/bin/sh
if [ "${WBEAM_TEST_LOADED:-1}" = 1 ]; then printf 'evdi 20480 0\n'; fi
MOCK
cat > "$tmp/mock-bin/ldd" <<'MOCK'
#!/bin/sh
if [ "${WBEAM_TEST_NO_EVDI:-0}" = 1 ]; then
  printf 'libc.so.6 => /lib/libc.so.6 (0x012345)\n'
  exit 0
fi
soname="${WBEAM_TEST_REQUIRED_SONAME:-libevdi.so.1}"
if [ -f "$WBEAM_EVDI_LIB_DIR/$soname" ]; then
  printf '\t%s => %s/%s (0x0123456789ab)\n' "$soname" "$WBEAM_EVDI_LIB_DIR" "$soname"
else
  printf '\t%s => not found\n' "$soname"
fi
MOCK
cat > "$tmp/mock-bin/readelf" <<'MOCK'
#!/bin/sh
printf ' 0x000000000000000e (SONAME) Library soname: [%s]\n' "${WBEAM_TEST_ACTUAL_SONAME:-${WBEAM_TEST_REQUIRED_SONAME:-libevdi.so.1}}"
MOCK
cat > "$tmp/mock-bin/objdump" <<'MOCK'
#!/bin/sh
printf '  SONAME               %s\n' "${WBEAM_TEST_ACTUAL_SONAME:-${WBEAM_TEST_REQUIRED_SONAME:-libevdi.so.1}}"
MOCK
cat > "$tmp/streamer" <<'MOCK'
#!/bin/sh
# Never executed; only mock dynamic-linker metadata is inspected.
exit 99
MOCK
chmod +x "$tmp/mock-bin/"* "$tmp/streamer"
export PATH="$tmp/mock-bin:$PATH"
export XDG_CONFIG_HOME="$tmp/config"
export WBEAM_RUST_STREAMER_BIN="$tmp/streamer"
export WBEAM_EVDI_LIB_DIR="$tmp/lib"

# Simulate sysfs enumeration and permissions at the OS boundary. The real
# discovery function was tested above with an EVDI sysfs tree and a character node.
evdi_find_drm_devices() {
  if [[ "${WBEAM_TEST_DRM_PRESENT:-1}" == 1 ]]; then printf '/dev/null\n'; fi
}
evdi_device_accessible() {
  [[ "${WBEAM_TEST_DRM_ACCESS:-1}" == 1 && -r "$1" && -w "$1" ]]
}

assert_failure() {
  local expected="$1" result status=0
  result="$(virtual_deps_main --json --backend evdi)" || status=$?
  [[ "$status" -eq 1 ]] || { printf '[evdi][FAIL] expected exit 1, got %s: %s\n' "$status" "$result" >&2; exit 1; }
  jq -e --arg expected "$expected" '.ok == false and (.missing | index($expected) != null)' <<<"$result" >/dev/null || {
    printf '[evdi][FAIL] missing diagnostic %s: %s\n' "$expected" "$result" >&2
    exit 1
  }
}

# .so.1 and .so.0 each work when the *selected binary* requires that ABI.
# Neither a development symlink nor matching build headers is required.
for soname in libevdi.so.1 libevdi.so.0; do
  rm -f "$tmp/lib/"*
  touch "$tmp/lib/$soname"
  [[ -z "$(evdi_find_linkable_library || true)" ]]
  [[ "$(evdi_find_runtime_library "$soname")" == "$tmp/lib/$soname" ]]
  result="$(WBEAM_TEST_REQUIRED_SONAME="$soname" virtual_deps_main --json --backend evdi)"
  jq -e '.ok == true and (.missing | length) == 0' <<<"$result" >/dev/null
  other=libevdi.so.1
  [[ "$soname" != libevdi.so.1 ]] || other=libevdi.so.0
  if evdi_find_runtime_library "$other" >/dev/null; then
    echo '[evdi][FAIL] runtime finder accepted the wrong ABI' >&2
    exit 1
  fi
  WBEAM_TEST_REQUIRED_SONAME="$other" assert_failure "libevdi-runtime-library:$other"
done
rm "$tmp/lib/libevdi.so.0"
touch "$tmp/lib/libevdi.so.1"

# A renamed/symlinked .so.0 that actually declares SONAME .so.1 is not compatible.
ln -s libevdi.so.1 "$tmp/lib/libevdi.so.0"
WBEAM_TEST_REQUIRED_SONAME=libevdi.so.0 WBEAM_TEST_ACTUAL_SONAME=libevdi.so.1 \
  assert_failure libevdi-abi-mismatch:libevdi.so.0
rm "$tmp/lib/libevdi.so.0"
WBEAM_TEST_NO_EVDI=1 assert_failure streamer-without-evdi
WBEAM_RUST_STREAMER_BIN="$tmp/absent-streamer" assert_failure streamer-binary-missing
WBEAM_TEST_DRM_PRESENT=0 assert_failure evdi-drm-device
WBEAM_TEST_DRM_ACCESS=0 assert_failure evdi-drm-access

# The objdump fallback verifies the same metadata. With neither ELF inspector,
# a successful ldd resolution must not turn an unverified ABI into readiness.
(
  command() {
    if [[ "$1" == -v && "$2" == readelf ]]; then return 1; fi
    builtin command "$@"
  }
  result="$(virtual_deps_main --json --backend evdi)"
  jq -e '.ok == true' <<<"$result" >/dev/null
  WBEAM_TEST_ACTUAL_SONAME=libevdi.so.0 assert_failure libevdi-abi-mismatch:libevdi.so.1
)
(
  command() {
    if [[ "$1" == -v && ( "$2" == readelf || "$2" == objdump ) ]]; then return 1; fi
    builtin command "$@"
  }
  assert_failure libevdi-elf-inspector-missing
)

# A loaded module with a missing userspace dependency does not need headers.
rm "$tmp/lib/libevdi.so.1"
assert_failure libevdi-runtime-library:libevdi.so.1
status=0
result="$(virtual_deps_main --json --backend evdi)" || status=$?
jq -e '[.missing[] | select(startswith("kernel-headers-for-"))] | length == 0' <<<"$result" >/dev/null
touch "$tmp/lib/libevdi.so.1"
# A loaded module remains usable even if its on-disk package is temporarily
# absent; a historical DKMS error must not invalidate this runtime state.
(
  printf 'error: stale build failure\n' > "$tmp/stale-make.log"
  evdi_find_build_log() { printf '%s\n' "$tmp/stale-make.log"; }
  result="$(WBEAM_TEST_MODINFO_CODE=1 virtual_deps_main --json --backend evdi)"
  jq -e '.ok == true and (.missing | length) == 0' <<<"$result" >/dev/null
)
WBEAM_TEST_LOADED=0 assert_failure evdi-module-loaded
status=0
result="$(WBEAM_TEST_LOADED=0 virtual_deps_main --json --backend evdi)" || status=$?
jq -e '[.missing[] | select(startswith("kernel-headers-for-"))] | length == 0' <<<"$result" >/dev/null
WBEAM_TEST_LOADED=0 WBEAM_TEST_MODINFO_CODE=1 assert_failure evdi-kernel-module
status=0
result="$(WBEAM_TEST_LOADED=0 WBEAM_TEST_MODINFO_CODE=1 virtual_deps_main --json --backend evdi)" || status=$?
jq -e '[.missing[] | select(startswith("kernel-headers-for-"))] | length == 1' <<<"$result" >/dev/null

# The daemon environment overrides user config; reading config must not create it.
mkdir -p "$tmp/config/wbeam"
printf 'WBEAM_RUST_STREAMER_BIN="%s"\n' "$tmp/config-streamer" > "$tmp/config/wbeam/wbeam.conf"
[[ "$(WBEAM_RUST_STREAMER_BIN= evdi_selected_streamer "$ROOT_DIR")" == "$tmp/config-streamer" ]]
[[ "$(evdi_selected_streamer "$ROOT_DIR")" == "$tmp/streamer" ]]

echo '[evdi][PASS] exact runtime ABI, EVDI DRM identity/access, overrides and separate build requirements'
