#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

echo "[reset] root=$ROOT_DIR"

step() {
  echo
  echo "[reset] $1"
}

step "stop local daemon wrappers"
./wbeam daemon down >/dev/null 2>&1 || true

step "kill stale debug/daemon processes"
pkill -f run_wbeamd_debug.sh >/dev/null 2>&1 || true
pkill -f "wbeamd-server" >/dev/null 2>&1 || true
pkill -f "wbeamd-streamer" >/dev/null 2>&1 || true

step "clear stale lock"
rm -f /tmp/wbeamd.lock

step "bump unified build version"
NEW_VER="$(./wbeam version new)"
echo "[reset] version=$NEW_VER"

step "build host release with unified version"
WBEAM_BUILD_REV="$NEW_VER" ./wbeam host build

step "reset user service unit (systemd --user)"
if command -v systemctl >/dev/null 2>&1; then
  systemctl --user stop wbeam-daemon >/dev/null 2>&1 || true
  systemctl --user disable wbeam-daemon >/dev/null 2>&1 || true
  rm -f "$HOME/.config/systemd/user/wbeam-daemon.service"
  systemctl --user daemon-reload >/dev/null 2>&1 || true
else
  echo "[reset] WARN: systemctl not found, skipping user service reset"
fi

step "fresh android deploy (all ADB devices)"
WBEAM_BUILD_REV="$NEW_VER" WBEAM_ANDROID_FORCE_INSTALL=1 ./wbeam android deploy-all

step "doctor snapshot"
./wbeam version doctor || true

echo
echo "[reset] DONE"
echo "[reset] Next in GUI: Install service -> Start service"
