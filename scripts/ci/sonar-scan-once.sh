#!/usr/bin/env sh
set -eu

REPO_DIR="${REPO_DIR:-/workspace}"
SONAR_HOST_URL="${SONAR_HOST_URL:-http://sonarqube:9000}"
SONAR_USER_HOME="${SONAR_USER_HOME:-${REPO_DIR}/.sonar}"
PROJECT_KEY="${PROJECT_KEY:-wbeam}"
PROJECT_NAME="${PROJECT_NAME:-WBeam}"
PRE_SCAN_CMD="${PRE_SCAN_CMD:-}"

if [ -z "${SONAR_TOKEN:-}" ]; then
  echo "SONAR_TOKEN is required"
  exit 1
fi

mkdir -p "${SONAR_USER_HOME}/cache"

if [ -d "${REPO_DIR}/.git" ]; then
  git config --global --add safe.directory "${REPO_DIR}" >/dev/null 2>&1 || true
fi

if [ -n "${PRE_SCAN_CMD}" ]; then
  sh -lc "cd '${REPO_DIR}' && ${PRE_SCAN_CMD}" || true
fi

exec sonar-scanner \
  -Dsonar.projectBaseDir="${REPO_DIR}" \
  -Dsonar.userHome="${SONAR_USER_HOME}" \
  -Dsonar.host.url="${SONAR_HOST_URL}" \
  -Dsonar.token="${SONAR_TOKEN}" \
  -Dsonar.projectKey="${PROJECT_KEY}" \
  -Dsonar.projectName="${PROJECT_NAME}" \
  -Dsonar.qualitygate.wait=false
