#!/usr/bin/env bash
set -euo pipefail

# Simple helper to build the prototype APK, install it on a device, and start the host image server.
# Environment:
#   HOST_IP         Host IP the device should hit (default 192.168.42.170 — USB tether)
#   SERIAL          Android serial (optional, defaults to adb default device)
#   GRADLEW         Path to gradlew (defaults to ./gradlew inside proto/front)
#   CARGO_BIN       Host server binary (optional, will `cargo run --release` if not provided)

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"

usage() {
  cat <<'USAGE'
Usage: ./run.sh [fast|balanced|quality]
  fast      Low latency (default)
  balanced  Mid quality/latency
  quality   Highest quality, heaviest
Environment variables still work (e.g., PROTO_PRESET), but the positional
argument takes precedence.
USAGE
}

# Kill any leftover host process from a previous run (cargo run spawns a grandchild
# that survives the cargo PID being killed by the old cleanup trap).
pkill -f proto-host-image >/dev/null 2>&1 || true

HOST_IP="${HOST_IP:-}"
PROTO_PRESET="${PROTO_PRESET:-fast}"
if [[ $# -gt 0 ]]; then
  case "$1" in
    fast|balanced|quality)
      PROTO_PRESET="$1"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf '[proto] %s\n' "unknown preset '$1' (expected: fast|balanced|quality)"
      usage
      exit 1
      ;;
  esac
fi

PROTO_CAPTURE_SIZE="${PROTO_CAPTURE_SIZE:-}"
PROTO_CAPTURE_BITRATE_KBPS="${PROTO_CAPTURE_BITRATE_KBPS:-}"
PROTO_CAPTURE_FPS="${PROTO_CAPTURE_FPS:-}"
PROTO_MJPEG_FPS="${PROTO_MJPEG_FPS:-}"
PROTO_ADB_PUSH_FPS="${PROTO_ADB_PUSH_FPS:-}"
PROTO_SKIP_SIG_CHECK="${PROTO_SKIP_SIG_CHECK:-1}"
PROTO_DEBUG_FRAMES="${PROTO_DEBUG_FRAMES:-1}"
PROTO_DEBUG_FRAMES_DIR="${PROTO_DEBUG_FRAMES_DIR:-$ROOT_DIR/debugframes}"
PROTO_DEBUG_FRAMES_FPS="${PROTO_DEBUG_FRAMES_FPS:-2}"
PROTO_DEBUG_FRAMES_SLOTS="${PROTO_DEBUG_FRAMES_SLOTS:-180}"
PROTO_ADB_PUSH="${PROTO_ADB_PUSH:-1}"
PROTO_ADB_PUSH_ADDR="${PROTO_ADB_PUSH_ADDR:-127.0.0.1:5006}"
PROTO_PORTAL="${PROTO_PORTAL:-1}"
PROTO_PORTAL_JPEG_SOURCE="${PROTO_PORTAL_JPEG_SOURCE:-debug}"
PROTO_CURSOR_MODE="${PROTO_CURSOR_MODE:-embedded}"
PROTO_PORTAL_ONLY="${PROTO_PORTAL_ONLY:-0}"
PROTO_H264="${PROTO_H264:-0}"
PROTO_WAIT_FOR_FIRST_FRAME_SECS="${PROTO_WAIT_FOR_FIRST_FRAME_SECS:-1}"
PROTO_WAIT_FOR_FIRST_FRAME_REQUIRED="${PROTO_WAIT_FOR_FIRST_FRAME_REQUIRED:-1}"
PROTO_ANDROID_BUILD_TYPE="${PROTO_ANDROID_BUILD_TYPE:-debug}"
PROTO_REQUIRE_TURBO="${PROTO_REQUIRE_TURBO:-1}"
PROTO_FORCE_JAVA_FALLBACK="${PROTO_FORCE_JAVA_FALLBACK:-0}"

log() { printf '[proto] %s\n' "$*"; }

refresh_serial_flag() {
  SERIAL_FLAG=()
  [[ -n "${SERIAL:-}" ]] && SERIAL_FLAG=(-s "$SERIAL")
}

select_serial_noninteractive() {
  local serial
  local model
  local preferred=""
  local first_physical=""
  local first_any=""

  adb start-server >/dev/null 2>&1 || true

  while IFS= read -r serial; do
    [[ -z "$serial" ]] && continue

    if [[ -z "$first_any" ]]; then
      first_any="$serial"
    fi

    model=""
    model="$(adb -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)"

    if [[ "$serial" != emulator-* && -z "$first_physical" ]]; then
      first_physical="$serial"
    fi

    if [[ "$model" == *S6000* || "$model" == *Lenovo* ]]; then
      preferred="$serial"
      break
    fi
  done < <(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1}')

  if [[ -n "$preferred" ]]; then
    printf '%s\n' "$preferred"
    return 0
  fi
  if [[ -n "$first_physical" ]]; then
    printf '%s\n' "$first_physical"
    return 0
  fi
  if [[ -n "$first_any" ]]; then
    printf '%s\n' "$first_any"
    return 0
  fi

  return 1
}

if [[ -z "${SERIAL:-}" ]]; then
  if SERIAL="$(select_serial_noninteractive)"; then
    export SERIAL
    log "SERIAL not set; selected device $SERIAL"
  fi
fi

refresh_serial_flag

case "$PROTO_PRESET" in
  fast)
    : "${PROTO_CAPTURE_SIZE:=960x540}"
    : "${PROTO_CAPTURE_BITRATE_KBPS:=12000}"
    : "${PROTO_CAPTURE_FPS:=60}"
    : "${PROTO_MJPEG_FPS:=60}"
    ;;
  balanced)
    : "${PROTO_CAPTURE_SIZE:=1280x720}"
    : "${PROTO_CAPTURE_BITRATE_KBPS:=16000}"
    : "${PROTO_CAPTURE_FPS:=30}"
    : "${PROTO_MJPEG_FPS:=30}"
    ;;
  quality)
    : "${PROTO_CAPTURE_SIZE:=1920x1080}"
    : "${PROTO_CAPTURE_BITRATE_KBPS:=26000}"
    : "${PROTO_CAPTURE_FPS:=50}"
    : "${PROTO_MJPEG_FPS:=50}"
    ;;
  *)
    printf '[proto] %s\n' "unknown PROTO_PRESET=$PROTO_PRESET (expected: fast|balanced|quality)"
    exit 1
    ;;
