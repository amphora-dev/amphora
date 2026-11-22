#!/usr/bin/env bash
# Run a vktest build (tools/vktest) with the given arguments and print its report.
#
#   vkrun.sh <arm64|x64|x86> "<vktest arguments>" [screenshot.png] [setting=value ...]
#   vkrun.sh x64 "--window --frames 1500 --mode mailbox" "" display.frameRate=game
#
# The frame rate setting decides how a swapchain presents (default: every frame
# shown, whatever --mode asks for); display.frameRate=game leaves it to --mode.
# The report comes back through --log: a session started by the app has no
# stdout. Also prints what the driver's host side logged, and crashes. A
# screenshot is taken two seconds after the swapchain exists.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
RUN="$HERE/run.sh"
S="${ANDROID_SERIAL:-HA262AAH}"
case "${1:?arm64, x64 or x86}" in
  arm64) exe=vk/vktest-arm64.exe ;;
  x64) exe=vk/vktest64.exe ;;
  x86) exe=vk/vktest32.exe ;;
  *) echo "vkrun.sh: arm64, x64 or x86" >&2; exit 2 ;;
esac

adb -s "$S" shell "run-as app.amphora rm -f cache/vktest.log"
"$RUN" start "$exe" "${2:-} --log Z:\\data\\user\\0\\app.amphora\\cache\\vktest.log" "${@:4}" || exit 1
if [ -n "${3:-}" ] && "$RUN" wait-log 'wineandroid.drv: handle_create' 20; then
  sleep 2
  "$RUN" shot "$3"
fi
# a windowed run takes as long as its --frames
"$RUN" wait-line cache/vktest.log '^(PASS|FAIL)' 120 || echo "vkrun.sh: no verdict within 120 s"
"$RUN" cat cache/vktest.log
adb -s "$S" logcat -d | grep -E "[IE] wineandroid.drv|I wine *: vulkan:|Fatal signal|F DEBUG" | tail -n 8
"$RUN" end
