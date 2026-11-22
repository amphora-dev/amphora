#!/usr/bin/env bash
# Run one back end of the AIO Graphics Test that is in the app's library and
# print its benchmark result (add it first: `ui.sh add aio AIO-Graphics-Test-64bit.exe`).
#
#   aio.sh <title> <vk|gl|dx7|ddraw2d|dx8|dx9|dx10|dx11|dx12> [bench seconds] [setting=value ...]
#   aio.sh AIO-Graphics-Test-64bit dx11
#   aio.sh AIO-Graphics-Test-32bit dx9 8 display.frameRate=screen
#
# The frame rate setting defaults to display.frameRate=game here: AIO asks for
# an unthrottled swapchain, so the number is what the stack can do. A number
# says frames were presented, not that the cube was drawn: look at the
# screenshot (/tmp/aio-<title>-<api>.png, taken mid-run).
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
S="${ANDROID_SERIAL:-HA262AAH}"
PKG=app.amphora
title="${1:?title}" api="${2:?api}" bench="${3:-8}"
settings=("${@:4}")
[ "${#settings[@]}" -gt 0 ] || settings=(display.frameRate=game)
dir="$(adb -s "$S" shell "run-as $PKG cat files/amphora/library.json" | python3 -c '
import json, os, sys
print(next((os.path.dirname(g["exePath"]) for g in json.load(sys.stdin)["games"] if g["title"] == sys.argv[1]), ""))' "$title")"
[ -n "$dir" ] || { echo "aio.sh: no game \"$title\" in the library" >&2; exit 1; }
results="$dir/AIO Results/Benchmark"

adb -s "$S" shell "rm -rf '$results'"
"$HERE/ui.sh" args "$title" "--cube $api --no-menu --bench $bench --autoclose 2" "${settings[@]}" || exit 1
adb -s "$S" logcat -c
# the screenshot mid-run: about 3 s to the first frame once the prefix exists and FEX has
# translated the system DLLs (the first run of a title takes longer; run it again)
"$HERE/ui.sh" play "$title" $((3 + bench / 2)) || exit 1
shot="/tmp/aio-$title-$api.png"
"$HERE/ui.sh" shot "$shot"
sleep $((bench / 2 + 8))
echo "== $title $api (${settings[*]})"
adb -s "$S" shell "cd '$results' 2>/dev/null && for f in *.txt; do echo \"\$f: \$(cat \"\$f\")  (average|lowest|highest fps)\"; done" || echo "no benchmark result"
"$HERE/ui.sh" log 400 | grep -a -E "DXVK: v|vkd3d-proton - |Device name|Driver *:|Found device|err:|Presenter|Present mode|Failed to compile" | head -n 12
adb -s "$S" logcat -d | grep -E "I wine *: vulkan:|handle_create|Fatal signal|F DEBUG" | tail -n 4
echo "screenshot: $shot"
"$HERE/ui.sh" end >/dev/null 2>&1 || true
