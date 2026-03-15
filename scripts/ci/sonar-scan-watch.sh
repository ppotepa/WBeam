#!/usr/bin/env sh
set -eu

REPO_DIR="${REPO_DIR:-/workspace}"
SONAR_HOST_URL="${SONAR_HOST_URL:-http://sonarqube:9000}"
SONAR_USER_HOME="${SONAR_USER_HOME:-${REPO_DIR}/.sonar}"
SCAN_INTERVAL_SECONDS="${SCAN_INTERVAL_SECONDS:-300}"
PROJECT_KEY="${PROJECT_KEY:-wbeam}"
PROJECT_NAME="${PROJECT_NAME:-WBeam}"
PRE_SCAN_CMD="${PRE_SCAN_CMD:-}"

if [ -z "${SONAR_TOKEN:-}" ]; then
  echo "SONAR_TOKEN is required"
  exit 1
fi

mkdir -p "${SONAR_USER_HOME}/cache"

echo "Starting continuous Sonar scan loop for ${PROJECT_NAME}"
while true; do
  date
  if [ -n "${PRE_SCAN_CMD}" ]; then
    sh -lc "cd '${REPO_DIR}' && ${PRE_SCAN_CMD}" || true
  fi
  sonar-scanner \
    -Dsonar.projectBaseDir="${REPO_DIR}" \
    -Dsonar.userHome="${SONAR_USER_HOME}" \
    -Dsonar.host.url="${SONAR_HOST_URL}" \
    -Dsonar.token="${SONAR_TOKEN}" \
    -Dsonar.projectKey="${PROJECT_KEY}" \
    -Dsonar.projectName="${PROJECT_NAME}" \
    -Dsonar.qualitygate.wait=false
  echo "Scan complete. Sleeping ${SCAN_INTERVAL_SECONDS}s"
  sleep "${SCAN_INTERVAL_SECONDS}"
done
