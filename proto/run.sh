#!/usr/bin/env bash
set -euo pipefail

# Single launcher for proto stack; all runtime knobs come from proto/config/proto.conf.

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
log() { printf '[proto] %s\n' "$*"; }

usage() {
  cat <<'USAGE'
Usage: ./run.sh [--config path]
All behavior is configured in proto.conf (backend/device/preset/tuning).
Runtime ENV overrides are disabled.
USAGE
}

load_config_file() {
  local cfg="$1"
  if [[ -z "$cfg" ]]; then
    return 0
  fi
  if [[ ! -f "$cfg" ]]; then
    log "config file not found: $cfg (using built-in defaults)"
    return 0
  fi

  # shellcheck disable=SC1090
  source "$cfg"
  log "loaded config: $cfg"
}

# Runtime config must come from proto.conf, not process env.
assert_no_runtime_env_overrides() {
  local bad=()
  while IFS='=' read -r name _; do
    case "$name" in
      PROTO_*|RUN_*|WBEAM_*|HOST_IP|SERIAL|GRADLEW|CARGO_BIN|QEMU_*|ANDROID_EMULATOR_BIN|ANDROID_LOG_FILE)
        bad+=("$name")
        ;;
    esac
  done < <(env)

  if [[ "${#bad[@]}" -gt 0 ]]; then
    log "runtime environment overrides are not allowed. Move these to proto.conf:"
    printf '[proto] %s\n' "${bad[@]}"
    exit 1
  fi
}

# Kill any leftover host process from a previous run (cargo run spawns a grandchild
# that survives the cargo PID being killed by the old cleanup trap).
pkill -f proto-host-image >/dev/null 2>&1 || true

assert_no_runtime_env_overrides

