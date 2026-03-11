#!/usr/bin/env bash
set -Eeuo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

require_env() {
  local key="$1"
  if [[ -z "${!key:-}" ]]; then
    echo "[publish] Missing required env var: ${key}" >&2
    exit 1
  fi
}

api_call() {
  local method="$1"
  local url="$2"
  local data="${3:-}"
  local tmp
  tmp="$(mktemp)"

  if [[ -n "${data}" ]]; then
    API_STATUS="$(curl -sS -o "${tmp}" -w '%{http_code}' \
      -X "${method}" \
      -H "Authorization: Bearer ${GH_TOKEN}" \
      -H "Accept: application/vnd.github+json" \
      -H "X-GitHub-Api-Version: 2022-11-28" \
      -H "Content-Type: application/json" \
      "${url}" \
      --data "${data}")"
  else
    API_STATUS="$(curl -sS -o "${tmp}" -w '%{http_code}' \
      -X "${method}" \
      -H "Authorization: Bearer ${GH_TOKEN}" \
      -H "Accept: application/vnd.github+json" \
      -H "X-GitHub-Api-Version: 2022-11-28" \
      "${url}")"
  fi

  API_BODY="$(cat "${tmp}")"
  rm -f "${tmp}"
}

upload_asset() {
  local upload_url="$1"
  local file_path="$2"
  local file_name encoded_name
  file_name="$(basename "${file_path}")"
  encoded_name="$(jq -rn --arg v "${file_name}" '$v|@uri')"

  api_call GET "${API_ROOT}/releases/${RELEASE_ID}/assets?per_page=100"
  if [[ "${API_STATUS}" != "200" ]]; then
    echo "[publish] Could not list assets, status=${API_STATUS}" >&2
    echo "${API_BODY}" >&2
    exit 1
  fi

  local existing_id
  existing_id="$(printf '%s' "${API_BODY}" | jq -r --arg n "${file_name}" '.[] | select(.name == $n) | .id' | head -n1)"
  if [[ -n "${existing_id}" && "${existing_id}" != "null" ]]; then
    api_call DELETE "${API_ROOT}/releases/assets/${existing_id}"
    if [[ "${API_STATUS}" != "204" ]]; then
      echo "[publish] Could not delete old asset ${file_name}, status=${API_STATUS}" >&2
      echo "${API_BODY}" >&2
      exit 1
    fi
  fi

  local upload_status
  upload_status="$(curl -sS -o /tmp/wbeam-upload.out -w '%{http_code}' \
    -X POST \
    -H "Authorization: Bearer ${GH_TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    -H "Content-Type: application/octet-stream" \
    --data-binary @"${file_path}" \
    "${upload_url}?name=${encoded_name}")"

  if [[ "${upload_status}" != "201" ]]; then
    echo "[publish] Upload failed for ${file_name}, status=${upload_status}" >&2
    cat /tmp/wbeam-upload.out >&2
    exit 1
  fi
}

require_env GH_TOKEN
require_env GH_OWNER
require_env GH_REPO
require_env WBEAM_VERSION

if ! ls "${DIST_DIR}"/* >/dev/null 2>&1; then
  echo "[publish] No artifacts in ${DIST_DIR}; nothing to upload." >&2
  exit 1
fi

if [[ -n "${CI_COMMIT_TAG:-}" ]]; then
  RELEASE_TAG="${CI_COMMIT_TAG}"
  RELEASE_NAME="${CI_COMMIT_TAG}"
  PRERELEASE_JSON="false"
elif [[ "${CI_COMMIT_BRANCH:-}" == "main" || "${CI_COMMIT_BRANCH:-}" == "${CI_DEFAULT_BRANCH:-}" ]]; then
  RELEASE_TAG="main-latest"
  RELEASE_NAME="main-latest (${WBEAM_VERSION})"
  PRERELEASE_JSON="true"
else
  echo "[publish] Branch is not releasable, skipping."
  exit 0
fi

API_ROOT="https://api.github.com/repos/${GH_OWNER}/${GH_REPO}"
RELEASE_PAYLOAD="$(jq -n \
  --arg tag_name "${RELEASE_TAG}" \
  --arg target_commitish "${CI_COMMIT_SHA:-}" \
  --arg name "${RELEASE_NAME}" \
  --arg body "Automated release from GitLab pipeline ${CI_PIPELINE_URL:-local}. Version: ${WBEAM_VERSION}" \
  --argjson prerelease "${PRERELEASE_JSON}" \
  '{tag_name:$tag_name,target_commitish:$target_commitish,name:$name,body:$body,draft:false,prerelease:$prerelease}')"

api_call GET "${API_ROOT}/releases/tags/${RELEASE_TAG}"
if [[ "${API_STATUS}" == "200" ]]; then
  RELEASE_ID="$(printf '%s' "${API_BODY}" | jq -r '.id')"
  api_call PATCH "${API_ROOT}/releases/${RELEASE_ID}" "${RELEASE_PAYLOAD}"
  if [[ "${API_STATUS}" != "200" ]]; then
    echo "[publish] Failed to update release ${RELEASE_TAG}, status=${API_STATUS}" >&2
    echo "${API_BODY}" >&2
    exit 1
  fi
elif [[ "${API_STATUS}" == "404" ]]; then
  api_call POST "${API_ROOT}/releases" "${RELEASE_PAYLOAD}"
  if [[ "${API_STATUS}" != "201" ]]; then
    echo "[publish] Failed to create release ${RELEASE_TAG}, status=${API_STATUS}" >&2
    echo "${API_BODY}" >&2
    exit 1
  fi
else
  echo "[publish] Failed to fetch release ${RELEASE_TAG}, status=${API_STATUS}" >&2
  echo "${API_BODY}" >&2
  exit 1
fi

RELEASE_ID="$(printf '%s' "${API_BODY}" | jq -r '.id')"
UPLOAD_URL="$(printf '%s' "${API_BODY}" | jq -r '.upload_url' | sed 's/{?name,label}//')"

for file in "${DIST_DIR}"/*; do
  [[ -f "${file}" ]] || continue
  echo "[publish] Uploading $(basename "${file}")"
  upload_asset "${UPLOAD_URL}" "${file}"
done

echo "[publish] Release updated: https://github.com/${GH_OWNER}/${GH_REPO}/releases/tag/${RELEASE_TAG}"
