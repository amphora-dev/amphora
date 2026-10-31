#!/usr/bin/env bash
# background-check.sh <serial> <api> [64|32]
#
# Sends a running AIO Graphics Test session to the background and back, and
# checks that the guest survives and its swapchain keeps presenting (docs/04
# §3: client layers park while the layer root is gone). AIO runs as
#   --cube <api> --no-menu --bench $BENCH --autoclose 2
# with the same preparation as aio-run.sh (stay-on, unlock, Wine log =
# errors, cleared logcat / timestats / AIO results).
#
# Steps: wait for the guest pid and the first AHB swapchain, +5 s, read the
# game layer's SurfaceFlinger totalFrames (T0); screen off (KEYCODE_SLEEP);
# $BG_S s with a per-second guest pid check; screen on (WAKEUP +
# dismiss-keyguard); +10 s, screenshot, totalFrames (T1); wait for AIO to
# finish its benchmark and exit. Files: $OUT_DIR/<serial>-<bits>-<api>-power.*
# (default .tmp/background-check/ in the repo root).
#
# Only screen off/on: after HOME, the launcher intent resumes MainActivity's
# task, not the session's (WineAndroidSessionActivity is singleTask,
# excludeFromRecents and not exported, so it cannot be started directly).
#
# The last line is the verdict:
#   RESULT <serial> <bits> <api> power alive=<yes|no> acq_err=<n> parked=<n>
#     unparked_ms=<ms|none> dframes=<T1-T0>/<need> bench=<fps|none> fatal=<n>
#     tombstones=<new> verdict=<PASS|FAIL>
# need = 5 s × $REFRESH Hz × 0.9 (REFRESH defaults to 60, the 6T).
set -uo pipefail

S=${1:?usage: background-check.sh <serial> <api> [64|32]}
API=${2:?usage: background-check.sh <serial> <api> [64|32]}
BITS=${3:-64}
MODE=power
BENCH=${BENCH:-60}
REFRESH=${REFRESH:-60}
# AIO exits on its own after 12 s without a new frame (src/watchdog.c,
# ExitProcess(3)), and a parked swapchain renders nothing, so stay below it.
BG_S=${BG_S:-8}
PKG=app.amphora
EXE="C:/ProgramData/Microsoft/Windows/Graphics-Test-${BITS}bit.exe"
ARGS="--cube $API --no-menu --bench $BENCH --autoclose 2"
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT_DIR=${OUT_DIR:-$ROOT/.tmp/background-check}
OUT=$OUT_DIR/$S-$BITS-$API-$MODE
NEED=$((5 * REFRESH * 9 / 10))

case $API in
  vk | gl | dx7 | ddraw2d | dx8 | dx9 | dx10 | dx11 | dx12) ;;
  *)
    echo "api must be one of vk gl dx7 ddraw2d dx8 dx9 dx10 dx11 dx12" >&2
    exit 2
    ;;
esac
case $BITS in
  64 | 32) ;;
  *)
    echo "bits must be 64 or 32" >&2
    exit 2
    ;;
esac

a() { adb -s "$S" "$@" </dev/null; }
now() { date +%s; }
log() { echo "[$(date +%H:%M:%S)] $*"; }
# Highest totalFrames among the amphora-client-* layers in a timestats dump.
client_frames() {
  a shell dumpsys SurfaceFlinger --timestats -dump | tr -d '\r' | tee "$1" |
    awk '/^layerName =/{n=$3" "$4} /^totalFrames =/{t=$3}
      /^averageFPS =/{if(n ~ /amphora-client-/) print t}' | sort -rn | head -1
}
wait_log() { # <regex> <seconds>
  local i
  for i in $(seq 1 "$2"); do
    a logcat -d | grep -qE "$1" && return 0
    sleep 1
  done
  return 1
}
mkdir -p "$OUT_DIR"

tomb_before=$(a shell "ls -l /data/tombstones" 2>/dev/null | tr -d '\r' | sort)

