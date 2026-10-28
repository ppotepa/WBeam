#!/usr/bin/env bash
# Hermetic runtime-vs-build EVDI dependency regression tests; never mutate
# the host kernel, install packages, or require root/physical EVDI devices.
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT_DIR/scripts/evdi-compat.sh"
CHECK="$ROOT_DIR/scripts/virtual-deps-check.sh"

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/mock-bin" "$tmp/lib"
cat > "$tmp/mock-bin/uname" <<'EOF'
#!/bin/sh
if [ "${1:-}" = "-s" ]; then printf 'Linux\n'; else printf '999.0.0-wbeam-fixture\n'; fi
EOF
cat > "$tmp/mock-bin/modinfo" <<'EOF'
#!/bin/sh
exit "${WBEAM_TEST_MODINFO_CODE:-0}"
EOF
cat > "$tmp/mock-bin/lsmod" <<'EOF'
#!/bin/sh
if [ "${WBEAM_TEST_LOADED:-1}" = 1 ]; then printf 'evdi 20480 0\n'; fi
EOF
chmod +x "$tmp/mock-bin/"*
export PATH="$tmp/mock-bin:$PATH"
export WBEAM_EVDI_LIB_DIR="$tmp/lib"

# Runtime SONAME is sufficient even if -dev linker symlink and the headers for
# our fake running kernel are absent.
touch "$tmp/lib/libevdi.so.1"
[[ -z "$(evdi_find_linkable_library || true)" ]] || {
  echo "[evdi][FAIL] fixture incorrectly contains linkable dev library" >&2
  exit 1
}
[[ "$(evdi_find_runtime_library)" == "$tmp/lib/libevdi.so.1" ]]
result="$(bash "$CHECK" --json --backend evdi)"
jq -e '.ok == true and (.missing | length) == 0' <<<"$result" >/dev/null || {
  echo "[evdi][FAIL] already loaded runtime must not require headers or libevdi.so" >&2
  echo "$result" >&2
  exit 1
}

# Missing runtime SONAME remains actionable, but does not suggest rebuilding
# the kernel module when it is already loaded.
rm "$tmp/lib/libevdi.so.1"
status=0
result="$(bash "$CHECK" --json --backend evdi)" || status=$?
[[ "$status" -eq 1 ]] || { echo "[evdi][FAIL] missing library diagnostic exit: $status" >&2; exit 1; }
jq -e '.ok == false and (.missing | index("libevdi-runtime-library") != null)' <<<"$result" >/dev/null
jq -e '[.missing[] | select(startswith("kernel-headers-for-"))] | length == 0' <<<"$result" >/dev/null

# An installed-but-unloaded module needs a modprobe/MOK check, not headers.
touch "$tmp/lib/libevdi.so.1"
status=0
result="$(WBEAM_TEST_LOADED=0 bash "$CHECK" --json --backend evdi)" || status=$?
[[ "$status" -eq 1 ]] || { echo "[evdi][FAIL] unloaded module diagnostic exit: $status" >&2; exit 1; }
jq -e '.ok == false and (.missing | index("evdi-module-loaded") != null)' <<<"$result" >/dev/null
jq -e '[.missing[] | select(startswith("kernel-headers-for-"))] | length == 0' <<<"$result" >/dev/null

# Only the missing module triggers the DKMS/header build diagnosis.
status=0
result="$(WBEAM_TEST_LOADED=0 WBEAM_TEST_MODINFO_CODE=1 bash "$CHECK" --json --backend evdi)" || status=$?
[[ "$status" -eq 1 ]] || { echo "[evdi][FAIL] missing module diagnostic exit: $status" >&2; exit 1; }
jq -e '.ok == false and (.missing | index("evdi-kernel-module") != null)' <<<"$result" >/dev/null
jq -e '[.missing[] | select(startswith("kernel-headers-for-"))] | length == 1' <<<"$result" >/dev/null

echo "[evdi][PASS] SONAME runtime, DKMS headers and loaded module are independent"
