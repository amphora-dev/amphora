#!/usr/bin/env bash
# The device tests, one line each, as fast as the programs themselves allow
# (run.sh starts sessions directly and waits for results, not for a clock).
#
#   smoke.sh            the quick set: x86 translation (hello64, hello32), Vulkan off screen
#                       on the three architectures, one Direct3D back end per bitness
#                       (64-bit dx11, 32-bit dx9), each with a look at the picture
#   smoke.sh full       every AIO back end that should work (vk, dx8-dx12), 64 and 32 bit
#   smoke.sh <name>...  only these: hello64 hello32 vk-arm64 vk-x64 vk-x86 aio64-<api> aio32-<api>
#
# AIO runs for BENCH seconds (default 2: enough to see that it draws; the frame
# rate of such a short run is not a benchmark, use aio.sh for numbers).
# The test programs are expected under /sdcard/Download/amphora-test/ (tools/README.md).
# Exit status: the number of failed tests.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
RUN="$HERE/run.sh"
BENCH="${BENCH:-2}"
LOG='Z:\data\user\0\app.amphora\cache\smoke.log'
AIO_RESULTS='/sdcard/Download/amphora-test/aio/AIO Results/Benchmark'
S="${ANDROID_SERIAL:-HA262AAH}"
failed=0

# report <name> <started at, seconds> <ok|fail> <detail>
report() {
  printf '%-4s %-12s %5.1fs  %s\n' "$([ "$3" = ok ] && echo PASS || echo FAIL)" "$1" \
    "$(python3 -c "import time; print(time.time() - $2)")" "$4"
  [ "$3" = ok ] || failed=$((failed + 1))
}
now() { python3 -c 'import time; print(time.time())'; }

# A program that writes its own report (--log) and ends it with a verdict line. With a
# fourth argument the program is one that exits by itself, and the session must end with
# it: a translator that hangs while the process terminates fails here.
program() {
  local name="$1" exe="$2" args="$3" exits="${4:-}" verdict t0 out
  t0="$(now)"
  adb -s "$S" shell "run-as app.amphora rm -f cache/smoke.log"
  if ! out="$("$RUN" start "$exe" "$args --log $LOG" 2>&1)"; then report "$name" "$t0" fail "$out"; return; fi
  if "$RUN" wait-line cache/smoke.log '^(PASS|FAIL|PASSED|FAILED)' 40; then
    verdict="$("$RUN" cat cache/smoke.log | grep -E '^(PASS|FAIL)' | tail -n 1)"
    case "$verdict" in
      PASS*)
        if [ -z "$exits" ]; then report "$name" "$t0" ok "$verdict"
        elif "$RUN" wait-exit 15; then report "$name" "$t0" ok "$verdict, exited by itself"
        else report "$name" "$t0" fail "$verdict, but the process did not exit within 15 s"
        fi ;;
      *) report "$name" "$t0" fail "$verdict" ;;
    esac
  else
    report "$name" "$t0" fail "no verdict; $("$RUN" log 3 | tail -n 1)"
  fi
  "$RUN" end
}

# One AIO back end: it must create a swapchain, draw the cube, and write a result.
aio() {
  local name="$1" bits="$2" api="$3" t0 out shot result drawn=""
  t0="$(now)"
  shot="/tmp/smoke-$name.png"
  adb -s "$S" shell "rm -rf '$AIO_RESULTS'"
  if ! out="$("$RUN" start "aio/AIO-Graphics-Test-${bits}bit.exe" "--cube $api --no-menu --bench $BENCH --autoclose 1" \
      display.frameRate=game 2>&1)"; then report "$name" "$t0" fail "$out"; return; fi
  if ! "$RUN" wait-log 'wineandroid.drv: handle_create' 30; then
    report "$name" "$t0" fail "no swapchain; $("$RUN" log 3 | tail -n 1)"; "$RUN" end; return
  fi
  # The first frames come a moment after the swapchain (32-bit: FEX translates the renderer
  # first): look until the cube is there, for as long as the run lasts.
  local tries=0
  until "$RUN" shot "$shot" && drawn="$("$RUN" drawn "$shot")"; do
    tries=$((tries + 1))
    if [ "$tries" -ge $((BENCH * 2 + 4)) ]; then
      report "$name" "$t0" fail "cube not drawn ($drawn), $shot"; "$RUN" end; return
    fi
    sleep 0.3
  done
  if result="$("$RUN" wait-any "$AIO_RESULTS" '*.txt' $((BENCH + 20)))"; then
    report "$name" "$t0" ok "${result##*: } fps avg|min|max, $drawn"
  else
    report "$name" "$t0" fail "no result file"
  fi
  "$RUN" end
}

one() {
  case "$1" in
    hello64) program hello64 x86/hello64.exe -q exits ;;
    hello32) program hello32 x86/hello32.exe -q exits ;;
    vk-arm64) program vk-arm64 vk/vktest-arm64.exe "" ;;
    vk-x64) program vk-x64 vk/vktest64.exe "" ;;
    vk-x86) program vk-x86 vk/vktest32.exe "" ;;
    aio64-*) aio "$1" 64 "${1#aio64-}" ;;
    aio32-*) aio "$1" 32 "${1#aio32-}" ;;
    *) echo "smoke.sh: no test \"$1\"" >&2; failed=$((failed + 1)) ;;
  esac
}

quick=(hello64 hello32 vk-arm64 vk-x64 vk-x86 aio64-dx11 aio32-dx9)
case "${1:-}" in
  "") tests=("${quick[@]}") ;;
  full)
    tests=(hello64 hello32 vk-arm64 vk-x64 vk-x86)
    for bits in 64 32; do for api in vk dx8 dx9 dx10 dx11 dx12; do tests+=("aio$bits-$api"); done; done ;;
  -h|--help) sed -n '2,15p' "$0" >&2; exit 2 ;;
  *) tests=("$@") ;;
esac

start="$(now)"
for t in "${tests[@]}"; do one "$t"; done
printf '%d tests, %d failed, %.0f s\n' "${#tests[@]}" "$failed" "$(python3 -c "import time; print(time.time() - $start)")"
exit "$failed"
