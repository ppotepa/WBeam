#!/usr/bin/env python3
from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPTS_DIR = ROOT / "scripts"
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

from wizard.context import WizardContext
from wizard.model import StepStatus
from wizard.steps.adb_probe import classify_adb, parse_adb_devices
from wizard.steps.host_build import host_binary_evidence
from wizard.steps.service import service_unit_content
from wizard.steps.stream_smoke import STREAM_MODES, start_url


class WizardStepHelpersTests(unittest.TestCase):
    def test_parse_adb_devices_and_classify(self) -> None:
        rows = parse_adb_devices("List of devices attached\nABC123 device\n")
        self.assertEqual(rows, [{"serial": "ABC123", "state": "device"}])
        status, summary, evidence, next_action = classify_adb(rows)
        self.assertEqual(status, StepStatus.OK)
        self.assertIn("ABC123", summary)
        self.assertEqual(evidence["selected_serial"], "ABC123")
        self.assertEqual(next_action, "")

    def test_stream_start_url_includes_capture_backend(self) -> None:
        ctx = WizardContext(
            root_dir=ROOT,
            state_dir=ROOT / ".tmp-state",
            run_dir=ROOT / ".tmp-run",
            report_dir=None,
            backend="wayland_portal",
            control_port=5001,
            stream_port=5000,
        )
        self.assertIn("capture_backend=wayland_portal", start_url(ctx, STREAM_MODES["wayland_portal"]))
        self.assertNotIn("capture_backend=", start_url(ctx, STREAM_MODES["benchmark_game"]))

    def test_service_unit_content_switches_backend(self) -> None:
        ctx = WizardContext(
            root_dir=ROOT,
            state_dir=ROOT / ".tmp-state",
            run_dir=ROOT / ".tmp-run",
            report_dir=None,
            backend="evdi",
            control_port=5001,
            stream_port=5000,
        )
        content = service_unit_content(ctx, "wbeam-daemon")
        self.assertIn("Environment=WBEAM_CAPTURE_BACKEND=evdi", content)

    def test_host_binary_evidence_reports_paths(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            evidence = host_binary_evidence(root)
        self.assertIn("server_path", evidence)
        self.assertIn("streamer_path", evidence)


if __name__ == "__main__":
    unittest.main()