PROTO_CONFIG_FILE="$ROOT_DIR/config/proto.conf"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --config)
      if [[ $# -lt 2 ]]; then
        log "--config requires a file path"
        exit 1
      fi
      PROTO_CONFIG_FILE="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      log "unknown argument '$1'"
      usage
      exit 1
      ;;
  esac
done

load_config_file "$PROTO_CONFIG_FILE"

# If run from tty/ssh, infer desktop session endpoints so portal capture can work.
if [[ -z "${XDG_RUNTIME_DIR:-}" ]]; then
  _runtime_guess="/run/user/$(id -u)"
  if [[ -d "$_runtime_guess" ]]; then
    export XDG_RUNTIME_DIR="$_runtime_guess"
    log "XDG_RUNTIME_DIR not set; using $XDG_RUNTIME_DIR"
  fi
fi
if [[ -z "${DBUS_SESSION_BUS_ADDRESS:-}" && -n "${XDG_RUNTIME_DIR:-}" && -S "${XDG_RUNTIME_DIR}/bus" ]]; then
  export DBUS_SESSION_BUS_ADDRESS="unix:path=${XDG_RUNTIME_DIR}/bus"
  log "DBUS_SESSION_BUS_ADDRESS not set; using ${DBUS_SESSION_BUS_ADDRESS}"
fi
if [[ -z "${WAYLAND_DISPLAY:-}" && -n "${XDG_RUNTIME_DIR:-}" && -S "${XDG_RUNTIME_DIR}/wayland-0" ]]; then
  export WAYLAND_DISPLAY="wayland-0"
  log "WAYLAND_DISPLAY not set; using ${WAYLAND_DISPLAY}"
fi

RUN_BACKEND="${RUN_BACKEND:-rust}"
RUN_DEVICE="${RUN_DEVICE:-adb}"

HOST_IP="${HOST_IP:-}"
SERIAL="${SERIAL:-}"
GRADLEW="${GRADLEW:-$ROOT_DIR/front/gradlew}"
CARGO_BIN="${CARGO_BIN:-}"
QEMU_AVD="${QEMU_AVD:-WBeam_Tablet_API17}"
if [[ -z "${ANDROID_EMULATOR_BIN:-}" ]]; then
  if [[ -x /opt/android-sdk/emulator/emulator ]]; then
    ANDROID_EMULATOR_BIN="/opt/android-sdk/emulator/emulator"
  else
    ANDROID_EMULATOR_BIN="$HOME/Android/Sdk/emulator/emulator"
  fi
fi
QEMU_LOG="${QEMU_LOG:-/tmp/proto-emulator.log}"
QEMU_ARGS="${QEMU_ARGS:-}"

RUN_CS_BACKEND=0
RUN_QEMU_EMULATOR=0
if [[ "$RUN_BACKEND" == "cs" ]]; then
  RUN_CS_BACKEND=1
elif [[ "$RUN_BACKEND" != "rust" ]]; then
  log "invalid RUN_BACKEND=$RUN_BACKEND (expected: rust|cs)"
  exit 1
fi
if [[ "$RUN_DEVICE" == "qemu" ]]; then
  RUN_QEMU_EMULATOR=1
elif [[ "$RUN_DEVICE" != "adb" ]]; then
  log "invalid RUN_DEVICE=$RUN_DEVICE (expected: adb|qemu)"
  exit 1
fi

PROTO_PRESET="${PROTO_PRESET:-fast}"

PROTO_CAPTURE_SIZE="${PROTO_CAPTURE_SIZE:-}"
PROTO_CAPTURE_BITRATE_KBPS="${PROTO_CAPTURE_BITRATE_KBPS:-}"
PROTO_CAPTURE_FPS="${PROTO_CAPTURE_FPS:-}"
PROTO_MJPEG_FPS="${PROTO_MJPEG_FPS:-}"
PROTO_ADB_PUSH_FPS="${PROTO_ADB_PUSH_FPS:-}"
PROTO_SKIP_SIG_CHECK="${PROTO_SKIP_SIG_CHECK:-1}"
PROTO_DEBUG_FRAMES="${PROTO_DEBUG_FRAMES:-0}"
PROTO_DEBUG_FRAMES_DIR="${PROTO_DEBUG_FRAMES_DIR:-$ROOT_DIR/debugframes}"
PROTO_DEBUG_FRAMES_FPS="${PROTO_DEBUG_FRAMES_FPS:-2}"
PROTO_DEBUG_FRAMES_SLOTS="${PROTO_DEBUG_FRAMES_SLOTS:-180}"
PROTO_ADB_PUSH="${PROTO_ADB_PUSH:-1}"
PROTO_ADB_PUSH_ADDR="${PROTO_ADB_PUSH_ADDR:-127.0.0.1:5006}"
PROTO_MAX_FRAME_BYTES="${PROTO_MAX_FRAME_BYTES:-}"
PROTO_MAX_CHUNK_BYTES="${PROTO_MAX_CHUNK_BYTES:-}"
PROTO_JPEG_TARGET_KB="${PROTO_JPEG_TARGET_KB:-}"
PROTO_PORTAL="${PROTO_PORTAL:-1}"
PROTO_PORTAL_JPEG_SOURCE="${PROTO_PORTAL_JPEG_SOURCE:-debug}"
PROTO_CURSOR_MODE="${PROTO_CURSOR_MODE:-}"
WBEAM_VIDEORATE_DROP_ONLY="${WBEAM_VIDEORATE_DROP_ONLY:-0}"
WBEAM_FRAMED_SEND_TIMEOUT_S="${WBEAM_FRAMED_SEND_TIMEOUT_S:-0}"
WBEAM_FRAMED_DUPLICATE_STALE="${WBEAM_FRAMED_DUPLICATE_STALE:-0}"
WBEAM_PIPEWIRE_KEEPALIVE_MS="${WBEAM_PIPEWIRE_KEEPALIVE_MS:-}"
WBEAM_PIPEWIRE_ALWAYS_COPY="${WBEAM_PIPEWIRE_ALWAYS_COPY:-1}"
WBEAM_FRAMED_PULL_TIMEOUT_MS="${WBEAM_FRAMED_PULL_TIMEOUT_MS:-}"
WBEAM_QUEUE_MAX_BUFFERS="${WBEAM_QUEUE_MAX_BUFFERS:-1}"
WBEAM_QUEUE_MAX_TIME_MS="${WBEAM_QUEUE_MAX_TIME_MS:-12}"
WBEAM_APPSINK_MAX_BUFFERS="${WBEAM_APPSINK_MAX_BUFFERS:-2}"
PROTO_PORTAL_ONLY="${PROTO_PORTAL_ONLY:-0}"
PROTO_H264="${PROTO_H264:-}"
PROTO_H264_REORDER="${PROTO_H264_REORDER:-0}"
PROTO_H264_SOURCE_FRAMED="${PROTO_H264_SOURCE_FRAMED:-}"
PROTO_WAIT_FOR_FIRST_FRAME_SECS="${PROTO_WAIT_FOR_FIRST_FRAME_SECS:-1}"
PROTO_WAIT_FOR_FIRST_FRAME_REQUIRED="${PROTO_WAIT_FOR_FIRST_FRAME_REQUIRED:-1}"
PROTO_ANDROID_BUILD_TYPE="${PROTO_ANDROID_BUILD_TYPE:-debug}"
PROTO_REQUIRE_TURBO="${PROTO_REQUIRE_TURBO:-1}"
PROTO_FORCE_JAVA_FALLBACK="${PROTO_FORCE_JAVA_FALLBACK:-0}"
PROTO_ANDROID_LOGCAT="${PROTO_ANDROID_LOGCAT:-0}"
PROTO_ANDROID_LOG_POLLER="${PROTO_ANDROID_LOG_POLLER:-0}"
PROTO_FORCE_PORTAL="${PROTO_FORCE_PORTAL:-1}"
PROTO_ADB_SHELL_TIMEOUT_SECS="${PROTO_ADB_SHELL_TIMEOUT_SECS:-8}"

prepare_cs_backend() {
  local host_cs_dir="$ROOT_DIR/host-cs"
  local project="$host_cs_dir/ProtoHostCs.csproj"
  local runner="$host_cs_dir/host-cs.sh"

  if ! command -v dotnet >/dev/null 2>&1; then
    log "RUN_BACKEND=cs, but dotnet is not available in PATH"
    exit 1
  fi
  if [[ ! -f "$project" || ! -x "$runner" ]]; then
    log "RUN_BACKEND=cs, but proto/host-cs files are missing"
    exit 1
  fi

  log "building C# backend (Release)…"
  dotnet build -c Release "$project"
  CARGO_BIN="$runner"
  : "${PROTO_H264:=1}"
  : "${PROTO_H264_SOURCE_FRAMED:=1}"
}

pick_qemu_avd() {
  local emulator_bin="$1"
  local requested="${QEMU_AVD:-WBeam_Tablet_API17}"
  local avds
  avds="$("$emulator_bin" -list-avds 2>/dev/null || true)"
  if [[ -z "$avds" ]]; then
    return 1
  fi
  if printf '%s\n' "$avds" | grep -Fxq "$requested"; then
    printf '%s\n' "$requested"
    return 0
  fi
  printf '%s\n' "$avds" | head -n 1
}

find_running_qemu_serial() {
  local target_avd="$1"
  local serial
  local avd_name
  local fallback=""

  while IFS= read -r serial; do
    [[ -z "$serial" ]] && continue
    [[ -z "$fallback" ]] && fallback="$serial"
    avd_name="$(adb -s "$serial" emu avd name 2>/dev/null | tr -d '\r' || true)"
    if [[ -n "$avd_name" && "$avd_name" == "$target_avd" ]]; then
      printf '%s\n' "$serial"
      return 0
    fi
  done < <(adb devices 2>/dev/null | awk 'NR>1 && $1 ~ /^emulator-/ && $2=="device" {print $1}')

  if [[ -n "$fallback" ]]; then
    printf '%s\n' "$fallback"
    return 0
  fi
  return 1
}

wait_for_qemu_serial() {
  local timeout_s="${1:-180}"
  local start_ts
  local serial
  start_ts="$(date +%s)"
  while true; do
    serial="$(adb devices 2>/dev/null | awk 'NR>1 && $1 ~ /^emulator-/ && $2=="device" {print $1; exit}')"
    if [[ -n "$serial" ]]; then
      printf '%s\n' "$serial"
      return 0
    fi
    if (( "$(date +%s)" - start_ts >= timeout_s )); then
      return 1
    fi
    sleep 1
  done
}

wait_for_qemu_boot_complete() {
  local serial="$1"
  local boot=""
  local anim=""
  adb -s "$serial" wait-for-device >/dev/null 2>&1 || true
  for _ in $(seq 1 180); do
    boot="$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    anim="$(adb -s "$serial" shell getprop init.svc.bootanim 2>/dev/null | tr -d '\r')"
    if [[ "$boot" == "1" ]] && [[ "$anim" == "stopped" || -z "$anim" ]]; then
      return 0
    fi
    sleep 1
  done
  return 1
}

prepare_qemu_emulator() {
  local emulator_bin="$ANDROID_EMULATOR_BIN"
  local qemu_log="$QEMU_LOG"
  local qemu_args="$QEMU_ARGS"
  local avd_name

  if ! command -v adb >/dev/null 2>&1; then
    log "RUN_DEVICE=qemu, but adb is not available in PATH"
    exit 1
  fi
  if [[ ! -x "$emulator_bin" ]]; then
    log "RUN_DEVICE=qemu, but emulator binary not found at $emulator_bin"
    log "install emulator tools (Android SDK Emulator) and create AVD (prefer API 17: WBeam_Tablet_API17)"
    exit 1
  fi

  adb start-server >/dev/null 2>&1 || true

  if ! avd_name="$(pick_qemu_avd "$emulator_bin")"; then
    log "RUN_DEVICE=qemu, but no AVDs were found"
    log "create an emulator image first (prefer API 17 for this project)"
    exit 1
  fi

  if SERIAL="$(find_running_qemu_serial "$avd_name")"; then
    log "using running emulator $SERIAL (avd=$avd_name)"
  else
    log "starting emulator AVD: $avd_name"
    nohup "$emulator_bin" -avd "$avd_name" -netdelay none -netspeed full -gpu swiftshader_indirect -no-snapshot-load $qemu_args >"$qemu_log" 2>&1 &
    if ! SERIAL="$(wait_for_qemu_serial 180)"; then
      log "emulator did not appear in adb within timeout (log: $qemu_log)"
      exit 1
    fi
    log "emulator online: $SERIAL"
  fi

  if ! wait_for_qemu_boot_complete "$SERIAL"; then
    log "emulator boot not completed in timeout (serial: $SERIAL)"
    exit 1
  fi

  if [[ "${PROTO_REQUIRE_TURBO}" == "1" ]]; then
    PROTO_REQUIRE_TURBO=0
  fi
  if [[ "${PROTO_FORCE_JAVA_FALLBACK}" == "0" ]]; then
    PROTO_FORCE_JAVA_FALLBACK=1
  fi
  if [[ -z "${PROTO_H264}" ]]; then
    PROTO_H264=0
  fi
  if [[ -z "${PROTO_FORCE_NATIVE_SIZE:-}" ]]; then
    PROTO_FORCE_NATIVE_SIZE=0
  fi
  if [[ -z "${PROTO_ADB_PUSH}" ]]; then
    PROTO_ADB_PUSH=1
  fi
  if [[ -z "${PROTO_PORTAL}" ]]; then
    PROTO_PORTAL=1
  fi
  if [[ -z "${PROTO_WAIT_FOR_FIRST_FRAME_REQUIRED}" ]]; then
    PROTO_WAIT_FOR_FIRST_FRAME_REQUIRED=1
  fi
  if [[ "${PROTO_ADB_PUSH}" == "0" ]]; then
    : "${HOST_IP:=10.0.2.2}"
  fi
}

if [[ "$RUN_CS_BACKEND" -eq 1 ]]; then
  prepare_cs_backend
fi

if [[ "$RUN_QEMU_EMULATOR" -eq 1 ]]; then
  prepare_qemu_emulator
fi

refresh_serial_flag() {
  SERIAL_FLAG=()
  if [[ -n "${SERIAL:-}" ]]; then
    SERIAL_FLAG=(-s "$SERIAL")
  fi
}

adb_shell_quick() {
  if command -v timeout >/dev/null 2>&1; then
    timeout "${PROTO_ADB_SHELL_TIMEOUT_SECS}s" adb "${SERIAL_FLAG[@]}" shell "$@"
  else
    adb "${SERIAL_FLAG[@]}" shell "$@"
  fi
}

select_serial_noninteractive() {
  local allow_emulator="${1:-1}"
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
  if [[ "$allow_emulator" == "1" && -n "$first_any" ]]; then
    printf '%s\n' "$first_any"
    return 0
  fi

  return 1
}

if [[ -z "${SERIAL:-}" ]]; then
  select_any_serial=1
  if [[ "$RUN_QEMU_EMULATOR" -eq 0 ]]; then
    select_any_serial=0
  fi
  if SERIAL="$(select_serial_noninteractive "$select_any_serial")"; then
    log "SERIAL not set; selected device $SERIAL"
  elif [[ "$RUN_QEMU_EMULATOR" -eq 0 ]]; then
    log "SERIAL not set; no physical adb device detected (set RUN_DEVICE=qemu in proto.conf for emulator)"
  fi
fi

refresh_serial_flag

if [[ -n "${SERIAL:-}" ]]; then
  # Stop stale app early so it does not keep showing old "connection refused"
  # state while the new host/app session is being prepared.
  log "preflight: stopping stale app (timeout=${PROTO_ADB_SHELL_TIMEOUT_SECS}s)"
  if ! adb_shell_quick am force-stop com.proto.demo >/dev/null 2>&1; then
    log "WARNING: preflight force-stop timed out or failed; continuing"
  fi
fi

case "$PROTO_PRESET" in
  fast)
    : "${PROTO_CAPTURE_SIZE:=1024x640}"
    : "${PROTO_CAPTURE_BITRATE_KBPS:=16000}"
    : "${PROTO_CAPTURE_FPS:=45}"
    : "${PROTO_MJPEG_FPS:=45}"
    : "${PROTO_ADB_PUSH_FPS:=45}"
    : "${PROTO_MAX_FRAME_BYTES:=131072}" # 128 KB
    : "${PROTO_JPEG_TARGET_KB:=72}"
    ;;
  balanced)
    : "${PROTO_CAPTURE_SIZE:=960x600}"
    : "${PROTO_CAPTURE_BITRATE_KBPS:=9000}"
    : "${PROTO_CAPTURE_FPS:=30}"
    : "${PROTO_MJPEG_FPS:=24}"
    : "${PROTO_ADB_PUSH_FPS:=24}"
    : "${PROTO_MAX_FRAME_BYTES:=143360}" # 140 KB
    : "${PROTO_JPEG_TARGET_KB:=72}"
    ;;
  quality)
    : "${PROTO_CAPTURE_SIZE:=1920x1080}"
    : "${PROTO_CAPTURE_BITRATE_KBPS:=26000}"
    : "${PROTO_CAPTURE_FPS:=50}"
    : "${PROTO_MJPEG_FPS:=50}"
    : "${PROTO_MAX_FRAME_BYTES:=225280}" # 220 KB
    : "${PROTO_JPEG_TARGET_KB:=120}"
    ;;
  *)
    printf '[proto] %s\n' "unknown PROTO_PRESET=$PROTO_PRESET (expected: fast|balanced|quality)"
    exit 1
    ;;
