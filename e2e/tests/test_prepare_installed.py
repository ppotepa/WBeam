#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


TEST_DIR = Path(__file__).resolve().parent
SCRIPTS_DIR = TEST_DIR.parent / "scripts"
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import runner  # noqa: E402


class PrepareInstalledHelpersTests(unittest.TestCase):
    def test_install_backend_for_session_gnome_wayland_is_wayland(self) -> None:
        self.assertEqual(runner.install_backend_for_session("gnome-wayland"), "wayland")

    def test_install_backend_for_session_gnome_xorg_is_x11(self) -> None:
        self.assertEqual(runner.install_backend_for_session("gnome-xorg"), "x11")

    def test_install_backend_for_session_headless_is_benchmark_game(self) -> None:
        self.assertEqual(runner.install_backend_for_session("headless"), "benchmark_game")

    def test_install_backend_for_scenario_backend_wayland_portal_maps_to_wayland(self) -> None:
        self.assertEqual(runner.install_backend_for_scenario_backend("wayland_portal"), "wayland")

    def test_install_backend_for_scenario_backend_evdi_maps_to_evdi(self) -> None:
        self.assertEqual(runner.install_backend_for_scenario_backend("evdi"), "evdi")

    def test_scenario_workdisk_path_contains_run_id_and_scenario_id(self) -> None:
        path = runner.scenario_workdisk_path(Path("/tmp/work"), "RID", "scenario-a")
        self.assertEqual(path, Path("/tmp/work/runs/RID/scenario-a/disk.qcow2"))

    def test_runner_report_path_is_aggregated_per_run(self) -> None:
        path = runner.runner_report_path(Path("/tmp/reports"), "RID")
        self.assertEqual(path, Path("/tmp/reports/RID"))

    def test_portal_consented_manifest_path_contains_session(self) -> None:
        path = runner.portal_consented_manifest_path("fedora-43", "gnome-wayland", Path("/tmp/base"))
        self.assertEqual(path, Path("/tmp/base/fedora-43/gnome-wayland-portal-consented.json"))


if __name__ == "__main__":
    unittest.main()
