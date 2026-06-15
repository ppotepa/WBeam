#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


TEST_DIR = Path(__file__).resolve().parent
SCRIPTS_DIR = TEST_DIR.parent / "scripts"
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

import report  # noqa: E402
import finalize_e2e  # noqa: E402
import host_android_smoke  # noqa: E402
import assert_green_run  # noqa: E402
import runner  # noqa: E402
import seed  # noqa: E402
import vm  # noqa: E402


class MatrixTests(unittest.TestCase):
    def setUp(self) -> None:
        self.matrix = runner.load_matrix()

    def test_validate_matrix_accepts_current_matrix(self) -> None:
        self.assertEqual(runner.validate_matrix(self.matrix), [])

    def test_select_scenarios_by_distro_and_backend(self) -> None:
        args = argparse.Namespace(
            scenario=None,
            distro=["fedora-43"],
            backend=["evdi"],
            tag=None,
        )
        selected = runner.select_scenarios(self.matrix, args)
        self.assertTrue(selected)
        self.assertTrue(all(item["distro"] == "fedora-43" for item in selected))
        self.assertTrue(all(item["backend"] == "evdi" for item in selected))

    def test_run_parser_accepts_multiple_scenarios_run_id_report_dir(self) -> None:
        parser = runner.build_parser()
        args = parser.parse_args(
            [
                "run",
                "--scenario",
                "scenario-a",
                "--scenario",
                "scenario-b",
                "--run-id",
                "RID",
                "--report-dir",
                "/tmp/reports",
                "--dry-run",
            ]
        )
        self.assertEqual(args.scenario, ["scenario-a", "scenario-b"])
        self.assertEqual(args.run_id, "RID")
        self.assertEqual(args.report_dir, "/tmp/reports")
        self.assertFalse(args.allow_unconsented_portal)

    def test_runner_has_diagnose_run_command(self) -> None:
        parser = runner.build_parser()
        args = parser.parse_args(["diagnose-run", "--run-id", "R", "--scenario", "S"])
        self.assertEqual(getattr(args, "func"), runner.cmd_diagnose_run)
        self.assertEqual(args.run_id, "R")
        self.assertEqual(args.scenario, "S")

    def test_runner_has_portal_diagnose_command(self) -> None:
        parser = runner.build_parser()
        args = parser.parse_args(["portal-diagnose", "--run-id", "R", "--scenario", "S"])
        self.assertEqual(getattr(args, "func"), runner.cmd_portal_diagnose)
        self.assertEqual(args.run_id, "R")
        self.assertEqual(args.scenario, "S")

    def test_runner_has_close_command(self) -> None:
        parser = runner.build_parser()
        args = parser.parse_args(["close", "--profile", "fedora-mvp", "--json"])
        self.assertEqual(getattr(args, "func"), runner.cmd_close)
        self.assertEqual(args.profile, "fedora-mvp")
        self.assertTrue(args.json)

    def test_runner_has_prepare_portal_consent_command(self) -> None:
        parser = runner.build_parser()
        args = parser.parse_args(
            [
                "prepare-portal-consent",
                "--distro",
                "fedora-43",
                "--session",
                "gnome-wayland",
                "--backend",
                "wayland_portal",
                "--timeout-sec",
                "240",
                "--promote",
            ]
        )
        self.assertEqual(getattr(args, "func"), runner.cmd_prepare_portal_consent)
        self.assertEqual(args.distro, "fedora-43")
        self.assertEqual(args.session, "gnome-wayland")
        self.assertEqual(args.backend, "wayland_portal")
        self.assertEqual(args.timeout_sec, 240)
        self.assertTrue(args.promote)

    def test_prepare_portal_consent_dry_run_reports_paths(self) -> None:
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
                    "--backend",
                    "wayland_portal",
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
            self.assertIn("wayland_portal", printed)

    def test_resolve_portal_consent_display_live_without_display_uses_vnc(self) -> None:
        args = argparse.Namespace(display="auto", vnc_port=None)
        with mock.patch.dict(os.environ, {}, clear=True):
            display, extra_args, hint = runner.resolve_portal_consent_display(args, live=True)
        self.assertEqual(display, "none")
        self.assertEqual(len(extra_args), 2)
        self.assertEqual(extra_args[0], "-vnc")
        self.assertTrue(hint.startswith("vnc:127.0.0.1:"))

    def test_resolve_portal_consent_display_live_with_display_uses_gtk(self) -> None:
        args = argparse.Namespace(display="auto", vnc_port=None)
        with mock.patch.dict(os.environ, {"DISPLAY": ":99"}, clear=True):
            display, extra_args, hint = runner.resolve_portal_consent_display(args, live=True)
        self.assertEqual(display, "gtk")
        self.assertEqual(extra_args, ())
        self.assertEqual(hint, "gtk")

    def test_image_specs_group_by_distro_and_session(self) -> None:
        scenarios = [
            item
            for item in self.matrix["scenarios"]
            if item["distro"] == "fedora-43" and item["session"] == "gnome-wayland"
        ]
        specs = runner.image_specs(self.matrix, scenarios)
        self.assertEqual(len(specs), 1)
        spec = specs[0]
        self.assertEqual(spec["distro"], "fedora-43")
        self.assertEqual(spec["session"], "gnome-wayland")
        self.assertIn("evdi", spec["backends"])
        self.assertIn("wayland_portal", spec["backends"])

    def test_validate_matrix_rejects_required_device_without_android_execution(self) -> None:
        scenario = dict(self.matrix["scenarios"][0])
        scenario["id"] = "bad-required-device"
        scenario["device_policy"] = "required"
        scenario["android_execution"] = "none"
        matrix = {**self.matrix, "scenarios": [scenario]}
        errors = runner.validate_matrix(matrix)
        self.assertTrue(any("requires device but has android_execution=none" in error for error in errors))

    def test_guest_wizard_flags_skip_device_for_host_android(self) -> None:
        scenario = {
            "device_policy": "required",
            "android_execution": "host",
            "wizard_flags": ["--yes", "--stream-smoke"],
        }
        flags = runner.guest_wizard_flags_for_scenario(scenario)
        self.assertIn("--skip-device", flags)

    def test_backing_image_for_scenario_prefers_portal_consented_image(self) -> None:
        scenario = {
            "id": "fedora43-gnome-wayland-portal-h264",
            "distro": "fedora-43",
            "session": "gnome-wayland",
            "backend": "wayland_portal",
            "requires_portal": True,
        }
        with tempfile.TemporaryDirectory() as tmp:
            base_root = Path(tmp)
            consented = runner.portal_consented_image_path("fedora-43", "gnome-wayland", base_root)
            consented.parent.mkdir(parents=True, exist_ok=True)
            consented.write_bytes(b"0" * (11 * 1024 * 1024))
            consented.with_suffix(".json").write_text(
                json.dumps({"schema": 2, "kind": "portal_consented", "distro": "fedora-43", "session": "gnome-wayland", "backend": "wayland_portal", "stream_smoke_ok": True}),
                encoding="utf-8",
            )
            backing, kind = runner.backing_image_for_scenario(scenario, base_root)
            self.assertEqual(backing, consented)
            self.assertEqual(kind, "portal_consented")

    def test_backing_image_for_scenario_ignores_invalid_portal_manifest(self) -> None:
        scenario = {
            "id": "fedora43-gnome-wayland-portal-h264",
            "distro": "fedora-43",
            "session": "gnome-wayland",
            "backend": "wayland_portal",
            "requires_portal": True,
        }
        with tempfile.TemporaryDirectory() as tmp:
            base_root = Path(tmp)
            installed = runner.installed_image_path("fedora-43", "gnome-wayland", base_root)
            installed.parent.mkdir(parents=True, exist_ok=True)
            installed.write_bytes(b"0" * (11 * 1024 * 1024))
            installed.with_suffix(".json").write_text(json.dumps({"schema": 2, "kind": "installed"}), encoding="utf-8")
            consented = runner.portal_consented_image_path("fedora-43", "gnome-wayland", base_root)
            consented.parent.mkdir(parents=True, exist_ok=True)
            consented.write_bytes(b"0" * (11 * 1024 * 1024))
            consented.with_suffix(".json").write_text(json.dumps({"schema": 2, "kind": "portal_consented", "distro": "fedora-43", "session": "other", "backend": "wayland_portal", "stream_smoke_ok": False}), encoding="utf-8")
            backing, kind = runner.backing_image_for_scenario(scenario, base_root)
            self.assertEqual(backing, installed)
            self.assertEqual(kind, "installed")

    def test_run_blocks_invalid_portal_consented_image(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            report_root = tmp_path / "reports"
            base_root = tmp_path / "base"
            installed = runner.installed_image_path("fedora-43", "gnome-wayland", base_root)
            installed.parent.mkdir(parents=True, exist_ok=True)
            installed.write_bytes(b"0" * (11 * 1024 * 1024))
            installed.with_suffix(".json").write_text(json.dumps({"schema": 2, "kind": "installed"}), encoding="utf-8")
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
                        "stream_smoke_ok": False,
                    }
                ),
                encoding="utf-8",
            )
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
            with mock.patch.object(runner, "load_matrix", return_value=self.matrix):
                self.assertEqual(runner.cmd_run(args), 0)
            summary = json.loads((report_root / "RID" / "summary.json").read_text(encoding="utf-8"))
            result = summary["results"][0]
            self.assertEqual(result["status"], "blocked")
            self.assertEqual(result["reason_code"], "invalid_portal_consented_image")
            self.assertEqual(result["phase"], "portal_consent")

    def test_portal_consented_manifest_path(self) -> None:
        path = runner.portal_consented_manifest_path("fedora-43", "gnome-wayland", Path("/tmp/base"))
        self.assertEqual(path, Path("/tmp/base/fedora-43/gnome-wayland-portal-consented.json"))

    def test_runner_summarizes_stream_failure(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            summary_path = tmp_path / "summary.json"
            summary_path.write_text(
                json.dumps(
                    {
                        "run_id": "R",
                        "status": "fail",
                        "failures": [],
                        "last_step": {"id": "stream_smoke", "status": "fail", "summary": "stream smoke failed"},
                    }
                ),
                encoding="utf-8",
            )
            steps_path = tmp_path / "steps.jsonl"
            steps_path.write_text("", encoding="utf-8")
            stream_summary_path = tmp_path / "stream-summary.json"
            stream_summary_path.write_text(
                json.dumps(
                    {
                        "ok": False,
                        "phase": "stream_smoke",
                        "reason": "stream_tcp_no_bytes: client connected but received no bytes",
                        "next_action": "Inspect client.json",
                    }
                ),
                encoding="utf-8",
            )
            phase, reason, next_action = runner.summarize_guest_wizard_failure(summary_path, steps_path, stream_summary_path)
            self.assertEqual(phase, "stream_smoke")
            self.assertIn("stream_tcp_no_bytes", reason)
            self.assertEqual(next_action, "Inspect client.json")

    def test_diagnose_portal_consent_missing_outputs_next_action(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            base_root = tmp_path / "base"
            installed = runner.installed_image_path("fedora-43", "gnome-wayland", base_root)
            installed.parent.mkdir(parents=True, exist_ok=True)
            installed.write_bytes(b"0" * (11 * 1024 * 1024))
            installed.with_suffix(".json").write_text(json.dumps({"schema": 2, "kind": "installed"}), encoding="utf-8")
            args = argparse.Namespace(
                distro="fedora-43",
                session="gnome-wayland",
                base_dir=str(base_root),
                work_dir=str(tmp_path / "work"),
            )
            with mock.patch.object(runner, "load_matrix", return_value=self.matrix):
                with mock.patch("builtins.print") as mock_print:
                    self.assertEqual(runner.cmd_diagnose_portal_consent(args), 0)
            printed = "".join(str(call.args[0]) for call in mock_print.call_args_list)
            self.assertIn("portal_consented_exists", printed)
            self.assertIn("next_action", printed)
            self.assertIn("prepare-portal-consent", printed)

    def test_assert_run_parser_accepts_require_portal_consented(self) -> None:
        parser = runner.build_parser()
        args = parser.parse_args(
            [
                "assert-run",
                "--run-id",
                "RID",
                "--scenario",
                "scenario-a",
                "--require-portal-consented",
            ]
        )
        self.assertEqual(getattr(args, "func"), runner.cmd_assert_run)
        self.assertTrue(args.require_portal_consented)

    def test_assert_green_run_passes_with_stream_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            report_root = tmp_path / "reports"
            run_dir = report_root / "RID"
            run_dir.mkdir(parents=True, exist_ok=True)
            (run_dir / "summary.json").write_text(
                json.dumps(
                    {
                        "run_id": "RID",
                        "status": "pass",
                        "results": [
                            {
                                "scenario": "scenario-a",
                                "status": "pass",
                                "reason_code": "",
                                "l1_backing_kind": "portal-consented",
                                "l1_backing_image": "/tmp/gnome-wayland-portal-consented.qcow2",
                                "stream_summary": str(run_dir / "stream.json"),
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )
            (run_dir / "stream.json").write_text(
                json.dumps({"ok": True, "client": {"connected": True, "bytes_read": 10}}),
                encoding="utf-8",
            )
            rc, payload = assert_green_run.assert_green_run(
                report_root=report_root,
                run_id="RID",
                scenario="scenario-a",
                min_bytes=1,
                require_portal_consented=True,
            )
            self.assertEqual(rc, 0)
            self.assertTrue(payload["ok"])
            self.assertEqual(payload["l1_backing_kind"], "portal-consented")

    def test_assert_green_run_passes_with_portal_consented_and_bytes(self) -> None:
        self.test_assert_green_run_passes_with_stream_bytes()

    def test_assert_green_run_rejects_blocked_portal_consent(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            report_root = tmp_path / "reports"
            run_dir = report_root / "RID"
            run_dir.mkdir(parents=True, exist_ok=True)
            (run_dir / "summary.json").write_text(
                json.dumps(
                    {
                        "run_id": "RID",
                        "status": "blocked",
                        "results": [
                            {
                                "scenario": "scenario-a",
                                "status": "blocked",
                                "reason_code": "missing_portal_consented_image",
                                "next_action": "prepare",
                                "l1_backing_kind": "installed",
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )
            rc, payload = assert_green_run.assert_green_run(report_root=report_root, run_id="RID", scenario="scenario-a")
            self.assertEqual(rc, 1)
            self.assertFalse(payload["ok"])
            self.assertEqual(payload["reason_code"], "missing_portal_consented_image")

    def test_assert_green_run_fails_without_portal_consented_when_required(self) -> None:
        self.test_assert_green_run_rejects_blocked_portal_consent()

    def test_assert_green_run_rejects_missing_stream_summary(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            report_root = tmp_path / "reports"
            run_dir = report_root / "RID"
            run_dir.mkdir(parents=True, exist_ok=True)
            (run_dir / "summary.json").write_text(
                json.dumps(
                    {
                        "run_id": "RID",
                        "status": "pass",
                        "results": [
                            {
                                "scenario": "scenario-a",
                                "status": "pass",
                                "reason_code": "",
                                "l1_backing_kind": "portal-consented",
                                "l1_backing_image": "/tmp/gnome-wayland-portal-consented.qcow2",
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )
            rc, payload = assert_green_run.assert_green_run(report_root=report_root, run_id="RID", scenario="scenario-a")
            self.assertEqual(rc, 1)
            self.assertFalse(payload["ok"])
            self.assertEqual(payload["reason_code"], "stream_summary_missing")

    def test_assert_green_run_fails_when_stream_summary_missing(self) -> None:
        self.test_assert_green_run_rejects_missing_stream_summary()

    def test_assert_green_run_rejects_zero_bytes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            report_root = tmp_path / "reports"
            run_dir = report_root / "RID"
            run_dir.mkdir(parents=True, exist_ok=True)
            (run_dir / "summary.json").write_text(
                json.dumps(
                    {
                        "run_id": "RID",
                        "status": "pass",
                        "results": [
                            {
                                "scenario": "scenario-a",
                                "status": "pass",
                                "reason_code": "",
                                "l1_backing_kind": "portal-consented",
                                "l1_backing_image": "/tmp/gnome-wayland-portal-consented.qcow2",
                                "stream_summary": str(run_dir / "stream.json"),
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )
            (run_dir / "stream.json").write_text(json.dumps({"ok": True, "client": {"connected": True, "bytes_read": 0}}), encoding="utf-8")
            rc, payload = assert_green_run.assert_green_run(report_root=report_root, run_id="RID", scenario="scenario-a", min_bytes=1)
            self.assertEqual(rc, 1)
            self.assertFalse(payload["ok"])
            self.assertEqual(payload["reason_code"], "stream_no_bytes")

    def test_assert_green_run_fails_when_bytes_zero(self) -> None:
        self.test_assert_green_run_rejects_zero_bytes()

    def test_assert_green_run_fails_when_result_status_blocked(self) -> None:
        self.test_assert_green_run_rejects_blocked_portal_consent()

    def test_runner_normalize_wizard_result_maps_portal_consent_blocked(self) -> None:
        wizard_summary = {
            "status": "blocked",
            "reason_code": "portal_consent_required",
            "last_step": {
                "id": "stream_smoke",
                "status": "blocked",
                "summary": "Wayland portal consent required.",
                "next_action": "./e2e/run prepare-portal-consent --distro fedora-43 --session gnome-wayland --backend wayland_portal --live --promote",
                "evidence": {"reason_code": "portal_consent_required"},
            },
        }
        status, phase, reason, next_action = runner.normalize_wizard_result(wizard_summary, 20)
        self.assertEqual(status, "blocked")
        self.assertEqual(phase, "portal_consent")
        self.assertIn("Wayland portal consent required", reason)
        self.assertIn("prepare-portal-consent", next_action)

    def test_cmd_plan_uses_guest_wizard_flags_for_host_android(self) -> None:
        matrix = {
            "scenarios": [
                {
                    "id": "fedora43-gnome-wayland-portal-android-h264",
                    "distro": "fedora-43",
                    "session": "gnome-wayland",
                    "backend": "wayland_portal",
                    "display_mode": "duplicate",
                    "tier": "hardware",
                    "device_policy": "required",
                    "android_execution": "host",
                    "guest_wizard_flags": ["--yes", "--skip-device", "--stream-smoke"],
                    "expected_steps": ["host_preflight", "stream_smoke"],
                    "required_artifacts": ["guest/wizard/summary.json"],
                }
            ],
            "defaults": {"stream_duration_sec": 60},
        }

        args = argparse.Namespace(scenario=["fedora43-gnome-wayland-portal-android-h264"], distro=None, backend=None, tag=None)
        with mock.patch.object(runner, "load_matrix", return_value=matrix):
            with mock.patch.object(runner, "default_wizard_flags_for_scenario", side_effect=AssertionError("should not use default flags")):
                with mock.patch.object(runner, "print") as print_mock:
                    self.assertEqual(runner.cmd_plan(args), 0)
        rendered = "\n".join(str(call.args[0]) for call in print_mock.call_args_list)
        self.assertIn("--skip-device", rendered)
        self.assertIn("--stream-smoke", rendered)

    def test_scenario_requires_host_android(self) -> None:
        self.assertTrue(
            runner.scenario_requires_host_android(
                {"device_policy": "required", "android_execution": "host"}
            )
        )
        self.assertFalse(
            runner.scenario_requires_host_android(
                {"device_policy": "none", "android_execution": "host"}
            )
        )
        self.assertFalse(
            runner.scenario_requires_host_android(
                {"device_policy": "required", "android_execution": "guest_usb"}
            )
        )

    def test_adb_devices_ignores_daemon_noise_and_uses_devices_l(self) -> None:
        class Result:
            stdout = "\n".join(
                [
                    "* daemon not running; starting now at tcp:5037",
                    "* daemon started successfully",
                    "List of devices attached",
                    "ABC123\tdevice usb:1-5 product:x model:y",
                ]
            )
            stderr = ""

        with mock.patch.object(runner.subprocess, "run", return_value=Result()) as run_mock:
            rows = runner.adb_devices()

        self.assertEqual(rows, [{"serial": "ABC123", "state": "device"}])
        run_mock.assert_called_with(["adb", "devices", "-l"], capture_output=True, text=True, check=False, timeout=8)

    def test_select_android_serial_detects_ready_device_after_empty_retry(self) -> None:
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

        with mock.patch.object(runner.subprocess, "run", side_effect=fake_run):
            serial, status, reason = runner.select_android_serial()

        self.assertEqual(serial, "ABC123")
        self.assertEqual(status, "ok")
        self.assertEqual(reason, "single device ready")
        self.assertIn(["adb", "start-server"], calls)

    def test_android_preflight_reason_code_maps_to_allowed_blockers(self) -> None:
        self.assertEqual(runner.android_preflight_reason_code("device unauthorized; accept RSA prompt"), "android_device_unauthorized")
        self.assertEqual(runner.android_preflight_reason_code("device offline; reconnect USB"), "android_device_missing")
        self.assertEqual(runner.android_preflight_reason_code("no adb device"), "android_device_missing")

    def test_base_sanity_command_varies_by_session(self) -> None:
        headless = runner.base_sanity_command(session="headless")
        desktop = runner.base_sanity_command(session="gnome-wayland")
        self.assertNotIn("/etc/gdm/custom.conf", headless)
        self.assertIn("/etc/gdm/custom.conf", desktop)
        self.assertNotIn("AutomaticLogin=wbeam", desktop)
        self.assertNotIn("sudo -n true", desktop)

    def test_serial_log_formatter_strips_ansi_noise(self) -> None:
        line = "[\x1b[0;32m  OK  \x1b[0m] Started \x1b[0;1;39mNetworkManager.service\x1b[0m - Network Manager..."
        formatted = runner.format_serial_line("installer-fedora-43", line, color=False)
        self.assertIsNotNone(formatted)
        self.assertNotIn("\x1b", formatted or "")
        self.assertIn("[installer-fedora-43] OK", formatted or "")
        self.assertIn("NetworkManager.service", formatted or "")

    def test_serial_log_formatter_drops_audit_noise(self) -> None:
        line = "\x1b[0;21;32maudit: type=1400 audit(1781344814.870:203): avc: denied\x1b[0m"
        self.assertIsNone(runner.format_serial_line("installer-fedora-43", line, color=False))


class VmSyncTests(unittest.TestCase):
    def test_rsync_to_guest_excludes_heavy_local_artifacts(self) -> None:
        with mock.patch.object(vm, "require_tool", return_value="rsync"), mock.patch.object(vm, "run_cmd") as run_mock:
            vm.rsync_to_guest("wbeam", 2222, Path("/tmp/key"), Path("/repo"), "/home/wbeam/WBeam")
        cmd = run_mock.call_args.args[0]
        self.assertIn("--exclude=e2e/images/", cmd)
        self.assertIn("--exclude=e2e/work/", cmd)
        self.assertIn("--exclude=e2e/reports/", cmd)
        self.assertIn("--exclude=target/", cmd)

    def test_qemu_command_adds_control_and_stream_hostfwds(self) -> None:
        spec = vm.QemuSpec(
            name="test",
            disk=Path("/tmp/disk.qcow2"),
            ssh_port=2201,
            run_dir=Path("/tmp/run"),
            host_forwards=((25001, 5001), (27001, 5000)),
        )
        with mock.patch.object(vm, "require_tool", return_value="qemu-system-x86_64"):
            cmd = vm.qemu_command(spec)
        netdev = cmd[cmd.index("-netdev") + 1]
        self.assertIn("hostfwd=tcp:127.0.0.1:2201-:22", netdev)
        self.assertIn("hostfwd=tcp:127.0.0.1:25001-:5001", netdev)
        self.assertIn("hostfwd=tcp:127.0.0.1:27001-:5000", netdev)


class ArtifactPublicationTests(unittest.TestCase):
    def test_scenario_report_path_from_run_dir_does_not_duplicate_run_id(self) -> None:
        run_dir = Path("/tmp/reports/20260613T123140Z")
        path = runner.scenario_report_path_from_run_dir(run_dir, "fedora43")
        self.assertEqual(path, Path("/tmp/reports/20260613T123140Z/scenarios/fedora43"))
        self.assertNotIn("20260613T123140Z/20260613T123140Z", str(path))

    def test_scenario_workdisk_path_uses_runner_layout(self) -> None:
        path = runner.scenario_workdisk_path(Path("/tmp/work"), "RID", "scenario-a")
        self.assertEqual(path, Path("/tmp/work/runs/RID/scenario-a/disk.qcow2"))

    def test_validate_required_artifacts_reports_missing_paths(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "guest" / "wizard").mkdir(parents=True)
            (root / "guest" / "wizard" / "summary.json").write_text("{}", encoding="utf-8")
            scenario = {"required_artifacts": ["guest/wizard/summary.json", "guest/wizard/steps.jsonl"]}
            self.assertEqual(runner.validate_required_artifacts(root, scenario), ["guest/wizard/steps.jsonl"])

    def test_collect_host_info_tolerates_permission_error(self) -> None:
        calls = []

        def fake_run(cmd, capture_output=True, text=True, check=False):
            calls.append(cmd)
            if cmd and cmd[0] == "qemu-system-x86_64":
                raise PermissionError("not executable")

            class Result:
                stdout = ""

            return Result()

        with mock.patch.object(runner.subprocess, "run", side_effect=fake_run):
            info = runner.collect_host_info()

        self.assertIn("qemu_system", info)
        self.assertEqual(info["qemu_system"], "")
        self.assertGreaterEqual(len(calls), 1)

    def test_publish_scenario_artifacts_copies_guest_and_logs(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            run_dir = tmp_path / "work" / "scenario"
            guest_root = run_dir / "guest"
            (guest_root / "wizard").mkdir(parents=True)
            (guest_root / "wizard" / "summary.json").write_text("{}", encoding="utf-8")
            run_dir.mkdir(parents=True, exist_ok=True)
            (run_dir / "guest-wizard.log").write_text("wizard log", encoding="utf-8")
            report_dir = tmp_path / "reports" / "run" / "scenarios" / "scenario"

            runner.publish_scenario_artifacts(
                scenario_run_dir=run_dir,
                scenario_report_dir=report_dir,
                guest_report_root=guest_root,
            )

            self.assertTrue((report_dir / "guest" / "wizard" / "summary.json").exists())
            self.assertEqual((report_dir / "logs" / "guest-wizard.log").read_text(encoding="utf-8"), "wizard log")

    def test_host_android_smoke_dry_run_writes_summary(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            report_dir = Path(tmp) / "android"
            result = subprocess.run(
                [
                    sys.executable,
                    str(SCRIPTS_DIR / "host_android_smoke.py"),
                    "--serial",
                    "DRYRUN",
                    "--host-control-port",
                    "25001",
                    "--host-stream-port",
                    "27001",
                    "--backend",
                    "wayland_portal",
                    "--report-dir",
                    str(report_dir),
                    "--dry-run",
                ],
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            summary = json.loads((report_dir / "summary.json").read_text(encoding="utf-8"))
            self.assertTrue(summary["ok"])
            self.assertEqual(summary["android_execution"], "host")

    def test_cmd_run_dry_run_writes_aggregated_summary_for_multiple_scenarios(self) -> None:
        fake_matrix = {
            "scenarios": [
                {
                    "id": "scenario-a",
                    "distro": "fedora-43",
                    "session": "gnome-wayland",
                    "backend": "wayland_portal",
                    "display_mode": "duplicate",
                    "tier": "backend",
                    "device_policy": "none",
                    "android_execution": "none",
                    "required_artifacts": [],
                },
                {
                    "id": "scenario-b",
                    "distro": "fedora-43",
                    "session": "gnome-wayland",
                    "backend": "evdi",
                    "display_mode": "duplicate",
                    "tier": "backend",
                    "device_policy": "none",
                    "android_execution": "none",
                    "required_artifacts": [],
                },
            ],
            "defaults": {"stream_duration_sec": 60},
        }
        with tempfile.TemporaryDirectory() as tmp:
            report_root = Path(tmp) / "reports"
            args = argparse.Namespace(
                scenario=["scenario-a", "scenario-b"],
                distro=None,
                backend=None,
                tag=None,
                use_installed=True,
                base_dir=None,
                work_dir=str(Path(tmp) / "work"),
                run_id="RID",
                report_dir=str(report_root),
                android_serial=None,
                retain_workdisk="on-fail",
                dry_run=True,
                live=False,
            )
            with mock.patch.object(runner, "load_matrix", return_value=fake_matrix):
                self.assertEqual(runner.cmd_run(args), 0)
            summary = json.loads((report_root / "RID" / "summary.json").read_text(encoding="utf-8"))
            self.assertEqual(summary["scenarios_total"], 2)
            self.assertEqual(len(summary["results"]), 2)
            self.assertTrue(all(result["run_id"] == "RID" for result in summary["results"]))

    def test_cmd_rerun_last_failed_propagates_android_serial(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            report_root = Path(tmp)
            run_dir = report_root / "run-1"
            run_dir.mkdir(parents=True)
            summary = {
                "status": "fail",
                "results": [
                    {
                        "scenario": "fedora43-gnome-wayland-portal-android-h264",
                        "status": "fail",
                        "phase": "wizard",
                    }
                ],
            }
            (run_dir / "summary.json").write_text(json.dumps(summary), encoding="utf-8")

            captured = {}

            def fake_cmd_run(args: argparse.Namespace) -> int:
                captured["android_serial"] = getattr(args, "android_serial", None)
                captured["scenario"] = getattr(args, "scenario", None)
                return 0

            args = argparse.Namespace(
                report_dir=str(report_root),
                base_dir=None,
                work_dir=None,
                retain_workdisk="on-fail",
                dry_run=False,
                live=False,
                stop_on_fail=True,
                android_serial="SERIAL42",
            )
            with mock.patch.object(runner, "cmd_run", side_effect=fake_cmd_run):
                self.assertEqual(runner.cmd_rerun_last_failed(args), 0)

            self.assertEqual(captured["android_serial"], "SERIAL42")
            self.assertEqual(captured["scenario"], ["fedora43-gnome-wayland-portal-android-h264"])

    def test_android_deploy_env_sets_phone_ports_and_localhost(self) -> None:
        args = argparse.Namespace(
            serial="SERIAL1",
            phone_control_port=5101,
            phone_stream_port=5100,
        )
        env = host_android_smoke.android_deploy_env(args)
        self.assertEqual(env["WBEAM_ANDROID_SERIAL"], "SERIAL1")
        self.assertEqual(env["WBEAM_API_HOST"], "127.0.0.1")
        self.assertEqual(env["WBEAM_STREAM_HOST"], "127.0.0.1")
        self.assertEqual(env["WBEAM_CONTROL_PORT"], "5101")
        self.assertEqual(env["WBEAM_STREAM_PORT"], "5100")
        self.assertEqual(env["WBEAM_API_IMPL"], "host")

    def test_resolve_bytes_received_falls_back_to_logcat(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            metrics_local = tmp_path / "phone-metrics.json"
            metrics_local.write_text("{}", encoding="utf-8")
            logcat = tmp_path / "phone-logcat.log"
            logcat.write_text("WBeam frame decoded\nnoise\nWBeam rendered\n", encoding="utf-8")
            bytes_received, source, frame_events = host_android_smoke.resolve_bytes_received(metrics_local, logcat)
            self.assertEqual(source, "logcat_fallback")
            self.assertEqual(frame_events, 2)
            self.assertEqual(bytes_received, 2 * 16384)


class SeedTests(unittest.TestCase):
    def test_boot_append_args_cover_supported_families(self) -> None:
        fedora = {"family": "fedora"}
        ubuntu = {"family": "ubuntu"}
        debian = {"family": "debian"}
        fedora_args = seed.boot_append_args(distro=fedora, session="headless")
        self.assertIn("inst.ks=", fedora_args)
        self.assertIn("inst.stage2=hd:LABEL=Fedora-E-dvd-x86_64-43", fedora_args)
        self.assertIn("inst.text", fedora_args)
        self.assertIn("inst.sshd", fedora_args)
        self.assertIn("autoinstall", seed.boot_append_args(distro=ubuntu, session="headless"))
        self.assertIn("auto=true", seed.boot_append_args(distro=debian, session="headless"))
        self.assertNotIn("/cdrom/preseed.cfg", seed.boot_append_args(distro=debian, session="headless"))

    def test_create_seed_iso_uses_expected_label_and_writes_seed_files(self) -> None:
        distro = {"id": "fedora-43", "family": "fedora"}
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            output = tmp_path / "seed.iso"
            calls: list[tuple[Path, Path, str]] = []

            def fake_make_iso(source_dir: Path, output_path: Path, label: str) -> None:
                calls.append((source_dir, output_path, label))
                output_path.write_text("fake-iso", encoding="utf-8")

            with mock.patch.object(seed, "make_iso", side_effect=fake_make_iso):
                result = seed.create_seed_iso(
                    distro=distro,
                    session="headless",
                    ssh_user="wbeam",
                    public_key="ssh-ed25519 AAAA test",
                    output=output,
                )

            self.assertEqual(result, output)
            self.assertTrue(output.exists())
            self.assertEqual(len(calls), 1)
            source_dir, output_path, label = calls[0]
            self.assertEqual(output_path, output)
            self.assertEqual(label, "WBEAM-SEED")
            ks_cfg = source_dir / "ks.cfg"
            self.assertTrue(ks_cfg.exists())
            content = ks_cfg.read_text(encoding="utf-8")
            self.assertIn("poweroff", content)
            self.assertIn("sshkey --username=wbeam", content)
            self.assertIn("@core", content)
            self.assertNotIn("@workstation-product-environment", content)

    def test_desktop_seed_contains_gdm_autologin_and_session_selection(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)

            fedora_seed = tmp_path / "fedora"
            seed.write_fedora_seed(
                fedora_seed,
                distro_id="fedora-43",
                session="gnome-xorg",
                ssh_user="wbeam",
                public_key="ssh-ed25519 AAAA test",
            )
            fedora_content = (fedora_seed / "ks.cfg").read_text(encoding="utf-8")
            self.assertIn("@core", fedora_content)
            self.assertIn("@workstation-product-environment", fedora_content)
            self.assertIn("AutomaticLogin=wbeam", fedora_content)
            self.assertIn("WaylandEnable=false", fedora_content)
            self.assertIn("Session=gnome", fedora_content)
            self.assertNotIn("gnome-session-xsession", fedora_content)

            ubuntu_seed = tmp_path / "ubuntu"
            seed.write_ubuntu_seed(
                ubuntu_seed,
                distro_id="ubuntu-24.04",
                session="gnome-wayland",
                ssh_user="wbeam",
                public_key="ssh-ed25519 AAAA test",
            )
            ubuntu_content = (ubuntu_seed / "user-data").read_text(encoding="utf-8")
            self.assertIn("AutomaticLogin=wbeam", ubuntu_content)
            self.assertIn("Session=gnome", ubuntu_content)
            self.assertNotIn("WaylandEnable=false", ubuntu_content)

            debian_seed = tmp_path / "debian"
            seed.write_debian_seed(
                debian_seed,
                distro_id="debian-12",
                session="gnome-xorg",
                ssh_user="wbeam",
                public_key="ssh-ed25519 AAAA test",
            )
            debian_content = (debian_seed / "preseed.cfg").read_text(encoding="utf-8")
            self.assertIn("tasksel tasksel/first multiselect standard, ssh-server", debian_content)
            self.assertIn("gnome-core", debian_content)
            self.assertIn("xserver-xorg", debian_content)
            self.assertNotIn("task-gnome-desktop", debian_content)
            self.assertNotIn("gnome-session-xsession", debian_content)
            self.assertNotIn("preseed/late_command", debian_content)

    def test_systemd_unit_helper_uses_matching_target(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            systemd_dir = root / "lib" / "systemd" / "system"
            systemd_dir.mkdir(parents=True)
            (systemd_dir / "ssh.service").write_text("[Service]\n", encoding="utf-8")
            (systemd_dir / "ssh.socket").write_text("[Socket]\n", encoding="utf-8")

            log = root / "enable.log"
            runner.ensure_systemd_unit_enabled(root, "ssh.service", log)
            runner.ensure_systemd_unit_enabled(root, "ssh.socket", log)

            self.assertTrue((root / "etc" / "systemd" / "system" / "multi-user.target.wants" / "ssh.service").is_symlink())
            self.assertTrue((root / "etc" / "systemd" / "system" / "sockets.target.wants" / "ssh.socket").is_symlink())
            self.assertFalse((root / "etc" / "systemd" / "system" / "multi-user.target.wants" / "ssh.socket").exists())
            self.assertFalse((root / "etc" / "systemd" / "system" / "sockets.target.wants" / "ssh.service").exists())


class ReportTests(unittest.TestCase):
    def test_finalize_run_report_writes_summary_and_junit(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            run_dir = report.init_run_report(
                root,
                "run-1",
                [{"id": "scenario-a"}],
                host={"platform": "linux"},
            )
            report.finalize_run_report(
                run_dir,
                "run-1",
                [
                    {
                        "scenario": "scenario-a",
                        "status": "fail",
                        "phase": "stream",
                        "reason": "no bytes",
                        "guest_command": "./install-wbeam --stream-smoke",
                        "report_dir": "/tmp/report",
                        "duration_sec": 12,
                    }
                ],
            )
            summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
            junit = (run_dir / "junit.xml").read_text(encoding="utf-8")
            report_md = (run_dir / "report.md").read_text(encoding="utf-8")
            host = json.loads((run_dir / "host.json").read_text(encoding="utf-8"))
            self.assertEqual(summary["status"], "fail")
            self.assertEqual(summary["scenarios_failed"], 1)
            self.assertIn("no bytes", junit)
            self.assertIn("scenario-a", report_md)
            self.assertIn("Guest command", report_md)
            self.assertEqual(host["platform"], "linux")

    def test_finalize_run_report_counts_blocked_and_reboot_required(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            run_dir = report.init_run_report(
                root,
                "run-2",
                [{"id": "scenario-blocked"}, {"id": "scenario-reboot"}],
                host={"platform": "linux"},
            )
            report.finalize_run_report(
                run_dir,
                "run-2",
                [
                    {
                        "scenario": "scenario-blocked",
                        "status": "blocked",
                        "phase": "wizard",
                        "reason": "adb unauthorized",
                        "report_dir": "/tmp/report-blocked",
                        "duration_sec": 1,
                    },
                    {
                        "scenario": "scenario-reboot",
                        "status": "reboot_required",
                        "phase": "wizard",
                        "reason": "mok pending",
                        "report_dir": "/tmp/report-reboot",
                        "duration_sec": 2,
                    },
                ],
            )
            summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
            self.assertEqual(summary["scenarios_blocked"], 1)
            self.assertEqual(summary["scenarios_reboot_required"], 1)
            self.assertEqual(summary["status_counts"]["blocked"], 1)
            self.assertEqual(summary["status_counts"]["reboot_required"], 1)
            self.assertEqual(summary["status"], "blocked")

    def test_finalize_run_report_marks_reboot_required_top_level_status(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            run_dir = report.init_run_report(
                root,
                "run-3",
                [{"id": "scenario-reboot"}],
                host={"platform": "linux"},
            )
            report.finalize_run_report(
                run_dir,
                "run-3",
                [
                    {
                        "scenario": "scenario-reboot",
                        "status": "reboot_required",
                        "phase": "wizard",
                        "reason": "mok pending",
                        "report_dir": "/tmp/report-reboot",
                        "duration_sec": 2,
                    }
                ],
            )
            summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
            self.assertEqual(summary["status"], "reboot_required")

    def test_finalize_run_report_marks_blocked_top_level_status(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            run_dir = report.init_run_report(
                root,
                "run-4",
                [{"id": "scenario-blocked"}],
                host={"platform": "linux"},
            )
            report.finalize_run_report(
                run_dir,
                "run-4",
                [
                    {
                        "scenario": "scenario-blocked",
                        "status": "blocked",
                        "phase": "android-preflight",
                        "reason": "device unauthorized",
                        "report_dir": "/tmp/report-blocked",
                        "duration_sec": 0,
                    }
                ],
            )
            summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
            self.assertEqual(summary["status"], "blocked")

    def test_status_snapshot_reports_progress_and_partial_readiness(self) -> None:
        matrix = runner.load_matrix()
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            report_root = tmp_path / "reports"
            report.init_run_report(
                report_root,
                "run-1",
                [{"id": "scenario-a"}],
                host={"platform": "linux"},
            )
            report.finalize_run_report(
                report_root / "run-1",
                "run-1",
                [
                    {
                        "scenario": "scenario-a",
                        "status": "pass",
                        "phase": "dry-run",
                        "reason": "",
                        "report_dir": str(report_root / "run-1" / "scenarios" / "scenario-a"),
                        "duration_sec": 0,
                    }
                ],
            )
            fail_run = report.init_run_report(
                report_root,
                "run-2",
                [{"id": "scenario-b"}],
                host={"platform": "linux"},
            )
            report.finalize_run_report(
                fail_run,
                "run-2",
                [
                    {
                        "scenario": "scenario-b",
                        "status": "fail",
                        "phase": "wizard",
                        "reason": "boom",
                        "report_dir": str(fail_run / "scenarios" / "scenario-b"),
                        "duration_sec": 1,
                    }
                ],
            )
            snapshot = runner.status_snapshot(matrix, base_root=tmp_path / "base", report_root=report_root)
            self.assertGreater(snapshot["percent"], 0)
            self.assertGreaterEqual(snapshot["asset_percent"], 0)
            self.assertEqual(snapshot["assets_total"], 18)
            self.assertEqual(snapshot["base_images_ready"], 0)
            self.assertEqual(snapshot["installed_images_ready"], 0)
            self.assertFalse(snapshot["live_run_verified"])
            self.assertTrue(snapshot["dry_run_verified"])
            self.assertEqual(snapshot["report_runs"], 2)
            self.assertEqual(snapshot["last_run"]["run_id"], "run-2")
            self.assertEqual(snapshot["last_failed_run"]["run_id"], "run-2")
            self.assertEqual(snapshot["last_failed_scenario"], "scenario-b")
            self.assertEqual(snapshot["last_failed_reason"], "boom")
            self.assertIn("./e2e/run rerun-last-failed --live", snapshot["recovery_commands"])
            self.assertEqual(len(snapshot["missing_iso_inputs"]), 3)
            self.assertEqual(len(snapshot["missing_base_images"]), 9)
            self.assertIn("WBEAM_E2E_ISO_FEDORA_43", {item["env"] for item in snapshot["missing_iso_inputs"]})
            self.assertIn("./e2e/run init-env", snapshot["next_commands"])
            self.assertIn("./e2e/run iso-sources", snapshot["next_commands"])
            self.assertIn('eval "$(./e2e/run env-shell)"', snapshot["next_commands"])
            self.assertTrue(any(command.startswith("export WBEAM_E2E_ISO_FEDORA_43=") for command in snapshot["next_commands"]))
            self.assertIn("./e2e/run prepare-base --all --missing --live", snapshot["next_commands"])
            self.assertIn("./e2e/run prepare-installed --distro fedora-43 --session headless --live", snapshot["next_commands"])
            self.assertIn("./e2e/run run --scenario fedora43-headless-benchmark-h264 --live", snapshot["next_commands"])
            self.assertIn("./e2e/run history", snapshot["next_commands"])
            self.assertIn("./e2e/run last-failed", snapshot["next_commands"])
            self.assertIn("./e2e/run rerun-last-failed --live", snapshot["next_commands"])

    def test_cmd_report_prints_wizard_artifacts(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            report_root = Path(tmp) / "reports"
            run_dir = report.init_run_report(
                report_root,
                "run-1",
                [{"id": "scenario-a"}],
                host={"platform": "linux"},
            )
            report.finalize_run_report(
                run_dir,
                "run-1",
                [
                    {
                        "scenario": "scenario-a",
                        "status": "pass",
                        "phase": "wizard",
                        "reason": "pass",
                        "report_dir": str(run_dir / "scenarios" / "scenario-a"),
                        "duration_sec": 12,
                        "wizard_summary": "/tmp/wizard/summary.json",
                        "wizard_steps": "/tmp/wizard/steps.jsonl",
                        "stream_summary": "/tmp/wizard/stream/summary.json",
                        "workdisk_policy": "on-fail",
                        "workdisk_retained": False,
                    }
                ],
            )
            args = argparse.Namespace(run_id="run-1", report_dir=str(report_root))
            with mock.patch("builtins.print") as print_mock:
                rc = runner.cmd_report(args)
            self.assertEqual(rc, 0)
            printed = "\n".join(str(call.args[0]) for call in print_mock.call_args_list if call.args)
            self.assertIn("/tmp/wizard/summary.json", printed)
            self.assertIn("/tmp/wizard/steps.jsonl", printed)
            self.assertIn("/tmp/wizard/stream/summary.json", printed)
            self.assertIn("workdisk_policy", printed)

    def test_cmd_report_prints_portal_fields(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            report_root = Path(tmp) / "reports"
            run_dir = report.init_run_report(
                report_root,
                "run-portal",
                [{"id": "scenario-a"}],
                host={"platform": "linux"},
            )
            report.finalize_run_report(
                run_dir,
                "run-portal",
                [
                    {
                        "scenario": "scenario-a",
                        "status": "blocked",
                        "phase": "portal_consent",
                        "reason": "Wayland portal consent required.",
                        "reason_code": "portal_consent_required",
                        "report_dir": str(run_dir / "scenarios" / "scenario-a"),
                        "duration_sec": 0,
                        "portal_consented_image": "/tmp/portal-consented.qcow2",
                        "l1_backing_kind": "installed",
                        "allow_unconsented_portal": False,
                        "next_action": "./e2e/run prepare-portal-consent --distro fedora-43 --session gnome-wayland --backend wayland_portal --live --promote",
                    }
                ],
            )
            args = argparse.Namespace(run_id="run-portal", report_dir=str(report_root))
            with mock.patch("builtins.print") as print_mock:
                rc = runner.cmd_report(args)
            self.assertEqual(rc, 0)
            printed = "\n".join(str(call.args[0]) for call in print_mock.call_args_list if call.args)
            self.assertIn("portal_consented_image", printed)
            self.assertIn("reason_code", printed)
            self.assertIn("portal_consent", printed)

    def test_cmd_report_prints_status_counts(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            report_root = Path(tmp) / "reports"
            run_dir = report.init_run_report(
                report_root,
                "run-2",
                [{"id": "scenario-a"}],
                host={"platform": "linux"},
            )
            report.finalize_run_report(
                run_dir,
                "run-2",
                [
                    {
                        "scenario": "scenario-a",
                        "status": "blocked",
                        "phase": "wizard",
                        "reason": "blocked",
                        "report_dir": str(run_dir / "scenarios" / "scenario-a"),
                        "duration_sec": 1,
                        "workdisk_policy": "on-fail",
                        "workdisk_retained": True,
                    }
                ],
            )
            args = argparse.Namespace(run_id="run-2", report_dir=str(report_root))
            with mock.patch("builtins.print") as print_mock:
                rc = runner.cmd_report(args)
            self.assertEqual(rc, 0)
            printed = "\n".join(str(call.args[0]) for call in print_mock.call_args_list if call.args)
            self.assertIn("status_counts", printed)
            self.assertIn("blocked", printed)

    def test_cmd_report_marks_corrupt_summary(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            report_root = Path(tmp) / "reports"
            run_dir = report_root / "run-1"
            run_dir.mkdir(parents=True, exist_ok=True)
            (run_dir / "summary.json").write_text('{"run_id":"broken"', encoding="utf-8")
            args = argparse.Namespace(run_id="run-1", report_dir=str(report_root))
            with mock.patch("builtins.print") as print_mock:
                rc = runner.cmd_report(args)
            self.assertEqual(rc, 0)
            printed = "\n".join(str(call.args[0]) for call in print_mock.call_args_list if call.args)
            self.assertIn("summary_corrupt", printed)
            self.assertIn("summary_path", printed)

    def test_finalize_prefers_pass_over_later_blocked_report(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)

            pass_dir = root / "FEDORA-WAYLAND-LIVE-999"
            pass_dir.mkdir()
            (pass_dir / "summary.json").write_text(
                json.dumps(
                    {
                        "results": [
                            {
                                "scenario": "fedora43-gnome-wayland-portal-h264",
                                "status": "pass",
                                "run_id": "FEDORA-WAYLAND-LIVE-999",
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )

            blocked_dir = root / "PORTAL-BLOCK-DRYRUN-999"
            blocked_dir.mkdir()
            (blocked_dir / "summary.json").write_text(
                json.dumps(
                    {
                        "results": [
                            {
                                "scenario": "fedora43-gnome-wayland-portal-h264",
                                "status": "blocked",
                                "run_id": "PORTAL-BLOCK-DRYRUN-999",
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )

            result, run_dir = finalize_e2e.latest_result_for_scenario(
                root,
                "fedora43-gnome-wayland-portal-h264",
            )

            self.assertEqual(result["status"], "pass")
            self.assertEqual(run_dir.name, "FEDORA-WAYLAND-LIVE-999")

    def test_history_and_last_failed_report_latest_runs(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            report_root = Path(tmp) / "reports"
            pass_run = report.init_run_report(
                report_root,
                "20260613T010000Z",
                [{"id": "scenario-a"}],
                host={"platform": "linux"},
            )
            report.finalize_run_report(
                pass_run,
                "20260613T010000Z",
                [
                    {
                        "scenario": "scenario-a",
                        "status": "pass",
                        "phase": "wizard",
                        "reason": "",
                        "report_dir": str(pass_run / "scenarios" / "scenario-a"),
                        "duration_sec": 1,
                    }
                ],
            )
            fail_run = report.init_run_report(
                report_root,
                "20260613T020000Z",
                [{"id": "scenario-b"}],
                host={"platform": "linux"},
            )
            report.finalize_run_report(
                fail_run,
                "20260613T020000Z",
                [
                    {
                        "scenario": "scenario-b",
                        "status": "fail",
                        "phase": "stream",
                        "reason": "boom",
                        "report_dir": str(fail_run / "scenarios" / "scenario-b"),
                        "duration_sec": 2,
                    }
                ],
            )
            args = argparse.Namespace(report_dir=str(report_root), limit=5, failed_only=False)
            with mock.patch("builtins.print") as print_mock:
                rc = runner.cmd_history(args)
            self.assertEqual(rc, 0)
            entries = json.loads("\n".join(str(call.args[0]) for call in print_mock.call_args_list if call.args))
            self.assertEqual(entries["entries"][0]["run_id"], "20260613T020000Z")
            self.assertEqual(entries["entries"][1]["run_id"], "20260613T010000Z")
            self.assertEqual(entries["entries"][0]["failed_scenarios"], ["scenario-b"])
            self.assertEqual(entries["entries"][0]["failure_count"], 1)
            self.assertEqual(entries["entries"][0]["scenarios_failed"], 1)
            self.assertEqual(entries["entries"][0]["scenarios_blocked"], 0)
            self.assertIn("last_failure_workdisk_policy", entries["entries"][0])
            self.assertIn("last_failure_workdisk_retained", entries["entries"][0])

            args = argparse.Namespace(report_dir=str(report_root))
            with mock.patch("builtins.print") as print_mock:
                rc = runner.cmd_last_failed(args)
            self.assertEqual(rc, 0)
            entry = json.loads("\n".join(str(call.args[0]) for call in print_mock.call_args_list if call.args))
            self.assertEqual(entry["run_id"], "20260613T020000Z")
            self.assertEqual(entry["status"], "fail")
            self.assertEqual(entry["failed_scenarios"], ["scenario-b"])
            self.assertEqual(entry["scenarios_failed"], 1)

    def test_history_skips_corrupt_runs(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            report_root = Path(tmp) / "reports"
            corrupt_run = report_root / "20260613T010000Z"
            corrupt_run.mkdir(parents=True, exist_ok=True)
            (corrupt_run / "summary.json").write_text('{"run_id":"broken"', encoding="utf-8")
            valid_run = report.init_run_report(
                report_root,
                "20260613T020000Z",
                [{"id": "scenario-b"}],
                host={"platform": "linux"},
            )
            report.finalize_run_report(
                valid_run,
                "20260613T020000Z",
                [
                    {
                        "scenario": "scenario-b",
                        "status": "fail",
                        "phase": "stream",
                        "reason": "boom",
                        "report_dir": str(valid_run / "scenarios" / "scenario-b"),
                        "duration_sec": 2,
                    }
                ],
            )
            args = argparse.Namespace(report_dir=str(report_root), limit=5, failed_only=False)
            with mock.patch("builtins.print") as print_mock:
                rc = runner.cmd_history(args)
            self.assertEqual(rc, 0)
            entries = json.loads("\n".join(str(call.args[0]) for call in print_mock.call_args_list if call.args))
            self.assertEqual(entries["skipped_corrupt_runs"], 1)
            self.assertEqual(len(entries["entries"]), 1)
            self.assertEqual(entries["entries"][0]["run_id"], "20260613T020000Z")

    def test_rerun_last_failed_replays_failed_scenarios(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            report_root = Path(tmp) / "reports"
            fail_run = report.init_run_report(
                report_root,
                "20260613T020000Z",
                [{"id": "scenario-a"}, {"id": "scenario-b"}],
                host={"platform": "linux"},
            )
            report.finalize_run_report(
                fail_run,
                "20260613T020000Z",
                [
                    {
                        "scenario": "scenario-a",
                        "status": "fail",
                        "phase": "wizard",
                        "reason": "boom-a",
                        "report_dir": str(fail_run / "scenarios" / "scenario-a"),
                        "duration_sec": 1,
                    },
                    {
                        "scenario": "scenario-b",
                        "status": "fail",
                        "phase": "stream",
                        "reason": "boom-b",
                        "report_dir": str(fail_run / "scenarios" / "scenario-b"),
                        "duration_sec": 2,
                    },
                ],
            )
            args = argparse.Namespace(
                report_dir=str(report_root),
                base_dir=None,
                work_dir=None,
                retain_workdisk="on-fail",
                dry_run=False,
                live=False,
                stop_on_fail=True,
            )
            seen: list[list[str]] = []

            def fake_cmd_run(rerun_args):
                seen.append(rerun_args.scenario)
                return 0

            with mock.patch.object(runner, "cmd_run", side_effect=fake_cmd_run):
                rc = runner.cmd_rerun_last_failed(args)

            self.assertEqual(rc, 0)
            self.assertEqual(seen, [["scenario-a"], ["scenario-b"]])

    def test_rerun_last_failed_can_continue_past_failures(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            report_root = Path(tmp) / "reports"
            fail_run = report.init_run_report(
                report_root,
                "20260613T020000Z",
                [{"id": "scenario-a"}, {"id": "scenario-b"}],
                host={"platform": "linux"},
            )
            report.finalize_run_report(
                fail_run,
                "20260613T020000Z",
                [
                    {
                        "scenario": "scenario-a",
                        "status": "fail",
                        "phase": "wizard",
                        "reason": "boom-a",
                        "report_dir": str(fail_run / "scenarios" / "scenario-a"),
                        "duration_sec": 1,
                    },
                    {
                        "scenario": "scenario-b",
                        "status": "fail",
                        "phase": "stream",
                        "reason": "boom-b",
                        "report_dir": str(fail_run / "scenarios" / "scenario-b"),
                        "duration_sec": 2,
                    },
                ],
            )
            args = argparse.Namespace(
                report_dir=str(report_root),
                base_dir=None,
                work_dir=None,
                retain_workdisk="on-fail",
                dry_run=False,
                live=False,
                stop_on_fail=False,
            )
            seen: list[list[str]] = []

            def fake_cmd_run(rerun_args):
                seen.append(rerun_args.scenario)
                return 1 if len(seen) == 1 else 0

            with mock.patch.object(runner, "cmd_run", side_effect=fake_cmd_run):
                rc = runner.cmd_rerun_last_failed(args)

            self.assertEqual(rc, 0)
            self.assertEqual(seen, [["scenario-a"], ["scenario-b"]])


class DryRunTests(unittest.TestCase):
    def setUp(self) -> None:
        self.matrix = runner.load_matrix()

    def test_prepare_base_dry_run_without_iso_env(self) -> None:
        args = argparse.Namespace(
            distro=["fedora-43"],
            backend=None,
            scenario=None,
            tag=None,
            session=["headless"],
            all=False,
            base_dir=None,
            work_dir=None,
            force=False,
            dry_run=True,
        )
        with mock.patch("builtins.print") as print_mock:
            rc = runner.cmd_prepare_base(args)
        self.assertEqual(rc, 0)
        printed = "\n".join(str(call.args[0]) for call in print_mock.call_args_list if call.args)
        self.assertIn('"iso": "<unset>"', printed)

    def test_run_dry_run_writes_report_and_host_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            report_root = tmp_path / "reports"
            work_root = tmp_path / "work"
            base_root = tmp_path / "base"
            args = argparse.Namespace(
                distro=None,
                backend=None,
                scenario=["fedora43-headless-benchmark-h264"],
                tag=None,
                all=False,
                run_id="dry-run-1",
                base_dir=str(base_root),
                work_dir=str(work_root),
                report_dir=str(report_root),
                copy_mode="overlay",
                retain_workdisk="on-fail",
                force=False,
                stop_on_fail=False,
                dry_run=True,
            )
            rc = runner.cmd_run(args)
            self.assertEqual(rc, 0)
            summary = json.loads((report_root / "dry-run-1" / "summary.json").read_text(encoding="utf-8"))
            host = json.loads((report_root / "dry-run-1" / "host.json").read_text(encoding="utf-8"))
            self.assertEqual(summary["status"], "pass")
            self.assertEqual(summary["scenarios_total"], 1)
            self.assertIn("repo_root", host)

    def test_prepare_installed_removes_partial_work_disk_before_overlay(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            base_root = tmp_path / "base"
            work_root = tmp_path / "work"
            base_image = base_root / "fedora-43" / "gnome-wayland.qcow2"
            base_image.parent.mkdir(parents=True, exist_ok=True)
            base_image.write_text("base", encoding="utf-8")
            work_disk = work_root / "installed" / "fedora-43" / "gnome-wayland" / "work.qcow2"
            work_disk.parent.mkdir(parents=True, exist_ok=True)
            work_disk.write_text("partial", encoding="utf-8")
            args = argparse.Namespace(
                distro="fedora-43",
                session="gnome-wayland",
                base_dir=str(base_root),
                work_dir=str(work_root),
                dry_run=False,
                force=False,
                missing=False,
                live=False,
            )

            def fake_overlay(base: Path, target: Path) -> None:
                self.assertEqual(base, base_image)
                self.assertEqual(target, work_disk)
                self.assertFalse(target.exists())
                raise RuntimeError("stop after overlay check")

            with mock.patch.object(runner, "qemu_img_overlay", side_effect=fake_overlay):
                self.assertEqual(runner.cmd_prepare_installed(args), 1)
            self.assertFalse((base_root / "fedora-43" / "gnome-wayland-installed.qcow2").exists())
            self.assertTrue((work_root / "installed" / "fedora-43" / "gnome-wayland" / "prepare-installed-failure.json").exists())

    def test_should_keep_workdisk_policy_matrix(self) -> None:
        self.assertFalse(runner.should_keep_workdisk("on-fail", succeeded=True))
        self.assertTrue(runner.should_keep_workdisk("on-fail", succeeded=False))
        self.assertTrue(runner.should_keep_workdisk("always", succeeded=True))
        self.assertTrue(runner.should_keep_workdisk("always", succeeded=False))
        self.assertFalse(runner.should_keep_workdisk("never", succeeded=True))
        self.assertFalse(runner.should_keep_workdisk("never", succeeded=False))
        self.assertTrue(runner.should_keep_workdisk("on-success", succeeded=True))
        self.assertFalse(runner.should_keep_workdisk("on-success", succeeded=False))

    def test_env_shell_prints_export_lines(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            env_path = Path(tmp) / "env.local"
            env_path.write_text(
                "\n".join(
                    [
                        "# comment",
                        "WBEAM_E2E_ISO_FEDORA_43=/isos/fedora.iso",
                        "WBEAM_E2E_WORK_DIR=/tmp/wbeam-work",
                    ]
                )
                + "\n",
                encoding="utf-8",
            )
            args = argparse.Namespace(file=str(env_path))
            with mock.patch("builtins.print") as print_mock:
                rc = runner.cmd_env_shell(args)
            self.assertEqual(rc, 0)
            lines = [str(call.args[0]) for call in print_mock.call_args_list if call.args]
            self.assertIn("export WBEAM_E2E_ISO_FEDORA_43=/isos/fedora.iso", lines)
            self.assertIn("export WBEAM_E2E_WORK_DIR=/tmp/wbeam-work", lines)

    def test_load_env_local_sets_missing_values_without_overriding(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            env_path = Path(tmp) / "env.local"
            env_path.write_text(
                "\n".join(
                    [
                        "WBEAM_E2E_ISO_FEDORA_43=/isos/from-file.iso",
                        "WBEAM_E2E_ISO_UBUNTU_24_04=/isos/ubuntu.iso",
                    ]
                )
                + "\n",
                encoding="utf-8",
            )
            with mock.patch.dict(
                os.environ,
                {"WBEAM_E2E_ISO_FEDORA_43": "/isos/from-shell.iso"},
                clear=False,
            ):
                os.environ.pop("WBEAM_E2E_ISO_UBUNTU_24_04", None)
                runner.load_env_local(env_path)
                self.assertEqual(os.environ["WBEAM_E2E_ISO_FEDORA_43"], "/isos/from-shell.iso")
                self.assertEqual(os.environ["WBEAM_E2E_ISO_UBUNTU_24_04"], "/isos/ubuntu.iso")

    def test_init_env_copies_example(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "env.local"
            args = argparse.Namespace(file=str(target), force=False)
            with mock.patch("builtins.print") as print_mock:
                rc = runner.cmd_init_env(args)
            self.assertEqual(rc, 0)
            self.assertTrue(target.exists())
            self.assertIn("WBEAM_E2E_ISO_FEDORA_43=", target.read_text(encoding="utf-8"))
            printed = [str(call.args[0]) for call in print_mock.call_args_list if call.args]
            self.assertIn(str(target.resolve()), printed)

    def test_next_prints_only_command_queue(self) -> None:
        args = argparse.Namespace(json=False)
        with mock.patch("builtins.print") as print_mock:
            rc = runner.cmd_next(args)
        self.assertEqual(rc, 0)
        lines = [str(call.args[0]) for call in print_mock.call_args_list if call.args]
        self.assertIn("./e2e/run init-env", lines)
        self.assertIn("./e2e/run iso-sources", lines)
        self.assertIn("./e2e/run status", lines)
        self.assertTrue(all(line and not line.startswith("[e2e] ") for line in lines))

    def test_iso_sources_prints_known_official_pages(self) -> None:
        args = argparse.Namespace(json=False)
        with mock.patch("builtins.print") as print_mock:
            rc = runner.cmd_iso_sources(args)
        self.assertEqual(rc, 0)
        lines = [str(call.args[0]) for call in print_mock.call_args_list if call.args]
        self.assertTrue(any("download.fedoraproject.org" in line for line in lines))
        self.assertTrue(any("releases.ubuntu.com/noble" in line for line in lines))
        self.assertTrue(any("debian.org/releases/bookworm/debian-installer" in line for line in lines))
        self.assertTrue(any("Fedora-Everything-netinst-x86_64-43-1.6.iso" in line for line in lines))
        self.assertTrue(any("ubuntu-24.04.4-desktop-amd64.iso" in line for line in lines))
        self.assertTrue(any("SHA256SUMS" in line or "CHECKSUM" in line for line in lines))


if __name__ == "__main__":
    unittest.main()