esac

if [[ -z "$PROTO_H264" ]]; then
  if [[ "$PROTO_PRESET" == "fast" ]]; then
    PROTO_H264=1
  else
    PROTO_H264=0
  fi
fi

if [[ -z "$PROTO_H264_SOURCE_FRAMED" ]]; then
  if [[ "$PROTO_H264" == "1" ]]; then
    PROTO_H264_SOURCE_FRAMED=1
  else
    PROTO_H264_SOURCE_FRAMED=0
  fi
fi

if [[ -z "$PROTO_CURSOR_MODE" ]]; then
  PROTO_CURSOR_MODE="embedded"
fi

  : "${PROTO_ADB_PUSH_FPS:=$PROTO_CAPTURE_FPS}"

# ── Auto-detect native device resolution ─────────────────────────────────────
# Query the connected Android device for its physical screen size and override
# PROTO_CAPTURE_SIZE so the host encodes at exactly the tablet's native pixels.
# This reduces JPEG payload size and eliminates pointless upscale/downscale.
# Set PROTO_CAPTURE_SIZE_OVERRIDE=1 to keep the preset value instead.
if [[ -z "${PROTO_FORCE_NATIVE_SIZE:-}" ]]; then
  if [[ "$PROTO_PRESET" == "fast" ]]; then
    if [[ "$PROTO_H264" == "1" ]]; then
      PROTO_FORCE_NATIVE_SIZE=1
    else
      PROTO_FORCE_NATIVE_SIZE=0
    fi
  else
    PROTO_FORCE_NATIVE_SIZE=1
  fi
