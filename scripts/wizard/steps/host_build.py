from __future__ import annotations

import os
from pathlib import Path

from ..context import WizardContext
from ..model import StepDefinition, StepPlan, StepResult, StepStatus
from ..shell import run_logged
from ..state import step_log_path


def host_binary_evidence(root_dir: Path) -> dict[str, object]:
    server = root_dir / "host" / "rust" / "target" / "release" / "wbeamd-server"
    streamer = root_dir / "host" / "rust" / "target" / "release" / "wbeamd-streamer"
    return {
        "server_path": str(server),
        "server_exists": server.exists(),
        "server_executable": server.exists() and os.access(server, os.X_OK),
        "streamer_path": str(streamer),
        "streamer_exists": streamer.exists(),
        "streamer_executable": streamer.exists() and os.access(streamer, os.X_OK),
    }


class HostBuildStep:
    definition = StepDefinition(id="host_build", title="Build WBeam host binaries", requires=("system_deps",), timeout_sec=2400)

    def probe(self, ctx: WizardContext) -> StepResult:
        evidence = host_binary_evidence(ctx.root_dir)
        status = StepStatus.OK if evidence["server_executable"] and evidence["streamer_executable"] else StepStatus.WARN
        return StepResult(
            id=self.definition.id,
            title=self.definition.title,
            status=status,
            summary="Checked host build artifact presence.",
            evidence=evidence,
        )

    def plan(self, ctx: WizardContext) -> StepPlan:
        return StepPlan(
            id=self.definition.id,
            title=self.definition.title,
            summary="Build host binaries with the WBeam CLI.",
            commands=[[str(ctx.root_dir / "wbeam"), "host", "build"]],
            expected_artifacts=[
                "host/rust/target/release/wbeamd-server",
                "host/rust/target/release/wbeamd-streamer",
            ],
            risks=["Build may take a long time and can fail if host deps are incomplete."],
            next_action="Open the log and rerun --from-step host_build if compilation fails.",
        )

    def run(self, ctx: WizardContext) -> StepResult:
        log_path = step_log_path(ctx.run_dir, self.definition.id)
        plan = self.plan(ctx)
        if ctx.skip_build or ctx.device_only:
            return StepResult(
                id=self.definition.id,
                title=self.definition.title,
                status=StepStatus.SKIPPED,
                summary="Host build skipped by policy.",
                log_path=log_path,
                evidence={"commands": plan.commands},
            )
        if ctx.dry_run:
            return StepResult(
                id=self.definition.id,
                title=self.definition.title,
                status=StepStatus.OK,
                summary="DRY-RUN: host binaries would be built.",
                log_path=log_path,
                evidence={"commands": plan.commands},
            )
        exit_code = run_logged(plan.commands[0], cwd=ctx.root_dir, log_path=log_path, env=ctx.env, dry_run=False, timeout_sec=self.definition.timeout_sec)
        evidence = host_binary_evidence(ctx.root_dir)
        status = StepStatus.OK if exit_code == 0 and evidence["server_executable"] and evidence["streamer_executable"] else StepStatus.FAIL
        summary = "Host binaries built." if status == StepStatus.OK else f"Host build failed with exit code {exit_code}."
        next_action = "" if status == StepStatus.OK else f"Open {log_path} and rerun --from-step host_build."
        return StepResult(
            id=self.definition.id,
            title=self.definition.title,
            status=status,
            summary=summary,
            log_path=log_path,
            exit_code=exit_code,
            next_action=next_action,
            evidence=evidence,
        )

    def validate(self, ctx: WizardContext) -> StepResult:
        evidence = host_binary_evidence(ctx.root_dir)
        status = StepStatus.OK if evidence["server_executable"] and evidence["streamer_executable"] else StepStatus.WARN
        return StepResult(
            id=self.definition.id,
            title=self.definition.title,
            status=status,
            summary="Validated host build artifacts.",
            evidence=evidence,
        )
