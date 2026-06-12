#!/usr/bin/env python3
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import shlex
import shutil
import subprocess
import sys
import time
from pathlib import Path

from report import finalize_run_report, init_run_report, scenario_report_dir, write_json
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


ROOT = Path(__file__).resolve().parents[2]
E2E_DIR = ROOT / "e2e"
MATRIX_PATH = ROOT / "e2e" / "matrix.json"
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


def load_matrix() -> dict:
    with MATRIX_PATH.open("r", encoding="utf-8") as fh:
        return json.load(fh)


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


def scenario_base_image_path(scenario: dict, base_root: Path | None = None) -> Path:
    return base_image_path(scenario["distro"], scenario["session"], base_root)


def scenario_work_dir(run_id: str, scenario: dict, work_root: Path | None = None) -> Path:
    root = work_root or work_dir()
    return root / "runs" / run_id / scenario["id"]


def ssh_key_path() -> Path:
    return path_from_env("WBEAM_E2E_SSH_KEY", work_dir() / "ssh" / "id_ed25519")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_existing_file(path: Path, *, what: str) -> Path:
    if not path.exists():
        raise RuntimeError(f"missing {what}: {path}")
    return path


def distro_by_id(matrix: dict, distro_id: str) -> dict:
    distros = distros_by_id(matrix)
    try:
        return distros[distro_id]
    except KeyError as exc:
        raise RuntimeError(f"unknown distro: {distro_id}") from exc


def require_iso_path(distro: dict) -> Path:
    iso_value = os.environ.get(distro["iso_env"], "").strip()
    if not iso_value:
        raise RuntimeError(f"environment variable {distro['iso_env']} is not set")
    return require_existing_file(Path(iso_value).expanduser().resolve(), what=f"installer ISO for {distro['id']}")


def default_qemu_display(session: str, *, installer: bool) -> str:
    if session == "headless" and not installer:
        return "none"
    return os.environ.get("WBEAM_E2E_QEMU_DISPLAY", "gtk")


def coerce_int(value: object, *, default: int) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def run_dir_for_base(spec: dict, work_root: Path) -> Path:
    return work_root / "base-build" / spec["distro"] / spec["session"]


def write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def shell_quote(value: str) -> str:
    return shlex.quote(value)


def collect_guest_command_output(
    *,
    user: str,
    port: int,
    key: Path,
    command: str,
    output_path: Path,
    check: bool = False,
) -> None:
    proc = ssh(user, port, key, command, check=check)
    payload = (proc.stdout or "") + (proc.stderr or "")
    write_text(output_path, payload)


def stop_process(proc: subprocess.Popen[str], *, timeout_sec: int = 20) -> None:
    if proc.poll() is not None:
        return
    proc.terminate()
    try:
        proc.wait(timeout=timeout_sec)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait(timeout=timeout_sec)


def wait_debian_installer_complete(proc: subprocess.Popen[str], serial_log: Path, timeout_sec: int, *, name: str) -> None:
    deadline = time.time() + timeout_sec
    while time.time() < deadline:
        if proc.poll() is not None:
            return
        if serial_log.exists():
            tail = serial_log.read_text(encoding="utf-8", errors="ignore")[-30000:]
            if "Installation step failed" in tail:
                raise RuntimeError(f"{name} failed during Debian installer finish step")
            if (
                "Installing GRUB boot loader" in tail
                and "Finishing the installation" in tail
                and "Debian installer main menu" in tail
            ):
                stop_process(proc)
                return
        time.sleep(5)
    stop_process(proc)
    raise TimeoutError(f"{name} did not reach Debian installer completion within {timeout_sec}s")


def guest_user_ids(root: Path, user: str) -> tuple[int, int]:
    passwd = root / "etc" / "passwd"
    if passwd.exists():
        for line in passwd.read_text(encoding="utf-8", errors="ignore").splitlines():
            fields = line.split(":")
            if len(fields) >= 4 and fields[0] == user:
                return int(fields[2]), int(fields[3])
    return 1000, 1000


def configure_debian_desktop_offline(root: Path, session: str, ssh_user: str, log: Path) -> None:
    if session == "headless":
        return
    session_value = "gnome-xorg" if session == "gnome-xorg" else "gnome"
    gdm_lines = ["[daemon]", "AutomaticLoginEnable=True", f"AutomaticLogin={ssh_user}"]
    if session == "gnome-xorg":
        gdm_lines.append("WaylandEnable=false")
    gdm_lines.extend(["[security]", "DisallowTCP=false"])
    account_lines = ["[User]", f"Session={session_value}", f"XSession={session_value}", "SystemAccount=false"]
    write_text(root / "etc" / "gdm" / "custom.conf", "\n".join(gdm_lines) + "\n")
    accounts_root = root / "var" / "lib" / "AccountsService"
    accounts_users = accounts_root / "users"
    accounts_user_file = accounts_users / ssh_user
    write_text(accounts_user_file, "\n".join(account_lines) + "\n")
    accounts_root.mkdir(parents=True, exist_ok=True)
    accounts_users.mkdir(parents=True, exist_ok=True)
    os.chown(accounts_root, 0, 0)
    os.chown(accounts_users, 0, 0)
    os.chown(accounts_user_file, 0, 0)
    os.chmod(accounts_root, 0o755)
    os.chmod(accounts_users, 0o755)
    os.chmod(accounts_user_file, 0o644)
    if shutil.which("systemctl"):
        run_cmd([require_tool("systemctl"), "--root", str(root), "set-default", "graphical.target"], log=log, check=False)
        run_cmd([require_tool("systemctl"), "--root", str(root), "enable", "gdm3"], log=log, check=False)
        run_cmd([require_tool("systemctl"), "--root", str(root), "enable", "gdm"], log=log, check=False)


def ensure_systemd_unit_enabled(root: Path, unit: str, log: Path) -> None:
    target = "sockets.target.wants" if unit.endswith(".socket") else "multi-user.target.wants"
    wants_dir = root / "etc" / "systemd" / "system" / target
    candidates = [
        root / "lib" / "systemd" / "system" / unit,
        root / "usr" / "lib" / "systemd" / "system" / unit,
    ]
    unit_path = next((path for path in candidates if path.exists()), None)
    if unit_path is None:
        return
    wants_dir.mkdir(parents=True, exist_ok=True)
    link_path = wants_dir / unit
    if link_path.exists() or link_path.is_symlink():
        return
    link_path.symlink_to(unit_path)
    run_cmd(
        [
            require_tool("bash"),
            "-lc",
            f"printf '%s -> %s\\n' {shell_quote(str(link_path))} {shell_quote(str(unit_path))}",
        ],
        log=log,
        check=False,
    )


