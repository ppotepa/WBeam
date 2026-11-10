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
[[ "$version_code" =~ ^[1-9][0-9]{0,9}$ ]] && (( version_code > 1 && version_code <= 2100000000 )) || {
  echo "[release] missing/invalid signed Android versionCode" >&2
  exit 1
}
recorded_sha="$(sed -n 's/^CI_COMMIT_SHA=//p' "${DIST_DIR}/VERSION.txt" | head -n 1)"
[[ "$recorded_sha" =~ ^[0-9a-f]{40}$ ]] || {
  echo "[release] manifest must record the full build commit hash" >&2
  exit 1
}
for key in WBEAM_VERSION WBEAM_VERSION_CODE CI_COMMIT_SHA; do
  [[ "$(grep -c "^${key}=" "${DIST_DIR}/VERSION.txt")" == 1 ]] || {
    echo "[release] manifest requires exactly one ${key} entry" >&2
    exit 1
  }
done
if [[ -n "${CI_COMMIT_SHA:-}" ]]; then
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
if (( ${#debs[@]} != 2 || ${#rpms[@]} < 1 || ${#apks[@]} != 1 )); then
  echo "[release] missing/ambiguous package set: DEB=${#debs[@]} RPM=${#rpms[@]} APK=${#apks[@]}" >&2
  exit 1
fi
[[ "${apks[0]}" == "${DIST_DIR}/wbeam-android-${WBEAM_VERSION}.apk" ]] || {
  echo "[release] Android artifact version differs from the manifest" >&2
  exit 1
}
for file in "${debs[@]}"; do
  name="$(basename "$file")"
  case "$name" in
    wbeam_"$(wbeam_deb_package_version "$WBEAM_VERSION" bookworm)"_*.deb|wbeam_"$(wbeam_deb_package_version "$WBEAM_VERSION" noble)"_*.deb) ;;
    *) echo "[release] stale or unsupported DEB artifact: $name" >&2; exit 1 ;;
  esac
done
rpm_version="${WBEAM_VERSION/-rc./~rc.}"
rpm_version="${rpm_version/-beta./~beta.}"
rpm_version="${rpm_version/-alpha./~alpha.}"
for file in "${rpms[@]}"; do
  case "$(basename "$file")" in
    wbeam-"$rpm_version"-1.x86_64.rpm|wbeam-"$rpm_version"-1.aarch64.rpm|wbeam-"$rpm_version"-1.fc43.x86_64.rpm|wbeam-"$rpm_version"-1.fc43.aarch64.rpm) ;;
    *) echo "[release] stale or unsupported RPM artifact: $(basename "$file")" >&2; exit 1 ;;
  esac
done
for file in "${debs[@]}" "${rpms[@]}" "${apks[@]}"; do
  [[ -f "$file" && -s "$file" && ! -L "$file" ]] || {
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
