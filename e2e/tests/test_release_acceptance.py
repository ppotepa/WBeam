import tempfile
import unittest
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
from validate_hardware_acceptance import (  # noqa: E402
    BACKENDS, DISTROS, COMMON_CHECKS, BACKEND_CHECKS, validate_report
)


class HardwareAcceptanceTests(unittest.TestCase):
    def make_report(self, root: Path):
        evidence = root / "evidence.txt"
        evidence.write_text("real device log placeholder for validator unit test", encoding="utf-8")
        runs = []
        for distro in DISTROS:
            for backend in BACKENDS:
                checks = {
                    key: {"status": "pass", "evidence": "evidence.txt"}
                    for key in COMMON_CHECKS + BACKEND_CHECKS[backend]
                }
                runs.append({
                    "backend": backend, "distro": distro, "session": "gnome-wayland",
                    "source": "physical_android", "kernel": "6.19.11",
                    "android_api": 35, "device_id_sha256": "a" * 64,
                    "artifact_sha256": "b" * 64,
                    "start_stop_cycles": 20, "soak_seconds": 1800,
                    "checks": checks,
                })
        return {"schema": 1, "release": "0.1.3-rc.1",
                "tested_commit": "c" * 40, "qa_signoff": "QA reviewer", "runs": runs}

    def test_complete_matrix_and_evidence_can_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.assertEqual(validate_report(self.make_report(root), root, "0.1.3-rc.1"), [])

    def test_missing_backend_or_unverified_hardware_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            data = self.make_report(root)
            data["runs"].pop()
            data["runs"][0]["source"] = "vm_tcp"
            errors = validate_report(data, root)
            self.assertTrue(any("missing physical matrix run" in e for e in errors))
            self.assertTrue(any("physical_android" in e for e in errors))

    def test_pending_or_missing_evidence_fails(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            data = self.make_report(root)
            data["runs"][0]["checks"]["stream_soak_1800s"]["status"] = "pending"
            data["runs"][1]["checks"]["start_stop_20"]["evidence"] = "missing.log"
            errors = validate_report(data, root)
            self.assertTrue(any("requires status=pass" in e for e in errors))
            self.assertTrue(any("does not exist" in e for e in errors))

    def test_rejects_evidence_path_escape_and_wrong_release(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            data = self.make_report(root)
            data["runs"][0]["checks"]["rendered_frames_60s"]["evidence"] = "../private.txt"
            errors = validate_report(data, root, expected_release="0.1.3")
            self.assertTrue(any("relative" in e for e in errors))
            self.assertTrue(any("release mismatch" in e for e in errors))


if __name__ == "__main__":
    unittest.main()
