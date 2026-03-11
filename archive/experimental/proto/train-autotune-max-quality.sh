#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
exec "${ROOT_DIR}/src/domains/training/train_max_quality.sh" "$@"
