#!/usr/bin/env python3
from __future__ import annotations

import argparse
import tempfile
import sys
import json
import unittest
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
SCRIPTS_DIR = ROOT / "scripts"
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

from wizard import report as wizard_report
from wizard import run as wizard_run
from wizard.model import StepStatus


class WizardRunTests(unittest.TestCase):
    def test_dry_run_creates_summary_and_state(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            args = argparse.Namespace(
                backend="wayland",
                yes=True,
                dry_run=True,
                skip_system_deps=False,
                skip_build=False,
                skip_service=False,
                skip_device=True,
                device_only=False,
                android_serial=None,
                control_port=5001,
                stream_port=5000,
                legacy=False,
                json_events=True,
                status_json=False,
                report_dir=str(tmp_path / "run"),
                from_step=None,
                only=None,
                resume=False,
                retry_step=None,
                stream_smoke=False,
                device_policy="none",
            )
            with mock.patch.object(wizard_run, "default_state_dir", return_value=tmp_path / "state"), mock.patch.object(
                wizard_run, "default_run_id", return_value="20260101T000000Z"
            ), mock.patch(
                "wizard.steps.host_preflight.collect_host_probe",
                return_value={
                    "distro": "fedora",
                    "distro_version": "43",
                    "package_manager": "dnf",
                    "arch": "x86_64",
                    "session_type": "wayland",
                    "wayland": "available",
                    "x11": "missing",
                    "portal": "active",
                    "pipewire": "pipewire=active",
                    "encoder": "h264:x264enc",
                    "evdi": "module-not-loaded",
                    "video_group": True,
                    "service": "active",
                },
            ), mock.patch(
                "wizard.steps.system_deps.detect_distro",
                return_value=mock.Mock(id="fedora", id_like="", version="43", package_manager="dnf"),
            ):
                rc = wizard_run.run_wizard(args)
            self.assertEqual(rc, 0)
            run_dir = tmp_path / "run"
            self.assertTrue((run_dir / "summary.json").exists())
            self.assertTrue((run_dir / "steps.jsonl").exists())
            self.assertTrue((run_dir / "report.md").exists())
            self.assertTrue((tmp_path / "state" / "install-state.json").exists())

    def test_status_json_renders_current_step(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            state_dir = tmp_path / "state"
            state_dir.mkdir(parents=True, exist_ok=True)
            log_path = tmp_path / "system_deps.log"
            log_path.write_text("line1\nline2\n", encoding="utf-8")
            (state_dir / "install-state.json").write_text(
                json.dumps(
                    {
                        "schema": 2,
                        "run_id": "20260101T000000Z",
                        "backend": "wayland",
                        "distro": "fedora",
                        "steps": [
                            {"id": "host_preflight", "title": "Probe host environment", "status": "ok", "log_path": "/tmp/one.log"},
                            {"id": "system_deps", "title": "Install system dependencies", "status": "fail", "log_path": str(log_path), "next_action": "fix deps"},
                        ],
                        "last_step": {"id": "system_deps", "title": "Install system dependencies", "status": "fail", "log_path": str(log_path), "next_action": "fix deps"},
                    }
                ),
                encoding="utf-8",
            )
            with mock.patch("builtins.print") as print_mock, mock.patch.object(wizard_run, "default_state_dir", return_value=state_dir):
                rc = wizard_run.status_json(state_dir)
            self.assertEqual(rc, 0)
            rendered = json.loads("\n".join(str(call.args[0]) for call in print_mock.call_args_list if call.args))
            self.assertEqual(rendered["current_step"]["last_log"], "line2")
            self.assertEqual(rendered["state_file"], str(state_dir / "install-state.json"))
            self.assertEqual(rendered["run_dir"], str(state_dir))
            self.assertEqual(rendered["recovery_commands"], ["./install-wbeam --from-step system_deps", "./install-wbeam --resume"])

    def test_status_json_falls_back_to_latest_run_state(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            state_dir = tmp_path / "state"
            run_dir = state_dir / "runs" / "20260101T000000Z"
            run_dir.mkdir(parents=True, exist_ok=True)
            (run_dir / "install-state.json").write_text(
                json.dumps(
                    {
                        "schema": 2,
                        "run_id": "20260101T000000Z",
                        "backend": "wayland",
                        "distro": "fedora",
                        "steps": [
                            {"id": "host_preflight", "title": "Probe host environment", "status": "ok", "log_path": "/tmp/one.log"}
                        ],
                        "last_step": {"id": "host_preflight", "title": "Probe host environment", "status": "ok", "log_path": "/tmp/one.log"},
                    }
                ),
                encoding="utf-8",
            )
            (run_dir / "summary.json").write_text("{}", encoding="utf-8")
            (run_dir / "report.md").write_text("# report\n", encoding="utf-8")
            (run_dir / "steps.jsonl").write_text("{}\n", encoding="utf-8")
            with mock.patch("builtins.print") as print_mock:
                rc = wizard_run.status_json(state_dir)
            self.assertEqual(rc, 0)
            rendered = json.loads("\n".join(str(call.args[0]) for call in print_mock.call_args_list if call.args))
            self.assertEqual(rendered["run_id"], "20260101T000000Z")
            self.assertEqual(rendered["state_file"], str(run_dir / "install-state.json"))
            self.assertEqual(rendered["summary_path"], str(run_dir / "summary.json"))
            self.assertEqual(rendered["report_md"], str(run_dir / "report.md"))
            self.assertEqual(rendered["steps_path"], str(run_dir / "steps.jsonl"))
            self.assertEqual(rendered["recovery_commands"], [])

    def test_status_json_exposes_warn_and_reboot_recovery(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            state_dir = tmp_path / "state"
            state_dir.mkdir(parents=True, exist_ok=True)

            warn_state = state_dir / "install-state.json"
            warn_state.write_text(
                json.dumps(
                    {
                        "schema": 2,
                        "run_id": "20260101T000000Z",
                        "backend": "wayland",
                        "distro": "fedora",
                        "steps": [
                            {"id": "system_deps", "title": "Install system dependencies", "status": "warn", "log_path": "/tmp/one.log", "next_action": "fix later"},
                        ],
                        "last_step": {"id": "system_deps", "title": "Install system dependencies", "status": "warn", "log_path": "/tmp/one.log", "next_action": "fix later"},
                    }
                ),
                encoding="utf-8",
            )
            with mock.patch("builtins.print") as print_mock:
                wizard_run.status_json(state_dir)
            rendered = json.loads("\n".join(str(call.args[0]) for call in print_mock.call_args_list if call.args))
            self.assertEqual(rendered["recovery_commands"], ["./install-wbeam --from-step system_deps"])

            warn_state.write_text(
                json.dumps(
                    {
                        "schema": 2,
                        "run_id": "20260101T000000Z",
                        "backend": "wayland",
                        "distro": "fedora",
                        "steps": [
                            {"id": "host_preflight", "title": "Probe host environment", "status": "reboot_required", "log_path": "/tmp/one.log", "next_action": "reboot"},
                        ],
                        "last_step": {"id": "host_preflight", "title": "Probe host environment", "status": "reboot_required", "log_path": "/tmp/one.log", "next_action": "reboot"},
                    }
                ),
                encoding="utf-8",
            )
            with mock.patch("builtins.print") as print_mock:
                wizard_run.status_json(state_dir)
            rendered = json.loads("\n".join(str(call.args[0]) for call in print_mock.call_args_list if call.args))
            self.assertEqual(rendered["recovery_commands"], ["./install-wbeam --resume"])

    def test_status_json_ignores_corrupt_state_and_uses_latest_valid_run(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            state_dir = tmp_path / "state"
            state_dir.mkdir(parents=True, exist_ok=True)
            (state_dir / "install-state.json").write_text('{"schema":2,"run_id":"broken"', encoding="utf-8")

            valid_run = state_dir / "runs" / "20260101T010000Z"
            valid_run.mkdir(parents=True, exist_ok=True)
            (valid_run / "install-state.json").write_text(
                json.dumps(
                    {
                        "schema": 2,
                        "run_id": "20260101T010000Z",
                        "backend": "wayland",
                        "distro": "fedora",
                        "steps": [
                            {"id": "host_preflight", "title": "Probe host environment", "status": "ok", "log_path": "/tmp/ok.log"},
                        ],
                        "last_step": {"id": "host_preflight", "title": "Probe host environment", "status": "ok", "log_path": "/tmp/ok.log"},
                    }
                ),
                encoding="utf-8",
            )

            corrupt_run = state_dir / "runs" / "20260101T000000Z"
            corrupt_run.mkdir(parents=True, exist_ok=True)
            (corrupt_run / "install-state.json").write_text('{"schema":2,"run_id":"broken"', encoding="utf-8")
            with mock.patch("builtins.print") as print_mock:
                rc = wizard_run.status_json(state_dir)
            self.assertEqual(rc, 0)
            rendered = json.loads("\n".join(str(call.args[0]) for call in print_mock.call_args_list if call.args))
            self.assertEqual(rendered["run_id"], "20260101T010000Z")
            self.assertEqual(rendered["status"], "running")

    def test_status_json_marks_corrupt_state_without_fallback_as_corrupt(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            state_dir = tmp_path / "state"
            state_dir.mkdir(parents=True, exist_ok=True)
            (state_dir / "install-state.json").write_text('{"schema":2,"run_id":"broken"', encoding="utf-8")
            with mock.patch("builtins.print") as print_mock:
                rc = wizard_run.status_json(state_dir)
            self.assertEqual(rc, 0)
            rendered = json.loads("\n".join(str(call.args[0]) for call in print_mock.call_args_list if call.args))
            self.assertEqual(rendered["status"], "corrupt")
            self.assertTrue(rendered["summary_corrupt"])
            self.assertIn("state_file", rendered)

    def test_render_results_includes_artifact_paths(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            results = [
                wizard_run.StepResult(
                    id="stream_smoke",
                    title="Run stream smoke test",
                    status=wizard_run.StepStatus.OK,
                    summary="done",
                    next_action="",
                )
            ]
            with mock.patch("builtins.print") as print_mock:
                wizard_run.render_results(results, run_dir=tmp_path / "run")
            printed = "\n".join(str(call.args[0]) for call in print_mock.call_args_list if call.args)
            self.assertIn("run dir:", printed)
            self.assertIn("summary.json", printed)
            self.assertIn("report.md", printed)
            self.assertIn("steps.jsonl", printed)

    def test_wizard_summary_reports_split_status_counts(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            run_dir = Path(tmp) / "run"
            run_dir.mkdir(parents=True, exist_ok=True)
            summary_path = wizard_report.write_wizard_summary(
                run_dir,
                run_id="20260101T000000Z",
                results=[
                    wizard_report.StepResult(id="ok", title="ok", status=StepStatus.OK, summary="ok"),
                    wizard_report.StepResult(id="warn", title="warn", status=StepStatus.WARN, summary="warn"),
                    wizard_report.StepResult(id="blocked", title="blocked", status=StepStatus.BLOCKED, summary="blocked"),
                    wizard_report.StepResult(id="reboot", title="reboot", status=StepStatus.REBOOT_REQUIRED, summary="reboot"),
                ],
            )
            summary = json.loads(summary_path.read_text(encoding="utf-8"))
            report_md = (run_dir / "report.md").read_text(encoding="utf-8")
            self.assertEqual(summary["steps_ok"], 1)
            self.assertEqual(summary["steps_warn"], 1)
            self.assertEqual(summary["steps_blocked"], 1)
            self.assertEqual(summary["steps_reboot_required"], 1)
            self.assertIn("Blocked: 1", report_md)
            self.assertIn("Reboot Required: 1", report_md)

    def test_dry_run_writes_run_local_install_state(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            args = argparse.Namespace(
                backend="wayland",
                yes=True,
                dry_run=True,
                skip_system_deps=False,
                skip_build=False,
                skip_service=False,
                skip_device=True,
                device_only=False,
                android_serial=None,
                control_port=5001,
                stream_port=5000,
                legacy=False,
                json_events=True,
                status_json=False,
                report_dir=str(tmp_path / "run"),
                from_step=None,
                only=None,
                resume=False,
                retry_step=None,
                stream_smoke=False,
                device_policy="none",
            )
            with mock.patch.object(wizard_run, "default_state_dir", return_value=tmp_path / "state"), mock.patch.object(
                wizard_run, "default_run_id", return_value="20260101T000000Z"
            ), mock.patch(
                "wizard.steps.host_preflight.collect_host_probe",
                return_value={
                    "distro": "fedora",
                    "distro_version": "43",
                    "package_manager": "dnf",
                    "arch": "x86_64",
                    "session_type": "wayland",
                    "wayland": "available",
                    "x11": "missing",
                    "portal": "active",
                    "pipewire": "pipewire=active",
                    "encoder": "h264:x264enc",
                    "evdi": "module-not-loaded",
                    "video_group": True,
                    "service": "active",
                },
            ), mock.patch(
                "wizard.steps.system_deps.detect_distro",
                return_value=mock.Mock(id="fedora", id_like="", version="43", package_manager="dnf"),
            ):
                rc = wizard_run.run_wizard(args)
            self.assertEqual(rc, 0)
            self.assertTrue((tmp_path / "run" / "install-state.json").exists())
            self.assertTrue((tmp_path / "state" / "install-state.json").exists())

    def test_json_events_suppresses_human_plan_output(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            args = argparse.Namespace(
                backend="wayland",
                yes=True,
                dry_run=True,
                skip_system_deps=False,
                skip_build=False,
                skip_service=False,
                skip_device=True,
                device_only=False,
                android_serial=None,
                control_port=5001,
                stream_port=5000,
                legacy=False,
                json_events=True,
                status_json=False,
                report_dir=str(tmp_path / "run"),
                from_step=None,
                only=None,
                resume=False,
                retry_step=None,
                stream_smoke=False,
                device_policy="none",
            )
            with mock.patch.object(wizard_run, "default_state_dir", return_value=tmp_path / "state"), mock.patch.object(
                wizard_run, "default_run_id", return_value="20260101T000000Z"
            ), mock.patch(
                "wizard.steps.host_preflight.collect_host_probe",
                return_value={
                    "distro": "fedora",
                    "distro_version": "43",
                    "package_manager": "dnf",
                    "arch": "x86_64",
                    "session_type": "wayland",
                    "wayland": "available",
                    "x11": "missing",
                    "portal": "active",
                    "pipewire": "pipewire=active",
                    "encoder": "h264:x264enc",
                    "evdi": "module-not-loaded",
                    "video_group": True,
                    "service": "active",
                },
            ), mock.patch(
                "wizard.steps.system_deps.detect_distro",
                return_value=mock.Mock(id="fedora", id_like="", version="43", package_manager="dnf"),
            ), mock.patch("builtins.print") as print_mock:
                rc = wizard_run.run_wizard(args)
            self.assertEqual(rc, 0)
            printed = "\n".join(str(call.args[0]) for call in print_mock.call_args_list if call.args)
            self.assertNotIn("[wizard] plan:", printed)

    def test_blocking_result_is_persisted_to_state(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            args = argparse.Namespace(
                backend="wayland",
                yes=True,
                dry_run=False,
                skip_system_deps=True,
                skip_build=True,
                skip_service=True,
                skip_device=True,
                device_only=False,
                android_serial=None,
                control_port=5001,
                stream_port=5000,
                legacy=False,
                json_events=False,
                status_json=False,
                report_dir=str(tmp_path / "run"),
                from_step="stream_smoke",
                only="stream_smoke",
                resume=False,
                retry_step=None,
                stream_smoke=False,
                device_policy="none",
            )
            with mock.patch.object(wizard_run, "default_state_dir", return_value=tmp_path / "state"), mock.patch.object(
                wizard_run, "default_run_id", return_value="20260101T000000Z"
            ), mock.patch(
                "wizard.steps.host_preflight.collect_host_probe",
                return_value={
                    "distro": "fedora",
                    "distro_version": "43",
                    "package_manager": "dnf",
                    "arch": "x86_64",
                    "session_type": "wayland",
                    "wayland": "available",
                    "x11": "missing",
                    "portal": "active",
                    "pipewire": "pipewire=active",
                    "encoder": "h264:x264enc",
                    "evdi": "module-not-loaded",
                    "video_group": True,
                    "service": "active",
                },
            ), mock.patch(
                "wizard.steps.system_deps.detect_distro",
                return_value=mock.Mock(id="fedora", id_like="", version="43", package_manager="dnf"),
            ):
                rc = wizard_run.run_wizard(args)
            self.assertEqual(rc, 0)
            state = json.loads((tmp_path / "state" / "install-state.json").read_text(encoding="utf-8"))
            self.assertEqual(state["last_step"]["id"], "stream_smoke")
            self.assertEqual(state["last_step"]["status"], "skipped")


if __name__ == "__main__":
    unittest.main()
