#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

PORTAL_CONSENTED_KINDS = {"portal_consented", "portal-consented"}


def read_json(path: Path) -> dict:
    if not path.exists():
        return {}
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return {}
    return payload if isinstance(payload, dict) else {}


def is_portal_consented_kind(kind: str) -> bool:
    return str(kind or "").strip() in PORTAL_CONSENTED_KINDS


def validate_portal_consented_asset(
    *,
    distro: str,
    session: str,
    base_dir: Path,
    allow_missing: bool = False,
) -> tuple[int, dict]:
    image = base_dir / distro / f"{session}-portal-consented.qcow2"
    manifest = image.with_suffix(".json")
    payload = {
        "schema": 1,
        "image": str(image),
        "manifest": str(manifest),
    }

    if not image.exists() or not manifest.exists():
        payload.update({"ok": False, "status": "missing", "reason_code": "missing_portal_consented_image"})
        return (0 if allow_missing else 2), payload
    try:
        size = image.stat().st_size
    except OSError as exc:
        payload.update({"ok": False, "status": "invalid", "reason_code": "image_stat_failed", "reason": str(exc)})
        return 3, payload
    if size <= 10 * 1024 * 1024:
        payload.update({"ok": False, "status": "invalid", "reason_code": "image_too_small"})
        return 3, payload

    qemu_img = shutil.which("qemu-img")
    if qemu_img:
        proc = subprocess.run([qemu_img, "info", str(image)], capture_output=True, text=True, check=False)
        if proc.returncode != 0:
            payload.update({"ok": False, "status": "invalid", "reason_code": "qemu_img_info_failed"})
            return 4, payload
    manifest_payload = read_json(manifest)
    if not manifest_payload:
        payload.update({"ok": False, "status": "invalid", "reason_code": "invalid_portal_consented_manifest"})
        return 3, payload
    schema = manifest_payload.get("schema")
    if schema != 2:
        payload.update({"ok": False, "status": "stale", "reason_code": "unsupported_portal_consented_schema"})
        return 5, payload
    if not is_portal_consented_kind(str(manifest_payload.get("kind", ""))):
        payload.update({"ok": False, "status": "invalid", "reason_code": "invalid_portal_consented_kind"})
        return 3, payload
    if manifest_payload.get("distro") != distro or manifest_payload.get("session") != session:
        payload.update({"ok": False, "status": "stale", "reason_code": "portal_consented_manifest_mismatch"})
        return 5, payload
    if manifest_payload.get("backend") != "wayland_portal":
        payload.update({"ok": False, "status": "invalid", "reason_code": "portal_consented_backend_mismatch"})
        return 3, payload
    if manifest_payload.get("stream_smoke_ok") is not True:
        payload.update({"ok": False, "status": "stale", "reason_code": "portal_consented_stream_not_ok"})
        return 5, payload
    validation = manifest_payload.get("validation") if isinstance(manifest_payload.get("validation"), dict) else {}
    if validation.get("client_connected") is not True or validation.get("bytes_read_gt_zero") is not True:
        payload.update({"ok": False, "status": "invalid", "reason_code": "portal_consented_validation_failed"})
        return 3, payload

    payload.update(
        {
            "ok": True,
            "status": "ok",
            "reason_code": "portal_consented_asset_ok",
            "validation": validation,
        }
    )
    return 0, payload


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="validate-portal-consented-asset")
    parser.add_argument("--distro", required=True)
    parser.add_argument("--session", default="gnome-wayland")
    parser.add_argument("--base-dir", default=str(ROOT / "e2e" / "images" / "base"))
    parser.add_argument("--allow-missing", action="store_true")
    parser.add_argument("--json", action="store_true")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    rc, payload = validate_portal_consented_asset(
        distro=args.distro,
        session=args.session,
        base_dir=Path(args.base_dir).expanduser().resolve(),
        allow_missing=args.allow_missing,
    )
    print(json.dumps(payload, indent=2, sort_keys=True))
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
