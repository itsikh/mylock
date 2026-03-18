#!/bin/bash
# install.sh — Build and install the debug APK to a connected device/emulator.
# Requires a physical device with USB debugging ON, or a running emulator.

export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

# Update SDK_DIR to match your machine's Android SDK location (from local.properties)
SDK_DIR=$(grep 'sdk.dir' local.properties 2>/dev/null | cut -d'=' -f2 | tr -d ' ')
ADB="${SDK_DIR}/platform-tools/adb"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Check for connected device
DEVICES=$("$ADB" devices 2>/dev/null | grep -v "List of devices" | grep -v "^$" | grep "device$")
if [ -z "$DEVICES" ]; then
    echo "No Android device/emulator connected."
    echo ""
    echo "Options:"
    echo "  1. Connect a physical device via USB and enable USB debugging:"
    echo "     Settings → Developer options → USB debugging"
    echo "  2. Start an emulator from Android Studio:"
    echo "     Android Studio → Device Manager → Create/Start an AVD"
    exit 1
fi

echo "Installing to device..."
./gradlew installDebug "$@"
