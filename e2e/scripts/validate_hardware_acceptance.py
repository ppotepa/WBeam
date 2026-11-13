#!/usr/bin/env python3
"""Validate externally collected WayBeam QA evidence against release artifacts.

This tool does not generate PASS evidence or assert that a VM/TCP client is an
Android device. QA must run the tests on physical hardware and attach reports.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

BACKENDS = ("evdi", "wayland_portal")
DISTROS = ("fedora-43", "ubuntu-24.04")
COMMON_CHECKS = (
    "clean_package_install",
    "signed_apk_install_and_update",
    "rendered_frames_60s",
    "stream_soak_1800s",
    "start_stop_20",
    "usb_disconnect_reconnect",
    "daemon_restart_recovery",
)
BACKEND_CHECKS = {
    "evdi": ("missing_evdi_diagnostics",),
    "wayland_portal": ("portal_consent_denied",),
}
HASH64 = re.compile(r"^[0-9a-f]{64}$")
SHA40 = re.compile(r"^[0-9a-f]{40}$")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def contained_file(base: Path, relative: object, *, basename_only: bool = False) -> Path:
    if not isinstance(relative, str) or not relative.strip():
        raise ValueError("requires a nonempty relative file path")
    rel = Path(relative)
    if rel.is_absolute() or ".." in rel.parts or (basename_only and len(rel.parts) != 1):
        raise ValueError("path must be relative and inside its directory")
    root = base.resolve(strict=True)
    path = (root / rel).resolve(strict=True)
    if root not in path.parents or not path.is_file() or path.stat().st_size == 0:
        raise ValueError("file is missing, empty or outside its directory")
    return path


def validate_artifact_inventory(inventory: object, artifacts_dir: Path) -> list[str]:
    """Bind the complete publication set, including packages outside the QA matrix."""
    if not isinstance(inventory, dict):
        return ["artifacts must map every published filename to its SHA256"]
    try:
        root = artifacts_dir.resolve(strict=True)
        if not root.is_dir():
            raise ValueError("artifacts directory is not a directory")
        expected = {"VERSION.txt"}
        for pattern in ("*.deb", "*.rpm", "*.apk"):
            expected.update(path.name for path in root.glob(pattern))
    except (OSError, RuntimeError, ValueError) as exc:
        return [f"cannot enumerate artifact inventory: {exc}"]

    problems: list[str] = []
    names = {name for name in inventory if isinstance(name, str)}
    for name in sorted(expected - names):
        problems.append(f"artifacts: missing hash for published file {name}")
    for name in sorted(names - expected):
        problems.append(f"artifacts: unexpected file {name}; include only DEB/RPM/APK and VERSION.txt")
    for name, expected_hash in inventory.items():
        if not isinstance(name, str):
            problems.append("artifacts: filenames must be strings")
            continue
        if not isinstance(expected_hash, str) or not HASH64.fullmatch(expected_hash):
            problems.append(f"artifacts[{name}]: requires a lowercase SHA256")
            continue
        try:
            path = contained_file(root, name, basename_only=True)
            if sha256(path) != expected_hash:
                problems.append(f"artifacts[{name}]: SHA256 does not match the signed-off file")
        except (OSError, RuntimeError, ValueError) as exc:
            problems.append(f"artifacts[{name}]: cannot verify file: {exc}")
    return problems


def validate_report(
    data: object, base: Path, expected_release: str, expected_commit: str, artifacts_dir: Path,
) -> list[str]:
    problems: list[str] = []
    if not isinstance(data, dict):
        return ["report must be a JSON object"]
    if data.get("schema") != 2:
        problems.append("schema must be 2 (commit, artifact inventory, package, APK and evidence hashes are required)")
    release = data.get("release")
    if not isinstance(release, str) or not release.strip():
        problems.append("release must be a nonempty string")
    elif expected_release and release != expected_release:
        problems.append(f"release mismatch: expected {expected_release!r}, got {release!r}")
    if not SHA40.fullmatch(str(data.get("tested_commit", ""))):
        problems.append("tested_commit must be a full lowercase 40-digit SHA")
    if not SHA40.fullmatch(expected_commit):
        problems.append("expected commit must be a full lowercase 40-digit SHA")
    elif data.get("tested_commit") != expected_commit:
        problems.append("tested_commit differs from the expected build commit")
    try:
        manifest_path = contained_file(artifacts_dir, "VERSION.txt", basename_only=True)
        manifest: dict[str, str] = {}
        for line in manifest_path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            key, separator, value = line.partition("=")
            if not separator or key in manifest:
                raise ValueError("invalid or duplicate VERSION.txt entry")
            manifest[key] = value
        if manifest.get("WBEAM_VERSION") != expected_release:
            problems.append("VERSION.txt release differs from the expected release")
        if manifest.get("CI_COMMIT_SHA") != expected_commit:
            problems.append("VERSION.txt commit differs from the expected build commit")
    except (OSError, RuntimeError, ValueError, UnicodeError) as exc:
        problems.append(f"cannot verify VERSION.txt: {exc}")
    problems.extend(validate_artifact_inventory(data.get("artifacts"), artifacts_dir))
    signoff = data.get("qa_signoff")
    if (not isinstance(signoff, str) or len(signoff.strip()) < 3
            or signoff.strip().lower() in {"pending", "unknown", "todo", "qa reviewer"}):
        problems.append("qa_signoff must identify the accepting QA reviewer")
    runs = data.get("runs")
    if not isinstance(runs, list):
        return problems + ["runs must be an array"]
    seen: set[tuple[str, str]] = set()
    for index, run in enumerate(runs):
        prefix = f"runs[{index}]"
        if not isinstance(run, dict):
            problems.append(f"{prefix} must be an object")
            continue
        backend = run.get("backend")
        distro = run.get("distro")
        if not isinstance(backend, str) or not isinstance(distro, str) or backend not in BACKENDS or distro not in DISTROS:
            problems.append(f"{prefix}: unsupported release matrix pair {distro}/{backend}")
            continue
        if (distro, backend) in seen:
            problems.append(f"{prefix}: duplicate matrix pair {distro}/{backend}")
        seen.add((distro, backend))
        if run.get("source") != "physical_android":
            problems.append(f"{prefix}: source must be physical_android (not VM/TCP)")
        if run.get("session") != "gnome-wayland":
            problems.append(f"{prefix}: session must be gnome-wayland")
        if not isinstance(run.get("kernel"), str) or len(run["kernel"]) < 5:
            problems.append(f"{prefix}: missing running kernel")
        if type(run.get("android_api")) is not int or run["android_api"] < 17:
            problems.append(f"{prefix}: missing Android API level")
        if not HASH64.fullmatch(str(run.get("device_id_sha256", ""))):
            problems.append(f"{prefix}: device_id_sha256 must be a 64-digit lowercase hash")
        for file_key, hash_key in (("artifact", "artifact_sha256"), ("apk_artifact", "apk_sha256")):
            expected_hash = run.get(hash_key)
            if not isinstance(expected_hash, str) or not HASH64.fullmatch(expected_hash):
                problems.append(f"{prefix}: {hash_key} must be a 64-digit lowercase hash")
                continue
            filename = run.get(file_key)
            try:
                artifact = contained_file(artifacts_dir, filename, basename_only=True)
                if sha256(artifact) != expected_hash:
                    problems.append(f"{prefix}: {hash_key} does not match the actual artifact {filename}")
                if file_key == "apk_artifact":
                    if filename != f"wbeam-android-{expected_release}.apk":
                        problems.append(f"{prefix}: apk_artifact must name this release's APK")
                elif distro == "ubuntu-24.04":
                    deb_version = re.sub(r"-(rc|beta|alpha)\.", r"~\1.", expected_release)
                    if not re.fullmatch(r"wbeam_" + re.escape(deb_version) + r"\+noble_amd64\.deb", filename):
                        problems.append(f"{prefix}: Ubuntu run requires this release's Noble DEB")
                else:
                    rpm_version = re.sub(r"-(rc|beta|alpha)\.", r"~\1.", expected_release)
                    if not re.fullmatch(r"wbeam-" + re.escape(rpm_version) + r"-1(?:\.fc43)?\.x86_64\.rpm", filename):
                        problems.append(f"{prefix}: Fedora run requires this release's Fedora RPM")
            except (OSError, RuntimeError, ValueError) as exc:
                problems.append(f"{prefix}: cannot verify {file_key}: {exc}")
        if type(run.get("start_stop_cycles")) is not int or run["start_stop_cycles"] < 20:
            problems.append(f"{prefix}: at least 20 start/stop cycles required")
        if type(run.get("soak_seconds")) is not int or run["soak_seconds"] < 1800:
            problems.append(f"{prefix}: at least 1800 seconds of real streaming required")
        checks = run.get("checks")
        if not isinstance(checks, dict):
            problems.append(f"{prefix}: checks must be an object")
            continue
        for key in COMMON_CHECKS + BACKEND_CHECKS[backend]:
            result = checks.get(key)
            if not isinstance(result, dict) or result.get("status") != "pass":
                problems.append(f"{prefix}: {key} requires status=pass")
                continue
            try:
                path = contained_file(base, result.get("evidence"))
                expected_hash = result.get("evidence_sha256")
                if not isinstance(expected_hash, str) or not HASH64.fullmatch(expected_hash):
                    problems.append(f"{prefix}: {key} requires evidence_sha256")
                elif sha256(path) != expected_hash:
                    problems.append(f"{prefix}: {key} evidence_sha256 does not match the evidence file")
            except (OSError, RuntimeError, ValueError) as exc:
                problems.append(f"{prefix}: {key} cannot verify evidence: {exc}")
    missing = {(d, b) for d in DISTROS for b in BACKENDS} - seen
    for distro, backend in sorted(missing):
        problems.append(f"missing physical matrix run: {distro}/{backend}")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path, help="hardware-acceptance.json created by QA")
    parser.add_argument("--release", required=True, help="expected release identifier (WBEAM_VERSION)")
    parser.add_argument("--commit", required=True, help="full expected build commit (CI_COMMIT_SHA)")
    parser.add_argument("--artifacts-dir", type=Path, required=True, help="directory containing actual packages and VERSION.txt")
    args = parser.parse_args()
    try:
        data = json.loads(args.report.read_text(encoding="utf-8"))
    except (OSError, ValueError, UnicodeError) as exc:
        print(f"[hardware] cannot read report: {exc}", file=sys.stderr)
        return 2
    problems = validate_report(data, args.report.parent, args.release, args.commit, args.artifacts_dir)
    if problems:
        for problem in problems:
            print(f"[hardware][FAIL] {problem}", file=sys.stderr)
        return 1
    print("[hardware] 4 declared physical runs match the build commit, packages, APK and evidence hashes; human hardware sign-off remains required")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
