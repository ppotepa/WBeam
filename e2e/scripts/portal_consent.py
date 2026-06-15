#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MATRIX_PATH = ROOT / "e2e" / "matrix.json"


def read_json(path: Path) -> dict:
    if not path.exists():
        return {}
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return {}
    return payload if isinstance(payload, dict) else {}


def read_text(path: Path) -> str:
    if not path.exists():
        return ""
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def load_matrix() -> dict:
    return read_json(MATRIX_PATH)


def find_scenario(scenario_id: str) -> dict:
    matrix = load_matrix()
    for scenario in matrix.get("scenarios", []):
        if isinstance(scenario, dict) and scenario.get("id") == scenario_id:
            return scenario
    return {}


def stream_dir_for_report(report_dir: Path, scenario_id: str) -> Path:
    return report_dir / "scenarios" / scenario_id / "guest" / "wizard" / "stream" / "wayland_portal"


def _json_bool(value: object) -> bool:
    return bool(value) if isinstance(value, bool) else False


def _read_nested_number(payload: dict, *path: str) -> int:
    current: object = payload
    for key in path:
        if not isinstance(current, dict):
            return 0
        current = current.get(key)
    if isinstance(current, (int, float)):
        return int(current)
    return 0


def _read_nested_string(payload: dict, *path: str) -> str:
    current: object = payload
    for key in path:
        if not isinstance(current, dict):
            return ""
        current = current.get(key)
    return str(current or "")


def _state_value(payload: dict) -> str:
    if not isinstance(payload, dict):
        return ""
    if payload.get("state"):
        return str(payload.get("state"))
    base = payload.get("base")
    if isinstance(base, dict) and base.get("state"):
        return str(base.get("state"))
    return ""


def _last_error_value(payload: dict) -> str:
    if not isinstance(payload, dict):
        return ""
    if payload.get("last_error"):
        return str(payload.get("last_error"))
    base = payload.get("base")
    if isinstance(base, dict) and base.get("last_error"):
        return str(base.get("last_error"))
    return ""


def _prepare_portal_consent_next_action(scenario: dict | None) -> str:
    distro = (scenario or {}).get("distro", "fedora-43")
    session = (scenario or {}).get("session", "gnome-wayland")
    return f"./e2e/run prepare-portal-consent --distro {distro} --session {session} --backend wayland_portal --live --promote"


def _portal_approved_next_action() -> str:
    return "Portal consent is approved; rerun the scenario."


