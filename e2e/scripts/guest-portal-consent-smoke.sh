#!/usr/bin/env bash
set -euo pipefail
exec "$(dirname "$0")/guest-portal-consent.sh" "$@"
