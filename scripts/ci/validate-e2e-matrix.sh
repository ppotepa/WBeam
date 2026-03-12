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
require_exec "./trainer.sh"
require_exec "./desktop.sh"
require_exec "./host/scripts/run_wbeamd.sh"
require_exec "./host/scripts/run_wbeamd_debug.sh"

echo "[matrix] check canonical workflow files"
require_file "./host/training/wizard.py"
require_file "./desktop/apps/trainer-tauri/package.json"
require_file "./desktop/apps/desktop-tauri/package.json"

echo "[matrix] check historical archive markers"
require_file "./archive/legacy/proto/README.md"
require_file "./archive/legacy/proto_x11/README.md"

echo "[matrix] check wrapper removal"
require_absent "./src"
require_absent "./proto"
require_absent "./proto_x11"

echo "[matrix] syntax checks"
bash -n \
  ./wbeam \
  ./trainer.sh \
  ./desktop.sh \
  ./host/scripts/run_wbeamd.sh \
  ./host/scripts/run_wbeamd_debug.sh

echo "[matrix] cli smoke"
./wbeam --help >/dev/null
./trainer.sh --help >/dev/null
./desktop.sh --help >/dev/null

echo "[matrix] python smoke"
python3 -m py_compile \
  ./host/training/wizard.py

echo "[matrix] OK"
