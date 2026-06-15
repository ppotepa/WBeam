#!/usr/bin/env python3
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import shlex
import shutil
import subprocess
import sys
import time
from pathlib import Path

# Force unbuffered output for the entire project
def print(*args, **kwargs):
    kwargs["flush"] = True
    import builtins
    builtins.print(*args, **kwargs)

from report import finalize_run_report, init_run_report, read_json, scenario_report_dir, write_json
import portal_consent
from seed import boot_append_args, create_seed_iso, desktop_shell_commands, extract_boot_assets
from vm import (
    QemuSpec,
    alloc_ssh_port,
    ensure_ssh_key,
    qemu_img_create,
    qemu_img_full_copy,
    qemu_img_overlay,
    read_public_key,
    require_tool,
    rsync_from_guest,
    rsync_to_guest,
    run_cmd,
    shutdown_guest,
    ssh,
    start_qemu,
    wait_for_ssh,
    wait_process,
)


SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = Path(__file__).resolve().parents[2]
E2E_DIR = ROOT / "e2e"
MATRIX_PATH = ROOT / "e2e" / "matrix.json"
WIZARD_ENTRYPOINT = ROOT / "install-wbeam"
ASSERT_GREEN_SCRIPT = E2E_DIR / "scripts" / "assert_green_run.py"
FINALIZE_SCRIPT = E2E_DIR / "scripts" / "finalize_e2e.py"
ISO_SOURCES = {
    "fedora-43": {
        "label": "Fedora 43 Everything netinst x86_64",
        "page_url": "https://download.fedoraproject.org/pub/fedora/linux/releases/43/Everything/x86_64/iso/",
        "download_url": "https://download.fedoraproject.org/pub/fedora/linux/releases/43/Everything/x86_64/iso/Fedora-Everything-netinst-x86_64-43-1.6.iso",
        "checksum_url": "https://download.fedoraproject.org/pub/fedora/linux/releases/43/Everything/x86_64/iso/Fedora-43-1.6-x86_64-CHECKSUM",
        "filename_hint": "Fedora-Everything-netinst-x86_64-43-1.6.iso",
    },
    "ubuntu-24.04": {
        "label": "Ubuntu 24.04 Noble desktop amd64",
        "page_url": "https://releases.ubuntu.com/noble",
        "download_url": "https://releases.ubuntu.com/noble/ubuntu-24.04.4-desktop-amd64.iso",
        "checksum_url": "https://releases.ubuntu.com/noble/SHA256SUMS",
        "filename_hint": "ubuntu-24.04.4-desktop-amd64.iso",
    },
    "debian-12": {
        "label": "Debian 12.12 Bookworm netinst amd64",
        "page_url": "https://www.debian.org/releases/bookworm/debian-installer/",
        "download_url": "https://cdimage.debian.org/cdimage/archive/12.12.0/amd64/iso-cd/debian-12.12.0-amd64-netinst.iso",
        "checksum_url": "https://cdimage.debian.org/cdimage/archive/12.12.0/amd64/iso-cd/SHA256SUMS",
        "filename_hint": "debian-12.12.0-amd64-netinst.iso",
    },
}

WIZARD_STEP_ORDER = [
    "host_preflight",
    "system_deps",
    "host_build",
    "service_setup",
    "adb_probe",
    "android_deploy",
    "stream_smoke",
]

ANSI_ESCAPE_RE = re.compile(
    r"""
    \x1b\][^\x07]*(?:\x07|\x1b\\)  # OSC
    |
    \x1b\[[0-?]*[ -/]*[@-~]        # CSI
    |
    \x1b[ -/]*[@-~]                # 7-bit fallback
    """,
    re.VERBOSE,
)
CONTROL_RE = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
ANSI_COLORS = {
    "ok": "\033[32m",
    "warn": "\033[33m",
    "error": "\033[31m",
    "progress": "\033[36m",
    "reset": "\033[0m",
}
SERIAL_NOISE_PATTERNS = (
    "audit: type=",
    "proctitle=",
    "avc:  denied",
    "plymouth",
    "brltty.service",
    "sshd-keygen@",
)
ADB_DEVICE_STATES = {"device", "unauthorized", "offline", "recovery", "sideload"}


def load_matrix() -> dict:
    with MATRIX_PATH.open("r", encoding="utf-8") as fh:
        return json.load(fh)


def load_env_local(path: Path | None = None) -> None:
    env_path = path or E2E_DIR / "env.local"
    if not env_path.exists():
        return
    for raw in env_path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip())


def validate_matrix(matrix: dict) -> list[str]:
    errors: list[str] = []
    if matrix.get("schema") not in {1, 2}:
        errors.append(f"matrix schema must be 1 or 2, got {matrix.get('schema')!r}")
    distros = matrix.get("distros", [])
    if not isinstance(distros, list) or not distros:
        errors.append("matrix.distros must be a non-empty list")
    for distro in distros:
        if not isinstance(distro, dict):
            errors.append(f"distro entry must be an object: {distro!r}")
            continue
        for key in ("id", "family", "iso_env", "ssh_user"):
            if not distro.get(key):
                errors.append(f"distro {distro.get('id', '<unknown>')} missing {key}")
    scenarios = matrix.get("scenarios", [])
    if not isinstance(scenarios, list) or not scenarios:
        errors.append("matrix.scenarios must be a non-empty list")
    for scenario in scenarios:
        if not isinstance(scenario, dict):
            errors.append(f"scenario entry must be an object: {scenario!r}")
            continue
        for key in ("id", "distro", "session", "backend", "display_mode", "tier"):
            if not scenario.get(key):
                errors.append(f"scenario {scenario.get('id', '<unknown>')} missing {key}")
        if scenario.get("device_policy", "optional") not in {"none", "optional", "required"}:
            errors.append(f"scenario {scenario.get('id', '<unknown>')} has invalid device_policy {scenario.get('device_policy')!r}")
        android_execution = scenario.get("android_execution", "none")
        if android_execution not in {"none", "host", "guest_usb"}:
            errors.append(f"scenario {scenario.get('id', '<unknown>')} has invalid android_execution {android_execution!r}")
        if scenario.get("device_policy") == "required" and android_execution == "none":
            errors.append(f"scenario {scenario.get('id', '<unknown>')} requires device but has android_execution=none")
        if android_execution == "host" and not scenario.get("host_android"):
            errors.append(f"scenario {scenario.get('id', '<unknown>')} has android_execution=host but no host_android config")
        if "expected_steps" in scenario and not all(isinstance(item, str) for item in scenario["expected_steps"]):
            errors.append(f"scenario {scenario.get('id', '<unknown>')} expected_steps must be a list of strings")
        if "wizard_flags" in scenario and not all(isinstance(item, str) for item in scenario["wizard_flags"]):
            errors.append(f"scenario {scenario.get('id', '<unknown>')} wizard_flags must be a list of strings")
        if "guest_wizard_flags" in scenario and not all(isinstance(item, str) for item in scenario["guest_wizard_flags"]):
            errors.append(f"scenario {scenario.get('id', '<unknown>')} guest_wizard_flags must be a list of strings")
        if "required_artifacts" in scenario:
            artifacts = scenario["required_artifacts"]
            if not isinstance(artifacts, list) or not all(isinstance(item, str) for item in artifacts):
                errors.append(f"scenario {scenario.get('id', '<unknown>')} required_artifacts must be a list of strings")
            for artifact in artifacts:
                if artifact.startswith("/") or ".." in artifact.split("/"):
                    errors.append(f"scenario {scenario.get('id', '<unknown>')} required_artifacts contains unsafe path {artifact!r}")
        if scenario.get("stability", "stable") not in {"stable", "experimental", "manual"}:
            errors.append(f"scenario {scenario.get('id', '<unknown>')} has invalid stability {scenario.get('stability')!r}")
    return errors


def image_specs(matrix: dict, scenarios: list[dict]) -> list[dict]:
    grouped: dict[tuple[str, str], dict] = {}
    for scenario in scenarios:
        key = (scenario["distro"], scenario["session"])
        spec = grouped.setdefault(
            key,
            {
                "distro": scenario["distro"],
                "session": scenario["session"],
                "backends": [],
                "scenarios": [],
            },
        )
        spec["backends"].append(scenario["backend"])
        spec["scenarios"].append(scenario["id"])
    return list(grouped.values())


def select_scenarios(matrix: dict, args: argparse.Namespace) -> list[dict]:
    scenarios = list(matrix.get("scenarios", []))
    def _as_set(value) -> set[str]:
        if value is None:
            return set()
        if isinstance(value, str):
            return {value}
        return set(value)

    selected_ids = _as_set(getattr(args, "scenario", None))
    selected_distros = _as_set(getattr(args, "distro", None))
    selected_backends = _as_set(getattr(args, "backend", None))
    selected_tags = _as_set(getattr(args, "tag", None))
    if selected_ids:
        scenarios = [item for item in scenarios if item["id"] in selected_ids]
    if selected_distros:
        scenarios = [item for item in scenarios if item["distro"] in selected_distros]
    if selected_backends:
        scenarios = [item for item in scenarios if item["backend"] in selected_backends]
    if selected_tags:
        scenarios = [item for item in scenarios if selected_tags.intersection(item.get("tags", []))]
    return scenarios


def scenario_duration(matrix: dict, scenario: dict) -> int:
    defaults = matrix.get("defaults", {})
    return int(scenario.get("duration_sec") or defaults.get("stream_duration_sec") or 60)


def build_guest_wizard_command(*, guest_root: str, scenario: dict, guest_report_root: str, duration: int, flags: list[str]) -> str:
    install_backend = install_backend_for_scenario_backend(scenario["backend"])
    parts = [
        "./install-wbeam",
        "--backend",
        install_backend,
        "--report-dir",
        f"{guest_report_root}/wizard",
        *flags,
    ]
    env_parts = [
        f"WBEAM_E2E_BACKEND={shlex.quote(scenario['backend'])}",
        f"WBEAM_E2E_DURATION_SEC={duration}",
    ]
    return " ".join(parts), " ".join(env_parts)


def summarize_guest_wizard_failure(
    summary_path: Path,
    steps_path: Path,
    stream_summary_path: Path | None = None,
) -> tuple[str, str, str]:
    summary = read_json(summary_path)
    stream_summary = read_json(stream_summary_path) if stream_summary_path else {}
    last_step = summary.get("last_step") or {}
    if stream_summary.get("blocked") is True and stream_summary.get("reason_code") == "portal_consent_required":
        return (
            "portal_consent",
            "Wayland portal consent required.",
            str(
                stream_summary.get("next_action")
                or "Run ./e2e/run prepare-portal-consent --distro fedora-43 --session gnome-wayland --backend wayland_portal --live, approve the portal prompt, then rerun."
            ),
        )
    if stream_summary and stream_summary.get("ok") is False:
        phase = str(stream_summary.get("phase") or "stream_smoke")
        reason = str(stream_summary.get("reason") or "stream smoke failed")
        next_action = str(stream_summary.get("next_action") or "Inspect stream summary and daemon logs.")
        return phase, reason, next_action
    failures = summary.get("failures") or []
    if isinstance(failures, list) and failures and isinstance(failures[0], dict):
        return (
            str(failures[0].get("phase") or "wizard"),
            str(failures[0].get("reason") or "wizard failed"),
            str(failures[0].get("next_action") or "Inspect guest wizard logs."),
        )
    if isinstance(last_step, dict) and str(last_step.get("status", "")).lower() not in {"ok", "skipped", ""}:
        evidence = last_step.get("evidence") or {}
        if isinstance(evidence, dict) and evidence.get("reason_code") == "portal_consent_required":
            return (
                "portal_consent",
                "Wayland portal consent required.",
                str(
                    last_step.get("next_action")
                    or evidence.get("next_action")
                    or "Run ./e2e/run prepare-portal-consent --distro fedora-43 --session gnome-wayland --backend wayland_portal --live, approve the portal prompt, then rerun."
                ),
            )
        return (
            str(last_step.get("id") or "wizard"),
            str(last_step.get("summary") or last_step.get("status") or "wizard failed"),
            str(last_step.get("next_action") or f"Inspect {steps_path}"),
        )
    return "wizard", str(summary.get("status") or "fail"), "Inspect guest wizard summary and steps."


def normalize_wizard_result(wizard_summary: dict, guest_rc: int) -> tuple[str, str, str, str]:
    last_step = wizard_summary.get("last_step") or {}
    if not isinstance(last_step, dict):
        last_step = {}
    summary_status = str(wizard_summary.get("status") or "").lower()
    last_status = str(last_step.get("status") or "").lower()
    if summary_status == "pass" and guest_rc == 0:
        return "pass", "wizard", "pass", ""
    evidence = last_step.get("evidence") or {}
    if not isinstance(evidence, dict):
        evidence = {}
    reason_code = str(evidence.get("reason_code") or wizard_summary.get("reason_code") or "")
    if summary_status == "blocked" or last_status == "blocked":
        if reason_code == "portal_consent_required":
            return (
                "blocked",
                "portal_consent",
                "Wayland portal consent required.",
                str(
                    last_step.get("next_action")
                    or wizard_summary.get("next_action")
                    or "Run ./e2e/run prepare-portal-consent --distro fedora-43 --session gnome-wayland --backend wayland_portal --live, approve the portal prompt, then rerun."
                ),
            )
        return (
            "blocked",
            str(last_step.get("phase") or last_step.get("id") or "wizard"),
            str(last_step.get("summary") or wizard_summary.get("reason") or "blocked"),
            str(last_step.get("next_action") or wizard_summary.get("next_action") or "Inspect the wizard summary and steps."),
        )
    if guest_rc != 0:
        return (
            "fail",
            str(last_step.get("phase") or last_step.get("id") or "wizard"),
            f"guest exited with rc={guest_rc}; {last_step.get('summary') or wizard_summary.get('reason') or 'wizard failed'}",
            str(last_step.get("next_action") or wizard_summary.get("next_action") or "Inspect the wizard summary and steps."),
        )
    return "fail", str(last_step.get("phase") or last_step.get("id") or "wizard"), str(wizard_summary.get("reason") or "wizard failed"), str(wizard_summary.get("next_action") or "")


def default_wizard_flags_for_scenario(scenario: dict) -> list[str]:
    flags = ["--yes", "--stream-smoke"]
    if scenario.get("device_policy", "none") == "none":
        flags.append("--skip-device")
    return flags