fi

if [[ "${PROTO_FORCE_NATIVE_SIZE:-1}" != "1" ]]; then
  log "PROTO_FORCE_NATIVE_SIZE=0 — keeping preset PROTO_CAPTURE_SIZE=${PROTO_CAPTURE_SIZE}"
elif [[ "${PROTO_CAPTURE_SIZE_OVERRIDE:-0}" != "1" ]]; then
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
  _kscreen_cmd=(kscreen-doctor -o)
  if command -v timeout >/dev/null 2>&1; then
    _kscreen_cmd=(timeout 5s kscreen-doctor -o)
  fi
  _virt_output="$("${_kscreen_cmd[@]}" 2>/dev/null \
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

if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -d /usr/lib/jvm/java-17-openjdk-amd64 ]]; then
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
  elif [[ -d /usr/lib/jvm/java-17-openjdk ]]; then
    JAVA_HOME=/usr/lib/jvm/java-17-openjdk
  fi
  if [[ -n "${JAVA_HOME:-}" ]]; then
    PATH="$JAVA_HOME/bin:$PATH"
    log "JAVA_HOME not set; using $JAVA_HOME"
  fi
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

RUNTIME_CONFIG_FILE="/tmp/proto-runtime-${$}.conf"
write_runtime_config() {
  cat > "$RUNTIME_CONFIG_FILE" <<EOF
PROTO_PRESET=$PROTO_PRESET
PROTO_CAPTURE_SIZE=$PROTO_CAPTURE_SIZE
PROTO_CAPTURE_BITRATE_KBPS=$PROTO_CAPTURE_BITRATE_KBPS
PROTO_CAPTURE_FPS=$PROTO_CAPTURE_FPS
PROTO_MJPEG_FPS=$PROTO_MJPEG_FPS
PROTO_ADB_PUSH_FPS=$PROTO_ADB_PUSH_FPS
PROTO_SKIP_SIG_CHECK=$PROTO_SKIP_SIG_CHECK
PROTO_DEBUG_FRAMES=$PROTO_DEBUG_FRAMES
PROTO_DEBUG_FRAMES_DIR=$PROTO_DEBUG_FRAMES_DIR
PROTO_DEBUG_FRAMES_FPS=$PROTO_DEBUG_FRAMES_FPS
PROTO_DEBUG_FRAMES_SLOTS=$PROTO_DEBUG_FRAMES_SLOTS
PROTO_ADB_PUSH=$PROTO_ADB_PUSH
PROTO_ADB_PUSH_ADDR=$PROTO_ADB_PUSH_ADDR
PROTO_MAX_FRAME_BYTES=$PROTO_MAX_FRAME_BYTES
PROTO_MAX_CHUNK_BYTES=$PROTO_MAX_CHUNK_BYTES
PROTO_JPEG_TARGET_KB=$PROTO_JPEG_TARGET_KB
PROTO_PORTAL=$PROTO_PORTAL
PROTO_PORTAL_JPEG_SOURCE=$PROTO_PORTAL_JPEG_SOURCE
PROTO_CURSOR_MODE=$PROTO_CURSOR_MODE
WBEAM_VIDEORATE_DROP_ONLY=$WBEAM_VIDEORATE_DROP_ONLY
WBEAM_FRAMED_SEND_TIMEOUT_S=$WBEAM_FRAMED_SEND_TIMEOUT_S
WBEAM_FRAMED_DUPLICATE_STALE=$WBEAM_FRAMED_DUPLICATE_STALE
WBEAM_PIPEWIRE_KEEPALIVE_MS=$WBEAM_PIPEWIRE_KEEPALIVE_MS
WBEAM_PIPEWIRE_ALWAYS_COPY=$WBEAM_PIPEWIRE_ALWAYS_COPY
WBEAM_FRAMED_PULL_TIMEOUT_MS=$WBEAM_FRAMED_PULL_TIMEOUT_MS
WBEAM_QUEUE_MAX_BUFFERS=$WBEAM_QUEUE_MAX_BUFFERS
WBEAM_QUEUE_MAX_TIME_MS=$WBEAM_QUEUE_MAX_TIME_MS
WBEAM_APPSINK_MAX_BUFFERS=$WBEAM_APPSINK_MAX_BUFFERS
PROTO_PORTAL_ONLY=$PROTO_PORTAL_ONLY
PROTO_H264=$PROTO_H264
PROTO_H264_REORDER=$PROTO_H264_REORDER
PROTO_H264_SOURCE_FRAMED=$PROTO_H264_SOURCE_FRAMED
PROTO_ANDROID_LOG_POLLER=$PROTO_ANDROID_LOG_POLLER
PROTO_FORCE_PORTAL=$PROTO_FORCE_PORTAL
PROTO_EXTEND_RIGHT_PX=0
PROTO_SERIAL=${SERIAL:-}
EOF
}

