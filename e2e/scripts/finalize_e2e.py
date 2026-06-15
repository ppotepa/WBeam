#!/usr/bin/env python3
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS_DIR = ROOT / "e2e" / "scripts"
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import runner  # noqa: E402


def utc_timestamp() -> str:
    return dt.datetime.now(dt.UTC).strftime("%Y%m%dT%H%M%SZ")


def read_json(path: Path) -> dict:
    if not path.exists():
        return {}
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return {}
    return payload if isinstance(payload, dict) else {}


def result_status_rank(result: dict) -> int:
    status = str(result.get("status") or "").lower()
    if status == "pass":
        return 3
    if status == "blocked":
        return 2
    if status == "fail":
        return 1
    return 0


def latest_result_for_scenario(report_root: Path, scenario_id: str) -> tuple[dict, Path]:
    candidates: list[tuple[int, float, str, dict, Path]] = []
    runs = [p for p in report_root.iterdir() if p.is_dir()] if report_root.exists() else []
    for run_dir in runs:
        summary_path = run_dir / "summary.json"
        summary = read_json(summary_path)
        try:
            mtime = summary_path.stat().st_mtime
        except OSError:
            mtime = 0.0
        for result in summary.get("results", []) or []:
            if isinstance(result, dict) and result.get("scenario") == scenario_id:
                candidates.append((result_status_rank(result), mtime, run_dir.name, result, run_dir))
    if not candidates:
        return {}, Path()
    _rank, _mtime, _name, result, run_dir = max(candidates, key=lambda item: (item[0], item[1], item[2]))
    return result, run_dir


def scenario_command(scenario_id: str) -> str:
    return f"./e2e/run run --scenario {scenario_id} --use-installed --live"


def pick_scenario(matrix: dict, *, distro: str | None = None, session: str | None = None, backend: str | None = None, requires_portal: bool | None = None, requires_evdi: bool | None = None) -> dict:
    for scenario in matrix.get("scenarios", []):
        if distro and scenario.get("distro") != distro:
            continue
        if session and scenario.get("session") != session:
            continue
        if backend and scenario.get("backend") != backend:
            continue
        if requires_portal is not None and bool(scenario.get("requires_portal")) != requires_portal:
            continue
        if requires_evdi is not None and bool(scenario.get("requires_evdi")) != requires_evdi:
            continue
        return scenario
    return {}


def adb_state() -> str:
    try:
        proc = subprocess.run(["adb", "devices", "-l"], capture_output=True, text=True, check=False, timeout=8)
    except (FileNotFoundError, OSError, subprocess.SubprocessError):
        return "missing"
    lines = []
    for line in "\n".join([proc.stdout, proc.stderr]).splitlines():
        line = line.strip()
        if not line or line.lower().startswith("list of devices attached") or line.startswith("* daemon "):
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1] in {"device", "unauthorized", "offline", "recovery", "sideload"}:
            lines.append(line)
        elif len(parts) >= 3 and parts[1] == "no" and parts[2] == "permissions":
            lines.append(f"{parts[0]} no_permissions")
    if any(line.split()[1:2] == ["device"] for line in lines if len(line.split()) >= 2):
        return "ready"
    if any("unauthorized" in line for line in lines):
        return "unauthorized"
    return "missing"


def result_from_latest_or_blocked(report_root: Path, scenario: dict, *, fallback_reason_code: str, fallback_reason: str, next_action: str) -> dict:
    scenario_id = scenario.get("id", "")
    result, run_dir = latest_result_for_scenario(report_root, scenario_id)
    if result:
        return {
            "name": scenario_id,
            "scenario": scenario_id,
            "status": result.get("status", "unknown"),
            "reason_code": result.get("reason_code", ""),
            "next_action": result.get("next_action", ""),
            "run_id": result.get("run_id") or run_dir.name,
            "report": str(run_dir / "report.md") if run_dir else "",
        }
    return {
        "name": scenario_id,
        "scenario": scenario_id,
        "status": "blocked",
        "reason_code": fallback_reason_code,
        "next_action": next_action,
        "run_id": "",
        "report": "",
        "reason": fallback_reason,
    }