INSTALL_BACKEND_BY_SCENARIO_BACKEND = {
    "benchmark_game": "benchmark_game",
    "wayland_portal": "wayland",
    "evdi": "evdi",
    "x11_gst": "x11",
}

INSTALL_BACKEND_BY_SESSION = {
    "headless": "benchmark_game",
    "gnome-wayland": "wayland",
    "gnome-xorg": "x11",
}


def install_backend_for_session(session: str) -> str:
    return INSTALL_BACKEND_BY_SESSION.get(session, "wayland")


def install_backend_for_scenario_backend(backend: str) -> str:
    return INSTALL_BACKEND_BY_SCENARIO_BACKEND.get(backend, backend)


def guest_wizard_flags_for_scenario(scenario: dict) -> list[str]:
    if scenario.get("guest_wizard_flags"):
        return list(scenario["guest_wizard_flags"])
    flags = list(scenario.get("wizard_flags") or default_wizard_flags_for_scenario(scenario))
    if scenario.get("android_execution") == "host" and "--skip-device" not in flags:
        flags.append("--skip-device")
    return flags


def resolve_android_serial(args: argparse.Namespace, scenario: dict) -> str | None:
    return getattr(args, "android_serial", None) or os.environ.get("WBEAM_ANDROID_SERIAL") or scenario.get("android_serial")


def adb_devices() -> list[dict[str, str]]:
    try:
        proc = subprocess.run(["adb", "devices", "-l"], capture_output=True, text=True, check=False, timeout=8)
    except FileNotFoundError:
        return [{"serial": "", "state": "missing"}]
    except (OSError, subprocess.SubprocessError):
        return [{"serial": "", "state": "error"}]
    rows: list[dict[str, str]] = []
    output = "\n".join([proc.stdout, proc.stderr])
    for line in output.splitlines():
        line = line.strip()
        if not line or line.lower().startswith("list of devices attached"):
            continue
        if line.startswith("* daemon "):
            continue
        parts = line.strip().split()
        if len(parts) >= 3 and parts[1] == "no" and parts[2] == "permissions":
            rows.append({"serial": parts[0], "state": "no_permissions"})
            continue
        if len(parts) >= 2:
            if parts[1] in ADB_DEVICE_STATES:
                rows.append({"serial": parts[0], "state": parts[1]})
    if not rows:
        try:
            subprocess.run(["adb", "start-server"], capture_output=True, text=True, check=False, timeout=8)
            retry = subprocess.run(["adb", "devices", "-l"], capture_output=True, text=True, check=False, timeout=8)
        except (FileNotFoundError, OSError, subprocess.SubprocessError):
            return rows
        for line in "\n".join([retry.stdout, retry.stderr]).splitlines():
            line = line.strip()
            if not line or line.lower().startswith("list of devices attached") or line.startswith("* daemon "):
                continue
            parts = line.split()
            if len(parts) >= 3 and parts[1] == "no" and parts[2] == "permissions":
                rows.append({"serial": parts[0], "state": "no_permissions"})
                continue
            if len(parts) >= 2:
                if parts[1] in ADB_DEVICE_STATES:
                    rows.append({"serial": parts[0], "state": parts[1]})
    return rows


def select_android_serial(requested: str | None = None) -> tuple[str | None, str, str]:
    rows = adb_devices()
    if rows and rows[0].get("state") == "missing":
        return None, "blocked", "adb command not found"
    if rows and rows[0].get("state") == "error":
        return None, "blocked", "adb probe failed"
    if requested:
        match = next((row for row in rows if row["serial"] == requested), None)
        if not match:
            return None, "blocked", f"requested serial not found: {requested}"
        if match["state"] != "device":
            return None, "blocked", f"requested serial is {match['state']}"
        return requested, "ok", "requested device ready"
    ready = [row for row in rows if row["state"] == "device"]
    if len(ready) == 1:
        return ready[0]["serial"], "ok", "single device ready"
    if len(ready) > 1:
        return None, "blocked", "multiple devices; pass --android-serial"
    if any(row["state"] == "unauthorized" for row in rows):
        return None, "blocked", "device unauthorized; accept RSA prompt"
    if any(row["state"] == "offline" for row in rows):
        return None, "blocked", "device offline; reconnect USB"
    if any(row["state"] == "no_permissions" for row in rows):
        return None, "blocked", "device has no adb permissions; check udev rules and USB access"
    return None, "blocked", "no adb device"


def android_preflight_reason_code(reason: str) -> str:
    normalized = reason.lower()
    if "unauthorized" in normalized or "rsa" in normalized:
        return "android_device_unauthorized"
    return "android_device_missing"


def publish_scenario_artifacts(*, scenario_run_dir: Path, scenario_report_dir: Path, guest_report_root: Path) -> None:
    scenario_report_dir.mkdir(parents=True, exist_ok=True)
    if guest_report_root.exists():
        shutil.copytree(guest_report_root, scenario_report_dir / "guest", dirs_exist_ok=True)
    for name in ("qemu.log", "serial.log", "guest-wizard.log"):
        src = scenario_run_dir / name
        if src.exists():
            dst = scenario_report_dir / "logs" / name
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dst)


def validate_required_artifacts(root: Path, scenario: dict) -> list[str]:
    missing: list[str] = []
    for rel_path in scenario.get("required_artifacts") or []:
        if not (root / rel_path).exists():
            missing.append(rel_path)
    return missing


def scenario_report_path_from_run_dir(run_dir: Path, scenario_id: str) -> Path:
    return run_dir / "scenarios" / scenario_id


def runner_report_path(report_root: Path, run_id: str) -> Path:
    return report_root / run_id


def scenario_workdisk_path(work_root: Path, run_id: str, scenario_id: str) -> Path:
    return work_root / "runs" / run_id / scenario_id / "disk.qcow2"


def scenario_requires_host_android(scenario: dict) -> bool:
    return scenario.get("device_policy") == "required" and scenario.get("android_execution") == "host"


def backing_image_for_scenario(scenario: dict, base_root: Path) -> tuple[Path, str]:
    if scenario_requires_portal_consent(scenario):
        consented = portal_consented_image_path(scenario["distro"], scenario["session"], base_root)
        valid, _reason = portal_consented_image_is_valid(scenario["distro"], scenario["session"], base_root)
        if valid:
            return consented, "portal_consented"
    return installed_image_path(scenario["distro"], scenario["session"], base_root), "installed"


def scenario_requires_portal_consent(scenario: dict) -> bool:
    return scenario.get("backend") == "wayland_portal" and scenario.get("requires_portal") is True


def run_host_android_smoke(
    *,
    serial: str,
    host_control_port: int,
    host_stream_port: int,
    scenario: dict,
    report_dir: Path,
    duration_sec: int,
    live: bool,
    dry_run: bool = False,
) -> int:
    host_android = scenario.get("host_android") or {}
    cmd = [
        sys.executable,
        str(SCRIPT_DIR / "host_android_smoke.py"),
        "--serial",
        serial,
        "--host-control-port",
        str(host_control_port),
        "--host-stream-port",
        str(host_stream_port),
        "--phone-control-port",
        str(host_android.get("phone_control_port", 5001)),
        "--phone-stream-port",
        str(host_android.get("phone_stream_port", 5000)),
        "--backend",
        scenario["backend"],
        "--display-mode",
        scenario.get("display_mode", "duplicate"),
        "--duration-sec",
        str(duration_sec),
        "--min-bytes-received",
        str(host_android.get("min_bytes_received", 1)),
        "--report-dir",
        str(report_dir),
    ]
    if dry_run:
        cmd.append("--dry-run")
    proc = run_cmd(cmd, log=report_dir / "host-android-smoke.log", check=False, live=live)
    return proc.returncode


def matrix_control_port(*, matrix: dict, scenario: dict) -> int:
    return int(scenario.get("control_port") or matrix.get("defaults", {}).get("control_port") or 5001)


def matrix_stream_port(*, matrix: dict, scenario: dict) -> int:
    return int(scenario.get("stream_port") or matrix.get("defaults", {}).get("stream_port") or 5000)


def collect_host_info() -> dict:
    def run_text(cmd: list[str]) -> str:
        try:
            return subprocess.run(cmd, capture_output=True, text=True, check=False).stdout.strip()
        except OSError:
            return ""

    qemu_system = run_text(["qemu-system-x86_64", "--version"])
    qemu_img = run_text(["qemu-img", "--version"])
    adb_version = run_text(["adb", "version"])
    return {
        "repo_root": str(ROOT),
        "git_rev": run_text(["git", "-C", str(ROOT), "rev-parse", "HEAD"]),
        "git_dirty": bool(run_text(["git", "-C", str(ROOT), "status", "--porcelain"])),
        "kernel": run_text(["uname", "-a"]),
        "qemu_system": qemu_system.splitlines()[0] if qemu_system else "",
        "qemu_img": qemu_img.splitlines()[0] if qemu_img else "",
        "adb": adb_version.splitlines()[0] if adb_version else "",
        "python": sys.version,
    }


def status_snapshot(matrix: dict, *, base_root: Path | None = None, report_root: Path | None = None) -> dict:
    base_root = base_root or base_dir()
    report_root = report_root or report_dir()
    scenarios = matrix.get("scenarios", [])
    specs = image_specs(matrix, scenarios)
    total = len(specs)
    base_ready = sum(1 for spec in specs if base_image_path(spec["distro"], spec["session"], base_root).exists())
    installed_ready = sum(1 for spec in specs if installed_image_path(spec["distro"], spec["session"], base_root).exists())
    missing_base = [
        {
            "distro": spec["distro"],
            "session": spec["session"],
            "path": str(base_image_path(spec["distro"], spec["session"], base_root)),
        }
        for spec in specs
        if not base_image_path(spec["distro"], spec["session"], base_root).exists()
    ]
    missing_iso = [
        {"env": d["iso_env"], "id": d["id"]}
        for d in matrix.get("distros", [])
        if not os.environ.get(d["iso_env"])
    ]
    report_runs = len([p for p in report_root.glob("*") if p.is_dir()]) if report_root.exists() else 0
    assets_total = total * 2
    assets_ready = base_ready + installed_ready
    assets_percent = int((assets_ready / assets_total) * 100) if assets_total else 0
    percent = max(1, int((installed_ready / total) * 100)) if total and report_runs else int((installed_ready / total) * 100) if total else 0
    runs = sorted((p for p in report_root.iterdir() if p.is_dir()), key=lambda p: p.name, reverse=True) if report_root.exists() else []
    last_run = _collect_run_overview(runs[0]) if runs else {}
    last_failed = {}
    for run_dir in runs:
        overview = _collect_run_overview(run_dir)
        if overview.get("status") != "pass":
            last_failed = overview
            break
    recovery_commands = _collect_recovery_commands(last_failed)
    portal_specs = [
        (spec["distro"], spec["session"])
        for spec in specs
        if any(
            scenario.get("backend") == "wayland_portal" and scenario.get("requires_portal")
            for scenario in scenarios
            if scenario.get("distro") == spec["distro"] and scenario.get("session") == spec["session"]
        )
    ]
    portal_unique = sorted(set(portal_specs))
    portal_missing: list[dict[str, object]] = []
    portal_ready = 0
    for distro_id, session in portal_unique:
        valid, reason = portal_consented_image_is_valid(distro_id, session, base_root)
        if valid:
            portal_ready += 1
        else:
            portal_missing.append(
                {
                    "distro": distro_id,
                    "session": session,
                    "path": str(portal_consented_image_path(distro_id, session, base_root)),
                    "reason": reason,
                }
            )
    portal_total = len(portal_unique)
    portal_percent = int((portal_ready / portal_total) * 100) if portal_total else 100
    return {
        "percent": percent,
        "asset_percent": assets_percent,
        "assets_total": assets_total,
        "assets_ready": assets_ready,
        "base_images_ready": base_ready,
        "installed_images_ready": installed_ready,
        "live_run_verified": False,
        "dry_run_verified": report_runs > 0,
        "report_runs": report_runs,
        "portal_consent_total": portal_total,
        "portal_consent_ready": portal_ready,
        "portal_consent_percent": portal_percent,
        "missing_portal_consented_images": portal_missing,
        "last_run": last_run,
        "last_failed_run": last_failed,
        "last_failed_scenario": (last_failed.get("last_failure") or {}).get("scenario", "") if last_failed else "",
        "last_failed_reason": (last_failed.get("last_failure") or {}).get("reason", "") if last_failed else "",
        "recovery_commands": recovery_commands,
        "missing_iso_inputs": missing_iso,
        "missing_base_images": missing_base,
        "next_commands": [
            "./e2e/run init-env",
            "./e2e/run iso-sources",
            'eval "$(./e2e/run env-shell)"',
            *[f"export {item['env']}=<path>" for item in missing_iso],
            "./e2e/run prepare-base --all --missing --live",
            "./e2e/run prepare-installed --distro fedora-43 --session headless --live",
            "./e2e/run diagnose-portal-consent --distro fedora-43 --session gnome-wayland",
            "./e2e/run prepare-portal-consent --distro fedora-43 --session gnome-wayland --backend wayland_portal --live --promote",
            "./e2e/run run --scenario fedora43-headless-benchmark-h264 --live",
            "./e2e/run history",
            "./e2e/run last-failed",
            "./e2e/run rerun-last-failed --live",
        ],
    }


def cmd_env_shell(args: argparse.Namespace) -> int:
    env_file = Path(args.file)
    if not env_file.exists():
        return 0
    for line in env_file.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        print(f"export {key.strip()}={value.strip()}")
    return 0


def cmd_init_env(args: argparse.Namespace) -> int:
    target = Path(args.file)
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and not args.force:
        print(str(target.resolve()))
        return 0
    lines = [
        "WBEAM_E2E_ISO_FEDORA_43=",
        "WBEAM_E2E_ISO_UBUNTU_24_04=",
        "WBEAM_E2E_ISO_DEBIAN_12=",
    ]
    target.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(str(target.resolve()))
    return 0


def cmd_next(args: argparse.Namespace) -> int:
    commands = [
        "./e2e/run init-env",
        "./e2e/run iso-sources",
        "./e2e/run status",
    ]
    if getattr(args, "json", False):
        print(json.dumps(commands, indent=2))
    else:
        for command in commands:
            print(command)
    return 0