def classify_guest_portal_report(report_dir: Path, scenario: dict | None = None) -> dict:
    summary_path = report_dir / "summary.json"
    summary = read_json(summary_path)
    if not summary and not summary_path.exists():
        return {
            "schema": 1,
            "status": "fail",
            "reason_code": "guest_report_missing",
            "reason": f"Missing guest report summary: {summary_path}",
            "confidence": "low",
            "evidence": {
                "backend": str((scenario or {}).get("backend", "wayland_portal")),
                "display_mode": str((scenario or {}).get("display_mode", "virtual_monitor")),
            },
            "next_action": "Inspect the guest report directory and rerun prepare-portal-consent.",
            "summary": {},
        }
    client = read_json(report_dir / "client.json")
    status_before = read_json(report_dir / "status-before.json")
    status_after_start = read_json(report_dir / "status-after-start.json")
    status_after = read_json(report_dir / "status-after.json")
    metrics_after = read_json(report_dir / "metrics-after.json")
    portal_probe = read_json(report_dir / "portal-probe.json")
    pipewire_probe = read_json(report_dir / "pipewire-probe.json")
    session_probe = read_json(report_dir / "session-probe.json")
    virtual_probe = read_json(report_dir / "virtual-probe.json")
    ports_text = read_text(report_dir / "ports.txt")

    backend = str(summary.get("backend") or (scenario or {}).get("backend") or "wayland_portal")
    display_mode = str(summary.get("display_mode") or (scenario or {}).get("display_mode") or "virtual_monitor")
    portal_active = _read_nested_string(portal_probe, "xdg_desktop_portal_user") == "active"
    portal_gnome_active = _read_nested_string(portal_probe, "xdg_desktop_portal_gnome_user") == "active"
    pipewire_active = _read_nested_string(pipewire_probe, "pipewire_user") == "active"
    wireplumber_active = _read_nested_string(pipewire_probe, "wireplumber_user") == "active"
    virtual_supported = _json_bool(virtual_probe.get("virtual_supported"))
    state_before = _state_value(status_before)
    state_after_start = _state_value(status_after_start)
    state_after = _state_value(status_after)
    last_error = _last_error_value(status_after) or str(summary.get("last_error") or "")
    client_connected = _json_bool(client.get("connected"))
    bytes_read = _read_nested_number(client, "bytes_read")
    attempts = _read_nested_number(client, "attempts")
    connection_refused = _read_nested_number(client, "connection_refused")
    frame_in = _read_nested_number(metrics_after, "metrics", "frame_in") or _read_nested_number(metrics_after, "metrics", "metrics", "frame_in")
    frame_out = _read_nested_number(metrics_after, "metrics", "frame_out") or _read_nested_number(metrics_after, "metrics", "metrics", "frame_out")
    stream_port_listening = bool(re.search(r":5000\b.*LISTEN", ports_text, re.IGNORECASE | re.DOTALL))
    graphical_session_present = bool(_read_nested_string(session_probe, "wayland_display") or _read_nested_string(session_probe, "display"))
    portal_services_active = portal_active and portal_gnome_active and pipewire_active and wireplumber_active and virtual_supported

    evidence = {
        "backend": backend,
        "display_mode": display_mode,
        "portal_active": portal_active,
        "pipewire_active": pipewire_active,
        "virtual_supported": virtual_supported,
        "state_before": state_before,
        "state_after_start": state_after_start,
        "state_after": state_after,
        "last_error": last_error,
        "client_connected": client_connected,
        "bytes_read": bytes_read,
        "stream_port_listening": stream_port_listening,
        "frame_in": frame_in,
        "frame_out": frame_out,
        "attempts": attempts,
        "connection_refused": connection_refused,
        "portal_gnome_active": portal_gnome_active,
        "wireplumber_active": wireplumber_active,
        "session_type": session_probe.get("xdg_session_type", ""),
    }

    if summary.get("blocked") is True and summary.get("reason_code") == "portal_consent_required":
        return {
            "schema": 1,
            "status": "blocked",
            "reason_code": "portal_consent_required",
            "reason": "Wayland portal consent required.",
            "confidence": "high",
            "evidence": evidence,
            "next_action": str(summary.get("next_action") or _prepare_portal_consent_next_action(scenario)),
            "summary": summary,
        }

    if client_connected and bytes_read > 0:
        return {
            "schema": 1,
            "status": "pass",
            "reason_code": "portal_consent_approved",
            "reason": "Stream received bytes after portal approval.",
            "confidence": "high",
            "evidence": evidence,
            "next_action": _portal_approved_next_action(),
            "summary": summary,
        }

    if backend in {"wayland", "wayland_portal"} and portal_services_active and "timeout waiting for streaming signal" in last_error:
        return {
            "schema": 1,
            "status": "blocked",
            "reason_code": "portal_consent_required",
            "reason": "Wayland ScreenCast portal requires first user approval.",
            "confidence": "high",
            "evidence": evidence,
            "next_action": _prepare_portal_consent_next_action(scenario),
            "summary": summary,
        }

    if connection_refused > 0 and state_after == "IDLE" and backend in {"wayland", "wayland_portal"} and portal_services_active:
        return {
            "schema": 1,
            "status": "blocked",
            "reason_code": "portal_consent_required",
            "reason": "Wayland ScreenCast portal requires first user approval.",
            "confidence": "medium",
            "evidence": evidence,
            "next_action": _prepare_portal_consent_next_action(scenario),
            "summary": summary,
        }

    if backend in {"wayland", "wayland_portal"} and portal_services_active:
        if display_mode == "virtual_monitor" and not client_connected and bytes_read == 0:
            if "timeout waiting for streaming signal" in last_error or not stream_port_listening:
                return {
                    "schema": 1,
                    "status": "blocked",
                    "reason_code": "portal_consent_required",
                    "reason": "Wayland ScreenCast portal requires first user approval.",
                    "confidence": "medium",
                    "evidence": evidence,
                    "next_action": _prepare_portal_consent_next_action(scenario),
                    "summary": summary,
                }

    if backend in {"wayland", "wayland_portal"} and not portal_active:
        return {
            "schema": 1,
            "status": "fail",
            "reason_code": "portal_unavailable",
            "reason": "XDG portal backend is unavailable.",
            "confidence": "medium",
            "evidence": evidence,
            "next_action": "Ensure xdg-desktop-portal and xdg-desktop-portal-gnome are active in the same user session.",
            "summary": summary,
        }

    if not stream_port_listening and not client_connected:
        return {
            "schema": 1,
            "status": "fail",
            "reason_code": "stream_port_not_open",
            "reason": "Client could not connect to stream port; stream process was not listening.",
            "confidence": "medium",
            "evidence": evidence,
            "next_action": "Inspect ports.txt, daemon logs, status-after-start.json, and metrics-after.json.",
            "summary": summary,
        }

    if client_connected and bytes_read == 0:
        return {
            "schema": 1,
            "status": "fail",
            "reason_code": "stream_started_no_bytes",
            "reason": "TCP stream connected but no stream bytes crossed the socket.",
            "confidence": "medium",
            "evidence": evidence,
            "next_action": "Inspect client.json, daemon logs, and portal probes.",
            "summary": summary,
        }

    if not graphical_session_present:
        return {
            "schema": 1,
            "status": "fail",
            "reason_code": "graphical_session_missing",
            "reason": "The smoke command is not running inside a graphical Wayland/X11 session.",
            "confidence": "medium",
            "evidence": evidence,
            "next_action": "Run the helper inside an active graphical Wayland/X11 session.",
            "summary": summary,
        }

    if last_error:
        return {
            "schema": 1,
            "status": "fail",
            "reason_code": "stream_transport_failed",
            "reason": f"Daemon last_error={last_error}",
            "confidence": "low",
            "evidence": evidence,
            "next_action": "Inspect daemon logs, status-after.json, and metrics-after.json.",
            "summary": summary,
        }

    return {
        "schema": 1,
        "status": "fail",
        "reason_code": "unknown_stream_failure",
        "reason": "Unknown stream smoke failure.",
        "confidence": "low",
        "evidence": evidence,
        "next_action": "Inspect client.json, status-after.json, metrics-after.json, daemon logs and portal probes.",
        "summary": summary,
    }