esac

  : "${PROTO_ADB_PUSH_FPS:=$PROTO_CAPTURE_FPS}"

# ── Auto-detect native device resolution ─────────────────────────────────────
# Query the connected Android device for its physical screen size and override
# PROTO_CAPTURE_SIZE so the host encodes at exactly the tablet's native pixels.
# This reduces JPEG payload size and eliminates pointless upscale/downscale.
# Set PROTO_CAPTURE_SIZE_OVERRIDE=1 to keep the preset value instead.
if [[ "${PROTO_CAPTURE_SIZE_OVERRIDE:-0}" != "1" ]]; then
  _serial_flag=""
  [[ -n "${SERIAL:-}" ]] && _serial_flag="-s ${SERIAL}"

  # Method 1: wm size (Android >= 4.3). Output: "Physical size: 1280x800"
  _dev_size="$(adb ${_serial_flag} shell wm size 2>/dev/null \
    | grep -oP '(?<=: )\d+x\d+' | tail -1 || true)"

  # Method 2: dumpsys window (API 17+). Output contains: "init=1280x800 160dpi ..."
  if [[ -z "$_dev_size" ]]; then
    _dev_size="$(adb ${_serial_flag} shell dumpsys window 2>/dev/null \
      | grep -oP 'init=\K\d+x\d+' | head -1 || true)"
  fi

  if [[ -n "$_dev_size" ]]; then
    log "device native resolution: $_dev_size (overrides preset ${PROTO_CAPTURE_SIZE})"
    PROTO_CAPTURE_SIZE="$_dev_size"
  else
    log "WARNING: could not detect device resolution; keeping preset PROTO_CAPTURE_SIZE=${PROTO_CAPTURE_SIZE}"
  fi
else
  log "PROTO_CAPTURE_SIZE_OVERRIDE=1 — keeping PROTO_CAPTURE_SIZE=${PROTO_CAPTURE_SIZE}"
fi

# ── Set virtual Wayland output to device resolution ───────────────────────────
# The KDE XDG portal creates a virtual output (e.g. Virtual-virtual-xdp-kde-*)
# at 1920x1080 by default. If the portal captures from this output at full HD
# and we only resize during H264 encode, we waste CPU on every frame.
# Setting the virtual output mode to the device native resolution means the
# portal captures at exactly the right size — zero wasted pixels.
# Gated on PROTO_SET_VIRTUAL_RES (default 1); set to 0 to skip.
if [[ "${PROTO_SET_VIRTUAL_RES:-1}" == "1" ]] && command -v kscreen-doctor &>/dev/null; then
  _virt_output="$(kscreen-doctor -o 2>/dev/null \
    | grep -oP 'Output: \d+ \K[^\s]+' \
    | grep -i 'virtual\|xdp\|xdg' \
    | head -1 || true)"
  if [[ -n "$_virt_output" && -n "$_dev_size" ]]; then
    log "setting virtual output '$_virt_output' to ${_dev_size}@60"
    if kscreen-doctor "output.${_virt_output}.mode.${_dev_size}@60" 2>/dev/null; then
      log "virtual output mode set OK — portal will capture at $_dev_size"
      sleep 0.5   # let KMS settle before portal starts
    else
      log "WARNING: kscreen-doctor mode change failed (mode may not exist); portal will still encode at $_dev_size via --size flag"
    fi
  else
    log "no virtual output found or device size unknown — skipping virtual monitor resize"
  fi
