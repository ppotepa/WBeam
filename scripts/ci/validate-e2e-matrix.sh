#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

echo "[matrix] root=${ROOT_DIR}"

require_exec() {
  local p="$1"
  if [[ ! -x "$p" ]]; then
    echo "[matrix][ERROR] expected executable: $p"
    exit 1
  fi
}

require_file() {
  local p="$1"
  if [[ ! -f "$p" ]]; then
    echo "[matrix][ERROR] expected file: $p"
    exit 1
  fi
}

require_absent() {
  local p="$1"
  if [[ -e "$p" ]]; then
    echo "[matrix][ERROR] legacy wrapper path must be removed: $p"
    exit 1
  fi
}

echo "[matrix] check canonical executables"
require_exec "./wbeam"
require_exec "./desktop.sh"
require_exec "./host/scripts/run_wbeamd.sh"
require_exec "./host/scripts/run_wbeamd_debug.sh"
require_exec "./e2e/scripts/guest-prepare-installed.sh"
require_exec "./e2e/scripts/guest-stream-smoke.sh"
require_exec "./e2e/scripts/guest-portal-consent.sh"
require_exec "./e2e/scripts/guest-portal-consent-smoke.sh"

echo "[matrix] check canonical workflow files"
require_file "./desktop/apps/desktop-tauri/package.json"

echo "[matrix] check wrapper removal"
require_absent "./src"
require_absent "./proto"
require_absent "./proto_x11"

echo "[matrix] syntax checks"
bash -n \
  ./wbeam \
  ./install-wbeam \
  ./desktop.sh \
  ./host/scripts/run_wbeamd.sh \
  ./host/scripts/run_wbeamd_debug.sh \
  ./scripts/install-wizard.sh \
  ./e2e/run \
  ./e2e/scripts/guest-install-wbeam.sh \
  ./e2e/scripts/guest-stream-smoke.sh \
  ./e2e/scripts/guest-portal-consent.sh \
  ./e2e/scripts/guest-portal-consent-smoke.sh

echo "[matrix] python syntax"
python3 -m py_compile ./e2e/scripts/runner.py ./e2e/scripts/wizard.py ./e2e/scripts/portal_consent.py
find ./scripts/wizard ./e2e/scripts -name '*.py' -print0 | xargs -0 -r python3 -m py_compile

echo "[matrix] unit tests"
python3 -m unittest discover -s e2e/tests -p 'test_*.py'
python3 -m unittest discover -s e2e/tests -p 'test_wizard_assets.py'
python3 -m unittest discover -s e2e/tests -p 'test_download_isos.py'
python3 -m unittest discover -s e2e/tests -p 'test_prepare_installed.py'
python3 -m unittest e2e.tests.test_portal_consent
python3 -m unittest e2e.tests.test_e2e_runner
python3 -m unittest e2e.tests.test_wizard_assets
python3 -m unittest e2e.tests.test_stream_smoke_contract

echo "[matrix] cli smoke"
./wbeam --help >/dev/null
./desktop.sh --help >/dev/null
./install-wbeam --dry-run --backend wayland --skip-device >/dev/null
python3 e2e/scripts/download_isos.py --distro fedora-43 --missing --help >/dev/null
./e2e/run prepare-installed --distro fedora-43 --session gnome-wayland --install-backend wayland --dry-run >/dev/null
./e2e/run diagnose-installed --distro fedora-43 --session gnome-wayland >/dev/null
./e2e/run portal-diagnose --scenario fedora43-gnome-wayland-portal-h264 >/dev/null
./e2e/run prepare-portal-consent --distro fedora-43 --session gnome-wayland --backend wayland_portal --dry-run >/dev/null
./e2e/run validate >/dev/null

echo "[matrix] OK"
