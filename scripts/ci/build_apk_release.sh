#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

ANDROID_DIR="${ROOT_DIR}/android"
APK_SRC="${ANDROID_DIR}/app/build/outputs/apk/release/app-release.apk"
APK_OUT="${DIST_DIR}/wbeam-android-${WBEAM_VERSION}.apk"

echo "[build_apk] Building Android release APK with WBEAM_BUILD_REV=${WBEAM_VERSION}..."
(
  cd "${ANDROID_DIR}"
  chmod +x ./gradlew
  ./gradlew --no-daemon \
    -PWBEAM_BUILD_REV="${WBEAM_VERSION}" \
    :app:assembleRelease
)

if [[ ! -f "${APK_SRC}" ]]; then
  echo "[build_apk] Missing output APK: ${APK_SRC}" >&2
  exit 1
fi

cp -f "${APK_SRC}" "${APK_OUT}"
write_version_manifest
echo "[build_apk] Created ${APK_OUT}"
