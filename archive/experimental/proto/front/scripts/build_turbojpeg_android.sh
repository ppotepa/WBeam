#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
APP_DIR="$ROOT_DIR/app"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/ppotepa/Android/Sdk}}"
NDK_DIR="${ANDROID_NDK_HOME:-$SDK_DIR/ndk/23.1.7779620}"
TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake"

if [[ ! -f "$TOOLCHAIN_FILE" ]]; then
  echo "[turbo] missing NDK toolchain: $TOOLCHAIN_FILE" >&2
  exit 1
fi

WORK_DIR="${1:-/tmp/wbeam-libs}"
SRC_DIR="$WORK_DIR/libjpeg-turbo"
BUILD_ROOT="$WORK_DIR/build-android"

mkdir -p "$WORK_DIR" "$BUILD_ROOT"

if [[ ! -d "$SRC_DIR/.git" ]]; then
  echo "[turbo] cloning libjpeg-turbo into $SRC_DIR"
  git clone --depth 1 https://github.com/libjpeg-turbo/libjpeg-turbo.git "$SRC_DIR"
else
  echo "[turbo] updating libjpeg-turbo"
  git -C "$SRC_DIR" pull --ff-only || true
fi

build_one() {
  local abi="$1"
  local platform="android-17"
  local build_dir="$BUILD_ROOT/$abi"

  echo "[turbo] building ABI=$abi"
  rm -rf "$build_dir"
  cmake -S "$SRC_DIR" -B "$build_dir" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN_FILE" \
    -DANDROID_ABI="$abi" \
    -DANDROID_PLATFORM="$platform" \
    -DENABLE_SHARED=TRUE \
    -DENABLE_STATIC=FALSE \
    -DWITH_JAVA=FALSE \
    -DWITH_TURBOJPEG=TRUE

  cmake --build "$build_dir" --target turbojpeg

  local so_file
  so_file="$(find "$build_dir" -type f -name libturbojpeg.so | head -n 1 || true)"
  if [[ -z "$so_file" ]]; then
    echo "[turbo] libturbojpeg.so not found for $abi" >&2
    exit 1
  fi

  local header_file
  header_file="$(find "$build_dir" -type f -name turbojpeg.h | head -n 1 || true)"
  if [[ -z "$header_file" ]]; then
    header_file="$(find "$SRC_DIR" -type f -name turbojpeg.h | head -n 1 || true)"
  fi
  if [[ -z "$header_file" ]]; then
    echo "[turbo] turbojpeg.h not found" >&2
    exit 1
  fi

  mkdir -p "$APP_DIR/src/main/jniLibs/$abi"
  cp -f "$so_file" "$APP_DIR/src/main/jniLibs/$abi/libturbojpeg.so"

  mkdir -p "$APP_DIR/src/main/cpp/include"
  cp -f "$header_file" "$APP_DIR/src/main/cpp/include/turbojpeg.h"

  echo "[turbo] copied $abi -> $APP_DIR/src/main/jniLibs/$abi/libturbojpeg.so"
}

build_one "armeabi-v7a"
build_one "arm64-v8a"
build_one "x86"

echo "[turbo] done"