# --- same preparation as aio-run.sh ---
a shell am force-stop "$PKG"
a shell svc power stayon usb
a shell input keyevent KEYCODE_WAKEUP
a shell wm dismiss-keyguard
prefs_script=$(mktemp)
cat >"$prefs_script" <<PREFS
f=shared_prefs/amphora_graphics.xml
mkdir -p shared_prefs
[ -f \$f ] || printf '%s\\n%s\\n' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" '<map>' '</map>' >\$f
sed -i '/name="advanced_wine_debug"/d' \$f
sed -i 's#</map>#    <string name="advanced_wine_debug">${AIO_WINE_LOG:-errors}</string>\\n</map>#' \$f
PREFS
adb -s "$S" push "$prefs_script" /data/local/tmp/aio-prefs.sh >/dev/null
rm -f "$prefs_script"
a shell "run-as $PKG sh /data/local/tmp/aio-prefs.sh"
a shell "run-as $PKG sh -c 'rm -rf \"files/imagefs/AIO Results\"; : > files/wine_stderr.log'" 2>/dev/null
a shell dumpsys SurfaceFlinger --timestats -disable -clear >/dev/null
a logcat -c
a shell dumpsys SurfaceFlinger --timestats -enable -clear >/dev/null
a shell am start -n "$PKG/.MainActivity" \
  --ez app.amphora.debug.WINEANDROID true \
  --es app.amphora.debug.WINE_EXE "$EXE" \
  --es app.amphora.debug.WINE_ARGS "'$ARGS'" >/dev/null
# --- end of aio-run.sh preparation ---

pid=
for _ in $(seq 1 120); do
  pid=$(a logcat -d -s WineAndroidLauncher:I | grep -oE 'guest running pid=[0-9]+' | tail -1 | cut -d= -f2)
  [[ -n $pid ]] && break
  a logcat -d | grep -qE 'prepare failed|FATAL EXCEPTION' && break
  sleep 1
done
alive() { a shell "ps -A -o PID" | grep -qw "$pid"; }
fail_early() {
  log "$1"
  a logcat -d >"$OUT.log"
  a shell "run-as $PKG cat files/wine_stderr.log" >"$OUT.wine.log" 2>/dev/null
  a shell am force-stop "$PKG"
  echo "RESULT $S $BITS $API $MODE alive=no acq_err=- parked=- unparked_ms=none dframes=0/$NEED bench=none fatal=- tombstones=- verdict=FAIL ($1)"
  exit 1
}
[[ -n $pid ]] || fail_early "no guest pid"
log "guest pid=$pid"
wait_log 'AHB_SC create images=' 60 || fail_early "no AHB swapchain"
sleep 5
a exec-out screencap -p >"$OUT-before.png"
t0=$(client_frames "$OUT-t0.ts")
log "T0 totalFrames=${t0:-none}"

a shell input keyevent KEYCODE_SLEEP
t_bg=$(now)
if wait_log 'WineAndroidDesktop: layer root destroyed' 5; then
  log "layer root destroyed after $(($(now) - t_bg)) s"
else
  log "no 'layer root destroyed' within 5 s"
fi

died_at=
for i in $(seq 1 "$BG_S"); do
  sleep 1
  if [[ -z $died_at ]] && ! alive; then died_at=$i; fi
  if [[ $i -eq $((BG_S / 2)) ]]; then
    a shell "ps -A -o PID,S,NAME" | grep -E 'box64|wine' | tr -d '\r' >"$OUT-bg-ps.txt"
  fi
done
if [[ -n $died_at ]]; then log "guest gone after ${died_at} s in background"; else log "guest alive for ${BG_S} s in background"; fi

a shell input keyevent KEYCODE_WAKEUP
a shell wm dismiss-keyguard
t_fg=$(now)
sleep 2
a shell dumpsys activity activities | tr -d '\r' |
  grep -E 'ResumedActivity|topResumedActivity' >"$OUT-resumed.txt"