def build_profile_results(profile: str, matrix: dict, report_root: Path) -> tuple[list[dict], list[str], list[str]]:
    required_pass: list[str] = []
    allowed_blocked: list[str] = [
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
    ]
    results: list[dict] = []

    portal = pick_scenario(matrix, distro="fedora-43", session="gnome-wayland", backend="wayland_portal", requires_portal=True)
    if portal:
        required_pass.append(portal["id"])
        portal_consented = runner.portal_consented_image_is_valid("fedora-43", "gnome-wayland", runner.base_dir())
        if portal_consented[0]:
            results.append(result_from_latest_or_blocked(report_root, portal, fallback_reason_code="missing_run", fallback_reason="Portal run not executed yet.", next_action=scenario_command(portal["id"])))
        else:
            results.append(
                {
                    "name": portal["id"],
                    "scenario": portal["id"],
                    "status": "blocked",
                    "reason_code": "missing_portal_consented_image",
                    "next_action": "./e2e/run prepare-portal-consent --distro fedora-43 --session gnome-wayland --backend wayland_portal --live --promote",
                    "run_id": "",
                    "report": "",
                }
            )

    evdi = pick_scenario(matrix, distro="fedora-43", session="gnome-wayland", requires_evdi=True)
    if evdi:
        results.append(result_from_latest_or_blocked(report_root, evdi, fallback_reason_code="evdi_module_missing", fallback_reason="EVDI scenario not executed yet.", next_action=scenario_command(evdi["id"])))

    x11 = pick_scenario(matrix, distro="fedora-43", session="gnome-xorg", backend="x11_gst")
    if x11:
        results.append(result_from_latest_or_blocked(report_root, x11, fallback_reason_code="x11_session_missing", fallback_reason="X11 scenario not executed yet.", next_action=scenario_command(x11["id"])))

    android = pick_scenario(matrix, distro="fedora-43", session="gnome-wayland", backend="wayland_portal", requires_portal=True)
    android = next((s for s in matrix.get("scenarios", []) if s.get("device_policy") == "required" or s.get("android_execution") == "host"), android)
    if android and android.get("device_policy") == "required":
        state = adb_state()
        if state == "ready":
            results.append(result_from_latest_or_blocked(report_root, android, fallback_reason_code="android_stream_no_bytes", fallback_reason="Android scenario not executed yet.", next_action=scenario_command(android["id"])))
        elif state == "unauthorized":
            results.append({"name": android["id"], "scenario": android["id"], "status": "blocked", "reason_code": "android_device_unauthorized", "next_action": "Connect/unlock Android device and authorize ADB.", "run_id": "", "report": ""})
        else:
            results.append({"name": android["id"], "scenario": android["id"], "status": "blocked", "reason_code": "android_device_missing", "next_action": "Connect/unlock Android device and authorize ADB.", "run_id": "", "report": ""})

    if profile in {"hardware", "full"}:
        # no-op, profile handled via presence of android scenario result above
        pass

    if profile == "full":
        ubuntu = pick_scenario(matrix, distro="ubuntu-24.04", session="headless", backend="benchmark_game")
        debian = pick_scenario(matrix, distro="debian-12", session="headless", backend="benchmark_game")
        if ubuntu:
            results.append(result_from_latest_or_blocked(report_root, ubuntu, fallback_reason_code="iso_missing", fallback_reason="Ubuntu headless scenario not executed yet.", next_action=scenario_command(ubuntu["id"])))
        if debian:
            results.append(result_from_latest_or_blocked(report_root, debian, fallback_reason_code="iso_missing", fallback_reason="Debian headless scenario not executed yet.", next_action=scenario_command(debian["id"])))

    if not results:
        results.append({"name": "none", "scenario": "", "status": "blocked", "reason_code": "no_profile_targets", "next_action": "Populate matrix or select a supported profile.", "run_id": "", "report": ""})

    return results, required_pass, allowed_blocked


