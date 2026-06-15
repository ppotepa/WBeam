from __future__ import annotations

import os
import platform
import grp
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

from ..context import WizardContext
from ..model import StepDefinition, StepPlan, StepResult, StepStatus
from ..state import step_log_path


def _read_os_release(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
            if "=" in line:
                key, value = line.split("=", 1)
                values[key] = value.strip().strip('"')
    return values


def _session_type() -> str:
    return os.environ.get("XDG_SESSION_TYPE", "unknown")


def _socket_state(path: str) -> str:
    return "available" if Path(path).exists() else "missing"


def _has_video_group() -> bool:
    for gid in os.getgroups():
        try:
            if grp.getgrgid(gid).gr_name == "video":
                return True
        except KeyError:
            continue
    return False


def _service_state(name: str) -> str:
    if shutil.which("systemctl") is None:
        return "unknown"
    try:
        proc = subprocess.run(
            ["systemctl", "--user", "is-active", name],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            check=False,
            timeout=3,
        )
        return proc.stdout.strip() or "unknown"
    except (OSError, subprocess.SubprocessError):
        return "unknown"


def _encoder_state() -> str:
    if shutil.which("gst-inspect-1.0") is None:
        return "missing"
    for encoder in ("nvh264enc", "vaapih264enc", "x264enc"):
        if subprocess.run(
            ["gst-inspect-1.0", encoder],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        ).returncode == 0:
            return f"h264:{encoder}"
    return "missing"


def _evdi_state() -> str:
    if shutil.which("mokutil") is not None:
        try:
            proc = subprocess.run(
                ["mokutil", "--sb-state"],
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                text=True,
                check=False,
                timeout=3,
            )
            if "enabled" in proc.stdout.lower():
                return "secure-boot-enabled"
        except (OSError, subprocess.SubprocessError):
            pass
    if shutil.which("modinfo") is not None:
        proc = subprocess.run(
            ["modinfo", "evdi"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            check=False,
        )
        if proc.returncode == 0:
            return "module-present"
    return "module-not-loaded"


def collect_host_probe(ctx: WizardContext, *, os_release_path: Path | None = None) -> dict[str, object]:
    os_release = _read_os_release(os_release_path or Path("/etc/os-release"))
    distro = os_release.get("ID", "unknown")
    distro_version = os_release.get("VERSION_ID", "unknown")
    package_manager = "unknown"
    if shutil.which("dnf"):
        package_manager = "dnf"
    elif shutil.which("apt-get"):
        package_manager = "apt"
    elif shutil.which("pacman"):
        package_manager = "pacman"
    evidence = {
        "distro": distro,
        "distro_version": distro_version,
        "package_manager": package_manager,
        "arch": platform.machine(),
        "session_type": _session_type(),
        "wayland": _socket_state("/run/user/%s/wayland-0" % os.getuid()),
        "x11": _socket_state("/tmp/.X11-unix/X0"),
        "portal": "active" if shutil.which("xdg-desktop-portal") else "missing",
        "pipewire": "pipewire=active" if shutil.which("pipewire") else "pipewire=missing",
        "encoder": _encoder_state(),
        "evdi": _evdi_state(),
        "video_group": _has_video_group(),
        "service": _service_state("wbeam-daemon.service"),
    }
    return evidence


def classify_host_probe(evidence: dict[str, object], *, backend: str) -> StepStatus:
    if evidence.get("distro", "unknown") == "unknown":
        return StepStatus.FAIL
    if backend == "evdi" and "secure-boot-enabled" in str(evidence.get("evdi", "")):
        return StepStatus.REBOOT_REQUIRED
    if str(evidence.get("encoder", "missing")) == "missing":
        return StepStatus.WARN
    return StepStatus.OK


def host_probe_summary(evidence: dict[str, object]) -> str:
    return (
        f"{evidence.get('distro', 'unknown')} {evidence.get('distro_version', 'unknown')}, "
        f"{evidence.get('session_type', 'unknown')} session, encoder {evidence.get('encoder', 'missing')}"
    )


def host_probe_next_action(evidence: dict[str, object], *, backend: str) -> str:
    if backend == "evdi" and "secure-boot-enabled" in str(evidence.get("evdi", "")):
        return "Reboot and enroll MOK before rerunning the wizard."
    if str(evidence.get("encoder", "missing")) == "missing":
        return "Install or expose an H264 encoder, then rerun the wizard."
    return ""


class HostPreflightStep:
    definition = StepDefinition(id="host_preflight", title="Probe host environment", timeout_sec=60)

    def probe(self, ctx: WizardContext) -> StepResult:
        return self.run(ctx)

    def plan(self, ctx: WizardContext) -> StepPlan:
        return StepPlan(
            id="host_preflight",
            title="Probe host environment",
            summary="Detect OS, session, display stack, encoder and EVDI readiness.",
            commands=[],
            expected_artifacts=[],
            risks=[],
            next_action="Review the detected host readiness and fix any blocking conditions.",
        )

    def run(self, ctx: WizardContext) -> StepResult:
        log_path = step_log_path(ctx.run_dir, self.definition.id)
        evidence = collect_host_probe(ctx)
        status = classify_host_probe(evidence, backend=ctx.backend)
        summary = host_probe_summary(evidence)
        next_action = host_probe_next_action(evidence, backend=ctx.backend)
        log_path.parent.mkdir(parents=True, exist_ok=True)
        log_path.write_text(
            "\n".join(
                [
                    f"status={status}",
                    f"summary={summary}",
                    f"next_action={next_action}",
                    f"evidence={evidence}",
                ]
            )
            + "\n",
            encoding="utf-8",
        )
        return StepResult(
            id=self.definition.id,
            title=self.definition.title,
            status=status,
            summary=summary,
            log_path=log_path,
            next_action=next_action,
            evidence=evidence,
        )

    def validate(self, ctx: WizardContext) -> StepResult:
        evidence = collect_host_probe(ctx)
        return StepResult(
            id=self.definition.id,
            title=self.definition.title,
            status=classify_host_probe(evidence, backend=ctx.backend),
            summary=host_probe_summary(evidence),
            evidence=evidence,
            next_action=host_probe_next_action(evidence, backend=ctx.backend),
        )
