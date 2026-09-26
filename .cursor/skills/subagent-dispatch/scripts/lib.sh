#!/usr/bin/env bash
# Shared helpers for the subagent-dispatch skill. Source, do not execute.
# Portable to macOS bash 3.2: no associative arrays, no mapfile.

REQUIRED_ORCHESTRATOR="claude-opus-5.5"

# Model tiers (omp model ids). Picked from a 2026-09-26 bench on this repo
# (read-only lookup tasks, same prompt). Cost first: premium models (sonnet,
# gpt) burned ~0.9M tokens in 9 min on one refactor; use them only via --model.
# Re-bench when the provider list changes. Override any tier with SUBAGENT_MODEL_<TIER>.
#   fast  mechanical, well-specified steps: device smoke, release commands, greps
#   code  multi-file edits that must compile: refactors, deletions, test fixes
#   deep  investigation where the spec cannot list every step
# Each tier is a comma-separated list: the first is the model, the rest are
# omp's own retry.fallbackChains (429 / usage limit / stream stall switches
# in-session and omp remembers the cooldown). ClinePass Muse first (subscription), kilo Muse next, premium last.
MODEL_FAST="${SUBAGENT_MODEL_FAST:-tencent-intranet/deepseek-v4.1-flash,cline-free/deepseek-v4.1-flash}"
MODEL_CODE="${SUBAGENT_MODEL_CODE:-cline-pass/muse-spark-1.3-contributor,kilo/meta/muse-spark-1.3-contributor,tencent-intranet/claude-sonnet-5}"
MODEL_DEEP="${SUBAGENT_MODEL_DEEP:-cline-pass/muse-spark-1.3-contributor,kilo/meta/muse-spark-1.3-contributor,tencent-intranet/gpt-5.6-luna}"

die() {
  echo "subagent-dispatch: $*" >&2
  exit 2
}

tier_model() {
  case "$1" in
    fast) echo "$MODEL_FAST" ;;
    code) echo "$MODEL_CODE" ;;
    deep) echo "$MODEL_DEEP" ;;
    *) die "unknown --tier '$1' (fast|code|deep)" ;;
  esac
}

# require_orchestrator <value-of---orchestrator>
# claude-opus-5-5 is the API model id of the same model and is accepted too.
require_orchestrator() {
  [ "${AMPHORA_SUBAGENT:-}" = "1" ] &&
    die "refusing to run inside a subagent (AMPHORA_SUBAGENT=1); only the orchestrator dispatches"
  case "${1:-}" in
    "$REQUIRED_ORCHESTRATOR" | claude-opus-5-5) ;;
    *) die "this skill is restricted to --orchestrator $REQUIRED_ORCHESTRATOR (got '${1:-<none>}')" ;;
  esac
}

repo_root() {
  git -C "${1:-.}" rev-parse --show-toplevel 2>/dev/null ||
    die "not inside a git repository: ${1:-.}"
}

# Where run directories live. Device independent: under the repo's ignored .tmp/.
subagent_home() {
  local home="${SUBAGENT_HOME:-$(repo_root "$SKILL_DIR")/.tmp/subagents}"
  mkdir -p "$home" || die "cannot create $home"
  (cd "$home" && pwd)
}

abs_path() {
  local p="$1"
  if [ -d "$p" ]; then (cd "$p" && pwd); else
    local d
    d="$(cd "$(dirname "$p")" 2>/dev/null && pwd)" || die "no such path: $p"
    printf '%s/%s\n' "$d" "$(basename "$p")"
  fi
}

# resolve_run <name-or-dir>: newest run dir matching, or the dir itself.
resolve_run() {
  local home d
  home="$(subagent_home)"
  if [ -d "$1" ]; then abs_path "$1"; return; fi
  [ -d "$home/$1" ] && { echo "$home/$1"; return; }
  # shellcheck disable=SC2012  # run dir names are [A-Za-z0-9._-]
  d="$(ls -d "$home"/*-"$1" 2>/dev/null | tail -1)"
  [ -n "$d" ] || die "no such run: $1"
  echo "$d"
}

# age_s <file>: seconds since last modification.
age_s() {
  local m
  m="$(stat -f %m "$1" 2>/dev/null || stat -c %Y "$1" 2>/dev/null || echo 0)"
  echo $(($(date +%s) - m))
}

# jq filter: one human line per omp json event (tool calls with intent, text, end).
# shellcheck disable=SC2016,SC2034  # jq program; used by watch.sh/status.sh
JQ_PROGRESS='
  if .type == "tool_execution_start" then
    "→ \(.toolName) \(.intent // "")  \((.args | tostring)[0:120])"
  elif .type == "tool_execution_end" and (.isError // false) then
    "✗ \(.toolName) error"
  elif .type == "message_end" and .message.role == "assistant" then
    (.message.content[]? | select(.type == "text") | .text | gsub("\n+"; " ⏎ ") | "💬 " + .[0:300])
  elif .type == "agent_end" then "■ agent_end"
  else empty end'

utc_stamp() { date -u +%Y%m%dT%H%M%SZ; }
now() { date '+%F %T'; }

SKILL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
