#!/usr/bin/env python3
from __future__ import annotations

import sys
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPTS_DIR = ROOT / "scripts"
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

from wizard import status
from wizard.model import StepStatus


class WizardStatusTests(unittest.TestCase):
    def test_terminal_statuses(self) -> None:
        for item in (StepStatus.OK, StepStatus.WARN, StepStatus.SKIPPED):
            self.assertTrue(status.is_terminal(item))

    def test_non_terminal_statuses(self) -> None:
        for item in (StepStatus.RUNNING, StepStatus.PENDING):
            self.assertFalse(status.is_terminal(item))

    def test_blocking_statuses(self) -> None:
        self.assertTrue(status.is_blocking(StepStatus.FAIL))
        self.assertTrue(status.is_blocking(StepStatus.BLOCKED))
        self.assertTrue(status.is_blocking(StepStatus.REBOOT_REQUIRED))
        self.assertFalse(status.is_blocking(StepStatus.WARN))

    def test_scenario_status_from_steps(self) -> None:
        self.assertEqual(status.scenario_status_from_steps([StepStatus.OK, StepStatus.OK]), "pass")
        self.assertEqual(status.scenario_status_from_steps([StepStatus.OK, StepStatus.WARN]), "pass")
        self.assertEqual(status.scenario_status_from_steps([StepStatus.OK, StepStatus.BLOCKED]), "blocked")
        self.assertEqual(status.scenario_status_from_steps([StepStatus.OK, StepStatus.FAIL]), "fail")


if __name__ == "__main__":
    unittest.main()
