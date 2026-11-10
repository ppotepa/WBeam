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
  local tmp curl_exit=0
  tmp="$(mktemp)"

  if [[ -n "${data}" ]]; then
    API_STATUS="$(curl -sS -o "${tmp}" -w '%{http_code}' \
      -X "${method}" \
      -H "Authorization: Bearer ${GH_TOKEN}" \
      -H "Accept: application/vnd.github+json" \
      -H "X-GitHub-Api-Version: 2022-11-28" \
      -H "Content-Type: application/json" \
      "${url}" \
      --data "${data}")" || curl_exit=$?
  else
    API_STATUS="$(curl -sS -o "${tmp}" -w '%{http_code}' \
      -X "${method}" \
      -H "Authorization: Bearer ${GH_TOKEN}" \
      -H "Accept: application/vnd.github+json" \
      -H "X-GitHub-Api-Version: 2022-11-28" \
      "${url}")" || curl_exit=$?
  fi

  API_BODY="$(cat "${tmp}")"
  rm -f "${tmp}"
  if (( curl_exit != 0 )); then
    echo "[publish] GitHub request failed: ${method} ${url}" >&2
    return "$curl_exit"
  fi
}

# Resolve the tag itself: release.target_commitish does not change an existing
# Git ref. Annotated tags may point to another annotated tag before a commit.
# Return 1 only for an absent ref; other failures must not be treated as absence.
resolve_tag_commit() {
  local tag="$1" object_type object_sha depth
  RESOLVED_TAG_COMMIT=""
  api_call GET "${API_ROOT}/git/ref/tags/${tag}" || return 2
  if [[ "$API_STATUS" == "404" ]]; then return 1; fi
  if [[ "$API_STATUS" != "200" ]]; then
    echo "[publish] Cannot resolve tag ${tag}, status=${API_STATUS}" >&2
    return 2
  fi
  for ((depth = 0; depth < 16; depth++)); do
    object_type="$(printf '%s' "$API_BODY" | jq -er '.object.type')" || return 2
    object_sha="$(printf '%s' "$API_BODY" | jq -er '.object.sha')" || return 2
    if [[ ! "$object_sha" =~ ^[0-9a-f]{40}$ ]]; then
      echo "[publish] Invalid Git object SHA for tag ${tag}" >&2
      return 2
    fi
    case "$object_type" in
      commit)
        RESOLVED_TAG_COMMIT="$object_sha"
        return 0
        ;;
      tag)
        api_call GET "${API_ROOT}/git/tags/${object_sha}" || return 2
        if [[ "$API_STATUS" != "200" ]]; then
          echo "[publish] Cannot resolve annotated tag ${tag}, status=${API_STATUS}" >&2
          return 2
        fi
        ;;
      *)
        echo "[publish] Tag ${tag} does not resolve to a commit" >&2
        return 2
        ;;
    esac
  done
  echo "[publish] Annotated tag chain is too deep for ${tag}" >&2
  return 2
}

verify_tag_commit() {
  local tag="$1"
  if ! resolve_tag_commit "$tag"; then
    echo "[publish] Cannot verify build commit for tag ${tag}" >&2
    exit 1
  fi
  if [[ "$RESOLVED_TAG_COMMIT" != "$CI_COMMIT_SHA" ]]; then
    echo "[publish] Tag ${tag} resolves to ${RESOLVED_TAG_COMMIT}, not tested commit ${CI_COMMIT_SHA}" >&2
    exit 1
  fi
}

