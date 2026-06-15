#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="${WBEAM_E2E_GUEST_ROOT:-$(pwd)}"
BACKEND="${1:-${WBEAM_E2E_BACKEND:-benchmark_game}}"
DURATION_SEC="${2:-${WBEAM_E2E_DURATION_SEC:-60}}"
CONTROL_PORT="${WBEAM_E2E_CONTROL_PORT:-5001}"
STREAM_PORT="${WBEAM_E2E_STREAM_PORT:-5000}"
DISPLAY_MODE="${WBEAM_E2E_DISPLAY_MODE:-}"
SIZE="${WBEAM_E2E_SIZE:-1280x800}"
FPS="${WBEAM_E2E_FPS:-30}"
BITRATE_KBPS="${WBEAM_E2E_BITRATE_KBPS:-10000}"
ENCODER="${WBEAM_E2E_ENCODER:-h264}"
REPORT_DIR="${WBEAM_E2E_REPORT_DIR:-$ROOT_DIR/e2e/reports/guest-smoke-$(date -u +%Y%m%dT%H%M%SZ)}"
WARMUP_SEC="${WBEAM_E2E_STREAM_WARMUP_SEC:-3}"
MIN_STREAM_BYTES="${WBEAM_E2E_MIN_STREAM_BYTES:-1}"

DAEMON_PID=""
EXIT_CODE=0
FAIL_PHASE=""
FAIL_REASON_CODE=""
FAIL_REASON=""
NEXT_ACTION=""
FAIL_BLOCKED="false"
STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

log() {
  echo "[e2e-guest-stream] $*"
}

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    fail 2 "dependency" "missing command: $1" "Install missing tool in L1 or add it to seed/install deps."
  fi
}

json_file_or_empty() {
  local path="$1"
  if [[ -f "$path" ]]; then
    cat "$path"
  else
    echo '{}'
  fi
}

api_capture_backend() {
  case "$BACKEND" in
    benchmark_game)
      echo ""
      ;;
    wayland|wayland_portal)
      echo "wayland_portal"
      ;;
    evdi)
      echo "evdi"
      ;;
    x11|x11_gst)
      echo "x11_gst"
      ;;
    *)
      echo "$BACKEND"
      ;;
  esac
}

ensure_graphical_context() {
  if [[ -n "${WAYLAND_DISPLAY:-}" || -n "${DISPLAY:-}" ]]; then
    return 0
  fi

  if [[ "${WBEAM_GUEST_STREAM_REEXECED:-0}" == "1" ]]; then
    return 0
  fi

  local runas_remote="$ROOT_DIR/runas-remote"
  if [[ ! -x "$runas_remote" ]]; then
    log "graphical session env missing and runas-remote unavailable; continuing for diagnostics"
    return 0
  fi

  export RUNAS_REMOTE_PASSTHROUGH_ENV="WBEAM_E2E_GUEST_ROOT,WBEAM_E2E_BACKEND,WBEAM_E2E_DURATION_SEC,WBEAM_E2E_CONTROL_PORT,WBEAM_E2E_STREAM_PORT,WBEAM_E2E_DISPLAY_MODE,WBEAM_E2E_SIZE,WBEAM_E2E_FPS,WBEAM_E2E_BITRATE_KBPS,WBEAM_E2E_ENCODER,WBEAM_E2E_REPORT_DIR,WBEAM_E2E_STREAM_WARMUP_SEC,WBEAM_E2E_MIN_STREAM_BYTES"
  log "graphical session env missing; relaunching via runas-remote"
  exec env WBEAM_GUEST_STREAM_REEXECED=1 "$runas_remote" "$0"
}

apply_url() {
  echo "http://127.0.0.1:${CONTROL_PORT}/v1/apply"
}

health_url() {
  echo "http://127.0.0.1:${CONTROL_PORT}/v1/health"
}

status_url() {
  echo "http://127.0.0.1:${CONTROL_PORT}/v1/status"
}

metrics_url() {
  echo "http://127.0.0.1:${CONTROL_PORT}/v1/metrics"
}

