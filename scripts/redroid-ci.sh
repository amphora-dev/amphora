#!/usr/bin/env bash
# Run Amphora's ARM64 instrumentation + visible Wine smoke test on ReDroid.
set -euo pipefail

if [[ $# -ne 3 ]]; then
  echo "usage: $0 <app.apk> <android-test.apk> <artifact-dir>" >&2
  exit 2
fi

APP_APK="$(realpath "$1")"
TEST_APK="$(realpath "$2")"
ARTIFACT_DIR="$(realpath -m "$3")"
SERIAL="${ANDROID_SERIAL:-127.0.0.1:5555}"
CONTAINER="${REDROID_CONTAINER:-redroid15}"
mkdir -p "$ARTIFACT_DIR"

cleanup() {
  set +e
  adb -s "$SERIAL" logcat -d -v threadtime >"$ARTIFACT_DIR/logcat.txt" 2>&1
  adb -s "$SERIAL" shell \
    "run-as app.amphora cat files/wine_stderr.log" \
    >"$ARTIFACT_DIR/wine_stderr.log" 2>&1
  adb -s "$SERIAL" shell dumpsys window >"$ARTIFACT_DIR/window.txt" 2>&1
  docker stats --no-stream "$CONTAINER" >"$ARTIFACT_DIR/docker-stats.txt" 2>&1
  adb -s "$SERIAL" shell am force-stop app.amphora >/dev/null 2>&1
}
trap cleanup EXIT

if [[ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null || true)" != true ]]; then
  docker start "$CONTAINER"
fi

boot_completed=
for _ in $(seq 1 60); do
  boot_completed="$(docker exec "$CONTAINER" getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  [[ "$boot_completed" == 1 ]] && break
  sleep 5
done
[[ "$boot_completed" == 1 ]] || {
  echo "ReDroid failed to boot" >&2
  exit 1
}

docker exec "$CONTAINER" wm size | tee "$ARTIFACT_DIR/display.txt"
docker exec "$CONTAINER" wm density | tee -a "$ARTIFACT_DIR/display.txt"
grep -q '1920x1200' "$ARTIFACT_DIR/display.txt"

adb kill-server >/dev/null 2>&1 || true
adb connect "$SERIAL"
adb -s "$SERIAL" wait-for-device
adb -s "$SERIAL" shell settings put secure immersive_mode_confirmations confirmed || true

adb -s "$SERIAL" install -r -t "$APP_APK"
adb -s "$SERIAL" install -r -t "$TEST_APK"

# Dedicated CI device: System Vulkan exercises non-Adreno host composition.
PREFS_XML='<?xml version="1.0" encoding="utf-8" standalone="yes" ?>
<map>
    <string name="adrenotools_driver_id">System</string>
    <string name="display_resolution">R1920x1200</string>
    <string name="directdraw_wrapper_id">dd7to9</string>
    <string name="advanced_wine_debug">errors</string>
    <boolean name="enable_emulator_logs" value="true" />
</map>'
PREFS_B64="$(printf '%s\n' "$PREFS_XML" | base64 -w0)"
adb -s "$SERIAL" shell \
  "run-as app.amphora sh -c 'mkdir -p shared_prefs; echo $PREFS_B64 | base64 -d > shared_prefs/amphora_graphics.xml; chmod 660 shared_prefs/amphora_graphics.xml'"

adb -s "$SERIAL" logcat -c
set +e
adb -s "$SERIAL" shell am instrument -w -r \
  -e class \
  app.amphora.AlsaRuntimeSupportTest,app.amphora.GameSessionLaunchTest,app.amphora.ImagefsExtractionTest,app.amphora.PreparerGraphicsDriverTest,app.amphora.RemoteContentSourceTest,app.amphora.SharedDllLinkerTest,app.amphora.XServerSurfaceViewInitTest \
  app.amphora.test/app.amphora.HiltTestRunner \
  | tee "$ARTIFACT_DIR/instrumentation.txt"
instrument_rc=${PIPESTATUS[0]}
set -e
if (( instrument_rc != 0 )) ||
  grep -q 'FAILURES!!!' "$ARTIFACT_DIR/instrumentation.txt" ||
  ! grep -q 'OK (9 tests)' "$ARTIFACT_DIR/instrumentation.txt"; then
  echo "Instrumentation suite failed" >&2
  exit 1
fi

# The debug APK exposes a guarded Intent route to the deterministic Wine PE.
# Release builds ignore these extras; no coordinate-based UI automation.
adb -s "$SERIAL" logcat -c
adb -s "$SERIAL" shell am force-stop app.amphora
adb -s "$SERIAL" shell am start -W \
  -n app.amphora/.MainActivity \
  --ez app.amphora.debug.WINE_SMOKE true \
  --ei app.amphora.debug.WIDTH 1920 \
  --ei app.amphora.debug.HEIGHT 1200 \
  >/dev/null
sleep 15

adb -s "$SERIAL" shell \
  "run-as app.amphora ps -A" \
  | tee "$ARTIFACT_DIR/processes.txt"
grep -q 'wine-smoke-test.exe' "$ARTIFACT_DIR/processes.txt"
grep -q 'wineserver' "$ARTIFACT_DIR/processes.txt"

adb -s "$SERIAL" logcat -d -v threadtime >"$ARTIFACT_DIR/wine-logcat.txt"
grep -q 'guestExecutable=wine explorer /desktop=shell,1920x1200' \
  "$ARTIFACT_DIR/wine-logcat.txt"
grep -q 'Swapchain surface=1920x1200 extent=1920x1200' \
  "$ARTIFACT_DIR/wine-logcat.txt"
if grep -Eqi 'SIGSEGV|fdsan|FATAL EXCEPTION|Fatal signal' \
  "$ARTIFACT_DIR/wine-logcat.txt"; then
  echo "Fatal error found in Wine session logcat" >&2
  exit 1
fi

adb -s "$SERIAL" shell \
  "run-as app.amphora cat files/wine_stderr.log" \
  >"$ARTIFACT_DIR/wine_stderr-live.log" 2>&1
if grep -qi 'SIGSEGV' "$ARTIFACT_DIR/wine_stderr-live.log"; then
  echo "Box64 SIGSEGV found in Wine stderr" >&2
  exit 1
fi

adb -s "$SERIAL" shell uiautomator dump /sdcard/amphora-ci.xml >/dev/null
adb -s "$SERIAL" shell cat /sdcard/amphora-ci.xml >"$ARTIFACT_DIR/ui.xml"
adb -s "$SERIAL" exec-out screencap -p >"$ARTIFACT_DIR/wine-fullscreen.png"
file "$ARTIFACT_DIR/wine-fullscreen.png" | tee "$ARTIFACT_DIR/screenshot.txt"
grep -q 'PNG image data, 1920 x 1200' "$ARTIFACT_DIR/screenshot.txt"

echo "ReDroid ARM64 test passed"