def provision_debian_install_offline(
    *,
    disk: Path,
    build_dir: Path,
    distro_id: str,
    session: str,
    ssh_user: str,
    public_key: str,
) -> None:
    log = build_dir / "offline-provision.log"
    nbd = os.environ.get("WBEAM_E2E_NBD_DEVICE", "/dev/nbd0")
    partition = Path(f"{nbd}p1")
    mount_root = build_dir / "offline-provision" / "mnt"
    mount_root.mkdir(parents=True, exist_ok=True)
    mounted = False
    connected = False
    run_cmd([require_tool("modprobe"), "nbd", "max_part=8"], log=log)
    run_cmd([require_tool("qemu-nbd"), "--disconnect", nbd], log=log, check=False)
    try:
        run_cmd([require_tool("qemu-nbd"), f"--connect={nbd}", str(disk)], log=log)
        connected = True
        for _ in range(30):
            if partition.exists():
                break
            time.sleep(1)
        if not partition.exists():
            raise RuntimeError(f"Debian install partition did not appear: {partition}")
        run_cmd([require_tool("mount"), str(partition), str(mount_root)], log=log)
        mounted = True

        uid, gid = guest_user_ids(mount_root, ssh_user)
        ssh_dir = mount_root / "home" / ssh_user / ".ssh"
        ssh_dir.mkdir(parents=True, exist_ok=True)
        authorized_keys = ssh_dir / "authorized_keys"
        authorized_keys.write_text(public_key + "\n", encoding="utf-8")
        os.chown(ssh_dir, uid, gid)
        os.chown(authorized_keys, uid, gid)
        os.chmod(ssh_dir, 0o700)
        os.chmod(authorized_keys, 0o600)

        sudoers = mount_root / "etc" / "sudoers.d" / ssh_user
        write_text(sudoers, f"{ssh_user} ALL=(ALL) NOPASSWD: ALL\n")
        os.chmod(sudoers, 0o440)

        marker = {
            "created_by": "wbeam-e2e",
            "distro": distro_id,
            "session": session,
            "ssh_user": ssh_user,
        }
        write_text(mount_root / "var" / "lib" / "wbeam-e2e" / "base-ready.json", json.dumps(marker, sort_keys=True) + "\n")

        if shutil.which("systemctl"):
            run_cmd([require_tool("systemctl"), "--root", str(mount_root), "enable", "ssh"], log=log, check=False)
            run_cmd([require_tool("systemctl"), "--root", str(mount_root), "enable", "sshd"], log=log, check=False)
        ensure_systemd_unit_enabled(mount_root, "ssh.service", log)
        ensure_systemd_unit_enabled(mount_root, "sshd.service", log)
        ensure_systemd_unit_enabled(mount_root, "ssh.socket", log)
        configure_debian_desktop_offline(mount_root, session, ssh_user, log)
    finally:
        if mounted:
            run_cmd([require_tool("umount"), str(mount_root)], log=log, check=False)
        if connected:
            run_cmd([require_tool("qemu-nbd"), "--disconnect", nbd], log=log, check=False)


def offline_provision_marker(build_dir: Path) -> Path:
    return build_dir / "offline-provision.done"


def ensure_selection(selected: list[dict], *, noun: str) -> None:
    if not selected:
        raise RuntimeError(f"no {noun} selected")


def build_guest_env(defaults: dict, scenario: dict, *, guest_report_dir: str) -> str:
    env_map = {
        "WBEAM_E2E_GUEST_ROOT": "/home/wbeam/WBeam",
        "WBEAM_E2E_BACKEND": scenario["backend"],
        "WBEAM_E2E_DISPLAY_MODE": scenario["display_mode"],
        "WBEAM_E2E_DURATION_SEC": str(scenario_duration({"defaults": defaults}, scenario)),
        "WBEAM_E2E_CONTROL_PORT": str(defaults.get("control_port", 5001)),
        "WBEAM_E2E_STREAM_PORT": str(defaults.get("stream_port", 5000)),
        "WBEAM_E2E_ENCODER": str(scenario.get("encoder", defaults.get("encoder", "h264"))),
        "WBEAM_E2E_SIZE": str(scenario.get("size", defaults.get("size", "1280x800"))),
        "WBEAM_E2E_FPS": str(scenario.get("fps", defaults.get("fps", 30))),
        "WBEAM_E2E_BITRATE_KBPS": str(scenario.get("bitrate_kbps", defaults.get("bitrate_kbps", 10000))),
        "WBEAM_E2E_REPORT_DIR": guest_report_dir,
    }
    return " ".join(f"{name}={shell_quote(value)}" for name, value in sorted(env_map.items()))


def scenario_runtime_config(matrix: dict, scenario: dict) -> dict:
    defaults = matrix.get("defaults", {})
    return {
        "cpu": coerce_int(scenario.get("cpu", defaults.get("cpu")), default=4),
        "memory_mib": coerce_int(scenario.get("memory_mib", defaults.get("memory_mib")), default=8192),
        "control_port": coerce_int(defaults.get("control_port"), default=5001),
        "stream_port": coerce_int(defaults.get("stream_port"), default=5000),
    }


def base_manifest_path(distro_id: str, session: str, base_root: Path | None = None) -> Path:
    return base_image_path(distro_id, session, base_root).with_suffix(".json")


def safe_remove(path: Path) -> None:
    if not path.exists():
        return
    if path.is_dir():
        shutil.rmtree(path)
    else:
        path.unlink()


def host_metadata() -> dict:
    return {
        "generated_at": utc_iso_timestamp(),
        "repo_root": str(ROOT),
        "base_dir": str(base_dir()),
        "work_dir": str(work_dir()),
        "report_dir": str(report_dir()),
        "python": sys.version,
        "platform": sys.platform,
        "env": {
            key: os.environ.get(key, "")
            for key in (
                "WBEAM_E2E_ISO_FEDORA_43",
                "WBEAM_E2E_ISO_UBUNTU_24_04",
                "WBEAM_E2E_ISO_DEBIAN_12",
                "WBEAM_E2E_BASE_DIR",
                "WBEAM_E2E_WORK_DIR",
                "WBEAM_E2E_REPORT_DIR",
            )
        },
    }


def base_sanity_command(*, session: str) -> str:
    parts = [
        "set -euo pipefail",
        "test -f /var/lib/wbeam-e2e/base-ready.json",
        "id",
        "cat /etc/os-release",
    ]
    if session != "headless":
        parts.extend(
            [
                "sudo -n test -f /etc/gdm/custom.conf",
                "sudo -n test -f /var/lib/AccountsService/users/wbeam",
            ]
        )
    return "; ".join(parts)


