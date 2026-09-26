#!/usr/bin/env bash
# Run dependent subagent steps in order; stop at the first step that is not OK.
#
#   chain.sh --orchestrator claude-opus-5.5 --chain FILE.chain [--async]
#
# Chain file, one step per line (paths relative to the chain file, # = comment):
#   name|cwd|task.md|max-time|RESULT_KEY
set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

orchestrator="" chain="" async=0
while [ $# -gt 0 ]; do
  case "$1" in
    --orchestrator) orchestrator="$2"; shift 2 ;;
    --chain) chain="$2"; shift 2 ;;
    --async) async=1; shift ;;
    -h|--help) sed -n '2,7p' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
require_orchestrator "$orchestrator"
[ -f "$chain" ] || die "chain file not found: $chain"
chain="$(abs_path "$chain")"
base="$(dirname "$chain")"
here="$(cd "$(dirname "$0")" && pwd)"

# Validate every step before running any of them.
steps=0
while IFS='|' read -r name cwd task max_time key || [ -n "$name" ]; do
  case "$name" in ''|'#'*) continue ;; esac
  [ -n "$key" ] || die "step '$name' has no RESULT_KEY"
  (cd "$base" && [ -d "$cwd" ]) || die "step '$name': cwd not found: $cwd"
  (cd "$base" && [ -f "$task" ]) || die "step '$name': task not found: $task"
  steps=$((steps + 1))
done < "$chain"
[ "$steps" -gt 0 ] || die "no steps in $chain"

chain_log="$(subagent_home)/$(utc_stamp)-chain-$(basename "$chain" .chain).status"

run_chain() {
  echo "$(now) CHAIN START $chain ($steps steps)" >> "$chain_log"
  while IFS='|' read -r name cwd task max_time key <&3 || [ -n "$name" ]; do
    case "$name" in ''|'#'*) continue ;; esac
    local run_dir
    if run_dir="$(cd "$base" && "$here/dispatch.sh" --orchestrator "$orchestrator" \
        --name "$name" --cwd "$cwd" --task "$task" \
        --max-time "${max_time:-40m}" --result-key "$key" | head -1)" &&
       tail -1 "$run_dir/status" | grep -Eq "${key}_RESULT=(OK|PASS)"; then
      echo "$(now) OK   $name $run_dir" >> "$chain_log"
    else
      echo "$(now) STOP $name ${run_dir:-<no run dir>}" >> "$chain_log"
      return 1
    fi
  done 3< "$chain"
  echo "$(now) CHAIN DONE" >> "$chain_log"
}

echo "$chain_log"
if [ "$async" = 1 ]; then
  # Keep stderr (e.g. dispatch.sh refusing to start) in the chain log.
  ( run_chain ) >> "$chain_log" 2>&1 < /dev/null &
  exit 0
fi
run_chain