fi
# ─────────────────────────────────────────────────────────────────────────────

FRONT_DIR="$ROOT_DIR/front"
HOST_DIR="$ROOT_DIR/host"
GRADLEW="${GRADLEW:-$FRONT_DIR/gradlew}"
APK_PATH=""
CARGO_BIN="${CARGO_BIN:-}" # if set, we exec that instead of cargo run
ANDROID_LOG_FILE="${ANDROID_LOG_FILE:-/tmp/proto-android.log}"
LOGCAT_PID=""

case "$PROTO_ANDROID_BUILD_TYPE" in
  debug|release) ;;
  *)
    log "unknown PROTO_ANDROID_BUILD_TYPE=$PROTO_ANDROID_BUILD_TYPE (expected: debug|release)"
    exit 1
    ;;
esac

if ! [[ "$PROTO_WAIT_FOR_FIRST_FRAME_SECS" =~ ^[0-9]+$ ]]; then
  log "invalid PROTO_WAIT_FOR_FIRST_FRAME_SECS=$PROTO_WAIT_FOR_FIRST_FRAME_SECS (expected integer seconds)"
  exit 1
fi

resolve_apk_path() {
  local build_type="$1"
  if [[ "$build_type" == "release" ]]; then
    if [[ -f "$FRONT_DIR/app/build/outputs/apk/release/app-release.apk" ]]; then
      printf '%s\n' "$FRONT_DIR/app/build/outputs/apk/release/app-release.apk"
      return 0
    fi
    if [[ -f "$FRONT_DIR/app/build/outputs/apk/release/app-release-unsigned.apk" ]]; then
      printf '%s\n' "$FRONT_DIR/app/build/outputs/apk/release/app-release-unsigned.apk"
      return 0
    fi
    return 1
  fi

  if [[ -f "$FRONT_DIR/app/build/outputs/apk/debug/app-debug.apk" ]]; then
    printf '%s\n' "$FRONT_DIR/app/build/outputs/apk/debug/app-debug.apk"
    return 0
  fi
  return 1
}

find_apksigner() {
  if command -v apksigner >/dev/null 2>&1; then
    command -v apksigner
    return 0
  fi

  local sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  local latest
  if [[ -n "$sdk_root" && -d "$sdk_root/build-tools" ]]; then
    latest="$(ls -1 "$sdk_root/build-tools" 2>/dev/null | sort -V | tail -n 1 || true)"
    if [[ -n "$latest" && -x "$sdk_root/build-tools/$latest/apksigner" ]]; then
      printf '%s\n' "$sdk_root/build-tools/$latest/apksigner"
      return 0
    fi
  fi

  return 1
}

ensure_debug_keystore() {
  local keystore="$HOME/.android/debug.keystore"
  if [[ -f "$keystore" ]]; then
    printf '%s\n' "$keystore"
    return 0
  fi

  mkdir -p "$HOME/.android"
  if ! command -v keytool >/dev/null 2>&1; then
    return 1
  fi

  keytool -genkeypair \
    -keystore "$keystore" \
    -storepass android \
    -keypass android \
    -alias androiddebugkey \
    -dname "CN=Android Debug,O=Android,C=US" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 >/dev/null 2>&1

  if [[ -f "$keystore" ]]; then
    printf '%s\n' "$keystore"
    return 0
  fi

  return 1
}

sign_release_apk_if_needed() {
  local apk_path="$1"
  if [[ "$apk_path" != *"-unsigned.apk" ]]; then
    printf '%s\n' "$apk_path"
    return 0
  fi

  local apksigner
  if ! apksigner="$(find_apksigner)"; then
    log "release APK is unsigned and apksigner was not found"
    return 1
  fi

  local keystore
  if ! keystore="$(ensure_debug_keystore)"; then
    log "release APK is unsigned and debug keystore could not be prepared"
    return 1
  fi

  local signed_apk="${apk_path%-unsigned.apk}-signed.apk"
  rm -f "$signed_apk"

  "$apksigner" sign \
    --ks "$keystore" \
    --ks-pass pass:android \
    --key-pass pass:android \
    --ks-key-alias androiddebugkey \
    --out "$signed_apk" \
    "$apk_path" >/dev/null

  if ! "$apksigner" verify "$signed_apk" >/dev/null 2>&1; then
    log "signed release APK verification failed"
    return 1
  fi

  printf '%s\n' "$signed_apk"
}

