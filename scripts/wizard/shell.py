from __future__ import annotations

import shlex
import subprocess
from pathlib import Path
from typing import Mapping


def command_line(args: list[str]) -> str:
    return " ".join(shlex.quote(arg) for arg in args)


def run_logged(
    args: list[str],
    *,
    cwd: Path,
    log_path: Path,
    env: Mapping[str, str] | None = None,
    dry_run: bool = False,
    timeout_sec: int | None = None,
) -> int:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("a", encoding="utf-8") as log:
        log.write(f"RUN: {command_line(args)}\n")
        if dry_run:
            log.write("DRY-RUN: command not executed\n")
            return 0
        proc = subprocess.run(
            args,
            cwd=str(cwd),
            env=dict(env) if env is not None else None,
            stdout=log,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=timeout_sec,
            check=False,
        )
        log.write(f"EXIT: {proc.returncode}\n")
        return proc.returncode
