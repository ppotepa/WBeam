#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

DEB_VERSION="${WBEAM_VERSION}"
ARCH="$(dpkg --print-architecture)"
PKG_NAME="wbeam"

echo "[build_deb] Building Rust binaries..."
cargo build --release -p wbeamd-server -p wbeamd-streamer --manifest-path "${RUST_MANIFEST}"
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

mkdir -p "${PKGROOT}/DEBIAN" "${PKGROOT}/usr/local/bin" "${PKGROOT}/usr/share/doc/wbeam" "${PKGROOT}/usr/share/wbeam/host" "${PKGROOT}/usr/share/wbeam/config" "${PKGROOT}/usr/share/wbeam/host/rust/scripts" "${PKGROOT}/usr/share/applications" "${PKGROOT}/usr/share/icons/hicolor/256x256/apps"
install -m 0755 "${ROOT_DIR}/wbeam" "${PKGROOT}/usr/share/wbeam/wbeam"
cp -a "${ROOT_DIR}/host/scripts" "${PKGROOT}/usr/share/wbeam/host/"
mkdir -p "${PKGROOT}/usr/share/wbeam/host/rust/systemd"
install -m 0644 "${ROOT_DIR}/host/rust/systemd/wbeamd-rust.service.template" "${PKGROOT}/usr/share/wbeam/host/rust/systemd/wbeamd-rust.service.template"
install -m 0755 "${ROOT_DIR}/host/rust/scripts/install_systemd_user.sh" "${PKGROOT}/usr/share/wbeam/host/rust/scripts/install_systemd_user.sh"
install -m 0644 "${ROOT_DIR}/config/wbeam.conf" "${PKGROOT}/usr/share/wbeam/config/wbeam.conf"
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
Depends: libgtk-3-0, libwebkit2gtk-4.1-0, libsoup-3.0-0, libayatana-appindicator3-1
Description: WBeam host tools and daemon binaries
 WBeam host runtime package built by GitLab CI.
EOF

OUT_FILE="${DIST_DIR}/${PKG_NAME}_${DEB_VERSION}_${ARCH}.deb"
dpkg-deb --build "${PKGROOT}" "${OUT_FILE}"
echo "[build_deb] Created ${OUT_FILE}"
