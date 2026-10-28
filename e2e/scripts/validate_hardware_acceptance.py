#!/usr/bin/env python3
"""Validate *externally collected* real-hardware WBeam release evidence.

This tool does not generate PASS evidence or assert that a VM/TCP client is an
Android device. QA must run the tests on physical hardware and attach reports.
"""
from __future__ import annotations

import argparse
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


def validate_report(data: object, base: Path, expected_release: str | None = None) -> list[str]:
    problems: list[str] = []
    if not isinstance(data, dict):
        return ["report must be a JSON object"]
    if data.get("schema") != 1:
        problems.append("schema must be 1")
    release = data.get("release")
    if not isinstance(release, str) or not release.strip():
        problems.append("release must be a nonempty string")
    elif expected_release and release != expected_release:
        problems.append(f"release mismatch: expected {expected_release!r}, got {release!r}")
    if not SHA40.fullmatch(str(data.get("tested_commit", ""))):
        problems.append("tested_commit must be a full lowercase 40-digit SHA")
    signoff = data.get("qa_signoff")
    if not isinstance(signoff, str) or len(signoff.strip()) < 3:
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
        if backend not in BACKENDS or distro not in DISTROS:
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
        if not isinstance(run.get("android_api"), int) or run["android_api"] < 17:
            problems.append(f"{prefix}: missing Android API level")
        if not HASH64.fullmatch(str(run.get("device_id_sha256", ""))):
            problems.append(f"{prefix}: device_id_sha256 must be a 64-digit lowercase hash")
        if not HASH64.fullmatch(str(run.get("artifact_sha256", ""))):
            problems.append(f"{prefix}: artifact_sha256 must be a 64-digit lowercase hash")
        if not isinstance(run.get("start_stop_cycles"), int) or run["start_stop_cycles"] < 20:
            problems.append(f"{prefix}: at least 20 start/stop cycles required")
        if not isinstance(run.get("soak_seconds"), int) or run["soak_seconds"] < 1800:
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
            evidence = result.get("evidence")
            if not isinstance(evidence, str) or not evidence.strip():
                problems.append(f"{prefix}: {key} requires evidence file")
                continue
            rel = Path(evidence)
            if rel.is_absolute() or ".." in rel.parts:
                problems.append(f"{prefix}: {key} evidence path must be relative and inside report directory")
                continue
            try:
                root = base.resolve(strict=True)
                path = (root / rel).resolve(strict=True)
                if root not in path.parents or not path.is_file() or path.stat().st_size == 0:
                    problems.append(f"{prefix}: {key} evidence missing/empty/outside report directory")
            except (OSError, RuntimeError):
                problems.append(f"{prefix}: {key} evidence does not exist: {evidence}")
    missing = {(d, b) for d in DISTROS for b in BACKENDS} - seen
    for distro, backend in sorted(missing):
        problems.append(f"missing physical matrix run: {distro}/{backend}")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("report", type=Path, help="hardware-acceptance.json created by QA")
    parser.add_argument("--release", help="expected release identifier")
    args = parser.parse_args()
    try:
        data = json.loads(args.report.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"[hardware] cannot read report: {exc}", file=sys.stderr)
        return 2
    problems = validate_report(data, args.report.parent, args.release)
    if problems:
        for problem in problems:
            print(f"[hardware][FAIL] {problem}", file=sys.stderr)
        return 1
    print("[hardware] 4 physical Android matrix runs accepted with linked evidence")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
