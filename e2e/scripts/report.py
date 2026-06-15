#!/usr/bin/env python3
from __future__ import annotations

import html
import json
from pathlib import Path


def write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")


def read_json(path: Path) -> dict:
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return {}


def scenario_report_dir(report_root: Path, run_id: str, scenario_id: str) -> Path:
    return report_root / run_id / "scenarios" / scenario_id


def aggregate_run_status(status_counts: dict[str, int], failures: list[dict]) -> str:
    if not failures:
        return "pass"
    if status_counts.get("fail", 0) > 0:
        return "fail"
    if status_counts.get("blocked", 0) > 0:
        return "blocked"
    if status_counts.get("reboot_required", 0) > 0:
        return "reboot_required"
    return "fail"


def init_run_report(
    report_root: Path,
    run_id: str,
    scenarios: list[dict],
    *,
    host: dict | None = None,
) -> Path:
    run_dir = report_root / run_id
    (run_dir / "scenarios").mkdir(parents=True, exist_ok=True)
    write_json(run_dir / "matrix.json", {"scenarios": scenarios})
    write_json(run_dir / "host.json", host or {})
    write_json(
        run_dir / "summary.json",
        {
            "run_id": run_id,
            "status": "running",
            "scenarios_total": len(scenarios),
            "scenarios_passed": 0,
            "scenarios_failed": 0,
            "failures": [],
        },
    )
    return run_dir


def write_junit(run_dir: Path, scenario_results: list[dict]) -> None:
    failures = [result for result in scenario_results if result.get("status") != "pass"]
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        f'<testsuite name="wbeam-e2e" tests="{len(scenario_results)}" failures="{len(failures)}">',
    ]
    for result in scenario_results:
        name = html.escape(result["scenario"])
        duration = html.escape(str(result.get("duration_sec", 0)))
        lines.append(f'  <testcase classname="wbeam.e2e" name="{name}" time="{duration}">')
        if result.get("status") != "pass":
            reason = html.escape(result.get("reason", "unknown failure"))
            phase = html.escape(result.get("phase", "unknown"))
            lines.append(f'    <failure message="{phase}: {reason}">{reason}</failure>')
        output = html.escape(result.get("report_dir", ""))
        lines.append(f"    <system-out>{output}</system-out>")
        lines.append("  </testcase>")
    lines.append("</testsuite>")
    (run_dir / "junit.xml").write_text("\n".join(lines) + "\n", encoding="utf-8")


