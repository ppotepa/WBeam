#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${WBEAM_E2E_GUEST_ROOT:-$(pwd)}"
BACKEND="${1:-wayland_portal}"
REPORT_DIR="${WBEAM_E2E_REPORT_DIR:-$ROOT_DIR/e2e/work/portal-consent-report}"
DURATION_SEC="${WBEAM_E2E_DURATION_SEC:-30}"
DISPLAY_MODE="${WBEAM_E2E_DISPLAY_MODE:-virtual_monitor}"
APPROVAL_WAIT_SEC="${WBEAM_E2E_PORTAL_APPROVAL_WAIT_SEC:-60}"

log() {
  echo "[e2e-guest-portal-consent] $*"
}

run_attempt() {
  local attempt_dir="$1"
  mkdir -p "$attempt_dir"
  log "running stream smoke in $attempt_dir"
  WBEAM_E2E_REPORT_DIR="$attempt_dir" \
  WBEAM_E2E_DISPLAY_MODE="$DISPLAY_MODE" \
  WBEAM_E2E_DURATION_SEC="$DURATION_SEC" \
  ./e2e/scripts/guest-stream-smoke.sh "$BACKEND" "$DURATION_SEC" || true
}

write_final_summary() {
  python3 - "$REPORT_DIR" "$BACKEND" "$DISPLAY_MODE" "$APPROVAL_WAIT_SEC" <<'PY'
import json
import sys
from pathlib import Path

report_dir = Path(sys.argv[1])
backend = sys.argv[2]
display_mode = sys.argv[3]
approval_wait_sec = int(sys.argv[4])
attempt1 = report_dir / "attempt-1" / "summary.json"
attempt2 = report_dir / "attempt-2" / "summary.json"

def load(path: Path) -> dict:
    if not path.exists():
        return {}
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return {}
    return payload if isinstance(payload, dict) else {}

first = load(attempt1)
second = load(attempt2)
final = second or first

ok = bool(final.get("ok"))
blocked = bool(final.get("blocked"))
reason_code = str(final.get("reason_code") or "")
reason = str(final.get("reason") or "")
next_action = str(final.get("next_action") or "")
phase = str(final.get("phase") or "portal_consent")

payload = {
    "schema": 2,
    "ok": ok,
    "blocked": blocked,
    "backend": backend,
    "display_mode": display_mode,
    "approval_method": "manual_gnome_portal_prompt",
    "approval_wait_sec": approval_wait_sec,
    "phase": phase,
    "reason_code": reason_code,
    "reason": reason,
    "next_action": next_action,
    "attempt_1": str(attempt1),
    "attempt_2": str(attempt2),
}
report_dir.mkdir(parents=True, exist_ok=True)
(report_dir / "summary.json").write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")
PY
}

cd "$ROOT_DIR"
mkdir -p "$REPORT_DIR"

log "root=$ROOT_DIR"
log "report=$REPORT_DIR"
log "backend=$BACKEND display_mode=$DISPLAY_MODE duration=${DURATION_SEC}s approval_wait=${APPROVAL_WAIT_SEC}s"
log "Approve the GNOME ScreenCast / Virtual Monitor prompt in the VM window."
log "Do not close the VM until this command reports PASS or BLOCKED."

run_attempt "$REPORT_DIR/attempt-1"
sleep 3
run_attempt "$REPORT_DIR/attempt-2"
write_final_summary

if jq -e '.ok == true' "$REPORT_DIR/summary.json" >/dev/null 2>&1; then
  log "PASS"
  exit 0
fi

if jq -e '.reason_code == "portal_consent_required"' "$REPORT_DIR/summary.json" >/dev/null 2>&1; then
  log "BLOCKED: portal consent still required"
  exit 20
fi

log "FAIL"
exit 1
