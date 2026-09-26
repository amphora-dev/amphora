#!/usr/bin/env bash
# Shared helpers for the subagent-dispatch skill. Source, do not execute.
# Portable to macOS bash 3.2: no associative arrays, no mapfile.

REQUIRED_ORCHESTRATOR="claude-opus-5.5"
SUBAGENT_MODEL="${SUBAGENT_MODEL:-tencent-intranet/glm-5.3-flash-ioa}"

die() {
  echo "subagent-dispatch: $*" >&2
  exit 2
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

utc_stamp() { date -u +%Y%m%dT%H%M%SZ; }
now() { date '+%F %T'; }

SKILL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
