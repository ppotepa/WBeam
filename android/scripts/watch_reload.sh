#!/usr/bin/env bash
set -euo pipefail

APP_ID="com.wbeam"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

WATCH_CMD=(
  find app/src/main app/build.gradle build.gradle settings.gradle
  \( -type f \)
  \( -name '*.java' -o -name '*.kt' -o -name '*.xml' -o -name '*.gradle' -o -name '*.kts' \)
  -print0
)

compute_sig() {
  "${WATCH_CMD[@]}" \
    | xargs -0 -r stat -c '%n:%Y' \
    | sort \
    | sha1sum \
    | awk '{print $1}'
}

ensure_adb_device() {
  if ! adb get-state >/dev/null 2>&1; then
    echo "[watch-reload] adb device not ready. Connect device and enable USB debugging."
    return 1
  fi
}

rebuild_and_restart() {
  echo "[watch-reload] building + installing..."
  ./gradlew :app:installDebug
  adb shell am force-stop "$APP_ID" || true
  adb shell monkey -p "$APP_ID" -c android.intent.category.LAUNCHER 1 >/dev/null
  echo "[watch-reload] app relaunched"
}

echo "[watch-reload] root: $ROOT_DIR"
echo "[watch-reload] watching: app/src/main + gradle files"

ensure_adb_device

last_sig="$(compute_sig)"
echo "[watch-reload] initial signature: $last_sig"

rebuild_and_restart

while true; do
  sleep 1
  new_sig="$(compute_sig)"
  if [[ "$new_sig" != "$last_sig" ]]; then
    echo "[watch-reload] change detected"
    last_sig="$new_sig"
    if ensure_adb_device; then
      rebuild_and_restart || echo "[watch-reload] build/install failed; waiting for next change"
    fi
  fi
done
