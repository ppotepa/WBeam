#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT="$SCRIPT_DIR/ProtoHostCs.csproj"
DLL="$SCRIPT_DIR/bin/Release/net8.0/ProtoHostCs.dll"

if [[ ! -f "$DLL" ]]; then
  dotnet build -c Release "$PROJECT" >/dev/null
fi

exec dotnet "$DLL"