def cmd_iso_sources(args: argparse.Namespace) -> int:
    for distro_id, info in ISO_SOURCES.items():
        print(f"{distro_id}:")
        print(f"  page: {info['page_url']}")
        print(f"  download: {info['download_url']}")
        print(f"  checksum: {info['checksum_url']}")
        print(f"  filename: {info['filename_hint']}")
    return 0


def cmd_plan(args: argparse.Namespace) -> int:
    matrix = load_matrix()
    scenarios = select_scenarios(matrix, args)
    if not scenarios:
        print("[e2e] no scenarios selected")
        return 0
    for scenario in scenarios:
        duration = scenario_duration(matrix, scenario)
        wizard_flags = guest_wizard_flags_for_scenario(scenario)
        wizard_cmd, wizard_env = build_guest_wizard_command(
            guest_root=str(ROOT),
            scenario=scenario,
            guest_report_root="${guest_report_root}",
            duration=duration,
            flags=wizard_flags,
        )
        print(f"scenario: {scenario['id']}")
        print(f"  wizard: {wizard_env} {wizard_cmd}")
        print("  expected steps: " + " -> ".join(scenario.get("expected_steps") or WIZARD_STEP_ORDER[:5]))
        print("  required artifacts:")
        for artifact in scenario.get("required_artifacts") or ["wizard/steps.jsonl", "wizard/summary.json"]:
            print(f"    {artifact}")
    return 0


def cmd_validate(args: argparse.Namespace) -> int:
    matrix = load_matrix()
    errors = validate_matrix(matrix)
    if errors:
        for error in errors:
            print(f"[e2e][ERROR] {error}")
        return 1
    print("[e2e] matrix valid")
    return 0


def cmd_assert_run(args: argparse.Namespace) -> int:
    cmd = [
        sys.executable,
        str(ASSERT_GREEN_SCRIPT),
        "--report-root",
        str(Path(getattr(args, "report_dir", None) or report_dir()).expanduser().resolve()),
        "--run-id",
        args.run_id,
        "--scenario",
        args.scenario,
        "--min-bytes",
        str(int(getattr(args, "min_bytes", 1))),
    ]
    if getattr(args, "require_portal_consented", False):
        cmd.append("--require-portal-consented")
    proc = subprocess.run(cmd, text=True, capture_output=True, check=False)
    if proc.stdout:
        print(proc.stdout, end="")
    if proc.stderr:
        print(proc.stderr, end="", file=sys.stderr)
    return proc.returncode


def cmd_close(args: argparse.Namespace) -> int:
    cmd = [
        sys.executable,
        str(FINALIZE_SCRIPT),
        "--profile",
        getattr(args, "profile", "fedora-mvp"),
        "--run-prefix",
        getattr(args, "run_prefix", "FINAL-E2E-CLOSURE"),
    ]
    if getattr(args, "live", False):
        cmd.append("--live")
    if getattr(args, "json", False):
        cmd.append("--json")
    proc = subprocess.run(cmd, text=True, capture_output=True, check=False)
    if proc.stdout:
        print(proc.stdout, end="")
    if proc.stderr:
        print(proc.stderr, end="", file=sys.stderr)
    return proc.returncode


def utc_timestamp() -> str:
    return dt.datetime.now(dt.UTC).strftime("%Y%m%dT%H%M%SZ")


def utc_iso_timestamp() -> str:
    return dt.datetime.now(dt.UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def distros_by_id(matrix: dict) -> dict[str, dict]:
    return {d["id"]: d for d in matrix.get("distros", [])}


def path_from_env(env_name: str, default: Path) -> Path:
    return Path(os.environ.get(env_name, str(default))).expanduser().resolve()


def base_dir() -> Path:
    return path_from_env("WBEAM_E2E_BASE_DIR", E2E_DIR / "images" / "base")


def work_dir() -> Path:
    return path_from_env("WBEAM_E2E_WORK_DIR", E2E_DIR / "work")


def report_dir() -> Path:
    return path_from_env("WBEAM_E2E_REPORT_DIR", E2E_DIR / "reports")


def base_image_path(distro_id: str, session: str, base_root: Path | None = None) -> Path:
    root = base_root or base_dir()
    return root / distro_id / f"{session}.qcow2"


def base_manifest_path(distro_id: str, session: str, base_root: Path | None = None) -> Path:
    return base_image_path(distro_id, session, base_root).with_suffix(".json")


def ssh_key_path() -> Path:
    return E2E_DIR / "id_ed25519"


def use_color() -> bool:
    value = os.environ.get("WBEAM_E2E_COLOR", "auto").strip().lower()
    if value in {"1", "true", "yes", "always"}:
        return True
    if value in {"0", "false", "no", "never"}:
        return False
    return sys.stdout.isatty()


def strip_terminal_controls(raw: str) -> str:
    text = raw.replace("\r", "")
    text = ANSI_ESCAPE_RE.sub("", text)
    text = CONTROL_RE.sub("", text)
    return re.sub(r"\s+", " ", text).strip()


def should_show_serial_line(line: str) -> bool:
    if not line:
        return False
    lowered = line.lower()
    if any(pattern in lowered for pattern in SERIAL_NOISE_PATTERNS):
        return False
    return any(
        marker in lowered
        for marker in (
            "[ ok ",
            "[failed",
            "[ warn",
            " error",
            " failed",
            "starting ",
            "started ",
            "finished ",
            "reached target",
            "anaconda",
            "kickstart",
            "install",
            "networkmanager",
            "ssh access",
        )
    )


def serial_line_level(line: str) -> str:
    lowered = line.lower()
    if "[failed" in lowered or " failed" in lowered or " error" in lowered:
        return "error"
    if "[ warn" in lowered or " warning" in lowered:
        return "warn"
    if "[ ok " in lowered or "started " in lowered or "finished " in lowered or "reached target" in lowered:
        return "ok"
    return "progress"


def serial_line_message(line: str) -> str:
    text = re.sub(r"^\[\s*OK\s*\]\s*", "", line, flags=re.IGNORECASE)
    text = re.sub(r"^\[\s*FAILED\s*\]\s*", "", text, flags=re.IGNORECASE)
    text = re.sub(r"^\[\s*WARN(?:ING)?\s*\]\s*", "", text, flags=re.IGNORECASE)
    return text.strip()


def format_serial_line(name: str, raw: str, *, color: bool | None = None) -> str | None:
    line = strip_terminal_controls(raw)
    if not should_show_serial_line(line):
        return None
    level = serial_line_level(line)
    label = {"ok": "OK", "warn": "WARN", "error": "ERROR", "progress": "..."}.get(level, "...")
    message = serial_line_message(line)
    prefix = f"[{name}] {label:5}"
    if color is None:
        color = use_color()
    if color:
        return f"{ANSI_COLORS[level]}{prefix}{ANSI_COLORS['reset']} {message}"
    return f"{prefix} {message}"


def tail_serial_log(proc: subprocess.Popen, log_path: Path, timeout_sec: int, name: str) -> int:
    """Tails a log file until the process exits or timeout is reached."""
    start_time = time.time()
    last_size = 0
    color = use_color()
    while time.time() - start_time < timeout_sec:
        if proc.poll() is not None:
            return proc.returncode

        if log_path.exists():
            current_size = log_path.stat().st_size
            if current_size > last_size:
                with log_path.open("r", encoding="utf-8", errors="replace") as f:
                    f.seek(last_size)
                    for line in f:
                        formatted = format_serial_line(name, line, color=color)
                        if formatted:
                            print(formatted)
                last_size = current_size

        time.sleep(1)

    # Timeout
    proc.terminate()
    raise TimeoutError(f"{name} timed out after {timeout_sec}s")


def installed_image_path(distro_id: str, session: str, base_root: Path | None = None) -> Path:
    """Path for the Layer-1 'installed' snapshot (packages + compiled binaries)."""
    root = base_root or base_dir()
    return root / distro_id / f"{session}-installed.qcow2"


def installed_manifest_path(distro_id: str, session: str, base_root: Path | None = None) -> Path:
    return installed_image_path(distro_id, session, base_root).with_suffix(".json")


PORTAL_CONSENTED_KINDS = {"portal_consented", "portal-consented"}


def is_portal_consented_kind(kind: str) -> bool:
    return str(kind or "").strip() in PORTAL_CONSENTED_KINDS


def portal_consented_image_path(distro_id: str, session: str, base_root: Path | None = None) -> Path:
    root = base_root or base_dir()
    return root / distro_id / f"{session}-portal-consented.qcow2"


def portal_consented_manifest_path(distro_id: str, session: str, base_root: Path | None = None) -> Path:
    return portal_consented_image_path(distro_id, session, base_root).with_suffix(".json")


def portal_consent_next_action(distro: str, session: str) -> str:
    return (
        f"Run ./e2e/run prepare-portal-consent --distro {distro} "
        f"--session {session} --backend wayland_portal --live --promote"
    )


def is_valid_portal_consented_manifest(path: Path, *, distro: str, session: str, backend: str = "wayland_portal") -> tuple[bool, str]:
    payload = read_json(path)
    if not payload:
        return False, "missing_or_invalid_manifest"
    kind = str(payload.get("kind", ""))
    if not is_portal_consented_kind(kind):
        return False, f"unexpected_kind:{kind or 'missing'}"
    if payload.get("distro") != distro:
        return False, "distro_mismatch"
    if payload.get("session") != session:
        return False, "session_mismatch"
    if payload.get("backend") not in {backend, "wayland_portal", "wayland"}:
        return False, "backend_mismatch"
    if payload.get("stream_smoke_ok") is not True:
        return False, "stream_smoke_not_ok"
    return True, "ok"


def portal_consented_image_is_valid(distro: str, session: str, base_root: Path) -> tuple[bool, str]:
    image = portal_consented_image_path(distro, session, base_root)
    manifest = portal_consented_manifest_path(distro, session, base_root)
    if not image.exists():
        return False, "missing_image"
    if image.stat().st_size < 10 * 1024 * 1024:
        return False, "image_too_small"
    return is_valid_portal_consented_manifest(manifest, distro=distro, session=session)


def offline_provision_marker(run_dir: Path) -> Path:
    return run_dir / ".offline-provision-done"


def run_dir_for_base(spec: dict, work_root: Path) -> Path:
    return work_root / "base" / spec["distro"] / spec["session"]


def require_iso_path(distro: dict, dry_run: bool = False) -> Path:
    env_name = distro["iso_env"]
    val = os.environ.get(env_name)
    if not val:
        if dry_run:
            return Path(f"<{env_name}-NOT-SET>")
        raise RuntimeError(f"environment variable {env_name} is not set")
    path = Path(val).expanduser().resolve()
    if not path.exists():
        if dry_run:
            return path
        raise RuntimeError(f"ISO file not found for {distro['id']} at {path}")
    return path


def coerce_int(val: str | int | None, default: int) -> int:
    if val is None:
        return default
    try:
        return int(val)
    except (ValueError, TypeError):
        return default


def safe_remove(path: Path) -> None:
    if not path.exists():
        return
    if path.is_file():
        path.unlink()
    elif path.is_dir():
        shutil.rmtree(path)


def should_keep_workdisk(retain_workdisk: str, succeeded: bool) -> bool:
    policy = (retain_workdisk or "on-fail").strip().lower()
    if policy == "always":
        return True
    if policy == "never":
        return False
    if policy == "on-success":
        return succeeded
    return not succeeded


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def write_manifest(path: Path, payload: dict) -> None:
    write_text(path, json.dumps(payload, indent=2, sort_keys=True))


def resolve_portal_consent_display(args: argparse.Namespace, *, live: bool) -> tuple[str, tuple[str, ...], str]:
    requested = getattr(args, "display", "auto") or "auto"
    if requested == "none":
        return "none", (), "none"
    if requested in {"gtk", "sdl"}:
        return requested, (), requested
    if requested == "vnc":
        port = int(getattr(args, "vnc_port", None) or alloc_ssh_port("portal-consent-vnc", start=5901, span=400))
        display_no = max(1, port - 5900)
        return "none", ("-vnc", f"127.0.0.1:{display_no}"), f"vnc:127.0.0.1:{port}"
    if live and os.environ.get("DISPLAY"):
        return "gtk", (), "gtk"
    if live:
        port = int(getattr(args, "vnc_port", None) or alloc_ssh_port("portal-consent-vnc", start=5901, span=400))
        display_no = max(1, port - 5900)
        return "none", ("-vnc", f"127.0.0.1:{display_no}"), f"vnc:127.0.0.1:{port}"
    return "none", (), "none"


def write_portal_operator_artifacts(
    portal_work_dir: Path,
    *,
    display_hint: str,
    command: str,
    consented: Path,
    manifest: Path,
    timeout_sec: int,
) -> None:
    payload = {
        "schema": 1,
        "display_hint": display_hint,
        "guest_command": command,
        "portal_consented_image": str(consented),
        "portal_consented_manifest": str(manifest),
        "approval_timeout_sec": timeout_sec,
        "manual_action": "Approve GNOME ScreenCast prompt in the VM window.",
    }
    write_json(portal_work_dir / "operator.json", payload)
    write_text(
        portal_work_dir / "operator.md",
        "\n".join(
        [
            "# Portal Consent Operator Notes",
            "",
            f"- Display hint: `{display_hint}`",
            f"- Guest command: `{command}`",
                f"- Portal-consented image: `{consented}`",
                f"- Portal-consented manifest: `{manifest}`",
                f"- Approval timeout sec: `{timeout_sec}`",
                "",
                "Approve GNOME ScreenCast prompt in the VM window.",
            ]
        )
        + "\n",
    )


def default_qemu_display(session: str, *, installer: bool = False) -> str:
    # During automated installer, always hide.
    return "none"


def wait_debian_installer_complete(
    proc: subprocess.Popen,
    log_path: Path,
    timeout_sec: int,
    *,
    name: str,
) -> None:
    start = time.time()
    last_size = 0
    while time.time() - start < timeout_sec:
        if proc.poll() is not None:
            if proc.returncode == 0:
                return
            raise RuntimeError(f"{name} failed with exit code {proc.returncode}")

        if log_path.exists():
            current_size = log_path.stat().st_size
            if current_size > last_size:
                with log_path.open("r", encoding="utf-8", errors="replace") as fh:
                    fh.seek(last_size)
                    for line in fh:
                        line = line.strip()
                        if line:
                            print(f"[{name}] {line}")
                            # Debian preseed signals finish with 'finish-install'
                            if "finish-install" in line:
                                return
                last_size = current_size
        time.sleep(5)
    raise TimeoutError(f"{name} did not complete within {timeout_sec}s")


def provision_debian_install_offline(
    *,
    disk: Path,
    build_dir: Path,
    distro_id: str,
    session: str,
    ssh_user: str,
    public_key: str,
) -> None:
    """Uses virt-customize to fixup Debian install after basic installer exits."""
    pass


def base_sanity_command(session: str) -> str:
    cmds = ["id", "uname -a", "df -h", "free -m"]
    if session.startswith("gnome"):
        cmds += ["test -f /etc/gdm/custom.conf", "systemctl is-active gdm", "loginctl list-sessions"]
    return " && ".join(cmds)


def ensure_systemd_unit_enabled(root: Path, unit_name: str, log_path: Path) -> None:
    system_dir = root / "lib" / "systemd" / "system"
    unit_path = system_dir / unit_name
    if not unit_path.exists():
        raise FileNotFoundError(unit_path)
    wants_dir = root / "etc" / "systemd" / "system"
    wants_dir.mkdir(parents=True, exist_ok=True)
    target_dir = wants_dir / ("sockets.target.wants" if unit_name.endswith(".socket") else "multi-user.target.wants")
    target_dir.mkdir(parents=True, exist_ok=True)
    link = target_dir / unit_name
    if link.exists() or link.is_symlink():
        link.unlink()
    link.symlink_to(unit_path)
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("a", encoding="utf-8") as fh:
        fh.write(f"enabled {unit_name}\n")


def collect_guest_command_output(
    *,
    user: str,
    port: int,
    key: Path,
    command: str,
    output: Path,
) -> None:
    proc = ssh(user, port, key, command, check=False)
    output.write_text(proc.stdout, encoding="utf-8")


def cmd_prepare_base(args: argparse.Namespace) -> int:
    matrix = load_matrix()
    base_root = Path(args.base_dir).expanduser().resolve() if args.base_dir else base_dir()
    dry_run = getattr(args, "dry_run", False)
    missing_only = getattr(args, "missing", False)
    live = getattr(args, "live", False)

    specs = []
    if getattr(args, "all", False):
        for d in matrix["distros"]:
            specs.append({"distro": d["id"], "session": "gnome-wayland"})
    elif getattr(args, "distro", None):
        distro = args.distro[0] if isinstance(args.distro, list) else args.distro
        session = (args.session[0] if isinstance(args.session, list) else args.session) if getattr(args, "session", None) else "gnome-wayland"
        specs.append({"distro": distro, "session": session})

    if missing_only:
        missing = []
        for s in specs:
            img = base_image_path(s["distro"], s["session"], base_root)
            if not img.exists():
                missing.append(s)
        specs = missing
        if not specs:
            print("[e2e] no missing base images to prepare")
            return 0

    work_root = Path(args.work_dir).expanduser().resolve() if args.work_dir else work_dir()

    # P0.1 Fix: Don't create SSH keys in dry-run
    private_key: Path | None = None
    public_key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIDRYRUN wbeam-e2e-dry-run"

    if not dry_run:
        private_key = ensure_ssh_key(ssh_key_path())
        public_key = read_public_key(private_key)
    else:
        private_key = Path("<dry-run-ssh-key>")

    defaults = matrix["defaults"]
    distros = distros_by_id(matrix)

    for spec in specs:
        distro = distros[spec["distro"]]
        build_dir = run_dir_for_base(spec, work_root)
        install_disk = build_dir / "install.qcow2"
        seed_iso = build_dir / "seed.iso"
        boot_dir = build_dir / "boot"
        base_image = base_image_path(spec["distro"], spec["session"], base_root)
        manifest_path = base_manifest_path(spec["distro"], spec["session"], base_root)

        if base_image.exists() and not args.force:
            print(f"[e2e][ERROR] base image already exists: {base_image}")
            continue

        if dry_run:
            iso_value = os.environ.get(distro["iso_env"], "").strip()
            payload = {
                "distro": distro["id"],
                "session": spec["session"],
                "iso": iso_value or "<unset>",
                "build_dir": str(build_dir),
                "install_disk": str(install_disk),
                "seed_iso": str(seed_iso),
                "base_image": str(base_image),
            }
            print(json.dumps(payload, indent=2, sort_keys=True))
            continue

        if live:
            print(f"[e2e] prepare-base {distro['id']} {spec['session']}")
            print(f"[e2e] work dir: {build_dir}")
            print(f"[e2e] serial log: {build_dir / 'install-boot' / 'serial.log'}")
            print(f"[e2e] qemu log: {build_dir / 'install-boot' / 'qemu.log'}")

        if build_dir.exists() and args.force:
            shutil.rmtree(build_dir)
        build_dir.mkdir(parents=True, exist_ok=True)

        iso_path = require_iso_path(distro, dry_run=dry_run)
        ssh_port = alloc_ssh_port(f"base-{distro['id']}-{spec['session']}")

        started_at = time.time()

        qemu_img_create(install_disk, coerce_int(defaults.get("disk_gib"), default=48), log=build_dir / "qemu-img.log")
        create_seed_iso(
            distro=distro,
            session=spec["session"],
            ssh_user=distro["ssh_user"],
            public_key=public_key,
            output=seed_iso,
        )
        boot_assets = extract_boot_assets(
            iso=iso_path,
            distro=distro,
            output_dir=boot_dir,
            seed_dir=seed_iso.parent / "seed",
        )

        installer_run_dir = build_dir / "install-boot"
        installer_spec = QemuSpec(
            name=f"wbeam-base-{distro['id']}-{spec['session']}",
            disk=install_disk,
            ssh_port=ssh_port,
            run_dir=installer_run_dir,
            cpu=coerce_int(defaults.get("cpu"), default=4),
            memory_mib=coerce_int(defaults.get("memory_mib"), default=8192),
            iso=iso_path,
            seed_iso=None if distro["family"] == "debian" else seed_iso,
            display=default_qemu_display(spec["session"], installer=True),
            kernel=Path(boot_assets["kernel"]),
            initrd=Path(boot_assets["initrd"]),
            append=boot_append_args(distro=distro, session=spec["session"]),
            extra_args=("-boot", "once=d"),
        )

        proc = start_qemu(installer_spec)
        try:
            install_timeout = coerce_int(distro.get("install_timeout_sec"), default=5400)
            serial_log = installer_run_dir / "serial.log"
            if live:
                print(f"[e2e] installer started on SSH port {ssh_port}; streaming serial output")
            if distro["family"] == "debian":
                wait_debian_installer_complete(proc, serial_log, install_timeout, name=f"installer-{distro['id']}")
            else:
                rc = tail_serial_log(proc, serial_log, install_timeout, name=f"installer-{distro['id']}")
                if rc != 0:
                    print(f"[e2e][ERROR] installer failed: {rc}")
                    continue
        finally:
            if proc.poll() is None:
                proc.terminate()
                proc.wait(timeout=30)

        # Move to final location
        base_image.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(install_disk), str(base_image))

        manifest = {
            "schema": 2,
            "kind": "base",
            "distro": distro["id"],
            "session": spec["session"],
            "created_at": dt.datetime.now(dt.UTC).isoformat(),
            "duration_sec": round(time.time() - started_at, 2),
            "source_iso": str(iso_path),
            "target_image": str(base_image),
        }
        write_manifest(manifest_path, manifest)
        print(f"[e2e] base image ready: {base_image}")

    return 0


