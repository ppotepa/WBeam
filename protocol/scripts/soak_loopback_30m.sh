#!/usr/bin/env bash
# soak_loopback_30m.sh – 30-minute WBTP/1 loopback soak gate.
#
# Starts wbtp-receiver-null and wbtp-sender on loopback, runs for SOAK_DURATION
# seconds, then evaluates the metrics log and prints PASS or FAIL.
#
# Pass criteria (all must hold):
#   1. Zero CRC errors.
#   2. Zero magic sync errors.
#   3. recv_fps >= SOAK_FPS * MIN_FPS_RATIO for >= (1 - MAX_LOW_FPS_FRAC) of windows.
#   4. Minimum SOAK_DURATION * 0.9 metric windows emitted (guards silent crash).
#
# Environment overrides:
#   SOAK_DURATION        seconds (default 1800 = 30 min)
#   SOAK_FPS             target fps (default 60)
#   SOAK_PAYLOAD_BYTES   per-frame payload bytes (default 50000 ~= 20 Mbps at 60fps)
#   SOAK_QUEUE           sender queue depth (default 4)
#   SOAK_PORT            TCP port (default 15000 – avoids collision with real port 5000)
#   SOAK_CHECKSUM        1=enable CRC32 (default 1)
#   MIN_FPS_RATIO        fraction of target considered ok (default 0.90)
#   MAX_LOW_FPS_FRAC     fraction of windows allowed below MIN_FPS_RATIO (default 0.03)

set -euo pipefail
LC_ALL=C
export LC_ALL

# ── parameters ────────────────────────────────────────────────────────────────
SOAK_DURATION=${SOAK_DURATION:-1800}
SOAK_FPS=${SOAK_FPS:-60}
SOAK_PAYLOAD_BYTES=${SOAK_PAYLOAD_BYTES:-50000}
SOAK_QUEUE=${SOAK_QUEUE:-4}
SOAK_PORT=${SOAK_PORT:-15000}
SOAK_CHECKSUM=${SOAK_CHECKSUM:-1}
HEARTBEAT_INTERVAL=${HEARTBEAT_INTERVAL:-60}
MIN_FPS_RATIO=${MIN_FPS_RATIO:-0.90}
MAX_LOW_FPS_FRAC=${MAX_LOW_FPS_FRAC:-0.03}

# ── paths ─────────────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKSPACE_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
RUST_DIR="$WORKSPACE_ROOT/rust"
LOG_DIR="$WORKSPACE_ROOT/logs"
mkdir -p "$LOG_DIR"

TS=$(date +%Y%m%d-%H%M%S)
LOG="$LOG_DIR/soak_loopback_${TS}.log"
RECV_LOG="$LOG_DIR/recv_${TS}.log"
SEND_LOG="$LOG_DIR/send_${TS}.log"

# ── build ─────────────────────────────────────────────────────────────────────
echo "[soak] building protocol workspace (release)..."
cargo build --release --workspace --manifest-path "$RUST_DIR/Cargo.toml" 2>&1 | tail -3
RECV_BIN="$RUST_DIR/target/release/wbtp-receiver-null"
SEND_BIN="$RUST_DIR/target/release/wbtp-sender"

if [[ ! -x "$RECV_BIN" ]]; then
    echo "[soak] ERROR: $RECV_BIN not found after build"
    exit 1
fi
if [[ ! -x "$SEND_BIN" ]]; then
    echo "[soak] ERROR: $SEND_BIN not found after build"
    exit 1
fi

# ── cleanup helper ────────────────────────────────────────────────────────────
RECV_PID=0
SEND_PID=0
cleanup() {
    [[ $RECV_PID -ne 0 ]] && kill "$RECV_PID" 2>/dev/null || true
    [[ $SEND_PID -ne 0 ]] && kill "$SEND_PID" 2>/dev/null || true
    wait "$RECV_PID" "$SEND_PID" 2>/dev/null || true
}
trap cleanup EXIT

# ── start receiver ────────────────────────────────────────────────────────────
CHECKSUM_ARG=""
[[ "$SOAK_CHECKSUM" == "0" ]] && CHECKSUM_ARG="--no-verify-crc"

"$RECV_BIN" \
    --bind "127.0.0.1:${SOAK_PORT}" \
    --late-threshold-ms 200 \
    $CHECKSUM_ARG \
    > "$RECV_LOG" 2>&1 &
RECV_PID=$!

sleep 0.3
if ! kill -0 "$RECV_PID" 2>/dev/null; then
    echo "[soak] ERROR: receiver failed to start – check $RECV_LOG"
    cat "$RECV_LOG"
    exit 1
fi
echo "[soak] receiver started (pid=$RECV_PID port=$SOAK_PORT)"

# ── start sender ──────────────────────────────────────────────────────────────
SEND_CHECKSUM_ARG=""
[[ "$SOAK_CHECKSUM" == "0" ]] && SEND_CHECKSUM_ARG="--no-checksum"

"$SEND_BIN" \
    --addr "127.0.0.1:${SOAK_PORT}" \
    --fps "$SOAK_FPS" \
    --payload-bytes "$SOAK_PAYLOAD_BYTES" \
    --queue "$SOAK_QUEUE" \
    $SEND_CHECKSUM_ARG \
    > "$SEND_LOG" 2>&1 &
