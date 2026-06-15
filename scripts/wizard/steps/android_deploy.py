from __future__ import annotations

from ..context import WizardContext
from ..model import StepDefinition, StepPlan, StepResult, StepStatus
from ..shell import run_logged
from ..state import step_log_path


class AndroidDeployStep:
    definition = StepDefinition(id="android_deploy", title="Build and deploy Android APK", requires=("adb_probe",), timeout_sec=1800, optional=True)

    def plan(self, ctx: WizardContext) -> StepPlan:
        return StepPlan(
            id=self.definition.id,
            title=self.definition.title,
            summary="Build the Android package and deploy it to the selected device.",
            commands=[[str(ctx.root_dir / "wbeam"), "android", "deploy"], [str(ctx.root_dir / "wbeam"), "version", "doctor"]],
            expected_artifacts=[],
            risks=["Requires a selected Android device and a healthy install via USB path."],
            next_action="Run adb_probe successfully or pass --android-serial <serial>.",
        )

    def probe(self, ctx: WizardContext) -> StepResult:
        return self.run(ctx)

    def run(self, ctx: WizardContext) -> StepResult:
        log_path = step_log_path(ctx.run_dir, self.definition.id)
        if ctx.skip_device or ctx.device_policy == "none":
            return StepResult(
                id=self.definition.id,
                title=self.definition.title,
                status=StepStatus.SKIPPED,
                summary="Android deploy skipped by policy.",
                log_path=log_path,
                evidence={"device_policy": ctx.device_policy},
            )
        if not ctx.android_serial:
            status = StepStatus.BLOCKED if ctx.device_policy == "required" else StepStatus.WARN
            return StepResult(
                id=self.definition.id,
                title=self.definition.title,
                status=status,
                summary="No Android serial selected.",
                log_path=log_path,
                next_action="Run adb_probe successfully or pass --android-serial <serial>.",
                evidence={"device_policy": ctx.device_policy},
            )
        if ctx.dry_run:
            return StepResult(
                id=self.definition.id,
                title=self.definition.title,
                status=StepStatus.OK,
                summary="DRY-RUN: Android deploy and version doctor would run.",
                log_path=log_path,
                evidence={"android_serial": ctx.android_serial},
            )
        env = dict(ctx.env)
        env["WBEAM_ANDROID_SERIAL"] = ctx.android_serial
        deploy_exit = run_logged([str(ctx.root_dir / "wbeam"), "android", "deploy"], cwd=ctx.root_dir, log_path=log_path, env=env, dry_run=False, timeout_sec=self.definition.timeout_sec)
        doctor_exit = run_logged([str(ctx.root_dir / "wbeam"), "version", "doctor"], cwd=ctx.root_dir, log_path=log_path, env=env, dry_run=False, timeout_sec=120)
        status = StepStatus.OK if deploy_exit == 0 else StepStatus.FAIL
        if doctor_exit != 0 and status == StepStatus.OK:
            status = StepStatus.WARN
        summary = "Android deploy completed." if deploy_exit == 0 else f"Android deploy failed with exit code {deploy_exit}."
        next_action = "" if deploy_exit == 0 else f"Check Install via USB / phone screen / logcat, then rerun --from-step android_deploy."
        return StepResult(
            id=self.definition.id,
            title=self.definition.title,
            status=status,
            summary=summary,
            log_path=log_path,
            exit_code=deploy_exit,
            next_action=next_action,
            evidence={"android_serial": ctx.android_serial, "deploy_exit_code": deploy_exit, "doctor_exit_code": doctor_exit},
        )

    def validate(self, ctx: WizardContext) -> StepResult:
        return self.run(ctx)
