#!/usr/bin/env python3
from __future__ import annotations

import os
import shlex
import shutil
import socket
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path


def require_tool(name: str) -> str:
    path = shutil.which(name)
    if not path:
        raise RuntimeError(f"missing required tool: {name}")
    return path


def run_cmd(
    cmd: list[str],
    *,
    cwd: Path | None = None,
    log: Path | None = None,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    if log:
        log.parent.mkdir(parents=True, exist_ok=True)
        with log.open("a", encoding="utf-8") as fh:
            fh.write(f"$ {shlex.join(cmd)}\n")
            proc = subprocess.run(cmd, cwd=cwd, text=True, stdout=fh, stderr=subprocess.STDOUT)
    else:
        proc = subprocess.run(cmd, cwd=cwd, text=True, capture_output=True)
    if check and proc.returncode != 0:
        if not log:
            if proc.stdout:
                print(proc.stdout, end="")
            if proc.stderr:
                print(proc.stderr, end="")
        raise subprocess.CalledProcessError(proc.returncode, cmd)
    return proc


def qemu_img_create(path: Path, size_gib: int, *, log: Path | None = None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    run_cmd([require_tool("qemu-img"), "create", "-f", "qcow2", str(path), f"{size_gib}G"], log=log)


def qemu_img_overlay(base: Path, target: Path, *, log: Path | None = None) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    run_cmd(
        [
            require_tool("qemu-img"),
            "create",
            "-f",
            "qcow2",
            "-F",
            "qcow2",
            "-b",
            str(base),
            str(target),
        ],
        log=log,
    )


def qemu_img_full_copy(base: Path, target: Path, *, log: Path | None = None) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    run_cmd([require_tool("qemu-img"), "convert", "-O", "qcow2", str(base), str(target)], log=log)


def alloc_ssh_port(seed: str, start: int = 22000, span: int = 2000) -> int:
    offset = sum(seed.encode("utf-8")) % span
    for i in range(span):
        port = start + ((offset + i) % span)
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                sock.bind(("127.0.0.1", port))
            except OSError:
                continue
            return port
    raise RuntimeError("could not allocate localhost SSH port")


def ensure_ssh_key(path: Path) -> Path:
    private_key = path.expanduser().resolve()
    public_key = Path(f"{private_key}.pub")
    if private_key.exists() and public_key.exists():
        return private_key
    private_key.parent.mkdir(parents=True, exist_ok=True)
    run_cmd(
        [
            require_tool("ssh-keygen"),
            "-t",
            "ed25519",
            "-N",
            "",
            "-f",
            str(private_key),
            "-C",
            "wbeam-e2e",
        ]
    )
    return private_key


def read_public_key(private_key: Path) -> str:
    public_key = Path(f"{private_key}.pub")
    return public_key.read_text(encoding="utf-8").strip()


@dataclass(frozen=True)
class QemuSpec:
    name: str
    disk: Path
    ssh_port: int
    run_dir: Path
    cpu: int = 4
    memory_mib: int = 8192
    iso: Path | None = None
    seed_iso: Path | None = None
    boot_from_iso: bool = False
    display: str = "none"
    kernel: Path | None = None
    initrd: Path | None = None
    append: str | None = None
    extra_args: tuple[str, ...] = ()


def qemu_command(spec: QemuSpec) -> list[str]:
    qemu = require_tool("qemu-system-x86_64")
    args = [
        qemu,
        "-name",
        spec.name,
        "-machine",
        "accel=kvm:tcg",
        "-cpu",
        "host",
        "-smp",
        str(spec.cpu),
        "-m",
        str(spec.memory_mib),
        "-drive",
        f"file={spec.disk},if=virtio,format=qcow2",
        "-netdev",
        f"user,id=n0,hostfwd=tcp:127.0.0.1:{spec.ssh_port}-:22",
        "-device",
        "virtio-net-pci,netdev=n0",
        "-serial",
        f"file:{spec.run_dir / 'serial.log'}",
        "-monitor",
        f"unix:{spec.run_dir / 'qemu-monitor.sock'},server,nowait",
        "-display",
        spec.display,
    ]
    if spec.iso:
        args.extend(["-cdrom", str(spec.iso)])
    if spec.seed_iso:
        args.extend(["-drive", f"file={spec.seed_iso},media=cdrom,readonly=on"])
    if spec.kernel:
        args.extend(["-kernel", str(spec.kernel)])
    if spec.initrd:
        args.extend(["-initrd", str(spec.initrd)])
    if spec.append:
        args.extend(["-append", spec.append])
    if spec.boot_from_iso:
        args.extend(["-boot", "d"])
    args.extend(spec.extra_args)
    return args


def start_qemu(spec: QemuSpec) -> subprocess.Popen[str]:
    spec.run_dir.mkdir(parents=True, exist_ok=True)
    log_path = spec.run_dir / "qemu.log"
    log = log_path.open("a", encoding="utf-8")
    log.write(f"$ {shlex.join(qemu_command(spec))}\n")
    log.flush()
    return subprocess.Popen(qemu_command(spec), stdout=log, stderr=subprocess.STDOUT, text=True)


def wait_process(proc: subprocess.Popen[str], timeout_sec: int, *, name: str) -> int:
    try:
        return proc.wait(timeout=timeout_sec)
    except subprocess.TimeoutExpired as exc:
        proc.terminate()
        try:
            proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=20)
        raise TimeoutError(f"{name} did not exit within {timeout_sec}s") from exc


def ssh_base_cmd(user: str, port: int, key: Path) -> list[str]:
    return [
        require_tool("ssh"),
        "-i",
        str(key),
        "-p",
        str(port),
        "-o",
        "BatchMode=yes",
        "-o",
        "StrictHostKeyChecking=no",
        "-o",
        "UserKnownHostsFile=/dev/null",
        "-o",
        "ConnectTimeout=5",
        f"{user}@127.0.0.1",
    ]


def wait_for_ssh(user: str, port: int, key: Path, timeout_sec: int) -> None:
    deadline = time.time() + timeout_sec
    last_error = "ssh not attempted"
    while time.time() < deadline:
        proc = subprocess.run(
            ssh_base_cmd(user, port, key) + ["true"],
            text=True,
            capture_output=True,
        )
        if proc.returncode == 0:
            return
        last_error = (proc.stderr or proc.stdout or f"exit {proc.returncode}").strip()
        time.sleep(2)
    raise TimeoutError(f"SSH did not become ready on port {port}: {last_error}")


def ssh(
    user: str,
    port: int,
    key: Path,
    command: str,
    *,
    log: Path | None = None,
    check: bool = True,
) -> subprocess.CompletedProcess[str]:
    return run_cmd(ssh_base_cmd(user, port, key) + [command], log=log, check=check)


def rsync_to_guest(
    source: Path,
    dest: str,
    *,
    user: str,
    port: int,
    key: Path,
    log: Path | None = None,
    excludes: list[str] | None = None,
) -> None:
    rsync = require_tool("rsync")
    cmd = [
        rsync,
        "-a",
        "--delete",
        "-e",
        " ".join(shlex.quote(x) for x in ssh_base_cmd(user, port, key)[:-1]),
    ]
    for pattern in excludes or []:
        cmd.extend(["--exclude", pattern])
    cmd.extend([f"{source}/", f"{user}@127.0.0.1:{dest}"])
    run_cmd(cmd, log=log)


def rsync_from_guest(
    source: str,
    dest: Path,
    *,
    user: str,
    port: int,
    key: Path,
    log: Path | None = None,
) -> None:
    dest.mkdir(parents=True, exist_ok=True)
    rsync = require_tool("rsync")
    cmd = [
        rsync,
        "-a",
        "-e",
        " ".join(shlex.quote(x) for x in ssh_base_cmd(user, port, key)[:-1]),
        f"{user}@127.0.0.1:{source.rstrip('/')}/",
        f"{dest}/",
    ]
    run_cmd(cmd, log=log, check=False)


def shutdown_guest(user: str, port: int, key: Path, *, log: Path | None = None) -> None:
    ssh(user, port, key, "sudo -n systemctl poweroff --no-wall || sudo -n poweroff -f", log=log, check=False)
