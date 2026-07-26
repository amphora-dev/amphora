#!/usr/bin/env bash
# Assume an adb device is already online. Assemble APKs, install, and run the
# instrumented suite excluding @RequiresGraphicsDriver.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

TEST_TIMEOUT_SEC="${AMPHORA_EMU_TEST_TIMEOUT_SEC:-2400}"
LOG="${TMPDIR:-/tmp}/amphora-instrument-no-gpu.log"

if ! command -v adb >/dev/null; then
  echo "adb not found on PATH" >&2
  exit 1
fi

adb devices -l
if ! adb devices | awk 'NR>1 && $2=="device"{found=1} END{exit !found}'; then
  echo "No adb device in 'device' state" >&2
  exit 1
fi

adb shell 'wm dismiss-keyguard || true' >/dev/null 2>&1 || true
adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true

echo "Assembling APKs…"
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --stacktrace

echo "Installing APKs…"
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

echo "Running non-graphics instrumented tests…"
timeout --signal=INT "${TEST_TIMEOUT_SEC}s" adb shell am instrument -w -r \
  -e notAnnotation app.amphora.RequiresGraphicsDriver \
  app.amphora.test/app.amphora.HiltTestRunner \
  | tee "$LOG"

if ! grep -Eq 'OK \([1-9][0-9]* tests?\)' "$LOG"; then
  echo "Instrumented suite did not report OK" >&2
  exit 1
fi

echo "Non-graphics instrumented suite passed"