detect_host_ip() {
  local candidate
  candidate="$(ip -4 -o addr show scope global | awk '$4 ~ /^192\\.168\\.42\\./ {split($4,a,"/"); print a[1]; exit}')"
  if [[ -n "$candidate" ]]; then
    printf '%s\n' "$candidate"
    return 0
  fi

  candidate="$(ip -4 -o addr show scope global | awk '{split($4,a,"/"); print a[1]; exit}')"
  if [[ -n "$candidate" ]]; then
    printf '%s\n' "$candidate"
    return 0
  fi

  return 1
}

if [[ -z "$HOST_IP" ]]; then
  if HOST_IP="$(detect_host_ip)"; then
    log "HOST_IP not set; detected $HOST_IP"
  else
    HOST_IP="192.168.42.170"
    log "HOST_IP not set; fallback to $HOST_IP"
  fi
fi

has_adb_device() {
  local count
  count="$(adb devices | awk 'NR>1 && $2=="device" {c++} END {print c+0}')"
  [[ "$count" -gt 0 ]]
}

app_is_running() {
  local line
  line="$(adb "${SERIAL_FLAG[@]}" shell "pidof com.proto.demo 2>/dev/null || ps | grep com.proto.demo | grep -v grep" 2>/dev/null | tr -d '\r' | head -n 1 || true)"
  [[ -n "$line" ]]
}

apk_has_entry() {
  local apk_path="$1"
  local entry="$2"
  python3 - "$apk_path" "$entry" <<'PY'
import sys, zipfile
apk, entry = sys.argv[1], sys.argv[2]
try:
    with zipfile.ZipFile(apk, 'r') as zf:
        names = set(zf.namelist())
        sys.exit(0 if entry in names else 1)
except Exception:
    sys.exit(2)
PY
}

detect_device_abis() {
  local abilist
  local abi

  abilist="$(adb "${SERIAL_FLAG[@]}" shell getprop ro.product.cpu.abilist 2>/dev/null | tr -d '\r' || true)"
  if [[ -n "$abilist" ]]; then
    printf '%s\n' "$abilist" | tr ',' '\n' | sed '/^$/d'
    return 0
  fi

  abi="$(adb "${SERIAL_FLAG[@]}" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r' || true)"
  if [[ -n "$abi" ]]; then
    printf '%s\n' "$abi"
  fi
}

validate_turbo_packaging() {
  local apk_path="$1"
  shift
  local abis=("$@")
  local missing=0

  [[ "${#abis[@]}" -eq 0 ]] && return 0

  for abi in "${abis[@]}"; do
    if ! apk_has_entry "$apk_path" "lib/$abi/libwbeam.so"; then
      log "turbo preflight: APK missing lib/$abi/libwbeam.so"
      missing=1
      continue
    fi

    if ! apk_has_entry "$apk_path" "lib/$abi/libturbojpeg.so"; then
      log "turbo preflight: APK missing lib/$abi/libturbojpeg.so"
      missing=1
    fi
  done

  if [[ "$missing" -ne 0 ]]; then
    log "turbo preflight failed. Build/provision turbo libs first: ./front/scripts/build_turbojpeg_android.sh"
    return 1
  fi

  return 0
}

detect_host_ip_for_device() {
  local device_route
  local device_src
  local device_prefix
  local candidate

  device_route="$(adb "${SERIAL_FLAG[@]}" shell ip route 2>/dev/null | tr -d '\r' | grep ' dev rndis0 ' | head -n 1 || true)"
  device_src="$(printf '%s\n' "$device_route" | sed -n 's/.* src \([0-9.]*\).*/\1/p')"
  if [[ -z "$device_src" ]]; then
    return 1
  fi

  device_prefix="$(printf '%s' "$device_src" | awk -F. '{print $1"."$2"."$3}')"
  candidate="$(ip -4 -o addr show scope global | awk -v pfx="$device_prefix" '{split($4,a,"/"); if (index(a[1], pfx ".") == 1) {print a[1]; exit}}')"

  if [[ -n "$candidate" ]]; then
    printf '%s\n' "$candidate"
    return 0
  fi

  return 1
}

if [[ -z "${JAVA_HOME:-}" && -d /usr/lib/jvm/java-17-openjdk-amd64 ]]; then
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
  export PATH="$JAVA_HOME/bin:$PATH"
  log "JAVA_HOME not set; using $JAVA_HOME"