def report_summaries(report_root: Path | None = None) -> list[dict]:
    root = report_root or report_dir()
    summaries: list[dict] = []
    if not root.exists():
        return summaries
    for summary_path in sorted(root.glob("*/summary.json")):
        try:
            payload = json.loads(summary_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        payload["_path"] = str(summary_path)
        summaries.append(payload)
    return summaries


def status_snapshot(matrix: dict, *, base_root: Path | None = None, report_root: Path | None = None) -> dict:
    base_root = base_root or base_dir()
    report_root = report_root or report_dir()
    essential_files = [
        E2E_DIR / "run",
        E2E_DIR / "matrix.json",
        E2E_DIR / "env.example",
        E2E_DIR / "scripts" / "runner.py",
        E2E_DIR / "scripts" / "vm.py",
        E2E_DIR / "scripts" / "seed.py",
        E2E_DIR / "scripts" / "report.py",
        E2E_DIR / "scripts" / "guest-install-wbeam.sh",
        E2E_DIR / "scripts" / "guest-stream-smoke.sh",
        E2E_DIR / "scripts" / "preflight.sh",
    ]
    template_files = [
        E2E_DIR / "scripts" / "templates" / "fedora.ks",
        E2E_DIR / "scripts" / "templates" / "ubuntu-user-data.yml",
        E2E_DIR / "scripts" / "templates" / "debian-preseed.cfg",
    ]
    distros = matrix.get("distros", [])
    iso_inputs: list[dict] = []
    iso_ready = 0
    for distro in distros:
        iso_value = os.environ.get(distro["iso_env"], "").strip()
        exists = bool(iso_value) and Path(iso_value).expanduser().exists()
        iso_inputs.append(
            {
                "distro": distro["id"],
                "env": distro["iso_env"],
                "value": iso_value,
                "exists": exists,
            }
        )
        if exists:
            iso_ready += 1
    base_specs = image_specs(matrix)
    prepared_base = 0
    base_images: list[dict] = []
    for spec in base_specs:
        image_path = base_image_path(spec["distro"], spec["session"], base_root)
        manifest_path = base_manifest_path(spec["distro"], spec["session"], base_root)
        ready = image_path.exists() and manifest_path.exists()
        base_images.append(
            {
                "distro": spec["distro"],
                "session": spec["session"],
                "image_path": str(image_path),
                "manifest_path": str(manifest_path),
                "ready": ready,
            }
        )
        if ready:
            prepared_base += 1
    summaries = report_summaries(report_root)
    dry_run_ok = any(
        summary.get("status") == "pass"
        and any(result.get("phase") == "dry-run" for result in summary.get("results", []))
        for summary in summaries
    )
    live_run_ok = any(
        summary.get("status") == "pass"
        and any(result.get("phase") not in {"dry-run", None} for result in summary.get("results", []))
        for summary in summaries
    )
    desktop_commands = "\n".join(desktop_shell_commands("gnome-wayland", "wbeam"))
    desktop_sanity = base_sanity_command(session="gnome-wayland")
    desktop_ready = (
        "AutomaticLogin=wbeam" in desktop_commands
        and "Session=gnome" in desktop_commands
        and "sudo -n test -f /etc/gdm/custom.conf" in desktop_sanity
        and "sudo -n test -f /var/lib/AccountsService/users/wbeam" in desktop_sanity
    )
    tool_ready = all(
        [
            shutil.which("python3"),
            shutil.which("qemu-system-x86_64"),
            shutil.which("qemu-img"),
            shutil.which("ssh"),
            shutil.which("ssh-keygen"),
            shutil.which("rsync"),
            shutil.which("xorriso") or shutil.which("genisoimage") or shutil.which("cloud-localds"),
            Path("/dev/kvm").exists(),
        ]
    )

    items = [
        {
            "id": "matrix_valid",
            "label": "Matrix validates",
            "weight": 10,
            "done": not validate_matrix(matrix),
            "details": "e2e/matrix.json passes local validation",
        },
        {
            "id": "runner_files",
            "label": "Runner files present",
            "weight": 10,
            "done": all(path.exists() for path in essential_files),
            "details": "core e2e scripts and wrapper exist",
        },
        {
            "id": "seed_templates",
            "label": "Seed templates present",
            "weight": 8,
            "done": all(path.exists() for path in template_files),
            "details": "Fedora, Ubuntu, Debian seed templates exist",
        },
        {
            "id": "desktop_bootstrap",
            "label": "Desktop bootstrap configured",
            "weight": 8,
            "done": desktop_ready,
            "details": "desktop sessions configure GDM autologin and session selection",
        },
        {
            "id": "tests_present",
            "label": "Framework tests present",
            "weight": 7,
            "done": (E2E_DIR / "tests" / "test_e2e_runner.py").exists(),
            "details": "unit tests cover runner, seeds, reports, and dry-runs",
        },
        {
            "id": "host_tools",
            "label": "Host tools available",
            "weight": 8,
            "done": tool_ready,
            "details": "QEMU/KVM, SSH, rsync, and seed ISO tools are installed",
        },
        {
            "id": "iso_inputs",
            "label": "Installer ISOs configured",
            "weight": 10,
            "done": iso_ready == len(distros),
            "details": f"{iso_ready}/{len(distros)} ISO env vars point to existing files",
        },
        {
            "id": "base_images",
            "label": "Base images prepared",
            "weight": 12,
            "done": prepared_base == len(base_specs) and len(base_specs) > 0,
            "details": f"{prepared_base}/{len(base_specs)} base images + manifests exist",
        },
        {
            "id": "reports_ready",
            "label": "Report pipeline exercised",
            "weight": 7,
            "done": any((report_root / run_id / "junit.xml").exists() for run_id in [path.name for path in report_root.glob("*") if path.is_dir()]),
            "details": "at least one run wrote report summary and junit",
        },
        {
            "id": "dry_run_verified",
            "label": "Dry-run verified",
            "weight": 8,
            "done": dry_run_ok,
            "details": "at least one dry-run finished with pass status",
        },
        {
            "id": "live_run_verified",
            "label": "Live VM run verified",
            "weight": 12,
            "done": live_run_ok,
            "details": "at least one non-dry-run scenario passed",
        },
    ]
    completed_weight = 0.0
    total_weight = 0.0
    for item in items:
        total_weight += item["weight"]
        if item["id"] == "iso_inputs":
            completed_weight += item["weight"] * (iso_ready / max(len(distros), 1))
        elif item["id"] == "base_images":
            completed_weight += item["weight"] * (prepared_base / max(len(base_specs), 1))
        elif item["done"]:
            completed_weight += item["weight"]
    percent = round((completed_weight / total_weight) * 100, 1) if total_weight else 0.0
    next_commands: list[str] = []
    if not tool_ready:
        next_commands.append("./e2e/scripts/preflight.sh --strict")
    if iso_ready < len(distros):
        next_commands.append("./e2e/run init-env")
        next_commands.append("./e2e/run iso-sources")
        next_commands.append("edit e2e/env.local")
        next_commands.append('eval "$(./e2e/run env-shell)"')
        for item in iso_inputs:
            if not item["exists"]:
                next_commands.append(f"export {item['env']}=/absolute/path/to/{item['distro']}.iso")
        next_commands.append("./e2e/scripts/preflight.sh")
    if prepared_base < len(base_specs):
        next_commands.append("./e2e/run prepare-base --all --missing")
    if not live_run_ok:
        next_commands.append("./e2e/run run --tag smoke --ready")
    next_commands.append("./e2e/run status")
    return {
        "percent": percent,
        "items": items,
        "base_specs_total": len(base_specs),
        "base_specs_prepared": prepared_base,
        "base_images": base_images,
        "iso_ready": iso_ready,
        "iso_total": len(distros),
        "iso_inputs": iso_inputs,
        "report_runs": len(summaries),
        "live_run_verified": live_run_ok,
        "dry_run_verified": dry_run_ok,
        "missing_iso_inputs": [item for item in iso_inputs if not item["exists"]],
        "missing_base_images": [item for item in base_images if not item["ready"]],
        "next_commands": next_commands,
    }


def scenario_duration(matrix: dict, scenario: dict) -> int:
    return int(
        scenario.get(
            "duration_sec",
            matrix.get("defaults", {}).get("stream_duration_sec", 0),
        )
    )


def validate_matrix(matrix: dict) -> list[str]:
    errors: list[str] = []
    if matrix.get("schema") != 1:
        errors.append("schema must be 1")

    defaults = matrix.get("defaults")
    if not isinstance(defaults, dict):
        errors.append("defaults must be an object")
        defaults = {}
    if int(defaults.get("stream_duration_sec", 0)) < 60:
        errors.append("defaults.stream_duration_sec must be >= 60")

    distros = matrix.get("distros")
    if not isinstance(distros, list) or not distros:
        errors.append("distros must be a non-empty array")
        distros = []

    distro_ids: set[str] = set()
    for distro in distros:
        for key in ("id", "family", "iso_env", "installer", "ssh_user"):
            if not distro.get(key):
                errors.append(f"distro missing {key}: {distro}")
        distro_id = distro.get("id")
        if distro_id in distro_ids:
            errors.append(f"duplicate distro id: {distro_id}")
        if distro_id:
            distro_ids.add(distro_id)

    scenarios = matrix.get("scenarios")
    if not isinstance(scenarios, list) or not scenarios:
        errors.append("scenarios must be a non-empty array")
        scenarios = []

    scenario_ids: set[str] = set()
    for scenario in scenarios:
        for key in ("id", "distro", "session", "backend", "display_mode", "tier"):
            if not scenario.get(key):
                errors.append(f"scenario missing {key}: {scenario}")
        scenario_id = scenario.get("id")
        if scenario_id in scenario_ids:
            errors.append(f"duplicate scenario id: {scenario_id}")
        if scenario_id:
            scenario_ids.add(scenario_id)
        if scenario.get("distro") not in distro_ids:
            errors.append(f"scenario {scenario_id} references unknown distro {scenario.get('distro')}")
        if scenario_duration(matrix, scenario) < 60:
            errors.append(f"scenario {scenario_id} duration must be >= 60")

    for distro_id in ("fedora-43", "ubuntu-24.04", "debian-12"):
        if distro_id not in distro_ids:
            errors.append(f"missing required distro: {distro_id}")
        for backend in ("benchmark_game", "wayland_portal", "evdi"):
            if not any(s.get("distro") == distro_id and s.get("backend") == backend for s in scenarios):
                errors.append(f"missing scenario for distro={distro_id} backend={backend}")

    return errors


def parse_env_file(path: Path) -> dict[str, str]:
    payload: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip()
        if not key.startswith("WBEAM_E2E_"):
            continue
        payload[key] = value
    return payload


def select_scenarios(matrix: dict, args: argparse.Namespace) -> list[dict]:
    selected = list(matrix.get("scenarios", []))
    if getattr(args, "scenario", None):
        wanted = set(args.scenario)
        selected = [s for s in selected if s["id"] in wanted]
    if getattr(args, "distro", None):
        wanted = set(args.distro)
        selected = [s for s in selected if s["distro"] in wanted]
    if getattr(args, "backend", None):
        wanted = set(args.backend)
        selected = [s for s in selected if s["backend"] in wanted]
    if getattr(args, "tag", None):
        wanted = set(args.tag)
        selected = [s for s in selected if wanted.intersection(set(s.get("tags", [])))]
    return selected


def add_filters(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--distro", action="append", help="Select distro id; repeatable")
    parser.add_argument("--backend", action="append", help="Select backend; repeatable")
    parser.add_argument("--scenario", action="append", help="Select exact scenario id; repeatable")
    parser.add_argument("--tag", action="append", help="Select scenario tag; repeatable")


def has_any_filter(args: argparse.Namespace) -> bool:
    return bool(
        getattr(args, "scenario", None)
        or getattr(args, "distro", None)
        or getattr(args, "backend", None)
        or getattr(args, "tag", None)
    )


def image_specs(matrix: dict, scenarios: list[dict] | None = None) -> list[dict]:
    distros = distros_by_id(matrix)
    selected = scenarios if scenarios is not None else matrix.get("scenarios", [])
    specs: dict[tuple[str, str], dict] = {}
    for scenario in selected:
        key = (scenario["distro"], scenario["session"])
        spec = specs.setdefault(
            key,
            {
                "distro": scenario["distro"],
                "family": distros[scenario["distro"]]["family"],
                "session": scenario["session"],
                "installer": distros[scenario["distro"]]["installer"],
                "iso_env": distros[scenario["distro"]]["iso_env"],
                "backends": set(),
                "scenarios": [],
            },
        )
        spec["backends"].add(scenario["backend"])
        spec["scenarios"].append(scenario["id"])

    result: list[dict] = []
    for spec in specs.values():
        result.append(
            {
                **spec,
                "backends": sorted(spec["backends"]),
                "scenarios": sorted(spec["scenarios"]),
            }
        )
    return sorted(result, key=lambda s: (s["distro"], s["session"]))


def select_base_specs(matrix: dict, args: argparse.Namespace) -> list[dict]:
    selected = select_scenarios(matrix, args)
    specs = image_specs(matrix, selected)
    if getattr(args, "session", None):
        wanted_sessions = set(args.session)
        specs = [spec for spec in specs if spec["session"] in wanted_sessions]
    return specs


def filter_missing_base_specs(specs: list[dict], base_root: Path) -> list[dict]:
    return [
        spec
        for spec in specs
        if not (
            base_image_path(spec["distro"], spec["session"], base_root).exists()
            and base_manifest_path(spec["distro"], spec["session"], base_root).exists()
        )
    ]


def filter_ready_scenarios(scenarios: list[dict], base_root: Path) -> list[dict]:
    return [scenario for scenario in scenarios if scenario_base_image_path(scenario, base_root).exists()]


def cmd_validate(_: argparse.Namespace) -> int:
    errors = validate_matrix(load_matrix())
    if errors:
        for error in errors:
            print(f"[e2e][ERROR] {error}", file=sys.stderr)
        return 1
    print("[e2e] matrix OK")
    return 0


def cmd_env(_: argparse.Namespace) -> int:
    matrix = load_matrix()
    for distro in matrix.get("distros", []):
        print(distro["iso_env"])
    return 0


def cmd_env_shell(args: argparse.Namespace) -> int:
    env_path = Path(args.file).expanduser().resolve() if args.file else (E2E_DIR / "env.local").resolve()
    if not env_path.exists():
        print(f"[e2e][ERROR] env file does not exist: {env_path}", file=sys.stderr)
        return 2
    payload = parse_env_file(env_path)
    if not payload:
        print(f"[e2e][ERROR] no WBEAM_E2E_* entries found in: {env_path}", file=sys.stderr)
        return 2
    for key, value in sorted(payload.items()):
        print(f"export {key}={shell_quote(value)}")
    return 0


def cmd_init_env(args: argparse.Namespace) -> int:
    source = (E2E_DIR / "env.example").resolve()
    target = Path(args.file).expanduser().resolve() if args.file else (E2E_DIR / "env.local").resolve()
    if target.exists() and not args.force:
        print(f"[e2e][ERROR] env file already exists: {target}", file=sys.stderr)
        print("[e2e][ERROR] pass --force to overwrite", file=sys.stderr)
        return 2
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)
    print(target)
    return 0


def cmd_iso_sources(args: argparse.Namespace) -> int:
    matrix = load_matrix()
    rows = []
    for distro in matrix.get("distros", []):
        info = ISO_SOURCES.get(distro["id"], {})
        rows.append(
            {
                "distro": distro["id"],
                "env": distro["iso_env"],
                "label": info.get("label", ""),
                "page_url": info.get("page_url", ""),
                "download_url": info.get("download_url", ""),
                "checksum_url": info.get("checksum_url", ""),
                "filename_hint": info.get("filename_hint", ""),
            }
        )
    if args.json:
        print(json.dumps(rows, indent=2, sort_keys=True))
        return 0
    for row in rows:
        print(f"{row['distro']}: {row['page_url']}")
        print(f"  env: {row['env']}")
        if row["download_url"]:
            print(f"  download: {row['download_url']}")
        if row["checksum_url"]:
            print(f"  checksum: {row['checksum_url']}")
        if row["filename_hint"]:
            print(f"  file: {row['filename_hint']}")
    return 0


def cmd_list(args: argparse.Namespace) -> int:
    matrix = load_matrix()
    selected = select_scenarios(matrix, args)
    if args.json:
        print(json.dumps(selected, indent=2, sort_keys=True))
        return 0

    print(f"{'id':42} {'distro':14} {'session':16} {'backend':16} {'tier':8}")
    print("-" * 104)
    for scenario in selected:
        print(
            f"{scenario['id']:42} "
            f"{scenario['distro']:14} "
            f"{scenario['session']:16} "
            f"{scenario['backend']:16} "
            f"{scenario['tier']:8}"
        )
    return 0


def cmd_images(args: argparse.Namespace) -> int:
    matrix = load_matrix()
    selected = select_scenarios(matrix, args)
    root = Path(args.base_dir).expanduser().resolve() if args.base_dir else base_dir()
    rows = []
    for spec in image_specs(matrix, selected):
        path = base_image_path(spec["distro"], spec["session"], root)
        rows.append(
            {
                **spec,
                "path": str(path),
                "exists": path.exists(),
                "size_bytes": path.stat().st_size if path.exists() else 0,
            }
        )

    if args.json:
        print(json.dumps(rows, indent=2, sort_keys=True))
        return 0

    print(f"[e2e] base_dir={root}")
    print(f"{'distro':14} {'session':16} {'exists':7} {'backends':34} path")
    print("-" * 120)
    for row in rows:
        backends = ",".join(row["backends"])
        exists = "yes" if row["exists"] else "no"
        print(f"{row['distro']:14} {row['session']:16} {exists:7} {backends:34} {row['path']}")
    return 0


def scenario_start_url(defaults: dict, scenario: dict) -> str:
    control_port = defaults.get("control_port", 5001)
    params = [f"display_mode={scenario['display_mode']}"]
    if scenario["backend"] != "benchmark_game":
        params.append(f"capture_backend={scenario['backend']}")
    return f"http://127.0.0.1:{control_port}/v1/start?" + "&".join(params)


def cmd_plan(args: argparse.Namespace) -> int:
    matrix = load_matrix()
    errors = validate_matrix(matrix)
    if errors:
        for error in errors:
            print(f"[e2e][ERROR] {error}", file=sys.stderr)
        return 1

    selected = select_scenarios(matrix, args)
    distros = distros_by_id(matrix)
    defaults = matrix["defaults"]
    if args.json:
        payload = {
            "defaults": defaults,
            "distros": [distros[s["distro"]] for s in selected],
            "scenarios": selected,
        }
        print(json.dumps(payload, indent=2, sort_keys=True))
        return 0

    print(f"[e2e] selected scenarios: {len(selected)}")
    for scenario in selected:
        distro = distros[scenario["distro"]]
        duration = scenario_duration(matrix, scenario)
        iso_value = os.environ.get(distro["iso_env"], "")
        iso_status = iso_value if iso_value else "<unset>"
        print()
        print(f"scenario: {scenario['id']}")
        print(f"  distro: {distro['id']} ({distro['installer']})")
        print(f"  iso env: {distro['iso_env']}={iso_status}")
        print(f"  session: {scenario['session']}")
        print(f"  backend: {scenario['backend']}")
        print(f"  duration: {duration}s")
        print(f"  base image: {scenario_base_image_path(scenario)}")
        print("  work disk:")
        print(f"    ./e2e/run workdisk-create --scenario {scenario['id']}")
        print("  guest install:")
        print(f"    WBEAM_E2E_BACKEND={scenario['backend']} ./e2e/scripts/guest-install-wbeam.sh")
        print("  guest stream:")
        print(
            "    "
            f"WBEAM_E2E_BACKEND={scenario['backend']} "
            f"WBEAM_E2E_DISPLAY_MODE={scenario['display_mode']} "
            f"./e2e/scripts/guest-stream-smoke.sh {scenario['backend']} {duration}"
        )
        print(f"  start url: {scenario_start_url(defaults, scenario)}")
    return 0


def cmd_base_plan(args: argparse.Namespace) -> int:
    matrix = load_matrix()
    errors = validate_matrix(matrix)
    if errors:
        for error in errors:
            print(f"[e2e][ERROR] {error}", file=sys.stderr)
        return 1

    selected = select_scenarios(matrix, args)
    specs = image_specs(matrix, selected)
    if args.session:
        wanted_sessions = set(args.session)
        specs = [spec for spec in specs if spec["session"] in wanted_sessions]

    base_root = Path(args.base_dir).expanduser().resolve() if args.base_dir else base_dir()
    work_root = Path(args.work_dir).expanduser().resolve() if args.work_dir else work_dir()

    if args.json:
        payload = []
        for spec in specs:
            payload.append(
                {
                    **spec,
                    "base_image": str(base_image_path(spec["distro"], spec["session"], base_root)),
                    "build_dir": str(work_root / "base-build" / spec["distro"] / spec["session"]),
                    "install_disk": str(
                        work_root
                        / "base-build"
                        / spec["distro"]
                        / spec["session"]
                        / "install.qcow2"
                    ),
                }
            )
        print(json.dumps(payload, indent=2, sort_keys=True))
        return 0

    defaults = matrix["defaults"]
    for spec in specs:
        build_dir = work_root / "base-build" / spec["distro"] / spec["session"]
        install_disk = build_dir / "install.qcow2"
        base_image = base_image_path(spec["distro"], spec["session"], base_root)
        iso_value = os.environ.get(spec["iso_env"], "")
        iso_status = iso_value if iso_value else "<unset>"
        print()
        print(f"base: {spec['distro']} / {spec['session']}")
        print(f"  installer: {spec['installer']}")
        print(f"  iso: {spec['iso_env']}={iso_status}")
        print(f"  build dir: {build_dir}")
        print(f"  install disk: {install_disk}")
        print(f"  final base image: {base_image}")
        print("  create install disk:")
        print(f"    qemu-img create -f qcow2 {install_disk} {defaults['disk_gib']}G")
        print("  future installer runner:")
        print("    boot ISO + unattended seed, install OS to install disk, first boot, then shutdown")
        print("  promote clean disk:")
        print(f"    mkdir -p {base_image.parent}")
        print(f"    cp {install_disk} {base_image}")
        print("  test overlays will use this base image as read-only backing storage")
    return 0


def cmd_report_init(args: argparse.Namespace) -> int:
    matrix = load_matrix()
    selected = select_scenarios(matrix, args)
    report_base = report_dir()
    run_id = args.run_id or utc_timestamp()
    run_dir = init_run_report(report_base, run_id, selected, host=host_metadata())
    write_json(
        run_dir / "summary.json",
        {"run_id": run_id, "status": "created", "scenario_count": len(selected)},
    )
    print(run_dir)
    return 0


def qemu_img() -> str:
    binary = shutil.which("qemu-img")
    if not binary:
        raise RuntimeError("qemu-img not found; install qemu-utils/qemu-img")
    return binary


def run_qemu_img(cmd: list[str]) -> None:
    try:
        subprocess.run(cmd, check=True, capture_output=True, text=True)
    except subprocess.CalledProcessError as exc:
        if exc.stdout:
            print(exc.stdout, file=sys.stderr, end="" if exc.stdout.endswith("\n") else "\n")
        if exc.stderr:
            print(exc.stderr, file=sys.stderr, end="" if exc.stderr.endswith("\n") else "\n")
        raise


def cmd_workdisk_create(args: argparse.Namespace) -> int:
    matrix = load_matrix()
    errors = validate_matrix(matrix)
    if errors:
        for error in errors:
            print(f"[e2e][ERROR] {error}", file=sys.stderr)
        return 1
    if not args.all and not has_any_filter(args):
        print(
            "[e2e][ERROR] select at least one scenario/distro/backend/tag or pass --all",
            file=sys.stderr,
        )
        return 2

    selected = select_scenarios(matrix, args)
    base_root = Path(args.base_dir).expanduser().resolve() if args.base_dir else base_dir()
    if getattr(args, "ready", False):
        selected = filter_ready_scenarios(selected, base_root)
    if not selected:
        print("[e2e][ERROR] no scenarios selected", file=sys.stderr)
        return 2

    work_root = Path(args.work_dir).expanduser().resolve() if args.work_dir else work_dir()
    run_id = args.run_id or utc_timestamp()

    missing = [
        str(scenario_base_image_path(scenario, base_root))
        for scenario in selected
        if not scenario_base_image_path(scenario, base_root).exists()
    ]
    if missing:
        print("[e2e][ERROR] missing base image(s):", file=sys.stderr)
        for path in missing:
            print(f"  {path}", file=sys.stderr)
        print("Run ./e2e/run images and ./e2e/run base-plan first.", file=sys.stderr)
        return 3

    binary = qemu_img()
    created = []
    for scenario in selected:
        scenario_dir = scenario_work_dir(run_id, scenario, work_root)
        scenario_dir.mkdir(parents=True, exist_ok=True)
        disk_path = scenario_dir / "disk.qcow2"
        manifest_path = scenario_dir / "workdisk.json"
        if disk_path.exists():
            if not args.force:
                print(f"[e2e][ERROR] work disk already exists: {disk_path}", file=sys.stderr)
                return 4
            disk_path.unlink()

        base_path = scenario_base_image_path(scenario, base_root)
        if args.copy_mode == "overlay":
            cmd = [
                binary,
                "create",
                "-f",
                "qcow2",
                "-F",
                "qcow2",
                "-b",
                str(base_path),
                str(disk_path),
            ]
        else:
            cmd = [binary, "convert", "-O", "qcow2", str(base_path), str(disk_path)]
        run_qemu_img(cmd)

        manifest = {
            "run_id": run_id,
            "created_at": utc_iso_timestamp(),
            "scenario": scenario,
            "copy_mode": args.copy_mode,
            "base_image": str(base_path),
            "work_disk": str(disk_path),
        }
        manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True), encoding="utf-8")
        created.append({"scenario": scenario["id"], "disk": str(disk_path), "manifest": str(manifest_path)})

    if args.json:
        print(json.dumps({"run_id": run_id, "created": created}, indent=2, sort_keys=True))
    else:
        print(f"[e2e] run_id={run_id}")
        for item in created:
            print(f"[e2e] {item['scenario']}: {item['disk']}")
    return 0


