#!/usr/bin/env bash
set -euo pipefail

CONTROL_PORT="${1:-5001}"
BASE="http://127.0.0.1:${CONTROL_PORT}"

need() { command -v "$1" >/dev/null 2>&1 || { echo "missing command: $1" >&2; exit 1; }; }
need curl
need jq

check_endpoint() {
  local path="$1"
  curl -sSf "$BASE$path" >/tmp/wbeam_test_resp.json
  jq -e '.state and .active_config and .host_name and .uptime != null and .last_error != null' /tmp/wbeam_test_resp.json >/dev/null
}

echo "[wbeam-test] API contract checks"
check_endpoint /status
check_endpoint /health
check_endpoint /presets
check_endpoint /metrics


echo "[wbeam-test] apply presets"
for profile in lowlatency balanced ultra; do
  curl -sSf -X POST "$BASE/apply" -H 'Content-Type: application/json' -d "{\"profile\":\"$profile\"}" >/tmp/wbeam_apply.json
  jq -e --arg p "$profile" '.active_config.profile == $p' /tmp/wbeam_apply.json >/dev/null
  echo "  ok: $profile"
done

echo "[wbeam-test] idempotent stop"
pre_stop_count=$(curl -sSf "$BASE/metrics" | jq -r '.metrics.stop_count')
curl -sSf -X POST "$BASE/stop" >/dev/null
post_stop_count=$(curl -sSf "$BASE/metrics" | jq -r '.metrics.stop_count')
if [[ "$pre_stop_count" != "$post_stop_count" ]]; then
  echo "expected idempotent stop when already idle" >&2
  exit 1
fi

echo "[wbeam-test] PASS"
cat <<MANUAL
Manual matrix still required:
1. 1080p60 lowlatency stream
2. 1080p60 balanced stream
3. 1440p60 ultra stream
4. USB reconnect while streaming
5. host daemon restart while app is open
6. revoke/restore screen-share permission in KDE portal
7. 30 min run without manual intervention
MANUAL