fi

# Build APK (fallback to system gradle only if wrapper missing)
if [[ ! -x "$GRADLEW" ]]; then
  if command -v gradle >/dev/null 2>&1; then
    log "gradlew not found at $GRADLEW; using system gradle"
    GRADLEW="gradle"
  else
    log "gradlew not found at $GRADLEW and no system gradle in PATH; set GRADLEW or install gradle"
    exit 1
  fi
fi
if [[ "$PROTO_ANDROID_BUILD_TYPE" == "release" ]]; then
  log "building APK (release)…"
  ( cd "$FRONT_DIR" && "$GRADLEW" :app:assembleRelease )
else
  log "building APK (debug)…"
  ( cd "$FRONT_DIR" && "$GRADLEW" :app:assembleDebug )
fi

if ! APK_PATH="$(resolve_apk_path "$PROTO_ANDROID_BUILD_TYPE")"; then
  log "APK not found for build type=$PROTO_ANDROID_BUILD_TYPE"
  exit 1
fi

if [[ "$PROTO_ANDROID_BUILD_TYPE" == "release" ]]; then
  if ! APK_PATH="$(sign_release_apk_if_needed "$APK_PATH")"; then
    log "failed to prepare installable release APK"
    exit 1
  fi
fi

ADB_READY=0
APP_HOST_IP="$HOST_IP"
if has_adb_device; then
  ADB_READY=1
else
  log "no adb device detected; skipping APK install/launch (host-only run)"
  PROTO_ADB_PUSH=0
fi

# Install APK
if [[ "$ADB_READY" -eq 1 ]]; then
  if [[ "$PROTO_REQUIRE_TURBO" == "1" ]]; then
    mapfile -t DEVICE_ABIS < <(detect_device_abis)
    if [[ "${#DEVICE_ABIS[@]}" -eq 0 ]]; then
      DEVICE_ABIS=("armeabi-v7a" "arm64-v8a")
    fi
    validate_turbo_packaging "$APK_PATH" "${DEVICE_ABIS[@]}"
  fi

  if [[ ! -f "$APK_PATH" ]]; then
    log "APK not found at $APK_PATH"
    exit 1
  fi
  log "installing APK to device ${SERIAL:-<default>}…"
  INSTALL_OUT="$(adb "${SERIAL_FLAG[@]}" install -r "$APK_PATH" 2>&1)" || true
  if ! grep -q "Success" <<<"$INSTALL_OUT"; then
    log "adb install -r failed; trying uninstall/install fallback"
    printf '%s\n' "$INSTALL_OUT"
    adb "${SERIAL_FLAG[@]}" uninstall com.proto.demo >/dev/null 2>&1 || true
    INSTALL_OUT="$(adb "${SERIAL_FLAG[@]}" install "$APK_PATH" 2>&1)" || true
    printf '%s\n' "$INSTALL_OUT"
    if ! grep -q "Success" <<<"$INSTALL_OUT"; then
      log "APK install failed"
      exit 1
    fi
  fi

  if adb "${SERIAL_FLAG[@]}" reverse tcp:5005 tcp:5005 >/dev/null 2>&1; then
    APP_HOST_IP="127.0.0.1"
    log "adb reverse enabled: device tcp:5005 -> host tcp:5005"
  else
    if DEVICE_HOST_IP="$(detect_host_ip_for_device)"; then
      APP_HOST_IP="$DEVICE_HOST_IP"
      log "adb reverse unavailable; detected USB host IP=$APP_HOST_IP from device route"
    else
      APP_HOST_IP="$HOST_IP"
      log "adb reverse unavailable; using detected HOST_IP=$HOST_IP"
    fi
  fi

  # Emulator convenience: map host loopback to 10.0.2.2 for HTTP pull when reverse is absent
  if adb "${SERIAL_FLAG[@]}" shell getprop ro.product.model 2>/dev/null | grep -qi "sdk"; then
    if [[ "$APP_HOST_IP" != "127.0.0.1" ]]; then
      APP_HOST_IP="10.0.2.2"
      log "detected emulator; using host_ip=10.0.2.2 for HTTP pull"
    fi
  fi

  if [[ "$PROTO_ADB_PUSH" == "1" ]]; then
    adb "${SERIAL_FLAG[@]}" forward --remove tcp:5006 >/dev/null 2>&1 || true
    adb "${SERIAL_FLAG[@]}" forward tcp:5006 tcp:5006 >/dev/null
    log "adb forward enabled: host tcp:5006 -> device tcp:5006"
  fi
fi

