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
    return json.loads(path.read_text(encoding="utf-8"))


def scenario_report_dir(report_root: Path, run_id: str, scenario_id: str) -> Path:
    return report_root / run_id / "scenarios" / scenario_id


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
    failures = [
        {
            "scenario": result["scenario"],
            "phase": result.get("phase", "unknown"),
            "reason": result.get("reason", "unknown failure"),
        }
        for result in scenario_results
        if result.get("status") != "pass"
    ]
    write_json(
        run_dir / "summary.json",
        {
            "run_id": run_id,
            "status": "pass" if not failures else "fail",
            "scenarios_total": len(scenario_results),
            "scenarios_passed": len(scenario_results) - len(failures),
            "scenarios_failed": len(failures),
            "failures": failures,
            "results": scenario_results,
        },
    )
    write_junit(run_dir, scenario_results)
