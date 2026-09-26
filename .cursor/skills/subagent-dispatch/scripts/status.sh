#!/usr/bin/env bash
# Show subagent runs.
#   status.sh            one line per run (newest last) + chain logs
#   status.sh RUN_DIR    full status, pid liveness, last 40 log lines
set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

home="$(subagent_home)"

if [ $# -gt 0 ]; then
  d="$1"
  [ -d "$d" ] || d="$home/$1"
  [ -d "$d" ] || die "no such run: $1"
  cat "$d/status" 2>/dev/null || echo "(no status yet)"
  if [ -f "$d/pid" ]; then
    if kill -0 "$(cat "$d/pid")" 2>/dev/null; then echo "pid $(cat "$d/pid"): running"; else echo "pid $(cat "$d/pid"): exited"; fi
  fi
  echo "--- run.log (tail)"
  tail -n 40 "$d/run.log" 2>/dev/null || true
  exit 0
fi

found=0
for d in "$home"/*/; do
  [ -f "$d/status" ] || continue
  found=1
  printf '%-48s %s\n' "$(basename "$d")" "$(tail -1 "$d/status")"
done
for f in "$home"/*.status; do
  [ -f "$f" ] || continue
  found=1
  printf '%-48s %s\n' "$(basename "$f")" "$(tail -1 "$f")"
done
[ "$found" = 1 ] || echo "no runs under $home"
