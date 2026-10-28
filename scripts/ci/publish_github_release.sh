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
    if [[ "${ALLOW_REPLACE_ASSETS:-false}" != "true" ]]; then
      echo "[publish] Refusing to replace an existing tagged release asset: ${file_name}" >&2
      exit 1
    fi
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

# Publication is a manual protected operation, never an automatic side
# effect of a build or a release branch push.
if [[ "${WBEAM_RELEASE_APPROVED:-}" != "1" ]]; then
  echo "[publish] Protected WBEAM_RELEASE_APPROVED=1 required for publication." >&2
  exit 1
fi
if [[ ! -f "${ROOT_DIR}/LICENSE" ]]; then
  echo "[publish] Missing LICENSE; maintainer must confirm legal terms before release." >&2
  exit 1
fi
require_env WBEAM_HARDWARE_ACCEPTANCE_FILE
[[ -f "${WBEAM_HARDWARE_ACCEPTANCE_FILE}" ]] || {
  echo "[publish] QA acceptance file does not exist." >&2
  exit 1
}
python3 "${ROOT_DIR}/e2e/scripts/validate_hardware_acceptance.py" \
  "${WBEAM_HARDWARE_ACCEPTANCE_FILE}" --release "${WBEAM_VERSION}"

# Validate package set before contacting GitHub. Always generate a manifest
# with checksums of immutable artifacts and the exact build version.
bash "${ROOT_DIR}/scripts/ci/check-release-artifacts.sh"
require_env GH_TOKEN
require_env GH_OWNER
require_env GH_REPO
require_env WBEAM_VERSION

if [[ -n "${CI_COMMIT_TAG:-}" ]]; then
  RELEASE_TAG="${CI_COMMIT_TAG}"
  RELEASE_NAME="${CI_COMMIT_TAG}"
  if [[ "${RELEASE_TAG}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    PRERELEASE_JSON="false"
  elif [[ "${RELEASE_TAG}" =~ ^v[0-9]+\.[0-9]+\.[0-9]+-(rc|beta|alpha)\.[0-9]+$ ]]; then
    PRERELEASE_JSON="true"
  else
    echo "[publish] Invalid release tag: ${RELEASE_TAG} (expected vX.Y.Z or vX.Y.Z-rc.N)" >&2
    exit 1
  fi
elif [[ "${CI_COMMIT_BRANCH:-}" == "release" ]]; then
  RELEASE_TAG="release-latest"
  RELEASE_NAME="${RELEASE_TAG} (${WBEAM_VERSION})"
  PRERELEASE_JSON="true"
else
  echo "[publish] Only a manual release-branch prerelease or a signed-off version tag is publishable." >&2
  exit 1
fi

API_ROOT="https://api.github.com/repos/${GH_OWNER}/${GH_REPO}"

publish_release() {
  local tag="$1"
  local name="$2"
  local prerelease="$3"
  local allow_replace="${4:-false}"
  local release_payload
  release_payload="$(jq -n \
    --arg tag_name "${tag}" \
    --arg target_commitish "${CI_COMMIT_SHA:-}" \
    --arg name "${name}" \
    --arg body "Automated release from GitLab pipeline ${CI_PIPELINE_URL:-local}. Version: ${WBEAM_VERSION}" \
    --argjson prerelease "${prerelease}" \
    '{tag_name:$tag_name,target_commitish:$target_commitish,name:$name,body:$body,draft:false,prerelease:$prerelease}')"

  api_call GET "${API_ROOT}/releases/tags/${tag}"
  if [[ "${API_STATUS}" == "200" ]]; then
    if [[ "${allow_replace}" != "true" ]]; then
      echo "[publish] Versioned GitHub release already exists (${tag}); refusing to mutate it." >&2
      exit 1
    fi
    RELEASE_ID="$(printf '%s' "${API_BODY}" | jq -r '.id')"
    api_call PATCH "${API_ROOT}/releases/${RELEASE_ID}" "${release_payload}"
    if [[ "${API_STATUS}" != "200" ]]; then
      echo "[publish] Failed to update release ${tag}, status=${API_STATUS}" >&2
      echo "${API_BODY}" >&2
      exit 1
    fi
  elif [[ "${API_STATUS}" == "404" ]]; then
    api_call POST "${API_ROOT}/releases" "${release_payload}"
    if [[ "${API_STATUS}" != "201" ]]; then
      echo "[publish] Failed to create release ${tag}, status=${API_STATUS}" >&2
      echo "${API_BODY}" >&2
      exit 1
    fi
  else
    echo "[publish] Failed to fetch release ${tag}, status=${API_STATUS}" >&2
    echo "${API_BODY}" >&2
    exit 1
  fi

  RELEASE_ID="$(printf '%s' "${API_BODY}" | jq -r '.id')"
  UPLOAD_URL="$(printf '%s' "${API_BODY}" | jq -r '.upload_url' | sed 's/{?name,label}//')"

  ALLOW_REPLACE_ASSETS="${allow_replace}"
  # Publish only the validated Linux/Android artifact set plus provenance.
  # Never upload stale or unsupported files accidentally left under dist/.
  shopt -s nullglob
  for file in "${DIST_DIR}"/*.deb "${DIST_DIR}"/*.rpm "${DIST_DIR}"/*.apk \
    "${DIST_DIR}/VERSION.txt" "${DIST_DIR}/SHA256SUMS.txt"; do
    [[ -s "${file}" ]] || continue
    echo "[publish] Uploading $(basename "${file}") to ${tag}"
    upload_asset "${UPLOAD_URL}" "${file}"
  done

  echo "[publish] Release updated: https://github.com/${GH_OWNER}/${GH_REPO}/releases/tag/${tag}"
}

if [[ "${RELEASE_TAG}" == "release-latest" ]]; then
  publish_release "${RELEASE_TAG}" "${RELEASE_NAME}" "${PRERELEASE_JSON}" "true"
else
  publish_release "${RELEASE_TAG}" "${RELEASE_NAME}" "${PRERELEASE_JSON}" "false"
fi
