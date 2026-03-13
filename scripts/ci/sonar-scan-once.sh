#!/usr/bin/env sh
set -eu

REPO_DIR="${REPO_DIR:-/workspace}"
SONAR_HOST_URL="${SONAR_HOST_URL:-http://sonarqube:9000}"
SONAR_USER_HOME="${SONAR_USER_HOME:-${REPO_DIR}/.sonar}"
PROJECT_KEY="${PROJECT_KEY:-wbeam}"
PROJECT_NAME="${PROJECT_NAME:-WBeam}"
SONAR_BRANCH="${SONAR_BRANCH:-0.1.1/base}"
PRE_SCAN_CMD="${PRE_SCAN_CMD:-}"

if [ -z "${SONAR_TOKEN:-}" ]; then
  echo "SONAR_TOKEN is required"
  exit 1
fi

mkdir -p "${SONAR_USER_HOME}/cache"

if [ -d "${REPO_DIR}/.git" ]; then
  git config --global --add safe.directory "${REPO_DIR}" >/dev/null 2>&1 || true
  CURRENT_BRANCH="$(git -C "${REPO_DIR}" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
else
  CURRENT_BRANCH="unknown"
fi

if [ "${CURRENT_BRANCH}" != "${SONAR_BRANCH}" ]; then
  echo "Skipping Sonar scan: current branch '${CURRENT_BRANCH}' != target '${SONAR_BRANCH}'"
  exit 0
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
  -Dsonar.branch.name="${SONAR_BRANCH}" \
  -Dsonar.qualitygate.wait=false