def cmd_prepare_base(args: argparse.Namespace) -> int:
    matrix = load_matrix()
    errors = validate_matrix(matrix)
    if errors:
        for error in errors:
            print(f"[e2e][ERROR] {error}", file=sys.stderr)
        return 1

    specs = select_base_specs(matrix, args)
    if not args.all and not has_any_filter(args) and not getattr(args, "session", None):
        print("[e2e][ERROR] select distro/session filters or pass --all", file=sys.stderr)
        return 2
    if not specs:
        print("[e2e][ERROR] no base specs selected", file=sys.stderr)
        return 2

    base_root = Path(args.base_dir).expanduser().resolve() if args.base_dir else base_dir()
    if getattr(args, "missing", False):
        specs = filter_missing_base_specs(specs, base_root)
        if not specs:
            print("[e2e] no missing base images to prepare")
            return 0
    work_root = Path(args.work_dir).expanduser().resolve() if args.work_dir else work_dir()
    private_key = ensure_ssh_key(ssh_key_path())
    public_key = read_public_key(private_key)
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

        manifest_resume = base_image.exists() and not manifest_path.exists() and (build_dir / "base-ready.json").exists()
        if base_image.exists() and not args.force and not manifest_resume:
            print(f"[e2e][ERROR] base image already exists: {base_image}", file=sys.stderr)
            return 3

        if build_dir.exists() and args.force:
            safe_remove(build_dir)
        build_dir.mkdir(parents=True, exist_ok=True)

        if args.dry_run:
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

        iso_path = require_iso_path(distro)
        ssh_port = alloc_ssh_port(f"base-{distro['id']}-{spec['session']}")

        started_at = time.time()
        first_boot_run_dir = build_dir / "first-boot"
        first_boot_spec = QemuSpec(
            name=f"wbeam-base-{distro['id']}-{spec['session']}-first-boot",
            disk=install_disk,
            ssh_port=ssh_port,
            run_dir=first_boot_run_dir,
            cpu=coerce_int(defaults.get("cpu"), default=4),
            memory_mib=coerce_int(defaults.get("memory_mib"), default=8192),
            display=default_qemu_display(spec["session"], installer=False),
        )
        boot_assets: dict[str, str] = {}
        for name, filename in (("kernel", "vmlinuz"), ("initrd", "initrd")):
            asset = boot_dir / filename
            if asset.exists():
                boot_assets[name] = str(asset)
        resume_ready = install_disk.exists() and offline_provision_marker(build_dir).exists()
        if not resume_ready:
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
                if distro["family"] == "debian":
                    wait_debian_installer_complete(
                        proc,
                        installer_run_dir / "serial.log",
                        install_timeout,
                        name=f"{installer_spec.name} installer",
                    )
                    provision_debian_install_offline(
                        disk=install_disk,
                        build_dir=build_dir,
                        distro_id=distro["id"],
                        session=spec["session"],
                        ssh_user=distro["ssh_user"],
                        public_key=public_key,
                    )
                    write_text(offline_provision_marker(build_dir), "done\n")
                else:
                    wait_process(
                        proc,
                        install_timeout,
                        name=f"{installer_spec.name} installer",
                    )
            finally:
                if proc and proc.poll() is None:
                    proc.terminate()
                    try:
                        proc.wait(timeout=20)
                    except subprocess.TimeoutExpired:
                        proc.kill()
                        proc.wait(timeout=20)

        finalize_ready = (
            install_disk.exists()
            and offline_provision_marker(build_dir).exists()
            and (build_dir / "base-ready.json").exists()
            and (build_dir / "shutdown.log").exists()
        )
        if not finalize_ready:
            proc = start_qemu(first_boot_spec)
            try:
                wait_for_ssh(
                    distro["ssh_user"],
                    ssh_port,
                    private_key,
                    coerce_int(distro.get("boot_timeout_sec"), default=900),
                )
                sanity_log = build_dir / "sanity.log"
                ssh(
                    distro["ssh_user"],
                    ssh_port,
                    private_key,
                    base_sanity_command(session=spec["session"]),
                    log=sanity_log,
                )
                collect_guest_command_output(
                    user=distro["ssh_user"],
                    port=ssh_port,
                    key=private_key,
                    command="cat /var/lib/wbeam-e2e/base-ready.json",
                    output_path=build_dir / "base-ready.json",
                    check=True,
                )
                shutdown_guest(distro["ssh_user"], ssh_port, private_key, log=build_dir / "shutdown.log")
                wait_process(
                    proc,
                    coerce_int(distro.get("shutdown_timeout_sec"), default=180),
                    name=f"{first_boot_spec.name} shutdown",
                )
            except Exception:
                if proc.poll() is None:
                    proc.terminate()
                    try:
                        proc.wait(timeout=20)
                    except subprocess.TimeoutExpired:
                        proc.kill()
                        proc.wait(timeout=20)
                raise

        base_image.parent.mkdir(parents=True, exist_ok=True)
        if args.force or not base_image.exists():
            shutil.copy2(install_disk, base_image)
        write_json(
            manifest_path,
            {
                "distro": distro["id"],
                "session": spec["session"],
                "installer": distro["installer"],
                "iso_path": str(iso_path),
                "iso_sha256": sha256_file(iso_path),
                "seed_iso": str(seed_iso),
                "boot_assets": boot_assets,
                "created_at": utc_iso_timestamp(),
                "elapsed_sec": round(time.time() - started_at, 2),
                "build_dir": str(build_dir),
                "base_image": str(base_image),
            },
        )
        print(f"[e2e] prepared base image: {base_image}")
    return 0