start_url() {
  local query="display_mode=${DISPLAY_MODE}"
  local capture_backend
  capture_backend="$(api_capture_backend)"
  if [[ -n "$capture_backend" ]]; then
    query="${query}&capture_backend=${capture_backend}"
  fi
  echo "http://127.0.0.1:${CONTROL_PORT}/v1/start?${query}"
}

collect_ports() {
  {
    if command -v ss >/dev/null 2>&1; then
      ss -ltnp 2>/dev/null || true
    elif command -v netstat >/dev/null 2>&1; then
      netstat -ltnp 2>/dev/null || true
    fi
  } >"$REPORT_DIR/ports.txt"
}

collect_session_probes() {
  python3 - "$REPORT_DIR/session-probe.json" "$REPORT_DIR/pipewire-probe.json" "$REPORT_DIR/portal-probe.json" <<'PY'
import json
import os
import subprocess
import sys

session_path, pipewire_path, portal_path = sys.argv[1:]

def sh(cmd):
    try:
        return subprocess.run(cmd, capture_output=True, text=True, check=False).stdout.strip()
    except FileNotFoundError:
        return ""

def is_active(cmd):
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, check=False)
        return proc.stdout.strip()
    except FileNotFoundError:
        return ""

def write(path, payload):
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(payload, fh, indent=2, sort_keys=True)

write(session_path, {
    "xdg_session_type": os.environ.get("XDG_SESSION_TYPE", ""),
    "wayland_display": os.environ.get("WAYLAND_DISPLAY", ""),
    "display": os.environ.get("DISPLAY", ""),
    "user": sh(["id", "-un"]),
    "uid": sh(["id", "-u"]),
    "loginctl": sh(["loginctl", "list-sessions", "--no-legend"]),
    "processes": sh(["bash", "-lc", "ps -eo pid,comm,args | grep -E 'gnome-shell|pipewire|wireplumber|xdg-desktop-portal' | grep -v grep"]),
})
write(pipewire_path, {
    "pipewire_user": is_active(["systemctl", "--user", "is-active", "pipewire"]),
    "wireplumber_user": is_active(["systemctl", "--user", "is-active", "wireplumber"]),
    "pipewire_pulse_user": is_active(["systemctl", "--user", "is-active", "pipewire-pulse"]),
    "pactl_info": sh(["pactl", "info"]),
})
write(portal_path, {
    "xdg_desktop_portal_user": is_active(["systemctl", "--user", "is-active", "xdg-desktop-portal"]),
    "xdg_desktop_portal_gnome_user": is_active(["systemctl", "--user", "is-active", "xdg-desktop-portal-gnome"]),
    "bus_names": sh(["busctl", "--user", "list"]),
})
PY
}

collect_after_failure() {
  curl -fsS --max-time 2 "$(status_url)" | jq . >"$REPORT_DIR/status-after.json" || true
  curl -fsS --max-time 2 "$(metrics_url)" | jq . >"$REPORT_DIR/metrics-after.json" || true
  collect_ports
  collect_session_probes
}

wait_health() {
  local i
  for i in $(seq 1 80); do
    if curl -fsS --max-time 1 "$(health_url)" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.5
  done
  return 1
}

start_daemon_if_needed() {
  if curl -fsS --max-time 1 "$(health_url)" >/dev/null 2>&1; then
    log "daemon already reachable"
    return 0
  fi

  log "starting local daemon"
  (
    cd "$ROOT_DIR"
    WBEAM_RUST_LOG_DIR="$REPORT_DIR" ./host/scripts/run_wbeamd.sh "$CONTROL_PORT" "$STREAM_PORT"
  ) >"$REPORT_DIR/daemon.stdout.log" 2>"$REPORT_DIR/daemon.stderr.log" &
  DAEMON_PID="$!"

  if ! wait_health; then
    fail 3 "daemon_not_healthy" "daemon did not become healthy" "Inspect daemon.stderr.log and run_wbeamd.sh."
  fi
}

