#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
SERIAL="${WBEAM_ANDROID_SERIAL:-${1:-}}"

if [[ -z "${SERIAL}" ]]; then
  echo "[train-max] missing serial: set WBEAM_ANDROID_SERIAL or pass as first argument" >&2
  exit 2
fi

PROFILE_NAME="${PROFILE_NAME:-baseline}"
TRIALS="${TRIALS:-48}"
GENERATIONS="${GENERATIONS:-4}"
POPULATION="${POPULATION:-12}"
ELITE_COUNT="${ELITE_COUNT:-4}"
MUTATION_RATE="${MUTATION_RATE:-0.34}"
CROSSOVER_RATE="${CROSSOVER_RATE:-0.55}"
BITRATE_MIN_KBPS="${BITRATE_MIN_KBPS:-10000}"
BITRATE_MAX_KBPS="${BITRATE_MAX_KBPS:-200000}"
WARMUP_SEC="${WARMUP_SEC:-4}"
SAMPLE_SEC="${SAMPLE_SEC:-12}"
POLL_SEC="${POLL_SEC:-0.8}"

echo "[train-max] running trainer_v2 on serial=${SERIAL} profile=${PROFILE_NAME}"

exec "${ROOT_DIR}/wbeam" train wizard \
  --serial "${SERIAL}" \
  --profile-name "${PROFILE_NAME}" \
  --mode quality \
  --trials "${TRIALS}" \
  --generations "${GENERATIONS}" \
  --population "${POPULATION}" \
  --elite-count "${ELITE_COUNT}" \
  --mutation-rate "${MUTATION_RATE}" \
  --crossover-rate "${CROSSOVER_RATE}" \
  --encoder-mode multi \
  --encoders h265,h264 \
  --bitrate-min-kbps "${BITRATE_MIN_KBPS}" \
  --bitrate-max-kbps "${BITRATE_MAX_KBPS}" \
  --warmup-sec "${WARMUP_SEC}" \
  --sample-sec "${SAMPLE_SEC}" \
  --poll-sec "${POLL_SEC}" \
  --non-interactive \
  --apply-best \
  --export-best
