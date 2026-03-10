#!/usr/bin/env python3
"""Compatibility wrapper for relocated training legacy engine."""

from __future__ import annotations

import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
ENGINE_PATH = REPO_ROOT / "src" / "domains" / "training" / "legacy_engine.py"

if not ENGINE_PATH.exists():
    print(f"[autotune] missing engine: {ENGINE_PATH}", file=sys.stderr)
    sys.exit(1)

sys.path.insert(0, str(ENGINE_PATH.parent))
from legacy_engine import main  # type: ignore  # noqa: E402


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
