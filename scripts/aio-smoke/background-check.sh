#!/usr/bin/env bash
# background-check.sh <serial> <api|desktop> [64|32]
#
# Sends a running session to the background and back, and checks that the
# guest survives and draws again (docs/04 §3.4: client layers park while the
# layer root is gone, GDI layers unregister and re-register).
#
# <api> runs AIO Graphics Test as
#   --cube <api> --no-menu --bench $BENCH --autoclose 2
# with the same preparation as aio-run.sh (stay-on, unlock, Wine log =
# errors, cleared logcat / timestats / AIO results). `desktop` opens the
# Windows desktop (Explorer, GDI only) from the launcher UI instead: launcher
# intent, then "Windows Desktop" and its open button, found with uiautomator.
#
# MODE (env) picks how the session leaves and comes back:
#   power  screen off (KEYCODE_SLEEP), then WAKEUP + dismiss-keyguard
#   home   KEYCODE_HOME, then the launcher intent (what tapping the icon
#          sends: MAIN/LAUNCHER, flags 0x10200000)
#
# Steps: wait for the guest pid and the first AHB swapchain (desktop: the
# first registerSurface), +5 s, read the game layer's SurfaceFlinger
# totalFrames (T0); leave for $BG_S s with a per-second guest pid check;
# come back; +10 s, screenshot, totalFrames (T1); wait for AIO to finish its
# benchmark and exit (desktop: force-stop). logcat is streamed to a file from
# the start, because Wine fills a 256 KiB ring buffer (Y700) within seconds.
# Files: $OUT_DIR/<serial>-<bits>-<api>-<mode>.* (default
# .tmp/background-check/ in the repo root).
#
# The last line is the verdict:
#   RESULT <serial> <bits> <api> <mode> alive=<yes|no> returned=<yes|no>
#     acq_err=<n> parked=<n> unparked_ms=<ms|none> dframes=<T1-T0>/<need>
#     rereg=<n> bench=<fps|none> fatal=<n> tombstones=<new> verdict=<PASS|FAIL>
# need = 5 s × $REFRESH Hz × 0.9 (REFRESH defaults to 60, the 6T). rereg is
# the GDI `registerSurface … ok=true` count after coming back (desktop only).
set -uo pipefail

S=${1:?usage: background-check.sh <serial> <api|desktop> [64|32]}
API=${2:?usage: background-check.sh <serial> <api|desktop> [64|32]}
BITS=${3:-64}
MODE=${MODE:-power}
BENCH=${BENCH:-60}
REFRESH=${REFRESH:-60}
# A parked swapchain renders nothing, so AIO's watchdog (src/watchdog.c,
# 60 s without a frame since the fork's wip/watchdog-background) bounds this.
BG_S=${BG_S:-15}
PKG=app.amphora
EXE="C:/ProgramData/Microsoft/Windows/Graphics-Test-${BITS}bit.exe"
ARGS="--cube $API --no-menu --bench $BENCH --autoclose 2"
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT_DIR=${OUT_DIR:-$ROOT/.tmp/background-check}
OUT=$OUT_DIR/$S-$BITS-$API-$MODE
NEED=$((5 * REFRESH * 9 / 10))

