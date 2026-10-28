#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/runtime-assets.sh"
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/deb-target.sh"

# A supported DEB is compiled against the actual target distribution ABI.
# Never relabel a Bookworm/libevdi0 binary as Noble/libevdi1.
if [[ ! -r /etc/os-release ]]; then
  echo "[build_deb] Cannot detect target distribution (/etc/os-release missing)" >&2
  exit 2
fi
source /etc/os-release
detected_target="$(wbeam_deb_target_for_os "${ID:-}" "${VERSION_ID:-}")" || {
  echo "[build_deb] Unsupported target: ${ID:-unknown} ${VERSION_ID:-unknown}; supported debian:12 / ubuntu:24.04" >&2
  exit 2
}
WBEAM_DEB_TARGET="${WBEAM_DEB_TARGET:-$detected_target}"
if [[ "$WBEAM_DEB_TARGET" != "$detected_target" ]]; then
  echo "[build_deb] Requested target $WBEAM_DEB_TARGET differs from actual host $detected_target" >&2
  exit 2
fi
EVDI_RUNTIME_PKG="$(wbeam_deb_evdi_runtime_package "$WBEAM_DEB_TARGET")"
EXPECTED_EVDI_SONAME="$(wbeam_deb_evdi_soname "$WBEAM_DEB_TARGET")"
DEB_VERSION="$(wbeam_deb_package_version "$WBEAM_VERSION" "$WBEAM_DEB_TARGET")"
ARCH="$(dpkg --print-architecture)"
PKG_NAME="wbeam"
echo "[build_deb] Target=$WBEAM_DEB_TARGET, runtime=$EVDI_RUNTIME_PKG, version=$DEB_VERSION"

echo "[build_deb] Building Rust binaries..."
cargo build --release -p wbeamd-server --manifest-path "${RUST_MANIFEST}"
cargo build --release -p wbeamd-streamer --no-default-features --features evdi --manifest-path "${RUST_MANIFEST}"
streamer_bin="${ROOT_DIR}/host/rust/target/release/wbeamd-streamer"
if ! streamer_links="$(ldd "$streamer_bin" 2>&1)"; then
  echo "[build_deb] Failed to inspect streamer shared libraries" >&2
  echo "$streamer_links" >&2
  exit 1
fi
if printf '%s\n' "$streamer_links" | grep -Fq 'not found'; then
  echo "[build_deb] Unresolved EVDI/streamer dynamic library in $WBEAM_DEB_TARGET" >&2
  echo "$streamer_links" >&2
  exit 1
fi
if ! dynamic_deps="$(readelf -d "$streamer_bin" 2>&1)"; then
  echo "[build_deb] Cannot inspect streamer ELF NEEDED entries" >&2
  echo "$dynamic_deps" >&2
  exit 1
fi
if ! printf '%s\n' "$dynamic_deps" | grep -Fq "Shared library: [$EXPECTED_EVDI_SONAME]"; then
  echo "[build_deb] WRONG EVDI ABI: expected ELF NEEDED $EXPECTED_EVDI_SONAME on $WBEAM_DEB_TARGET" >&2
  echo "$dynamic_deps" >&2
  exit 1
fi
if ! printf '%s\n' "$streamer_links" | grep -Fq "$EXPECTED_EVDI_SONAME"; then
  echo "[build_deb] Runtime loader did not resolve required $EXPECTED_EVDI_SONAME" >&2
  echo "$streamer_links" >&2
  exit 1
fi
echo "[build_deb] Streamer ELF and runtime match $EXPECTED_EVDI_SONAME on $WBEAM_DEB_TARGET"
echo "[build_deb] Building desktop Tauri app..."
(
  cd "${ROOT_DIR}/desktop/apps/desktop-tauri"
  npm ci
  npm run build
  cargo build --release --manifest-path src-tauri/Cargo.toml
)
write_version_manifest

PKGROOT="$(mktemp -d)"
trap 'rm -rf "${PKGROOT}"' EXIT

mkdir -p "${PKGROOT}/DEBIAN" "${PKGROOT}/usr/local/bin" "${PKGROOT}/usr/share/doc/wbeam" "${PKGROOT}/usr/share/applications" "${PKGROOT}/usr/share/icons/hicolor/256x256/apps"
stage_wbeam_runtime_assets "${PKGROOT}/usr/share/wbeam"
assert_wbeam_runtime_assets "${PKGROOT}/usr/share/wbeam"
cat > "${PKGROOT}/usr/local/bin/wbeam" <<'EOF'
#!/usr/bin/env bash
export WBEAM_ROOT="${WBEAM_ROOT:-/usr/share/wbeam}"
exec "${WBEAM_ROOT}/wbeam" "$@"
EOF
chmod 0755 "${PKGROOT}/usr/local/bin/wbeam"
install -m 0755 "${ROOT_DIR}/host/rust/target/release/wbeamd-server" "${PKGROOT}/usr/local/bin/wbeamd-server"
install -m 0755 "${ROOT_DIR}/host/rust/target/release/wbeamd-streamer" "${PKGROOT}/usr/local/bin/wbeamd-streamer"
install -m 0755 "${ROOT_DIR}/desktop/apps/desktop-tauri/src-tauri/target/release/wbeam-desktop-tauri" "${PKGROOT}/usr/local/bin/wbeam-desktop-tauri"
cat > "${PKGROOT}/usr/local/bin/wbeam-desktop" <<'EOF'
#!/usr/bin/env bash
export WBEAM_ROOT="${WBEAM_ROOT:-/usr/share/wbeam}"
exec /usr/local/bin/wbeam-desktop-tauri "$@"
EOF
chmod 0755 "${PKGROOT}/usr/local/bin/wbeam-desktop"
install -m 0644 "${ROOT_DIR}/desktop/apps/desktop-tauri/src-tauri/icons/icon.png" "${PKGROOT}/usr/share/icons/hicolor/256x256/apps/wbeam.png"
cat > "${PKGROOT}/usr/share/applications/wbeam.desktop" <<'EOF'
[Desktop Entry]
Type=Application
Version=1.0
Name=WBeam Desktop
Comment=WBeam desktop control and host service manager
Exec=/usr/local/bin/wbeam-desktop
Icon=wbeam
Terminal=false
Categories=Utility;Network;
StartupNotify=true
EOF
install -m 0644 "${ROOT_DIR}/README.md" "${PKGROOT}/usr/share/doc/wbeam/README.md"

cat > "${PKGROOT}/DEBIAN/control" <<EOF
Package: ${PKG_NAME}
Version: ${DEB_VERSION}
Section: utils
Priority: optional
Architecture: ${ARCH}
Maintainer: WBeam CI <ci@example.local>
Depends: libgtk-3-0, libwebkit2gtk-4.1-0, libsoup-3.0-0, libayatana-appindicator3-1, ${EVDI_RUNTIME_PKG}, adb, curl, gstreamer1.0-plugins-base
Description: WBeam host tools and daemon binaries
 WBeam host runtime package built by GitLab CI.
EOF

OUT_FILE="${DIST_DIR}/${PKG_NAME}_${DEB_VERSION}_${ARCH}.deb"
dpkg-deb --build "${PKGROOT}" "${OUT_FILE}"
echo "[build_deb] Created ${OUT_FILE}"
