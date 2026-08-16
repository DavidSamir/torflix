#!/usr/bin/env bash
# Build and sideload TORFILX onto a Fire TV (or any adb-connected device).
#
# Usage: scripts/install.sh [fire-tv-ip]
set -euo pipefail

cd "$(dirname "$0")/.."

if [ $# -ge 1 ]; then
  adb connect "$1:5555"
fi

./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.torfilx.tv.debug/com.torfilx.tv.MainActivity
