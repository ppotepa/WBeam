from __future__ import annotations

import shutil
from pathlib import Path

from .base import DistroInfo


def detect_distro(os_release_path: Path | None = None) -> DistroInfo:
    values: dict[str, str] = {}
    path = os_release_path or Path("/etc/os-release")
    if path.exists():
        for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
            if "=" in line:
                key, value = line.split("=", 1)
                values[key] = value.strip().strip('"')
    package_manager = "unknown"
    if shutil.which("dnf"):
        package_manager = "dnf"
    elif shutil.which("apt-get"):
        package_manager = "apt"
    elif shutil.which("pacman"):
        package_manager = "pacman"
    return DistroInfo(
        id=values.get("ID", "unknown"),
        id_like=values.get("ID_LIKE", ""),
        version=values.get("VERSION_ID", "unknown"),
        package_manager=package_manager,
    )
