from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from ..context import WizardContext
from ..model import StepPlan, StepResult


@dataclass(frozen=True)
class DistroInfo:
    id: str
    id_like: str
    version: str
    package_manager: str


class SystemProvider(Protocol):
    name: str

    def probe(self, ctx: WizardContext) -> StepResult: ...

    def plan_install(self, ctx: WizardContext, distro: DistroInfo) -> StepPlan: ...

    def install(self, ctx: WizardContext, distro: DistroInfo, log_path: Path) -> StepResult: ...

    def validate(self, ctx: WizardContext, distro: DistroInfo) -> StepResult: ...