stream_client() {
  python3 - "$STREAM_PORT" "$DURATION_SEC" "$REPORT_DIR/client.json" <<'PY'
import errno
import json
import socket
import sys
import time

port = int(sys.argv[1])
duration = int(sys.argv[2])
out_path = sys.argv[3]
deadline_sec = duration
deadline = time.monotonic() + duration
connected_at = None
first_byte_at = None
bytes_read = 0
chunks = 0
short_reads = 0
attempts = 0
connection_refused = 0
last_error = ""
stream_host = "127.0.0.1"

while time.monotonic() < deadline:
    attempts += 1
    try:
        with socket.create_connection((stream_host, port), timeout=1.0) as sock:
            connected_at = time.monotonic()
            sock.settimeout(1.0)
            while time.monotonic() < deadline:
                try:
                    data = sock.recv(65536)
                except socket.timeout:
                    continue
                except OSError as exc:
                    last_error = str(exc)
                    break
                if not data:
                    break
                if first_byte_at is None:
                    first_byte_at = time.monotonic()
                bytes_read += len(data)
                chunks += 1
                if len(data) < 65536:
                    short_reads += 1
            break
    except OSError as exc:
        last_error = str(exc)
        if getattr(exc, "errno", None) == errno.ECONNREFUSED or "Connection refused" in str(exc):
            connection_refused += 1
        time.sleep(0.5)

payload = {
    "connected": connected_at is not None,
    "bytes_read": bytes_read,
    "chunks": chunks,
    "attempts": attempts,
    "connection_refused": connection_refused,
    "connected_at_monotonic": connected_at,
    "first_byte_at_monotonic": first_byte_at,
    "time_to_first_byte_sec": round(first_byte_at - connected_at, 3) if connected_at is not None and first_byte_at is not None else None,
    "last_error": last_error,
    "short_reads": short_reads,
    "deadline_sec": deadline_sec,
    "stream_host": stream_host,
    "stream_port": port,
}
with open(out_path, "w", encoding="utf-8") as fh:
    json.dump(payload, fh, indent=2, sort_keys=True)

if connected_at is None or bytes_read <= 0:
    sys.exit(1)
PY
}