def summarize(results: list[dict], required_pass: list[str], allowed_blocked: list[str]) -> dict:
    counts = {"pass": 0, "blocked": 0, "fail": 0, "unknown": 0}
    for result in results:
        status = str(result.get("status") or "unknown")
        if status not in counts:
            status = "unknown"
        counts[status] += 1
    disallowed_blocked = [
        result
        for result in results
        if result.get("status") == "blocked" and str(result.get("reason_code") or "") not in set(allowed_blocked)
    ]
    status = "pass"
    if counts["fail"] or counts["unknown"] or disallowed_blocked:
        status = "fail"
    elif counts["blocked"]:
        status = "blocked"
    next_action = ""
    if status != "pass":
        first = next((result for result in results if result.get("status") != "pass"), {})
        next_action = str(first.get("next_action") or "")
    return {
        "schema": 2,
        "status": status,
        "results": results,
        "counts": counts,
        "required_pass": required_pass,
        "allowed_blocked": allowed_blocked,
        "next_action": next_action,
    }


def write_report(output_dir: Path, payload: dict) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "final-summary.json").write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    lines = [
        "# WBeam E2E Final Closure",
        "",
        f"- Profile: `{payload['profile']}`",
        f"- Status: `{payload['status']}`",
        f"- Summary: `{output_dir / 'final-summary.json'}`",
        f"- Required pass: {', '.join(payload.get('required_pass', []) or ['-'])}",
        "",
        "## Required Green Path",
        "",
    ]
    for required in payload.get("required_pass", []) or []:
        lines.append(f"- `{required}`")
    lines.extend(
        [
            "",
            "## Portal Consented Asset",
            "",
            f"- `portal_consented_image`: `{payload.get('portal_consented_image', '')}`",
            f"- `green_run_id`: `{payload.get('green_run_id', '')}`",
            "",
            "## Runner Report",
            "",
        ]
    )
    for result in payload["results"]:
        lines.append(f"- `{result['name']}`: `{result.get('status', 'unknown')}`")
        if result.get("reason_code"):
            lines.append(f"  - Reason code: `{result['reason_code']}`")
        if result.get("next_action"):
            lines.append(f"  - Next action: {result['next_action']}")
        if result.get("run_id"):
            lines.append(f"  - Run ID: `{result['run_id']}`")
        if result.get("report"):
            lines.append(f"  - Report: `{result['report']}`")
    lines.extend(["", "## Assertion Result", "", f"- Counts: `{payload.get('counts', {})}`", "", "## Remaining Non-MVP Items", ""])
    remaining = [result for result in payload["results"] if result.get("status") != "pass"]
    for result in remaining:
        lines.append(f"- `{result['name']}`: `{result.get('status', 'unknown')}` ({result.get('reason_code', '')})")
    if not remaining:
        lines.append("- none")
    if payload.get("next_action"):
        lines.extend(["", "## Commands Used", "", payload["next_action"]])
    (output_dir / "final-report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    (output_dir / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="finalize-e2e")
    parser.add_argument("--profile", choices=("fedora-mvp", "hardware", "full"), default="fedora-mvp")
    parser.add_argument("--live", action="store_true")
    parser.add_argument("--run-prefix", default="FINAL-E2E-CLOSURE")
    parser.add_argument("--json", action="store_true")
    parser.add_argument("--report-root", default=str(ROOT / "e2e" / "reports"))
    return parser


def main() -> int:
    args = build_parser().parse_args()
    report_root = Path(args.report_root).expanduser().resolve()
    matrix = runner.load_matrix()
    results, required_pass, allowed_blocked = build_profile_results(args.profile, matrix, report_root)
    payload = summarize(results, required_pass, allowed_blocked)
    run_dir = report_root / f"{args.run_prefix}-{utc_timestamp()}"
    payload["profile"] = args.profile
    payload["report_dir"] = str(run_dir)
    green = next((result for result in results if result.get("status") == "pass"), {})
    payload["green_run_id"] = str(green.get("run_id") or "")
    payload["portal_consented_image"] = str(runner.portal_consented_image_path("fedora-43", "gnome-wayland", runner.base_dir()))
    write_report(run_dir, payload)
    print(json.dumps(payload, indent=2, sort_keys=True))
    if payload["status"] == "pass":
        return 0
    if payload["status"] == "blocked":
        blocked_codes = {str(result.get("reason_code") or "") for result in results if result.get("status") == "blocked"}
        if blocked_codes and blocked_codes.issubset(set(allowed_blocked)) and all(
            result.get("status") == "pass" for result in results if result.get("name") in required_pass
        ):
            return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