returned=no
# The session start already logged one "layer root created".
for _ in $(seq 1 10); do
  if [[ $(a logcat -d | grep -c 'WineAndroidDesktop: layer root created') -ge 2 ]]; then
    returned=yes
    break
  fi
  sleep 1
done
if [[ $returned == yes ]]; then
  log "layer root created again after $(($(now) - t_fg)) s"
else
  log "session did not come back to the foreground: $(head -1 "$OUT-resumed.txt")"
fi
sleep 10
a exec-out screencap -p >"$OUT-after.png"
t1=$(client_frames "$OUT-t1.ts")
log "T1 totalFrames=${t1:-none}"

exited=
for _ in $(seq 1 $((BENCH + 120))); do
  if ! alive; then
    exited=1
    break
  fi
  sleep 1
done
sleep 1
a shell dumpsys SurfaceFlinger --timestats -disable >/dev/null
a logcat -d >"$OUT.log"
a shell "run-as $PKG cat files/wine_stderr.log" >"$OUT.wine.log" 2>/dev/null
a shell "run-as $PKG cat 'files/imagefs/AIO Results/Benchmark/AIO-Graphics-Test_bench.csv'" 2>/dev/null | tr -d '\r' >"$OUT.csv"
a shell am force-stop "$PKG"
tomb_after=$(a shell "ls -l /data/tombstones" 2>/dev/null | tr -d '\r' | sort)
tombs=$(comm -13 <(printf '%s\n' "$tomb_before") <(printf '%s\n' "$tomb_after") | grep -c tombstone)

echo "== $S $BITS-bit $API $MODE pid=$pid exited=${exited:-no} returned=$returned (files $OUT*)"
grep -E 'layer root (destroyed|created)|parkSurface|parked|unparked|AHB_SC acquire dequeue ret=|buffer serve exit|registerSurface hwnd=.*same surface' "$OUT.log" |
  sed -E 's/^[0-9-]+ //' | cut -c1-200 | head -30
grep -oE 'AHB_SC destroy .*' "$OUT.log" | sed -E 's/ sc=0x[0-9a-f]+//' | tail -2
grep -E '^# (AIO|frames|avg)' "$OUT.csv" | sed 's/^# //' | paste -sd ' ' -

acq_err=$(grep -c 'AHB_SC acquire dequeue ret=' "$OUT.log")
parked=$(grep -cE 'serve DEQUEUE .*parked' "$OUT.log")
unparked_ms=$(grep -oE 'unparked after [0-9]+' "$OUT.log" | awk '{print $3}' | sort -rn | head -1)
no_anw=$(grep -c 'no client ANW after wait' "$OUT.wine.log")
bridges=$(grep -c 'amphora PE WSI bridge' "$OUT.wine.log")
fatal=$(grep -cE 'FATAL EXCEPTION|Fatal signal' "$OUT.log")
bench=$(grep -oE 'avg_fps,[0-9.]+' "$OUT.csv" | cut -d, -f2)
dframes=$((${t1:-0} - ${t0:-0}))
echo "wine log: 'no client ANW after wait'=$no_anw 'amphora PE WSI bridge'=$bridges"

verdict=PASS
[[ -z $died_at ]] || verdict=FAIL
[[ $acq_err -eq 0 && $parked -ge 1 && -n $unparked_ms ]] || verdict=FAIL
[[ $returned == yes && $dframes -ge $NEED ]] || verdict=FAIL
[[ -n $bench && $fatal -eq 0 && $tombs -eq 0 && $no_anw -eq 0 ]] || verdict=FAIL
echo "RESULT $S $BITS $API $MODE alive=$([[ -z $died_at ]] && echo yes || echo "no@${died_at}s")" \
  "acq_err=$acq_err parked=$parked unparked_ms=${unparked_ms:-none} dframes=$dframes/$NEED" \
  "bench=${bench:-none} fatal=$fatal tombstones=$tombs verdict=$verdict"
