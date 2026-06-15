#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${WBEAM_E2E_GUEST_ROOT:-$(pwd)}"
INSTALL_BACKEND="${1:-${WBEAM_E2E_INSTALL_BACKEND:-wayland}}"
REPORT_DIR="${WBEAM_E2E_REPORT_DIR:-$ROOT_DIR/e2e/work/prepare-installed-report}"

log() { echo "[e2e-guest-prepare-installed] $*"; }

mkdir -p "$REPORT_DIR"
cd "$ROOT_DIR"

log "root=$ROOT_DIR"
log "backend=$INSTALL_BACKEND"
log "report=$REPORT_DIR"

{
  echo "root=$ROOT_DIR"
  echo "backend=$INSTALL_BACKEND"
  echo "date_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "uname=$(uname -a)"
  echo "pwd=$(pwd)"
  echo "whoami=$(whoami)"
} > "$REPORT_DIR/preflight.txt"

if [[ ! -x ./install-wbeam ]]; then
  echo "missing ./install-wbeam" | tee "$REPORT_DIR/error.txt" >&2
  exit 11
fi

log "install-wbeam help snapshot"
./install-wbeam --help > "$REPORT_DIR/install-wbeam-help.txt" 2>&1 || true

log "running install-wbeam for L1"
set +e
./install-wbeam \
  --yes \
  --backend "$INSTALL_BACKEND" \
  --skip-service \
  --skip-device \
  --report-dir "$REPORT_DIR/wizard" \
  > "$REPORT_DIR/install-wbeam.stdout.log" \
  2> "$REPORT_DIR/install-wbeam.stderr.log"
rc="$?"
set -e

echo "$rc" > "$REPORT_DIR/install-wbeam.exit-code"

if [[ "$rc" -ne 0 ]]; then
  log "install-wbeam failed rc=$rc"
  exit "$rc"
fi

log "validating host build artifacts"
server_path="host/rust/target/release/wbeamd-server"
streamer_path="host/rust/target/release/wbeamd-streamer"

if [[ ! -x "$server_path" ]]; then
  echo "missing executable: $server_path" | tee "$REPORT_DIR/error.txt" >&2
  exit 21
fi

if [[ ! -x "$streamer_path" ]]; then
  echo "missing executable: $streamer_path" | tee "$REPORT_DIR/error.txt" >&2
  exit 22
fi

python3 - <<'PY' "$REPORT_DIR/summary.json" "$INSTALL_BACKEND" "$server_path" "$streamer_path"
import json, os, sys

out, backend, server, streamer = sys.argv[1:]
payload = {
  "schema": 1,
  "ok": True,
  "backend": backend,
  "server_path": server,
  "streamer_path": streamer,
  "server_exists": os.path.exists(server),
  "streamer_exists": os.path.exists(streamer),
}
with open(out, "w", encoding="utf-8") as fh:
  json.dump(payload, fh, indent=2, sort_keys=True)
PY

log "PASS"