def finalize_run_report(run_dir: Path, run_id: str, scenario_results: list[dict]) -> None:
    status_counts = {"pass": 0, "fail": 0, "blocked": 0, "reboot_required": 0}
    failed_count = 0
    failures = [
        {
            "scenario": result["scenario"],
            "status": result.get("status", "fail"),
            "phase": result.get("phase", "unknown"),
            "reason": result.get("reason", "unknown failure"),
            "reason_code": result.get("reason_code", ""),
            "next_action": result.get("next_action", ""),
            "stream_reason_code": result.get("stream_reason_code", ""),
            "stream_blocked": result.get("stream_blocked", False),
            "l1_backing_kind": result.get("l1_backing_kind", ""),
            "portal_consented_image": result.get("portal_consented_image", ""),
            "allow_unconsented_portal": result.get("allow_unconsented_portal", False),
            "guest_exit_code": result.get("guest_exit_code", None),
        }
        for result in scenario_results
        if result.get("status") != "pass"
    ]
    for result in scenario_results:
        status = str(result.get("status", "fail"))
        if status in status_counts:
            status_counts[status] += 1
        elif status != "pass":
            status_counts["fail"] += 1
        if status == "fail":
            failed_count += 1
    aggregate_status = aggregate_run_status(status_counts, failures)
    write_json(
        run_dir / "summary.json",
        {
            "run_id": run_id,
            "status": aggregate_status,
            "scenarios_total": len(scenario_results),
            "scenarios_passed": status_counts["pass"],
            "scenarios_failed": failed_count,
            "scenarios_blocked": status_counts["blocked"],
            "scenarios_reboot_required": status_counts["reboot_required"],
            "status_counts": status_counts,
            "failures": failures,
            "results": scenario_results,
        },
    )
    write_junit(run_dir, scenario_results)
    status_lines = [f"- {name.replace('_', ' ').title()}: {count}" for name, count in status_counts.items() if count]
    report_lines = [
        "# WBeam E2E Run Report",
        "",
        f"- Run ID: `{run_id}`",
        f"- Status: `{aggregate_status}`",
        f"- Summary: `{run_dir / 'summary.json'}`",
        f"- JUnit: `{run_dir / 'junit.xml'}`",
    ]
    if status_lines:
        report_lines.extend(["", "## Status Counts", ""])
        report_lines.extend(status_lines)
    report_lines.extend(["", "## Scenarios", ""])
    for result in scenario_results:
        report_lines.append(f"- `{result['scenario']}`: `{result.get('status', 'unknown')}`")
        if result.get("run_id"):
            report_lines.append(f"  - Run ID: `{result['run_id']}`")
        if result.get("wizard_summary"):
            report_lines.append(f"  - Wizard summary: `{result['wizard_summary']}`")
        if result.get("wizard_steps"):
            report_lines.append(f"  - Wizard steps: `{result['wizard_steps']}`")
        if result.get("stream_summary"):
            report_lines.append(f"  - Stream summary: `{result['stream_summary']}`")
        if result.get("android_summary"):
            report_lines.append(f"  - Android summary: `{result['android_summary']}`")
        if result.get("phone_info"):
            report_lines.append(f"  - Phone info: `{result['phone_info']}`")
        if result.get("phone_logcat"):
            report_lines.append(f"  - Phone logcat: `{result['phone_logcat']}`")
        if result.get("guest_command"):
            report_lines.append(f"  - Guest command: `{result['guest_command']}`")
        if result.get("l1_backing_image"):
            report_lines.append(f"  - L1 backing image: `{result['l1_backing_image']}`")
        if result.get("l1_backing_kind"):
            report_lines.append(f"  - L1 backing kind: `{result['l1_backing_kind']}`")
        if result.get("portal_consented_image"):
            report_lines.append(f"  - Portal consent image: `{result['portal_consented_image']}`")
        if result.get("l2_workdisk"):
            report_lines.append(f"  - L2 workdisk: `{result['l2_workdisk']}`")
        if result.get("runner_report_dir"):
            report_lines.append(f"  - Runner report dir: `{result['runner_report_dir']}`")
        if result.get("reason_code"):
            report_lines.append(f"  - Reason code: `{result['reason_code']}`")
        if result.get("phase"):
            report_lines.append(f"  - Phase: `{result['phase']}`")
        if result.get("stream_reason_code"):
            report_lines.append(f"  - Stream reason code: `{result['stream_reason_code']}`")
        if result.get("stream_blocked") is not None:
            report_lines.append(f"  - Stream blocked: `{result['stream_blocked']}`")
        if result.get("allow_unconsented_portal") is not None:
            report_lines.append(f"  - Allow unconsented portal: `{result['allow_unconsented_portal']}`")
        if result.get("guest_exit_code") is not None:
            report_lines.append(f"  - Guest exit code: `{result['guest_exit_code']}`")
        if result.get("bytes_received") is not None:
            report_lines.append(f"  - Android bytes received: `{result['bytes_received']}`")
        if result.get("reason"):
            report_lines.append(f"  - Reason: {result['reason']}")
        if result.get("next_action"):
            report_lines.append(f"  - Next action: {result['next_action']}")
        if result.get("phase") == "portal_consent":
            report_lines.append(f"  - Portal consent command: `{result.get('next_action', '')}`")
        if result.get("workdisk_policy"):
            report_lines.append(f"  - Workdisk policy: `{result['workdisk_policy']}`")
        if result.get("workdisk_retained") is not None:
            report_lines.append(f"  - Workdisk retained: `{result['workdisk_retained']}`")
    (run_dir / "report.md").write_text("\n".join(report_lines) + "\n", encoding="utf-8")
