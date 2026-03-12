#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

run_if_present() {
  local cmd="$1"
  shift
  if command -v "$cmd" >/dev/null 2>&1; then
    "$@"
  else
    echo "[strict-analyze] skip: missing ${cmd}"
  fi
}

echo "[strict-analyze] repo=${ROOT_DIR}"

if [[ -f "${ROOT_DIR}/host/rust/Cargo.toml" ]]; then
  (
    cd "${ROOT_DIR}/host/rust"
    run_if_present cargo cargo fmt --all -- --check
    run_if_present cargo cargo clippy --all-targets --all-features -- -D warnings
  )
fi

if [[ -x "${ROOT_DIR}/android/gradlew" && -f "${ROOT_DIR}/android/local.properties" ]]; then
  (
    cd "${ROOT_DIR}/android"
    ./gradlew --no-daemon :app:compileReleaseJavaWithJavac lint
  )
fi

if [[ -f "${ROOT_DIR}/desktop/apps/desktop-tauri/package.json" ]]; then
  (
    cd "${ROOT_DIR}/desktop/apps/desktop-tauri"
    run_if_present npm npm exec tsc --noEmit -p tsconfig.json
  )
fi

if [[ -f "${ROOT_DIR}/desktop/apps/trainer-tauri/package.json" ]]; then
  (
    cd "${ROOT_DIR}/desktop/apps/trainer-tauri"
    run_if_present npm npm exec tsc --noEmit -p tsconfig.json
  )
fi

if command -v python >/dev/null 2>&1 || command -v python3 >/dev/null 2>&1; then
  PYTHON_BIN="$(command -v python || command -v python3)"
  if "${PYTHON_BIN}" -m ruff --version >/dev/null 2>&1; then
    "${PYTHON_BIN}" -m ruff check "${ROOT_DIR}/host" "${ROOT_DIR}/scripts"
    "${PYTHON_BIN}" -m ruff format --check "${ROOT_DIR}/host" "${ROOT_DIR}/scripts"
  else
    echo "[strict-analyze] skip: missing ruff"
  fi
  if "${PYTHON_BIN}" -m mypy --version >/dev/null 2>&1; then
    "${PYTHON_BIN}" -m mypy "${ROOT_DIR}/host" "${ROOT_DIR}/scripts" || true
  else
    echo "[strict-analyze] skip: missing mypy"
  fi
fi
