from __future__ import annotations

from pathlib import Path

from .model import StepResult
from .state import step_result_payload, write_json_atomic
from .status import scenario_status_from_steps


def write_wizard_report_md(run_dir: Path, *, run_id: str, results: list[StepResult], summary_path: Path) -> Path:
    status_counts = {
        "ok": sum(1 for r in results if str(r.status) == "ok"),
        "warn": sum(1 for r in results if str(r.status) == "warn"),
        "fail": sum(1 for r in results if str(r.status) == "fail"),
        "blocked": sum(1 for r in results if str(r.status) == "blocked"),
        "reboot_required": sum(1 for r in results if str(r.status) == "reboot_required"),
        "skipped": sum(1 for r in results if str(r.status) == "skipped"),
    }
    status_lines = [f"- {name.replace('_', ' ').title()}: {count}" for name, count in status_counts.items() if count]
    lines = [
        "# WBeam Wizard Report",
        "",
        f"- Run ID: `{run_id}`",
        f"- Status: `{scenario_status_from_steps([result.status for result in results])}`",
        f"- Summary: `{summary_path}`",
        f"- Steps: `{run_dir / 'steps.jsonl'}`",
        "",
        "## Status Counts",
        "",
    ]
    lines.extend(status_lines or ["- No steps executed"])
    lines.extend(["", "## Steps", ""])
    if not results:
        lines.append("- No steps executed")
    else:
        for result in results:
            log_path = f"`{result.log_path}`" if result.log_path else "`-`"
            next_action = result.next_action or "-"
            lines.append(f"- `{result.id}`: `{result.status}` - {result.summary}")
            lines.append(f"  - Log: {log_path}")
            reason_code = result.evidence.get("reason_code") if isinstance(result.evidence, dict) else ""
            if reason_code:
                lines.append(f"  - Reason code: `{reason_code}`")
            if str(result.status) == "blocked":
                lines.append("  - Blocked: `true`")
            lines.append(f"  - Next: {next_action}")
    path = run_dir / "report.md"
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return path


def write_wizard_summary(run_dir: Path, *, run_id: str, results: list[StepResult]) -> Path:
    statuses = [result.status for result in results]
    payload = {
        "schema": 1,
        "run_id": run_id,
        "status": scenario_status_from_steps(statuses),
        "steps_total": len(results),
        "steps_ok": sum(1 for r in results if str(r.status) == "ok"),
        "steps_warn": sum(1 for r in results if str(r.status) == "warn"),
        "steps_failed": sum(1 for r in results if str(r.status) == "fail"),
        "steps_blocked": sum(1 for r in results if str(r.status) == "blocked"),
        "steps_reboot_required": sum(1 for r in results if str(r.status) == "reboot_required"),
        "steps_skipped": sum(1 for r in results if str(r.status) == "skipped"),
        "last_step": step_result_payload(results[-1]) if results else None,
        "steps": [step_result_payload(r) for r in results],
    }
    path = run_dir / "summary.json"
    write_json_atomic(path, payload)
    write_wizard_report_md(run_dir, run_id=run_id, results=results, summary_path=path)
    return path