def classify_stream_dir(stream_dir: Path, scenario: dict | None = None) -> dict:
    return classify_guest_portal_report(stream_dir, scenario)


def cmd_classify(args: argparse.Namespace) -> int:
    stream_dir = Path(args.stream_dir).expanduser().resolve()
    result = classify_guest_portal_report(stream_dir)
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


def cmd_summarize(args: argparse.Namespace) -> int:
    stream_dir = Path(args.stream_dir).expanduser().resolve()
    result = classify_guest_portal_report(stream_dir)
    payload = {
        "schema": 1,
        "stream_dir": str(stream_dir),
        "status": result["status"],
        "reason_code": result["reason_code"],
        "confidence": result["confidence"],
        "next_action": result["next_action"],
        "evidence": result["evidence"],
    }
    print(json.dumps(payload, indent=2, sort_keys=True))
    return 0


def cmd_diagnose(args: argparse.Namespace) -> int:
    report_dir = Path(args.report_dir).expanduser().resolve()
    scenario = find_scenario(args.scenario)
    stream_dir = stream_dir_for_report(report_dir, args.scenario)
    result = classify_guest_portal_report(stream_dir, scenario)
    payload = {
        "schema": 1,
        "report_dir": str(report_dir),
        "scenario": args.scenario,
        "stream_dir": str(stream_dir),
        **result,
    }
    print(json.dumps(payload, indent=2, sort_keys=True))
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="portal-consent")
    sub = parser.add_subparsers(dest="command", required=True)

    classify_p = sub.add_parser("classify")
    classify_p.add_argument("--stream-dir", required=True)
    classify_p.set_defaults(func=cmd_classify)

    summarize_p = sub.add_parser("summarize")
    summarize_p.add_argument("--stream-dir", required=True)
    summarize_p.set_defaults(func=cmd_summarize)

    diagnose_p = sub.add_parser("diagnose")
    diagnose_p.add_argument("--report-dir", required=True)
    diagnose_p.add_argument("--scenario", required=True)
    diagnose_p.set_defaults(func=cmd_diagnose)
    return parser


def main() -> int:
    args = build_parser().parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
