#!/usr/bin/env bash
# Run one task spec with a headless omp subagent.
#
#   dispatch.sh --orchestrator claude-opus-5.5 --name NAME --cwd DIR --task SPEC.md
#               [--result-key KEY] [--max-time 40m] [--thinking high] [--async]
#
# Prints the run directory. Exit 0 only when the last KEY_RESULT= line in the
# log is OK or PASS (or, without --result-key, when omp exits 0). With --async
# it returns at once.
set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

orchestrator="" name="" cwd="" task="" key="" max_time="40m" thinking="high" async=0
while [ $# -gt 0 ]; do
  case "$1" in
    --orchestrator) orchestrator="$2"; shift 2 ;;
    --name) name="$2"; shift 2 ;;
    --cwd) cwd="$2"; shift 2 ;;
    --task) task="$2"; shift 2 ;;
    --result-key) key="$2"; shift 2 ;;
    --max-time) max_time="$2"; shift 2 ;;
    --thinking) thinking="$2"; shift 2 ;;
    --async) async=1; shift ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
require_orchestrator "$orchestrator"
[ -n "$name" ] && [ -n "$cwd" ] && [ -n "$task" ] || die "--name, --cwd and --task are required"
case "$name" in *[!A-Za-z0-9._-]*) die "--name may only contain [A-Za-z0-9._-]" ;; esac
command -v omp >/dev/null || die "omp not found on PATH"
[ -f "$task" ] || die "task spec not found: $task"
cwd="$(abs_path "$cwd")"
[ -d "$cwd" ] || die "--cwd is not a directory: $cwd"

run_dir="$(subagent_home)/$(utc_stamp)-$name"
mkdir -p "$run_dir/artifacts"
cp "$task" "$run_dir/task.md"

prompt="严格按附件任务说明执行，只做允许的操作。遇到任何不符合预期的情况立即停止，并按「最终输出」一节报告 FAIL。
运行目录：${run_dir}（产物放在 ${run_dir}/artifacts）。"

run() {
  echo "$(now) START name=$name cwd=$cwd model=$SUBAGENT_MODEL" >> "$run_dir/status"
  printf '%q ' omp -p --model "$SUBAGENT_MODEL" --thinking "$thinking" --auto-approve \
    --max-time "$max_time" --cwd "$cwd" "@$run_dir/task.md" "<prompt>" > "$run_dir/cmd"
  echo >> "$run_dir/cmd"
  set +e
  AMPHORA_SUBAGENT=1 SUBAGENT_RUN_DIR="$run_dir" \
    omp -p --model "$SUBAGENT_MODEL" --thinking "$thinking" --auto-approve \
    --max-time "$max_time" --cwd "$cwd" "@$run_dir/task.md" "$prompt" \
    < /dev/null > "$run_dir/run.log" 2>&1
  local rc=$?
  set -e
  local line=""
  if [ -n "$key" ]; then
    line="$(grep -Eo "${key}_RESULT=[A-Z]+.*" "$run_dir/run.log" | tail -1 || true)"
  fi
  echo "$(now) END rc=$rc ${line:-${key:+NO_RESULT_LINE}}" >> "$run_dir/status"
  if [ -n "$key" ]; then
    case "$line" in "${key}_RESULT=OK"*|"${key}_RESULT=PASS"*) return 0 ;; *) return 1 ;; esac
  fi
  return "$rc"
}

echo "$run_dir"
if [ "$async" = 1 ]; then
  ( run ) > /dev/null 2>&1 < /dev/null &
  echo "$!" > "$run_dir/pid"
  exit 0
fi
run
