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

# Force unbuffered output for the entire project
def print(*args, **kwargs):
    kwargs["flush"] = True
    import builtins
    builtins.print(*args, **kwargs)


RSYNC_EXCLUDES = (
    ".git/",
    ".pytest_cache/",
    "__pycache__/",
    "target/",
    "node_modules/",
    "desktop/node_modules/",
    "desktop/apps/desktop-tauri/node_modules/",
    "android/.gradle/",
    "android/app/build/",
    "e2e/images/",
    "e2e/work/",
    "e2e/reports/",
    "e2e/*.qcow2",
    "e2e/*.iso",
)

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
    live: bool = False,
) -> subprocess.CompletedProcess[str]:
    if log:
        log.parent.mkdir(parents=True, exist_ok=True)
        with log.open("a", encoding="utf-8") as fh:
            fh.write(f"--- {time.ctime()} EXEC: {shlex.join(cmd)}\n")
            fh.flush()
            if live:
                proc_live = subprocess.Popen(
                    cmd,
                    cwd=cwd,
                    text=True,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                )
                assert proc_live.stdout is not None
                for line in proc_live.stdout:
                    fh.write(line)
                    fh.flush()
                    print(line, end="")
                returncode = proc_live.wait()
                proc = subprocess.CompletedProcess(cmd, returncode)
            else:
                proc = subprocess.run(cmd, cwd=cwd, text=True, stdout=fh, stderr=subprocess.STDOUT)
    else:
        proc = subprocess.run(cmd, cwd=cwd, text=True, capture_output=not live)
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
        ]
    )
    return private_key


def read_public_key(path: Path) -> str:
    public_key = Path(f"{path}.pub")
    return public_key.read_text(encoding="utf-8").strip()


@dataclass
class QemuSpec:
    name: str
    disk: Path
    ssh_port: int
    run_dir: Path
    cpu: int = 4
    memory_mib: int = 8192
    iso: Path | None = None
    seed_iso: Path | None = None
    kernel: Path | None = None
    initrd: Path | None = None
    append: str | None = None
    extra_args: tuple[str, ...] = ()
    display: str = "none"
    host_forwards: tuple[tuple[int, int], ...] = ()


def qemu_command(spec: QemuSpec) -> list[str]:
    hostfwds = [f"hostfwd=tcp:127.0.0.1:{spec.ssh_port}-:22"]
    for host_port, guest_port in spec.host_forwards:
        hostfwds.append(f"hostfwd=tcp:127.0.0.1:{host_port}-:{guest_port}")
    cmd = [
        require_tool("qemu-system-x86_64"),
        "-name",
        spec.name,
        "-m",
        str(spec.memory_mib),
        "-smp",
        str(spec.cpu),
        "-enable-kvm",
        "-cpu",
        "host",
        "-drive",
        f"file={spec.disk},format=qcow2,if=virtio",
        "-netdev",
        "user,id=n1," + ",".join(hostfwds),
        "-device",
        "virtio-net-pci,netdev=n1",
        "-display",
        spec.display,
        "-serial",
        f"file:{spec.run_dir / 'serial.log'}",
    ]
    if spec.iso:
        cmd += ["-cdrom", str(spec.iso)]
    if spec.seed_iso:
        cmd += [
            "-drive",
            f"file={spec.seed_iso},format=raw,if=virtio,readonly=on",
        ]
    if spec.kernel:
        cmd += ["-kernel", str(spec.kernel)]
    if spec.initrd:
        cmd += ["-initrd", str(spec.initrd)]
    if spec.append:
        cmd += ["-append", spec.append]
    cmd += list(spec.extra_args)
    return cmd


def start_qemu(spec: QemuSpec) -> subprocess.Popen[str]:
    spec.run_dir.mkdir(parents=True, exist_ok=True)
    log_path = spec.run_dir / "qemu.log"
    log = log_path.open("a", encoding="utf-8")
    log.write(f"--- {time.ctime()} START: {shlex.join(qemu_command(spec))}\n")
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


def wait_for_ssh(user: str, port: int, key: Path, timeout_sec: int) -> None:
    start = time.time()
    cmd = [
        require_tool("ssh"),
        "-p",
        str(port),
        "-i",
        str(key),
        "-o",
        "BatchMode=yes",
        "-o",
        "ConnectTimeout=5",
        "-o",
        "StrictHostKeyChecking=no",
        "-o",
        "UserKnownHostsFile=/dev/null",
        f"{user}@127.0.0.1",
        "exit 0",
    ]
    while time.time() - start < timeout_sec:
        proc = subprocess.run(cmd, capture_output=True, text=True)
        if proc.returncode == 0:
            return
        time.sleep(5)
    raise TimeoutError(f"SSH did not become available at 127.0.0.1:{port} within {timeout_sec}s")


def ssh(
    user: str,
    port: int,
    key: Path,
    command: str,
    *,
    log: Path | None = None,
    check: bool = True,
    live: bool = False,
) -> subprocess.CompletedProcess[str]:
    cmd = [
        require_tool("ssh"),
        "-p",
        str(port),
        "-i",
        str(key),
        "-o",
        "StrictHostKeyChecking=no",
        "-o",
        "UserKnownHostsFile=/dev/null",
        f"{user}@127.0.0.1",
        command,
    ]
    return run_cmd(cmd, log=log, check=check, live=live)


def rsync_to_guest(
    user: str,
    port: int,
    key: Path,
    src: Path,
    dest_str: str,
    *,
    log: Path | None = None,
    check: bool = True,
    live: bool = False,
) -> subprocess.CompletedProcess[str]:
    src_arg = f"{src}/" if src.is_dir() else str(src)
    cmd = [
        require_tool("rsync"),
        "-avz",
        *(f"--exclude={pattern}" for pattern in RSYNC_EXCLUDES),
        "-e",
        f"ssh -p {port} -i {key} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null",
        src_arg,
        f"{user}@127.0.0.1:{dest_str}",
    ]
    return run_cmd(cmd, log=log, check=check, live=live)


def rsync_from_guest(
    user: str,
    port: int,
    key: Path,
    src_str: str,
    dest: Path,
    *,
    log: Path | None = None,
    check: bool = True,
    live: bool = False,
) -> subprocess.CompletedProcess[str]:
    cmd = [
        require_tool("rsync"),
        "-avz",
        "-e",
        f"ssh -p {port} -i {key} -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null",
        f"{user}@127.0.0.1:{src_str}",
        str(dest),
    ]
    return run_cmd(cmd, log=log, check=check, live=live)


def shutdown_guest(user: str, port: int, key: Path, *, log: Path | None = None, live: bool = False) -> None:
    ssh(user, port, key, "sudo -n systemctl poweroff --no-wall || sudo -n poweroff -f", log=log, check=False, live=live)