def cmd_prepare_installed(args: argparse.Namespace) -> int:
    matrix = load_matrix()
    base_root = Path(args.base_dir).expanduser().resolve() if args.base_dir else base_dir()
    work_root = Path(args.work_dir).expanduser().resolve() if args.work_dir else work_dir()
    live = getattr(args, "live", False)
    missing_only = getattr(args, "missing", False)
    install_backend = getattr(args, "install_backend", None)

    spec = {"distro": args.distro, "session": args.session or "gnome-wayland"}
    install_backend = install_backend or install_backend_for_session(spec["session"])
    base_image = base_image_path(spec["distro"], spec["session"], base_root)
    inst_image = installed_image_path(spec["distro"], spec["session"], base_root)
    build_dir = work_root / "installed" / spec["distro"] / spec["session"]
    guest_report_local = build_dir / "guest-report"
    guest_report_remote = "/home/wbeam/WBeam/e2e/work/prepare-installed-report"
    failure_path = build_dir / "prepare-installed-failure.json"
    guest_summary: dict = {}
    prep_rc: int | None = None
    proc: subprocess.Popen[str] | None = None
    private_key: Path | None = None
    distro = None
    ssh_port: int | None = None

    if missing_only and inst_image.exists() and not args.force:
        print(f"[e2e] installed image already exists: {inst_image}")
        return 0

    if not base_image.exists():
        if args.dry_run:
            print(f"[e2e] DRY-RUN: base image {base_image} is missing, would fail here.")
        else:
            print(f"[e2e][ERROR] missing base image: {base_image}")
            return 1

    if args.dry_run:
        print(f"[e2e] DRY-RUN: would prepare installed image for {args.distro}/{spec['session']} backend={install_backend}")
        return 0

    try:
        if build_dir.exists() and args.force:
            shutil.rmtree(build_dir)
        build_dir.mkdir(parents=True, exist_ok=True)

        work_disk = build_dir / "work.qcow2"
        if work_disk.exists():
            safe_remove(work_disk)
        qemu_img_overlay(base_image, work_disk)

        private_key = ensure_ssh_key(ssh_key_path())
        distros = distros_by_id(matrix)
        distro = distros[spec["distro"]]
        ssh_port = alloc_ssh_port(f"inst-{distro['id']}")

        q_spec = QemuSpec(
            name=f"wbeam-install-{distro['id']}",
            disk=work_disk,
            ssh_port=ssh_port,
            run_dir=build_dir,
            cpu=coerce_int(matrix["defaults"].get("cpu"), default=4),
            memory_mib=coerce_int(matrix["defaults"].get("memory_mib"), default=8192),
        )

        proc = start_qemu(q_spec)
        if live:
            print(f"[e2e] prepare-installed {distro['id']} {spec['session']} backend={install_backend}")
            print(f"[e2e] work dir: {build_dir}")
            print(f"[e2e] qemu log: {build_dir / 'qemu.log'}")
            print(f"[e2e] waiting for SSH on localhost:{ssh_port}")

        try:
            wait_for_ssh(distro["ssh_user"], ssh_port, private_key, 600)
            rsync_to_guest(distro["ssh_user"], ssh_port, private_key, ROOT, "/home/wbeam/WBeam", live=live)
            guest_report_local.mkdir(parents=True, exist_ok=True)
            guest_prepare_cmd = (
                f"cd {shlex.quote('/home/wbeam/WBeam')} && "
                f"WBEAM_E2E_INSTALL_BACKEND={shlex.quote(install_backend)} "
                f"WBEAM_E2E_REPORT_DIR={shlex.quote(guest_report_remote)} "
                f"./e2e/scripts/guest-prepare-installed.sh {shlex.quote(install_backend)}"
            )
            if live:
                print(f"[e2e] running in guest: {guest_prepare_cmd}")
            prep_proc = ssh(
                distro["ssh_user"],
                ssh_port,
                private_key,
                guest_prepare_cmd,
                log=build_dir / "guest-prepare-installed.log",
                check=False,
                live=live,
            )
            prep_rc = prep_proc.returncode
            rsync_result = rsync_from_guest(
                distro["ssh_user"],
                ssh_port,
                private_key,
                f"{guest_report_remote}/",
                guest_report_local,
                check=False,
                live=live,
            )
            if rsync_result.returncode != 0:
                raise RuntimeError(f"guest report sync failed rc={rsync_result.returncode}")
            required_guest_artifacts = [
                guest_report_local / "install-wbeam.exit-code",
                guest_report_local / "install-wbeam.stdout.log",
                guest_report_local / "install-wbeam.stderr.log",
                guest_report_local / "summary.json",
            ]
            for required in required_guest_artifacts:
                if not required.exists():
                    raise RuntimeError(f"missing guest prepare artifact: {required}")
            guest_summary = read_json(guest_report_local / "summary.json")
            if guest_summary.get("ok") is not True:
                raise RuntimeError(f"guest prepare summary is not ok: {guest_report_local / 'summary.json'}")
            if prep_rc != 0:
                raise RuntimeError(f"guest prepare-installed failed rc={prep_rc}")
        except Exception as exc:
            write_manifest(
                failure_path,
                {
                    "schema": 1,
                    "status": "fail",
                    "phase": "guest_prepare_installed",
                    "distro": spec["distro"],
                    "session": spec["session"],
                    "install_backend": install_backend,
                    "exit_code": prep_rc if prep_rc is not None else -1,
                    "base_image": str(base_image),
                    "work_disk": str(work_disk),
                    "guest_report": str(guest_report_local),
                    "guest_summary": str(guest_report_local / "summary.json"),
                    "error": str(exc),
                    "next_action": f"Inspect {build_dir / 'guest-prepare-installed.log'}, {guest_report_local}, and {failure_path}",
                },
            )
            raise
        finally:
            try:
                if distro is not None and ssh_port is not None and private_key is not None:
                    shutdown_guest(distro["ssh_user"], ssh_port, private_key, live=live)
                if proc is not None:
                    wait_process(proc, 300, name="shutdown")
            finally:
                if proc is not None and proc.poll() is None:
                    proc.terminate()
                    proc.wait()

        inst_image.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(work_disk), str(inst_image))
        installed_manifest = installed_manifest_path(spec["distro"], spec["session"], base_root)
        write_manifest(
            installed_manifest,
            {
                "schema": 2,
                "kind": "installed",
                "distro": spec["distro"],
                "session": spec["session"],
                "install_backend": install_backend,
                "created_at": dt.datetime.now(dt.UTC).isoformat(),
                "base_image": str(base_image),
                "target_image": str(inst_image),
                "work_disk": str(work_disk),
                "guest_report": str(guest_report_local),
                "guest_summary": str(guest_report_local / "summary.json"),
                "server_path": guest_summary.get("server_path", ""),
                "streamer_path": guest_summary.get("streamer_path", ""),
            },
        )
        print(f"[e2e] installed snapshot ready: {inst_image}")
        return 0
    except Exception as exc:
        if not failure_path.exists():
            write_manifest(
                failure_path,
                {
                    "schema": 1,
                    "status": "fail",
                    "phase": "prepare_installed",
                    "distro": spec["distro"],
                    "session": spec["session"],
                    "install_backend": install_backend,
                    "base_image": str(base_image),
                    "work_disk": str(build_dir / "work.qcow2"),
                    "guest_report": str(guest_report_local),
                    "guest_summary": str(guest_report_local / "summary.json"),
                    "error": str(exc),
                    "next_action": f"Inspect {build_dir} and rerun with --live.",
                },
            )
        print(f"[e2e][ERROR] prepare-installed failed: {exc}")
        return 1