def create_scenario_workdisk(
    *,
    scenario: dict,
    run_id: str,
    base_root: Path,
    work_root: Path,
    copy_mode: str,
    force: bool,
) -> tuple[Path, Path]:
    scenario_dir = scenario_work_dir(run_id, scenario, work_root)
    scenario_dir.mkdir(parents=True, exist_ok=True)
    disk_path = scenario_dir / "disk.qcow2"
    manifest_path = scenario_dir / "workdisk.json"
    if disk_path.exists():
        if not force:
            return disk_path, manifest_path
        disk_path.unlink()
    base_path = require_existing_file(scenario_base_image_path(scenario, base_root), what="base image")
    if copy_mode == "overlay":
        qemu_img_overlay(base_path, disk_path, log=scenario_dir / "qemu-img.log")
    else:
        qemu_img_full_copy(base_path, disk_path, log=scenario_dir / "qemu-img.log")
    write_json(
        manifest_path,
        {
            "run_id": run_id,
            "created_at": utc_iso_timestamp(),
            "scenario": scenario,
            "copy_mode": copy_mode,
            "base_image": str(base_path),
            "work_disk": str(disk_path),
        },
    )
    return disk_path, manifest_path


def run_one_scenario(
    *,
    matrix: dict,
    scenario: dict,
    run_id: str,
    base_root: Path,
    work_root: Path,
    report_root: Path,
    private_key: Path,
    copy_mode: str,
    force: bool,
    retain_workdisk: str,
    dry_run: bool,
) -> dict:
    defaults = matrix["defaults"]
    distro = distro_by_id(matrix, scenario["distro"])
    scenario_dir = scenario_work_dir(run_id, scenario, work_root)
    report_path = scenario_report_dir(report_root, run_id, scenario["id"])
    report_path.mkdir(parents=True, exist_ok=True)
    started = time.time()
    result = {
        "scenario": scenario["id"],
        "distro": scenario["distro"],
        "session": scenario["session"],
        "backend": scenario["backend"],
        "status": "fail",
        "phase": "init",
        "reason": "",
        "report_dir": str(report_path),
        "work_dir": str(scenario_dir),
    }

    if dry_run:
        write_json(report_path / "dry-run.json", {"scenario": scenario, "run_id": run_id})
        result["status"] = "pass"
        result["phase"] = "dry-run"
        result["duration_sec"] = 0
        return result

    runtime = scenario_runtime_config(matrix, scenario)
    disk_path, manifest_path = create_scenario_workdisk(
        scenario=scenario,
        run_id=run_id,
        base_root=base_root,
        work_root=work_root,
        copy_mode=copy_mode,
        force=force,
    )
    result["work_disk"] = str(disk_path)
    shutil.copy2(manifest_path, report_path / "workdisk.json")

    ssh_port = alloc_ssh_port(f"{run_id}-{scenario['id']}")
    guest_root = "/home/wbeam/WBeam"
    guest_report_root = f"{guest_root}/e2e/reports/{run_id}/{scenario['id']}"
    spec = QemuSpec(
        name=f"wbeam-{scenario['id']}",
        disk=disk_path,
        ssh_port=ssh_port,
        run_dir=scenario_dir,
        cpu=runtime["cpu"],
        memory_mib=runtime["memory_mib"],
        display=default_qemu_display(scenario["session"], installer=False),
    )
    proc = start_qemu(spec)
    try:
        result["phase"] = "boot"
        wait_for_ssh(
            distro["ssh_user"],
            ssh_port,
            private_key,
            coerce_int(distro.get("boot_timeout_sec"), default=900),
        )

        result["phase"] = "sync"
        rsync_to_guest(
            ROOT,
            guest_root,
            user=distro["ssh_user"],
            port=ssh_port,
            key=private_key,
            log=report_path / "rsync-to-guest.log",
            excludes=[
                ".git",
                "node_modules",
                "target",
                "e2e/work",
                "e2e/images",
                "e2e/reports",
            ],
        )
        ssh(
            distro["ssh_user"],
            ssh_port,
            private_key,
            f"mkdir -p {shell_quote(guest_report_root)}",
            log=report_path / "guest-mkdir.log",
        )

        env_prefix = build_guest_env(defaults, scenario, guest_report_dir=guest_report_root)

        result["phase"] = "install"
        ssh(
            distro["ssh_user"],
            ssh_port,
            private_key,
            f"cd {shell_quote(guest_root)} && {env_prefix} ./e2e/scripts/guest-install-wbeam.sh {shell_quote(scenario['backend'])}",
            log=report_path / "guest-install.log",
        )

        result["phase"] = "stream"
        ssh(
            distro["ssh_user"],
            ssh_port,
            private_key,
            f"cd {shell_quote(guest_root)} && {env_prefix} ./e2e/scripts/guest-stream-smoke.sh {shell_quote(scenario['backend'])} {scenario_duration(matrix, scenario)}",
            log=report_path / "guest-stream.log",
        )

        result["phase"] = "collect"
        rsync_from_guest(
            guest_report_root,
            report_path / "guest",
            user=distro["ssh_user"],
            port=ssh_port,
            key=private_key,
            log=report_path / "rsync-from-guest.log",
        )
        collect_guest_command_output(
            user=distro["ssh_user"],
            port=ssh_port,
            key=private_key,
            command="sudo -n journalctl -b --no-pager",
            output_path=report_path / "journal-system.log",
        )
        collect_guest_command_output(
            user=distro["ssh_user"],
            port=ssh_port,
            key=private_key,
            command="journalctl --user --no-pager",
            output_path=report_path / "journal-user.log",
        )
        collect_guest_command_output(
            user=distro["ssh_user"],
            port=ssh_port,
            key=private_key,
            command="dmesg || true",
            output_path=report_path / "dmesg.log",
        )
        collect_guest_command_output(
            user=distro["ssh_user"],
            port=ssh_port,
            key=private_key,
            command="ls -la /dev/dri || true",
            output_path=report_path / "dev-dri.log",
        )
        if scenario.get("requires_evdi"):
            collect_guest_command_output(
                user=distro["ssh_user"],
                port=ssh_port,
                key=private_key,
                command="bash /home/wbeam/WBeam/scripts/evdi-diagnose.sh --verbose || true",
                output_path=report_path / "evdi-diagnose.log",
            )

        result["status"] = "pass"
        result["phase"] = "done"
    except subprocess.CalledProcessError as exc:
        result["reason"] = f"command failed with exit {exc.returncode}"
    except Exception as exc:  # noqa: BLE001
        result["reason"] = str(exc)
    finally:
        try:
            shutdown_guest(distro["ssh_user"], ssh_port, private_key, log=report_path / "shutdown.log")
        except Exception:
            pass
        if proc.poll() is None:
            try:
                wait_process(proc, coerce_int(distro.get("shutdown_timeout_sec"), default=180), name=spec.name)
            except Exception:
                if proc.poll() is None:
                    proc.terminate()
                    try:
                        proc.wait(timeout=20)
                    except subprocess.TimeoutExpired:
                        proc.kill()
                        proc.wait(timeout=20)
        result["duration_sec"] = round(time.time() - started, 2)
        keep_disk = retain_workdisk == "always" or (retain_workdisk == "on-fail" and result["status"] != "pass")
        if not keep_disk:
            safe_remove(scenario_dir)
    if not result["reason"]:
        result["reason"] = ""
    return result