# Clean any stale host process using port 5005.
if command -v lsof >/dev/null 2>&1; then
  EXISTING_PIDS="$( (lsof -t -iTCP:5005 -sTCP:LISTEN 2>/dev/null || true) | tr '\n' ' ' )"
  if [[ -n "${EXISTING_PIDS// }" ]]; then
    log "stopping stale host listener(s) on :5005: $EXISTING_PIDS"
    kill $EXISTING_PIDS >/dev/null 2>&1 || true
    sleep 1
  fi

  PORTAL_PIDS="$( (lsof -t -iTCP:5500 -sTCP:LISTEN 2>/dev/null || true) | tr '\n' ' ' )"
  if [[ -n "${PORTAL_PIDS// }" ]]; then
    log "stopping stale portal listener(s) on :5500: $PORTAL_PIDS"
    kill $PORTAL_PIDS >/dev/null 2>&1 || true
    sleep 1
  fi
fi

pkill -f stream_wayland_portal_h264.py >/dev/null 2>&1 || true

# Start host server first, then launch app so auto-start stream has endpoint ready.
log "starting host server on 0.0.0.0:5005 (desktop stream)…"
log "preset=$PROTO_PRESET size=$PROTO_CAPTURE_SIZE fps=$PROTO_CAPTURE_FPS bitrate=${PROTO_CAPTURE_BITRATE_KBPS}kbps mjpeg_fps=$PROTO_MJPEG_FPS adb_push_fps=$PROTO_ADB_PUSH_FPS adb_push=$PROTO_ADB_PUSH skip_sig_check=$PROTO_SKIP_SIG_CHECK debug_frames=$PROTO_DEBUG_FRAMES debug_frames_fps=$PROTO_DEBUG_FRAMES_FPS debug_frames_slots=$PROTO_DEBUG_FRAMES_SLOTS portal=$PROTO_PORTAL cursor=$PROTO_CURSOR_MODE jpeg_source=$PROTO_PORTAL_JPEG_SOURCE portal_only=$PROTO_PORTAL_ONLY h264=$PROTO_H264"

if [[ "$PROTO_DEBUG_FRAMES" == "1" ]]; then
  mkdir -p "$PROTO_DEBUG_FRAMES_DIR"
fi

cd "$HOST_DIR"
if [[ -n "$CARGO_BIN" ]]; then
  PROTO_FORCE_PORTAL=1 PROTO_SERIAL="${SERIAL:-}" PROTO_ADB_PUSH="$PROTO_ADB_PUSH" PROTO_ADB_PUSH_ADDR="$PROTO_ADB_PUSH_ADDR" PROTO_ADB_PUSH_FPS="$PROTO_ADB_PUSH_FPS" PROTO_SKIP_SIG_CHECK="$PROTO_SKIP_SIG_CHECK" PROTO_DEBUG_FRAMES="$PROTO_DEBUG_FRAMES" PROTO_DEBUG_FRAMES_DIR="$PROTO_DEBUG_FRAMES_DIR" PROTO_DEBUG_FRAMES_FPS="$PROTO_DEBUG_FRAMES_FPS" PROTO_DEBUG_FRAMES_SLOTS="$PROTO_DEBUG_FRAMES_SLOTS" PROTO_PORTAL="$PROTO_PORTAL" PROTO_PORTAL_ONLY="$PROTO_PORTAL_ONLY" PROTO_H264="$PROTO_H264" PROTO_EXTEND_RIGHT_PX=0 PROTO_CAPTURE_SIZE="$PROTO_CAPTURE_SIZE" PROTO_CAPTURE_BITRATE_KBPS="$PROTO_CAPTURE_BITRATE_KBPS" PROTO_CAPTURE_FPS="$PROTO_CAPTURE_FPS" PROTO_MJPEG_FPS="$PROTO_MJPEG_FPS" PROTO_PORTAL_JPEG_SOURCE="$PROTO_PORTAL_JPEG_SOURCE" PROTO_CURSOR_MODE="$PROTO_CURSOR_MODE" "$CARGO_BIN" &
