#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path

ISOS = {
    "WBEAM_E2E_ISO_FEDORA_43": {
        "distro": "fedora-43",
        "url": "https://download.fedoraproject.org/pub/fedora/linux/releases/43/Everything/x86_64/iso/Fedora-Everything-netinst-x86_64-43-1.6.iso",
        "filename": "fedora-43-netinst.iso",
        "min_size": 600 * 1024 * 1024,
    },
    "WBEAM_E2E_ISO_UBUNTU_24_04": {
        "distro": "ubuntu-24.04",
        "url": "https://releases.ubuntu.com/noble/ubuntu-24.04.4-desktop-amd64.iso",
        "filename": "ubuntu-24.04-desktop.iso",
        "min_size": 2000 * 1024 * 1024,
    },
    "WBEAM_E2E_ISO_DEBIAN_12": {
        "distro": "debian-12",
        "url": "https://cdimage.debian.org/cdimage/archive/12.12.0/amd64/iso-cd/debian-12.12.0-amd64-netinst.iso",
        "filename": "debian-12-netinst.iso",
        "min_size": 600 * 1024 * 1024,
    },
}

DISTRO_TO_ENV = {info["distro"]: env for env, info in ISOS.items()}


def parse_args() -> argparse.Namespace:
    return build_parser().parse_args()


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="download_isos.py")
    parser.add_argument("--distro", action="append", choices=sorted(DISTRO_TO_ENV))
    parser.add_argument("--missing", action="store_true")
    parser.add_argument("--json-events", action="store_true")
    return parser


def emit(args: argparse.Namespace, event: dict) -> None:
    if args.json_events:
        print(json.dumps(event, sort_keys=True), flush=True)
        return
    print(f"[download] {event.get('message', '')}", flush=True)


def selected_iso_items(args: argparse.Namespace):
    if not args.distro:
        return list(ISOS.items())
    selected = {DISTRO_TO_ENV[d] for d in args.distro}
    return [(env, info) for env, info in ISOS.items() if env in selected]


def target_is_valid(path: Path, min_size: int) -> bool:
    return path.exists() and path.stat().st_size >= min_size


def read_env_local(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def write_env_local(path: Path, values: dict[str, str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as fh:
        for key in sorted(values):
            fh.write(f"{key}={values[key]}\n")


def sync_selected_isos(args: argparse.Namespace, iso_dir: Path, env_local: Path) -> int:
    iso_dir.mkdir(parents=True, exist_ok=True)
    current_env = read_env_local(env_local)
    updated = False

    for env_var, info in selected_iso_items(args):
        target_path = iso_dir / info["filename"]
        valid_existing = target_is_valid(target_path, info["min_size"])
        if args.missing and valid_existing:
            emit(args, {"type": "iso_reuse", "env": env_var, "message": f"ISO OK: {target_path}", "path": str(target_path)})
        else:
            if target_path.exists() and not valid_existing:
                emit(
                    args,
                    {
                        "type": "iso_corrupt",
                        "env": env_var,
                        "message": f"{info['filename']} seems corrupted ({target_path.stat().st_size} bytes), re-downloading",
                        "path": str(target_path),
                    },
                )
            if not valid_existing:
                emit(args, {"type": "iso_download", "env": env_var, "message": f"Fetching {info['filename']}", "path": str(target_path)})
                try:
                    subprocess.run(
                        ["curl", "-L", "--fail", "--continue-at", "-", "--output", str(target_path), info["url"]],
                        check=True,
                    )
                    emit(args, {"type": "iso_done", "env": env_var, "message": f"Finished {info['filename']}", "path": str(target_path)})
                except Exception as exc:  # noqa: BLE001
                    emit(args, {"type": "iso_error", "env": env_var, "message": f"ERROR downloading {info['filename']}: {exc}", "path": str(target_path)})
                    continue
                if not target_is_valid(target_path, info["min_size"]):
                    emit(
                        args,
                        {
                            "type": "iso_error",
                            "env": env_var,
                            "message": f"Downloaded file is too small: {target_path.stat().st_size} bytes",
                            "path": str(target_path),
                        },
                    )
                    continue
        if current_env.get(env_var) != str(target_path):
            current_env[env_var] = str(target_path)
            updated = True

    if updated:
        write_env_local(env_local, current_env)
        emit(args, {"type": "env_updated", "message": f"Updated {env_local}"})
    else:
        emit(args, {"type": "noop", "message": "All selected ISOs present and env.local is up to date."})
    return 0


def main() -> int:
    args = parse_args()
    iso_dir = Path("e2e/images/iso").resolve()
    env_local = Path("e2e/env.local")
    return sync_selected_isos(args, iso_dir, env_local)


if __name__ == "__main__":
    raise SystemExit(main())
