#!/usr/bin/env bash
set -Eeuo pipefail

SONAR_HOST_URL="${SONAR_HOST_URL:-http://localhost:9000}"
SONAR_PROJECT_KEY="${SONAR_PROJECT_KEY:-wbeam}"
SONAR_TOKEN="${SONAR_TOKEN:-}"
SONAR_STATUSES="${SONAR_STATUSES:-OPEN,CONFIRMED,REOPENED}"
OUTPUT_FILE="${1:-issues.md}"
TMP_JSON="$(mktemp)"
trap 'rm -f "${TMP_JSON}"' EXIT

if [[ -z "${SONAR_TOKEN}" ]]; then
  echo "SONAR_TOKEN is required (export SONAR_TOKEN=...)" >&2
  exit 1
fi

page=1
ps=500
total=0
printf '[]' > "${TMP_JSON}"

while true; do
  response="$(curl -fsS -u "${SONAR_TOKEN}:" \
    "${SONAR_HOST_URL}/api/issues/search?componentKeys=${SONAR_PROJECT_KEY}&statuses=${SONAR_STATUSES}&ps=${ps}&p=${page}")"

  page_total="$(printf '%s' "${response}" | jq -r '.total')"
  if [[ "${page}" -eq 1 ]]; then
    total="${page_total}"
  fi

  printf '%s' "${response}" | jq '.issues' > "${TMP_JSON}.part"
  jq -s '.[0] + .[1]' "${TMP_JSON}" "${TMP_JSON}.part" > "${TMP_JSON}.next"
  mv "${TMP_JSON}.next" "${TMP_JSON}"
  rm -f "${TMP_JSON}.part"

  loaded="$(jq 'length' "${TMP_JSON}")"
  if [[ "${loaded}" -ge "${total}" ]]; then
    break
  fi
  page=$((page + 1))
done

python - "${TMP_JSON}" "${OUTPUT_FILE}" <<'PY'
import json
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone

src, out = sys.argv[1], sys.argv[2]
with open(src, "r", encoding="utf-8") as f:
    issues = json.load(f)

def esc(text):
    return str(text).replace("|", "\\|").replace("\n", " ")

by_file = defaultdict(list)
rule_counts = Counter()
for item in issues:
    comp = item.get("component", "")
    if ":" in comp:
        comp = comp.split(":", 1)[1]
    line = item.get("line")
    rule = item.get("rule", "")
    msg = item.get("message", "")
    sev = item.get("severity", "")
    typ = item.get("type", "")
    status = item.get("status", "")
    by_file[comp].append((line, rule, sev, typ, status, msg))
    rule_counts[rule] += 1

for comp in by_file:
    by_file[comp].sort(key=lambda x: (x[0] is None, x[0] if x[0] is not None else 10**9, x[1], x[5]))

sorted_files = sorted(by_file.items(), key=lambda kv: (-len(kv[1]), kv[0]))

lines = []
lines.append("# Sonar Issues Report (live export)")
lines.append("")
lines.append(f"- Generated at (UTC): {datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M:%S')}")
lines.append(f"- Total issues: {len(issues)}")
lines.append(f"- Total files: {len(sorted_files)}")
lines.append("")
lines.append("## Files sorted by number of issues")
lines.append("")
lines.append("| File | Issues |")
lines.append("|---|---:|")
for comp, rows in sorted_files:
    lines.append(f"| `{esc(comp)}` | {len(rows)} |")
lines.append("")
lines.append("## Issues by file")
lines.append("")

for comp, rows in sorted_files:
    lines.append(f"### `{esc(comp)}` ({len(rows)} issues)")
    lines.append("")
    lines.append("| Line | Rule | Severity | Type | Status | Message |")
    lines.append("|---:|---|---|---|---|---|")
    for line, rule, sev, typ, status, msg in rows:
        line_txt = str(line) if line is not None else "-"
        lines.append(
            f"| {line_txt} | `{esc(rule)}` | {esc(sev)} | {esc(typ)} | {esc(status)} | {esc(msg)} |"
        )
    lines.append("")

lines.append("## Rule summary")
lines.append("")
lines.append("| Rule | Count |")
lines.append("|---|---:|")
for rule, count in sorted(rule_counts.items(), key=lambda kv: (-kv[1], kv[0])):
    lines.append(f"| `{esc(rule)}` | {count} |")
lines.append("")

with open(out, "w", encoding="utf-8") as f:
    f.write("\n".join(lines))
PY

echo "Exported ${total} Sonar issues to ${OUTPUT_FILE}"
