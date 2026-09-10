#!/usr/bin/env bash
set -euo pipefail
# Ensure the IME test really has a keyboard available even with emulator hardware keys.
adb shell settings put secure show_ime_with_hard_keyboard 1
./gradlew --no-daemon --console=plain --stacktrace :shared:connectedDebugAndroidTest \
  2>&1 | tee "${RUNNER_TEMP:-/tmp}/gradle-android-ui.log"
