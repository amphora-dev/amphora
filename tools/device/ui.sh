#!/usr/bin/env bash
# Drive the app on the device from the shell, for tests that have to start a
# session the way a user does (a session needs the app: it hosts Wine's windows).
#
#   ui.sh dump                 visible texts: "<text or @content-description> <clickable> <x> <y>"
#   ui.sh tap <text>           tap the first element with exactly that text
#   ui.sh shot [file.png]      screenshot (default /tmp/shot.png)
#   ui.sh unlock               wake the device and leave the lock screen (no PIN)
#   ui.sh home                 restart the app at its library
#   ui.sh add <dir> <file.exe> add /sdcard/Download/amphora-test/<dir>/<file.exe> to the library
#   ui.sh play <title> [wait]  open the game and start it, then wait (default 12 s)
#   ui.sh end                  end the running session from its menu
#   ui.sh args <title> <args> [id=value ...]
#                              set the game's launch arguments and, optionally, other
#                              per-game settings (display.frameRate=game); replaces
#                              all of the game's settings and stops the app
#   ui.sh log [lines]          tail of the newest session log
#
# Texts are the app's Chinese labels, so this follows the UI and breaks with it.
# ANDROID_SERIAL picks the device (default: the Y700).
set -uo pipefail
S="${ANDROID_SERIAL:-HA262AAH}"
PKG=app.amphora
sh() { adb -s "$S" shell "$@"; }

dump() {
  sh 'uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml' | python3 -c '
import re, sys
for node in re.findall(r"<node [^>]*>", sys.stdin.read()):
    text = re.search(r"text=\"([^\"]*)\"", node).group(1)
    desc = re.search(r"content-desc=\"([^\"]*)\"", node).group(1)
    l, t, r, b = map(int, re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", node).groups())
    clickable = re.search(r"clickable=\"(\w+)\"", node).group(1)
    if text or desc: print(text or "@" + desc, clickable, (l + r) // 2, (t + b) // 2)'
}

position() { dump | awk -v t="$1" -F' (true|false) ' '$1 == t { print $2; exit }'; }

tap() {
  local x y
  # the UI may still be settling
  for _ in 1 2 3; do
    read -r x y < <(position "$1") || true
    if [ -n "${x:-}" ]; then sh input tap "$x" "$y"; return 0; fi
    sleep 1.5
  done
  echo "ui.sh: no \"$1\" on screen" >&2
  return 1
}

unlock() {
  sh 'input keyevent KEYCODE_WAKEUP
      if dumpsys window | grep -q "isKeyguardShowing=true"; then
        wm dismiss-keyguard; sleep 0.5; input swipe 950 1500 950 300 150; sleep 1
      fi' 2>/dev/null
}

home() { unlock; sh "am force-stop $PKG; am start -n $PKG/.MainActivity >/dev/null"; sleep 3; }

case "${1:-}" in
  dump) dump ;;
  tap) tap "$2" ;;
  shot) adb -s "$S" exec-out screencap -p > "${2:-/tmp/shot.png}" ;;
  unlock) unlock ;;
  home) home ;;
  add)
    home; tap 添加 && sleep 1.5 && tap Download && sleep 1.2 && tap amphora-test && sleep 1.2 && tap "$2" && sleep 1.2 || exit 1
    # the row's add button has no text of its own: same height, right edge
    read -r _ y < <(position "$3") || true
    [ -n "${y:-}" ] || { echo "ui.sh: no $3 in $2" >&2; exit 1; }
    read -r x _ < <(dump | awk -F' (true|false) ' '$1 == "@添加" { print $2; exit }')
    sh input tap "${x:-1749}" $((y + 21)); sleep 2 ;;
  play)
    home
    # a title below the fold: scroll the library once
    if [ -z "$(position "$2")" ]; then sh input swipe 1500 1500 1500 600 300; sleep 1.5; fi
    tap "$2" && sleep 2 && tap 开始游戏 && sleep "${3:-12}" ;;
  end) sh input keyevent KEYCODE_BACK; sleep 1.5; tap 结束会话 && sleep 2 ;;
  args)
    id="$(sh "run-as $PKG cat files/amphora/library.json" | python3 -c '
import json, sys
print(next((g["id"] for g in json.load(sys.stdin)["games"] if g["title"] == sys.argv[1]), ""))' "$2")"
    [ -n "$id" ] || { echo "ui.sh: no game \"$2\" in the library" >&2; exit 1; }
    # the user's per-game settings file, as the app writes it (the app must not be running)
    python3 -c 'import json, sys
values = {"wine.args": sys.argv[1]}
values.update(pair.split("=", 1) for pair in sys.argv[2:])
print(json.dumps({"format": 1, "values": values}))' "${@:3}" |
      sh "am force-stop $PKG; run-as $PKG sh -c 'mkdir -p files/amphora/settings/games; cat > files/amphora/settings/games/$id.json'" ;;
  log) sh "run-as $PKG sh -c 'cd files/amphora/logs; f=\$(ls | tail -1); echo \$f; grep -v \"amphora: env\" \$f | tail -${2:-40}'" ;;
  *) sed -n '2,20p' "$0" >&2; exit 2 ;;
esac
