#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# Use existing Java bytecode when available; refresh it if the local Android toolchain is ready.
if [[ -x "${ROOT_DIR}/android/gradlew" && -f "${ROOT_DIR}/android/local.properties" ]]; then
  (
    cd "${ROOT_DIR}/android"
    ./gradlew --no-daemon :app:compileReleaseJavaWithJavac >/dev/null 2>&1 || true
  )
fi
