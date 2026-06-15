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
from wizard.providers.apt import AptProvider
from wizard.providers.detect import detect_distro
from wizard.providers.fedora import FedoraProvider
from wizard.providers.base import DistroInfo


class ProviderTests(unittest.TestCase):
    def test_detect_distro_from_fake_os_release(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            os_release = Path(tmp) / "os-release"
            os_release.write_text('ID=ubuntu\nVERSION_ID="24.04"\n', encoding="utf-8")
            with mock.patch("wizard.providers.detect.shutil.which", side_effect=lambda name: "/usr/bin/apt-get" if name == "apt-get" else None):
                distro = detect_distro(os_release_path=os_release)
        self.assertEqual(distro.id, "ubuntu")
        self.assertEqual(distro.package_manager, "apt")

    def test_fedora_plan_includes_evdi_flag(self) -> None:
        ctx = WizardContext(
            root_dir=ROOT,
            state_dir=ROOT / ".tmp-state",
            run_dir=ROOT / ".tmp-run",
            report_dir=None,
            backend="evdi",
            control_port=5001,
            stream_port=5000,
        )
        provider = FedoraProvider()
        plan = provider.plan_install(ctx, DistroInfo("fedora", "", "43", "dnf"))
        self.assertIn("--with-evdi", plan.commands[0])

    def test_apt_dry_run_does_not_call_runner(self) -> None:
        ctx = WizardContext(
            root_dir=ROOT,
            state_dir=ROOT / ".tmp-state",
            run_dir=ROOT / ".tmp-run",
            report_dir=None,
            backend="wayland",
            control_port=5001,
            stream_port=5000,
            dry_run=True,
        )
        provider = AptProvider()
        with tempfile.TemporaryDirectory() as tmp:
            log_path = Path(tmp) / "apt.log"
            with mock.patch("wizard.providers.apt.run_logged", side_effect=AssertionError("must not be called")):
                result = provider.install(ctx, DistroInfo("ubuntu", "", "24.04", "apt"), log_path)
        self.assertEqual(result.status, StepStatus.OK)
        self.assertIn("DRY-RUN", result.summary)


if __name__ == "__main__":
    unittest.main()