write_summary() {
  local ok="$1"
  local phase="${2:-$FAIL_PHASE}"
  local reason_code="${3:-$FAIL_REASON_CODE}"
  local reason="${4:-$FAIL_REASON}"
  local exit_code="${5:-$EXIT_CODE}"
  local blocked="${6:-$FAIL_BLOCKED}"

  mkdir -p "$REPORT_DIR"

  for file in \
    client.json \
    health-before.json \
    metrics-before.json \
    status-before.json \
    status-after-start.json \
    metrics-after-start.json \
    status-after-start-3s.json \
    metrics-after-start-3s.json \
    status-after.json \
    metrics-after.json \
    apply.json \
    start.json \
    session-probe.json \
    portal-probe.json \
    pipewire-probe.json \
    host-probe.json \
    virtual-probe.json
  do
    if [[ ! -f "$REPORT_DIR/$file" ]]; then
      echo '{}' >"$REPORT_DIR/$file"
    fi
  done

  [[ -f "$REPORT_DIR/metrics.jsonl" ]] || : >"$REPORT_DIR/metrics.jsonl"
  [[ -f "$REPORT_DIR/daemon.stdout.log" ]] || : >"$REPORT_DIR/daemon.stdout.log"
  [[ -f "$REPORT_DIR/daemon.stderr.log" ]] || : >"$REPORT_DIR/daemon.stderr.log"
  [[ -f "$REPORT_DIR/ports.txt" ]] || : >"$REPORT_DIR/ports.txt"

  python3 - "$REPORT_DIR/summary.json" \
    "$ok" \
    "$REPORT_DIR/client.json" \
    "$REPORT_DIR/health-before.json" \
    "$REPORT_DIR/host-probe.json" \
    "$REPORT_DIR/virtual-probe.json" \
    "$REPORT_DIR/apply.json" \
    "$REPORT_DIR/start.json" \
    "$REPORT_DIR/status-before.json" \
    "$REPORT_DIR/metrics-before.json" \
    "$REPORT_DIR/status-after-start.json" \
    "$REPORT_DIR/metrics-after-start.json" \
    "$REPORT_DIR/status-after-start-3s.json" \
    "$REPORT_DIR/metrics-after-start-3s.json" \
    "$REPORT_DIR/status-after.json" \
    "$REPORT_DIR/metrics-after.json" \
    "$REPORT_DIR/session-probe.json" \
    "$REPORT_DIR/portal-probe.json" \
    "$REPORT_DIR/pipewire-probe.json" \
    "$REPORT_DIR/ports.txt" \
    "$BACKEND" \
    "$(api_capture_backend)" \
    "$DISPLAY_MODE" \
    "$phase" \
    "$reason_code" \
    "$reason" \
    "$NEXT_ACTION" \
    "$STARTED_AT" \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    "$DURATION_SEC" \
    "$exit_code" \
    "$blocked" \
    <<'PY'
import json
import os
import sys

(
    out_path,
    ok_arg,
    client_path,
    health_before_path,
    host_probe_path,
    virtual_probe_path,
    apply_path,
    start_path,
    status_before_path,
    metrics_before_path,
    status_after_start_path,
    metrics_after_start_path,
    status_after_start_3s_path,
    metrics_after_start_3s_path,
    status_after_path,
    metrics_after_path,
    session_probe_path,
    portal_probe_path,
    pipewire_probe_path,
    ports_path,
    backend,
    api_capture_backend,
    display_mode,
    phase,
    reason_code,
    reason,
    next_action,
    started_at,
    ended_at,
    duration_sec,
    exit_code,
    blocked,
) = sys.argv[1:]

def load(path):
    try:
        with open(path, "r", encoding="utf-8") as fh:
            return json.load(fh)
    except Exception:
        return {}

def load_text(path):
    try:
        with open(path, "r", encoding="utf-8") as fh:
            return fh.read()
    except Exception:
        return ""

payload = {
    "schema": 2,
    "ok": ok_arg.lower() == "true",
    "backend": backend,
    "api_capture_backend": api_capture_backend,
    "display_mode": display_mode,
    "phase": phase,
    "reason_code": reason_code,
    "reason": reason,
    "next_action": next_action,
    "started_at": started_at,
    "ended_at": ended_at,
    "duration_sec": int(duration_sec),
    "exit_code": int(exit_code),
    "blocked": blocked.lower() == "true",
    "client": load(client_path),
    "health_before": load(health_before_path),
    "host_probe": load(host_probe_path),
    "virtual_probe": load(virtual_probe_path),
    "apply": load(apply_path),
    "start": load(start_path),
    "status_before": load(status_before_path),
    "metrics_before": load(metrics_before_path),
    "status_after_start": load(status_after_start_path),
    "metrics_after_start": load(metrics_after_start_path),
    "status_after_start_3s": load(status_after_start_3s_path),
    "metrics_after_start_3s": load(metrics_after_start_3s_path),
    "status_after": load(status_after_path),
    "metrics_after": load(metrics_after_path),
    "session_probe": load(session_probe_path),
    "portal_probe": load(portal_probe_path),
    "pipewire_probe": load(pipewire_probe_path),
    "ports": load_text(ports_path),
    "artifacts": {
        "client": "client.json",
        "health_before": "health-before.json",
        "host_probe": "host-probe.json",
        "virtual_probe": "virtual-probe.json",
        "apply": "apply.json",
        "start": "start.json",
        "status_before": "status-before.json",
        "metrics_before": "metrics-before.json",
        "status_after_start": "status-after-start.json",
        "metrics_after_start": "metrics-after-start.json",
        "status_after_start_3s": "status-after-start-3s.json",
        "metrics_after_start_3s": "metrics-after-start-3s.json",
        "status_after": "status-after.json",
        "metrics_after": "metrics-after.json",
        "metrics_samples": "metrics.jsonl",
        "daemon_stdout": "daemon.stdout.log",
        "daemon_stderr": "daemon.stderr.log",
        "session_probe": "session-probe.json",
        "portal_probe": "portal-probe.json",
        "pipewire_probe": "pipewire-probe.json",
        "ports": "ports.txt",
    },
}
with open(out_path, "w", encoding="utf-8") as fh:
    json.dump(payload, fh, indent=2, sort_keys=True)
PY
}