def cmd_diagnose_installed(args: argparse.Namespace) -> int:
    root = Path(args.work_dir).expanduser().resolve() if args.work_dir else work_dir()
    path = root / "installed" / args.distro / (args.session or "gnome-wayland")
    payload = {
        "work_dir": str(path),
        "exists": path.exists(),
        "work_disk": str(path / "work.qcow2"),
        "work_disk_exists": (path / "work.qcow2").exists(),
        "guest_log": str(path / "guest-prepare-installed.log"),
        "guest_log_exists": (path / "guest-prepare-installed.log").exists(),
        "guest_report": str(path / "guest-report"),
        "guest_report_exists": (path / "guest-report").exists(),
        "failure_json": str(path / "prepare-installed-failure.json"),
        "failure_json_exists": (path / "prepare-installed-failure.json").exists(),
    }
    print(json.dumps(payload, indent=2, sort_keys=True))
    return 0


def cmd_diagnose_run(args: argparse.Namespace) -> int:
    report_root = Path(getattr(args, "report_dir", None) or report_dir()).expanduser().resolve()
    work_root = Path(getattr(args, "work_dir", None) or work_dir()).expanduser().resolve()
    scenario_report = report_root / args.run_id / "scenarios" / args.scenario
    scenario_work = work_root / "runs" / args.run_id / args.scenario
    payload = {
        "run_id": args.run_id,
        "scenario": args.scenario,
        "scenario_report": str(scenario_report),
        "scenario_report_exists": scenario_report.exists(),
        "scenario_work": str(scenario_work),
        "scenario_work_exists": scenario_work.exists(),
        "l2_workdisk": str(scenario_work / "disk.qcow2"),
        "l2_workdisk_exists": (scenario_work / "disk.qcow2").exists(),
        "guest_wizard_log": str(scenario_report / "logs" / "guest-wizard.log"),
        "guest_wizard_log_exists": (scenario_report / "logs" / "guest-wizard.log").exists(),
        "qemu_log": str(scenario_report / "logs" / "qemu.log"),
        "qemu_log_exists": (scenario_report / "logs" / "qemu.log").exists(),
        "wizard_summary": str(scenario_report / "guest" / "wizard" / "summary.json"),
        "wizard_summary_exists": (scenario_report / "guest" / "wizard" / "summary.json").exists(),
        "wizard_steps": str(scenario_report / "guest" / "wizard" / "steps.jsonl"),
        "wizard_steps_exists": (scenario_report / "guest" / "wizard" / "steps.jsonl").exists(),
        "stream_dirs": sorted(str(p) for p in (scenario_report / "guest" / "wizard" / "stream").glob("*")) if (scenario_report / "guest" / "wizard" / "stream").exists() else [],
    }
    print(json.dumps(payload, indent=2, sort_keys=True))
    return 0


def _latest_run_id(report_root: Path) -> str:
    runs = sorted((p for p in report_root.iterdir() if p.is_dir()), key=lambda p: p.name, reverse=True) if report_root.exists() else []
    return runs[0].name if runs else ""


def cmd_portal_diagnose(args: argparse.Namespace) -> int:
    report_root = Path(getattr(args, "report_dir", None) or report_dir()).expanduser().resolve()
    run_id = getattr(args, "run_id", None) or _latest_run_id(report_root)
    scenario_id = getattr(args, "scenario", None) or "fedora43-gnome-wayland-portal-h264"
    if not run_id:
        print(json.dumps({"schema": 1, "status": "missing", "reason_code": "no_runs", "next_action": "Run a scenario first."}, indent=2, sort_keys=True))
        return 0
    matrix = load_matrix()
    scenario = next((item for item in matrix.get("scenarios", []) if item.get("id") == scenario_id), {})
    stream_dir = report_root / run_id / "scenarios" / scenario_id / "guest" / "wizard" / "stream" / "wayland_portal"
    result = portal_consent.classify_guest_portal_report(stream_dir, scenario)
    base_root = Path(getattr(args, "base_dir", None)).expanduser().resolve() if getattr(args, "base_dir", None) else base_dir()
    distro = scenario.get("distro", "fedora-43")
    session = scenario.get("session", "gnome-wayland")
    installed = installed_image_path(distro, session, base_root)
    consented = portal_consented_image_path(distro, session, base_root)
    payload = {
        "schema": 1,
        "run_id": run_id,
        "scenario": scenario_id,
        "stream_dir": str(stream_dir),
        "installed_image": str(installed),
        "installed_exists": installed.exists(),
        "portal_consented_image": str(consented),
        "portal_consented_exists": consented.exists(),
        **result,
    }
    print(json.dumps(payload, indent=2, sort_keys=True))
    return 0


def cmd_diagnose_portal_consent(args: argparse.Namespace) -> int:
    base_root = Path(args.base_dir).expanduser().resolve() if args.base_dir else base_dir()
    work_root = Path(args.work_dir).expanduser().resolve() if args.work_dir else work_dir()
    distro = args.distro
    session = args.session or "gnome-wayland"
    installed = installed_image_path(distro, session, base_root)
    consented = portal_consented_image_path(distro, session, base_root)
    consented_manifest = portal_consented_manifest_path(distro, session, base_root)
    valid, reason = portal_consented_image_is_valid(distro, session, base_root)
    matrix = load_matrix()
    scenario = next(
        (
            item
            for item in matrix.get("scenarios", [])
            if item.get("distro") == distro and item.get("session") == session and item.get("backend") == "wayland_portal"
        ),
        {},
    )
    run_next_action = (
        f"./e2e/run run --scenario {scenario['id']} --use-installed --live"
        if scenario
        else portal_consent_next_action(distro, session)
    )
    payload = {
        "schema": 1,
        "distro": distro,
        "session": session,
        "installed_image": str(installed),
        "installed_exists": installed.exists(),
        "portal_consented_image": str(consented),
        "portal_consented_exists": consented.exists(),
        "portal_consented_manifest": str(consented_manifest),
        "portal_consented_valid": valid,
        "invalid_reason": reason,
        "work_dir": str(work_root / "portal-consent" / distro / session),
        "last_failure": str((work_root / "portal-consent" / distro / session / "prepare-portal-consent-failure.json")),
        "next_action": run_next_action if valid else f"Run ./e2e/run diagnose-portal-consent --distro {distro} --session {session} then recreate with ./e2e/run prepare-portal-consent --distro {distro} --session {session} --backend wayland_portal --live --promote",
    }
    print(json.dumps(payload, indent=2, sort_keys=True))
    return 0


def run_guest_portal_consent_attempt(
    *,
    distro_info: dict,
    ssh_port: int,
    private_key: Path,
    guest_root: str,
    guest_report_remote: str,
    guest_report_local: Path,
    backend: str,
    display_mode: str,
    duration_sec: int,
    timeout_sec: int,
    attempt: int,
    live: bool,
    log_path: Path,
) -> tuple[int, dict]:
    attempt_remote = f"{guest_report_remote}/attempt-{attempt}"
    attempt_local = guest_report_local / f"attempt-{attempt}"
    attempt_local.mkdir(parents=True, exist_ok=True)
    command = (
        f"cd {shlex.quote(guest_root)} && "
        f"WBEAM_E2E_BACKEND={shlex.quote(backend)} "
        f"WBEAM_E2E_DISPLAY_MODE={shlex.quote(display_mode)} "
        f"WBEAM_E2E_DURATION_SEC={duration_sec} "
        f"WBEAM_E2E_PORTAL_APPROVAL_WAIT_SEC={timeout_sec} "
        f"WBEAM_E2E_PORTAL_CONSENT_TIMEOUT_SEC={timeout_sec} "
        f"WBEAM_E2E_REPORT_DIR={shlex.quote(attempt_remote)} "
        f"./e2e/scripts/guest-portal-consent.sh"
    )
    proc = ssh(
        distro_info["ssh_user"],
        ssh_port,
        private_key,
        command,
        log=log_path,
        check=False,
        live=live,
    )
    rsync_from_guest(
        distro_info["ssh_user"],
        ssh_port,
        private_key,
        f"{attempt_remote}/",
        attempt_local,
        check=False,
        live=live,
    )
    summary = read_json(attempt_local / "summary.json")
    return proc.returncode, summary