def cmd_run(args: argparse.Namespace) -> int:
    matrix = load_matrix()
    errors = validate_matrix(matrix)
    if errors:
        for error in errors:
            print(f"[e2e][ERROR] {error}", file=sys.stderr)
        return 1

    if not args.all and not has_any_filter(args):
        print("[e2e][ERROR] select scenarios or pass --all", file=sys.stderr)
        return 2
    selected = select_scenarios(matrix, args)
    base_root = Path(args.base_dir).expanduser().resolve() if args.base_dir else base_dir()
    if getattr(args, "ready", False):
        selected = filter_ready_scenarios(selected, base_root)
    if not selected:
        print("[e2e][ERROR] no scenarios selected", file=sys.stderr)
        return 2

    work_root = Path(args.work_dir).expanduser().resolve() if args.work_dir else work_dir()
    report_root = Path(args.report_dir).expanduser().resolve() if args.report_dir else report_dir()
    private_key = ensure_ssh_key(ssh_key_path())
    run_id = args.run_id or utc_timestamp()
    run_dir = init_run_report(report_root, run_id, selected, host=host_metadata())
    results: list[dict] = []

    for scenario in selected:
        print(f"[e2e] running {scenario['id']}")
        result = run_one_scenario(
            matrix=matrix,
            scenario=scenario,
            run_id=run_id,
            base_root=base_root,
            work_root=work_root,
            report_root=report_root,
            private_key=private_key,
            copy_mode=args.copy_mode,
            force=args.force,
            retain_workdisk=args.retain_workdisk,
            dry_run=args.dry_run,
        )
        results.append(result)
        finalize_run_report(run_dir, run_id, results)
        print(f"[e2e] {scenario['id']}: {result['status']}")
        if result["status"] != "pass" and args.stop_on_fail:
            break

    finalize_run_report(run_dir, run_id, results)
    print(run_dir)
    return 0 if all(result["status"] == "pass" for result in results) else 4