classify_stream_failure_code() {
  local state last_error backend display_mode connected bytes frame_in frame_out portal_active pipewire_active virtual_supported start_state status_before state_after_start
  local portal_signal_present portal_log
  backend="$BACKEND"
  display_mode="$DISPLAY_MODE"
  state="$(jq -r '.state // .base.state // empty' "$REPORT_DIR/status-after.json" 2>/dev/null || true)"
  status_before="$(jq -r '.state // .base.state // empty' "$REPORT_DIR/status-before.json" 2>/dev/null || true)"
  state_after_start="$(jq -r '.state // .base.state // empty' "$REPORT_DIR/status-after-start.json" 2>/dev/null || true)"
  last_error="$(jq -r '.last_error // .base.last_error // empty' "$REPORT_DIR/status-after.json" 2>/dev/null || true)"
  connected="$(jq -r '.connected // false' "$REPORT_DIR/client.json" 2>/dev/null || echo false)"
  bytes="$(jq -r '.bytes_read // 0' "$REPORT_DIR/client.json" 2>/dev/null || echo 0)"
  frame_in="$(jq -r '.metrics.frame_in // .metrics.metrics.frame_in // .frame_in // 0' "$REPORT_DIR/metrics-after.json" 2>/dev/null || echo 0)"
  frame_out="$(jq -r '.metrics.frame_out // .metrics.metrics.frame_out // .frame_out // 0' "$REPORT_DIR/metrics-after.json" 2>/dev/null || echo 0)"
  portal_active="$(jq -r '.xdg_desktop_portal_user // empty' "$REPORT_DIR/portal-probe.json" 2>/dev/null || true)"
  pipewire_active="$(jq -r '.pipewire_user // empty' "$REPORT_DIR/pipewire-probe.json" 2>/dev/null || true)"
  virtual_supported="$(jq -r '.virtual_supported // false' "$REPORT_DIR/virtual-probe.json" 2>/dev/null || echo false)"
  portal_signal_present="false"

  for portal_log in "$REPORT_DIR"/wbeamd-rust.log.* "$REPORT_DIR"/daemon.stderr.log "$REPORT_DIR"/daemon.stdout.log; do
    [[ -f "$portal_log" ]] || continue
    if grep -Eq "Authorization required|portal session source_type=virtual|Requesting ScreenCast portal session" "$portal_log" 2>/dev/null; then
      portal_signal_present="true"
      break
    fi
  done

  if [[ "$backend" == "wayland" || "$backend" == "wayland_portal" ]]; then
    if [[ "$display_mode" == "virtual_monitor" && "$portal_active" == "active" && "$pipewire_active" == "active" && "$virtual_supported" == "true" ]]; then
      if [[ "$portal_signal_present" == "true" || "$last_error" == *"timeout waiting for streaming signal"* || "$state" == "IDLE" || "$state_after_start" == "STARTING" || "$status_before" == "IDLE" ]]; then
        if [[ "$connected" != "true" && "$bytes" == "0" && "$frame_in" == "0" && "$frame_out" == "0" ]]; then
          echo "portal_consent_required"
          return 0
        fi
      fi
    fi
  fi

  if [[ "$backend" == "wayland" || "$backend" == "wayland_portal" ]]; then
    if [[ "$portal_active" != "active" ]]; then
      echo "portal_unavailable"
      return 0
    fi
  fi

  if [[ -z "${WAYLAND_DISPLAY:-}" && -z "${DISPLAY:-}" ]]; then
    echo "graphical_session_missing"
    return 0
  fi

  if [[ "$connected" != "true" && "$bytes" == "0" ]]; then
    if [[ "$backend" == "wayland" || "$backend" == "wayland_portal" ]]; then
      if [[ "$display_mode" == "virtual_monitor" && "$portal_active" == "active" && "$pipewire_active" == "active" && "$virtual_supported" == "true" ]]; then
        echo "portal_consent_required"
        return 0
      fi
    fi
    echo "stream_port_not_open"
    return 0
  fi

  if [[ "$state" != "STREAMING" && "$state" != "STARTING" && "$state" != "RECONNECTING" ]]; then
    echo "capture_backend_failed"
    return 0
  fi

  if [[ "$bytes" == "0" ]]; then
    echo "stream_tcp_no_bytes"
    return 0
  fi

  if [[ -n "$last_error" ]]; then
    echo "daemon_crashed"
    return 0
  fi

  echo "unknown_stream_failure"
}

