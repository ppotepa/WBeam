#!/usr/bin/env python3
from __future__ import annotations

import argparse
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
SCRIPTS_DIR = ROOT / "scripts"
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

from wizard import run as wizard_run
from wizard.context import WizardContext
from wizard.engine import WizardEngine, select_steps
from wizard.events import EventSink
from wizard.model import StepDefinition


class DummyStep:
    def __init__(self, step_id: str, status: str = "ok") -> None:
        self.definition = StepDefinition(id=step_id, title=step_id.replace("_", " ").title())
        self._status = status

    def plan(self, ctx: WizardContext):  # pragma: no cover - contract helper
        return mock.Mock(commands=[], next_action="", summary=self.definition.title)

    def run(self, ctx: WizardContext):
        from wizard.model import StepResult, StepStatus

        return StepResult(
            id=self.definition.id,
            title=self.definition.title,
            status=getattr(StepStatus, self._status.upper()),
            summary=self._status,
            evidence={},
        )

    def validate(self, ctx: WizardContext):
        return self.run(ctx)


class WizardResumeTests(unittest.TestCase):
    def test_select_steps_supports_only_and_from_step(self) -> None:
        steps = [DummyStep("host_preflight"), DummyStep("system_deps"), DummyStep("host_build")]
        self.assertEqual([s.definition.id for s in select_steps(steps, only="system_deps")], ["system_deps"])
        self.assertEqual([s.definition.id for s in select_steps(steps, from_step="system_deps")], ["system_deps", "host_build"])

    def test_unknown_step_raises(self) -> None:
        steps = [DummyStep("host_preflight")]
        with self.assertRaises(RuntimeError):
            select_steps(steps, only="missing")
        with self.assertRaises(RuntimeError):
            select_steps(steps, from_step="missing")

    def test_retry_step_alias_routes_to_from_step(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            ctx = WizardContext(
                root_dir=ROOT,
                state_dir=tmp_path / "state",
                run_dir=tmp_path / "run",
                report_dir=None,
                backend="wayland",
                control_port=5001,
                stream_port=5000,
                yes=True,
                dry_run=True,
            )
            ctx.state_dir.mkdir(parents=True, exist_ok=True)
            ctx.run_dir.mkdir(parents=True, exist_ok=True)
            events = EventSink(jsonl_path=ctx.run_dir / "steps.jsonl")
            engine = WizardEngine(ctx=ctx, steps=[DummyStep("host_preflight"), DummyStep("system_deps")], events=events)
            with mock.patch.object(engine, "_apply_previous_state", wraps=engine._apply_previous_state) as apply_mock:
                engine.run(from_step=None, only="system_deps", resume=False)
            self.assertTrue(apply_mock.called)

    def test_resume_prefers_first_non_ok_step(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            state_dir = tmp_path / "state"
            state_dir.mkdir(parents=True, exist_ok=True)
            (state_dir / "install-state.json").write_text(
                '{"schema":2,"run_id":"1","backend":"wayland","distro":"fedora","steps":[{"id":"host_preflight","title":"Host","status":"ok"},{"id":"system_deps","title":"Deps","status":"fail"}],"last_step":{"id":"system_deps","title":"Deps","status":"fail"}}',
                encoding="utf-8",
            )
            ctx = WizardContext(
                root_dir=ROOT,
                state_dir=state_dir,
                run_dir=tmp_path / "run",
                report_dir=None,
                backend="wayland",
                control_port=5001,
                stream_port=5000,
                yes=True,
                dry_run=True,
            )
            ctx.run_dir.mkdir(parents=True, exist_ok=True)
            events = EventSink(jsonl_path=ctx.run_dir / "steps.jsonl")
            engine = WizardEngine(ctx=ctx, steps=[DummyStep("host_preflight"), DummyStep("system_deps"), DummyStep("host_build")], events=events)
            selected = engine._apply_previous_state(from_step=None, resume=True)
            self.assertEqual([s.definition.id for s in selected], ["system_deps", "host_build"])

    def test_resume_rejects_context_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            state_dir = tmp_path / "state"
            state_dir.mkdir(parents=True, exist_ok=True)
            (state_dir / "install-state.json").write_text(
                '{"schema":2,"run_id":"1","backend":"wayland","control_port":5001,"stream_port":5000,"device_policy":"optional","distro":"fedora","steps":[{"id":"host_preflight","title":"Host","status":"ok"}],"last_step":{"id":"host_preflight","title":"Host","status":"ok"}}',
                encoding="utf-8",
            )
            ctx = WizardContext(
                root_dir=ROOT,
                state_dir=state_dir,
                run_dir=tmp_path / "run",
                report_dir=None,
                backend="evdi",
                control_port=5001,
                stream_port=5000,
                yes=True,
                dry_run=True,
            )
            ctx.run_dir.mkdir(parents=True, exist_ok=True)
            events = EventSink(jsonl_path=ctx.run_dir / "steps.jsonl")
            engine = WizardEngine(ctx=ctx, steps=[DummyStep("host_preflight"), DummyStep("system_deps")], events=events)
            with self.assertRaisesRegex(RuntimeError, "does not match current context"):
                engine._apply_previous_state(from_step=None, resume=True)


if __name__ == "__main__":
    unittest.main()