def cmd_report(args: argparse.Namespace) -> int:
    run_root = report_dir() / args.run_id
    summary_path = run_root / "summary.json"
    if not summary_path.exists():
        print(f"[e2e][ERROR] missing report summary: {summary_path}", file=sys.stderr)
        return 2
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    if args.json:
        print(json.dumps(summary, indent=2, sort_keys=True))
        return 0
    print(f"[e2e] run_id={summary['run_id']} status={summary['status']}")
    print(f"[e2e] passed={summary['scenarios_passed']} failed={summary['scenarios_failed']}")
    for failure in summary.get("failures", []):
        print(f"[e2e] fail {failure['scenario']} phase={failure['phase']} reason={failure['reason']}")
    print(run_root)
    return 0


def cmd_clean(args: argparse.Namespace) -> int:
    root = Path(args.work_dir).expanduser().resolve() if args.work_dir else work_dir()
    target = root / "runs" / args.run_id
    if not target.exists():
        print(f"[e2e][ERROR] run work dir does not exist: {target}", file=sys.stderr)
        return 2
    safe_remove(target)
    print(target)
    return 0


def cmd_status(args: argparse.Namespace) -> int:
    matrix = load_matrix()
    snapshot = status_snapshot(matrix)
    if args.json:
        print(json.dumps(snapshot, indent=2, sort_keys=True))
        return 0
    print(f"[e2e] progress={snapshot['percent']}%")
    for item in snapshot["items"]:
        state = "done" if item["done"] else "todo"
        print(f"[e2e] {state:4} {item['label']} ({item['weight']}%) - {item['details']}")
    print(
        f"[e2e] base_images={snapshot['base_specs_prepared']}/{snapshot['base_specs_total']} "
        f"iso_inputs={snapshot['iso_ready']}/{snapshot['iso_total']} "
        f"reports={snapshot['report_runs']}"
    )
    if snapshot["missing_iso_inputs"]:
        print("[e2e] missing ISO inputs:")
        for item in snapshot["missing_iso_inputs"]:
            current = item["value"] or "<unset>"
            print(f"[e2e]   {item['env']} ({item['distro']}) = {current}")
    if snapshot["missing_base_images"]:
        print("[e2e] missing base images:")
        for item in snapshot["missing_base_images"]:
            print(f"[e2e]   {item['distro']} / {item['session']} -> {item['image_path']}")
    if snapshot["next_commands"]:
        print("[e2e] next commands:")
        for command in snapshot["next_commands"]:
            print(f"[e2e]   {command}")
    return 0


