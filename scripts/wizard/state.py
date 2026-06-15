from __future__ import annotations

import json
import os
from dataclasses import asdict
from pathlib import Path
from typing import Any

from .model import StepResult


def write_json_atomic(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(payload, indent=2, sort_keys=True, default=str), encoding="utf-8")
    os.replace(tmp, path)


def append_jsonl(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(payload, sort_keys=True, default=str))
        fh.write("\n")


def read_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return {}


def step_result_payload(result: StepResult) -> dict[str, Any]:
    payload = asdict(result)
    payload["status"] = str(result.status)
    if result.log_path is not None:
        payload["log_path"] = str(result.log_path)
    return payload


def step_log_path(run_dir: Path, step_id: str) -> Path:
    return run_dir / "logs" / f"{step_id}.log"


def write_install_state(
    state_file: Path,
    *,
    run_id: str,
    backend: str,
    distro: str,
    steps: list[StepResult],
    control_port: int | None = None,
    stream_port: int | None = None,
    device_policy: str | None = None,
) -> None:
    write_json_atomic(
        state_file,
        {
            "schema": 2,
            "run_id": run_id,
            "backend": backend,
            "distro": distro,
            "control_port": control_port,
            "stream_port": stream_port,
            "device_policy": device_policy,
            "steps": [step_result_payload(step) for step in steps],
            "last_step": step_result_payload(steps[-1]) if steps else None,
        },
    )


def read_install_state(state_file: Path) -> dict[str, Any]:
    return read_json(state_file)
