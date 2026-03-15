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

mkdir -p "${PKGROOT}/DEBIAN" "${PKGROOT}/usr/local/bin" "${PKGROOT}/usr/share/doc/wbeam"
install -m 0755 "${ROOT_DIR}/wbeam" "${PKGROOT}/usr/local/bin/wbeam"
install -m 0755 "${ROOT_DIR}/host/rust/target/release/wbeamd-server" "${PKGROOT}/usr/local/bin/wbeamd-server"
install -m 0755 "${ROOT_DIR}/host/rust/target/release/wbeamd-streamer" "${PKGROOT}/usr/local/bin/wbeamd-streamer"
install -m 0755 "${ROOT_DIR}/desktop/apps/desktop-tauri/src-tauri/target/release/wbeam-desktop-tauri" "${PKGROOT}/usr/local/bin/wbeam-desktop-tauri"
cat > "${PKGROOT}/usr/local/bin/wbeam-desktop" <<'EOF'
#!/usr/bin/env bash
exec /usr/local/bin/wbeam-desktop-tauri "$@"
EOF
chmod 0755 "${PKGROOT}/usr/local/bin/wbeam-desktop"
install -m 0644 "${ROOT_DIR}/README.md" "${PKGROOT}/usr/share/doc/wbeam/README.md"

cat > "${PKGROOT}/DEBIAN/control" <<EOF
Package: ${PKG_NAME}
Version: ${DEB_VERSION}
Section: utils
Priority: optional
Architecture: ${ARCH}
Maintainer: WBeam CI <ci@example.local>
Description: WBeam host tools and daemon binaries
 WBeam host runtime package built by GitLab CI.
EOF

OUT_FILE="${DIST_DIR}/${PKG_NAME}_${DEB_VERSION}_${ARCH}.deb"
dpkg-deb --build "${PKGROOT}" "${OUT_FILE}"
echo "[build_deb] Created ${OUT_FILE}"
