#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
version="$(sed -n -E 's/^WBEAM_VERSION_BASE=([0-9]+\.[0-9]+\.[0-9]+).*$/\1/p' "$ROOT_DIR/config/wbeam.conf" | head -n 1)"
[[ -n "$version" ]] || { echo "[version] config/wbeam.conf does not specify a valid release base" >&2; exit 1; }
grep -Fq "\"version\": \"$version\"" "$ROOT_DIR/desktop/apps/desktop-tauri/package.json"
grep -Fq "\"version\": \"$version\"" "$ROOT_DIR/desktop/apps/desktop-tauri/src-tauri/tauri.conf.json"
grep -Fq "version = \"$version\"" "$ROOT_DIR/desktop/apps/desktop-tauri/src-tauri/Cargo.toml"
grep -Fq "version = \"$version\"" "$ROOT_DIR/host/rust/Cargo.toml"
if grep -Fq "signingConfig = signingConfigs.debug" "$ROOT_DIR/android/app/build.gradle"; then
  echo "[version] ERROR: Android release still uses debug signing" >&2
  exit 1
fi
echo "[version] base $version consistent, no release debug signing"
