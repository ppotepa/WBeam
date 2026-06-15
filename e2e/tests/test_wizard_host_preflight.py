#!/usr/bin/env python3
from __future__ import annotations

import tempfile
import sys
import unittest
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
SCRIPTS_DIR = ROOT / "scripts"
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

from wizard.context import WizardContext
from wizard.model import StepStatus
from wizard.steps.host_preflight import classify_host_probe, collect_host_probe, host_probe_next_action


class HostPreflightTests(unittest.TestCase):
    def test_classify_evdi_secure_boot_requires_reboot(self) -> None:
        evidence = {
            "distro": "fedora",
            "distro_version": "43",
            "encoder": "h264:x264enc",
            "evdi": "secure-boot-enabled",
        }
        self.assertEqual(classify_host_probe(evidence, backend="evdi"), StepStatus.REBOOT_REQUIRED)
        self.assertIn("MOK", host_probe_next_action(evidence, backend="evdi"))

    def test_classify_missing_encoder_warns(self) -> None:
        evidence = {"distro": "fedora", "distro_version": "43", "encoder": "missing", "evdi": "module-not-loaded"}
        self.assertEqual(classify_host_probe(evidence, backend="wayland"), StepStatus.WARN)
        self.assertIn("encoder", host_probe_next_action(evidence, backend="wayland"))

    def test_collect_host_probe_uses_fake_os_release(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            os_release = Path(tmp) / "os-release"
            os_release.write_text('ID=fedora\nVERSION_ID="43"\n', encoding="utf-8")
            ctx = WizardContext(
                root_dir=ROOT,
                state_dir=Path(tmp) / "state",
                run_dir=Path(tmp) / "run",
                report_dir=None,
                backend="wayland",
                control_port=5001,
                stream_port=5000,
            )
            with mock.patch("wizard.steps.host_preflight.shutil.which", side_effect=lambda name: {"dnf": "/usr/bin/dnf", "gst-inspect-1.0": "/usr/bin/gst-inspect-1.0", "xdg-desktop-portal": "/usr/bin/xdg-desktop-portal"}.get(name)), mock.patch(
                "wizard.steps.host_preflight._socket_state", side_effect=lambda _: "available"
            ), mock.patch("wizard.steps.host_preflight._service_state", return_value="active"), mock.patch(
                "wizard.steps.host_preflight._encoder_state", return_value="h264:x264enc"
            ), mock.patch("wizard.steps.host_preflight._evdi_state", return_value="module-not-loaded"), mock.patch(
                "wizard.steps.host_preflight.os.getgroups", return_value=[]
            ), mock.patch("wizard.steps.host_preflight.platform.machine", return_value="x86_64"), mock.patch(
                "wizard.steps.host_preflight._session_type", return_value="wayland"
            ):
                evidence = collect_host_probe(ctx, os_release_path=os_release)
        self.assertEqual(evidence["distro"], "fedora")
        self.assertEqual(evidence["distro_version"], "43")
        self.assertEqual(evidence["arch"], "x86_64")


if __name__ == "__main__":
    unittest.main()
