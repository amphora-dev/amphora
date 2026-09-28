#!/usr/bin/env bash
# aio-run.sh <serial> <api> [bench_s] [64|32]
#
# Runs the AIO Graphics Test build the container stages (Start menu →
# Programs → Graphics Test) as
#   --cube <api> --no-menu --bench <bench_s> --autoclose 2
# in a wineandroid session. <api> is one of vk gl dx7 ddraw2d dx8 dx9 dx10
# dx11 dx12. Takes a screenshot 3 s after the first AHB swapchain appears
# (or $SHOT_DELAY s after the guest starts when the backend has none), waits
# for the guest to exit, and keeps logcat, SurfaceFlinger timestats,
# wine_stderr.log and AIO's benchmark CSV under $OUT_DIR
# (default: .tmp/aio-smoke/ in the repo root).
#
# The last line is a one-line verdict (aio-matrix.sh collects these):
#   RESULT <serial> <bits> <api> bench=<avg fps|none> presents=<n> sf=<frames> fatal=<n> err=<first wine err>
# bench=none means AIO never finished its benchmark: the backend failed to
# start (look at the screenshot for its error dialog and at err=). A bench
# number alone is not proof of a picture; check the screenshot.
set -uo pipefail

S=${1:?usage: aio-run.sh <serial> <api> [bench_s] [64|32]}
API=${2:?usage: aio-run.sh <serial> <api> [bench_s] [64|32]}
BENCH=${3:-8}
BITS=${4:-64}
PKG=app.amphora
EXE="C:/ProgramData/Microsoft/Windows/Graphics-Test-${BITS}bit.exe"
ARGS="--cube $API --no-menu --bench $BENCH --autoclose 2"
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT_DIR=${OUT_DIR:-$ROOT/.tmp/aio-smoke}
OUT=$OUT_DIR/$S-$BITS-$API
SHOT_DELAY=${SHOT_DELAY:-15}

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
mkdir -p "$OUT_DIR"

a shell am force-stop "$PKG"
# The screen must be on and unlocked, otherwise the Activity has no Surface.
a shell svc power stayon usb
a shell input keyevent KEYCODE_WAKEUP
a shell wm dismiss-keyguard
# AIO 2.x writes its results under "AIO Results/" in the guest working directory (the imagefs root).
a shell "run-as $PKG sh -c 'rm -rf \"files/imagefs/AIO Results\"; : > files/wine_stderr.log'" 2>/dev/null
a shell dumpsys SurfaceFlinger --timestats -disable -clear >/dev/null
a logcat -c
a shell dumpsys SurfaceFlinger --timestats -enable -clear >/dev/null
t0=$(now)
# The remote shell joins its arguments, so quote the value for it.
a shell am start -n "$PKG/.MainActivity" \
  --ez app.amphora.debug.WINEANDROID true \
  --es app.amphora.debug.WINE_EXE "$EXE" \
  --es app.amphora.debug.WINE_ARGS "'$ARGS'" >/dev/null

pid=
for _ in $(seq 1 120); do
  pid=$(a logcat -d -s WineAndroidLauncher:I | grep -oE 'guest running pid=[0-9]+' | tail -1 | cut -d= -f2)
  [[ -n $pid ]] && break
  a logcat -d | grep -qE 'prepare failed|FATAL EXCEPTION' && break
  sleep 1
done
t_pid=$(now)
shot_at=$((t_pid + SHOT_DELAY))
shot='' exited='' sc_seen=''
for _ in $(seq 1 $((BENCH + 90))); do
  [[ -z $pid ]] && break
  if [[ -z $sc_seen ]] && a logcat -d -s WineAndroidWsi:I | grep -q 'AHB_SC create images='; then
    sc_seen=1
    shot_at=$(($(now) + 3))
  fi
  if [[ -z $shot && $(now) -ge $shot_at ]]; then
    a exec-out screencap -p >"$OUT.png" && shot=1
  fi
  if ! a shell "ps -A -o PID" | grep -qw "$pid"; then
    exited=1
    break
  fi
  sleep 1
done
t_end=$(now)
[[ -z $shot ]] && a exec-out screencap -p >"$OUT.png"
sleep 1
a shell dumpsys SurfaceFlinger --timestats -dump | tr -d '\r' >"$OUT.ts"
a shell dumpsys SurfaceFlinger --timestats -disable >/dev/null
a logcat -d >"$OUT.log"
a shell "run-as $PKG cat files/wine_stderr.log" >"$OUT.wine.log" 2>/dev/null
a shell "run-as $PKG cat 'files/imagefs/AIO Results/Benchmark/AIO-Graphics-Test_bench.csv'" 2>/dev/null | tr -d '\r' >"$OUT.csv"
a shell am force-stop "$PKG"

echo "== $S $BITS-bit $API args='$ARGS' pid=${pid:-none} exited=${exited:-no}" \
  "start->pid=$((t_pid - t0))s pid->end=$((t_end - t_pid))s (files $OUT.*)"
grep -E '^# (AIO|frames|avg)' "$OUT.csv" | sed 's/^# //' | paste -sd ' ' -
grep -E 'AHB_SC create ' "$OUT.log" | grep -v cancel | sed -E 's/.*(AHB_SC create )/\1/; s/ (sc|surface)=0x[0-9a-f]+//' | sort | uniq -c | head -2
grep -oE 'AHB_SC destroy .*' "$OUT.log" | sed -E 's/ sc=0x[0-9a-f]+//' | tail -2
sf=$(awk '/^layerName =/{n=$3" "$4} /^totalFrames =/{t=$3}
  /^averageFPS =/{if(n ~ /SurfaceView\[app.amphora/) print t}' "$OUT.ts" | sort -rn | head -1)
fatal=$(grep -cE 'FATAL EXCEPTION|Fatal signal' "$OUT.log")
bench=$(grep -oE 'avg_fps,[0-9.]+' "$OUT.csv" | cut -d, -f2)
presents=$(grep -oE 'AHB_SC destroy .*presents=[0-9]+' "$OUT.log" | grep -oE 'presents=[0-9]+' | cut -d= -f2 | sort -rn | head -1)
# Wine / DXVK / vkd3d errors, minus the ones every session prints.
errs=$(grep -aE 'err:|FAILED|Failed to|Lacking' "$OUT.wine.log" |
  grep -vE 'install_bpf|load_android_libs|amphora |egldrv_init_pixel_formats|winediag:wined3d_dll_init|tabtip|mprotect_exec|ANDROID_VulkanInit|ANDROID_vulkan_surface_create|convert_device_create_info|create_ioctl|winebth|machine-id|OpenVR' |
  sed -E 's/^[0-9.]+:[0-9a-f]{4}:[0-9a-f]{4}://; s/^[0-9a-f]{4}://')
printf '%s\n' "$errs" | grep -v '^$' | sort | uniq -c | sort -rn | head -4 | cut -c1-170
first_err=$(printf '%s\n' "$errs" | grep -v '^$' | head -1 | tr -s ' ' | cut -c1-120)
echo "RESULT $S $BITS $API bench=${bench:-none} presents=${presents:-0} sf=${sf:-0} fatal=$fatal err=${first_err:--}"