else
  PROTO_FORCE_PORTAL=1 PROTO_SERIAL="${SERIAL:-}" PROTO_ADB_PUSH="$PROTO_ADB_PUSH" PROTO_ADB_PUSH_ADDR="$PROTO_ADB_PUSH_ADDR" PROTO_ADB_PUSH_FPS="$PROTO_ADB_PUSH_FPS" PROTO_SKIP_SIG_CHECK="$PROTO_SKIP_SIG_CHECK" PROTO_DEBUG_FRAMES="$PROTO_DEBUG_FRAMES" PROTO_DEBUG_FRAMES_DIR="$PROTO_DEBUG_FRAMES_DIR" PROTO_DEBUG_FRAMES_FPS="$PROTO_DEBUG_FRAMES_FPS" PROTO_DEBUG_FRAMES_SLOTS="$PROTO_DEBUG_FRAMES_SLOTS" PROTO_PORTAL="$PROTO_PORTAL" PROTO_PORTAL_ONLY="$PROTO_PORTAL_ONLY" PROTO_H264="$PROTO_H264" PROTO_EXTEND_RIGHT_PX=0 PROTO_CAPTURE_SIZE="$PROTO_CAPTURE_SIZE" PROTO_CAPTURE_BITRATE_KBPS="$PROTO_CAPTURE_BITRATE_KBPS" PROTO_CAPTURE_FPS="$PROTO_CAPTURE_FPS" PROTO_MJPEG_FPS="$PROTO_MJPEG_FPS" PROTO_PORTAL_JPEG_SOURCE="$PROTO_PORTAL_JPEG_SOURCE" PROTO_CURSOR_MODE="$PROTO_CURSOR_MODE" cargo run --release &
fi
HOST_PID=$!

