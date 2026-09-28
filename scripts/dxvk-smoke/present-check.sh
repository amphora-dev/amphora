#!/usr/bin/env bash
# present-check.sh <serial> [30|45|60|90|120|off]
#
# Runs amphora-dxvk-smoke.exe (build and push: see amphora-dxvk-smoke.c) in a
# wineandroid session with SurfaceFlinger timestats enabled, waits until the
# swapchain that presented is destroyed, and prints what docs/05 §5 checks:
# guest readback CLASS, the AHB_SC destroy counters (presents / fenced /
# acquires / imported), the game layer's SF totalFrames, FATAL and
# prepare-failed counts, and the Proton build seen in the log.
#
# The limit goes into shared_prefs/amphora_graphics.xml as advanced_frame_rate
# ("off" removes it); the other prefs are kept and the original file is put
# back on exit. Full logcat and timestats land in $OUT_DIR
# (default: .tmp/present-check/ in the repo root).
set -uo pipefail

S=${1:?usage: present-check.sh <serial> [30|45|60|90|120|off]}
LIMIT=${2:-off}
PKG=app.amphora
PREFS=shared_prefs/amphora_graphics.xml
EXE=/data/user/0/$PKG/files/exe/amphora-dxvk-smoke.exe
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT_DIR=${OUT_DIR:-$ROOT/.tmp/present-check}
OUT=$OUT_DIR/$S-$LIMIT.log
# DXVK may or may not create and drop an empty swapchain first, so wait for
# the destroy line of one that presented rather than counting destroys.
DONE_RE='AHB_SC destroy .*presents=[1-9]|prepare failed|FATAL EXCEPTION|Fatal signal'
WAIT_S=240

case $LIMIT in
  off | 30 | 45 | 60 | 90 | 120) ;;
  *)
    echo "limit must be one of 30 45 60 90 120 off" >&2
    exit 2
    ;;
esac

a() { adb -s "$S" "$@" </dev/null; }
write_prefs() { adb -s "$S" shell "run-as $PKG sh -c 'cat > $PREFS'"; }

if ! a shell "run-as $PKG ls files/exe/amphora-dxvk-smoke.exe" >/dev/null 2>&1; then
  echo "$S: files/exe/amphora-dxvk-smoke.exe is missing; push it first (see amphora-dxvk-smoke.c)" >&2
  exit 2
fi
mkdir -p "$OUT_DIR"

ORIG=$(a shell "run-as $PKG cat $PREFS 2>/dev/null" | tr -d '\r')
restore_prefs() {
  a shell am force-stop "$PKG"
  if [[ -n $ORIG ]]; then
    printf '%s\n' "$ORIG" | write_prefs
  else
    a shell "run-as $PKG rm -f $PREFS"
  fi
}

a shell am force-stop "$PKG"
{
  echo "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
  echo "<map>"
  if [[ -n $ORIG ]]; then
    printf '%s\n' "$ORIG" | grep -vE '^<\?xml|^ *<map|^ *</map>|name="advanced_frame_rate"'
  fi
  if [[ $LIMIT != off ]]; then echo "    <string name=\"advanced_frame_rate\">$LIMIT</string>"; fi
  echo "</map>"
} | write_prefs
trap restore_prefs EXIT

# The screen must be on and unlocked, otherwise the Activity has no Surface.
a shell svc power stayon usb
a shell input keyevent KEYCODE_WAKEUP
a shell wm dismiss-keyguard
a shell dumpsys SurfaceFlinger --timestats -disable -clear >/dev/null
a logcat -c
a shell dumpsys SurfaceFlinger --timestats -enable -clear >/dev/null
a shell am start -n "$PKG/.MainActivity" \
  --ez app.amphora.debug.WINEANDROID true \
  --es app.amphora.debug.WINE_EXE "$EXE" >/dev/null

# logcat -e/-m exits on the first matching line (buffered lines included).
# Run adb itself in the background (not a function) so $! is adb's pid.
adb -s "$S" logcat -e "$DONE_RE" -m 1 </dev/null >/dev/null 2>&1 &
waiter=$!
for _ in $(seq 1 "$WAIT_S"); do
  kill -0 "$waiter" 2>/dev/null || break
  sleep 1
done
if kill -0 "$waiter" 2>/dev/null; then
  kill "$waiter" 2>/dev/null
  echo "$S: no AHB_SC destroy with presents>0 within ${WAIT_S}s" >&2
fi
wait "$waiter" 2>/dev/null
sleep 1
a shell dumpsys SurfaceFlinger --timestats -dump | tr -d '\r' >"$OUT.ts"
a shell dumpsys SurfaceFlinger --timestats -disable >/dev/null
a logcat -d >"$OUT"

echo "== $S limit=$LIMIT (${SECONDS}s, log $OUT)"
grep -E 'guest-readback' "$OUT" | grep -oE '^[0-9-]+ [0-9:.]+|queue_n=[0-9]+|CLASS=[A-Z]+' | paste - - - | head -2
grep -oE 'AHB_SC destroy .*' "$OUT" | tail -1
grep -oE 'AHB_SC acquire: .*' "$OUT" | sort -u | head -2
grep -oE 'serve DEQUEUE .*release_fences=[0-9]+' "$OUT" | tail -1
echo "fatal=$(grep -cE 'FATAL EXCEPTION|Fatal signal' "$OUT") prepare_failed=$(grep -c 'prepare failed' "$OUT")"
awk '/^layerName =/{n=$3" "$4} /^totalFrames =/{t=$3} /^droppedFrames =/{d=$3}
  /^averageFPS =/{if(n ~ /amphora-client-/) print "sf totalFrames="t" dropped="d" fps="$3}' "$OUT.ts" |
  sort -t= -k2 -rn | head -1
grep -oE 'Proton-[0-9.]+-[0-9a-f]{9}' "$OUT" | sort | uniq -c | sort -rn | head -2
