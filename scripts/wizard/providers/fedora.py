from __future__ import annotations

from pathlib import Path

from ..context import WizardContext
from ..model import StepPlan, StepResult, StepStatus
from ..shell import run_logged
from .base import DistroInfo


class FedoraProvider:
    name = "fedora"

    def probe(self, ctx: WizardContext) -> StepResult:
        return StepResult(
            id="system_deps",
            title="Install system dependencies",
            status=StepStatus.OK,
            summary="Fedora provider available",
            evidence={"provider": self.name},
        )

    def plan_install(self, ctx: WizardContext, distro: DistroInfo) -> StepPlan:
        commands = [[str(ctx.root_dir / "scripts" / "fedora-setup.sh"), "--yes"]]
        if ctx.backend == "evdi":
            commands[0].append("--with-evdi")
        return StepPlan(
            id="system_deps",
            title="Install system dependencies",
            summary="Install Fedora system dependencies with the repo bootstrap script.",
            commands=commands,
            expected_artifacts=["/usr/bin/cargo", "/usr/bin/gst-inspect-1.0"],
            risks=["May require internet access, package manager access, and sudo privileges."],
            next_action="Inspect the log if Fedora dependency installation fails.",
        )

    def install(self, ctx: WizardContext, distro: DistroInfo, log_path: Path) -> StepResult:
        plan = self.plan_install(ctx, distro)
        if ctx.skip_system_deps or ctx.device_only:
            return StepResult(
                id="system_deps",
                title="Install system dependencies",
                status=StepStatus.SKIPPED,
                summary="System dependency installation skipped by policy.",
                log_path=log_path,
                evidence={"provider": self.name, "plan": plan.commands},
            )
        if ctx.dry_run:
            return StepResult(
                id="system_deps",
                title="Install system dependencies",
                status=StepStatus.OK,
                summary="DRY-RUN: Fedora dependencies would be installed.",
                log_path=log_path,
                evidence={"provider": self.name, "commands": plan.commands},
            )
        cmd = plan.commands[0]
        exit_code = run_logged(cmd, cwd=ctx.root_dir, log_path=log_path, env=ctx.env, dry_run=False, timeout_sec=3600)
        status = StepStatus.OK if exit_code == 0 else StepStatus.FAIL
        summary = "Fedora dependencies installed." if exit_code == 0 else f"Fedora dependency installation failed with exit code {exit_code}."
        next_action = "" if exit_code == 0 else f"Open {log_path} and rerun --from-step system_deps after fixing the failure."
        return StepResult(
            id="system_deps",
            title="Install system dependencies",
            status=status,
            summary=summary,
            log_path=log_path,
            exit_code=exit_code,
            next_action=next_action,
            evidence={"provider": self.name, "commands": plan.commands},
        )

    def validate(self, ctx: WizardContext, distro: DistroInfo) -> StepResult:
        cargo = Path("/usr/bin/cargo")
        gst = Path("/usr/bin/gst-inspect-1.0")
        return StepResult(
            id="system_deps",
            title="Install system dependencies",
            status=StepStatus.OK if cargo.exists() or gst.exists() else StepStatus.WARN,
            summary="Validated basic Fedora dependency presence.",
            evidence={
                "provider": self.name,
                "cargo_exists": cargo.exists(),
                "gst_inspect_exists": gst.exists(),
            },
        )
