#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
publisher="$ROOT_DIR/scripts/ci/publish_github_release.sh"
manifest="$ROOT_DIR/scripts/ci/check-release-artifacts.sh"

grep -Fq 'PRERELEASE_JSON="true"' "$publisher"
grep -Fq 'PRERELEASE_JSON="false"' "$publisher"
grep -Fq 'WBEAM_RELEASE_APPROVED' "$publisher"
grep -Fq 'WBEAM_HARDWARE_ACCEPTANCE_FILE' "$publisher"
grep -Fq 'Versioned GitHub release already exists' "$publisher"
! grep -Fq 'publish_release "${VERSION_TAG}"' "$publisher"
grep -Fq 'SHA256SUMS.txt' "$manifest"
grep -Fq 'sha256sum -c SHA256SUMS.txt' "$manifest"
grep -Fq 'debs=(' "$manifest"
grep -Fq 'rpms=(' "$manifest"
grep -Fq 'apks=(' "$manifest"
echo '[release] Static publication guard contract: OK'
