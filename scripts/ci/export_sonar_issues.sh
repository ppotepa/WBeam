#!/usr/bin/env bash
set -Eeuo pipefail

SONAR_HOST_URL="${SONAR_HOST_URL:-http://localhost:9000}"
SONAR_PROJECT_KEY="${SONAR_PROJECT_KEY:-wbeam}"
SONAR_TOKEN="${SONAR_TOKEN:-}"
SONAR_STATUSES="${SONAR_STATUSES:-OPEN,CONFIRMED,REOPENED}"
OUTPUT_FILE="${1:-issues.md}"
TMP_JSON="$(mktemp)"
NORMALIZED_JSON="$(mktemp)"
trap 'rm -f "${TMP_JSON}" "${NORMALIZED_JSON}"' EXIT

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

GENERATED_AT="$(date -u '+%Y-%m-%d %H:%M:%S')"

jq '
  map({
    comp: (
      (.component // "")
      | tostring
      | split(":")
      | if length > 1 then .[1] else .[0] end
    ),
    line,
    rule: (.rule // ""),
    sev: (.severity // ""),
    typ: (.type // ""),
    status: (.status // ""),
    msg: (.message // "")
  })
' "${TMP_JSON}" > "${NORMALIZED_JSON}"

{
  echo "# Sonar Issues Report (live export)"
  echo
  echo "- Generated at (UTC): ${GENERATED_AT}"
  echo "- Total issues: $(jq 'length' "${NORMALIZED_JSON}")"
  echo "- Total files: $(jq '[.[].comp] | unique | length' "${NORMALIZED_JSON}")"
  echo
  echo "## Files sorted by number of issues"
  echo
  echo "| File | Issues |"
  echo "|---|---:|"
  jq -r '
    def esc: tostring | gsub("\\|"; "\\\\|") | gsub("\n"; " ");
    sort_by(.comp)
    | group_by(.comp)
    | map({comp: .[0].comp, count: length})
    | sort_by(-.count, .comp)
    | .[]
    | "| `\(.comp | esc)` | \(.count) |"
  ' "${NORMALIZED_JSON}"
  echo
  echo "## Issues by file"
  echo
  jq -r '
    def esc: tostring | gsub("\\|"; "\\\\|") | gsub("\n"; " ");
    sort_by(.comp, (.line // 1000000000), .rule, .msg)
    | group_by(.comp)
    | sort_by(-(length), .[0].comp)
    | .[]
    | "### `\(.[0].comp | esc)` (\(length) issues)\n\n| Line | Rule | Severity | Type | Status | Message |\n|---:|---|---|---|---|---|",
      (.[] | "| \(if .line == null then "-" else (.line | tostring) end) | `\(.rule | esc)` | \(.sev | esc) | \(.typ | esc) | \(.status | esc) | \(.msg | esc) |"),
      ""
  ' "${NORMALIZED_JSON}"
  echo "## Rule summary"
  echo
  echo "| Rule | Count |"
  echo "|---|---:|"
  jq -r '
    def esc: tostring | gsub("\\|"; "\\\\|") | gsub("\n"; " ");
    sort_by(.rule)
    | group_by(.rule)
    | map({rule: .[0].rule, count: length})
    | sort_by(-.count, .rule)
    | .[]
    | "| `\(.rule | esc)` | \(.count) |"
  ' "${NORMALIZED_JSON}"
  echo
} > "${OUTPUT_FILE}"

echo "Exported ${total} Sonar issues to ${OUTPUT_FILE}"
