from __future__ import annotations

import shutil
import subprocess
from pathlib import Path

from ..context import WizardContext
from ..model import StepDefinition, StepPlan, StepResult, StepStatus
from ..shell import run_logged
from ..state import step_log_path


def service_unit_content(ctx: WizardContext, service_name: str) -> str:
    lines = [
        "[Unit]",
        "Description=WBeam Screen Streaming Daemon",
        "After=graphical-session.target",
        "",
        "[Service]",
        "Type=simple",
        f"ExecStart={ctx.root_dir}/host/scripts/run_wbeamd.sh {ctx.control_port} {ctx.stream_port}",
        "Restart=on-failure",
        "RestartSec=3",
        "Environment=RUST_LOG=info",
        f"Environment=WBEAM_ROOT={ctx.root_dir}",
        f"Environment=WBEAM_LOCK_FILE=/tmp/wbeamd-service-{ctx.control_port}.lock",
    ]
    if ctx.backend == "evdi":
        lines.append("Environment=WBEAM_CAPTURE_BACKEND=evdi")
    lines.extend(["", "[Install]", "WantedBy=default.target", ""])
    return "\n".join(lines)


class ServiceSetupStep:
    definition = StepDefinition(id="service_setup", title="Install and enable WBeam user service", requires=("host_build",), timeout_sec=600)

    def plan(self, ctx: WizardContext) -> StepPlan:
        service_name = "wbeam-daemon"
        return StepPlan(
            id=self.definition.id,
            title=self.definition.title,
            summary="Install a user-level systemd service for the WBeam daemon.",
            commands=[
                ["systemctl", "--user", "daemon-reload"],
                ["systemctl", "--user", "enable", f"{service_name}.service"],
            ],
            expected_artifacts=[f"~/.config/systemd/user/{service_name}.service"],
            risks=["Requires systemctl --user and a functioning user session."],
            next_action="Open the log and rerun --from-step service_setup if systemd setup fails.",
        )

    def probe(self, ctx: WizardContext) -> StepResult:
        return StepResult(
            id=self.definition.id,
            title=self.definition.title,
            status=StepStatus.OK if shutil.which("systemctl") else StepStatus.WARN,
            summary="Checked user service readiness.",
            evidence={"systemctl": bool(shutil.which("systemctl"))},
        )

    def run(self, ctx: WizardContext) -> StepResult:
        service_name = "wbeam-daemon"
        log_path = step_log_path(ctx.run_dir, self.definition.id)
        unit_content = service_unit_content(ctx, service_name)
        unit_dir = Path.home() / ".config" / "systemd" / "user"
        unit_path = unit_dir / f"{service_name}.service"
        if ctx.skip_service or ctx.device_only:
            return StepResult(
                id=self.definition.id,
                title=self.definition.title,
                status=StepStatus.SKIPPED,
                summary="Service setup skipped by policy.",
                log_path=log_path,
                evidence={"unit_path": str(unit_path), "unit_content": unit_content},
            )
        if ctx.dry_run:
            return StepResult(
                id=self.definition.id,
                title=self.definition.title,
                status=StepStatus.OK,
                summary="DRY-RUN: systemd user service would be installed.",
                log_path=log_path,
                evidence={"unit_path": str(unit_path), "unit_content": unit_content},
            )
        if shutil.which("systemctl") is None:
            return StepResult(
                id=self.definition.id,
                title=self.definition.title,
                status=StepStatus.WARN,
                summary="systemctl --user is unavailable.",
                log_path=log_path,
                evidence={"unit_path": str(unit_path), "unit_content": unit_content},
                next_action="Use a desktop session with systemd user services available.",
            )
        unit_dir.mkdir(parents=True, exist_ok=True)
        unit_path.write_text(unit_content, encoding="utf-8")
        exit_code = 0
        for command in (["systemctl", "--user", "daemon-reload"], ["systemctl", "--user", "enable", f"{service_name}.service"]):
            exit_code = run_logged(command, cwd=ctx.root_dir, log_path=log_path, env=ctx.env, dry_run=False, timeout_sec=120)
            if exit_code != 0:
                break
        status = StepStatus.OK if exit_code == 0 else StepStatus.FAIL
        summary = "WBeam systemd user service installed." if status == StepStatus.OK else f"Service setup failed with exit code {exit_code}."
        next_action = "" if status == StepStatus.OK else f"Open {log_path} and rerun --from-step service_setup."
        return StepResult(
            id=self.definition.id,
            title=self.definition.title,
            status=status,
            summary=summary,
            log_path=log_path,
            exit_code=exit_code,
            next_action=next_action,
            evidence={"unit_path": str(unit_path), "unit_content": unit_content},
        )

    def validate(self, ctx: WizardContext) -> StepResult:
        return StepResult(
            id=self.definition.id,
            title=self.definition.title,
            status=StepStatus.OK,
            summary="Service validation is placeholder for now.",
            evidence={"systemctl": bool(shutil.which("systemctl"))},
        )
