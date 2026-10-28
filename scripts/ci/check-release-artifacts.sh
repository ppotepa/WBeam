#!/usr/bin/env bash
# Fail-closed release artifact completeness/integrity check.
# Prerequisites: GNU coreutils, bash, and artifacts from the same CI pipeline.
set -Eeuo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/deb-target.sh"

[[ -s "${DIST_DIR}/VERSION.txt" ]] || {
  echo "[release] VERSION.txt is missing" >&2
  exit 1
}
manifest_version="$(sed -n 's/^WBEAM_VERSION=//p' "${DIST_DIR}/VERSION.txt" | head -n 1)"
[[ "$manifest_version" == "$WBEAM_VERSION" ]] || {
  echo "[release] manifest version mismatch: expected ${WBEAM_VERSION}, found ${manifest_version:-none}" >&2
  exit 1
}
version_code="$(sed -n 's/^WBEAM_VERSION_CODE=//p' "${DIST_DIR}/VERSION.txt" | head -n 1)"
[[ "$version_code" =~ ^[0-9]+$ ]] && (( version_code > 1 )) || {
  echo "[release] missing/invalid signed Android versionCode" >&2
  exit 1
}
if [[ -n "${CI_COMMIT_SHA:-}" ]]; then
  recorded_sha="$(sed -n 's/^CI_COMMIT_SHA=//p' "${DIST_DIR}/VERSION.txt" | head -n 1)"
  [[ "$recorded_sha" == "$CI_COMMIT_SHA" ]] || {
    echo "[release] manifest commit hash differs from current CI commit" >&2
    exit 1
  }
fi

shopt -s nullglob
debs=("${DIST_DIR}"/*.deb)
if ! wbeam_deb_has_both_artifacts "${DIST_DIR}"; then
  echo "[release] Both Bookworm and Ubuntu Noble ABI-specific DEB packages are required" >&2
  exit 1
fi
rpms=("${DIST_DIR}"/*.rpm)
apks=("${DIST_DIR}"/*.apk)
if (( ${#debs[@]} < 1 || ${#rpms[@]} < 1 || ${#apks[@]} != 1 )); then
  echo "[release] missing/ambiguous package set: DEB=${#debs[@]} RPM=${#rpms[@]} APK=${#apks[@]}" >&2
  exit 1
fi
[[ "${apks[0]}" == "${DIST_DIR}/wbeam-android-${WBEAM_VERSION}.apk" ]] || {
  echo "[release] Android artifact version differs from the manifest" >&2
  exit 1
}
for file in "${debs[@]}" "${rpms[@]}" "${apks[@]}"; do
  [[ -s "$file" ]] || {
    echo "[release] artifact is missing or empty: $file" >&2
    exit 1
  }
done

# Deterministic checksums created before uploads. Unlisted, stale build files
# are not silently treated as a supported release platform.
(
  cd "${DIST_DIR}"
  package_names=()
  for file in "${debs[@]}" "${rpms[@]}" "${apks[@]}"; do
    package_names+=("$(basename "$file")")
  done
  printf '%s\n' "${package_names[@]}" | LC_ALL=C sort |
    while IFS= read -r name; do
      sha256sum "$name"
    done
  sha256sum VERSION.txt
) > "${DIST_DIR}/SHA256SUMS.txt"
[[ -s "${DIST_DIR}/SHA256SUMS.txt" ]]
(cd "${DIST_DIR}" && sha256sum -c SHA256SUMS.txt >/dev/null)
echo "[release] Complete DEB/RPM/APK set, provenance and SHA256 verified: ${WBEAM_VERSION}"
