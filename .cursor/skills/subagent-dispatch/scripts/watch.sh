#!/usr/bin/env bash
# Live view of one subagent run: tool calls (with the model's stated intent),
# assistant text, errors. Read-only; safe to run from any terminal.
#
#   watch.sh RUN            follow until the run ends (Ctrl-C to detach)
#   watch.sh RUN --last N   print the last N progress lines and exit
#
# RUN is a run dir, its basename, or a --name (newest run with that name).
set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

[ $# -ge 1 ] || die "usage: watch.sh RUN [--last N]"
d="$(resolve_run "$1")"
ev="$d/events.jsonl"
[ -f "$ev" ] || die "no events.jsonl in $d (run started before json mode?)"

if [ "${2:-}" = "--last" ]; then
  jq -r "$JQ_PROGRESS" "$ev" 2>/dev/null | tail -n "${3:-20}"
  echo "-- $(tail -1 "$d/status") | last event $(age_s "$ev")s ago"
  exit 0
fi

echo "== $(basename "$d")  $(head -1 "$d/status")"
# tail -F keeps following across truncation; stop once status has END.
tail -n +1 -F "$ev" 2>/dev/null | jq --unbuffered -r "$JQ_PROGRESS" 2>/dev/null &
tail_pid=$!
trap 'kill $tail_pid 2>/dev/null || true' EXIT INT TERM
while ! grep -q ' END ' "$d/status" 2>/dev/null; do sleep 2; done
sleep 1
echo "== $(tail -1 "$d/status")"
