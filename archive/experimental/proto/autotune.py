#!/usr/bin/env python3
"""Deprecated entrypoint: legacy proto autotune has been removed."""

from __future__ import annotations

import sys


def main() -> int:
    print(
        "[autotune] legacy proto trainer path has been removed.\n"
        "[autotune] use: ./wbeam train wizard\n"
        "[autotune] for max-quality preset run: ./src/domains/training/train_max_quality.sh <serial>",
        file=sys.stderr,
    )
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