ensure_release_tag() {
  local tag="$1" allow_replace="$2" result=0 payload
  if [[ "$allow_replace" == "true" && "$tag" != "release-latest" ]]; then
    echo "[publish] Only release-latest may move to another commit" >&2
    exit 1
  fi
  resolve_tag_commit "$tag" || result=$?
  if (( result == 1 )); then
    payload="$(jq -n --arg ref "refs/tags/${tag}" --arg sha "$CI_COMMIT_SHA" '{ref:$ref,sha:$sha}')"
    api_call POST "${API_ROOT}/git/refs" "$payload"
    # A competing publisher may create the ref between GET and POST. Accept
    # that race only if the ref now resolves to this exact tested commit.
    if [[ "$API_STATUS" != "201" && "$API_STATUS" != "422" ]]; then
      echo "[publish] Cannot create tag ${tag}, status=${API_STATUS}" >&2
      exit 1
    fi
  elif (( result != 0 )); then
    echo "[publish] Failed to inspect tag ${tag}; refusing publication" >&2
    exit 1
  elif [[ "$RESOLVED_TAG_COMMIT" != "$CI_COMMIT_SHA" ]]; then
    if [[ "$allow_replace" != "true" ]]; then
      echo "[publish] Immutable tag ${tag} differs from tested commit ${CI_COMMIT_SHA}" >&2
      exit 1
    fi
    payload="$(jq -n --arg sha "$CI_COMMIT_SHA" '{sha:$sha,force:true}')"
    api_call PATCH "${API_ROOT}/git/refs/tags/${tag}" "$payload"
    if [[ "$API_STATUS" != "200" ]]; then
      echo "[publish] Cannot move rolling tag ${tag}, status=${API_STATUS}" >&2
      exit 1
    fi
  fi
  verify_tag_commit "$tag"
}

