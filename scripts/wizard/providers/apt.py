from __future__ import annotations

import os
from pathlib import Path

from ..context import WizardContext
from ..model import StepPlan, StepResult, StepStatus
from ..shell import run_logged
from .base import DistroInfo

APT_PACKAGES_BASE = [
    "ca-certificates",
    "curl",
    "git",
    "jq",
    "pkg-config",
    "build-essential",
    "python3",
    "libssl-dev",
    "libglib2.0-dev",
    "libgstreamer1.0-dev",
    "libgstreamer-plugins-base1.0-dev",
    "gstreamer1.0-tools",
    "gstreamer1.0-plugins-base",
    "gstreamer1.0-plugins-good",
    "gstreamer1.0-plugins-bad",
    "gstreamer1.0-libav",
    "gstreamer1.0-x",
    "libx11-dev",
    "libxrandr-dev",
    "libxfixes-dev",
    "libxext-dev",
    "libxrender-dev",
    "xvfb",
    "x11-xserver-utils",
    "dbus-user-session",
    "pipewire",
    "wireplumber",
    "xdg-desktop-portal",
    "xdg-desktop-portal-gnome",
]


class AptProvider:
    name = "apt"

    def probe(self, ctx: WizardContext) -> StepResult:
        return StepResult(
            id="system_deps",
            title="Install system dependencies",
            status=StepStatus.OK,
            summary="APT provider available",
            evidence={"provider": self.name},
        )

    def plan_install(self, ctx: WizardContext, distro: DistroInfo) -> StepPlan:
        sudo = [] if os.geteuid() == 0 else ["sudo"]
        update = [*sudo, "apt-get", "update"]
        install = [*sudo, "apt-get", "install", "-y", "--no-install-recommends", *APT_PACKAGES_BASE]
        commands = [update, install]
        return StepPlan(
            id="system_deps",
            title="Install system dependencies",
            summary="Install APT packages required by the wizard.",
            commands=commands,
            expected_artifacts=["/usr/bin/cargo", "/usr/bin/gst-inspect-1.0"],
            risks=["May require internet access and sudo privileges."],
            next_action="Inspect the log if the APT install fails.",
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
                summary="DRY-RUN: APT dependencies would be installed.",
                log_path=log_path,
                evidence={"provider": self.name, "commands": plan.commands},
            )
        exit_code = 0
        for cmd in plan.commands:
            exit_code = run_logged(cmd, cwd=ctx.root_dir, log_path=log_path, env=ctx.env, dry_run=False, timeout_sec=3600)
            if exit_code != 0:
                break
        status = StepStatus.OK if exit_code == 0 else StepStatus.FAIL
        summary = "APT dependencies installed." if exit_code == 0 else f"APT dependency installation failed with exit code {exit_code}."
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
        return StepResult(
            id="system_deps",
            title="Install system dependencies",
            status=StepStatus.OK,
            summary="APT provider validation is placeholder for now.",
            evidence={"provider": self.name},
        )