def cmd_next(args: argparse.Namespace) -> int:
    matrix = load_matrix()
    snapshot = status_snapshot(matrix)
    if args.json:
        print(json.dumps({"progress_percent": snapshot["percent"], "next_commands": snapshot["next_commands"]}, indent=2, sort_keys=True))
        return 0
    for command in snapshot["next_commands"]:
        print(command)
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="WBeam local E2E helper")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("validate", help="Validate e2e/matrix.json").set_defaults(func=cmd_validate)
    sub.add_parser("env", help="Print ISO environment variable names").set_defaults(func=cmd_env)
    init_env_p = sub.add_parser("init-env", help="Create e2e/env.local from e2e/env.example")
    init_env_p.add_argument("--file", help="Target env file; defaults to e2e/env.local")
    init_env_p.add_argument("--force", action="store_true", help="Overwrite existing target env file")
    init_env_p.set_defaults(func=cmd_init_env)
    iso_sources_p = sub.add_parser("iso-sources", help="Print official ISO source pages for the matrix distros")
    iso_sources_p.add_argument("--json", action="store_true")
    iso_sources_p.set_defaults(func=cmd_iso_sources)
    env_shell_p = sub.add_parser("env-shell", help="Print shell export lines from e2e/env.local or another env file")
    env_shell_p.add_argument("--file", help="Path to env file; defaults to e2e/env.local")
    env_shell_p.set_defaults(func=cmd_env_shell)
    next_p = sub.add_parser("next", help="Print only the next commands needed to move e2e toward completion")
    next_p.add_argument("--json", action="store_true")
    next_p.set_defaults(func=cmd_next)
    status_p = sub.add_parser("status", help="Show evidence-based e2e progress and readiness")
    status_p.add_argument("--json", action="store_true")
    status_p.set_defaults(func=cmd_status)

    images_p = sub.add_parser("images", help="List required clean base images")
    add_filters(images_p)
    images_p.add_argument("--base-dir", help="Override base image root")
    images_p.add_argument("--json", action="store_true")
    images_p.set_defaults(func=cmd_images)

    list_p = sub.add_parser("list", help="List scenarios")
    add_filters(list_p)
    list_p.add_argument("--json", action="store_true")
    list_p.set_defaults(func=cmd_list)

    plan_p = sub.add_parser("plan", help="Show selected scenario execution plan")
    add_filters(plan_p)
    plan_p.add_argument("--json", action="store_true")
    plan_p.set_defaults(func=cmd_plan)

    base_plan_p = sub.add_parser("base-plan", help="Show clean base image creation plan")
    add_filters(base_plan_p)
    base_plan_p.add_argument("--session", action="append", help="Select base session; repeatable")
    base_plan_p.add_argument("--base-dir", help="Override base image root")
    base_plan_p.add_argument("--work-dir", help="Override work root")
    base_plan_p.add_argument("--json", action="store_true")
    base_plan_p.set_defaults(func=cmd_base_plan)

    workdisk_p = sub.add_parser("workdisk-create", help="Create disposable test disk from base image")
    add_filters(workdisk_p)
    workdisk_p.add_argument("--all", action="store_true", help="Create work disks for all selected scenarios")
    workdisk_p.add_argument("--run-id", help="Run id; default is UTC timestamp")
    workdisk_p.add_argument("--base-dir", help="Override base image root")
    workdisk_p.add_argument("--work-dir", help="Override work root")
    workdisk_p.add_argument("--ready", action="store_true", help="Only use scenarios whose base image already exists")
    workdisk_p.add_argument("--copy-mode", choices=("overlay", "full"), default="overlay")
    workdisk_p.add_argument("--force", action="store_true", help="Replace existing generated work disk")
    workdisk_p.add_argument("--json", action="store_true")
    workdisk_p.set_defaults(func=cmd_workdisk_create)

    report_p = sub.add_parser("report-init", help="Create an empty report directory")
    add_filters(report_p)
    report_p.add_argument("--run-id")
    report_p.set_defaults(func=cmd_report_init)

    prepare_base_p = sub.add_parser("prepare-base", help="Build a clean base image from installer ISO")
    add_filters(prepare_base_p)
    prepare_base_p.add_argument("--session", action="append", help="Select base session; repeatable")
    prepare_base_p.add_argument("--all", action="store_true", help="Prepare all selected base images")
    prepare_base_p.add_argument("--base-dir", help="Override base image root")
    prepare_base_p.add_argument("--work-dir", help="Override work root")
    prepare_base_p.add_argument("--missing", action="store_true", help="Only prepare base images that are still missing")
    prepare_base_p.add_argument("--force", action="store_true", help="Replace existing build dir/base image")
    prepare_base_p.add_argument("--dry-run", action="store_true")
    prepare_base_p.set_defaults(func=cmd_prepare_base)

    run_p = sub.add_parser("run", help="Run one or more scenarios on disposable VM disks")
    add_filters(run_p)
    run_p.add_argument("--all", action="store_true", help="Run all selected scenarios")
    run_p.add_argument("--run-id", help="Run id; default is UTC timestamp")
    run_p.add_argument("--base-dir", help="Override base image root")
    run_p.add_argument("--work-dir", help="Override work root")
    run_p.add_argument("--report-dir", help="Override report root")
    run_p.add_argument("--ready", action="store_true", help="Only run scenarios whose base image already exists")
    run_p.add_argument("--copy-mode", choices=("overlay", "full"), default="overlay")
    run_p.add_argument("--retain-workdisk", choices=("never", "on-fail", "always"), default="on-fail")
    run_p.add_argument("--force", action="store_true", help="Replace existing generated work disk")
    run_p.add_argument("--stop-on-fail", action="store_true", help="Stop after first failing scenario")
    run_p.add_argument("--dry-run", action="store_true")
    run_p.set_defaults(func=cmd_run)

    summary_p = sub.add_parser("report", help="Show summary for a previous run")
    summary_p.add_argument("--run-id", required=True)
    summary_p.add_argument("--json", action="store_true")
    summary_p.set_defaults(func=cmd_report)

    clean_p = sub.add_parser("clean", help="Remove disposable work dir for a run id")
    clean_p.add_argument("--run-id", required=True)
    clean_p.add_argument("--work-dir", help="Override work root")
    clean_p.set_defaults(func=cmd_clean)

    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
