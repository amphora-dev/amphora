#!/usr/bin/env bash
# aio-matrix.sh <serial>...
#
# Every AIO Graphics Test backend through aio-run.sh, 64-bit then 32-bit,
# one device per serial in parallel (each device runs its list in order).
# Prints the RESULT lines as a table at the end; per-run detail goes to
# $OUT_DIR/matrix-<serial>.txt next to the run files.
#
# Env: APIS (default: vk gl dx7 ddraw2d dx8 dx9 dx10 dx11 dx12),
#      BITS (default: "64 32"), BENCH (seconds, default 8), OUT_DIR,
#      FRAME_RATES (Frame rate options to run, outermost loop; default "sync";
#      any of sync game uncapped 30 45 60 90 120), AIO_DRIVER (passed to
#      aio-run.sh, e.g. System).
set -uo pipefail

[[ $# -ge 1 ]] || {
  echo "usage: aio-matrix.sh <serial>..." >&2
  exit 2
}
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
export OUT_DIR=${OUT_DIR:-$ROOT/.tmp/aio-smoke}
APIS=${APIS:-"vk gl dx7 ddraw2d dx8 dx9 dx10 dx11 dx12"}
BITS=${BITS:-"64 32"}
BENCH=${BENCH:-8}
FRAME_RATES=${FRAME_RATES:-sync}
export AIO_DRIVER=${AIO_DRIVER:-}
mkdir -p "$OUT_DIR"

run_dev() {
  local s=$1 rate bits api
  for rate in $FRAME_RATES; do
    for bits in $BITS; do
      for api in $APIS; do
        AIO_FRAME_RATE=$rate "$HERE/aio-run.sh" "$s" "$api" "$BENCH" "$bits"
        echo
      done
    done
  done >"$OUT_DIR/matrix-$s.txt" 2>&1
}

for s in "$@"; do run_dev "$s" & done
wait

for s in "$@"; do
  grep -h '^RESULT ' "$OUT_DIR/matrix-$s.txt" | cut -d' ' -f2-
done
