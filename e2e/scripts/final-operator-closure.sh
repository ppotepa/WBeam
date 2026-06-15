#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

RUN_ID="${WBEAM_FINAL_RUN_ID:-FEDORA-PORTAL-GREEN-001}"
SCENARIO="fedora43-gnome-wayland-portal-h264"

echo "[final] static validation"
PYTHONPATH=. ./e2e/scripts/validate-final-e2e.sh

echo "[final] preparing portal consented image"
./e2e/run prepare-portal-consent \
  --distro fedora-43 \
  --session gnome-wayland \
  --backend wayland_portal \
  --display "${WBEAM_PORTAL_DISPLAY:-auto}" \
  --approval-timeout-sec "${WBEAM_PORTAL_APPROVAL_TIMEOUT_SEC:-900}" \
  --approval-poll-sec "${WBEAM_PORTAL_APPROVAL_POLL_SEC:-5}" \
  --live \
  --promote

echo "[final] validating portal consented asset"
python3 e2e/scripts/validate_portal_consented_asset.py \
  --distro fedora-43 \
  --session gnome-wayland \
  --base-dir e2e/images/base \
  --json

echo "[final] running Fedora Wayland Portal green scenario"
./e2e/run run \
  --run-id "$RUN_ID" \
  --report-dir e2e/reports \
  --use-installed \
  --live \
  --scenario "$SCENARIO"

echo "[final] asserting green run"
./e2e/run assert-run \
  --run-id "$RUN_ID" \
  --scenario "$SCENARIO" \
  --report-dir e2e/reports \
  --require-portal-consented \
  --min-bytes 1 \
  --json | tee "e2e/reports/$RUN_ID/assert-green.json"

echo "[final] closing E2E MVP profile"
./e2e/run close \
  --profile fedora-mvp \
  --live \
  --json | tee "e2e/reports/$RUN_ID/final-close.json"

echo "[final] DONE"
