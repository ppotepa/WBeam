#!/bin/bash
# Build and upload WBeam APK to Android device

set -e

BUILD_TYPE="${1:-debug}"
APP_PACKAGE="com.wbeam"
APP_ACTIVITY="$APP_PACKAGE.MainActivity"

if [[ "$BUILD_TYPE" != "debug" && "$BUILD_TYPE" != "release" ]]; then
    echo "Usage: $0 [debug|release]"
    echo ""
    echo "Examples:"
    echo "  $0 debug   - Build and install debug APK (default)"
    echo "  $0 release - Build and install release APK"
    exit 1
fi

echo "=== WBeam Android Upload ==="
echo "Build type: $BUILD_TYPE"
echo ""

cd "$(dirname "$0")/android"
GRADLE_USER_HOME="$(cd .. && pwd)/.gradle-user"
mkdir -p "$GRADLE_USER_HOME"

# Build APK
echo "🔨 Building $BUILD_TYPE APK..."
if [[ "$BUILD_TYPE" == "debug" ]]; then
    GRADLE_USER_HOME="$GRADLE_USER_HOME" bash ./gradlew assembleDebug
    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
else
    GRADLE_USER_HOME="$GRADLE_USER_HOME" bash ./gradlew assembleRelease
    APK_PATH="app/build/outputs/apk/release/app-release.apk"
fi

if [[ ! -f "$APK_PATH" ]]; then
    echo "❌ APK not found at $APK_PATH"
    exit 1
fi

echo "✓ Build complete: $APK_PATH"
echo ""

# Check ADB connection
echo "📱 Checking ADB connection..."
if ! adb devices | grep -q "device$"; then
    echo "❌ No Android device connected"
    echo "   Connect device and run: adb devices"
    exit 1
fi
echo "✓ Device connected"
echo ""

# Uninstall old version if exists
echo "🗑️  Uninstalling old version..."
adb uninstall $APP_PACKAGE 2>/dev/null || echo "  (no previous version)"

# Install new APK
echo "📦 Installing APK..."
if [[ "$BUILD_TYPE" == "debug" ]]; then
    # Debug APK installs as com.wbeam.debug
    adb install "$APK_PATH"
    APP_PACKAGE="${APP_PACKAGE}.debug"
else
    adb install "$APK_PATH"
fi

echo "✓ Installed: $APP_PACKAGE"
echo ""

# Setup ADB reverse for daemon connection
echo "🔄 Setting up ADB reverse..."
adb reverse tcp:5000 tcp:5000  # WBTP stream
adb reverse tcp:5001 tcp:5001  # HTTP control API
echo "✓ Port forwarding: 5000 (stream), 5001 (control)"
echo ""

# Launch app
echo "🚀 Launching app..."
adb shell am start -n "$APP_PACKAGE/$APP_ACTIVITY"
echo ""

echo "✅ Upload complete!"
echo ""
echo "Next steps:"
echo "  • Start daemon: ./wbeam service up"
echo "  • View logs: ./wbeam logs live"
echo "  • Check status: curl http://127.0.0.1:5001/status"
