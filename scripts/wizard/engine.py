from __future__ import annotations

import datetime as dt
import time
from dataclasses import dataclass, field
from pathlib import Path

from .context import WizardContext
from .events import EventSink
from .model import StepResult, StepStatus, WizardStep
from .report import write_wizard_summary
from .state import read_install_state, write_install_state
from .status import is_blocking


def utc_now() -> str:
    return dt.datetime.now(dt.UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


@dataclass
class WizardRuntime:
    selected_android_serial: str | None = None
    step_results: dict[str, StepResult] = field(default_factory=dict)


def select_steps(steps: list[WizardStep], *, from_step: str | None = None, only: str | None = None) -> list[WizardStep]:
    if only:
        filtered = [step for step in steps if step.definition.id == only]
        if not filtered:
            raise RuntimeError(f"unknown step: {only}")
        return filtered
    if from_step:
        ids = [step.definition.id for step in steps]
        if from_step not in ids:
            raise RuntimeError(f"unknown step: {from_step}")
        return steps[ids.index(from_step) :]
    return steps


class WizardEngine:
    def __init__(self, *, ctx: WizardContext, steps: list[WizardStep], events: EventSink) -> None:
        self.ctx = ctx
        self.steps = steps
        self.events = events
        self.results: list[StepResult] = []
        self.runtime = WizardRuntime()

    def _previous_results(self) -> dict[str, dict]:
        state = read_install_state(self.ctx.state_dir / "install-state.json")
        steps = state.get("steps") or []
        results: dict[str, dict] = {}
        for step in steps:
            if isinstance(step, dict) and step.get("id"):
                results[str(step["id"])] = step
        return results

    def _previous_state(self) -> dict:
        return read_install_state(self.ctx.state_dir / "install-state.json")

    def _state_matches_context(self, state: dict) -> bool:
        if not state:
            return False
        expected = {
            "backend": self.ctx.backend,
            "control_port": self.ctx.control_port,
            "stream_port": self.ctx.stream_port,
            "device_policy": self.ctx.device_policy,
        }
        for key, value in expected.items():
            if key in state and state.get(key) != value:
                return False
        return True

    def _apply_previous_state(self, from_step: str | None, resume: bool) -> list[WizardStep]:
        if not resume:
            return select_steps(self.steps, from_step=from_step)
        state = self._previous_state()
        if state and not self._state_matches_context(state):
            raise RuntimeError(
                "saved wizard state does not match current context; rerun without --resume or start a fresh run"
            )
        previous = self._previous_results()
        if not previous:
            return select_steps(self.steps, from_step=from_step)
        selected = from_step
        if not selected:
            for step in reversed(self.steps):
                prev = previous.get(step.definition.id)
                if not prev or str(prev.get("status")) != str(StepStatus.OK):
                    selected = step.definition.id
                    break
        if not selected:
            return []
        selected_index = [step.definition.id for step in self.steps].index(selected)
        for step in self.steps[:selected_index]:
            prev = previous.get(step.definition.id)
            if not prev or str(prev.get("status")) != str(StepStatus.OK):
                selected = step.definition.id
                selected_index = [step.definition.id for step in self.steps].index(selected)
                break
            try:
                validation = step.validate(self.ctx)
            except Exception:
                selected = step.definition.id
                break
            if validation.status != StepStatus.OK:
                selected = step.definition.id
                break
        return select_steps(self.steps, from_step=selected)

    def run(self, *, from_step: str | None = None, only: str | None = None, resume: bool = False) -> list[StepResult]:
        steps = self.steps
        if only or from_step or resume:
            steps = self._apply_previous_state(from_step=from_step, resume=resume)
            if only:
                steps = select_steps(steps, only=only)
        for step in steps:
            definition = step.definition
            log_path = self.ctx.run_dir / "logs" / f"{definition.id}.log"
            self.events.step_started(definition.id, definition.title, log_path=str(log_path))
            started = time.time()
            try:
                result = step.run(self.ctx)
            except Exception as exc:  # noqa: BLE001
                result = StepResult(
                    id=definition.id,
                    title=definition.title,
                    status=StepStatus.FAIL,
                    summary=str(exc),
                    log_path=log_path,
                    duration_sec=round(time.time() - started, 2),
                    next_action=f"Read the step log and rerun with --from-step {definition.id}",
                )
            if result.log_path is None:
                result = StepResult(
                    id=result.id,
                    title=result.title,
                    status=result.status,
                    summary=result.summary,
                    log_path=log_path,
                    started_at=result.started_at,
                    ended_at=result.ended_at,
                    duration_sec=result.duration_sec,
                    exit_code=result.exit_code,
                    next_action=result.next_action,
                    evidence=result.evidence,
                )
            self.results.append(result)
            self.runtime.step_results[definition.id] = result
            self.events.step_finished(result)
            write_install_state(
                self.ctx.state_dir / "install-state.json",
                run_id=self.ctx.run_dir.name,
                backend=self.ctx.backend,
                distro=str(result.evidence.get("distro", "unknown")),
                steps=self.results,
                control_port=self.ctx.control_port,
                stream_port=self.ctx.stream_port,
                device_policy=self.ctx.device_policy,
            )
            write_install_state(
                self.ctx.run_dir / "install-state.json",
                run_id=self.ctx.run_dir.name,
                backend=self.ctx.backend,
                distro=str(result.evidence.get("distro", "unknown")),
                steps=self.results,
                control_port=self.ctx.control_port,
                stream_port=self.ctx.stream_port,
                device_policy=self.ctx.device_policy,
            )
            if is_blocking(result.status):
                self.events.blocked(result)
                break
            if result.status == StepStatus.REBOOT_REQUIRED:
                break
        write_wizard_summary(self.ctx.run_dir, run_id=self.ctx.run_dir.name, results=self.results)
        return self.results
