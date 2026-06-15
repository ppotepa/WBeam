from __future__ import annotations

import shutil
import subprocess
from pathlib import Path

from ..context import WizardContext
from ..model import StepDefinition, StepPlan, StepResult, StepStatus
from ..state import step_log_path


def parse_adb_devices(output: str) -> list[dict[str, str]]:
    rows = []
    for raw in output.splitlines()[1:]:
        parts = raw.split()
        if len(parts) >= 2:
            rows.append({"serial": parts[0], "state": parts[1]})
    return rows


def classify_adb(rows: list[dict[str, str]], requested_serial: str | None = None) -> tuple[StepStatus, str, dict[str, object], str]:
    devices = [r for r in rows if r["state"] == "device"]
    unauthorized = [r for r in rows if r["state"] == "unauthorized"]
    offline = [r for r in rows if r["state"] == "offline"]
    if requested_serial:
        match = next((r for r in rows if r["serial"] == requested_serial), None)
        if match and match["state"] == "device":
            return StepStatus.OK, f"ADB device ready: {requested_serial}", {"selected_serial": requested_serial, "rows": rows}, ""
        state = match["state"] if match else "not-detected"
        return StepStatus.BLOCKED, f"Requested ADB serial is {state}", {"rows": rows}, "Connect the requested phone, authorize USB debugging, then retry adb_probe."
    if len(devices) == 1:
        return StepStatus.OK, f"ADB device ready: {devices[0]['serial']}", {"selected_serial": devices[0]["serial"], "rows": rows}, ""
    if len(devices) > 1:
        return StepStatus.BLOCKED, "Multiple ADB devices detected", {"rows": rows}, "Rerun with --android-serial <serial>."
    if unauthorized:
        return StepStatus.BLOCKED, "ADB device is unauthorized", {"rows": rows}, "Unlock the phone and accept the USB debugging RSA prompt."
    if offline:
        return StepStatus.BLOCKED, "ADB device is offline", {"rows": rows}, "Replug USB or run: adb kill-server; adb start-server."
    return StepStatus.BLOCKED, "No ADB device detected", {"rows": rows}, "Connect the phone, enable Developer options and USB debugging."


class AdbProbeStep:
    definition = StepDefinition(id="adb_probe", title="Probe Android device", requires=("service_setup",), timeout_sec=120, optional=True)

    def plan(self, ctx: WizardContext) -> StepPlan:
        return StepPlan(
            id=self.definition.id,
            title=self.definition.title,
            summary="Probe adb devices and classify phone readiness.",
            commands=[["adb", "devices"]],
            expected_artifacts=[],
            risks=["Requires adb and optionally a connected phone."],
            next_action="Connect or authorize the phone if device onboarding is required.",
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
                summary="Android device step disabled by policy.",
                log_path=log_path,
                evidence={"device_policy": ctx.device_policy},
            )
        if ctx.dry_run:
            return StepResult(
                id=self.definition.id,
                title=self.definition.title,
                status=StepStatus.OK,
                summary="DRY-RUN: adb devices would be probed.",
                log_path=log_path,
                evidence={"commands": [["adb", "devices"]], "device_policy": ctx.device_policy},
            )
        if shutil.which("adb") is None:
            status = StepStatus.WARN if ctx.device_policy == "optional" else StepStatus.BLOCKED
            return StepResult(
                id=self.definition.id,
                title=self.definition.title,
                status=status,
                summary="adb is not available.",
                log_path=log_path,
                next_action="Install Android platform-tools / adb.",
                evidence={"device_policy": ctx.device_policy},
            )
        proc = subprocess.run(["adb", "devices"], capture_output=True, text=True, check=False)
        rows = parse_adb_devices(proc.stdout)
        status, summary, evidence, next_action = classify_adb(rows, ctx.android_serial)
        if status == StepStatus.BLOCKED and ctx.device_policy == "optional":
            status = StepStatus.WARN
            summary = summary + "; phone onboarding left pending"
        return StepResult(
            id=self.definition.id,
            title=self.definition.title,
            status=status,
            summary=summary,
            log_path=log_path,
            exit_code=proc.returncode,
            next_action=next_action,
            evidence={**evidence, "device_policy": ctx.device_policy, "adb_exit_code": proc.returncode},
        )

    def validate(self, ctx: WizardContext) -> StepResult:
        return self.run(ctx)
