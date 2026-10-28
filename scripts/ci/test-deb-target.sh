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
[[ "$(wbeam_deb_package_version 0.1.3-rc.1 bookworm)" == '0.1.3~rc.1+bookworm' ]]
[[ "$(wbeam_deb_package_version 0.1.3-rc.1 noble)" == '0.1.3~rc.1+noble' ]]
[[ "$(wbeam_deb_package_version 0.1.3 noble)" == '0.1.3+noble' ]]
if wbeam_deb_package_version 0.1.3 unknown >/dev/null; then
  echo "[deb] Unsupported artifact target was accepted" >&2
  exit 1
fi
echo "[deb][PASS] Bookworm/Noble binary profile and prerelease ordering"
