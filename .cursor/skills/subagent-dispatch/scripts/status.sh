#!/usr/bin/env bash
# Show subagent runs.
#   status.sh            one line per run: state, idle seconds, last intent
#   status.sh RUN        full status, pid liveness, last 20 progress lines
# A running run idle for more than SUBAGENT_STALL_S (default 300) is flagged STALL.
set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

home="$(subagent_home)"
stall="${SUBAGENT_STALL_S:-300}"

if [ $# -gt 0 ]; then
  d="$(resolve_run "$1")"
  cat "$d/status" 2>/dev/null || echo "(no status yet)"
  [ -f "$d/worktree" ] && echo "worktree: $(cat "$d/worktree")"
  if [ -f "$d/pid" ]; then
    if kill -0 "$(cat "$d/pid")" 2>/dev/null; then echo "pid $(cat "$d/pid"): running"; else echo "pid $(cat "$d/pid"): exited"; fi
  fi
  if [ -f "$d/events.jsonl" ]; then
    echo "--- progress (tail)"
    jq -r "$JQ_PROGRESS" "$d/events.jsonl" 2>/dev/null | tail -n 20
  else
    echo "--- run.log (tail)"
    tail -n 40 "$d/run.log" 2>/dev/null || true
  fi
  exit 0
fi

found=0
for d in "$home"/*/; do
  d="${d%/}"
  [ -f "$d/status" ] || continue
  found=1
  last="$(tail -1 "$d/status")"
  if [ -f "$d/events.jsonl" ] && ! grep -q ' END ' "$d/status"; then
    idle="$(age_s "$d/events.jsonl")"
    intent="$(jq -r 'select(.type=="tool_execution_start") | .intent // .toolName' "$d/events.jsonl" 2>/dev/null | tail -1)"
    state="RUN"
    [ "$idle" -gt "$stall" ] && state="STALL"
    printf '%-44s %-5s idle=%ss  %s\n' "$(basename "$d")" "$state" "$idle" "${intent:0:70}"
  else
    printf '%-44s %s\n' "$(basename "$d")" "${last:20}"
  fi
done
for f in "$home"/*.status; do
  [ -f "$f" ] || continue
  found=1
  printf '%-44s %s\n' "$(basename "$f")" "$(tail -1 "$f")"
done
[ "$found" = 1 ] || echo "no runs under $home"