SEND_PID=$!

sleep 0.5
if ! kill -0 "$SEND_PID" 2>/dev/null; then
    echo "[soak] ERROR: sender failed to start – check $SEND_LOG"
    cat "$SEND_LOG"
    exit 1
fi
echo "[soak] sender started   (pid=$SEND_PID fps=$SOAK_FPS payload=${SOAK_PAYLOAD_BYTES}B)"
echo "[soak] soak duration    = ${SOAK_DURATION}s"
echo "[soak] log files        = $RECV_LOG / $SEND_LOG"
echo ""

# ── wait and heartbeat ────────────────────────────────────────────────────────
HEARTBEAT_INTERVAL=60
ELAPSED=0
while [[ $ELAPSED -lt $SOAK_DURATION ]]; do
    STEP=$((HEARTBEAT_INTERVAL < (SOAK_DURATION - ELAPSED) ? HEARTBEAT_INTERVAL : (SOAK_DURATION - ELAPSED)))
    sleep "$STEP"
    ELAPSED=$((ELAPSED + STEP))
    # grab last metrics line for live feedback
    LAST=$(grep ': metrics ' "$RECV_LOG" 2>/dev/null | tail -1 || true)
    echo "[soak] elapsed=${ELAPSED}s/${SOAK_DURATION}s | $LAST"
done

echo "[soak] soak window complete – stopping processes..."
kill "$SEND_PID" 2>/dev/null || true
sleep 1
kill "$RECV_PID" 2>/dev/null || true
sleep 0.3

# ── evaluate results ──────────────────────────────────────────────────────────
METRICS_LINES=$(grep ': metrics ' "$RECV_LOG" | wc -l)
echo "[soak] total metric windows = $METRICS_LINES"

MIN_WINDOWS=$(awk "BEGIN { printf \"%d\", $SOAK_DURATION * 0.9 }")
if [[ $METRICS_LINES -lt $MIN_WINDOWS ]]; then
    echo "[soak:FAIL] not enough metric windows: got $METRICS_LINES, need >= $MIN_WINDOWS"
    echo "[soak:FAIL] $TS | ${SOAK_DURATION}s | windows=$METRICS_LINES" >> "$LOG"
    exit 1
fi

# Sum cumulative errors
TOTAL_CRC=$(grep ': metrics ' "$RECV_LOG" | grep -oP 'crc_errors=\K[0-9]+' | awk '{s+=$1}END{print s+0}')
TOTAL_MAGIC=$(grep ': metrics ' "$RECV_LOG" | grep -oP 'magic_errors=\K[0-9]+' | awk '{s+=$1}END{print s+0}')

if [[ "$TOTAL_CRC" -ne 0 ]]; then
    echo "[soak:FAIL] CRC errors = $TOTAL_CRC (must be 0)"
    echo "[soak:FAIL] $TS | crc_errors=$TOTAL_CRC" >> "$LOG"
    exit 1
fi
if [[ "$TOTAL_MAGIC" -ne 0 ]]; then
    echo "[soak:FAIL] magic sync errors = $TOTAL_MAGIC (must be 0)"
    echo "[soak:FAIL] $TS | magic_errors=$TOTAL_MAGIC" >> "$LOG"
    exit 1
fi

# Count low-fps windows
MIN_FPS=$(awk "BEGIN { printf \"%.2f\", $SOAK_FPS * $MIN_FPS_RATIO }")
LOW_FPS_WINDOWS=$(grep ': metrics ' "$RECV_LOG" | grep -oP 'recv_fps="\K[0-9.]+' | awk -v m="$MIN_FPS" '$1+0 < m+0 {c++} END {print c+0}')
MAX_ALLOWED=$(awk "BEGIN { printf \"%d\", $METRICS_LINES * $MAX_LOW_FPS_FRAC }")

echo "[soak] min_fps_threshold=$MIN_FPS | low_fps_windows=$LOW_FPS_WINDOWS | max_allowed=$MAX_ALLOWED"

if [[ $LOW_FPS_WINDOWS -gt $MAX_ALLOWED ]]; then
    echo "[soak:FAIL] too many low-fps windows: $LOW_FPS_WINDOWS > $MAX_ALLOWED (${MAX_LOW_FPS_FRAC} of $METRICS_LINES)"
    echo "[soak:FAIL] $TS | low_fps_windows=$LOW_FPS_WINDOWS/$METRICS_LINES" >> "$LOG"
    exit 1
fi

TOTAL_LATE=$(grep ': metrics ' "$RECV_LOG" | grep -oP 'late_frames=\K[0-9]+' | awk '{s+=$1}END{print s+0}')

echo "[soak:PASS] $TS | ${SOAK_DURATION}s | fps=$SOAK_FPS payload=${SOAK_PAYLOAD_BYTES}B | windows=$METRICS_LINES | crc_errors=$TOTAL_CRC | magic_errors=$TOTAL_MAGIC | low_fps_windows=$LOW_FPS_WINDOWS/$METRICS_LINES | late_frames=$TOTAL_LATE"
echo "[soak:loopback $TS] PASS | ${SOAK_DURATION}s | fps_low_windows=$LOW_FPS_WINDOWS/$METRICS_LINES | crc_errors=$TOTAL_CRC | late_frames=$TOTAL_LATE | log=$RECV_LOG" >> "$LOG"
