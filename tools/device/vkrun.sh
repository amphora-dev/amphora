#!/usr/bin/env bash
# Run a vktest build that is in the app's library with the given arguments and
# print its report (tools/vktest; add it first with `ui.sh add vk vktest64.exe`).
#
#   vkrun.sh <title> "<vktest arguments>" [wait seconds] [screenshot.png] [setting=value ...]
#   vkrun.sh vktest64 "--window --frames 1500 --mode fifo" 18 "" display.frameRate=game
#
# The frame rate setting decides how a swapchain presents (default: every frame
# shown, whatever --mode asks for); display.frameRate=game leaves it to --mode.
# The report comes back through --log: a session started by the app has no
# stdout. Also prints what the driver's host side logged, and crashes.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
S="${ANDROID_SERIAL:-HA262AAH}"
PKG=app.amphora
LOG='Z:\data\user\0\app.amphora\cache\vktest.log'

adb -s "$S" shell "run-as $PKG rm -f cache/vktest.log"
"$HERE/ui.sh" args "$1" "$2 --log $LOG" "${@:5}" || exit 1
adb -s "$S" logcat -c
"$HERE/ui.sh" play "$1" "${3:-12}" || exit 1
if [ -n "${4:-}" ]; then "$HERE/ui.sh" shot "$4"; fi
adb -s "$S" shell "run-as $PKG cat cache/vktest.log 2>/dev/null" || true
adb -s "$S" logcat -d | grep -E "[IE] wineandroid.drv|I wine *: vulkan:|Fatal signal|F DEBUG" | tail -n 8
"$HERE/ui.sh" end >/dev/null 2>&1 || true
