#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


TEST_DIR = Path(__file__).resolve().parent
WIZARD_PATH = TEST_DIR.parent / "scripts" / "wizard.py"
SCRIPTS_DIR = TEST_DIR.parent / "scripts"
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import runner  # noqa: E402


def load_wizard():
    spec = importlib.util.spec_from_file_location("wizard_assets_module", WIZARD_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def fake_matrix() -> dict:
    return {
        "distros": [
            {"id": "fedora-43", "iso_env": "WBEAM_E2E_ISO_FEDORA_43"},
            {"id": "ubuntu-24.04", "iso_env": "WBEAM_E2E_ISO_UBUNTU_24_04"},
        ],
        "scenarios": [
            {
                "id": "fedora43-gnome-wayland-portal-h264",
                "distro": "fedora-43",
                "session": "gnome-wayland",
                "backend": "wayland_portal",
                "tier": "backend",
                "stability": "experimental",
                "device_policy": "none",
                "requires_desktop": True,
                "requires_evdi": False,
                "requires_portal": True,
            },
            {
                "id": "fedora43-gnome-wayland-evdi-h264",
                "distro": "fedora-43",
                "session": "gnome-wayland",
                "backend": "evdi",
                "tier": "backend",
                "stability": "experimental",
                "device_policy": "none",
                "requires_desktop": True,
                "requires_evdi": True,
                "requires_portal": False,
            },
            {
                "id": "ubuntu2404-gnome-xorg-x11-h264",
                "distro": "ubuntu-24.04",
                "session": "gnome-xorg",
                "backend": "x11_gst",
                "tier": "backend",
                "stability": "experimental",
                "device_policy": "none",
                "requires_desktop": True,
                "requires_evdi": False,
                "requires_portal": False,
            },
            {
                "id": "fedora43-gnome-wayland-portal-android-h264",
                "distro": "fedora-43",
                "session": "gnome-wayland",
                "backend": "wayland_portal",
                "tier": "hardware",
                "stability": "manual",
                "device_policy": "required",
                "requires_desktop": True,
                "requires_evdi": False,
                "requires_portal": True,
            },
        ],
    }


class WizardAssetTests(unittest.TestCase):
    def make_ready_l1_without_portal(self, wizard, tmp_path: Path) -> Path:
        base_dir = tmp_path / "base"
        iso_path = tmp_path / "fedora-43.iso"
        iso_path.write_bytes(b"0" * (11 * 1024 * 1024))
        l0_path = base_dir / "fedora-43" / "gnome-wayland.qcow2"
        l0_path.parent.mkdir(parents=True, exist_ok=True)
        l0_path.write_bytes(b"0" * (11 * 1024 * 1024))
        l0_path.with_suffix(".json").write_text('{"schema": 2, "kind": "base"}', encoding="utf-8")
        l1_path = base_dir / "fedora-43" / "gnome-wayland-installed.qcow2"
        l1_path.write_bytes(b"0" * (11 * 1024 * 1024))
        l1_path.with_suffix(".json").write_text('{"schema": 2, "kind": "installed"}', encoding="utf-8")
        os.environ["WBEAM_E2E_BASE_DIR"] = str(base_dir)
        os.environ["WBEAM_E2E_ISO_FEDORA_43"] = str(iso_path)
        return base_dir

    def test_backend_selection_defaults_to_wayland_portal(self) -> None:
        wizard = load_wizard()
        state = wizard.WizardState(matrix=fake_matrix())
        self.assertEqual(state.selected_backends, ["wayland_portal"])

    def test_resolve_selected_scenarios_keeps_both_backends(self) -> None:
        wizard = load_wizard()
        state = wizard.WizardState(matrix=fake_matrix())
        state.selected_distros = ["fedora-43"]
        state.selected_backends = ["wayland_portal", "evdi"]
        scenarios = wizard.resolve_selected_scenarios(state)
        self.assertEqual({item["backend"] for item in scenarios}, {"wayland_portal", "evdi"})

    def test_resolve_selected_scenarios_maps_x11_to_gnome_xorg(self) -> None:
        wizard = load_wizard()
        state = wizard.WizardState(matrix=fake_matrix())
        state.selected_distros = ["ubuntu-24.04"]
        state.selected_backends = ["x11_gst"]
        scenarios = wizard.resolve_selected_scenarios(state)
        self.assertEqual([item["session"] for item in scenarios], ["gnome-xorg"])
        self.assertEqual([item["backend"] for item in scenarios], ["x11_gst"])

    def test_build_readiness_deduplicates_shared_l0_l1(self) -> None:
        wizard = load_wizard()
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            base_dir = tmp_path / "base"
            with mock.patch.dict(os.environ, {"WBEAM_E2E_BASE_DIR": str(base_dir)}, clear=False):
                state = wizard.WizardState(matrix=fake_matrix())
                state.selected_distros = ["fedora-43"]
                state.selected_backends = ["wayland_portal", "evdi"]
                readiness = wizard.build_readiness(state)
                self.assertEqual(len(readiness), 1)
                self.assertEqual(readiness[0].distro, "fedora-43")
                self.assertEqual(readiness[0].session, "gnome-wayland")
                self.assertEqual(len(readiness[0].shared_by_scenarios), 2)
                self.assertEqual(set(readiness[0].shared_backends), {"wayland_portal", "evdi"})

    def test_build_readiness_reports_missing_iso_l0_l1(self) -> None:
        wizard = load_wizard()
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            with mock.patch.dict(os.environ, {"WBEAM_E2E_BASE_DIR": str(tmp_path / "base")}, clear=False):
                with mock.patch.object(wizard, "get_iso_path", return_value=None):
                    state = wizard.WizardState(matrix=fake_matrix())
                    state.selected_distros = ["fedora-43"]
                    state.selected_backends = ["wayland_portal"]
                    readiness = wizard.build_readiness(state)
                    self.assertEqual(len(readiness), 1)
                    item = readiness[0]
                    self.assertEqual(item.iso_status, "missing")
                    self.assertEqual(item.l0_status, "missing")
                    self.assertEqual(item.l1_status, "missing")
                    self.assertEqual(item.action, "download_iso -> build_l0 -> build_l1 -> run")

    def test_build_readiness_reports_existing_iso_l0_l1(self) -> None:
        wizard = load_wizard()
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            base_dir = tmp_path / "base"
            iso_dir = tmp_path / "iso"
            iso_dir.mkdir(parents=True)
            iso_path = iso_dir / "fedora-43-netinst.iso"
            iso_path.write_bytes(b"0" * 1024)
            l0_path = base_dir / "fedora-43" / "gnome-wayland.qcow2"
            l0_path.parent.mkdir(parents=True, exist_ok=True)
            l0_path.write_bytes(b"0" * (11 * 1024 * 1024))
            (l0_path.with_suffix(".json")).write_text('{"schema": 2, "kind": "base"}', encoding="utf-8")
            l1_path = base_dir / "fedora-43" / "gnome-wayland-installed.qcow2"
            l1_path.write_bytes(b"0" * (11 * 1024 * 1024))
            (l1_path.with_suffix(".json")).write_text('{"schema": 2, "kind": "installed"}', encoding="utf-8")
            consented = base_dir / "fedora-43" / "gnome-wayland-portal-consented.qcow2"
            consented.write_bytes(b"0" * (11 * 1024 * 1024))
            consented.with_suffix(".json").write_text(
                '{"schema": 2, "kind": "portal_consented", "distro": "fedora-43", "session": "gnome-wayland", "backend": "wayland_portal", "stream_smoke_ok": true}',
                encoding="utf-8",
            )
            with mock.patch.dict(
                os.environ,
                {
                    "WBEAM_E2E_BASE_DIR": str(base_dir),
                    "WBEAM_E2E_ISO_FEDORA_43": str(iso_path),
                },
                clear=False,
            ):
                with mock.patch.object(wizard, "iso_min_size", return_value=1):
                    state = wizard.WizardState(matrix=fake_matrix())
                    state.selected_distros = ["fedora-43"]
                    state.selected_backends = ["wayland_portal"]
                    readiness = wizard.build_readiness(state)
                    item = readiness[0]
                    self.assertEqual(item.iso_status, "ok")
                    self.assertEqual(item.l0_status, "ok")
                    self.assertEqual(item.l1_status, "ok")
                    self.assertEqual(item.portal_consented_status, "ok")
                    self.assertEqual(item.portal_required, True)
                    self.assertEqual(item.action, "reuse_portal_consented_l1p -> run_l2_overlays")

    def test_readiness_shows_portal_consent_missing_for_wayland_portal(self) -> None:
        wizard = load_wizard()
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            base_dir = tmp_path / "base"
            iso_path = tmp_path / "fedora-43.iso"
            iso_path.write_bytes(b"0" * (11 * 1024 * 1024))
            l0_path = base_dir / "fedora-43" / "gnome-wayland.qcow2"
            l0_path.parent.mkdir(parents=True, exist_ok=True)
            l0_path.write_bytes(b"0" * (11 * 1024 * 1024))
            l0_path.with_suffix(".json").write_text('{"schema": 2, "kind": "base"}', encoding="utf-8")
            l1_path = base_dir / "fedora-43" / "gnome-wayland-installed.qcow2"
            l1_path.write_bytes(b"0" * (11 * 1024 * 1024))
            l1_path.with_suffix(".json").write_text('{"schema": 2, "kind": "installed"}', encoding="utf-8")
            with mock.patch.dict(
                os.environ,
                {
                    "WBEAM_E2E_BASE_DIR": str(base_dir),
                    "WBEAM_E2E_ISO_FEDORA_43": str(iso_path),
                },
                clear=False,
            ):
                with mock.patch.object(wizard, "iso_min_size", return_value=1):
                    state = wizard.WizardState(matrix=fake_matrix())
                    state.selected_distros = ["fedora-43"]
                    state.selected_backends = ["wayland_portal"]
                    readiness = wizard.build_readiness(state)
                    item = readiness[0]
                    self.assertTrue(item.portal_required)
                    self.assertEqual(item.portal_consented_status, "missing")
                    self.assertEqual(item.portal_consented_action, "manual_approve")

    def test_readiness_shows_invalid_portal_consent(self) -> None:
        wizard = load_wizard()
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            base_dir = tmp_path / "base"
            consented = base_dir / "fedora-43" / "gnome-wayland-portal-consented.qcow2"
            consented.parent.mkdir(parents=True, exist_ok=True)
            consented.write_bytes(b"0" * (11 * 1024 * 1024))
            consented.with_suffix(".json").write_text(
                json.dumps(
                    {
                        "schema": 2,
                        "kind": "portal_consented",
                        "distro": "fedora-43",
                        "session": "gnome-wayland",
                        "backend": "wayland_portal",
                        "stream_smoke_ok": False,
                    }
                ),
                encoding="utf-8",
            )
            with mock.patch.dict(os.environ, {"WBEAM_E2E_BASE_DIR": str(base_dir)}, clear=False):
                state = wizard.WizardState(matrix=fake_matrix())
                state.selected_distros = ["fedora-43"]
                state.selected_backends = ["wayland_portal"]
                readiness = wizard.build_readiness(state)
                self.assertEqual(readiness[0].portal_consented_status, "stale")
                self.assertEqual(readiness[0].portal_consented_action, "manual_approve")

    def test_readiness_does_not_require_portal_consent_for_evdi(self) -> None:
        wizard = load_wizard()
        state = wizard.WizardState(matrix=fake_matrix())
        state.selected_distros = ["fedora-43"]
        state.selected_backends = ["evdi"]
        readiness = wizard.build_readiness(state)
        self.assertEqual(len(readiness), 1)
        self.assertFalse(readiness[0].portal_required)
        self.assertEqual(readiness[0].portal_consented_status, "not_required")

    def test_execution_plan_includes_prepare_portal_consent_before_wayland_run(self) -> None:
        wizard = load_wizard()
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            base_dir = tmp_path / "base"
            base_dir.mkdir(parents=True, exist_ok=True)
            iso_path = tmp_path / "fedora-43.iso"
            iso_path.write_bytes(b"0" * (11 * 1024 * 1024))
            (base_dir / "fedora-43" / "gnome-wayland.qcow2").parent.mkdir(parents=True, exist_ok=True)
            (base_dir / "fedora-43" / "gnome-wayland.qcow2").write_bytes(b"0" * (11 * 1024 * 1024))
            (base_dir / "fedora-43" / "gnome-wayland.qcow2").with_suffix(".json").write_text('{"schema": 2, "kind": "base"}', encoding="utf-8")
            (base_dir / "fedora-43" / "gnome-wayland-installed.qcow2").write_bytes(b"0" * (11 * 1024 * 1024))
            (base_dir / "fedora-43" / "gnome-wayland-installed.json").write_text('{"schema": 2, "kind": "installed"}', encoding="utf-8")
            with mock.patch.dict(
                os.environ,
                {
                    "WBEAM_E2E_BASE_DIR": str(base_dir),
                    "WBEAM_E2E_ISO_FEDORA_43": str(iso_path),
                },
                clear=False,
            ):
                with mock.patch.object(wizard, "iso_min_size", return_value=1):
                    state = wizard.WizardState(matrix=fake_matrix(), run_id="RID")
                    state.selected_distros = ["fedora-43"]
                    state.selected_backends = ["wayland_portal"]
                    state.readiness = wizard.build_readiness(state)
                    plan = wizard.build_execution_plan(state)
                    kinds = [action.kind for action in plan]
                    self.assertIn("portal_consent", kinds)
                    portal_actions = [action for action in plan if action.kind == "portal_consent"]
                    self.assertTrue(portal_actions)
                    self.assertIn("--promote", portal_actions[0].command)

    def test_execution_plan_uses_existing_portal_consented_asset(self) -> None:
        wizard = load_wizard()
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            base_dir = tmp_path / "base"
            iso_path = tmp_path / "fedora-43.iso"
            iso_path.write_bytes(b"0" * (11 * 1024 * 1024))
            l0_path = base_dir / "fedora-43" / "gnome-wayland.qcow2"
            l0_path.parent.mkdir(parents=True, exist_ok=True)
            l0_path.write_bytes(b"0" * (11 * 1024 * 1024))
            l0_path.with_suffix(".json").write_text('{"schema": 2, "kind": "base"}', encoding="utf-8")
            l1_path = base_dir / "fedora-43" / "gnome-wayland-installed.qcow2"
            l1_path.write_bytes(b"0" * (11 * 1024 * 1024))
            l1_path.with_suffix(".json").write_text('{"schema": 2, "kind": "installed"}', encoding="utf-8")
            consented = base_dir / "fedora-43" / "gnome-wayland-portal-consented.qcow2"
            consented.write_bytes(b"0" * (11 * 1024 * 1024))
            consented.with_suffix(".json").write_text(
                '{"schema": 2, "kind": "portal_consented", "distro": "fedora-43", "session": "gnome-wayland", "backend": "wayland_portal", "stream_smoke_ok": true}',
                encoding="utf-8",
            )
            with mock.patch.dict(
                os.environ,
                {
                    "WBEAM_E2E_BASE_DIR": str(base_dir),
                    "WBEAM_E2E_ISO_FEDORA_43": str(iso_path),
                },
                clear=False,
            ):
                with mock.patch.object(wizard, "iso_min_size", return_value=1):
                    state = wizard.WizardState(matrix=fake_matrix(), run_id="RID")
                    state.selected_distros = ["fedora-43"]
                    state.selected_backends = ["wayland_portal"]
                    state.readiness = wizard.build_readiness(state)
                    plan = wizard.build_execution_plan(state)
                    portal_actions = [action for action in plan if action.kind == "portal_consent"]
                    self.assertFalse(portal_actions)
                    run_actions = [action for action in plan if action.kind == "run_matrix"]
                    self.assertTrue(run_actions)

    def test_readiness_portal_shortcut_command_matches_runner_next_action(self) -> None:
        wizard = load_wizard()
        state = wizard.WizardState(matrix=fake_matrix(), run_id="RID")
        state.selected_distros = ["fedora-43"]
        state.selected_backends = ["wayland_portal"]
        readiness = wizard.build_readiness(state)
        self.assertIn("prepare-portal-consent", readiness[0].next_action)
        self.assertIn("--backend wayland_portal", readiness[0].next_action)

    def test_readiness_accepts_portal_consented_underscore_kind(self) -> None:
        wizard = load_wizard()
        with tempfile.TemporaryDirectory() as tmp:
            base_root = Path(tmp) / "base"
            consented = runner.portal_consented_image_path("fedora-43", "gnome-wayland", base_root)
            consented.parent.mkdir(parents=True, exist_ok=True)
            consented.write_bytes(b"0" * (11 * 1024 * 1024))
            consented.with_suffix(".json").write_text(
                json.dumps(
                    {
                        "schema": 2,
                        "kind": "portal_consented",
                        "distro": "fedora-43",
                        "session": "gnome-wayland",
                        "backend": "wayland_portal",
                        "stream_smoke_ok": True,
                    }
                ),
                encoding="utf-8",
            )
            with mock.patch.dict(os.environ, {"WBEAM_E2E_BASE_DIR": str(base_root)}, clear=False):
                state = wizard.WizardState(matrix=fake_matrix())
                state.selected_distros = ["fedora-43"]
                state.selected_backends = ["wayland_portal"]
                readiness = wizard.build_readiness(state)
                self.assertEqual(readiness[0].portal_consented_status, "ok")
                self.assertEqual(readiness[0].portal_action, "reuse")

    def test_readiness_accepts_legacy_portal_consented_dash_kind(self) -> None:
        wizard = load_wizard()
        with tempfile.TemporaryDirectory() as tmp:
            base_root = Path(tmp) / "base"
            consented = runner.portal_consented_image_path("fedora-43", "gnome-wayland", base_root)
            consented.parent.mkdir(parents=True, exist_ok=True)
            consented.write_bytes(b"0" * (11 * 1024 * 1024))
            consented.with_suffix(".json").write_text(
                json.dumps(
                    {
                        "schema": 2,
                        "kind": "portal-consented",
                        "distro": "fedora-43",
                        "session": "gnome-wayland",
                        "backend": "wayland_portal",
                        "stream_smoke_ok": True,
                    }
                ),
                encoding="utf-8",
            )
            with mock.patch.dict(os.environ, {"WBEAM_E2E_BASE_DIR": str(base_root)}, clear=False):
                state = wizard.WizardState(matrix=fake_matrix())
                state.selected_distros = ["fedora-43"]
                state.selected_backends = ["wayland_portal"]
                readiness = wizard.build_readiness(state)
                self.assertEqual(readiness[0].portal_consented_status, "ok")
                self.assertEqual(readiness[0].portal_action, "reuse")

    def test_readiness_missing_portal_next_action_contains_prepare_portal_consent(self) -> None:
        wizard = load_wizard()
        with tempfile.TemporaryDirectory() as tmp:
            with mock.patch.dict(os.environ, {"WBEAM_E2E_BASE_DIR": str(Path(tmp) / "base")}, clear=False):
                state = wizard.WizardState(matrix=fake_matrix())
                state.selected_distros = ["fedora-43"]
                state.selected_backends = ["wayland_portal"]
                readiness = wizard.build_readiness(state)
                self.assertIn("prepare-portal-consent", readiness[0].next_action)
                self.assertIn("--backend wayland_portal", readiness[0].next_action)

    def test_readiness_invalid_portal_kind_is_not_ok(self) -> None:
        wizard = load_wizard()
        with tempfile.TemporaryDirectory() as tmp:
            base_root = Path(tmp) / "base"
            consented = runner.portal_consented_image_path("fedora-43", "gnome-wayland", base_root)
            consented.parent.mkdir(parents=True, exist_ok=True)
            consented.write_bytes(b"0" * (11 * 1024 * 1024))
            consented.with_suffix(".json").write_text(
                json.dumps(
                    {
                        "schema": 2,
                        "kind": "portal_foo",
                        "distro": "fedora-43",
                        "session": "gnome-wayland",
                        "backend": "wayland_portal",
                        "stream_smoke_ok": True,
                    }
                ),
                encoding="utf-8",
            )
            with mock.patch.dict(os.environ, {"WBEAM_E2E_BASE_DIR": str(base_root)}, clear=False):
                state = wizard.WizardState(matrix=fake_matrix())
                state.selected_distros = ["fedora-43"]
                state.selected_backends = ["wayland_portal"]
                readiness = wizard.build_readiness(state)
                self.assertEqual(readiness[0].portal_consented_status, "invalid")
                self.assertEqual(readiness[0].portal_consented_action, "manual_approve")

    def test_wizard_does_not_use_allow_unconsented_portal(self) -> None:
        wizard = load_wizard()
        state = wizard.WizardState(matrix=fake_matrix(), run_id="RID")
        state.selected_distros = ["fedora-43"]
        state.selected_backends = ["wayland_portal"]
        state.readiness = wizard.build_readiness(state)
        plan = wizard.build_execution_plan(state)
        portal_actions = [action for action in plan if action.kind == "portal_consent"]
        self.assertTrue(portal_actions)
        self.assertTrue(all("--allow-unconsented-portal" not in action.command for action in portal_actions))

    def test_readiness_view_shows_missing_l1p_as_primary_blocker(self) -> None:
        wizard = load_wizard()
        with tempfile.TemporaryDirectory() as tmp:
            with mock.patch.dict(os.environ, {}, clear=True):
                self.make_ready_l1_without_portal(wizard, Path(tmp))
                with mock.patch.object(wizard, "iso_min_size", return_value=1):
                    state = wizard.WizardState(matrix=fake_matrix(), run_id="RID")
                    state.selected_distros = ["fedora-43"]
                    state.selected_backends = ["wayland_portal"]
                    view = wizard.build_readiness_view(state)
        self.assertEqual(view.status, "blocked")
        self.assertEqual(view.blocker_reason_code, "missing_portal_consented_image")
        self.assertIn("prepare-portal-consent", view.next_action)

    def test_readiness_view_next_action_contains_prepare_portal_consent(self) -> None:
        wizard = load_wizard()
        state = wizard.WizardState(matrix=fake_matrix(), run_id="RID")
        state.selected_distros = ["fedora-43"]
        state.selected_backends = ["wayland_portal"]
        view = wizard.build_readiness_view(state)
        self.assertIn("prepare-portal-consent", view.next_action)
        self.assertIn("--backend wayland_portal", view.next_action)

    def test_readiness_view_android_deploy_skipped_for_device_policy_none(self) -> None:
        wizard = load_wizard()
        state = wizard.WizardState(matrix=fake_matrix(), run_id="RID")
        state.selected_distros = ["fedora-43"]
        state.selected_backends = ["wayland_portal"]
        view = wizard.build_readiness_view(state)
        android_rows = [row for row in view.environment_gates if row.label == "Android APK deploy"]
        self.assertTrue(android_rows)
        self.assertEqual(android_rows[0].status, "skipped")
        self.assertIn("device_policy=none", android_rows[0].detail)

    def test_readiness_view_marks_evdi_out_of_scope_for_portal_mvp(self) -> None:
        wizard = load_wizard()
        state = wizard.WizardState(matrix=fake_matrix(), run_id="RID")
        state.selected_distros = ["fedora-43"]
        state.selected_backends = ["wayland_portal"]
        view = wizard.build_readiness_view(state)
        evdi_rows = [row for row in view.out_of_scope if row.label == "Fedora EVDI"]
        self.assertTrue(evdi_rows)
        self.assertEqual(evdi_rows[0].status, "out_of_scope")

    def test_readiness_view_asset_pipeline_contains_iso_l0_l1_l1p(self) -> None:
        wizard = load_wizard()
        state = wizard.WizardState(matrix=fake_matrix(), run_id="RID")
        state.selected_distros = ["fedora-43"]
        state.selected_backends = ["wayland_portal"]
        view = wizard.build_readiness_view(state)
        labels = [row.label for row in view.required_assets]
        self.assertIn("ISO", labels)
        self.assertIn("L0 clean OS", labels)
        self.assertIn("L1 installed WBeam", labels)
        self.assertIn("L1P portal-consented", labels)

    def test_readiness_view_manual_gate_contains_gnome_portal_consent(self) -> None:
        wizard = load_wizard()
        state = wizard.WizardState(matrix=fake_matrix(), run_id="RID")
        state.selected_distros = ["fedora-43"]
        state.selected_backends = ["wayland_portal"]
        view = wizard.build_readiness_view(state)
        labels = [row.label for row in view.manual_gates]
        self.assertIn("GNOME ScreenCast consent", labels)

    def test_execution_plan_preview_orders_portal_consent_before_run(self) -> None:
        wizard = load_wizard()
        with tempfile.TemporaryDirectory() as tmp:
            with mock.patch.dict(os.environ, {}, clear=True):
                self.make_ready_l1_without_portal(wizard, Path(tmp))
                with mock.patch.object(wizard, "iso_min_size", return_value=1):
                    state = wizard.WizardState(matrix=fake_matrix(), run_id="RID")
                    state.selected_distros = ["fedora-43"]
                    state.selected_backends = ["wayland_portal"]
                    view = wizard.build_readiness_view(state)
        labels = [row.label for row in view.execution_plan]
        self.assertIn("Approve GNOME ScreenCast portal", labels[0])
        self.assertTrue(any("Run 1 selected scenario" in label for label in labels))

    def test_detail_view_model_includes_paths_and_warnings(self) -> None:
        wizard = load_wizard()
        state = wizard.WizardState(matrix=fake_matrix(), run_id="RID")
        state.selected_distros = ["fedora-43"]
        state.selected_backends = ["wayland_portal"]
        view = wizard.build_readiness_view(state)
        l1p_rows = [row for row in view.required_assets if row.label == "L1P portal-consented"]
        self.assertTrue(l1p_rows)
        self.assertIn("portal-consented.qcow2", l1p_rows[0].detail)

    def test_status_badge_uses_semantic_labels(self) -> None:
        wizard = load_wizard()
        self.assertEqual(wizard.status_badge("manual_required"), "[MANUAL REQUIRED]")
        self.assertEqual(wizard.status_badge("out_of_scope"), "[OUT OF MVP]")

    def test_progress_hint_maps_long_running_logs(self) -> None:
        wizard = load_wizard()
        self.assertEqual(wizard.progress_hint_from_log("[e2e] waiting for SSH on localhost:2222"), (0.35, "waiting for SSH"))
        self.assertEqual(
            wizard.progress_hint_from_log("[e2e] waiting for GNOME ScreenCast approval; approve the prompt"),
            (0.80, "waiting for manual GNOME ScreenCast approval"),
        )

    def test_update_task_progress_rolls_into_overall_progress(self) -> None:
        wizard = load_wizard()
        state = wizard.WizardState(matrix=fake_matrix())
        state.current_action_index = 1
        state.current_action_total = 4
        wizard.update_task_progress_from_log(state, "[e2e] waiting for SSH on localhost:2222")
        self.assertEqual(state.current_task_progress, 0.35)
        self.assertEqual(state.current_task_phase, "waiting for SSH")
        self.assertGreater(state.overall_progress, 0)
        self.assertAlmostEqual(state.overall_progress, 0.0875)

    def test_update_task_progress_is_monotonic_within_step(self) -> None:
        wizard = load_wizard()
        state = wizard.WizardState(matrix=fake_matrix())
        state.current_action_index = 1
        state.current_action_total = 1
        wizard.update_task_progress_from_log(state, "running stream smoke")
        wizard.update_task_progress_from_log(state, "waiting for SSH")
        self.assertEqual(state.current_task_progress, 0.74)
        self.assertEqual(state.current_task_phase, "running stream smoke")

    def test_build_execution_plan_downloads_only_selected_distro(self) -> None:
        wizard = load_wizard()
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            with mock.patch.dict(
                os.environ,
                {
                    "WBEAM_E2E_BASE_DIR": str(tmp_path / "base"),
                    "WBEAM_E2E_ISO_FEDORA_43": "",
                    "WBEAM_E2E_ISO_UBUNTU_24_04": "",
                },
                clear=False,
            ):
                with mock.patch.object(wizard, "get_iso_path", return_value=None):
                    with mock.patch.object(wizard, "expected_iso_path", side_effect=lambda distro_id: tmp_path / f"{distro_id}.iso"):
                        state = wizard.WizardState(matrix=fake_matrix())
                        state.selected_distros = ["fedora-43"]
                        state.selected_backends = ["wayland_portal"]
                        state.readiness = wizard.build_readiness(state)
                        plan = wizard.build_execution_plan(state)
                        download = [action for action in plan if action.kind == "download_iso"]
                        self.assertTrue(download)
                        self.assertTrue(all("fedora-43" in action.command for action in download))
                        self.assertFalse(any("ubuntu-24.04" in action.command for action in plan))

    def test_build_execution_plan_emits_single_run_matrix_action(self) -> None:
        wizard = load_wizard()
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            with mock.patch.dict(os.environ, {"WBEAM_E2E_BASE_DIR": str(tmp_path / "base")}, clear=False):
                with mock.patch.object(wizard, "get_iso_path", return_value=None):
                    state = wizard.WizardState(matrix=fake_matrix(), run_id="RID")
                    state.selected_distros = ["fedora-43"]
                    state.selected_backends = ["wayland_portal", "evdi"]
                    state.readiness = wizard.build_readiness(state)
                    plan = wizard.build_execution_plan(state)
                    run_actions = [action for action in plan if action.kind == "run_matrix"]
                    self.assertEqual(len(run_actions), 1)
                    self.assertEqual(run_actions[0].scenario_ids, [
                        "fedora43-gnome-wayland-evdi-h264",
                        "fedora43-gnome-wayland-portal-h264",
                    ])
                    self.assertIn("--run-id", run_actions[0].command)
                    self.assertIn("--report-dir", run_actions[0].command)
                    self.assertTrue(str(run_actions[0].runner_report_dir).endswith("e2e/reports/RID"))

    def test_build_l1_plan_passes_install_backend_wayland_for_wayland_and_evdi_shared_asset(self) -> None:
        wizard = load_wizard()
        state = wizard.WizardState(matrix=fake_matrix(), run_id="RID")
        state.selected_distros = ["fedora-43"]
        state.selected_backends = ["wayland_portal", "evdi"]
        state.selected_tier = "smoke"
        state.readiness = [
            wizard.AssetReadiness(
                distro="fedora-43",
                session="gnome-wayland",
                iso_status="ok",
                iso_path="/tmp/fedora.iso",
                iso_action="reuse",
                l0_status="ok",
                l0_path="/tmp/base.qcow2",
                l0_action="reuse",
                l1_status="missing",
                l1_path="/tmp/base-installed.qcow2",
                l1_action="build",
                shared_backends=["evdi", "wayland_portal"],
                shared_by_scenarios=["fedora43-gnome-wayland-evdi-h264", "fedora43-gnome-wayland-portal-h264"],
            )
        ]
        plan = wizard.build_execution_plan(state)
        l1_actions = [action for action in plan if action.kind == "build_l1"]
        self.assertEqual(len(l1_actions), 1)
        self.assertIn("--install-backend", l1_actions[0].command)
        self.assertIn("wayland", l1_actions[0].command)
        self.assertNotIn("evdi", l1_actions[0].command)

    def test_expected_iso_path_matches_filenames(self) -> None:
        wizard = load_wizard()
        self.assertTrue(str(wizard.expected_iso_path("fedora-43")).endswith("fedora-43-netinst.iso"))
        self.assertTrue(str(wizard.expected_iso_path("ubuntu-24.04")).endswith("ubuntu-24.04-desktop.iso"))

    def test_expected_l2_overlay_path_matches_runner_layout(self) -> None:
        wizard = load_wizard()
        self.assertTrue(
            str(wizard.expected_l2_overlay_path("RID", "scenario-a")).endswith(
                "e2e/work/runs/RID/scenario-a/disk.qcow2"
            )
        )

    def test_trim_middle_shortens_long_paths(self) -> None:
        wizard = load_wizard()
        trimmed = wizard.trim_middle("/very/long/path/to/some/asset/file.qcow2", 20)
        self.assertLessEqual(len(trimmed), 20)
        self.assertIn("...", trimmed)

    def test_safe_log_name_replaces_colons(self) -> None:
        wizard = load_wizard()
        self.assertEqual(wizard.safe_log_name("build-l1:fedora-43:gnome-wayland"), "build-l1_fedora-43_gnome-wayland")

    def test_classify_disk_image_uses_manifest_contract(self) -> None:
        wizard = load_wizard()
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            image = tmp_path / "base.qcow2"
            image.write_bytes(b"0" * (11 * 1024 * 1024))
            manifest = tmp_path / "base.json"
            manifest.write_text('{"schema": 2, "kind": "base"}', encoding="utf-8")
            status, warnings = wizard.classify_disk_image(image, manifest, expected_kind="base")
            self.assertEqual(status, "ok")
            self.assertEqual(warnings, [])

            manifest.write_text('{"schema": 2, "kind": "installed"}', encoding="utf-8")
            status, warnings = wizard.classify_disk_image(image, manifest, expected_kind="base")
            self.assertEqual(status, "stale")
            self.assertTrue(warnings)

            manifest.write_text("not-json", encoding="utf-8")
            status, warnings = wizard.classify_disk_image(image, manifest, expected_kind="base")
            self.assertEqual(status, "invalid_manifest")
            self.assertTrue(warnings)

    def test_rebuild_args_use_force_for_damaged_assets(self) -> None:
        wizard = load_wizard()
        self.assertEqual(wizard.rebuild_args_for_status("missing"), ["--missing"])
        for status in ("partial", "invalid", "invalid_manifest", "stale"):
            self.assertEqual(wizard.rebuild_args_for_status(status), ["--force"])
        self.assertEqual(wizard.rebuild_args_for_status("ok"), [])

    def test_build_execution_plan_uses_force_for_partial_l0(self) -> None:
        wizard = load_wizard()
        state = wizard.WizardState(matrix=fake_matrix())
        state.selected_backends = ["wayland_portal"]
        state.selected_tier = "smoke"
        state.readiness = [
            wizard.AssetReadiness(
                distro="fedora-43",
                session="gnome-wayland",
                iso_status="ok",
                iso_path="/tmp/fedora.iso",
                iso_action="reuse",
                l0_status="partial",
                l0_path="/tmp/base.qcow2",
                l0_action="build",
                l1_status="missing",
                l1_path="/tmp/base-installed.qcow2",
                l1_action="blocked",
                shared_by_scenarios=["fedora43-gnome-wayland-portal-h264"],
            )
        ]
        plan = wizard.build_execution_plan(state)
        l0_actions = [action for action in plan if action.kind == "build_l0"]
        self.assertEqual(len(l0_actions), 1)
        self.assertIn("--force", l0_actions[0].command)
        self.assertNotIn("--missing", l0_actions[0].command)

    def test_build_execution_plan_uses_force_for_invalid_manifest_l1(self) -> None:
        wizard = load_wizard()
        state = wizard.WizardState(matrix=fake_matrix())
        state.selected_backends = ["wayland_portal"]
        state.selected_tier = "smoke"
        state.readiness = [
            wizard.AssetReadiness(
                distro="fedora-43",
                session="gnome-wayland",
                iso_status="ok",
                iso_path="/tmp/fedora.iso",
                iso_action="reuse",
                l0_status="ok",
                l0_path="/tmp/base.qcow2",
                l0_action="reuse",
                l1_status="invalid_manifest",
                l1_path="/tmp/base-installed.qcow2",
                l1_action="build",
                shared_by_scenarios=["fedora43-gnome-wayland-portal-h264"],
            )
        ]
        plan = wizard.build_execution_plan(state)
        l1_actions = [action for action in plan if action.kind == "build_l1"]
        self.assertEqual(len(l1_actions), 1)
        self.assertIn("--force", l1_actions[0].command)
        self.assertNotIn("--missing", l1_actions[0].command)

    def test_status_attr_treats_invalid_manifest_as_error(self) -> None:
        wizard = load_wizard()
        with mock.patch.object(wizard.curses, "color_pair", side_effect=lambda value: value):
            self.assertEqual(wizard.status_attr("invalid_manifest"), wizard.CP_MISSING)

    def test_selected_requires_device_reads_matrix_policy(self) -> None:
        wizard = load_wizard()
        state = wizard.WizardState(matrix=fake_matrix())
        state.selected_distros = ["fedora-43"]
        state.selected_backends = ["wayland_portal"]
        state.selected_tier = "smoke"
        self.assertFalse(wizard.selected_requires_device(state))
        state.selected_tier = "hardware"
        self.assertTrue(wizard.selected_requires_device(state))

    def test_classify_adb_host_distinguishes_unauthorized_and_ready(self) -> None:
        wizard = load_wizard()
        unauthorized = wizard.classify_adb_host("List of devices attached\nABC\tunauthorized\n")
        self.assertEqual(unauthorized.status, "unauthorized")
        ready = wizard.classify_adb_host("List of devices attached\nABC\tdevice product:x\n")
        self.assertEqual(ready.status, "ready")
        self.assertEqual(ready.serial, "ABC")

    def test_classify_adb_host_ignores_daemon_start_noise(self) -> None:
        wizard = load_wizard()
        output = "\n".join(
            [
                "* daemon not running; starting now at tcp:5037",
                "* daemon started successfully",
                "List of devices attached",
                "ABC123\tdevice usb:1-5 product:x model:y",
            ]
        )
        ready = wizard.classify_adb_host(output)
        self.assertEqual(ready.status, "ready")
        self.assertEqual(ready.serial, "ABC123")

    def test_probe_adb_host_retries_after_empty_initial_probe(self) -> None:
        wizard = load_wizard()

        class Result:
            def __init__(self, stdout: str = "", stderr: str = "") -> None:
                self.stdout = stdout
                self.stderr = stderr

        calls: list[list[str]] = []

        def fake_run(cmd, capture_output=True, text=True, check=False, timeout=8):
            calls.append(cmd)
            if cmd == ["adb", "devices", "-l"] and len([item for item in calls if item == cmd]) == 1:
                return Result("List of devices attached\n")
            if cmd == ["adb", "start-server"]:
                return Result()
            return Result("List of devices attached\nABC123\tdevice product:x\n")

        with mock.patch.object(wizard.subprocess, "run", side_effect=fake_run):
            ready = wizard.probe_adb_host()

        self.assertEqual(ready.status, "ready")
        self.assertEqual(ready.serial, "ABC123")
        self.assertIn(["adb", "start-server"], calls)

    def test_check_env_throttles_adb_probe_until_selection_changes(self) -> None:
        wizard = load_wizard()
        state = wizard.WizardState(matrix=fake_matrix())
        state.selected_tier = "smoke"
        with mock.patch.object(wizard, "probe_adb_host", return_value=wizard.AdbHostStatus("ready", "ABC123", "ready")) as probe:
            wizard.check_env(state, force=True)
            wizard.check_env(state)
            self.assertEqual(probe.call_count, 1)
            wizard.sync_selected_context(state)
            wizard.check_env(state)
            self.assertEqual(probe.call_count, 2)

    def test_android_reason_code_maps_non_ready_states_to_allowed_blockers(self) -> None:
        wizard = load_wizard()
        self.assertEqual(wizard.android_reason_code_for_adb_status("unauthorized"), "android_device_unauthorized")
        self.assertEqual(wizard.android_reason_code_for_adb_status("offline"), "android_device_missing")
        self.assertEqual(wizard.android_reason_code_for_adb_status("no_permissions"), "android_device_missing")

    def test_no_selected_scenarios_produces_no_plan_actions(self) -> None:
        wizard = load_wizard()
        state = wizard.WizardState(matrix=fake_matrix())
        state.selected_distros = ["ubuntu-24.04"]
        state.selected_backends = ["evdi"]
        self.assertEqual(wizard.resolve_selected_scenarios(state), [])
        self.assertEqual(wizard.build_execution_plan(state), [])

    def test_emit_wizard_event_writes_jsonl(self) -> None:
        wizard = load_wizard()
        with tempfile.TemporaryDirectory() as tmp:
            state = wizard.WizardState(matrix=fake_matrix(), run_id="RID", run_dir=Path(tmp))
            wizard.emit_wizard_event(state, "test_event", value=123)
            payload = json.loads((Path(tmp) / "steps.jsonl").read_text(encoding="utf-8").strip())
            self.assertEqual(payload["type"], "test_event")
            self.assertEqual(payload["run_id"], "RID")
            self.assertEqual(payload["value"], 123)

    def test_wizard_summary_writes_report_and_steps_paths(self) -> None:
        wizard = load_wizard()
        with tempfile.TemporaryDirectory() as tmp:
            run_dir = Path(tmp)
            state = wizard.WizardState(matrix=fake_matrix(), run_id="RID", run_dir=run_dir)
            state.scenarios_to_run = [{"id": "scenario-a", "distro": "fedora-43", "session": "gnome-wayland", "backend": "wayland_portal"}]
            state.execution_plan = [wizard.PlannedAction(id="run-matrix:RID", title="Run", kind="run_matrix")]
            state.action_results = [{"id": "run-matrix:RID", "status": "ok"}]
            wizard.write_wizard_summary(state, status="pass")
            summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
            self.assertTrue((run_dir / "report.md").exists())
            self.assertEqual(summary["steps"], "steps.jsonl")
            self.assertEqual(summary["report_md"], "report.md")
            self.assertIn("runner_report_dir", summary)
            self.assertIn("runner_summary", summary)
            self.assertIn("runner_junit", summary)
            self.assertIn("runner_report_md", summary)


if __name__ == "__main__":
    unittest.main()
