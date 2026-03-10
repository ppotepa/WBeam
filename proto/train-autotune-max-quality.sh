#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
PYTHON_BIN="${PYTHON_BIN:-python3}"

if ! command -v "${PYTHON_BIN}" >/dev/null 2>&1; then
  echo "[autotune-max] python3 not found: ${PYTHON_BIN}" >&2
  exit 1
fi

TARGET_FPS="${TARGET_FPS:-60}"
STAMP="$(date -u +%Y%m%d-%H%M%S)"

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
"${PYTHON_BIN}" "${ROOT_DIR}/autotune.py" \
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
  --best-config-out "config/autotune-best-stage1-${STAMP}.json" \
  --results "autotune-results-stage1-${STAMP}.json"

echo "[autotune-max] stage2: deep refinement"
"${PYTHON_BIN}" "${ROOT_DIR}/autotune.py" \
  --base-config "config/autotune-best-stage1-${STAMP}.json" \
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
  --best-config-out config/autotune-best.json \
  --profiles-out config/profiles.json \
  --results "autotune-results-stage2-${STAMP}.json"

echo "[autotune-max] done"
echo "[autotune-max] best config: ${ROOT_DIR}/config/autotune-best.json"
echo "[autotune-max] profiles:    ${ROOT_DIR}/config/profiles.json"
echo "[autotune-max] reports:     ${ROOT_DIR}/autotune-results-stage1-${STAMP}.json , ${ROOT_DIR}/autotune-results-stage2-${STAMP}.json"