# Start host server first, then launch app so auto-start stream has endpoint ready.
log "starting host server on 0.0.0.0:5005 (desktop stream)…"
log "preset=$PROTO_PRESET size=$PROTO_CAPTURE_SIZE fps=$PROTO_CAPTURE_FPS bitrate=${PROTO_CAPTURE_BITRATE_KBPS}kbps mjpeg_fps=$PROTO_MJPEG_FPS adb_push_fps=$PROTO_ADB_PUSH_FPS adb_push=$PROTO_ADB_PUSH max_frame_bytes=$PROTO_MAX_FRAME_BYTES max_chunk_bytes=$PROTO_MAX_CHUNK_BYTES jpeg_target_kb=$PROTO_JPEG_TARGET_KB skip_sig_check=$PROTO_SKIP_SIG_CHECK debug_frames=$PROTO_DEBUG_FRAMES drop_only=$WBEAM_VIDEORATE_DROP_ONLY duplicate_stale=$WBEAM_FRAMED_DUPLICATE_STALE keepalive_ms=${WBEAM_PIPEWIRE_KEEPALIVE_MS:-auto} always_copy=$WBEAM_PIPEWIRE_ALWAYS_COPY pull_timeout_ms=${WBEAM_FRAMED_PULL_TIMEOUT_MS:-auto} q_max_buffers=$WBEAM_QUEUE_MAX_BUFFERS q_max_time_ms=$WBEAM_QUEUE_MAX_TIME_MS appsink_max_buffers=$WBEAM_APPSINK_MAX_BUFFERS send_timeout_s=$WBEAM_FRAMED_SEND_TIMEOUT_S portal=$PROTO_PORTAL cursor=$PROTO_CURSOR_MODE jpeg_source=$PROTO_PORTAL_JPEG_SOURCE portal_only=$PROTO_PORTAL_ONLY h264=$PROTO_H264 h264_source_framed=$PROTO_H264_SOURCE_FRAMED h264_reorder=$PROTO_H264_REORDER android_logcat=$PROTO_ANDROID_LOGCAT android_log_poller=$PROTO_ANDROID_LOG_POLLER"