def cmd_prepare_portal_consent(args: argparse.Namespace) -> int:
    matrix = load_matrix()
    base_root = Path(args.base_dir).expanduser().resolve() if args.base_dir else base_dir()
    work_root = Path(args.work_dir).expanduser().resolve() if args.work_dir else work_dir()
    live = getattr(args, "live", False)
    dry_run = getattr(args, "dry_run", False)
    force = getattr(args, "force", False)
    promote = getattr(args, "promote", False)
    distro = args.distro
    session = args.session or "gnome-wayland"
    backend = getattr(args, "backend", None) or "wayland_portal"
    approval_timeout_sec = int(getattr(args, "approval_timeout_sec", 900) or 900)
    approval_poll_sec = int(getattr(args, "approval_poll_sec", 5) or 5)
    timeout_sec = int(getattr(args, "timeout_sec", 180) or 180)
    installed = installed_image_path(distro, session, base_root)
    consented = portal_consented_image_path(distro, session, base_root)
    consented_manifest = portal_consented_manifest_path(distro, session, base_root)
    portal_work_dir = work_root / "portal-consent" / distro / session
    work_disk = portal_work_dir / "work.qcow2"
    guest_report_local = portal_work_dir / "guest-report"
    guest_report_remote = "/home/wbeam/WBeam/e2e/work/portal-consent-report"
    failure_path = portal_work_dir / "prepare-portal-consent-failure.json"

    if consented.exists() and not force:
        valid, reason = portal_consented_image_is_valid(distro, session, base_root)
        if valid:
            payload = {
                "schema": 1,
                "status": "exists",
                "portal_consented_image": str(consented),
                "portal_consented_manifest": str(consented_manifest),
            }
            print(json.dumps(payload, indent=2, sort_keys=True))
            return 0
        payload = {
            "schema": 1,
            "status": "blocked",
            "phase": "portal_consent",
            "reason_code": "invalid_portal_consented_image",
            "reason": f"invalid portal-consented image: {reason}",
            "next_action": f"Run ./e2e/run diagnose-portal-consent --distro {distro} --session {session} then recreate with ./e2e/run prepare-portal-consent --distro {distro} --session {session} --backend wayland_portal --live --promote",
            "portal_consented_image": str(consented),
            "portal_consented_manifest": str(consented_manifest),
        }
        print(json.dumps(payload, indent=2, sort_keys=True))
        return 1

    if not installed.exists():
        payload = {
            "schema": 1,
            "status": "blocked",
            "phase": "prepare_installed",
            "reason_code": "missing_installed_image",
            "reason": f"missing installed image: {installed}",
            "next_action": f"Run ./e2e/run prepare-installed --distro {distro} --session {session} --live",
            "installed_image": str(installed),
        }
        print(json.dumps(payload, indent=2, sort_keys=True))
        return 1

    if dry_run:
        display_hint = getattr(args, "display", "auto") or "auto"
        payload = {
            "schema": 1,
            "status": "dry-run",
            "portal_consented_image": str(consented),
            "portal_consented_manifest": str(consented_manifest),
            "installed_image": str(installed),
            "work_disk": str(work_disk),
            "display": display_hint,
            "backend": backend,
            "timeout_sec": timeout_sec,
            "approval_timeout_sec": approval_timeout_sec,
            "approval_poll_sec": approval_poll_sec,
        }
        print(json.dumps(payload, indent=2, sort_keys=True))
        return 0

    if portal_work_dir.exists() and force:
        shutil.rmtree(portal_work_dir)
    portal_work_dir.mkdir(parents=True, exist_ok=True)
    guest_report_local.mkdir(parents=True, exist_ok=True)
    if work_disk.exists():
        safe_remove(work_disk)
    qemu_img_overlay(installed, work_disk)

    display, extra_display_args, display_hint = resolve_portal_consent_display(args, live=live)
    private_key = ensure_ssh_key(ssh_key_path())
    ssh_port = alloc_ssh_port(f"portal-consent-{distro}-{session}")
    distros = distros_by_id(matrix)
    distro_info = distros[distro]
    q_spec = QemuSpec(
        name=f"wbeam-portal-consent-{distro}-{session}",
        disk=work_disk,
        ssh_port=ssh_port,
        run_dir=portal_work_dir,
        cpu=coerce_int(matrix["defaults"].get("cpu"), default=4),
        memory_mib=coerce_int(matrix["defaults"].get("memory_mib"), default=8192),
        display=display,
        extra_args=extra_display_args,
    )
    proc = start_qemu(q_spec)
    guest_summary: dict = {}
    guest_rc = -1
    keep_vm_on_timeout = bool(getattr(args, "keep_vm_on_timeout", False))
    preserve_vm = False
    try:
        if live:
            print("[e2e] ================================================================")
            print("[e2e] GNOME ScreenCast portal approval required")
            print("[e2e] A VM window should be visible.")
            print("[e2e] When the prompt appears, approve WBeam / ScreenCast / Virtual Monitor.")
            print(f"[e2e] portal consent VM display: {display_hint}")
            print("[e2e] action: approve GNOME ScreenCast prompt in the VM window")
            print(f"[e2e] after approval this command should promote: {consented}")
            print("[e2e] Do not close the VM until this command reports PASS, BLOCKED, or times out.")
            print("[e2e] ================================================================")
            print(f"[e2e] work dir: {portal_work_dir}")
            print(f"[e2e] qemu log: {portal_work_dir / 'qemu.log'}")
            print(f"[e2e] waiting for SSH on localhost:{ssh_port}")
        wait_for_ssh(distro_info["ssh_user"], ssh_port, private_key, 600)
        rsync_to_guest(distro_info["ssh_user"], ssh_port, private_key, ROOT, "/home/wbeam/WBeam", live=live)
        write_portal_operator_artifacts(
            portal_work_dir,
            display_hint=display_hint,
            command=f"./e2e/scripts/guest-portal-consent.sh",
            consented=consented,
            manifest=consented_manifest,
            timeout_sec=approval_timeout_sec,
        )
        started = time.time()
        attempt = 0
        last_summary: dict = {}
        while time.time() - started < approval_timeout_sec:
            attempt += 1
            if live:
                print(f"[e2e] portal consent attempt {attempt} (poll {approval_poll_sec}s)")
            guest_rc, guest_summary = run_guest_portal_consent_attempt(
                distro_info=distro_info,
                ssh_port=ssh_port,
                private_key=private_key,
                guest_root="/home/wbeam/WBeam",
                guest_report_remote=guest_report_remote,
                guest_report_local=guest_report_local,
                backend=backend,
                display_mode="virtual_monitor",
                duration_sec=20,
                timeout_sec=approval_timeout_sec,
                attempt=attempt,
                live=live,
                log_path=portal_work_dir / "guest-portal-consent.log",
            )
            last_summary = guest_summary
            if guest_summary.get("ok") is True:
                break
            if guest_summary.get("reason_code") == "portal_consent_required":
                print("[e2e] waiting for GNOME ScreenCast approval; approve the prompt in the VM window")
                time.sleep(max(1, approval_poll_sec))
                continue
            break

        summary_path = guest_report_local / "summary.json"
        if not last_summary:
            last_summary = read_json(summary_path)
        if not last_summary:
            payload = {
                "schema": 1,
                "status": "fail",
                "phase": "portal_consent",
                "reason_code": "guest_report_missing",
                "reason": f"missing guest portal consent summary: {summary_path}",
                "next_action": f"Inspect {guest_report_local} and retry prepare-portal-consent.",
                "guest_report": str(guest_report_local),
                "guest_summary": str(summary_path),
                "guest_command": f"./e2e/scripts/guest-portal-consent.sh",
                "portal_consented_image": str(consented),
                "portal_consented_manifest": str(consented_manifest),
                "installed_image": str(installed),
                "work_disk": str(work_disk),
            }
            print(json.dumps(payload, indent=2, sort_keys=True))
            failure_path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")
            return 1

        payload = {
            "schema": 1,
            "status": last_summary.get("status", "blocked"),
            "reason_code": last_summary.get("reason_code", ""),
            "reason": last_summary.get("reason", ""),
            "next_action": last_summary.get("next_action", ""),
            "guest_report": str(guest_report_local),
            "guest_summary": str(summary_path),
            "guest_command": f"./e2e/scripts/guest-portal-consent.sh",
            "portal_consented_image": str(consented),
            "portal_consented_manifest": str(consented_manifest),
            "installed_image": str(installed),
            "work_disk": str(work_disk),
        }
        if last_summary.get("ok") is True:
            if promote:
                shutdown_guest(distro_info["ssh_user"], ssh_port, private_key, live=live)
                wait_process(proc, 300, name="shutdown")
                consented_tmp = consented.with_suffix(".qcow2.tmp")
                manifest_tmp = consented_manifest.with_suffix(".json.tmp")
                for temp_path in (consented_tmp, manifest_tmp):
                    if temp_path.exists():
                        safe_remove(temp_path)
                if consented.exists():
                    safe_remove(consented)
                if consented_manifest.exists():
                    safe_remove(consented_manifest)
                shutil.copy2(work_disk, consented_tmp)
                qemu_img = require_tool("qemu-img")
                subprocess.run([qemu_img, "info", str(consented_tmp)], capture_output=True, text=True, check=True)
                consented_tmp.rename(consented)
                write_manifest(
                    manifest_tmp,
                    {
                        "schema": 2,
                        "kind": "portal_consented",
                        "distro": distro,
                        "session": session,
                        "backend": backend,
                        "display_mode": "virtual_monitor",
                        "created_at": dt.datetime.now(dt.UTC).isoformat(),
                        "source_installed_image": str(installed),
                        "portal_consented_image": str(consented),
                        "source_work_disk": str(work_disk),
                        "guest_report": str(guest_report_local),
                        "guest_summary": str(summary_path),
                        "approval_mode": "manual-portal-prompt",
                        "stream_smoke_ok": True,
                        "validation": {
                            "client_connected": True,
                            "bytes_read_gt_zero": True,
                            "daemon_streaming_seen": True,
                        },
                    },
                )
                manifest_tmp.rename(consented_manifest)
                if work_disk.exists():
                    safe_remove(work_disk)
                payload["status"] = "promoted"
                payload["portal_consented_image"] = str(consented)
                payload["portal_consented_manifest"] = str(consented_manifest)
                print(json.dumps(payload, indent=2, sort_keys=True))
                return 0
            payload["status"] = "ok_pending_promote"
            payload["next_action"] = "Rerun the same command with --promote before deleting the work overlay."
            print(json.dumps(payload, indent=2, sort_keys=True))
            return 2

        if last_summary.get("reason_code") == "portal_consent_required" or (
            last_summary.get("blocked") is True and last_summary.get("phase") == "portal_consent"
        ):
            payload["status"] = "blocked"
            payload["phase"] = "portal_consent"
            payload["reason_code"] = "portal_consent_required"
            payload["reason"] = "approval timeout; prompt not approved"
            payload["next_action"] = "Rerun prepare-portal-consent with --live --promote and approve the prompt."
            print(json.dumps(payload, indent=2, sort_keys=True))
            failure_path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")
            if keep_vm_on_timeout:
                preserve_vm = True
                print(f"[e2e] keeping VM alive on timeout; work dir: {portal_work_dir}")
                print(f"[e2e] ssh port: {ssh_port}")
                print(f"[e2e] display hint: {display_hint}")
                print(f"[e2e] qemu pid: {proc.pid}")
                print(f"[e2e] guest summary: {summary_path}")
            return 20

        payload["status"] = "fail"
        payload["phase"] = "portal_consent"
        payload["reason_code"] = last_summary.get("reason_code", "portal_consent_failed")
        payload["reason"] = last_summary.get("reason", "portal consent preparation failed")
        payload["next_action"] = last_summary.get("next_action") or "Inspect the guest portal-consent report and daemon logs."
        print(json.dumps(payload, indent=2, sort_keys=True))
        failure_path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")
        return 1
    except Exception as exc:  # noqa: BLE001
        payload = {
            "schema": 1,
            "status": "fail",
            "phase": "portal_consent",
            "reason_code": "portal_consent_timeout",
            "reason": str(exc),
            "next_action": f"Inspect {portal_work_dir / 'guest-portal-consent.log'} and {guest_report_local}.",
            "guest_report": str(guest_report_local),
            "guest_command": "./e2e/scripts/guest-portal-consent.sh",
            "portal_consented_image": str(consented),
            "portal_consented_manifest": str(consented_manifest),
            "installed_image": str(installed),
            "work_disk": str(work_disk),
        }
        print(json.dumps(payload, indent=2, sort_keys=True))
        failure_path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")
        return 1
    finally:
        if not preserve_vm:
            try:
                shutdown_guest(distro_info["ssh_user"], ssh_port, private_key, live=live)
                wait_process(proc, 300, name="shutdown")
            except Exception:
                pass
            if proc.poll() is None:
                proc.terminate()
                proc.wait()


