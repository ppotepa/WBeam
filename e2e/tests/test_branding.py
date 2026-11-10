import importlib.util
import json
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("apply_branding", ROOT / "scripts/apply-branding.py")
branding = importlib.util.module_from_spec(spec)
spec.loader.exec_module(branding)


class BrandingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "config").mkdir()
        self.manifest = self.root / "config/branding.json"
        self.manifest.write_text(json.dumps({"schema": 1, "displayName": "WayBeam", "desktopName": "WayBeam Desktop"}))
        self.desktop = self.root / "desktop/apps/desktop-tauri"
        (self.desktop / "src-tauri").mkdir(parents=True)
        (self.desktop / "public").mkdir()
        (self.desktop / "src-tauri/tauri.conf.json").write_text(json.dumps({
            "identifier": "com.wbeam.desktop", "productName": "WBeam Desktop",
            "app": {"windows": [{"title": "WBeam Desktop", "label": "main"}]},
        }))
        for path in (self.desktop / "index.html", self.desktop / "public/x11-warning.html"):
            path.write_text("<!doctype html><title>WBeam</title><p>Legacy notice remains.</p>\n")

    def test_preview_is_read_only_and_apply_is_idempotent(self):
        before = {p: p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        self.assertTrue(branding.synchronize(self.root))
        self.assertEqual(before, {p: p.read_bytes() for p in self.root.rglob("*") if p.is_file()})
        self.assertTrue(branding.synchronize(self.root, apply=True))
        self.assertEqual(branding.synchronize(self.root, apply=True), [])

    def test_identity_and_legacy_notice_are_preserved(self):
        branding.synchronize(self.root, apply=True)
        config = json.loads((self.desktop / "src-tauri/tauri.conf.json").read_text())
        self.assertEqual(config["identifier"], "com.wbeam.desktop")
        self.assertEqual(config["app"]["windows"][0]["label"], "main")
        self.assertIn("Legacy notice remains.", (self.desktop / "public/x11-warning.html").read_text())
        self.assertIn("Exec=/usr/local/bin/waybeam-desktop", (self.root / "config/waybeam.desktop").read_text())
        self.assertIn("Icon=wbeam", (self.root / "config/waybeam.desktop").read_text())

    def test_unsafe_name_fails_before_any_write(self):
        self.manifest.write_text(json.dumps({"schema": 1, "displayName": "Bad\nExec=command", "desktopName": "WayBeam"}))
        with self.assertRaises(ValueError):
            branding.synchronize(self.root, apply=True)
        self.assertFalse((self.root / "config/branding.sh").exists())

    def test_alternative_name_updates_all_generated_targets(self):
        self.manifest.write_text(json.dumps({"schema": 1, "displayName": "TestBeam", "desktopName": "TestBeam Desktop"}))
        branding.synchronize(self.root, apply=True)
        self.assertIn('APP_NAME = "TestBeam"', (self.desktop / "src/config/branding.ts").read_text())
        self.assertIn('APP_NAME: &str = "TestBeam"', (self.desktop / "src-tauri/src/branding.rs").read_text())
        self.assertIn("Name=TestBeam Desktop", (self.root / "config/waybeam.desktop").read_text())

    def test_names_with_spaces_round_trip_through_shell_and_window_metadata(self):
        self.manifest.write_text(json.dumps({
            "schema": 1, "displayName": "Test Beam 2.0", "desktopName": "Test Beam 2.0 Desktop",
        }))
        branding.synchronize(self.root, apply=True)
        result = subprocess.run([
            "bash", "-c", 'source "$1"; printf "%s\\n" "$WBEAM_PRODUCT_NAME" "$WBEAM_DESKTOP_NAME"',
            "fixture", str(self.root / "config/branding.sh"),
        ], capture_output=True, text=True, check=True)
        self.assertEqual(result.stdout.splitlines(), ["Test Beam 2.0", "Test Beam 2.0 Desktop"])
        config = json.loads((self.desktop / "src-tauri/tauri.conf.json").read_text())
        self.assertEqual(config["productName"], "Test Beam 2.0 Desktop")
        self.assertEqual(config["app"]["windows"][0]["title"], "Test Beam 2.0 Desktop")
        self.assertIn("<title>Test Beam 2.0 Desktop</title>", (self.desktop / "index.html").read_text())
        self.assertIn("<title>Test Beam 2.0 - X11 Legacy Notice</title>", (self.desktop / "public/x11-warning.html").read_text())

    def test_invalid_later_target_leaves_every_file_unchanged(self):
        (self.desktop / "public/x11-warning.html").write_text("<p>Missing title: review the template.</p>")
        before = {p: p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        with self.assertRaises(ValueError):
            branding.synchronize(self.root, apply=True)
        self.assertEqual(before, {p: p.read_bytes() for p in self.root.rglob("*") if p.is_file()})


if __name__ == "__main__":
    unittest.main()
