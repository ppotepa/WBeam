#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REPORT_ROOT = ROOT / "e2e" / "reports"
BLOCKING_REASON_CODES = {
    "missing_portal_consented_image",
    "portal_consent_required",
    "android_device_missing",
    "android_device_unauthorized",
    "evdi_module_missing",
    "evdi_kernel_headers_missing",
    "evdi_device_missing",
    "evdi_permission_denied",
    "x11_session_missing",
    "distro_image_missing",
    "iso_missing",
    "invalid_portal_consented_image",
}


def read_json(path: Path) -> dict:
    if not path.exists():
        return {}
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return {}
    return payload if isinstance(payload, dict) else {}


def _load_stream_summary(result: dict, run_dir: Path) -> tuple[dict, Path]:
    stream_ref = str(result.get("stream_summary") or "")
    candidates = []
    if stream_ref:
        ref_path = Path(stream_ref)
        candidates.append(ref_path)
        if not ref_path.is_absolute():
            candidates.append(run_dir / ref_path)
    candidates.append(run_dir / "scenarios" / str(result.get("scenario") or "") / "guest" / "wizard" / "stream" / "wayland_portal" / "summary.json")
    for candidate in candidates:
        if candidate.exists():
            return read_json(candidate), candidate
    return {}, candidates[0] if candidates else run_dir / "missing-summary.json"


def assert_green_run(
    *,
    report_root: Path,
    run_id: str,
    scenario: str,
    min_bytes: int = 1,
    require_portal_consented: bool = False,
) -> tuple[int, dict]:
    run_dir = report_root / run_id
    summary = read_json(run_dir / "summary.json")
    if not summary:
        payload = {
            "ok": False,
            "run_id": run_id,
            "scenario": scenario,
            "status": "unknown",
            "reason_code": "summary_missing",
            "stream_ok": False,
            "client_connected": False,
            "bytes_read": 0,
            "l1_backing_kind": "unknown",
            "next_action": f"Inspect {run_dir / 'summary.json'}",
            "artifacts": {"summary": str(run_dir / "summary.json")},
        }
        return 1, payload

    result = next((item for item in summary.get("results", []) if isinstance(item, dict) and item.get("scenario") == scenario), None)
    if not result:
        payload = {
            "ok": False,
            "run_id": run_id,
            "scenario": scenario,
            "status": str(summary.get("status", "unknown")),
            "reason_code": "scenario_missing",
            "stream_ok": False,
            "client_connected": False,
            "bytes_read": 0,
            "l1_backing_kind": "unknown",
            "next_action": f"Inspect {run_dir / 'summary.json'} and ensure scenario {scenario} exists.",
            "artifacts": {"summary": str(run_dir / "summary.json")},
        }
        return 1, payload

    stream_summary, stream_path = _load_stream_summary(result, run_dir)
    client = stream_summary.get("client") if isinstance(stream_summary.get("client"), dict) else {}
    client_connected = bool(client.get("connected"))
    bytes_read = int(client.get("bytes_read") or 0)
    status = str(result.get("status", "unknown"))
    reason_code = str(result.get("reason_code") or stream_summary.get("reason_code") or "")
    if status != "pass":
        payload = {
            "ok": False,
            "run_id": run_id,
            "scenario": scenario,
            "status": status,
            "reason_code": reason_code or ("blocked" if status == "blocked" else "run_not_passed"),
            "stream_ok": bool(stream_summary.get("ok")),
            "client_connected": client_connected,
            "bytes_read": bytes_read,
            "l1_backing_kind": str(result.get("l1_backing_kind") or "unknown"),
            "next_action": str(result.get("next_action") or "Inspect the run summary."),
            "artifacts": {
                "summary": str(run_dir / "summary.json"),
                "stream_summary": str(stream_path),
                "wizard_summary": str(result.get("wizard_summary") or ""),
                "wizard_steps": str(result.get("wizard_steps") or ""),
            },
        }
        return 1, payload
    payload = {
        "ok": True,
        "run_id": run_id,
        "scenario": scenario,
        "status": status,
        "reason_code": "green",
        "stream_ok": bool(stream_summary.get("ok")),
        "client_connected": client_connected,
        "bytes_read": bytes_read,
        "l1_backing_kind": str(result.get("l1_backing_kind") or "unknown"),
        "next_action": str(result.get("next_action") or ""),
        "artifacts": {
            "summary": str(run_dir / "summary.json"),
            "stream_summary": str(stream_path),
            "wizard_summary": str(result.get("wizard_summary") or ""),
            "wizard_steps": str(result.get("wizard_steps") or ""),
        },
    }
    if reason_code in BLOCKING_REASON_CODES:
        payload["ok"] = False
        payload["reason_code"] = reason_code or "blocked"
        return 1, payload
    if not stream_summary:
        payload["ok"] = False
        payload["reason_code"] = "stream_summary_missing"
        payload["next_action"] = f"Inspect {stream_path}"
        return 1, payload
    if not stream_summary.get("ok"):
        payload["ok"] = False
        payload["reason_code"] = str(stream_summary.get("reason_code") or "stream_failure")
        payload["next_action"] = str(stream_summary.get("next_action") or result.get("next_action") or "")
        return 1, payload
    if not client_connected:
        payload["ok"] = False
        payload["reason_code"] = "client_not_connected"
        payload["next_action"] = str(stream_summary.get("next_action") or "Inspect stream client and daemon logs.")
        return 1, payload
    if bytes_read < min_bytes:
        payload["ok"] = False
        payload["reason_code"] = "stream_no_bytes"
        payload["next_action"] = str(stream_summary.get("next_action") or "Inspect stream client and daemon logs.")
        return 1, payload
    if require_portal_consented:
        l1_kind = str(result.get("l1_backing_kind") or "")
        l1_image = str(result.get("l1_backing_image") or "")
        if l1_kind not in {"portal_consented", "portal-consented"} or not l1_image.endswith("gnome-wayland-portal-consented.qcow2"):
            payload["ok"] = False
            payload["reason_code"] = "portal_backing_not_consented"
            payload["next_action"] = "Recreate portal-consented L1P and rerun."
            return 1, payload
    return 0, payload


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="assert-green-run")
    parser.add_argument("--report-root", default=str(REPORT_ROOT))
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--scenario", required=True)
    parser.add_argument("--min-bytes", type=int, default=1)
    parser.add_argument("--require-portal-consented", action="store_true")
    parser.add_argument("--json", action="store_true")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    rc, payload = assert_green_run(
        report_root=Path(args.report_root).expanduser().resolve(),
        run_id=args.run_id,
        scenario=args.scenario,
        min_bytes=args.min_bytes,
        require_portal_consented=args.require_portal_consented,
    )
    print(json.dumps(payload, indent=2, sort_keys=True))
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
