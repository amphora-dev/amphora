#!/usr/bin/env bash
# Benchmark one back end of the AIO Graphics Test and print its result, the
# versions in use and a screenshot taken mid-run. For "does it draw" use
# smoke.sh, which runs it for 2 seconds; this one is for the number.
#
#   aio.sh <64|32> <vk|gl|dx7|ddraw2d|dx8|dx9|dx10|dx11|dx12> [bench seconds] [setting=value ...]
#   aio.sh 64 dx11
#   aio.sh 32 dx9 8 display.frameRate=60
#
# The frame rate setting defaults to display.frameRate=game here: AIO asks for
# an unthrottled swapchain, so the number is what the stack can do. DXVK's and
# vkd3d-proton's log is on (debug.dxvkLog=info) to show their versions and the
# driver; that costs nothing per frame. A number says frames were presented,
# not that the cube was drawn: the script checks the screenshot for it.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
RUN="$HERE/run.sh"
S="${ANDROID_SERIAL:-HA262AAH}"
bits="${1:?64 or 32}" api="${2:?back end}" bench="${3:-8}"
settings=("${@:4}")
[ "${#settings[@]}" -gt 0 ] || settings=(display.frameRate=game)
results='/sdcard/Download/amphora-test/aio/AIO Results/Benchmark'
shot="/tmp/aio-$bits-$api.png"

adb -s "$S" shell "rm -rf '$results'"
"$RUN" start "aio/AIO-Graphics-Test-${bits}bit.exe" "--cube $api --no-menu --bench $bench --autoclose 1" \
  "${settings[@]}" debug.dxvkLog=info || exit 1
echo "== $bits-bit $api (${settings[*]})"
if "$RUN" wait-log 'wineandroid.drv: handle_create' 30; then
  sleep $((bench / 2))
  "$RUN" shot "$shot"
  "$RUN" drawn "$shot" || echo "THE CUBE IS NOT DRAWN: $shot"
  "$RUN" wait-any "$results" '*.txt' $((bench + 20)) | sed 's/$/  (average|lowest|highest fps)/' || echo "no benchmark result"
else
  echo "no swapchain was created"
fi
"$RUN" log 400 | grep -a -E "DXVK: v|vkd3d-proton - build|Found device|Present mode|err:|Failed to compile" | sort -u | head -n 10
adb -s "$S" logcat -d | grep -E "I wine *: vulkan:|Fatal signal|F DEBUG" | sed 's/.*I wine *: //' | sort -u | tail -n 3
echo "screenshot: $shot"
"$RUN" end
