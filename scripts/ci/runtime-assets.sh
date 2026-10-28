#!/usr/bin/env bash
# Shared, strictly source-to-runtime staging for DEB/RPM.
# Called by build scripts after compiling the daemon, desktop and signed APK.
set -Eeuo pipefail

stage_wbeam_runtime_assets() {
  local target="$1"
  local apk="${WBEAM_ANDROID_APK_PATH:-${DIST_DIR}/wbeam-android-${WBEAM_VERSION}.apk}"
  if [[ ! -s "$apk" ]]; then
    echo "[package] Missing signed release APK: $apk (run build_apk_release first)" >&2
    return 1
  fi
  if [[ ! -s "${DIST_DIR}/VERSION.txt" ]]; then
    echo "[package] Missing version manifest in ${DIST_DIR}/VERSION.txt" >&2
    return 1
  fi
  mkdir -p "$target"/{host/scripts,host/rust/systemd,host/rust/scripts,config,scripts,android}
  install -m 0755 "${ROOT_DIR}/wbeam" "$target/wbeam"
  cp -a "${ROOT_DIR}/host/scripts/." "$target/host/scripts/"
  install -m 0644 "${ROOT_DIR}/host/rust/systemd/wbeamd-rust.service.template" "$target/host/rust/systemd/"
  install -m 0755 "${ROOT_DIR}/host/rust/scripts/install_systemd_user.sh" "$target/host/rust/scripts/"
  install -m 0644 "${ROOT_DIR}/config/wbeam.conf" "$target/config/"
  local script
  for script in "${ROOT_DIR}"/scripts/*.sh; do
    [[ -f "$script" ]] || continue
    install -m 0755 "$script" "$target/scripts/$(basename "$script")"
  done
  cp -a "${ROOT_DIR}/scripts/tools" "$target/scripts/" 2>/dev/null || true
  install -m 0644 "$apk" "$target/android/wbeam-android.apk"
  install -m 0644 "${DIST_DIR}/VERSION.txt" "$target/VERSION.txt"
}

assert_wbeam_runtime_assets() {
  local root="$1"
  local f
  for f in wbeam VERSION.txt android/wbeam-android.apk config/wbeam.conf \
    host/scripts/run_wbeamd.sh host/scripts/wbeam_config.sh \
    scripts/virtual-deps-check.sh scripts/virtual-deps-install.sh \
    scripts/evdi-diagnose.sh scripts/fedora-setup.sh; do
    if [[ ! -s "$root/$f" ]]; then
      echo "[package] Missing runtime asset: $root/$f" >&2
      return 1
    fi
  done
  echo "[package] Runtime asset contract: OK"
}