if [[ "$PROTO_DEBUG_FRAMES" == "1" ]]; then
  mkdir -p "$PROTO_DEBUG_FRAMES_DIR"
fi
write_runtime_config

cd "$HOST_DIR"
if [[ -n "$CARGO_BIN" ]]; then
  "$CARGO_BIN" --config "$RUNTIME_CONFIG_FILE" &
else
  cargo run --release -- --config "$RUNTIME_CONFIG_FILE" &
fi
HOST_PID=$!

cleanup() {
  if [[ -n "$LOGCAT_PID" ]]; then
    kill "$LOGCAT_PID" >/dev/null 2>&1 || true
  fi
  rm -f "$RUNTIME_CONFIG_FILE" >/dev/null 2>&1 || true
  # Kill the cargo wrapper and the actual binary (cargo run spawns a grandchild)
  kill "$HOST_PID" >/dev/null 2>&1 || true
  pkill -f proto-host-image >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

wait_for_host_health() {
  local timeout_s="${1:-12}"
  local deadline
  local ok_streak=0
  deadline=$(( $(date +%s) + timeout_s ))

  while (( $(date +%s) <= deadline )); do
    if ! kill -0 "$HOST_PID" >/dev/null 2>&1; then
      return 1
    fi

    if curl -fsS --max-time 1 "http://127.0.0.1:5005/health" >/dev/null 2>&1; then
      ok_streak=$((ok_streak + 1))
      if (( ok_streak >= 2 )); then
        return 0
      fi
    else
      ok_streak=0
    fi
    sleep 0.2
  done
  return 1
}

if [[ "$PROTO_H264" != "1" ]] && command -v curl >/dev/null 2>&1; then
  if wait_for_host_health 12; then
    log "host listener is ready (:5005)"
  else
    log "ERROR: host listener on :5005 did not come up in time"
    exit 1
  fi
fi

sleep 1
log "if Wayland prompt appears, pick the virtual/extended screen source"

# Wait briefly for first decoded portal frame so app doesn't start on long fallback.
# Skip wait when using H264 direct path (no JPEG files produced).
FRAME_READY=0
FRAME_READY_PATH="/tmp/proto-portal-frame.jpg"
if [[ "$PROTO_PORTAL_JPEG_SOURCE" == "debug" ]]; then
  FRAME_READY_PATH="/dev/shm/proto-portal-frames"
fi
FRAME_READY_MIN_EPOCH="$(date +%s)"

portal_frame_is_fresh() {
  local mtime
  if [[ "$PROTO_PORTAL_JPEG_SOURCE" == "debug" ]]; then
    local latest_frame
    latest_frame="$(ls -t "$FRAME_READY_PATH"/*.jpg 2>/dev/null | head -n 1 || true)"
    [[ -n "$latest_frame" ]] || return 1
    mtime="$(stat -c %Y "$latest_frame" 2>/dev/null || echo 0)"
  else
    [[ -s "$FRAME_READY_PATH" ]] || return 1
    mtime="$(stat -c %Y "$FRAME_READY_PATH" 2>/dev/null || echo 0)"
  fi
  (( mtime >= FRAME_READY_MIN_EPOCH ))
}

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
    if portal_frame_is_fresh; then
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
    if portal_frame_is_fresh; then
      FRAME_READY=1
      break
    fi
    sleep 0.1
  done
fi
fi

if [[ "$PROTO_H264" == "1" ]]; then
  :
elif [[ "$FRAME_READY" -eq 1 ]]; then
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
  if [[ "$PROTO_ANDROID_LOGCAT" == "1" ]]; then
    : > "$ANDROID_LOG_FILE"
    adb "${SERIAL_FLAG[@]}" logcat -v time > "$ANDROID_LOG_FILE" 2>&1 &
    LOGCAT_PID="$!"
  else
    LOGCAT_PID=""
  fi

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
  if [[ "$PROTO_H264_REORDER" == "1" ]]; then
    EXTRA_STEPS+=(--ez h264_reorder true)
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
  APP_LOG_DUMP=""
  if [[ "$PROTO_ANDROID_LOGCAT" != "1" ]]; then
    APP_LOG_DUMP="$(adb "${SERIAL_FLAG[@]}" logcat -d 2>/dev/null || true)"
  fi
  if app_is_running; then
    if [[ "$PROTO_ANDROID_LOGCAT" == "1" ]]; then
      log "android app is running (logs: $ANDROID_LOG_FILE)"
    else
      log "android app is running"
    fi

    if [[ "$PROTO_REQUIRE_TURBO" == "1" ]]; then
      if [[ "$PROTO_ANDROID_LOGCAT" == "1" ]]; then
        TURBO_SOURCE="$ANDROID_LOG_FILE"
        TURBO_FAIL=0
        if grep -Eq "failed to load libwbeam|failed to load libturbojpeg|turbojpeg unavailable|native renderer unavailable" "$TURBO_SOURCE"; then
          TURBO_FAIL=1
        fi
      else
        TURBO_SOURCE="logcat -d"
        TURBO_FAIL=0
        if grep -Eq "failed to load libwbeam|failed to load libturbojpeg|turbojpeg unavailable|native renderer unavailable" <<<"$APP_LOG_DUMP"; then
          TURBO_FAIL=1
        fi
      fi
      if [[ "$TURBO_FAIL" -eq 1 ]]; then
        log "turbo runtime check failed; app started without native turbo path"
        if [[ "$PROTO_ANDROID_LOGCAT" == "1" ]]; then
          grep -E "failed to load libwbeam|failed to load libturbojpeg|turbojpeg unavailable|native renderer unavailable" "$TURBO_SOURCE" | tail -n 30
        else
          grep -E "failed to load libwbeam|failed to load libturbojpeg|turbojpeg unavailable|native renderer unavailable" <<<"$APP_LOG_DUMP" | tail -n 30
        fi
        exit 1
      fi
    fi
  else
    if [[ "$PROTO_ANDROID_LOGCAT" == "1" ]]; then
      log "android app appears stopped after launch; recent logcat (logs: $ANDROID_LOG_FILE):"
      grep -E "com\.proto\.demo|AndroidRuntime|FATAL EXCEPTION|Process: com\.proto\.demo" "$ANDROID_LOG_FILE" | tail -n 120 || tail -n 120 "$ANDROID_LOG_FILE"
    else
      log "android app appears stopped after launch; recent logcat:"
      grep -E "com\.proto\.demo|AndroidRuntime|FATAL EXCEPTION|Process: com\.proto\.demo" <<<"$APP_LOG_DUMP" | tail -n 120 || tail -n 120 <<<"$APP_LOG_DUMP"
    fi
  fi
fi

wait "$HOST_PID"
