#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$ROOT_DIR/scripts/ci/deb-target.sh"

[[ "$(wbeam_deb_target_for_os debian 12)" == bookworm ]]
[[ "$(wbeam_deb_target_for_os ubuntu 24.04)" == noble ]]
if wbeam_deb_target_for_os ubuntu 22.04 >/dev/null; then
  echo "[deb] Unsupported suite was accepted" >&2
  exit 1
fi
[[ "$(wbeam_deb_evdi_runtime_package bookworm)" == libevdi0 ]]
[[ "$(wbeam_deb_evdi_runtime_package noble)" == libevdi1 ]]
[[ "$(wbeam_deb_evdi_build_package bookworm)" == libevdi0-dev ]]
[[ "$(wbeam_deb_evdi_build_package noble)" == libevdi-dev ]]
[[ "$(wbeam_deb_evdi_soname bookworm)" == libevdi.so.0 ]]
[[ "$(wbeam_deb_evdi_soname noble)" == libevdi.so.1 ]]
if wbeam_deb_evdi_soname unknown >/dev/null; then
  echo "[deb] Unsupported target SONAME was accepted" >&2
  exit 1
fi
[[ "$(wbeam_deb_package_version 0.1.3-rc.1 bookworm)" == '0.1.3~rc.1+bookworm' ]]
[[ "$(wbeam_deb_package_version 0.1.3-rc.1 noble)" == '0.1.3~rc.1+noble' ]]
[[ "$(wbeam_deb_package_version 0.1.3 noble)" == '0.1.3+noble' ]]
# Assert Debian's actual version ordering, not only the expected string.
if command -v dpkg >/dev/null 2>&1; then
  dpkg --compare-versions '0.1.3~rc.1+bookworm' lt '0.1.3+bookworm'
  dpkg --compare-versions '0.1.3~rc.1+noble' lt '0.1.3+noble'
fi
if wbeam_deb_package_version 0.1.3 unknown >/dev/null; then
  echo "[deb] Unsupported artifact target was accepted" >&2
  exit 1
fi
# Verify publication is blocked until both real distribution variants
# are present. Dummy data is sufficient because this only checks filenames.
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
touch "$tmp/wbeam_0.1.3+bookworm_amd64.deb"
if wbeam_deb_has_both_artifacts "$tmp"; then
  echo "[deb][FAIL] accepted only Bookworm DEB" >&2
  exit 1
fi
touch "$tmp/wbeam_0.1.3+noble_amd64.deb"
wbeam_deb_has_both_artifacts "$tmp"
echo "[deb][PASS] Bookworm/Noble ABI profiles, prerelease ordering and artifact completeness"
