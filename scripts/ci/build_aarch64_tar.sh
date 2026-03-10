#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

ARTIFACT_VER="$(artifact_version)"
TARGET="aarch64-unknown-linux-gnu"
OUT_NAME="wbeam-linux-aarch64-${ARTIFACT_VER}.tar.gz"
PKGROOT="$(mktemp -d)"
trap 'rm -rf "${PKGROOT}"' EXIT

echo "[build_aarch64] Installing Rust target ${TARGET}..."
rustup target add "${TARGET}"

echo "[build_aarch64] Building wbeamd-server for ${TARGET}..."
export CC_aarch64_unknown_linux_gnu=aarch64-linux-gnu-gcc
cargo build --release --target "${TARGET}" -p wbeamd-server --manifest-path "${RUST_MANIFEST}"

mkdir -p "${PKGROOT}/bin" "${PKGROOT}/doc"
install -m 0755 "${ROOT_DIR}/wbeam" "${PKGROOT}/bin/wbeam"
install -m 0755 "${ROOT_DIR}/src/host/rust/target/${TARGET}/release/wbeamd-server" "${PKGROOT}/bin/wbeamd-server"
install -m 0644 "${ROOT_DIR}/README.md" "${PKGROOT}/doc/README.md"

tar -C "${PKGROOT}" -czf "${DIST_DIR}/${OUT_NAME}" .
echo "[build_aarch64] Created ${DIST_DIR}/${OUT_NAME}"
