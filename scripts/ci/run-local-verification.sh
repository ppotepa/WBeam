#!/usr/bin/env bash
# Verify WBeam from a checkout when hosted CI runners are unavailable.
# This script never publishes releases, mutates the kernel or needs signing keys.
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MODE=quick
REPORT_DIR="${WBEAM_VERIFICATION_DIR:-${XDG_STATE_HOME:-${HOME:-/tmp}/.local/state}/wbeam/verification/$(date -u +%Y%m%dT%H%M%SZ)}"

usage() {
  cat <<'HELP'
Usage: bash scripts/ci/run-local-verification.sh [--quick|--full] [--report-dir PATH]
  --quick  Bash, Python and desktop TypeScript tests/build (default)
  --full   Quick plus host Rust, Tauri Rust and Android Gradle tests/build
  --list   Show the exact test cases without running or writing files
  --help   Show this help

Reports: <report-dir>/summary.tsv, metadata.txt and one .log per test.
Missing prerequisites cause a BLOCKED result and a nonzero exit code.
This is *not* a replacement for hardware EVDI/Portal acceptance.
HELP
}

show_cases() {
  printf '%s\n' shell_syntax release_shell python_e2e desktop_install desktop_test desktop_build
  if [[ "$MODE" == full ]]; then
    printf '%s\n' host_rust host_streamer tauri_rust tauri_check android_debug
  fi
}

while (($#)); do
  case "$1" in
    --quick) MODE=quick; shift ;;
    --full) MODE=full; shift ;;
    --list) show_cases; exit 0 ;;
    --help|-h) usage; exit 0 ;;
    --report-dir)
      (($# >= 2)) || { echo '[verify] --report-dir requires a path' >&2; exit 2; }
      REPORT_DIR="$2"
      shift 2 ;;
    *) echo "[verify] Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

mkdir -p "$REPORT_DIR"
REPORT_DIR="$(cd "$REPORT_DIR" && pwd)"
SUMMARY="$REPORT_DIR/summary.tsv"
printf 'result\ttest\tlog\n' > "$SUMMARY"
{
  printf 'mode=%s\n' "$MODE"
  printf 'timestamp_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf 'commit_sha=%s\n' "$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null || printf 'unavailable')"
  printf 'root=%s\n' "$ROOT_DIR"
} > "$REPORT_DIR/metadata.txt"
failed=0
blocked=0
passed=0

record() {
  local result="$1" test_name="$2" log_file="$3"
  printf '%s\t%s\t%s\n' "$result" "$test_name" "$log_file" >> "$SUMMARY"
  printf '[verify] %-7s %-20s %s\n' "$result" "$test_name" "$log_file"
  case "$result" in
    PASS) passed=$((passed + 1)) ;;
    FAIL) failed=$((failed + 1)) ;;
    BLOCKED) blocked=$((blocked + 1)) ;;
  esac
}

run_step() {
  local name="$1" prerequisite="$2" workdir="$3"
  shift 3
  local log_file="$REPORT_DIR/$name.log"
  if ! command -v "$prerequisite" >/dev/null 2>&1; then
    printf '[verify] missing required command: %s\n' "$prerequisite" > "$log_file"
    record BLOCKED "$name" "$log_file"
    return 0
  fi
  if [[ ! -d "$workdir" ]]; then
    printf '[verify] missing project directory: %s\n' "$workdir" > "$log_file"
    record BLOCKED "$name" "$log_file"
    return 0
  fi
  if (cd "$workdir" && "$@") > "$log_file" 2>&1; then
    record PASS "$name" "$log_file"
  else
    record FAIL "$name" "$log_file"
  fi
}

run_step shell_syntax bash "$ROOT_DIR" bash -c \
  'bash -n wbeam scripts/virtual-deps-check.sh scripts/virtual-deps-install.sh scripts/evdi-compat.sh scripts/ci/*.sh'
run_step release_shell bash "$ROOT_DIR" bash -c \
  'set -Eeuo pipefail; bash scripts/ci/test-release-version.sh; bash scripts/ci/check-version-consistency.sh; bash scripts/ci/test-evdi-diagnostics.sh; bash scripts/ci/test-virtual-deps-runtime.sh; bash scripts/ci/test-virtual-deps-install-dry-run.sh; bash scripts/ci/test-deb-target.sh; bash scripts/ci/test-publication-contract.sh; bash scripts/ci/test-control-bind-policy.sh; bash scripts/ci/test-daemon-log-location.sh; bash scripts/ci/test-systemd-offline-start.sh'
run_step python_e2e python3 "$ROOT_DIR" python3 -m unittest discover -s e2e/tests -v
run_step desktop_install npm "$ROOT_DIR/desktop/apps/desktop-tauri" npm ci
run_step desktop_test npm "$ROOT_DIR/desktop/apps/desktop-tauri" npm test
run_step desktop_build npm "$ROOT_DIR/desktop/apps/desktop-tauri" npm run build

if [[ "$MODE" == full ]]; then
  run_step host_rust cargo "$ROOT_DIR" cargo test --locked --manifest-path host/rust/Cargo.toml -p wbeamd-api -p wbeamd-core -p wbeamd-server
  run_step host_streamer cargo "$ROOT_DIR" bash -c 'set -Eeuo pipefail; cargo check --locked --manifest-path host/rust/Cargo.toml -p wbeamd-streamer --no-default-features --features evdi; cargo test --locked --manifest-path host/rust/Cargo.toml -p wbeamd-streamer --no-default-features --features evdi'
  run_step tauri_rust cargo "$ROOT_DIR" cargo test --locked --manifest-path desktop/apps/desktop-tauri/src-tauri/Cargo.toml
  run_step tauri_check cargo "$ROOT_DIR" cargo check --locked --manifest-path desktop/apps/desktop-tauri/src-tauri/Cargo.toml
  if [[ ! -f "$ROOT_DIR/android/gradlew" ]]; then
    echo 'Gradle wrapper missing' > "$REPORT_DIR/android_debug.log"
    record BLOCKED android_debug "$REPORT_DIR/android_debug.log"
  else
    run_step android_debug java "$ROOT_DIR/android" bash ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
  fi
fi

printf '[verify] Summary: %d passed, %d failed, %d blocked.\n' "$passed" "$failed" "$blocked"
printf '[verify] Results: %s\n' "$SUMMARY"
echo '[verify] Review and redact log files before attaching to public GitHub issues.'
if (( failed > 0 )); then exit 1; fi
if (( blocked > 0 )); then exit 2; fi
exit 0