def cmd_run(args: argparse.Namespace) -> int:
    matrix = load_matrix()
    scenarios = select_scenarios(matrix, args)
    if not scenarios and getattr(args, "scenario", None):
        scenario = next((s for s in matrix["scenarios"] if s["id"] in (args.scenario or [])), None)
        scenarios = [scenario] if scenario else []
    if not scenarios:
        print("[e2e][ERROR] no scenarios selected")
        return 1

    base_root = Path(args.base_dir).expanduser().resolve() if args.base_dir else base_dir()
    work_root = Path(args.work_dir).expanduser().resolve() if args.work_dir else work_dir()
    live = getattr(args, "live", False)
    retain_workdisk = getattr(args, "retain_workdisk", "on-fail")
    allow_unconsented_portal = getattr(args, "allow_unconsented_portal", False)

    if args.dry_run:
        report_root = Path(getattr(args, "report_dir", None) or report_dir()).expanduser().resolve()
        run_id = getattr(args, "run_id", None) or utc_timestamp()
        run_path = runner_report_path(report_root, run_id)
        init_run_report(report_root, run_id, scenarios, host=collect_host_info())
        results = []
        for scenario in scenarios:
            backing, backing_kind = backing_image_for_scenario(scenario, base_root)
            disk = scenario_workdisk_path(work_root, run_id, scenario["id"])
            portal_image = portal_consented_image_path(scenario["distro"], scenario["session"], base_root)
            portal_valid, portal_reason = portal_consented_image_is_valid(scenario["distro"], scenario["session"], base_root)
            if scenario_requires_portal_consent(scenario) and not portal_image.exists() and not allow_unconsented_portal:
                results.append(
                    {
                        "scenario": scenario["id"],
                        "status": "blocked",
                        "phase": "portal_consent",
                        "reason_code": "missing_portal_consented_image",
                        "reason": "missing portal-consented image for Wayland Portal scenario",
                        "next_action": portal_consent_next_action(scenario["distro"], scenario["session"]),
                        "run_id": run_id,
                        "report_dir": str(scenario_report_path_from_run_dir(run_path, scenario["id"])),
                        "runner_report_dir": str(run_path),
                        "l1_backing_image": str(installed_image_path(scenario["distro"], scenario["session"], base_root)),
                        "l1_backing_kind": "installed",
                        "portal_consented_image": str(portal_image),
                        "l2_workdisk": str(disk),
                        "allow_unconsented_portal": allow_unconsented_portal,
                        "wizard_summary": "",
                        "wizard_steps": "",
                        "stream_summary": "",
                        "stream_reason_code": "",
                        "stream_blocked": True,
                        "duration_sec": 0,
                    }
                )
                continue
            if scenario_requires_portal_consent(scenario) and portal_image.exists() and not portal_valid and not allow_unconsented_portal:
                results.append(
                    {
                        "scenario": scenario["id"],
                        "status": "blocked",
                        "phase": "portal_consent",
                        "reason_code": "invalid_portal_consented_image",
                        "reason": f"invalid portal-consented image: {portal_reason}",
                        "next_action": f"Run ./e2e/run diagnose-portal-consent --distro {scenario['distro']} --session {scenario['session']} then recreate with ./e2e/run prepare-portal-consent --distro {scenario['distro']} --session {scenario['session']} --backend wayland_portal --live --promote",
                        "run_id": run_id,
                        "report_dir": str(scenario_report_path_from_run_dir(run_path, scenario["id"])),
                        "runner_report_dir": str(run_path),
                        "l1_backing_image": str(installed_image_path(scenario["distro"], scenario["session"], base_root)),
                        "l1_backing_kind": "installed",
                        "portal_consented_image": str(portal_image),
                        "l2_workdisk": str(disk),
                        "allow_unconsented_portal": allow_unconsented_portal,
                        "wizard_summary": "",
                        "wizard_steps": "",
                        "stream_summary": "",
                        "stream_reason_code": "",
                        "stream_blocked": True,
                        "duration_sec": 0,
                    }
                )
                continue
            results.append(
                {
                    "scenario": scenario["id"],
                    "status": "pass",
                    "phase": "dry-run",
                    "reason": "",
                    "reason_code": "",
                    "run_id": run_id,
                    "report_dir": str(scenario_report_path_from_run_dir(run_path, scenario["id"])),
                    "runner_report_dir": str(run_path),
                    "l1_backing_image": str(backing),
                    "l1_backing_kind": backing_kind,
                    "portal_consented_image": str(portal_image),
                    "l2_workdisk": str(disk),
                    "allow_unconsented_portal": allow_unconsented_portal,
                    "wizard_summary": "",
                    "wizard_steps": "",
                    "stream_summary": "",
                    "stream_reason_code": "",
                    "stream_blocked": False,
                    "duration_sec": 0,
                }
            )
        finalize_run_report(run_path, run_id, results)
        print(f"[e2e] DRY-RUN: wrote report at {run_path}")
        return 0

    run_id = getattr(args, "run_id", None) or utc_timestamp()
    report_root = Path(getattr(args, "report_dir", None) or report_dir()).expanduser().resolve()
    run_dir = runner_report_path(report_root, run_id)
    init_run_report(report_root, run_id, scenarios, host=collect_host_info())
    scenario_results: list[dict] = []

    for scenario in scenarios:
        scenario_report_path = scenario_report_path_from_run_dir(run_dir, scenario["id"])
        backing, backing_kind = backing_image_for_scenario(scenario, base_root)
        portal_image = portal_consented_image_path(scenario["distro"], scenario["session"], base_root)
        portal_valid, portal_reason = portal_consented_image_is_valid(scenario["distro"], scenario["session"], base_root)
        if scenario_requires_portal_consent(scenario) and not portal_image.exists() and not allow_unconsented_portal:
            scenario_results.append(
                {
                    "scenario": scenario["id"],
                    "status": "blocked",
                    "phase": "portal_consent",
                    "reason_code": "missing_portal_consented_image",
                    "reason": "missing portal-consented image for Wayland Portal scenario",
                    "next_action": portal_consent_next_action(scenario["distro"], scenario["session"]),
                    "report_dir": str(scenario_report_path),
                    "run_id": run_id,
                    "l1_backing_image": str(installed_image_path(scenario["distro"], scenario["session"], base_root)),
                    "l1_backing_kind": "installed",
                    "portal_consented_image": str(portal_image),
                    "runner_report_dir": str(run_dir),
                    "allow_unconsented_portal": allow_unconsented_portal,
                    "duration_sec": 0,
                }
            )
            continue
        if scenario_requires_portal_consent(scenario) and portal_image.exists() and not portal_valid and not allow_unconsented_portal:
            scenario_results.append(
                {
                    "scenario": scenario["id"],
                    "status": "blocked",
                    "phase": "portal_consent",
                    "reason_code": "invalid_portal_consented_image",
                    "reason": f"invalid portal-consented image: {portal_reason}",
                    "next_action": f"Run ./e2e/run diagnose-portal-consent --distro {scenario['distro']} --session {scenario['session']} then recreate with ./e2e/run prepare-portal-consent --distro {scenario['distro']} --session {scenario['session']} --backend wayland_portal --live --promote",
                    "report_dir": str(scenario_report_path),
                    "run_id": run_id,
                    "l1_backing_image": str(installed_image_path(scenario["distro"], scenario["session"], base_root)),
                        "l1_backing_kind": "installed",
                    "portal_consented_image": str(portal_image),
                    "runner_report_dir": str(run_dir),
                    "allow_unconsented_portal": allow_unconsented_portal,
                    "duration_sec": 0,
                }
            )
            continue
        if not backing.exists():
            scenario_results.append(
                {
                    "scenario": scenario["id"],
                    "status": "fail",
                    "phase": "boot",
                    "reason": f"missing backing image: {backing}",
                    "reason_code": "missing_backing_image",
                    "report_dir": str(scenario_report_path),
                    "run_id": run_id,
                    "l1_backing_image": str(backing),
                    "l1_backing_kind": backing_kind,
                    "portal_consented_image": str(portal_image),
                    "runner_report_dir": str(run_dir),
                    "next_action": f"Run ./e2e/run prepare-installed --distro {scenario['distro']} --session {scenario['session']} --live",
                    "allow_unconsented_portal": allow_unconsented_portal,
                    "duration_sec": 0,
                }
            )
            continue

        scenario_run_dir = work_root / "runs" / run_id / scenario["id"]
        scenario_run_dir.mkdir(parents=True, exist_ok=True)
        guest_root = "/home/wbeam/WBeam"
        guest_report_root = scenario_run_dir / "guest"
        guest_report_root.mkdir(parents=True, exist_ok=True)
        guest_report_root_remote = f"{guest_root}/e2e/work/runs/{run_id}/{scenario['id']}/guest"
        distros = distros_by_id(matrix)
        distro = distros[scenario["distro"]]
        ssh_port = alloc_ssh_port(f"run-{run_id}-{scenario['id']}")
        android_serial = None
        if scenario_requires_host_android(scenario):
            android_serial, adb_status, adb_reason = select_android_serial(resolve_android_serial(args, scenario))
            if adb_status != "ok" or not android_serial:
                reason_code = android_preflight_reason_code(adb_reason)
                scenario_results.append(
                    {
                        "scenario": scenario["id"],
                        "status": "blocked",
                        "phase": "android-preflight",
                        "reason_code": reason_code,
                        "reason": adb_reason,
                        "report_dir": str(scenario_report_path),
                        "next_action": "Connect/unlock the phone, accept USB debugging, or rerun with --android-serial.",
                        "wizard_summary": "",
                        "wizard_steps": "",
                        "stream_summary": "",
                        "stream_reason_code": "",
                        "stream_blocked": False,
                        "duration_sec": 0,
                    }
                )
                continue
        host_forwards: tuple[tuple[int, int], ...] = ()
        host_control_port: int | None = None
        host_stream_port: int | None = None
        if scenario.get("android_execution") == "host":
            host_control_port = alloc_ssh_port(f"ctrl-{run_id}-{scenario['id']}", start=25000)
            host_stream_port = alloc_ssh_port(f"stream-{run_id}-{scenario['id']}", start=27000)
            host_forwards = (
                (host_control_port, matrix_control_port(matrix=matrix, scenario=scenario)),
                (host_stream_port, matrix_stream_port(matrix=matrix, scenario=scenario)),
            )
        private_key = ensure_ssh_key(ssh_key_path())
        disk = scenario_workdisk_path(work_root, run_id, scenario["id"])
        qemu_img_overlay(backing, disk)
        q_spec = QemuSpec(
            name=f"wbeam-run-{run_id}-{scenario['id']}",
            disk=disk,
            ssh_port=ssh_port,
            run_dir=scenario_run_dir,
            cpu=coerce_int(matrix["defaults"].get("cpu"), default=4),
            memory_mib=coerce_int(matrix["defaults"].get("memory_mib"), default=8192),
            host_forwards=host_forwards,
        )
        started = time.time()
        proc = start_qemu(q_spec)
        result = {
            "scenario": scenario["id"],
            "status": "fail",
            "phase": "wizard",
            "reason": "unknown",
            "reason_code": "",
            "report_dir": str(scenario_report_path),
            "run_id": run_id,
            "l1_backing_image": str(backing),
                    "l1_backing_kind": backing_kind,
            "portal_consented_image": str(portal_image),
            "l2_workdisk": str(disk),
            "runner_report_dir": str(run_dir),
            "guest_command": "",
            "allow_unconsented_portal": allow_unconsented_portal,
            "wizard_summary": "",
            "wizard_steps": "",
            "stream_summary": "",
            "stream_reason_code": "",
            "stream_blocked": False,
            "duration_sec": 0,
            "workdisk_policy": retain_workdisk,
            "workdisk_retained": None,
        }
        try:
            if live:
                print(f"[e2e] scenario {scenario['id']}")
                print(f"[e2e] work dir: {scenario_run_dir}")
                print(f"[e2e] qemu log: {scenario_run_dir / 'qemu.log'}")
                print(f"[e2e] guest wizard log: {scenario_run_dir / 'guest-wizard.log'}")
                if host_control_port and host_stream_port:
                    print(f"[e2e] host ctrl: 127.0.0.1:{host_control_port} -> guest:{matrix_control_port(matrix=matrix, scenario=scenario)}")
                    print(f"[e2e] host stream: 127.0.0.1:{host_stream_port} -> guest:{matrix_stream_port(matrix=matrix, scenario=scenario)}")
                print(f"[e2e] waiting for SSH on localhost:{ssh_port}")
            wait_for_ssh(distro["ssh_user"], ssh_port, private_key, 600)
            rsync_to_guest(distro["ssh_user"], ssh_port, private_key, ROOT, guest_root, live=live)
            ssh(distro["ssh_user"], ssh_port, private_key, f"mkdir -p {shlex.quote(guest_report_root_remote)}", live=live)
            wizard_flags = guest_wizard_flags_for_scenario(scenario)
            wizard_cmd, wizard_env = build_guest_wizard_command(
                guest_root=guest_root,
                scenario=scenario,
                guest_report_root=guest_report_root_remote,
                duration=scenario_duration(matrix, scenario),
                flags=wizard_flags,
            )
            command = f"cd {shlex.quote(guest_root)} && {wizard_env} {wizard_cmd}"
            result["guest_command"] = command
            if live:
                print(f"[e2e] running in guest: {command}")
            guest_proc = ssh(
                distro["ssh_user"],
                ssh_port,
                private_key,
                command,
                log=scenario_run_dir / "guest-wizard.log",
                check=False,
                live=live,
            )
            guest_rc = guest_proc.returncode
            rsync_from_guest(distro["ssh_user"], ssh_port, private_key, f"{guest_report_root_remote}/", guest_report_root, live=live)
            publish_scenario_artifacts(
                scenario_run_dir=scenario_run_dir,
                scenario_report_dir=scenario_report_path,
                guest_report_root=guest_report_root,
            )
            summary_path = scenario_report_path / "guest" / "wizard" / "summary.json"
            steps_path = scenario_report_path / "guest" / "wizard" / "steps.jsonl"
            stream_summary_path = scenario_report_path / "guest" / "wizard" / "stream" / scenario["backend"] / "summary.json"
            if not summary_path.exists() or not steps_path.exists():
                if guest_rc != 0:
                    result["status"] = "fail"
                    result["phase"] = "wizard"
                    result["reason"] = f"guest wizard exited with rc={guest_rc}"
                    result["next_action"] = "Open the scenario report dir and inspect logs/guest-wizard.log and logs/qemu.log."
                    result["guest_exit_code"] = guest_rc
                    raise RuntimeError("missing wizard summary or steps report")
                raise RuntimeError("missing wizard summary or steps report")
            wizard_summary = read_json(summary_path)
            stream_summary = read_json(stream_summary_path)
            status, phase, reason, next_action = normalize_wizard_result(wizard_summary, guest_rc)
            result["status"] = status
            result["phase"] = phase
            result["reason"] = reason
            result["next_action"] = next_action
            result["reason_code"] = str(wizard_summary.get("reason_code") or stream_summary.get("reason_code") or "")
            result["stream_reason_code"] = str(stream_summary.get("reason_code") or "")
            result["stream_blocked"] = bool(stream_summary.get("blocked")) or status == "blocked"
            if guest_rc != 0:
                result["guest_exit_code"] = guest_rc
            result["wizard_summary"] = str(summary_path)
            result["wizard_steps"] = str(steps_path)
            result["stream_summary"] = str(stream_summary_path)
            if result["status"] != "pass" and not result.get("next_action"):
                phase, reason, next_action = summarize_guest_wizard_failure(summary_path, steps_path, stream_summary_path)
                result["phase"] = phase
                result["reason"] = reason
                result["next_action"] = next_action
            if result["status"] == "pass" and scenario_requires_host_android(scenario):
                android_dir = scenario_report_path / "android"
                android_rc = run_host_android_smoke(
                    serial=android_serial or "",
                    host_control_port=host_control_port or matrix_control_port(matrix=matrix, scenario=scenario),
                    host_stream_port=host_stream_port or matrix_stream_port(matrix=matrix, scenario=scenario),
                    scenario=scenario,
                    report_dir=android_dir,
                    duration_sec=scenario_duration(matrix, scenario),
                    live=live,
                )
                android_summary_path = android_dir / "summary.json"
                android_summary = read_json(android_summary_path)
                result["android_summary"] = str(android_summary_path)
                result["adb_serial"] = android_serial
                result["phone_info"] = str(android_dir / "phone-info.json")
                result["phone_logcat"] = str(android_dir / "phone-logcat.log")
                result["bytes_received"] = android_summary.get("bytes_received", 0)
                if android_rc != 0 or android_summary.get("ok") is not True:
                    result["status"] = "fail"
                    result["phase"] = "android-stream"
                    result["reason"] = android_summary.get("reason", f"host Android smoke failed rc={android_rc}")
                    result["next_action"] = "Inspect android/summary.json, android/deploy.log, and android/phone-logcat.log."
            if result["status"] == "pass":
                missing_artifacts = validate_required_artifacts(scenario_report_path, scenario)
                if missing_artifacts:
                    result["status"] = "fail"
                    result["phase"] = "artifact-validation"
                    result["reason"] = f"missing required artifacts: {missing_artifacts}"
                    result["next_action"] = "Open the scenario report dir and inspect logs/guest-wizard.log plus guest/wizard/summary.json."
        except Exception as exc:  # noqa: BLE001
            result["reason"] = str(exc)
            result["next_action"] = "Open the scenario report dir and inspect logs/guest-wizard.log and logs/qemu.log."
        finally:
            duration = time.time() - started
            result["duration_sec"] = round(duration, 2)
            try:
                shutdown_guest(distro["ssh_user"], ssh_port, private_key, live=live)
                wait_process(proc, 300, name="shutdown")
            finally:
                if proc.poll() is None:
                    proc.terminate()
                    proc.wait()
            if not should_keep_workdisk(retain_workdisk, result["status"] == "pass"):
                safe_remove(disk)
                result["workdisk_retained"] = False
            else:
                result["workdisk_retained"] = True
        scenario_results.append(result)

    finalize_run_report(run_dir, run_id, scenario_results)
    return 0 if all(item.get("status") == "pass" for item in scenario_results) else 1


def cmd_report(args: argparse.Namespace) -> int:
    report_root = Path(getattr(args, "report_dir", None) or report_dir()).expanduser().resolve()
    run_id = getattr(args, "run_id", None)
    if not run_id:
        runs = sorted([p for p in report_root.glob("*") if p.is_dir()])
        if not runs:
            print("[e2e] no runs")
            return 0
        run_id = runs[-1].name
    run_dir = report_root / run_id
    summary_path = run_dir / "summary.json"
    summary = read_json(summary_path) if summary_path.exists() else {}
    summary_corrupt = summary_path.exists() and not summary
    if (run_dir / "report.md").exists():
        print(json.dumps({"report_md": str(run_dir / "report.md")}, indent=2, sort_keys=True))
    if summary_corrupt:
        print(json.dumps({"summary_corrupt": True, "summary_path": str(summary_path)}, indent=2, sort_keys=True))
    if summary.get("status_counts"):
        print(json.dumps({"status_counts": summary.get("status_counts")}, indent=2, sort_keys=True))
    if summary.get("results"):
        for result in summary["results"]:
            artifact_view = {
                "scenario": result.get("scenario"),
                "status": result.get("status"),
                "phase": result.get("phase"),
                "run_id": result.get("run_id"),
                "wizard_summary": result.get("wizard_summary"),
                "wizard_steps": result.get("wizard_steps"),
                "stream_summary": result.get("stream_summary"),
                "android_summary": result.get("android_summary"),
                "adb_serial": result.get("adb_serial"),
                "bytes_received": result.get("bytes_received"),
                "guest_command": result.get("guest_command"),
                "report_dir": result.get("report_dir"),
                "l1_backing_image": result.get("l1_backing_image"),
                "l1_backing_kind": result.get("l1_backing_kind"),
                "portal_consented_image": result.get("portal_consented_image"),
                "l2_workdisk": result.get("l2_workdisk"),
                "runner_report_dir": result.get("runner_report_dir"),
                "reason_code": result.get("reason_code"),
                "stream_reason_code": result.get("stream_reason_code"),
                "stream_blocked": result.get("stream_blocked"),
                "allow_unconsented_portal": result.get("allow_unconsented_portal"),
                "guest_exit_code": result.get("guest_exit_code"),
                "next_action": result.get("next_action"),
                "workdisk_policy": result.get("workdisk_policy"),
                "workdisk_retained": result.get("workdisk_retained"),
            }
            print(json.dumps(artifact_view, indent=2, sort_keys=True))
        return 0
    print(json.dumps(summary, indent=2, sort_keys=True))
    return 0

