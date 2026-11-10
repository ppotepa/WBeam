#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

ARTIFACT_VER="${WBEAM_VERSION}"
TARGET="aarch64-unknown-linux-gnu"
OUT_NAME="wbeam-linux-aarch64-${ARTIFACT_VER}.tar.gz"
PKGROOT="$(mktemp -d)"
trap 'rm -rf "${PKGROOT}"' EXIT

echo "[build_aarch64] Installing Rust target ${TARGET}..."
rustup target add "${TARGET}"

echo "[build_aarch64] Building wbeamd-server for ${TARGET}..."
export CC_aarch64_unknown_linux_gnu=aarch64-linux-gnu-gcc
export CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER=aarch64-linux-gnu-gcc
cargo build --release --target "${TARGET}" -p wbeamd-server --manifest-path "${RUST_MANIFEST}"

mkdir -p "${PKGROOT}/bin" "${PKGROOT}/doc"
install -m 0755 "${ROOT_DIR}/host/rust/target/${TARGET}/release/wbeamd-server" "${PKGROOT}/bin/wbeamd-server"
cat > "${PKGROOT}/doc/README.md" <<'EOF'
# WayBeam experimental aarch64 daemon

This archive contains only `bin/wbeamd-server`. It is a cross-build artifact
for daemon development, outside the supported release package set.

Run `./bin/wbeamd-server --help` from the extracted directory to inspect its
arguments. Streaming requires a matching streamer, native capture dependencies
and the runtime configuration from the source checkout. Desktop and Android
components are distributed through the complete build/package workflow.

Source and setup: https://github.com/ppotepa/WBeam
EOF
write_version_manifest
install -m 0644 "${DIST_DIR}/VERSION.txt" "${PKGROOT}/VERSION.txt"

tar -C "${PKGROOT}" -czf "${DIST_DIR}/${OUT_NAME}" .
echo "[build_aarch64] Created ${DIST_DIR}/${OUT_NAME}"
