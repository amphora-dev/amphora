#!/usr/bin/env bash
# Start and end sessions on the device without going through the app's screens:
# the debug build has an activity for it (app/src/debug, RunActivity), which does
# through the SDK what a user does with taps. Everything here returns as soon as
# what it waits for has happened; nothing sleeps a fixed time.
#
#   run.sh start <exe> ["<arguments>"] [setting=value ...]
#                              start <exe> as a game (added to the library when it is not
#                              there) with exactly these per-game settings; ends a session
#                              that is still running first. <exe> is a path on the device;
#                              a relative one is under /sdcard/Download/amphora-test/.
#   run.sh end                 end the running session
#   run.sh wait-line <file> <regex> [seconds]
#                              wait until a file in the app's data directory has a line
#                              matching <regex> (default 30 s); fails when it does not
#   run.sh wait-any <directory> <glob> [seconds]
#                              wait until a file matching <glob> is in a directory on shared
#                              storage, then print its name and content
#   run.sh wait-log <regex> [seconds]
#                              wait for a line in the system log since the last start
#   run.sh wait-exit [seconds] wait until the session is gone by itself (its program ended)
#   run.sh cat <file>          print a file in the app's data directory
#   run.sh log [lines]         tail of the newest session log (without the environment)
#   run.sh shot <file.png>     screenshot
#   run.sh drawn <file.png>    whether a screenshot shows AIO's cube: prints the share of
#                              the screen in the saturated colors of its faces; fails below 3%
#
# A session needs the screen: `start` wakes the device and leaves the lock screen
# (no PIN). ANDROID_SERIAL picks the device (default: the Y700).
set -uo pipefail
S="${ANDROID_SERIAL:-HA262AAH}"
PKG=app.amphora
ROOT=/sdcard/Download/amphora-test
sh() { adb -s "$S" shell "$@"; }

# poll <seconds> <device command that succeeds when done>
poll() {
  sh "i=0; while [ \$i -lt $(($1 * 5)) ]; do if $2; then exit 0; fi; sleep 0.2; i=\$((i+1)); done; exit 1"
}

case "${1:-}" in
  start)
    exe="${2:?run.sh start <exe>}"
    case "$exe" in /*) ;; *) exe="$ROOT/$exe" ;; esac
    set_extra="$(IFS='|'; echo "${*:4}")"
    sh 'input keyevent KEYCODE_WAKEUP
        if dumpsys window | grep -q "isKeyguardShowing=true"; then
          wm dismiss-keyguard; sleep 0.3; input swipe 950 1500 950 300 150; sleep 0.7
        fi' 2>/dev/null
    adb -s "$S" logcat -c
    # single quotes for the device's shell; an argument with one in it is not supported
    sh "am start -n $PKG/.debug.RunActivity --es exe '$exe' --es args '${3:-}' --es set '$set_extra'" >/dev/null
    if ! poll 60 "logcat -d -s AmphoraRun | grep -q -e started -e failed"; then
      echo "run.sh: the app did not answer (is the debug build installed?)" >&2; exit 1
    fi
    # the first start on a runtime builds the prefix template, hence the long limit
    line="$(sh "logcat -d -s AmphoraRun" | grep -e started -e failed | tail -n 1 | sed 's/.*AmphoraRun: //')"
    echo "$line"
    case "$line" in started*) ;; *) exit 1 ;; esac ;;
  end)
    adb -s "$S" logcat -c
    sh "am start -n $PKG/.debug.RunActivity --es end 1" >/dev/null
    poll 20 "logcat -d -s AmphoraRun | grep -q ended" || { echo "run.sh: the session did not end" >&2; exit 1; } ;;
  wait-line)
    poll "${4:-30}" "run-as $PKG grep -q -E '${3:?regex}' '${2:?file}' 2>/dev/null" ;;
  wait-any)
    poll "${4:-30}" "ls '${2:?directory}'/${3:?glob} >/dev/null 2>&1" || exit 1
    sh "cd '$2' && for f in $3; do echo \"\$f: \$(cat \"\$f\")\"; done" ;;
  wait-log)
    poll "${3:-30}" "logcat -d | grep -q -E '${2:?regex}'" ;;
  wait-exit)
    poll "${2:-15}" "! pidof $PKG:wine >/dev/null" ;;
  cat) sh "run-as $PKG cat '${2:?file}'" ;;
  log) sh "run-as $PKG sh -c 'cd files/amphora/logs; f=\$(ls | tail -1); echo \$f; grep -v \"amphora: env\" \$f | tail -${2:-40}'" ;;
  shot) adb -s "$S" exec-out screencap -p > "${2:?file.png}" ;;
  drawn)
    # sips ships with macOS; a BMP is the simplest thing to read without an image library
    bmp="$(mktemp -t amphora-shot).bmp"
    sips -Z 400 -s format bmp "${2:?file.png}" --out "$bmp" >/dev/null || exit 1
    python3 - "$bmp" <<'PY'
import struct, sys
data = open(sys.argv[1], "rb").read()
offset, = struct.unpack_from("<I", data, 10)
width, height, _, bits = struct.unpack_from("<iiHH", data, 18)
step, height = bits // 8, abs(height)
row = (width * step + 3) & ~3
vivid = 0
for y in range(height):
    line = data[offset + y * row: offset + y * row + width * step]
    for x in range(0, len(line), step):
        b, g, r = line[x], line[x + 1], line[x + 2]
        # The cube's faces are saturated colors. The desktop is a dull teal and the window's
        # background near black; the active title bar, (51, 153, 255), is the one other vivid thing.
        if max(r, g, b) > 180 and max(r, g, b) - min(r, g, b) > 100:
            vivid += not (abs(r - 51) < 30 and abs(g - 153) < 30 and b > 225)
share = 100.0 * vivid / (width * height)
print(f"the cube covers {share:.1f}% of the screen")
sys.exit(0 if share >= 3.0 else 1)
PY
    status=$?; rm -f "$bmp"; exit $status ;;
  *) sed -n '2,28p' "$0" >&2; exit 2 ;;
esac
