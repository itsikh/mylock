#!/bin/bash
# build.sh — Build the debug APK
# Uses Android Studio's bundled JBR since Java is not in system PATH.

export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "Building debug APK..."
./gradlew assembleDebug "$@"

if [ $? -eq 0 ]; then
    APK="$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"
    echo ""
    echo "APK: $APK"
    echo "Run ./install.sh to install to a connected device/emulator."
fi
