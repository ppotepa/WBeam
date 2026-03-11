#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

export WBEAM_ANDROID_PACKAGE="com.wbeam.x11"
export WBEAM_ANDROID_ACTIVITY="com.wbeam.MainActivity"
export WBEAM_ANDROID_APP_ID_SUFFIX=".x11"
export WBEAM_ANDROID_APP_NAME="WBeam X11"

cd "$ROOT_DIR"
exec ./wbeam android build "$@"
