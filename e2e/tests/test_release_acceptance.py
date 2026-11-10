import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from validate_hardware_acceptance import (  # noqa: E402
    BACKENDS, DISTROS, COMMON_CHECKS, BACKEND_CHECKS, sha256, validate_report,
)

RELEASE = "0.1.3-rc.1"
COMMIT = "c" * 40


class HardwareAcceptanceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.artifacts = self.root / "dist"
        self.artifacts.mkdir()
        self.evidence = self.root / "evidence.txt"
        self.evidence.write_text("Validator unit-test fixture only; not real QA evidence.", encoding="utf-8")
        (self.artifacts / "VERSION.txt").write_text(
            f"WBEAM_VERSION={RELEASE}\nCI_COMMIT_SHA={COMMIT}\n", encoding="utf-8")
        self.apk = f"wbeam-android-{RELEASE}.apk"
        self.packages = {
            "fedora-43": "wbeam-0.1.3~rc.1-1.fc43.x86_64.rpm",
            "ubuntu-24.04": "wbeam_0.1.3~rc.1+noble_amd64.deb",
        }
        self.bookworm = "wbeam_0.1.3~rc.1+bookworm_amd64.deb"
        for filename in [self.apk, self.bookworm, *self.packages.values()]:
            (self.artifacts / filename).write_bytes(f"unit-test fixture: {filename}".encode())
        self.data = {"schema": 2, "release": RELEASE, "tested_commit": COMMIT,
                     "qa_signoff": "Unit-test reviewer fixture", "runs": [],
                     "artifacts": {path.name: sha256(path) for path in self.artifacts.iterdir()}}
        for distro in DISTROS:
            for backend in BACKENDS:
                self.data["runs"].append({
                    "backend": backend, "distro": distro, "session": "gnome-wayland",
                    "source": "physical_android", "kernel": "6.19.11",
                    "android_api": 35, "device_id_sha256": "a" * 64,
                    "artifact": self.packages[distro],
                    "artifact_sha256": sha256(self.artifacts / self.packages[distro]),
                    "apk_artifact": self.apk, "apk_sha256": sha256(self.artifacts / self.apk),
                    "start_stop_cycles": 20, "soak_seconds": 1800,
                    "checks": {
                        key: {"status": "pass", "evidence": "evidence.txt", "evidence_sha256": sha256(self.evidence)}
                        for key in COMMON_CHECKS + BACKEND_CHECKS[backend]
                    },
                })

    def errors(self, **overrides):
        args = {"expected_release": RELEASE, "expected_commit": COMMIT, "artifacts_dir": self.artifacts}
        args.update(overrides)
        return validate_report(self.data, self.root, **args)

    def test_complete_matrix_matches_files_and_commit(self):
        self.assertEqual(self.errors(), [])

    def test_missing_backend_or_nonphysical_source_fails(self):
        self.data["runs"].pop()
        self.data["runs"][0]["source"] = "vm_tcp"
        errors = self.errors()
        self.assertTrue(any("missing physical matrix run" in e for e in errors))
        self.assertTrue(any("physical_android" in e for e in errors))

    def test_pending_or_missing_evidence_fails(self):
        self.data["runs"][0]["checks"]["stream_soak_1800s"]["status"] = "pending"
        self.data["runs"][1]["checks"]["start_stop_20"]["evidence"] = "missing.log"
        errors = self.errors()
        self.assertTrue(any("requires status=pass" in e for e in errors))
        self.assertTrue(any("cannot verify evidence" in e for e in errors))

    def test_evidence_path_escape_and_wrong_release_fail(self):
        self.data["runs"][0]["checks"]["rendered_frames_60s"]["evidence"] = "../private.txt"
        errors = self.errors(expected_release="0.1.3")
        self.assertTrue(any("relative" in e for e in errors))
        self.assertTrue(any("release mismatch" in e for e in errors))

    def test_stale_commit_and_manifest_fail(self):
        self.data["tested_commit"] = "d" * 40
        (self.artifacts / "VERSION.txt").write_text(f"WBEAM_VERSION={RELEASE}\nCI_COMMIT_SHA={'e' * 40}\n")
        errors = self.errors()
        self.assertTrue(any("tested_commit differs" in e for e in errors))
        self.assertTrue(any("VERSION.txt commit differs" in e for e in errors))

    def test_tampered_package_apk_and_evidence_fail(self):
        for path in (self.artifacts / self.packages["fedora-43"], self.artifacts / self.apk, self.evidence):
            path.write_bytes(path.read_bytes() + b"changed after QA")
        errors = self.errors()
        for field in ("artifact_sha256", "apk_sha256", "evidence_sha256"):
            self.assertTrue(any(field in e and "does not match" in e for e in errors), errors)

    def test_inventory_binds_bookworm_and_the_complete_version_manifest(self):
        # Bookworm is published but is not a separate physical-matrix target.
        bookworm = self.artifacts / self.bookworm
        bookworm.write_bytes(bookworm.read_bytes() + b"changed after sign-off")
        manifest = self.artifacts / "VERSION.txt"
        manifest.write_text(manifest.read_text() + "WBEAM_VERSION_CODE=99999\n")
        errors = self.errors()
        for name in (self.bookworm, "VERSION.txt"):
            self.assertTrue(any(f"artifacts[{name}]" in e and "does not match" in e for e in errors), errors)

    def test_inventory_requires_exact_set_and_rejects_extra_rpm(self):
        del self.data["artifacts"][self.bookworm]
        extra_rpm = "wbeam-0.1.3~rc.1-1.fc43.aarch64.rpm"
        (self.artifacts / extra_rpm).write_bytes(b"unreviewed extra RPM fixture")
        self.data["artifacts"]["SHA256SUMS.txt"] = "a" * 64
        errors = self.errors()
        for name in (self.bookworm, extra_rpm):
            self.assertTrue(any("missing hash" in e and name in e for e in errors), errors)
        self.assertTrue(any("unexpected file SHA256SUMS.txt" in e for e in errors), errors)

    def test_missing_or_invalid_inventory_fails(self):
        for inventory in (None, [], {self.apk: "not-a-hash"}):
            with self.subTest(inventory=inventory):
                self.data["artifacts"] = inventory
                self.assertTrue(any("artifacts" in e for e in self.errors()))

    def test_wrong_abi_even_with_matching_hash_fails(self):
        wrong = self.artifacts / "wbeam_0.1.3~rc.1+bookworm_amd64.deb"
        wrong.write_bytes(b"wrong distribution package")
        self.data["runs"][2]["artifact"] = wrong.name
        self.data["runs"][2]["artifact_sha256"] = sha256(wrong)
        self.assertTrue(any("Noble DEB" in e for e in self.errors()))

    def test_symlink_to_outside_artifact_directory_fails(self):
        path = self.artifacts / self.apk
        path.unlink()
        path.symlink_to(self.evidence)
        self.assertTrue(any("outside" in e for e in self.errors()))

    def test_malformed_json_values_fail_without_crashing(self):
        self.data["runs"][0]["backend"] = ["evdi"]
        self.data["runs"][1]["soak_seconds"] = True
        self.data["qa_signoff"] = "PENDING"
        self.assertGreaterEqual(len(self.errors()), 4)

    def test_old_schema_and_missing_hashes_fail(self):
        self.data["schema"] = 1
        del self.data["runs"][0]["apk_sha256"]
        del self.data["runs"][0]["checks"]["start_stop_20"]["evidence_sha256"]
        errors = self.errors()
        for field in ("schema", "apk_sha256", "evidence_sha256"):
            self.assertTrue(any(field in e for e in errors))

    def test_cli_requires_build_context(self):
        report = self.root / "report.json"
        report.write_text(json.dumps(self.data), encoding="utf-8")
        validator = Path(__file__).resolve().parents[1] / "scripts/validate_hardware_acceptance.py"
        missing = subprocess.run([sys.executable, str(validator), str(report)], capture_output=True)
        self.assertEqual(missing.returncode, 2)
        complete = subprocess.run([
            sys.executable, str(validator), str(report), "--release", RELEASE,
            "--commit", COMMIT, "--artifacts-dir", str(self.artifacts),
        ], capture_output=True, text=True)
        self.assertEqual(complete.returncode, 0, complete.stderr)


if __name__ == "__main__":
    unittest.main()
