#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
ENGINE_SCRIPT="${SCRIPT_DIR}/legacy_engine.py"
TRAINING_DIR="${ROOT_DIR}/config/training"
REPORT_DIR="${ROOT_DIR}/logs/train"
PYTHON_BIN="${PYTHON_BIN:-python3}"

if ! command -v "${PYTHON_BIN}" >/dev/null 2>&1; then
  echo "[autotune-max] python3 not found: ${PYTHON_BIN}" >&2
  exit 1
fi

TARGET_FPS="${TARGET_FPS:-60}"
STAMP="$(date -u +%Y%m%d-%H%M%S)"
mkdir -p "${TRAINING_DIR}" "${REPORT_DIR}"

STAGE1_BEST="${TRAINING_DIR}/autotune-best-stage1-${STAMP}.json"
STAGE2_BEST="${TRAINING_DIR}/autotune-best.json"
PROFILES_OUT="${TRAINING_DIR}/profiles.json"
RESULT_STAGE1="${REPORT_DIR}/autotune-results-stage1-${STAMP}.json"
RESULT_STAGE2="${REPORT_DIR}/autotune-results-stage2-${STAMP}.json"

# 200 Mbps ceiling (200000 kbps) with dense quality ladder.
BITRATE_VALUES="${BITRATE_VALUES:-12000,18000,25000,35000,50000,70000,90000,120000,150000,200000}"
# Candidate FPS set used by mutation (even when TARGET_FPS is pinned, this is recorded in report metadata).
FPS_VALUES="${FPS_VALUES:-45,60,72,90,120}"

STAGE1_GENERATIONS="${STAGE1_GENERATIONS:-4}"
STAGE1_POPULATION="${STAGE1_POPULATION:-10}"
STAGE1_ELITE="${STAGE1_ELITE:-3}"
STAGE1_MUTATION="${STAGE1_MUTATION:-0.40}"
STAGE1_WARMUP="${STAGE1_WARMUP:-10}"
STAGE1_SAMPLE="${STAGE1_SAMPLE:-26}"

STAGE2_GENERATIONS="${STAGE2_GENERATIONS:-10}"
STAGE2_POPULATION="${STAGE2_POPULATION:-14}"
STAGE2_ELITE="${STAGE2_ELITE:-4}"
STAGE2_MUTATION="${STAGE2_MUTATION:-0.34}"
STAGE2_WARMUP="${STAGE2_WARMUP:-12}"
STAGE2_SAMPLE="${STAGE2_SAMPLE:-40}"

if [[ "${TARGET_FPS}" -ge 90 ]]; then
  GATE_MIN_SENDER="${GATE_MIN_SENDER:-65}"
  GATE_MIN_PIPE="${GATE_MIN_PIPE:-75}"
else
  GATE_MIN_SENDER="${GATE_MIN_SENDER:-48}"
  GATE_MIN_PIPE="${GATE_MIN_PIPE:-55}"
fi
GATE_MAX_TIMEOUT="${GATE_MAX_TIMEOUT:-20}"

echo "[autotune-max] stage1: broad search"
"${PYTHON_BIN}" "${ENGINE_SCRIPT}" \
  --base-config config/proto.json \
  --fps "${TARGET_FPS}" \
  --fps-values "${FPS_VALUES}" \
  --bitrate-values "${BITRATE_VALUES}" \
  --generations "${STAGE1_GENERATIONS}" \
  --population "${STAGE1_POPULATION}" \
  --elite-count "${STAGE1_ELITE}" \
  --mutation-rate "${STAGE1_MUTATION}" \
  --warmup-secs "${STAGE1_WARMUP}" \
  --sample-secs "${STAGE1_SAMPLE}" \
  --startup-timeout-secs 240 \
  --min-samples 14 \
  --gate-min-sender-p50 "${GATE_MIN_SENDER}" \
  --gate-min-pipe-p50 "${GATE_MIN_PIPE}" \
  --gate-max-timeout-mean "${GATE_MAX_TIMEOUT}" \
  --require-portal-metrics \
  --reuse-device \
  --single-portal-consent \
  --overlay \
  --profile-name baseline \
  --no-export-profiles \
  --best-config-out "${STAGE1_BEST}" \
  --results "${RESULT_STAGE1}"

echo "[autotune-max] stage2: deep refinement"
"${PYTHON_BIN}" "${ENGINE_SCRIPT}" \
  --base-config "${STAGE1_BEST}" \
  --fps "${TARGET_FPS}" \
  --fps-values "${FPS_VALUES}" \
  --bitrate-values "${BITRATE_VALUES}" \
  --generations "${STAGE2_GENERATIONS}" \
  --population "${STAGE2_POPULATION}" \
  --elite-count "${STAGE2_ELITE}" \
  --mutation-rate "${STAGE2_MUTATION}" \
  --warmup-secs "${STAGE2_WARMUP}" \
  --sample-secs "${STAGE2_SAMPLE}" \
  --startup-timeout-secs 240 \
  --min-samples 18 \
  --gate-min-sender-p50 "${GATE_MIN_SENDER}" \
  --gate-min-pipe-p50 "${GATE_MIN_PIPE}" \
  --gate-max-timeout-mean "${GATE_MAX_TIMEOUT}" \
  --require-portal-metrics \
  --reuse-device \
  --single-portal-consent \
  --overlay \
  --fast-mode \
  --profile-name baseline \
  --export-profiles \
  --best-config-out "${STAGE2_BEST}" \
  --profiles-out "${PROFILES_OUT}" \
  --results "${RESULT_STAGE2}"

echo "[autotune-max] done"
echo "[autotune-max] best config: ${STAGE2_BEST}"
echo "[autotune-max] profiles:    ${PROFILES_OUT}"
echo "[autotune-max] reports:     ${RESULT_STAGE1} , ${RESULT_STAGE2}"