def _collect_run_overview(run_dir: Path) -> dict[str, object]:
    summary = read_json(run_dir / "summary.json")
    summary_corrupt = (run_dir / "summary.json").exists() and not summary
    failures = summary.get("failures") or []
    first_failure = failures[0] if isinstance(failures, list) and failures else {}
    last_failure_result = {}
    results = summary.get("results") or []
    if isinstance(results, list) and first_failure.get("scenario"):
        for result in results:
            if isinstance(result, dict) and result.get("scenario") == first_failure.get("scenario"):
                last_failure_result = result
                break
    failed_scenarios = _collect_failed_scenarios(run_dir) if summary.get("status") != "pass" else []
    return {
        "run_id": run_dir.name,
        "status": summary.get("status", "unknown"),
        "scenarios_total": summary.get("scenarios_total", 0),
        "scenarios_passed": summary.get("scenarios_passed", 0),
        "scenarios_failed": summary.get("scenarios_failed", 0),
        "scenarios_blocked": summary.get("scenarios_blocked", 0),
        "scenarios_reboot_required": summary.get("scenarios_reboot_required", 0),
        "status_counts": summary.get("status_counts", {}),
        "failed_scenarios": failed_scenarios,
        "failure_count": len(failures) if isinstance(failures, list) else 0,
        "report_dir": str(run_dir),
        "report_md": str(run_dir / "report.md") if (run_dir / "report.md").exists() else "",
        "junit": str(run_dir / "junit.xml") if (run_dir / "junit.xml").exists() else "",
        "last_failure": first_failure,
        "last_failure_workdisk_policy": last_failure_result.get("workdisk_policy", ""),
        "last_failure_workdisk_retained": last_failure_result.get("workdisk_retained", None),
        "summary_corrupt": summary_corrupt,
    }


def _collect_failed_scenarios(run_dir: Path) -> list[str]:
    summary = read_json(run_dir / "summary.json")
    results = summary.get("results") or []
    failed = []
    for result in results:
        if isinstance(result, dict) and result.get("status") != "pass" and result.get("scenario"):
            failed.append(str(result["scenario"]))
    return failed


def _collect_recovery_commands(last_failed: dict[str, object]) -> list[str]:
    if not last_failed or not str(last_failed.get("run_id", "")).strip():
        return []
    commands = ["./e2e/run last-failed", "./e2e/run rerun-last-failed --live"]
    scenario = str(last_failed.get("scenario", "")).strip()
    if scenario:
        commands.append(f"./e2e/run diagnose-run --run-id {last_failed['run_id']} --scenario {scenario}")
        if str((last_failed.get("last_failure") or {}).get("reason_code", "")) == "portal_consent_required":
            commands.append(f"./e2e/run portal-diagnose --run-id {last_failed['run_id']} --scenario {scenario}")
    return commands


def cmd_history(args: argparse.Namespace) -> int:
    report_root = Path(getattr(args, "report_dir", None) or report_dir()).expanduser().resolve()
    limit = int(getattr(args, "limit", 10))
    failed_only = getattr(args, "failed_only", False)
    if not report_root.exists():
        print(json.dumps([], indent=2))
        return 0
    runs = sorted((p for p in report_root.iterdir() if p.is_dir()), key=lambda p: p.name, reverse=True)
    entries = []
    skipped_corrupt = 0
    for run_dir in runs:
        overview = _collect_run_overview(run_dir)
        if overview.get("summary_corrupt"):
            skipped_corrupt += 1
            continue
        if failed_only and overview["status"] == "pass":
            continue
        entries.append(overview)
        if len(entries) >= limit:
            break
    payload: dict[str, object] = {"entries": entries}
    if skipped_corrupt:
        payload["skipped_corrupt_runs"] = skipped_corrupt
    print(json.dumps(payload, indent=2, sort_keys=True))
    return 0


def cmd_last_failed(args: argparse.Namespace) -> int:
    report_root = Path(getattr(args, "report_dir", None) or report_dir()).expanduser().resolve()
    if not report_root.exists():
        print(json.dumps({}, indent=2, sort_keys=True))
        return 0
    runs = sorted((p for p in report_root.iterdir() if p.is_dir()), key=lambda p: p.name, reverse=True)
    for run_dir in runs:
        overview = _collect_run_overview(run_dir)
        if overview["status"] != "pass":
            print(json.dumps(overview, indent=2, sort_keys=True))
            return 0
    print(json.dumps({}, indent=2, sort_keys=True))
    return 0


def cmd_rerun_last_failed(args: argparse.Namespace) -> int:
    report_root = Path(getattr(args, "report_dir", None) or report_dir()).expanduser().resolve()
    runs = sorted((p for p in report_root.iterdir() if p.is_dir()), key=lambda p: p.name, reverse=True) if report_root.exists() else []
    for run_dir in runs:
        failed = _collect_failed_scenarios(run_dir)
        if not failed:
            continue
        if getattr(args, "dry_run", False):
            print(json.dumps({"report_dir": str(report_root), "source_run": run_dir.name, "failed_scenarios": failed}, indent=2, sort_keys=True))
            return 0
        rc = 0
        for scenario in failed:
            rerun_args = argparse.Namespace(
                scenario=[scenario],
                use_installed=True,
                base_dir=getattr(args, "base_dir", None),
                work_dir=getattr(args, "work_dir", None),
                android_serial=getattr(args, "android_serial", None),
                retain_workdisk=getattr(args, "retain_workdisk", "on-fail"),
                dry_run=getattr(args, "dry_run", False),
                live=getattr(args, "live", False),
                report_dir=getattr(args, "report_dir", None),
                stop_on_fail=getattr(args, "stop_on_fail", True),
            )
            rc = cmd_run(rerun_args)
            if rc != 0 and getattr(args, "stop_on_fail", True):
                return rc
        return rc
    print(json.dumps({}, indent=2, sort_keys=True))
    return 0

def cmd_clean(args: argparse.Namespace) -> int:
    for root in [work_dir(), report_dir()]:
        if root.exists():
            shutil.rmtree(root)
    return 0

def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="e2e-runner")
    sub = parser.add_subparsers(dest="command", required=True)

    plan_p = sub.add_parser("plan")
    plan_p.add_argument("--scenario", nargs="*")
    plan_p.add_argument("--distro", nargs="*")
    plan_p.add_argument("--backend", nargs="*")
    plan_p.add_argument("--tag", nargs="*")
    plan_p.set_defaults(func=cmd_plan)

    status_p = sub.add_parser("status")
    status_p.add_argument("--base-dir")
    status_p.add_argument("--report-dir")
    status_p.add_argument("--json", action="store_true")
    status_p.set_defaults(func=lambda args: print(json.dumps(status_snapshot(load_matrix(), base_root=Path(args.base_dir).expanduser().resolve() if args.base_dir else base_dir(), report_root=Path(args.report_dir).expanduser().resolve() if args.report_dir else report_dir()), indent=2, sort_keys=True)) or 0)

    next_p = sub.add_parser("next")
    next_p.add_argument("--json", action="store_true")
    next_p.set_defaults(func=cmd_next)

    env_shell_p = sub.add_parser("env-shell")
    env_shell_p.add_argument("--file", default=str(E2E_DIR / "env.local"))
    env_shell_p.set_defaults(func=cmd_env_shell)

    init_env_p = sub.add_parser("init-env")
    init_env_p.add_argument("--file", default=str(E2E_DIR / "env.local"))
    init_env_p.add_argument("--force", action="store_true")
    init_env_p.set_defaults(func=cmd_init_env)

    iso_p = sub.add_parser("iso-sources")
    iso_p.set_defaults(func=cmd_iso_sources)

    validate_p = sub.add_parser("validate")
    validate_p.set_defaults(func=cmd_validate)

    base_p = sub.add_parser("prepare-base")
    base_p.add_argument("--distro")
    base_p.add_argument("--session")
    base_p.add_argument("--all", action="store_true")
    base_p.add_argument("--missing", action="store_true")
    base_p.add_argument("--force", action="store_true")
    base_p.add_argument("--base-dir")
    base_p.add_argument("--work-dir")
    base_p.add_argument("--dry-run", action="store_true")
    base_p.add_argument("--live", action="store_true", help="stream VM installer logs to this terminal")
    base_p.set_defaults(func=cmd_prepare_base)

    inst_p = sub.add_parser("prepare-installed")
    inst_p.add_argument("--distro", required=True)
    inst_p.add_argument("--session")
    inst_p.add_argument("--force", action="store_true")
    inst_p.add_argument("--missing", action="store_true")
    inst_p.add_argument(
        "--install-backend",
        choices=("benchmark_game", "wayland", "evdi", "x11"),
        help="Backend passed to install-wbeam during L1 provisioning. Defaults from session.",
    )
    inst_p.add_argument("--base-dir")
    inst_p.add_argument("--work-dir")
    inst_p.add_argument("--dry-run", action="store_true")
    inst_p.add_argument("--live", action="store_true", help="stream guest provisioning logs to this terminal")
    inst_p.set_defaults(func=cmd_prepare_installed)

    run_p = sub.add_parser("run")
    run_p.add_argument("--scenario", action="append", required=True)
    run_p.add_argument("--use-installed", action="store_true")
    run_p.add_argument("--base-dir")
    run_p.add_argument("--work-dir")
    run_p.add_argument("--run-id")
    run_p.add_argument("--report-dir")
    run_p.add_argument("--android-serial")
    run_p.add_argument("--allow-unconsented-portal", action="store_true")
    run_p.add_argument("--retain-workdisk", choices=("always", "on-success", "on-fail", "never"), default="on-fail")
    run_p.add_argument("--dry-run", action="store_true")
    run_p.add_argument("--live", action="store_true", help="stream guest wizard logs to this terminal")
    run_p.set_defaults(func=cmd_run)

    portal_p = sub.add_parser("prepare-portal-consent")
    portal_p.add_argument("--distro", required=True)
    portal_p.add_argument("--session", default="gnome-wayland")
    portal_p.add_argument("--backend", choices=("wayland_portal",), default="wayland_portal")
    portal_p.add_argument("--display", choices=("auto", "gtk", "sdl", "vnc", "none"), default="auto")
    portal_p.add_argument("--base-dir")
    portal_p.add_argument("--work-dir")
    portal_p.add_argument("--timeout-sec", type=int, default=180)
    portal_p.add_argument("--approval-timeout-sec", type=int, default=900)
    portal_p.add_argument("--approval-poll-sec", type=int, default=5)
    portal_p.add_argument("--vnc-port", type=int)
    portal_p.add_argument("--live", action="store_true")
    portal_p.add_argument("--dry-run", action="store_true")
    portal_p.add_argument("--force", action="store_true")
    portal_p.add_argument("--promote", action="store_true")
    portal_p.add_argument("--keep-vm-on-timeout", action="store_true")
    portal_p.set_defaults(func=cmd_prepare_portal_consent)

    report_p = sub.add_parser("report")
    report_p.add_argument("--run-id")
    report_p.add_argument("--report-dir")
    report_p.set_defaults(func=cmd_report)

    assert_p = sub.add_parser("assert-run")
    assert_p.add_argument("--run-id", required=True)
    assert_p.add_argument("--scenario", required=True)
    assert_p.add_argument("--report-dir")
    assert_p.add_argument("--min-bytes", type=int, default=1)
    assert_p.add_argument("--require-portal-consented", action="store_true")
    assert_p.add_argument("--json", action="store_true")
    assert_p.set_defaults(func=cmd_assert_run)

    close_p = sub.add_parser("close")
    close_p.add_argument("--profile", choices=("fedora-mvp", "hardware", "full"), default="fedora-mvp")
    close_p.add_argument("--live", action="store_true")
    close_p.add_argument("--run-prefix", default="FINAL-E2E-CLOSURE")
    close_p.add_argument("--json", action="store_true")
    close_p.set_defaults(func=cmd_close)

    history_p = sub.add_parser("history")
    history_p.add_argument("--report-dir")
    history_p.add_argument("--limit", type=int, default=10)
    history_p.add_argument("--failed-only", action="store_true")
    history_p.set_defaults(func=cmd_history)

    last_failed_p = sub.add_parser("last-failed")
    last_failed_p.add_argument("--report-dir")
    last_failed_p.set_defaults(func=cmd_last_failed)

    rerun_last_failed_p = sub.add_parser("rerun-last-failed")
    rerun_last_failed_p.add_argument("--report-dir")
    rerun_last_failed_p.add_argument("--base-dir")
    rerun_last_failed_p.add_argument("--work-dir")
    rerun_last_failed_p.add_argument("--android-serial")
    rerun_last_failed_p.add_argument("--retain-workdisk", choices=("always", "on-success", "on-fail", "never"), default="on-fail")
    rerun_last_failed_p.add_argument("--dry-run", action="store_true")
    rerun_last_failed_p.add_argument("--live", action="store_true")
    rerun_last_failed_p.add_argument("--stop-on-fail", dest="stop_on_fail", action="store_true", default=True)
    rerun_last_failed_p.add_argument("--no-stop-on-fail", dest="stop_on_fail", action="store_false")
    rerun_last_failed_p.set_defaults(func=cmd_rerun_last_failed)

    diag_run_p = sub.add_parser("diagnose-run")
    diag_run_p.add_argument("--run-id", required=True)
    diag_run_p.add_argument("--scenario", required=True)
    diag_run_p.add_argument("--report-dir")
    diag_run_p.add_argument("--work-dir")
    diag_run_p.set_defaults(func=cmd_diagnose_run)

    portal_diag_p = sub.add_parser("portal-diagnose")
    portal_diag_p.add_argument("--run-id")
    portal_diag_p.add_argument("--scenario", default="fedora43-gnome-wayland-portal-h264")
    portal_diag_p.add_argument("--report-dir")
    portal_diag_p.add_argument("--base-dir")
    portal_diag_p.set_defaults(func=cmd_portal_diagnose)

    diag_portal_p = sub.add_parser("diagnose-portal-consent")
    diag_portal_p.add_argument("--distro", required=True)
    diag_portal_p.add_argument("--session", default="gnome-wayland")
    diag_portal_p.add_argument("--base-dir")
    diag_portal_p.add_argument("--work-dir")
    diag_portal_p.set_defaults(func=cmd_diagnose_portal_consent)

    clean_p = sub.add_parser("clean")
    clean_p.set_defaults(func=cmd_clean)

    diag_inst_p = sub.add_parser("diagnose-installed")
    diag_inst_p.add_argument("--distro", required=True)
    diag_inst_p.add_argument("--session")
    diag_inst_p.add_argument("--work-dir")
    diag_inst_p.set_defaults(func=cmd_diagnose_installed)

    return parser


def main() -> int:
    load_env_local()
    parser = build_parser()
    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