case $API in
  vk | gl | dx7 | ddraw2d | dx8 | dx9 | dx10 | dx11 | dx12 | desktop) ;;
  *)
    echo "api must be one of vk gl dx7 ddraw2d dx8 dx9 dx10 dx11 dx12 desktop" >&2
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
case $MODE in
  power | home) ;;
  *)
    echo "MODE must be power or home" >&2
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
    grep -qaE "$1" "$OUT.log" && return 0
    sleep 1
  done
  return 1
}
launcher_intent() {
  a shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
    -f 0x10200000 -n "$PKG/.MainActivity" >/dev/null
}
# Tap the centre of the first view whose text matches <regex> (uiautomator).
tap_text() {
  local i b
  for i in $(seq 1 20); do
    b=$(a exec-out uiautomator dump /dev/tty 2>/dev/null | tr '>' '\n' |
      grep -E "text=\"($1)\"" | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' |
      grep -oE '[0-9]+' | paste -sd ' ' -)
    if [[ -n $b ]]; then
      # shellcheck disable=SC2086
      set -- $b
      a shell input tap $((($1 + $3) / 2)) $((($2 + $4) / 2))
      return 0
    fi
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
# Not through a(): $! must be adb itself so the kill below stops the stream.
adb -s "$S" logcat >"$OUT.log" 2>&1 </dev/null &
logcat_pid=$!
trap 'kill $logcat_pid 2>/dev/null' EXIT
a shell dumpsys SurfaceFlinger --timestats -enable -clear >/dev/null
if [[ $API == desktop ]]; then
  launcher_intent
  tap_text 'Windows Desktop' || log "no 'Windows Desktop' entry"
  tap_text 'Open desktop|Prepare and open desktop' || log "no open-desktop button"
else
  a shell am start -n "$PKG/.MainActivity" \
    --ez app.amphora.debug.WINEANDROID true \
    --es app.amphora.debug.WINE_EXE "$EXE" \
    --es app.amphora.debug.WINE_ARGS "'$ARGS'" >/dev/null
fi
# --- end of aio-run.sh preparation ---

pid=
for _ in $(seq 1 180); do
  pid=$(grep -aoE 'guest running pid=[0-9]+' "$OUT.log" | tail -1 | cut -d= -f2)
  [[ -n $pid ]] && break
  grep -qaE 'prepare failed|FATAL EXCEPTION' "$OUT.log" && break
  sleep 1
done
alive() { a shell "ps -A -o PID" | grep -qw "$pid"; }
finish_logs() {
  sleep 1
  kill "$logcat_pid" 2>/dev/null
  wait "$logcat_pid" 2>/dev/null
  a shell "run-as $PKG cat files/wine_stderr.log" >"$OUT.wine.log" 2>/dev/null
}
fail_early() {
  log "$1"
  finish_logs
  a shell am force-stop "$PKG"
  echo "RESULT $S $BITS $API $MODE alive=no returned=no acq_err=- parked=- unparked_ms=none dframes=0/$NEED rereg=- bench=none fatal=- tombstones=- verdict=FAIL ($1)"
  exit 1
}
[[ -n $pid ]] || fail_early "no guest pid"
log "guest pid=$pid"
if [[ $API == desktop ]]; then
  wait_log 'registerSurface hwnd=.* opengl=false ok=true' 90 || fail_early "no GDI registerSurface"
else
  wait_log 'AHB_SC create images=' 60 || fail_early "no AHB swapchain"
fi
sleep 5
a exec-out screencap -p >"$OUT-before.png"
t0=$(client_frames "$OUT-t0.ts")
log "T0 totalFrames=${t0:-none}"

if [[ $MODE == home ]]; then
  a shell input keyevent KEYCODE_HOME
else
  a shell input keyevent KEYCODE_SLEEP
fi
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

log_mark=$(($(wc -l <"$OUT.log") + 1))
if [[ $MODE == home ]]; then
  launcher_intent
else
  a shell input keyevent KEYCODE_WAKEUP
  a shell wm dismiss-keyguard
fi
t_fg=$(now)
sleep 2
a shell dumpsys activity activities | tr -d '\r' |
  grep -E 'ResumedActivity|topResumedActivity' >"$OUT-resumed.txt"
returned=no
# The session start already logged one "layer root created".
for _ in $(seq 1 10); do
  if [[ $(grep -ac 'WineAndroidDesktop: layer root created' "$OUT.log") -ge 2 ]]; then
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
if [[ $API != desktop ]]; then
  for _ in $(seq 1 $((BENCH + 120))); do
    if ! alive; then
      exited=1
      break
    fi
    sleep 1
  done
fi
a shell dumpsys SurfaceFlinger --timestats -disable >/dev/null
finish_logs
a shell "run-as $PKG cat 'files/imagefs/AIO Results/Benchmark/AIO-Graphics-Test_bench.csv'" 2>/dev/null | tr -d '\r' >"$OUT.csv"
a shell am force-stop "$PKG"
tomb_after=$(a shell "ls -l /data/tombstones" 2>/dev/null | tr -d '\r' | sort)
tombs=$(comm -13 <(printf '%s\n' "$tomb_before") <(printf '%s\n' "$tomb_after") | grep -c tombstone)

echo "== $S $BITS-bit $API $MODE pid=$pid exited=${exited:-no} returned=$returned (files $OUT*)"
grep -aE 'layer root (destroyed|created)|parkSurface|parked|unparked|AHB_SC acquire dequeue ret=|buffer serve exit|registerSurface hwnd=.*same surface|launcher intent over a running task' "$OUT.log" |
  sed -E 's/^[0-9-]+ //' | cut -c1-200 | head -30
grep -aoE 'AHB_SC destroy .*' "$OUT.log" | sed -E 's/ sc=0x[0-9a-f]+//' | tail -2
grep -E '^# (AIO|frames|avg)' "$OUT.csv" | sed 's/^# //' | paste -sd ' ' -

acq_err=$(grep -ac 'AHB_SC acquire dequeue ret=' "$OUT.log")
parked=$(grep -acE 'serve DEQUEUE .*parked' "$OUT.log")
unparked_ms=$(grep -aoE 'unparked after [0-9]+' "$OUT.log" | awk '{print $3}' | sort -rn | head -1)
rereg=$(tail -n +"$log_mark" "$OUT.log" | grep -acE 'registerSurface hwnd=.* opengl=false ok=true')
no_anw=$(grep -c 'no client ANW after wait' "$OUT.wine.log")
bridges=$(grep -c 'amphora PE WSI bridge' "$OUT.wine.log")
fatal=$(grep -acE 'FATAL EXCEPTION|Fatal signal' "$OUT.log")
bench=$(grep -oE 'avg_fps,[0-9.]+' "$OUT.csv" | cut -d, -f2)
dframes=$((${t1:-0} - ${t0:-0}))
echo "wine log: 'no client ANW after wait'=$no_anw 'amphora PE WSI bridge'=$bridges"

verdict=PASS
[[ -z $died_at && $returned == yes && $fatal -eq 0 && $tombs -eq 0 ]] || verdict=FAIL
if [[ $API == desktop ]]; then
  [[ $rereg -ge 1 ]] || verdict=FAIL
else
  [[ $acq_err -eq 0 && $parked -ge 1 && -n $unparked_ms ]] || verdict=FAIL
  [[ $dframes -ge $NEED && -n $bench && $no_anw -eq 0 ]] || verdict=FAIL
fi
echo "RESULT $S $BITS $API $MODE alive=$([[ -z $died_at ]] && echo yes || echo "no@${died_at}s")" \
  "returned=$returned acq_err=$acq_err parked=$parked unparked_ms=${unparked_ms:-none}" \
  "dframes=$dframes/$NEED rereg=$rereg bench=${bench:-none} fatal=$fatal tombstones=$tombs verdict=$verdict"
