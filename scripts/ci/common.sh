#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DIST_DIR="${ROOT_DIR}/dist"
RUST_MANIFEST="${ROOT_DIR}/host/rust/Cargo.toml"

mkdir -p "${DIST_DIR}"

normalize_tag_version() {
  local raw="${1:-}"
  raw="${raw#v}"
  raw="${raw#V}"
  raw="${raw//_/.}"
  raw="${raw//-/.}"
  if [[ -z "${raw}" ]]; then
    raw="0.0.0"
  fi
  printf '%s\n' "${raw}"
}

build_stamp() {
  date -u +%Y%m%d%H%M
}

wbeam_version() {
  if [[ -n "${CI_COMMIT_TAG:-}" ]]; then
    normalize_tag_version "${CI_COMMIT_TAG}"
  else
    printf '0.0.0.main.%s.%s\n' "$(build_stamp)" "${CI_COMMIT_SHORT_SHA:-dev}"
  fi
}

WBEAM_VERSION="${WBEAM_VERSION:-$(wbeam_version)}"
export WBEAM_VERSION

write_version_manifest() {
  cat > "${DIST_DIR}/VERSION.txt" <<EOF
WBEAM_VERSION=${WBEAM_VERSION}
CI_PIPELINE_ID=${CI_PIPELINE_ID:-local}
CI_COMMIT_SHA=${CI_COMMIT_SHA:-local}
CI_COMMIT_REF_NAME=${CI_COMMIT_REF_NAME:-local}
EOF
}

artifact_version() {
  printf '%s\n' "${WBEAM_VERSION}"
}