classify_stream_failure() {
  local reason_code reason_text
  reason_code="$(classify_stream_failure_code)"
  reason_text="$(reason_text_for_code "$reason_code")"
  printf '%s|%s\n' "$reason_code" "$reason_text"
}

reason_text_for_code() {
  case "$1" in
    portal_consent_required)
      echo "Wayland ScreenCast portal requires first user approval."
      ;;
    portal_unavailable)
      echo "XDG desktop portal or GNOME portal backend is unavailable in the graphical session."
      ;;
    graphical_session_missing)
      echo "The smoke command is not running inside a graphical Wayland/X11 session."
      ;;
    stream_port_not_open)
      echo "Client could not connect to stream port; stream process was not listening."
      ;;
    capture_backend_failed)
      echo "Capture backend did not reach a streaming state."
      ;;
    stream_tcp_no_bytes)
      echo "TCP stream connected but no stream bytes crossed the socket."
      ;;
    daemon_not_healthy)
      echo "Daemon health endpoint did not become reachable."
      ;;
    daemon_crashed)
      echo "Daemon exited or crashed during stream startup."
      ;;
    encoder_missing)
      echo "Required encoder is missing or unavailable."
      ;;
    *)
      echo "Unknown stream smoke failure."
      ;;
  esac
}

next_action_for_code() {
  case "$1" in
    portal_consent_required)
      echo "Run ./e2e/run prepare-portal-consent --distro fedora-43 --session gnome-wayland --backend wayland_portal --live --promote, approve the GNOME ScreenCast prompt once, then rerun the scenario."
      ;;
    portal_unavailable)
      echo "Inspect portal-probe.json, ensure xdg-desktop-portal and xdg-desktop-portal-gnome are active in the same user session."
      ;;
    graphical_session_missing)
      echo "Inspect session-probe.json and run through runas-remote or an active GNOME session."
      ;;
    stream_port_not_open)
      echo "Inspect status-after.json, metrics-after.json, daemon logs, and ports.txt."
      ;;
    *)
      echo "Inspect client.json, status-after.json, metrics-after.json, daemon logs and portal probes."
      ;;
  esac
}

fail() {
  local code="$1"
  local reason_code="$2"
  local reason="$3"
  local next_action="$4"
  local phase="$5"
  local blocked="${6:-false}"
  EXIT_CODE="$code"
  FAIL_PHASE="$phase"
  FAIL_REASON_CODE="$reason_code"
  FAIL_REASON="$reason"
  NEXT_ACTION="$next_action"
  FAIL_BLOCKED="$blocked"
  mkdir -p "$REPORT_DIR"
  echo "[e2e-guest-stream] FAIL phase=$FAIL_PHASE rc=$EXIT_CODE reason_code=$FAIL_REASON_CODE reason=$FAIL_REASON" >&2
  collect_after_failure || true
  write_summary false "$FAIL_PHASE" "$FAIL_REASON_CODE" "$FAIL_REASON" "$EXIT_CODE" "$FAIL_BLOCKED"
  exit "$EXIT_CODE"
}

