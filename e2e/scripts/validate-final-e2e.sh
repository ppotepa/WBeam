#!/usr/bin/env bash
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

python3 -m py_compile \
  e2e/scripts/runner.py \
  e2e/scripts/wizard.py \
  e2e/scripts/report.py \
  e2e/scripts/portal_consent.py \
  e2e/scripts/assert_green_run.py \
  e2e/scripts/finalize_e2e.py \
  e2e/scripts/validate_portal_consented_asset.py

bash -n e2e/scripts/guest-portal-consent.sh
bash -n e2e/scripts/guest-portal-consent-smoke.sh
bash -n e2e/scripts/final-operator-closure.sh
python3 -m unittest \
  e2e.tests.test_e2e_runner \
  e2e.tests.test_wizard_assets \
  e2e.tests.test_portal_consent

./scripts/ci/validate-e2e-matrix.sh
python3 e2e/scripts/validate_portal_consented_asset.py \
  --distro fedora-43 \
  --session gnome-wayland \
  --base-dir e2e/images/base \
  --json \
  --allow-missing >/tmp/wbeam-portal-asset-validation.json
./e2e/run status --json >/tmp/wbeam-e2e-status.json
echo "final e2e static validation OK"
