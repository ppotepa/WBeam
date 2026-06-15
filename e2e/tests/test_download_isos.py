#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest import mock


TEST_DIR = Path(__file__).resolve().parent
DOWNLOAD_PATH = TEST_DIR.parent / "scripts" / "download_isos.py"


def load_download_isos():
    spec = importlib.util.spec_from_file_location("download_isos_module", DOWNLOAD_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    spec.loader.exec_module(module)
    return module


class DownloadIsosTests(unittest.TestCase):
    def test_parser_accepts_repeatable_distro_and_missing(self) -> None:
        mod = load_download_isos()
        args = mod.build_parser().parse_args(["--distro", "fedora-43", "--distro", "ubuntu-24.04", "--missing"])
        self.assertEqual(args.distro, ["fedora-43", "ubuntu-24.04"])
        self.assertTrue(args.missing)

    def test_selected_iso_items_filters_by_distro(self) -> None:
        mod = load_download_isos()
        args = mod.build_parser().parse_args(["--distro", "fedora-43"])
        items = mod.selected_iso_items(args)
        self.assertEqual([env for env, _ in items], ["WBEAM_E2E_ISO_FEDORA_43"])

    def test_missing_skips_existing_valid_target(self) -> None:
        mod = load_download_isos()
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            iso_dir = tmp_path / "iso"
            env_local = tmp_path / "env.local"
            target = iso_dir / "fedora-43-netinst.iso"
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(b"0" * 1024)
            args = mod.build_parser().parse_args(["--distro", "fedora-43", "--missing"])
            with mock.patch.object(mod, "target_is_valid", return_value=True):
                with mock.patch.object(mod, "emit") as emit_mock:
                    with mock.patch.object(mod.subprocess, "run") as run_mock:
                        rc = mod.sync_selected_isos(args, iso_dir, env_local)
            self.assertEqual(rc, 0)
            run_mock.assert_not_called()
            self.assertTrue(any(call.args[1].get("type") == "iso_reuse" for call in emit_mock.call_args_list))

    def test_unknown_distro_is_parser_error(self) -> None:
        mod = load_download_isos()
        with self.assertRaises(SystemExit) as ctx:
            mod.build_parser().parse_args(["--distro", "unknown-os"])
        self.assertEqual(ctx.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
