#!/usr/bin/env python3
from __future__ import annotations

import argparse
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

import report  # noqa: E402
import runner  # noqa: E402
import seed  # noqa: E402


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

    def test_base_sanity_command_varies_by_session(self) -> None:
        headless = runner.base_sanity_command(session="headless")
        desktop = runner.base_sanity_command(session="gnome-wayland")
        self.assertNotIn("/etc/gdm/custom.conf", headless)
        self.assertIn("/etc/gdm/custom.conf", desktop)
        self.assertNotIn("AutomaticLogin=wbeam", desktop)
        self.assertNotIn("sudo -n true", desktop)


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
                        "report_dir": "/tmp/report",
                        "duration_sec": 12,
                    }
                ],
            )
            summary = json.loads((run_dir / "summary.json").read_text(encoding="utf-8"))
            junit = (run_dir / "junit.xml").read_text(encoding="utf-8")
            host = json.loads((run_dir / "host.json").read_text(encoding="utf-8"))
            self.assertEqual(summary["status"], "fail")
            self.assertEqual(summary["scenarios_failed"], 1)
            self.assertIn("no bytes", junit)
            self.assertEqual(host["platform"], "linux")

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
            snapshot = runner.status_snapshot(matrix, base_root=tmp_path / "base", report_root=report_root)
            self.assertGreater(snapshot["percent"], 0)
            self.assertFalse(snapshot["live_run_verified"])
            self.assertTrue(snapshot["dry_run_verified"])
            self.assertEqual(snapshot["report_runs"], 1)
            self.assertEqual(len(snapshot["missing_iso_inputs"]), 3)
            self.assertEqual(len(snapshot["missing_base_images"]), 9)
            self.assertIn("WBEAM_E2E_ISO_FEDORA_43", {item["env"] for item in snapshot["missing_iso_inputs"]})
            self.assertIn("./e2e/run init-env", snapshot["next_commands"])
            self.assertIn("./e2e/run iso-sources", snapshot["next_commands"])
            self.assertIn('eval "$(./e2e/run env-shell)"', snapshot["next_commands"])
            self.assertTrue(any(command.startswith("export WBEAM_E2E_ISO_FEDORA_43=") for command in snapshot["next_commands"]))
            self.assertIn("./e2e/run prepare-base --all --missing", snapshot["next_commands"])
            self.assertIn("./e2e/run run --tag smoke --ready", snapshot["next_commands"])


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
