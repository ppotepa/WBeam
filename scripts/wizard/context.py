from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path


@dataclass(frozen=True)
class WizardContext:
    root_dir: Path
    state_dir: Path
    run_dir: Path
    report_dir: Path | None
    backend: str
    control_port: int
    stream_port: int
    android_serial: str | None = None
    yes: bool = False
    dry_run: bool = False
    skip_system_deps: bool = False
    skip_build: bool = False
    skip_service: bool = False
    skip_device: bool = False
    device_only: bool = False
    device_policy: str = "optional"
    json_events: bool = False
    env: dict[str, str] = field(default_factory=dict)