main() {
  mkdir -p "$REPORT_DIR"
  need_cmd curl
  need_cmd jq
  need_cmd python3

  if [[ -z "$DISPLAY_MODE" ]]; then
    if [[ "$BACKEND" == "benchmark_game" ]]; then
      DISPLAY_MODE="benchmark_game"
    else
      DISPLAY_MODE="duplicate"
    fi
  fi

  cd "$ROOT_DIR"
  ensure_graphical_context
  log "root=$ROOT_DIR"
  log "report=$REPORT_DIR"
  log "backend=$BACKEND api_capture_backend=$(api_capture_backend) display_mode=$DISPLAY_MODE duration=${DURATION_SEC}s control=$CONTROL_PORT stream=$STREAM_PORT"

  collect_session_probes
  if [[ -z "${WAYLAND_DISPLAY:-}" && -z "${DISPLAY:-}" ]]; then
    fail 3 "graphical_session_missing" "graphical session env missing" "Run the helper through runas-remote or ensure the VM is logged into an active Wayland/X11 session." "session_not_ready"
  fi
  collect_ports

  start_daemon_if_needed

  curl -fsS "$(health_url)" | jq . >"$REPORT_DIR/health-before.json" || true
  curl -fsS "http://127.0.0.1:${CONTROL_PORT}/v1/host-probe" | jq . >"$REPORT_DIR/host-probe.json" || true
  curl -fsS "http://127.0.0.1:${CONTROL_PORT}/v1/virtual/probe" | jq . >"$REPORT_DIR/virtual-probe.json" || true
  curl -fsS "$(status_url)" | jq . >"$REPORT_DIR/status-before.json" || true
  curl -fsS "$(metrics_url)" | jq . >"$REPORT_DIR/metrics-before.json" || true

  apply_json="$(jq -nc \
    --arg encoder "$ENCODER" \
    --arg size "$SIZE" \
    --argjson fps "$FPS" \
    --argjson bitrate "$BITRATE_KBPS" \
    '{encoder:$encoder,size:$size,fps:$fps,bitrate_kbps:$bitrate}')"
  curl -fsS -X POST "$(apply_url)" \
    -H 'Content-Type: application/json' \
    -d "$apply_json" | jq . >"$REPORT_DIR/apply.json"

  curl -fsS -X POST "$(start_url)" \
    -H 'Content-Type: application/json' \
    -d '{}' | jq . >"$REPORT_DIR/start.json"
  curl -fsS "$(status_url)" | jq . >"$REPORT_DIR/status-after-start.json" || true
  curl -fsS "$(metrics_url)" | jq . >"$REPORT_DIR/metrics-after-start.json" || true
  collect_ports
  sleep "$WARMUP_SEC"
  curl -fsS "$(status_url)" | jq . >"$REPORT_DIR/status-after-start-3s.json" || true
  curl -fsS "$(metrics_url)" | jq . >"$REPORT_DIR/metrics-after-start-3s.json" || true
  collect_ports

  stream_client &
  client_pid="$!"

  deadline=$(( $(date +%s) + DURATION_SEC ))
  while [[ "$(date +%s)" -lt "$deadline" ]]; do
    curl -fsS --max-time 2 "$(metrics_url)" >>"$REPORT_DIR/metrics.jsonl" || true
    printf '\n' >>"$REPORT_DIR/metrics.jsonl"
    sleep 1
  done

  if ! wait "$client_pid"; then
    collect_after_failure || true
    classified="$(classify_stream_failure)"
    reason_code="${classified%%|*}"
    reason_text="${classified#*|}"
    next_action="$(next_action_for_code "$reason_code")"
    if [[ "$reason_code" == "portal_consent_required" ]]; then
      fail 20 "$reason_code" "$reason_text" "$next_action" "portal_consent" true
    fi
    fail 4 "$reason_code" "$reason_text" "$next_action" "stream_smoke"
  fi

  curl -fsS "$(status_url)" | jq . >"$REPORT_DIR/status-after.json" || true
  curl -fsS "$(metrics_url)" | jq . >"$REPORT_DIR/metrics-after.json" || true
  collect_ports
  collect_session_probes

  state="$(jq -r '.state // .base.state // empty' "$REPORT_DIR/status-after.json" 2>/dev/null || true)"
  if [[ "$state" != "STREAMING" && "$state" != "STARTING" && "$state" != "RECONNECTING" ]]; then
    fail 5 "capture_backend_failed" "unexpected final state: ${state:-unknown}" "Inspect status-after.json and daemon stderr." "capture_backend_failed"
  fi

  bytes="$(jq -r '.bytes_read // 0' "$REPORT_DIR/client.json" 2>/dev/null || echo 0)"
  if [[ "$bytes" -lt "$MIN_STREAM_BYTES" ]]; then
    fail 4 "stream_tcp_no_bytes" "stream bytes below threshold: $bytes < $MIN_STREAM_BYTES" "Inspect client.json, daemon logs, and metrics after start." "stream_smoke"
  fi

  write_summary true "stream_smoke" "" "" 0 false
  log "PASS"
  log "summary=$REPORT_DIR/summary.json"
  exit 0
}

main "$@"
