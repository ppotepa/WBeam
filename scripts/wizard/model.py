from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum
from pathlib import Path
from typing import Any, Protocol


class StepStatus(StrEnum):
    PENDING = "pending"
    RUNNING = "running"
    OK = "ok"
    WARN = "warn"
    FAIL = "fail"
    BLOCKED = "blocked"
    SKIPPED = "skipped"
    REBOOT_REQUIRED = "reboot_required"


@dataclass(frozen=True)
class StepPlan:
    id: str
    title: str
    summary: str
    commands: list[list[str]] = field(default_factory=list)
    expected_artifacts: list[str] = field(default_factory=list)
    risks: list[str] = field(default_factory=list)
    next_action: str = ""


@dataclass(frozen=True)
class StepResult:
    id: str
    title: str
    status: StepStatus
    summary: str
    log_path: Path | None = None
    started_at: str | None = None
    ended_at: str | None = None
    duration_sec: float | None = None
    exit_code: int | None = None
    next_action: str = ""
    evidence: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class StepDefinition:
    id: str
    title: str
    requires: tuple[str, ...] = ()
    timeout_sec: int = 900
    optional: bool = False


class WizardStep(Protocol):
    definition: StepDefinition

    def probe(self, ctx: "WizardContext") -> StepResult: ...

    def plan(self, ctx: "WizardContext") -> StepPlan: ...

    def run(self, ctx: "WizardContext") -> StepResult: ...

    def validate(self, ctx: "WizardContext") -> StepResult: ...
