#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
exec cargo run --manifest-path "$ROOT_DIR/src/apps/desktop-egui/Cargo.toml" -- "$@"
