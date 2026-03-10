#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DIST_DIR="${ROOT_DIR}/dist"
RUST_MANIFEST="${ROOT_DIR}/src/host/rust/Cargo.toml"

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
  date -u +%Y%m%d
}

deb_version() {
  if [[ -n "${CI_COMMIT_TAG:-}" ]]; then
    normalize_tag_version "${CI_COMMIT_TAG}"
  else
    printf '0.0.0~git%s.%s\n' "$(build_stamp)" "${CI_COMMIT_SHORT_SHA:-dev}"
  fi
}

rpm_version() {
  if [[ -n "${CI_COMMIT_TAG:-}" ]]; then
    normalize_tag_version "${CI_COMMIT_TAG}"
  else
    printf '0.0.0\n'
  fi
}

rpm_release() {
  if [[ -n "${CI_COMMIT_TAG:-}" ]]; then
    printf '1\n'
  else
    printf '0.git%s.%s\n' "$(build_stamp)" "${CI_COMMIT_SHORT_SHA:-dev}"
  fi
}

artifact_version() {
  if [[ -n "${CI_COMMIT_TAG:-}" ]]; then
    normalize_tag_version "${CI_COMMIT_TAG}"
  else
    printf 'main-%s\n' "${CI_COMMIT_SHORT_SHA:-dev}"
  fi
}
