#!/usr/bin/env bash
# Start the Amphora AVD (if needed), then run instrumented tests that do NOT
# require a real graphics driver / Vulkan / Turnip path.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$PATH"

API_LEVEL="${AMPHORA_EMU_API:-30}"
AVD_NAME="${AMPHORA_AVD_NAME:-amphora_api${API_LEVEL}_arm64}"
BOOT_TIMEOUT_SEC="${AMPHORA_EMU_BOOT_TIMEOUT_SEC:-600}"
TEST_TIMEOUT_SEC="${AMPHORA_EMU_TEST_TIMEOUT_SEC:-2400}"

EMU_PID=""
cleanup() {
  if [[ -n "${EMU_PID}" ]] && kill -0 "$EMU_PID" 2>/dev/null; then
    kill "$EMU_PID" 2>/dev/null || true
    wait "$EMU_PID" 2>/dev/null || true
  fi
  adb emu kill 2>/dev/null || true
}
trap cleanup EXIT

if ! command -v adb >/dev/null; then
  echo "adb not found on PATH" >&2
  exit 1
fi

adb start-server >/dev/null
if ! adb devices | awk 'NR>1 && $2=="device"{found=1} END{exit !found}'; then
  echo "Starting emulator $AVD_NAME (no KVM / software GPU)…"
  ACCEL_ARGS=(-accel off)
  if [[ -e /dev/kvm ]]; then
    ACCEL_ARGS=(-accel on)
    echo "Detected /dev/kvm — enabling hardware acceleration"
  fi
  nohup emulator \
    -avd "$AVD_NAME" \
    -no-window \
    -no-audio \
    -no-boot-anim \
    -gpu swiftshader_indirect \
    "${ACCEL_ARGS[@]}" \
    -memory 3072 \
    -partition-size 4096 \
    >"${TMPDIR:-/tmp}/amphora-emulator.log" 2>&1 &
  EMU_PID=$!

  echo "Waiting up to ${BOOT_TIMEOUT_SEC}s for emulator boot…"
  adb wait-for-device
  deadline=$((SECONDS + BOOT_TIMEOUT_SEC))
  until [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
    if (( SECONDS >= deadline )); then
      echo "Emulator boot timed out; last log lines:" >&2
      tail -n 80 "${TMPDIR:-/tmp}/amphora-emulator.log" >&2 || true
      exit 1
    fi
    if [[ -n "$EMU_PID" ]] && ! kill -0 "$EMU_PID" 2>/dev/null; then
      echo "Emulator process exited early; log:" >&2
      cat "${TMPDIR:-/tmp}/amphora-emulator.log" >&2 || true
      exit 1
    fi
    sleep 5
  done
  # Extra settle time for package manager / storage.
  sleep 15
fi

adb devices -l
adb shell 'wm dismiss-keyguard || true' >/dev/null 2>&1 || true
adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true

echo "Assembling APKs…"
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --stacktrace

echo "Installing APKs…"
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

# Emulator suite: remote provisioning + rootfs. Excludes Turnip / Vulkan /
# Wine-session / preparer graphics-driver coverage.
echo "Running non-graphics instrumented tests…"
timeout --signal=INT "${TEST_TIMEOUT_SEC}s" adb shell am instrument -w -r \
  -e notAnnotation app.amphora.RequiresGraphicsDriver \
  app.amphora.test/app.amphora.HiltTestRunner \
  | tee "${TMPDIR:-/tmp}/amphora-emulator-instrument.log"

if ! grep -Eq 'OK \\([1-9][0-9]* tests?\\)' "${TMPDIR:-/tmp}/amphora-emulator-instrument.log"; then
  echo "Instrumented suite did not report OK" >&2
  exit 1
fi

echo "Emulator non-graphics suite passed"
