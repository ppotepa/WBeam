#!/usr/bin/env bash
set -euo pipefail
X11_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$X11_DIR/run" "acceptance" "$@"
