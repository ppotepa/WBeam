#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

ANDROID_DIR="${ROOT_DIR}/android"
APK_SRC="${ANDROID_DIR}/app/build/outputs/apk/release/app-release.apk"
APK_OUT="${DIST_DIR}/wbeam-android-${WBEAM_VERSION}.apk"

if [[ -z "${WBEAM_VERSION_CODE:-}" && "${CI_PIPELINE_IID:-}" =~ ^[0-9]+$ ]]; then
  WBEAM_VERSION_CODE=$((10000 + 10#${CI_PIPELINE_IID}))
fi
if [[ ! "${WBEAM_VERSION_CODE:-}" =~ ^[1-9][0-9]*$ ]] || (( WBEAM_VERSION_CODE <= 1 || WBEAM_VERSION_CODE > 2100000000 )); then
  echo "[build_apk] Release requires monotonic WBEAM_VERSION_CODE (CI_PIPELINE_IID or explicit)." >&2
  exit 1
fi
for variable in WBEAM_ANDROID_KEYSTORE_PATH WBEAM_ANDROID_STORE_PASSWORD WBEAM_ANDROID_KEY_ALIAS WBEAM_ANDROID_KEY_PASSWORD; do
  if [[ -z "${!variable:-}" ]]; then
    echo "[build_apk] Missing protected release variable: $variable" >&2
    exit 1
  fi
done
[[ -r "$WBEAM_ANDROID_KEYSTORE_PATH" ]] || { echo "[build_apk] Release keystore unreadable." >&2; exit 1; }
export WBEAM_VERSION_CODE
echo "[build_apk] Release revision=${WBEAM_VERSION} versionCode=${WBEAM_VERSION_CODE}"
(
  cd "${ANDROID_DIR}"
  chmod +x ./gradlew
  ./gradlew --no-daemon \
    -PWBEAM_BUILD_REV="${WBEAM_VERSION}" \
    -PWBEAM_VERSION_CODE="${WBEAM_VERSION_CODE}" \
    :app:assembleRelease
)

if [[ ! -f "${APK_SRC}" ]]; then
  echo "[build_apk] Missing output APK: ${APK_SRC}" >&2
  exit 1
fi

APKSIGNER="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}/build-tools/35.0.0/apksigner"
if [[ ! -x "$APKSIGNER" ]]; then
  APKSIGNER="$(command -v apksigner || true)"
fi
[[ -x "$APKSIGNER" ]] || { echo "[build_apk] apksigner is required to verify release." >&2; exit 1; }
if ! signing_report="$("$APKSIGNER" verify --print-certs "$APK_SRC" 2>&1)"; then
  echo "[build_apk] APK signing verification failed." >&2
  exit 1
fi
if printf '%s' "$signing_report" | grep -Eqi 'CN=Android Debug'; then
  echo "[build_apk] Refusing Android debug signing certificate in release." >&2
  exit 1
fi
cp -f "${APK_SRC}" "${APK_OUT}"
write_version_manifest
echo "[build_apk] Created ${APK_OUT}"
