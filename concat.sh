#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT_FILE="${1:-$ROOT_DIR/ALL_CODE_CLEAN.txt}"

cd "$ROOT_DIR"

rm -f "$OUT_FILE"
{
  echo "# WBeam clean concat (android + rust + python)"
  echo "# Generated: $(date -Iseconds)"
  echo
} >> "$OUT_FILE"

find . -type f \
  \( -path './android/*' -o -path './host/*' -o -path './protocol/*' \) \
  \( -name '*.rs' -o -name '*.toml' -o -name '*.py' -o -name '*.java' -o -name '*.kt' -o -name '*.kts' -o -name '*.xml' -o -name '*.gradle' -o -name '*.properties' -o -name '*.pro' \) \
  ! -path '*/build/*' \
  ! -path '*/target/*' \
  ! -path '*/.gradle/*' \
  ! -path '*/.git/*' \
  ! -path '*/.idea/*' \
  ! -path '*/.vscode/*' \
  ! -path '*/__pycache__/*' \
  ! -path '*/logs/*' \
  ! -path '*/generated/*' \
  ! -path '*/intermediates/*' \
  ! -path '*/outputs/*' \
  ! -path '*/tmp/*' \
  | sort | while IFS= read -r file; do
      echo "===== FILE: ${file#./} =====" >> "$OUT_FILE"
      cat "$file" >> "$OUT_FILE"
      echo >> "$OUT_FILE"
      echo >> "$OUT_FILE"
    done

wc -l "$OUT_FILE"
ls -lh "$OUT_FILE"
echo "[concat] done: $OUT_FILE"
