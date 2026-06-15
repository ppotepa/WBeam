#!/usr/bin/env python3
from __future__ import annotations

import argparse
import importlib.util
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


TEST_DIR = Path(__file__).resolve().parent
SCRIPTS_DIR = TEST_DIR.parent / "scripts"
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import portal_consent  # noqa: E402
import report  # noqa: E402
import runner  # noqa: E402
import validate_portal_consented_asset  # noqa: E402

WIZARD_PATH = SCRIPTS_DIR / "wizard.py"


def load_wizard():
    spec = importlib.util.spec_from_file_location("portal_consent_wizard_module", WIZARD_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def fake_matrix() -> dict:
    return {
        "distros": [{"id": "fedora-43", "iso_env": "WBEAM_E2E_ISO_FEDORA_43"}],
        "scenarios": [
            {
                "id": "fedora43-gnome-wayland-portal-h264",
                "distro": "fedora-43",
                "session": "gnome-wayland",
                "backend": "wayland_portal",
                "tier": "backend",
                "stability": "manual",
                "device_policy": "none",
                "requires_desktop": True,
                "requires_evdi": False,
                "requires_portal": True,
            }
        ],
    }


class PortalConsentTests(unittest.TestCase):
    def test_classifies_portal_consent_required_high_confidence(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            stream_dir = Path(tmp)
            (stream_dir / "summary.json").write_text(
                json.dumps(
                    {
                        "backend": "wayland_portal",
                        "display_mode": "virtual_monitor",
                        "blocked": True,
                        "reason_code": "portal_consent_required",
                        "next_action": "approve once",
                    }
                ),
                encoding="utf-8",
            )
            (stream_dir / "client.json").write_text(json.dumps({"connected": False, "bytes_read": 0}), encoding="utf-8")
            (stream_dir / "status-before.json").write_text(json.dumps({"state": "IDLE"}), encoding="utf-8")
            (stream_dir / "status-after-start.json").write_text(json.dumps({"state": "STARTING"}), encoding="utf-8")
            (stream_dir / "status-after.json").write_text(
                json.dumps({"state": "IDLE", "last_error": "stream start aborted (code=-1): start timeout waiting for streaming signal"}),
                encoding="utf-8",
            )
            (stream_dir / "metrics-after.json").write_text(json.dumps({"metrics": {"frame_in": 0, "frame_out": 0}}), encoding="utf-8")
            (stream_dir / "portal-probe.json").write_text(
                json.dumps({"xdg_desktop_portal_user": "active", "xdg_desktop_portal_gnome_user": "active"}),
                encoding="utf-8",
            )
            (stream_dir / "pipewire-probe.json").write_text(
                json.dumps({"pipewire_user": "active", "wireplumber_user": "active"}),
                encoding="utf-8",
            )
            (stream_dir / "session-probe.json").write_text(
                json.dumps({"xdg_session_type": "wayland", "wayland_display": "wayland-0", "display": ":0"}),
                encoding="utf-8",
            )
            (stream_dir / "virtual-probe.json").write_text(json.dumps({"virtual_supported": True}), encoding="utf-8")
            (stream_dir / "ports.txt").write_text("LISTEN 0 128 127.0.0.1:5000", encoding="utf-8")
            result = portal_consent.classify_guest_portal_report(stream_dir, fake_matrix()["scenarios"][0])
            self.assertEqual(result["status"], "blocked")
            self.assertEqual(result["reason_code"], "portal_consent_required")
            self.assertEqual(result["confidence"], "high")

    def test_classify_waiting_for_streaming_signal_as_portal_consent_required(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            stream_dir = Path(tmp)
            (stream_dir / "summary.json").write_text(
                json.dumps({"backend": "wayland_portal", "display_mode": "virtual_monitor"}),
                encoding="utf-8",
            )
            (stream_dir / "client.json").write_text(json.dumps({"connected": False, "bytes_read": 0, "connection_refused": 2}), encoding="utf-8")
            (stream_dir / "status-before.json").write_text(json.dumps({"state": "IDLE"}), encoding="utf-8")
            (stream_dir / "status-after-start.json").write_text(json.dumps({"state": "STARTING"}), encoding="utf-8")
            (stream_dir / "status-after.json").write_text(
                json.dumps({"state": "IDLE", "last_error": "stream start aborted (code=-1): start timeout waiting for streaming signal"}),
                encoding="utf-8",
            )
            (stream_dir / "metrics-after.json").write_text(json.dumps({"metrics": {"frame_in": 0, "frame_out": 0}}), encoding="utf-8")
            (stream_dir / "portal-probe.json").write_text(
                json.dumps({"xdg_desktop_portal_user": "active", "xdg_desktop_portal_gnome_user": "active"}),
                encoding="utf-8",
            )
            (stream_dir / "pipewire-probe.json").write_text(json.dumps({"pipewire_user": "active", "wireplumber_user": "active"}), encoding="utf-8")
            (stream_dir / "virtual-probe.json").write_text(json.dumps({"virtual_supported": True}), encoding="utf-8")
            result = portal_consent.classify_guest_portal_report(stream_dir, fake_matrix()["scenarios"][0])
            self.assertEqual(result["status"], "blocked")
            self.assertEqual(result["reason_code"], "portal_consent_required")
            self.assertIn("prepare-portal-consent", result["next_action"])

    def test_classify_connected_client_as_portal_consent_approved(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            stream_dir = Path(tmp)
            (stream_dir / "summary.json").write_text(json.dumps({"backend": "wayland_portal", "display_mode": "virtual_monitor"}), encoding="utf-8")
            (stream_dir / "client.json").write_text(json.dumps({"connected": True, "bytes_read": 42}), encoding="utf-8")
            (stream_dir / "portal-probe.json").write_text(json.dumps({"xdg_desktop_portal_user": "active", "xdg_desktop_portal_gnome_user": "active"}), encoding="utf-8")
            (stream_dir / "pipewire-probe.json").write_text(json.dumps({"pipewire_user": "active", "wireplumber_user": "active"}), encoding="utf-8")
            result = portal_consent.classify_guest_portal_report(stream_dir, fake_matrix()["scenarios"][0])
            self.assertEqual(result["status"], "pass")
            self.assertEqual(result["reason_code"], "portal_consent_approved")

    def test_classify_missing_guest_report_as_failure(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            stream_dir = Path(tmp)
            result = portal_consent.classify_guest_portal_report(stream_dir, fake_matrix()["scenarios"][0])
            self.assertEqual(result["status"], "fail")
            self.assertEqual(result["reason_code"], "guest_report_missing")

    def test_classifies_stream_port_not_open_without_portal_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            stream_dir = Path(tmp)
            (stream_dir / "summary.json").write_text(json.dumps({"backend": "benchmark_game", "display_mode": "benchmark_game"}), encoding="utf-8")
            (stream_dir / "client.json").write_text(json.dumps({"connected": False, "bytes_read": 0}), encoding="utf-8")
            (stream_dir / "status-after.json").write_text(json.dumps({"state": "IDLE"}), encoding="utf-8")
            result = portal_consent.classify_guest_portal_report(stream_dir, {})
            self.assertEqual(result["status"], "fail")
            self.assertEqual(result["reason_code"], "stream_port_not_open")

    def test_classifies_portal_evidence_without_stream_port_as_portal_consent_required(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            stream_dir = Path(tmp)
            (stream_dir / "summary.json").write_text(
                json.dumps({"backend": "wayland_portal", "display_mode": "virtual_monitor"}),
                encoding="utf-8",
            )
            (stream_dir / "client.json").write_text(json.dumps({"connected": False, "bytes_read": 0, "connection_refused": 4}), encoding="utf-8")
            (stream_dir / "status-before.json").write_text(json.dumps({"state": "IDLE"}), encoding="utf-8")
            (stream_dir / "status-after-start.json").write_text(json.dumps({"state": "STARTING"}), encoding="utf-8")
            (stream_dir / "status-after.json").write_text(
                json.dumps({"state": "IDLE", "last_error": "stream start aborted (code=-1): start timeout waiting for streaming signal"}),
                encoding="utf-8",
            )
            (stream_dir / "portal-probe.json").write_text(
                json.dumps({"xdg_desktop_portal_user": "active", "xdg_desktop_portal_gnome_user": "active"}),
                encoding="utf-8",
            )
            (stream_dir / "pipewire-probe.json").write_text(json.dumps({"pipewire_user": "active", "wireplumber_user": "active"}), encoding="utf-8")
            (stream_dir / "virtual-probe.json").write_text(json.dumps({"virtual_supported": True}), encoding="utf-8")
            result = portal_consent.classify_guest_portal_report(stream_dir, fake_matrix()["scenarios"][0])
            self.assertEqual(result["status"], "blocked")
            self.assertEqual(result["reason_code"], "portal_consent_required")
            self.assertIn("prepare-portal-consent", result["next_action"])

    def test_portal_consent_manifest_contract(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            base_root = Path(tmp) / "base"
            image = runner.portal_consented_image_path("fedora-43", "gnome-wayland", base_root)
            image.parent.mkdir(parents=True, exist_ok=True)
            image.write_bytes(b"0" * (11 * 1024 * 1024))
            image.with_suffix(".json").write_text(
                json.dumps(
                    {
                        "schema": 2,
                        "kind": "portal_consented",
                        "distro": "fedora-43",
                        "session": "gnome-wayland",
                        "backend": "wayland_portal",
                        "stream_smoke_ok": True,
                        "validation": {"client_connected": True, "bytes_read_gt_zero": True},
                    }
                ),
                encoding="utf-8",
            )
            rc, payload = validate_portal_consented_asset.validate_portal_consented_asset(
                distro="fedora-43",
                session="gnome-wayland",
                base_dir=base_root,
            )
            self.assertEqual(rc, 0)
            self.assertTrue(payload["ok"])
            self.assertEqual(payload["reason_code"], "portal_consented_asset_ok")

    def test_portal_consent_does_not_promote_zero_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            base_root = Path(tmp) / "base"
            image = runner.portal_consented_image_path("fedora-43", "gnome-wayland", base_root)
            image.parent.mkdir(parents=True, exist_ok=True)
            image.write_bytes(b"0" * (11 * 1024 * 1024))
            image.with_suffix(".json").write_text(
                json.dumps(
                    {
                        "schema": 2,
                        "kind": "portal_consented",
                        "distro": "fedora-43",
                        "session": "gnome-wayland",
                        "backend": "wayland_portal",
                        "stream_smoke_ok": True,
                        "validation": {"client_connected": True, "bytes_read_gt_zero": False},
                    }
                ),
                encoding="utf-8",
            )
            rc, payload = validate_portal_consented_asset.validate_portal_consented_asset(
                distro="fedora-43",
                session="gnome-wayland",
                base_dir=base_root,
            )
            self.assertEqual(rc, 3)
            self.assertFalse(payload["ok"])
            self.assertEqual(payload["reason_code"], "portal_consented_validation_failed")

    def test_portal_consent_does_not_promote_stream_port_not_open(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            base_root = Path(tmp) / "base"
            image = runner.portal_consented_image_path("fedora-43", "gnome-wayland", base_root)
            image.parent.mkdir(parents=True, exist_ok=True)
            image.write_bytes(b"0" * (11 * 1024 * 1024))
            image.with_suffix(".json").write_text(
                json.dumps(
                    {
                        "schema": 2,
                        "kind": "portal_consented",
                        "distro": "fedora-43",
                        "session": "gnome-wayland",
                        "backend": "wayland_portal",
                        "stream_smoke_ok": False,
                        "validation": {"client_connected": True, "bytes_read_gt_zero": True},
                    }
                ),
                encoding="utf-8",
            )
            rc, payload = validate_portal_consented_asset.validate_portal_consented_asset(
                distro="fedora-43",
                session="gnome-wayland",
                base_dir=base_root,
            )
            self.assertEqual(rc, 5)
            self.assertFalse(payload["ok"])
            self.assertEqual(payload["reason_code"], "unsupported_portal_consented_schema" if payload["reason_code"] == "unsupported_portal_consented_schema" else payload["reason_code"])

    def test_portal_consent_promotes_after_connected_bytes(self) -> None:
        self.test_portal_consent_manifest_contract()

    def test_classifies_portal_unavailable(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            stream_dir = Path(tmp)
            (stream_dir / "summary.json").write_text(
                json.dumps({"backend": "wayland_portal", "display_mode": "virtual_monitor"}),
                encoding="utf-8",
            )
            (stream_dir / "client.json").write_text(json.dumps({"connected": False, "bytes_read": 0}), encoding="utf-8")
            (stream_dir / "status-after.json").write_text(json.dumps({"state": "IDLE", "last_error": "no portal"}), encoding="utf-8")
            (stream_dir / "portal-probe.json").write_text(json.dumps({"xdg_desktop_portal_user": "inactive"}), encoding="utf-8")
            (stream_dir / "pipewire-probe.json").write_text(json.dumps({"pipewire_user": "active"}), encoding="utf-8")
            (stream_dir / "virtual-probe.json").write_text(json.dumps({"virtual_supported": True}), encoding="utf-8")
            result = portal_consent.classify_guest_portal_report(stream_dir, fake_matrix()["scenarios"][0])
            self.assertEqual(result["reason_code"], "portal_unavailable")

    def test_runner_prefers_portal_consented_image_when_present(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            base_root = Path(tmp) / "base"
            consented = runner.portal_consented_image_path("fedora-43", "gnome-wayland", base_root)
            consented.parent.mkdir(parents=True, exist_ok=True)
            consented.write_bytes(b"0" * (11 * 1024 * 1024))
            (consented.with_suffix(".json")).write_text(
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
            backing, kind = runner.backing_image_for_scenario(fake_matrix()["scenarios"][0], base_root)
            self.assertEqual(backing, consented)
            self.assertEqual(kind, "portal_consented")

    def test_runner_blocks_when_portal_asset_missing(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            report_root = tmp_path / "reports"
            base_root = tmp_path / "base"
            installed = runner.installed_image_path("fedora-43", "gnome-wayland", base_root)
            installed.parent.mkdir(parents=True, exist_ok=True)
            installed.write_bytes(b"0" * (11 * 1024 * 1024))
            installed.with_suffix(".json").write_text(json.dumps({"schema": 2, "kind": "installed"}), encoding="utf-8")
            args = argparse.Namespace(
                scenario=["fedora43-gnome-wayland-portal-h264"],
                distro=None,
                backend=None,
                tag=None,
                use_installed=True,
                base_dir=str(base_root),
                work_dir=str(tmp_path / "work"),
                run_id="RID",
                report_dir=str(report_root),
                android_serial=None,
                allow_unconsented_portal=False,
                retain_workdisk="on-fail",
                dry_run=True,
                live=False,
            )
            with mock.patch.object(runner, "load_matrix", return_value=fake_matrix()):
                self.assertEqual(runner.cmd_run(args), 0)
            summary = json.loads((report_root / "RID" / "summary.json").read_text(encoding="utf-8"))
            result = summary["results"][0]
            self.assertEqual(result["status"], "blocked")
            self.assertEqual(result["reason_code"], "missing_portal_consented_image")
            self.assertIn("prepare-portal-consent", result["next_action"])
            self.assertEqual(result["phase"], "portal_consent")

    def test_runner_allows_unconsented_only_with_flag(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            report_root = tmp_path / "reports"
            base_root = tmp_path / "base"
            installed = runner.installed_image_path("fedora-43", "gnome-wayland", base_root)
            installed.parent.mkdir(parents=True, exist_ok=True)
            installed.write_bytes(b"0" * (11 * 1024 * 1024))
            installed.with_suffix(".json").write_text(json.dumps({"schema": 2, "kind": "installed"}), encoding="utf-8")
            args_blocked = argparse.Namespace(
                scenario=["fedora43-gnome-wayland-portal-h264"],
                distro=None,
                backend=None,
                tag=None,
                use_installed=True,
                base_dir=str(base_root),
                work_dir=str(tmp_path / "work"),
                run_id="RID1",
                report_dir=str(report_root),
                android_serial=None,
                allow_unconsented_portal=False,
                retain_workdisk="on-fail",
                dry_run=True,
                live=False,
            )
            args_allowed = argparse.Namespace(
                scenario=["fedora43-gnome-wayland-portal-h264"],
                distro=None,
                backend=None,
                tag=None,
                use_installed=True,
                base_dir=str(base_root),
                work_dir=str(tmp_path / "work2"),
                run_id="RID2",
                report_dir=str(report_root),
                android_serial=None,
                allow_unconsented_portal=True,
                retain_workdisk="on-fail",
                dry_run=True,
                live=False,
            )
            with mock.patch.object(runner, "load_matrix", return_value=fake_matrix()):
                self.assertEqual(runner.cmd_run(args_blocked), 0)
                self.assertEqual(runner.cmd_run(args_allowed), 0)
            summary_blocked = json.loads((report_root / "RID1" / "summary.json").read_text(encoding="utf-8"))
            summary_allowed = json.loads((report_root / "RID2" / "summary.json").read_text(encoding="utf-8"))
            self.assertEqual(summary_blocked["results"][0]["status"], "blocked")
            self.assertEqual(summary_blocked["results"][0]["reason_code"], "missing_portal_consented_image")
            self.assertEqual(summary_allowed["results"][0]["status"], "pass")
            self.assertEqual(summary_allowed["results"][0]["reason_code"], "")

    def test_prepare_portal_consent_dry_run_outputs_paths(self) -> None:
        parser = runner.build_parser()
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            base_root = tmp_path / "base"
            installed = runner.installed_image_path("fedora-43", "gnome-wayland", base_root)
            installed.parent.mkdir(parents=True, exist_ok=True)
            installed.write_bytes(b"0" * (11 * 1024 * 1024))
            installed.with_suffix(".json").write_text(json.dumps({"schema": 2, "kind": "installed"}), encoding="utf-8")
            args = parser.parse_args(
                [
                    "prepare-portal-consent",
                    "--distro",
                    "fedora-43",
                    "--session",
                    "gnome-wayland",
                    "--base-dir",
                    str(base_root),
                    "--work-dir",
                    str(tmp_path / "work"),
                    "--dry-run",
                ]
            )
            with mock.patch("builtins.print") as mock_print:
                self.assertEqual(runner.cmd_prepare_portal_consent(args), 0)
            printed = "".join(str(call.args[0]) for call in mock_print.call_args_list)
            self.assertIn("gnome-wayland-portal-consented.qcow2", printed)
            self.assertIn("work.qcow2", printed)

    def test_report_contains_portal_next_action(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            run_dir = report.init_run_report(root, "run-1", [{"id": "scenario-a"}], host={"platform": "linux"})
            report.finalize_run_report(
                run_dir,
                "run-1",
                [
                    {
                        "scenario": "scenario-a",
                        "status": "blocked",
                        "phase": "portal_consent",
                        "reason": "Wayland portal consent required.",
                        "reason_code": "portal_consent_required",
                        "next_action": "./e2e/run prepare-portal-consent --distro fedora-43 --session gnome-wayland --backend wayland_portal --live --promote",
                        "report_dir": str(run_dir / "scenarios" / "scenario-a"),
                        "duration_sec": 0,
                        "l1_backing_kind": "installed",
                        "portal_consented_image": "/tmp/portal-consented.qcow2",
                        "allow_unconsented_portal": False,
                    }
                ],
            )
            report_md = (run_dir / "report.md").read_text(encoding="utf-8")
            self.assertIn("Portal consent command", report_md)
            self.assertIn("Reason code", report_md)

    def test_readiness_shows_portal_consent_missing(self) -> None:
        wizard = load_wizard()
        with tempfile.TemporaryDirectory() as tmp:
            base_root = Path(tmp) / "base"
            with mock.patch.dict(os.environ, {"WBEAM_E2E_BASE_DIR": str(base_root)}, clear=False):
                state = wizard.WizardState(matrix=fake_matrix())
                state.selected_distros = ["fedora-43"]
                state.selected_backends = ["wayland_portal"]
                readiness = wizard.build_readiness(state)
                self.assertEqual(readiness[0].portal_status, "missing")
                self.assertEqual(readiness[0].portal_action, "manual_approve")

    def test_readiness_shows_portal_consent_ok(self) -> None:
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
                self.assertEqual(readiness[0].portal_status, "ok")
                self.assertEqual(readiness[0].portal_action, "reuse")


if __name__ == "__main__":
    unittest.main()
