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

wbeam_version() {
  local base short_sha
  base="${WBEAM_VERSION_BASE:-0.1.3}"
  if [[ -n "${CI_COMMIT_TAG:-}" ]]; then
    base="$(normalize_tag_version "${CI_COMMIT_TAG}")"
  fi

  short_sha="${CI_COMMIT_SHA:-${CI_COMMIT_SHORT_SHA:-}}"
  if [[ "${#short_sha}" -ge 5 ]]; then
    short_sha="${short_sha:0:5}"
  else
    short_sha="dev00"
  fi
  printf '%s.%s\n' "${base}" "${short_sha}"
}

WBEAM_VERSION="${WBEAM_VERSION:-$(wbeam_version)}"
export WBEAM_VERSION
export WBEAM_BUILD_REV="${WBEAM_BUILD_REV:-$WBEAM_VERSION}"

write_version_manifest() {
  # Packaging jobs download the signed APK job's manifest as an artifact.
  # Preserve its versionCode instead of overwriting it with an empty value.
  local version_code="${WBEAM_VERSION_CODE:-}"
  if [[ -z "$version_code" && -f "${DIST_DIR}/VERSION.txt" ]]; then
    version_code="$(sed -n 's/^WBEAM_VERSION_CODE=//p' "${DIST_DIR}/VERSION.txt" | head -n 1)"
  fi
  cat > "${DIST_DIR}/VERSION.txt" <<EOF
WBEAM_VERSION=${WBEAM_VERSION}
WBEAM_VERSION_CODE=${version_code}
CI_PIPELINE_ID=${CI_PIPELINE_ID:-local}
CI_COMMIT_SHA=${CI_COMMIT_SHA:-local}
CI_COMMIT_REF_NAME=${CI_COMMIT_REF_NAME:-local}
EOF
}

artifact_version() {
  printf '%s\n' "${WBEAM_VERSION}"
}
