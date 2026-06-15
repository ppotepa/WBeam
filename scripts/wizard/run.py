from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parents[2]
SCRIPTS_DIR = ROOT_DIR / "scripts"
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

from wizard.context import WizardContext
from wizard.engine import WizardEngine
from wizard.events import EventSink
from wizard.model import StepResult, StepStatus
from wizard.steps import AdbProbeStep, AndroidDeployStep, HostBuildStep, HostPreflightStep, ServiceSetupStep, StreamSmokeStep, SystemDepsStep
from wizard.state import read_install_state


def default_state_dir() -> Path:
    return Path(os.environ.get("XDG_STATE_HOME", Path.home() / ".local" / "state")) / "wbeam"


def default_run_id() -> str:
    from datetime import UTC, datetime

    return datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="install-wbeam")
    parser.add_argument("--backend", default="wayland")
    parser.add_argument("-y", "--yes", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--skip-system-deps", action="store_true")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--skip-service", action="store_true")
    parser.add_argument("--skip-device", action="store_true")
    parser.add_argument("--device-only", action="store_true")
    parser.add_argument("--android-serial")
    parser.add_argument("--control-port", type=int, default=5001)
    parser.add_argument("--stream-port", type=int, default=5000)
    parser.add_argument("--legacy", action="store_true")
    parser.add_argument("--json-events", action="store_true")
    parser.add_argument("--status-json", action="store_true")
    parser.add_argument("--report-dir")
    parser.add_argument("--from-step")
    parser.add_argument("--only")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--retry-step")
    parser.add_argument("--stream-smoke", action="store_true")
    parser.add_argument("--device-policy", choices=("none", "optional", "required"), default="optional")
    return parser


def build_context(args: argparse.Namespace) -> WizardContext:
    root_dir = ROOT_DIR
    state_dir = default_state_dir()
    run_id = default_run_id()
    run_dir = Path(args.report_dir).expanduser().resolve() if args.report_dir else state_dir / "runs" / run_id
    report_dir = run_dir
    env = dict(os.environ)
    env.setdefault("WBEAM_RUN_ID", run_id)
    return WizardContext(
        root_dir=root_dir,
        state_dir=state_dir,
        run_dir=run_dir,
        report_dir=report_dir,
        backend=args.backend,
        control_port=args.control_port,
        stream_port=args.stream_port,
        android_serial=args.android_serial,
        yes=args.yes,
        dry_run=args.dry_run,
        skip_system_deps=args.skip_system_deps,
        skip_build=args.skip_build,
        skip_service=args.skip_service,
        skip_device=args.skip_device or args.device_policy == "none",
        device_only=args.device_only,
        device_policy=args.device_policy,
        json_events=args.json_events,
        env=env,
    )


def load_steps() -> list:
    return [HostPreflightStep(), SystemDepsStep(), HostBuildStep(), ServiceSetupStep(), AdbProbeStep(), AndroidDeployStep(), StreamSmokeStep()]


def _latest_state_file(state_dir: Path) -> Path | None:
    direct = state_dir / "install-state.json"
    if direct.exists() and read_install_state(direct):
        return direct
    runs_root = state_dir / "runs"
    if not runs_root.exists():
        return None
    candidates = sorted(
        (path for path in runs_root.glob("*/install-state.json") if path.is_file()),
        key=lambda path: (path.stat().st_mtime, path.parent.name),
        reverse=True,
    )
    for candidate in candidates:
        if read_install_state(candidate):
            return candidate
    return candidates[0] if candidates else None


def _state_file_is_corrupt(state_file: Path | None) -> bool:
    if not state_file or not state_file.exists():
        return False
    return not read_install_state(state_file)


def status_json(state_dir: Path) -> int:
    state_file = _latest_state_file(state_dir)
    direct_state_file = state_dir / "install-state.json"
    if state_file is None and direct_state_file.exists():
        state_file = direct_state_file
    state = read_install_state(state_file) if state_file else {}
    state_corrupt = _state_file_is_corrupt(state_file)
    if not state:
        payload = {"schema": 1, "status": "missing"}
        if state_corrupt:
            payload["status"] = "corrupt"
            payload["summary_corrupt"] = True
            payload["state_file"] = str(state_file) if state_file else ""
        print(json.dumps(payload, indent=2, sort_keys=True))
        return 0
    steps = state.get("steps") or []
    current_step = None
    for step in reversed(steps):
        if isinstance(step, dict) and step.get("status") != "ok":
            current_step = {
                "id": step.get("id"),
                "title": step.get("title"),
                "status": step.get("status"),
                "log_path": step.get("log_path"),
                "last_log": tail_last_line(step.get("log_path")),
                "next_action": step.get("next_action", ""),
            }
            break
    if current_step is None and steps:
        last = steps[-1]
        if isinstance(last, dict):
            current_step = {
                "id": last.get("id"),
                "title": last.get("title"),
                "status": last.get("status"),
                "log_path": last.get("log_path"),
                "last_log": tail_last_line(last.get("log_path")),
                "next_action": last.get("next_action", ""),
            }
    recovery_commands = recovery_commands_for_status(current_step, state)
    payload = {
        "schema": 1,
        "run_id": state.get("run_id"),
        "status": state.get("status", "running" if steps else "missing"),
        "backend": state.get("backend"),
        "distro": state.get("distro"),
        "state_file": str(state_file) if state_file else "",
        "summary_corrupt": state_corrupt,
        "run_dir": str(state_file.parent) if state_file else "",
        "summary_path": str((state_file.parent / "summary.json") if state_file else ""),
        "report_md": str((state_file.parent / "report.md") if state_file and (state_file.parent / "report.md").exists() else ""),
        "steps_path": str((state_file.parent / "steps.jsonl") if state_file else ""),
        "current_step": current_step,
        "last_step": state.get("last_step"),
        "steps": steps,
        "next_action": (current_step or {}).get("next_action", ""),
        "recovery_commands": recovery_commands,
    }
    print(json.dumps(payload, indent=2, sort_keys=True))
    return 0


