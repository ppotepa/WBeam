#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

print_help() {
  cat <<'EOF'
Deploy Android app to all connected adb devices.

Usage:
  ./deploy-android-all.sh [--skip-build]

Options:
  --skip-build   Reuse existing APK, skip ./wbeam android build
  -h, --help     Show this help
EOF
}

skip_build=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-build)
      skip_build=1
      shift
      ;;
    -h|--help)
      print_help
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      print_help >&2
      exit 1
      ;;
  esac
done

if [[ "$skip_build" -eq 1 ]]; then
  WBEAM_ANDROID_SKIP_BUILD=1 "$ROOT_DIR/wbeam" android deploy-all
else
  "$ROOT_DIR/wbeam" android deploy-all
fi