prune_rolling_assets() {
  local tag="$1" approved_names="$2" page=1 count stale_ids='[]' ids id
  if [[ "$tag" != "release-latest" || "$ALLOW_REPLACE_ASSETS" != "true" ]]; then
    echo "[publish] Stale asset removal is restricted to the rolling release" >&2
    exit 1
  fi
  # Collect all pages before deleting: deleting while paging shifts later
  # records and can leave stale assets behind.
  while true; do
    api_call GET "${API_ROOT}/releases/${RELEASE_ID}/assets?per_page=100&page=${page}"
    if [[ "$API_STATUS" != "200" ]]; then
      echo "[publish] Cannot list rolling assets, status=${API_STATUS}" >&2
      exit 1
    fi
    count="$(printf '%s' "$API_BODY" | jq -er '
      if type == "array" and all(.[]; (.id | type == "number") and (.name | type == "string"))
      then length else error("invalid release assets response") end')"
    stale_ids="$(jq -cn --argjson ids "$stale_ids" --argjson assets "$API_BODY" \
      --argjson approved "$approved_names" \
      '$ids + [$assets[] | .name as $name | select(($approved | index($name)) == null) | .id]')"
    if (( count < 100 )); then break; fi
    page=$((page + 1))
  done
  ids="$(printf '%s' "$stale_ids" | jq -r '.[]')"
  while IFS= read -r id; do
    [[ -n "$id" ]] || continue
    api_call DELETE "${API_ROOT}/releases/assets/${id}"
    if [[ "$API_STATUS" != "204" ]]; then
      echo "[publish] Cannot remove stale rolling asset ${id}, status=${API_STATUS}" >&2
      exit 1
    fi
  done <<< "$ids"
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

  local upload_status upload_response
  upload_response="$(mktemp)"
  if ! upload_status="$(curl -sS -o "$upload_response" -w '%{http_code}' \
    -X POST \
    -H "Authorization: Bearer ${GH_TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    -H "Content-Type: application/octet-stream" \
    --data-binary @"${file_path}" \
    "${upload_url}?name=${encoded_name}")"; then
    rm -f "$upload_response"
    echo "[publish] Upload request failed for ${file_name}" >&2
    exit 1
  fi

  if [[ "${upload_status}" != "201" ]]; then
    echo "[publish] Upload failed for ${file_name}, status=${upload_status}" >&2
    cat "$upload_response" >&2
    rm -f "$upload_response"
    exit 1
  fi
  rm -f "$upload_response"
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
require_env CI_COMMIT_SHA
[[ "$CI_COMMIT_SHA" =~ ^[0-9a-f]{40}$ ]] || {
  echo "[publish] CI_COMMIT_SHA must identify the exact tested build commit." >&2
  exit 1
}
[[ -f "${WBEAM_HARDWARE_ACCEPTANCE_FILE}" ]] || {
  echo "[publish] QA acceptance file does not exist." >&2
  exit 1
}
# Validate package set before contacting GitHub. Always generate a manifest
# with checksums of immutable artifacts and the exact build version.
bash "${ROOT_DIR}/scripts/ci/check-release-artifacts.sh"
python3 "${ROOT_DIR}/e2e/scripts/validate_hardware_acceptance.py" \
  "${WBEAM_HARDWARE_ACCEPTANCE_FILE}" --release "${WBEAM_VERSION}" \
  --commit "${CI_COMMIT_SHA}" --artifacts-dir "${DIST_DIR}"
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
  # Keep new assets private until uploads and the final tag/commit check pass.
  # The rolling release is made a draft before its ref is moved as well.
  local release_payload release_body approved_names file
  local -a files=() names=()
  release_payload="$(jq -n \
    --arg tag_name "${tag}" \
    --arg target_commitish "${CI_COMMIT_SHA:-}" \
    --arg name "${name}" \
    --arg body "Automated release from GitLab pipeline ${CI_PIPELINE_URL:-local}. Version: ${WBEAM_VERSION}" \
    --argjson prerelease "${prerelease}" \
    --argjson draft true \
    '{tag_name:$tag_name,target_commitish:$target_commitish,name:$name,body:$body,draft:$draft,prerelease:$prerelease}')"

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
    release_body="$API_BODY"
    ensure_release_tag "$tag" "$allow_replace"
  elif [[ "${API_STATUS}" == "404" ]]; then
    ensure_release_tag "$tag" "$allow_replace"
    api_call POST "${API_ROOT}/releases" "${release_payload}"
    if [[ "${API_STATUS}" != "201" ]]; then
      echo "[publish] Failed to create release ${tag}, status=${API_STATUS}" >&2
      echo "${API_BODY}" >&2
      exit 1
    fi
    release_body="$API_BODY"
  else
    echo "[publish] Failed to fetch release ${tag}, status=${API_STATUS}" >&2
    echo "${API_BODY}" >&2
    exit 1
  fi

  RELEASE_ID="$(printf '%s' "${release_body}" | jq -r '.id')"
  UPLOAD_URL="$(printf '%s' "${release_body}" | jq -r '.upload_url' | sed 's/{?name,label}//')"

  ALLOW_REPLACE_ASSETS="${allow_replace}"
  # Publish only the validated Linux/Android artifact set plus provenance.
  # Never upload stale or unsupported files accidentally left under dist/.
  shopt -s nullglob
  files=("${DIST_DIR}"/*.deb "${DIST_DIR}"/*.rpm "${DIST_DIR}"/*.apk \
    "${DIST_DIR}/VERSION.txt" "${DIST_DIR}/SHA256SUMS.txt")
  if [[ "$allow_replace" == "true" ]]; then
    for file in "${files[@]}"; do names+=("$(basename "$file")"); done
    approved_names="$(jq -cn --args '$ARGS.positional' "${names[@]}")"
    prune_rolling_assets "$tag" "$approved_names"
  fi
  for file in "${files[@]}"; do
    [[ -s "${file}" ]] || continue
    echo "[publish] Uploading $(basename "${file}") to ${tag}"
    upload_asset "${UPLOAD_URL}" "${file}"
  done

  verify_tag_commit "$tag"
  api_call PATCH "${API_ROOT}/releases/${RELEASE_ID}" '{"draft":false}'
  if [[ "${API_STATUS}" != "200" ]]; then
    echo "[publish] Assets uploaded but release remains a draft: tag=${tag}. Review and retry with a new tag/draft." >&2
    exit 1
  fi

  echo "[publish] Release published: https://github.com/${GH_OWNER}/${GH_REPO}/releases/tag/${tag}"
}

if [[ "${RELEASE_TAG}" == "release-latest" ]]; then
  publish_release "${RELEASE_TAG}" "${RELEASE_NAME}" "${PRERELEASE_JSON}" "true"
else
  publish_release "${RELEASE_TAG}" "${RELEASE_NAME}" "${PRERELEASE_JSON}" "false"
fi
