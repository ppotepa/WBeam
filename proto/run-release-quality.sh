#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
export PROTO_ANDROID_BUILD_TYPE=release
exec ./rr quality