cleanup() {
  if [[ -n "$LOGCAT_PID" ]]; then
    kill "$LOGCAT_PID" >/dev/null 2>&1 || true
  fi
  # Kill the cargo wrapper and the actual binary (cargo run spawns a grandchild)
  kill "$HOST_PID" >/dev/null 2>&1 || true
  pkill -f proto-host-image >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

sleep 1
log "if Wayland prompt appears, pick the virtual/extended screen source"

# Wait briefly for first decoded portal frame so app doesn't start on long fallback.
# Skip wait when using H264 direct path (no JPEG files produced).
FRAME_READY=0
FRAME_READY_PATH="/tmp/proto-portal-frame.jpg"
if [[ "$PROTO_PORTAL_JPEG_SOURCE" == "debug" ]]; then
  FRAME_READY_PATH="/dev/shm/proto-portal-frames"
fi

portal_enabled=0
if [[ "$PROTO_PORTAL" != "0" && "$PROTO_PORTAL" != "false" && "$PROTO_PORTAL" != "FALSE" && "$PROTO_PORTAL" != "no" && "$PROTO_PORTAL" != "NO" ]]; then
  portal_enabled=1
fi

if [[ "$PROTO_H264" == "1" ]]; then
  log "H264 mode enabled — skipping portal JPEG wait"
else
if [[ "$portal_enabled" -eq 1 && "$PROTO_WAIT_FOR_FIRST_FRAME_REQUIRED" == "1" ]]; then
  log "waiting for first portal frame before launching app (required=1)"
  wait_ticks=0
  while true; do
    if [[ "$PROTO_PORTAL_JPEG_SOURCE" == "debug" ]]; then
      if find "$FRAME_READY_PATH" -maxdepth 1 -name '*.jpg' -type f | grep -q .; then
        FRAME_READY=1
        break
      fi
    elif [[ -s "$FRAME_READY_PATH" ]]; then
      FRAME_READY=1
      break
    fi
    wait_ticks=$((wait_ticks + 1))
    if (( wait_ticks % 50 == 0 )); then
      log "still waiting for portal frame... choose screen/monitor in portal prompt"
    fi
    sleep 0.1
  done
else
  for _ in $(seq 1 $((PROTO_WAIT_FOR_FIRST_FRAME_SECS * 10))); do
    if [[ "$PROTO_PORTAL_JPEG_SOURCE" == "debug" ]]; then
      if find "$FRAME_READY_PATH" -maxdepth 1 -name '*.jpg' -type f | grep -q .; then
        FRAME_READY=1
        break
      fi
    elif [[ -s "$FRAME_READY_PATH" ]]; then
      FRAME_READY=1
      break
    fi
    sleep 0.1
  done
fi
fi

if [[ "$FRAME_READY" -eq 1 ]]; then
  log "first portal frame detected ($PROTO_PORTAL_JPEG_SOURCE)"

  if [[ "$PROTO_PORTAL_JPEG_SOURCE" == "debug" ]] && command -v sha256sum >/dev/null 2>&1; then
    mapfile -t FRAME_SIGS < <(
      for _ in $(seq 1 6); do
        latest_frame="$(ls -t /dev/shm/proto-portal-frames/*.jpg 2>/dev/null | head -n 1 || true)"
        if [[ -n "$latest_frame" ]]; then
          sha256sum "$latest_frame" | awk '{print $1}'
        fi
        sleep 0.15
      done
    )
    uniq_sigs="$(printf '%s\n' "${FRAME_SIGS[@]}" | sed '/^$/d' | sort -u | wc -l)"
    if [[ "${uniq_sigs:-0}" -le 1 ]]; then
      log "WARNING: portal source appears static (same frame hash across checks). Consider PROTO_PORTAL_JPEG_SOURCE=file or re-open picker and select the intended output."
    fi
  fi
else
  log "portal frame not ready after ${PROTO_WAIT_FOR_FIRST_FRAME_SECS}s; launching app anyway (required=$PROTO_WAIT_FOR_FIRST_FRAME_REQUIRED)"
fi

# Launch app
if [[ "$ADB_READY" -eq 1 ]]; then
  adb "${SERIAL_FLAG[@]}" logcat -c >/dev/null 2>&1 || true
  : > "$ANDROID_LOG_FILE"
  adb "${SERIAL_FLAG[@]}" logcat -v time > "$ANDROID_LOG_FILE" 2>&1 &
  LOGCAT_PID="$!"

  log "launching app on device…"
  # Remove ADB forward BEFORE force-stop so adbd stops queuing connections to
  # the old app's port 5006; otherwise CLOSE_WAIT sockets linger and the new
  # app cannot bind the port immediately.
  if [[ "${PROTO_ADB_PUSH:-0}" == "1" ]]; then
    adb "${SERIAL_FLAG[@]}" forward --remove tcp:5006 >/dev/null 2>&1 || true
    sleep 1   # let adbd drain in-flight connection attempts
  fi
  adb "${SERIAL_FLAG[@]}" shell am force-stop com.proto.demo >/dev/null 2>&1 || true
  sleep 5   # let the kernel drain orphan port-5006 sockets (TCP keepalive ~40s is handled by the app-side retry loop)
  if [[ "${PROTO_ADB_PUSH:-0}" == "1" ]]; then
    adb "${SERIAL_FLAG[@]}" forward tcp:5006 tcp:5006 >/dev/null 2>&1 || true
  fi
  EXTRA_STEPS=()
  if [[ "${PROTO_TEST_STEPS:-0}" == "1" ]]; then
    EXTRA_STEPS+=(--ez test_steps true)
  fi
  if [[ "$PROTO_FORCE_JAVA_FALLBACK" == "1" ]]; then
    EXTRA_STEPS+=(--ez force_java_fallback true)
  fi

  h264_flag=false
  if [[ "${PROTO_H264:-0}" == "1" ]]; then
    h264_flag=true
  fi

  capture_size_extra=()
  if [[ -n "${PROTO_CAPTURE_SIZE:-}" ]]; then
    capture_size_extra=(--es capture_size "$PROTO_CAPTURE_SIZE")
  fi

  # If H264 path is enabled, skip portal JPEG wait and mark Intent
  if [[ "$PROTO_H264" == "1" ]]; then
    PROTO_PORTAL_JPEG_SOURCE="${PROTO_PORTAL_JPEG_SOURCE:-h264}"
    PROTO_WAIT_FOR_FIRST_FRAME_REQUIRED=0
  fi

  if [[ "$PROTO_ADB_PUSH" == "1" ]]; then
    adb "${SERIAL_FLAG[@]}" shell am start -n com.proto.demo/.MainActivity --es host_ip "$APP_HOST_IP" --ez adb_push true --ez h264 "$h264_flag" "${capture_size_extra[@]}" "${EXTRA_STEPS[@]}"
  else
    adb "${SERIAL_FLAG[@]}" shell am start -n com.proto.demo/.MainActivity --es host_ip "$APP_HOST_IP" --ez adb_push false --ez h264 "$h264_flag" "${capture_size_extra[@]}" "${EXTRA_STEPS[@]}"
  fi

  sleep 3
  if app_is_running; then
    log "android app is running (logs: $ANDROID_LOG_FILE)"

    if [[ "$PROTO_REQUIRE_TURBO" == "1" ]]; then
      if grep -Eq "failed to load libwbeam|failed to load libturbojpeg|turbojpeg unavailable|native renderer unavailable" "$ANDROID_LOG_FILE"; then
        log "turbo runtime check failed; app started without native turbo path"
        grep -E "failed to load libwbeam|failed to load libturbojpeg|turbojpeg unavailable|native renderer unavailable" "$ANDROID_LOG_FILE" | tail -n 30
        exit 1
      fi
    fi
  else
    log "android app appears stopped after launch; recent logcat (logs: $ANDROID_LOG_FILE):"
    grep -E "com\.proto\.demo|AndroidRuntime|FATAL EXCEPTION|Process: com\.proto\.demo" "$ANDROID_LOG_FILE" | tail -n 120 || tail -n 120 "$ANDROID_LOG_FILE"
  fi
fi

wait "$HOST_PID"
