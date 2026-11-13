#!/usr/bin/env bash
# bash -n accepts one script; check each tracked/new shell entrypoint separately.
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"
manifest="$(mktemp)"
trap 'rm -f "$manifest"' EXIT
git ls-files --cached --others --exclude-standard -z -- '*.sh' \
  wbeam waybeam install-wbeam install-waybeam devtool wbgui \
  redeploy-local start-remote runas-remote > "$manifest"
failed=0
count=0
while IFS= read -r -d '' script; do
  [[ -f "$script" ]] || continue
  bash -n "$script" || failed=1
  count=$((count + 1))
done < "$manifest"
(( count > 0 )) || { echo '[syntax] no shell entrypoints found' >&2; exit 1; }
(( failed == 0 )) || exit 1
printf '[syntax] %d shell entrypoints parsed successfully\n' "$count"
