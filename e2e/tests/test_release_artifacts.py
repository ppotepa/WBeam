import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "e2e/scripts"))
from validate_hardware_acceptance import BACKENDS, DISTROS, COMMON_CHECKS, BACKEND_CHECKS  # noqa: E402


class ReleaseArtifactTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.dist = self.root / "dist"
        self.dist.mkdir()
        scripts = self.root / "scripts/ci"
        scripts.mkdir(parents=True)
        for name in ("common.sh", "deb-target.sh", "check-release-artifacts.sh", "publish_github_release.sh"):
            shutil.copy2(ROOT / "scripts/ci" / name, scripts / name)
        (self.root / "e2e/scripts").mkdir(parents=True)
        shutil.copy2(ROOT / "e2e/scripts/validate_hardware_acceptance.py", self.root / "e2e/scripts")
        self.version = "0.1.3-rc.1"
        self.commit = "a" * 40
        self.manifest = self.dist / "VERSION.txt"
        self.manifest.write_text(f"WBEAM_VERSION={self.version}\nWBEAM_VERSION_CODE=10001\nCI_COMMIT_SHA={self.commit}\n")
        for name in ("wbeam_0.1.3~rc.1+bookworm_amd64.deb", "wbeam_0.1.3~rc.1+noble_amd64.deb",
                     "wbeam-0.1.3~rc.1-1.fc43.x86_64.rpm", "wbeam-android-0.1.3-rc.1.apk"):
            (self.dist / name).write_text("unit-test artifact placeholder: " + name)
        self.env = dict(os.environ, WBEAM_VERSION=self.version, CI_COMMIT_SHA=self.commit,
                        TMPDIR=str(self.root), CI_COMMIT_BRANCH="release", CI_COMMIT_TAG="")

    def run_script(self, name):
        return subprocess.run(["bash", str(self.root / "scripts/ci" / name)],
                              env=self.env, capture_output=True, text=True)

    def test_expected_artifact_set_generates_verified_checksums(self):
        result = self.run_script("check-release-artifacts.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len((self.dist / "SHA256SUMS.txt").read_text().splitlines()), 5)
        checked = subprocess.run(["sha256sum", "-c", "SHA256SUMS.txt"], cwd=self.dist, capture_output=True)
        self.assertEqual(checked.returncode, 0)

    def test_stale_distribution_package_is_rejected(self):
        old = self.dist / "wbeam_0.1.3~rc.1+noble_amd64.deb"
        old.rename(self.dist / "wbeam_0.1.2+noble_amd64.deb")
        result = self.run_script("check-release-artifacts.sh")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("stale or unsupported DEB", result.stderr)

    def test_duplicate_manifest_and_bad_version_code_fail(self):
        original = self.manifest.read_text()
        for content in (original + f"CI_COMMIT_SHA={self.commit}\n", original.replace("10001", "999999999999999999")):
            with self.subTest(content=content):
                self.manifest.write_text(content)
                self.assertNotEqual(self.run_script("check-release-artifacts.sh").returncode, 0)

    def test_extra_or_symlink_artifacts_fail(self):
        extra = self.dist / "unrelated.deb"
        extra.write_text("stale")
        self.assertNotEqual(self.run_script("check-release-artifacts.sh").returncode, 0)
        extra.unlink()
        apk = self.dist / "wbeam-android-0.1.3-rc.1.apk"
        apk.unlink()
        apk.symlink_to(self.manifest)
        self.assertNotEqual(self.run_script("check-release-artifacts.sh").returncode, 0)

    def create_report(self):
        # All fixtures stay in a temporary fake checkout. No physical QA claim.
        (self.root / "LICENSE").write_text("Test fixture only; not a license grant.")
        evidence = self.root / "evidence.txt"
        evidence.write_text("Unit-test fixture, not physical hardware evidence.")
        digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
        apk = self.dist / "wbeam-android-0.1.3-rc.1.apk"
        runs = []
        for distro in DISTROS:
            package = self.dist / ("wbeam-0.1.3~rc.1-1.fc43.x86_64.rpm" if distro == "fedora-43" else "wbeam_0.1.3~rc.1+noble_amd64.deb")
            for backend in BACKENDS:
                runs.append({"backend": backend, "distro": distro, "session": "gnome-wayland",
                             "source": "physical_android", "kernel": "6.19.11", "android_api": 35,
                             "device_id_sha256": "b" * 64, "artifact": package.name, "artifact_sha256": digest(package),
                             "apk_artifact": apk.name, "apk_sha256": digest(apk), "start_stop_cycles": 20,
                             "soak_seconds": 1800, "checks": {
                                 key: {"status": "pass", "evidence": evidence.name, "evidence_sha256": digest(evidence)}
                                 for key in COMMON_CHECKS + BACKEND_CHECKS[backend]}})
        report = self.root / "report.json"
        report.write_text(json.dumps({"schema": 2, "release": self.version, "tested_commit": self.commit,
                                      "qa_signoff": "Fixture reviewer", "runs": runs,
                                      "artifacts": {path.name: digest(path) for path in self.dist.iterdir()
                                                    if path.name == "VERSION.txt" or path.suffix in {".deb", ".rpm", ".apk"}}}))
        return report

    def test_publisher_rejects_tampering_before_any_github_request(self):
        # No network or real release: curl can only leave a local marker and fail.
        report = self.create_report()
        apk = self.dist / "wbeam-android-0.1.3-rc.1.apk"
        apk.write_text("modified after the fixture report was signed off")
        mock_bin = self.root / "bin"
        mock_bin.mkdir()
        curl = mock_bin / "curl"
        curl.write_text('#!/bin/sh\ntouch "$NETWORK_MARKER"\nexit 97\n')
        curl.chmod(0o755)
        marker = self.root / "network-called"
        self.env.update(PATH=str(mock_bin) + os.pathsep + os.environ["PATH"], NETWORK_MARKER=str(marker),
                        WBEAM_RELEASE_APPROVED="1", WBEAM_HARDWARE_ACCEPTANCE_FILE=str(report),
                        GH_TOKEN="unit-test-token", GH_OWNER="fixture", GH_REPO="fixture")
        result = self.run_script("publish_github_release.sh")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("apk_sha256 does not match", result.stderr)
        self.assertFalse(marker.exists(), "publication contacted GitHub before provenance validation")

    def mock_github(self, state, tag="v0.1.3-rc.1"):
        report = self.create_report()
        state.setdefault("refs", {})
        state.setdefault("tags", {})
        state.setdefault("assets", [])
        if state.get("release") is not None:
            state["release"].update(id=7, upload_url="https://uploads.github.com/repos/fixture/fixture/releases/7/assets{?name,label}")
        self.state_path = self.root / "github-state.json"
        self.state_path.write_text(json.dumps(state))
        self.calls_path = self.root / "github-calls.jsonl"
        self.calls_path.write_text("")
        mock_bin = self.root / "bin"
        mock_bin.mkdir(exist_ok=True)
        curl = mock_bin / "curl"
        curl.write_text(textwrap.dedent("""\
            #!/usr/bin/env python3
            # Hermetic fake API: this process never opens a network connection.
            import json, os, pathlib, sys
            from urllib.parse import urlsplit, parse_qs
            args = sys.argv[1:]
            def option(name, default=None):
                return args[args.index(name) + 1] if name in args else default
            url = next((arg for arg in args if arg.startswith('https://')), '')
            parsed = urlsplit(url)
            if parsed.netloc not in {'api.github.com', 'uploads.github.com'}:
                sys.exit(97)
            path = parsed.path.removeprefix('/repos/fixture/fixture')
            method = option('-X', 'GET')
            data = json.loads(option('--data', '{}'))
            state_path = pathlib.Path(os.environ['MOCK_GITHUB_STATE'])
            state = json.loads(state_path.read_text())
            event = {'method': method, 'path': path, 'payload': data,
                     'draft': (state.get('release') or {}).get('draft')}
            status, response = 500, {'message': 'unexpected mock request'}
            if parsed.netloc == 'uploads.github.com' and method == 'POST':
                name = parse_qs(parsed.query)['name'][0]
                event['asset'] = name
                assert pathlib.Path(option('--data-binary')[1:]).is_file()
                response = {'id': max((asset['id'] for asset in state['assets']), default=0) + 1, 'name': name}
                state['assets'].append(response)
                status = 201
                if 'drift_on_upload' in state:
                    for tag in state['refs']:
                        state['refs'][tag] = {'type': 'commit', 'sha': state['drift_on_upload']}
            elif path.startswith('/git/ref/tags/') and method == 'GET':
                tag = path.removeprefix('/git/ref/tags/')
                ref = state['refs'].get(tag)
                status, response = (200, {'object': ref}) if ref else (404, {})
                status = state.get('ref_status', status)
            elif path.startswith('/git/tags/') and method == 'GET':
                obj = state['tags'].get(path.removeprefix('/git/tags/'))
                status, response = (200, {'object': obj}) if obj else (404, {})
            elif path == '/git/refs' and method == 'POST':
                tag = data['ref'].removeprefix('refs/tags/')
                race = state.pop('create_ref_race_sha', None)
                if race:
                    state['refs'][tag] = {'type': 'commit', 'sha': race}
                if tag in state['refs']:
                    status, response = 422, {}
                else:
                    state['refs'][tag] = {'type': 'commit', 'sha': data['sha']}
                    status, response = 201, {'object': state['refs'][tag]}
            elif path.startswith('/git/refs/tags/') and method == 'PATCH':
                tag = path.removeprefix('/git/refs/tags/')
                state['refs'][tag] = {'type': 'commit', 'sha': data['sha']}
                status, response = 200, {'object': state['refs'][tag]}
            elif path.startswith('/releases/tags/') and method == 'GET':
                status, response = (200, state['release']) if state.get('release') else (404, {})
            elif path == '/releases' and method == 'POST':
                state['release'] = dict(data, id=7,
                    upload_url='https://uploads.github.com/repos/fixture/fixture/releases/7/assets{?name,label}')
                status, response = 201, state['release']
            elif path == '/releases/7' and method == 'PATCH':
                state['release'].update(data)
                status, response = 200, state['release']
            elif path == '/releases/7/assets' and method == 'GET':
                query = parse_qs(parsed.query)
                page = int(query.get('page', ['1'])[0])
                per_page = int(query.get('per_page', ['100'])[0])
                event['page'] = page
                status, response = 200, state['assets'][(page - 1) * per_page:page * per_page]
            elif path.startswith('/releases/assets/') and method == 'DELETE':
                asset_id = int(path.rsplit('/', 1)[1])
                state['assets'] = [asset for asset in state['assets'] if asset['id'] != asset_id]
                status, response = 204, {}
            with pathlib.Path(os.environ['MOCK_GITHUB_CALLS']).open('a') as calls:
                calls.write(json.dumps(event) + '\\n')
            state_path.write_text(json.dumps(state))
            pathlib.Path(option('-o')).write_text(json.dumps(response))
            print(status, end='')
            """))
        curl.chmod(0o755)
        self.env.update(PATH=str(mock_bin) + os.pathsep + os.environ["PATH"],
                        MOCK_GITHUB_STATE=str(self.state_path), MOCK_GITHUB_CALLS=str(self.calls_path),
                        WBEAM_RELEASE_APPROVED="1", WBEAM_HARDWARE_ACCEPTANCE_FILE=str(report),
                        GH_TOKEN="unit-test-token", GH_OWNER="fixture", GH_REPO="fixture", CI_COMMIT_TAG=tag)

    def api_calls(self):
        return [json.loads(line) for line in self.calls_path.read_text().splitlines()]

    def test_publisher_rejects_uncovered_bookworm_tampering_before_api_calls(self):
        self.mock_github({})
        (self.dist / "wbeam_0.1.3~rc.1+bookworm_amd64.deb").write_text("replaced after fixture sign-off")
        result = self.run_script("publish_github_release.sh")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("artifacts[wbeam_0.1.3~rc.1+bookworm_amd64.deb]", result.stderr)
        self.assertEqual(self.api_calls(), [])

    def test_absent_tag_is_created_at_tested_commit_before_draft_publication(self):
        self.mock_github({})
        result = self.run_script("publish_github_release.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        state = json.loads(self.state_path.read_text())
        self.assertEqual(state["refs"]["v0.1.3-rc.1"], {"type": "commit", "sha": self.commit})
        self.assertFalse(state["release"]["draft"])
        calls = self.api_calls()
        create = next(call for call in calls if call["path"] == "/git/refs")
        self.assertEqual(create["payload"], {"ref": "refs/tags/v0.1.3-rc.1", "sha": self.commit})
        uploads = [call for call in calls if "asset" in call]
        self.assertEqual(len(uploads), 6)
        self.assertTrue(all(call["draft"] is True for call in uploads))
        self.assertEqual(calls[-2]["path"], "/git/ref/tags/v0.1.3-rc.1")
        self.assertEqual(calls[-1]["payload"], {"draft": False})

    def test_immutable_tag_mismatch_fails_without_any_github_write(self):
        self.mock_github({"refs": {"v0.1.3-rc.1": {"type": "commit", "sha": "b" * 40}}})
        result = self.run_script("publish_github_release.sh")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Immutable tag", result.stderr)
        self.assertTrue(all(call["method"] == "GET" for call in self.api_calls()))

    def test_nested_annotated_tag_resolves_to_tested_commit_without_ref_mutation(self):
        self.mock_github({"refs": {"v0.1.3-rc.1": {"type": "tag", "sha": "c" * 40}},
                          "tags": {"c" * 40: {"type": "tag", "sha": "d" * 40},
                                   "d" * 40: {"type": "commit", "sha": self.commit}}})
        result = self.run_script("publish_github_release.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = self.api_calls()
        for sha in ("c" * 40, "d" * 40):
            self.assertTrue(any(call["path"] == "/git/tags/" + sha for call in calls))
        self.assertFalse(any(call["path"].startswith("/git/") and call["method"] != "GET" for call in calls))

    def test_ref_creation_race_accepts_only_the_same_commit(self):
        for race_sha in (self.commit, "b" * 40):
            with self.subTest(race_sha=race_sha):
                self.mock_github({"create_ref_race_sha": race_sha})
                result = self.run_script("publish_github_release.sh")
                self.assertEqual(result.returncode == 0, race_sha == self.commit, result.stderr)
                self.assertFalse(any(call["path"].startswith("/git/refs/") and call["method"] == "PATCH"
                                     for call in self.api_calls()))
                if race_sha != self.commit:
                    self.assertFalse(any("asset" in call for call in self.api_calls()))

    def test_only_rolling_tag_moves_and_release_is_hidden_until_verified(self):
        self.mock_github({"refs": {"release-latest": {"type": "commit", "sha": "b" * 40}},
                          "release": {"tag_name": "release-latest", "draft": False}}, tag="")
        result = self.run_script("publish_github_release.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = self.api_calls()
        moved = next(call for call in calls if call["path"] == "/git/refs/tags/release-latest")
        self.assertEqual(moved["payload"], {"sha": self.commit, "force": True})
        self.assertTrue(moved["draft"], "the old release must be hidden before moving its tag")
        self.assertTrue(all(call["draft"] is True for call in calls if "asset" in call))
        self.assertEqual(calls[-2]["path"], "/git/ref/tags/release-latest")
        self.assertEqual(calls[-1]["payload"], {"draft": False})

    def test_rolling_release_removes_stale_assets_across_all_pages(self):
        stale = [{"id": index + 1, "name": f"old-package-{index}.deb"} for index in range(105)]
        # This name is still approved and gets replaced by upload_asset, not pruning.
        retained = {"id": 200, "name": "VERSION.txt"}
        self.mock_github({"refs": {"release-latest": {"type": "commit", "sha": self.commit}},
                          "release": {"tag_name": "release-latest", "draft": False},
                          "assets": stale + [retained]}, tag="")
        result = self.run_script("publish_github_release.sh")
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = self.api_calls()
        page_two = next(index for index, call in enumerate(calls) if call.get("page") == 2)
        first_delete = next(index for index, call in enumerate(calls) if call["method"] == "DELETE")
        self.assertLess(page_two, first_delete, "all pages must be collected before deletion")
        state = json.loads(self.state_path.read_text())
        expected_names = {path.name for path in self.dist.iterdir()}
        self.assertEqual({asset["name"] for asset in state["assets"]}, expected_names)
        self.assertEqual(len(state["assets"]), len(expected_names))
        self.assertTrue(all(call["draft"] is True for call in calls if call["method"] == "DELETE"))

    def test_tag_drift_during_upload_keeps_release_private(self):
        self.mock_github({"drift_on_upload": "b" * 40})
        result = self.run_script("publish_github_release.sh")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("not tested commit", result.stderr)
        self.assertTrue(json.loads(self.state_path.read_text())["release"]["draft"])
        self.assertFalse(any(call["payload"].get("draft") is False for call in self.api_calls()))

    def test_ref_query_failure_is_not_treated_as_permission_to_create(self):
        self.mock_github({"ref_status": 500})
        result = self.run_script("publish_github_release.sh")
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(all(call["method"] == "GET" for call in self.api_calls()))


if __name__ == "__main__":
    unittest.main()