def render_status(state_dir: Path) -> int:
    return status_json(state_dir)


def tail_last_line(path: str | None, max_bytes: int = 4096) -> str:
    if not path:
        return ""
    log_path = Path(path)
    if not log_path.exists():
        return ""
    data = log_path.read_bytes()[-max_bytes:]
    lines = data.decode("utf-8", errors="replace").splitlines()
    return lines[-1] if lines else ""


def confirm_plan(ctx: WizardContext, steps: list) -> int:
    if ctx.yes or ctx.dry_run:
        return 0
    if not sys.stdin.isatty():
        print("[wizard] non-interactive install requires --yes or --dry-run", file=sys.stderr)
        return 2
    print("[wizard] plan:")
    for step in steps:
        plan = step.plan(ctx)
        print(f"  - {plan.id}: {plan.summary}")
        for command in plan.commands:
            print(f"      $ {' '.join(command)}")
    answer = input("Continue with this plan? [y/N] ").strip().lower()
    return 0 if answer == "y" else 2


def render_plan(ctx: WizardContext, steps: list) -> None:
    print("[wizard] plan:")
    for step in steps:
        plan = step.plan(ctx)
        print(f"  - {plan.id}: {plan.summary}")
        for command in plan.commands:
            print(f"      $ {' '.join(command)}")
        if plan.next_action:
            print(f"      next: {plan.next_action}")


def render_results(results: list[StepResult], *, run_dir: Path | None = None) -> None:
    if not results:
        print("[wizard] no steps executed")
        return
    last = results[-1]
    print(f"[wizard] last step: {last.id} [{last.status}] {last.summary}")
    if run_dir is not None:
        print(f"[wizard] run dir: {run_dir}")
        print(f"[wizard] summary: {run_dir / 'summary.json'}")
        print(f"[wizard] report: {run_dir / 'report.md'}")
        print(f"[wizard] steps: {run_dir / 'steps.jsonl'}")
    if last.next_action:
        print(f"[wizard] next action: {last.next_action}")
    recovery = recovery_commands_for_status(
        {
            "id": last.id,
            "status": str(last.status),
            "next_action": last.next_action,
        },
        {},
    )
    if recovery:
        print("[wizard] recovery:")
        for command in recovery:
            print(f"[wizard]   {command}")


def recovery_commands_for_status(current_step: dict | None, state: dict) -> list[str]:
    if not current_step:
        return []
    step_id = str(current_step.get("id", "")).strip()
    status = str(current_step.get("status", "")).strip().lower()
    next_action = str(current_step.get("next_action", "")).strip()
    commands: list[str] = []
    if status == "blocked" and "prepare-portal-consent" in next_action:
        commands.append(next_action)
        return commands
    if status == "reboot_required":
        commands.append("./install-wbeam --resume")
    elif status in {"blocked", "fail"} and step_id:
        commands.append(f"./install-wbeam --from-step {step_id}")
        commands.append("./install-wbeam --resume")
    elif status == "warn" and step_id:
        commands.append(f"./install-wbeam --from-step {step_id}")
    return commands


def run_wizard(args: argparse.Namespace) -> int:
    if args.legacy:
        legacy_args = [arg for arg in sys.argv[1:] if arg != "--legacy"]
        os.execv(str(ROOT_DIR / "scripts" / "install-wizard.sh"), [str(ROOT_DIR / "scripts" / "install-wizard.sh"), *legacy_args])
    ctx = build_context(args)
    ctx.run_dir.mkdir(parents=True, exist_ok=True)
    (ctx.run_dir / "logs").mkdir(parents=True, exist_ok=True)
    events = EventSink(jsonl_path=ctx.run_dir / "steps.jsonl", stdout_json=args.json_events)
    steps = load_steps()
    if args.status_json:
        return render_status(ctx.state_dir)
    if ctx.dry_run and not args.json_events:
        render_plan(ctx, steps)
    rc = confirm_plan(ctx, steps)
    if rc != 0:
        return rc
    engine = WizardEngine(ctx=ctx, steps=steps, events=events)
    results = engine.run(from_step=args.from_step or args.retry_step, only=args.only, resume=args.resume)
    if not args.json_events:
        render_results(results, run_dir=ctx.run_dir)
    if any(result.status == StepStatus.FAIL for result in results):
        return 1
    if any(result.status in {StepStatus.BLOCKED, StepStatus.REBOOT_REQUIRED} for result in results):
        return 3
    return 0


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    try:
        return run_wizard(args)
    except BrokenPipeError:
        return 0


if __name__ == "__main__":
    raise SystemExit(main())
