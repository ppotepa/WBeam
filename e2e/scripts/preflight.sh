#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STRICT=0

if [[ "${1:-}" == "--strict" ]]; then
  STRICT=1
fi

failures=0

note() {
  echo "[e2e-preflight] $*"
}

missing() {
  echo "[e2e-preflight][WARN] $*" >&2
  failures=$((failures + 1))
}

need_cmd() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    missing "missing command: $cmd"
  fi
}

note "root=${ROOT_DIR}"
"${ROOT_DIR}/e2e/run" validate
note "base_dir=${WBEAM_E2E_BASE_DIR:-${ROOT_DIR}/e2e/images/base}"
note "work_dir=${WBEAM_E2E_WORK_DIR:-${ROOT_DIR}/e2e/work}"
note "report_dir=${WBEAM_E2E_REPORT_DIR:-${ROOT_DIR}/e2e/reports}"

need_cmd python3
need_cmd qemu-system-x86_64
need_cmd qemu-img
need_cmd ssh
need_cmd ssh-keygen
need_cmd rsync

if [[ ! -e /dev/kvm ]]; then
  missing "/dev/kvm is missing; VM tests will be slow or unavailable"
elif [[ ! -r /dev/kvm || ! -w /dev/kvm ]]; then
  missing "/dev/kvm exists but is not readable/writable by this user"
else
  note "kvm=ok"
fi

if command -v genisoimage >/dev/null 2>&1; then
  note "seed iso tool=genisoimage"
elif command -v xorriso >/dev/null 2>&1; then
  note "seed iso tool=xorriso"
elif command -v cloud-localds >/dev/null 2>&1; then
  note "seed iso tool=cloud-localds"
else
  missing "missing seed ISO helper: install genisoimage, xorriso, or cloud-image-utils"
fi

while IFS= read -r env_name; do
  [[ -n "$env_name" ]] || continue
  iso_path="${!env_name:-}"
  if [[ -z "$iso_path" ]]; then
    missing "$env_name is not set"
  elif [[ ! -f "$iso_path" ]]; then
    missing "$env_name points to a missing file: $iso_path"
  else
    note "$env_name=$iso_path"
  fi
done < <("${ROOT_DIR}/e2e/run" env)

if [[ "$STRICT" -eq 1 && "$failures" -ne 0 ]]; then
  echo "[e2e-preflight] FAILED (${failures} warnings in strict mode)" >&2
  exit 1
fi

if [[ "$failures" -eq 0 ]]; then
  note "OK"
else
  note "completed with ${failures} warning(s)"
fi
