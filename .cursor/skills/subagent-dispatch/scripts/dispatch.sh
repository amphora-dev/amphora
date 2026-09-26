#!/usr/bin/env bash
# Run one task spec with a headless omp subagent.
#
#   dispatch.sh --orchestrator claude-opus-5.5 --name NAME --cwd DIR --task SPEC.md
#               [--tier fast|code|deep | --model ID[,FALLBACK...]] [--result-key KEY]
#               [--worktree [BASE]] [--on-end CMD] [--max-time 40m] [--thinking medium] [--async]
#
# Model fallback, cooldown and stream-stall recovery are omp's own
# (retry.fallbackChains, providers.streamIdleTimeoutSeconds); this script only
# writes the overlay and runs omp once.
# --on-end CMD (or SUBAGENT_ON_END): bash command run once at the end with
#             SUBAGENT_RUN_DIR and SUBAGENT_END (the END status line) set.
# --worktree  run in a fresh git worktree of DIR's repo on branch sub/NAME
#             (from BASE, default DIR's HEAD); an existing one is reused (resume).
# Output: omp --mode json events go to events.jsonl (watch.sh renders them live);
# the final assistant text goes to result.md. Prints the run directory.
# Exit 0 only when the last KEY_RESULT= line is OK or PASS (or, without
# --result-key, when omp exits 0). With --async it returns at once.
set -euo pipefail
# shellcheck source=lib.sh
. "$(dirname "$0")/lib.sh"

orchestrator="" name="" cwd="" task="" key="" max_time="40m" thinking="medium" async=0
tier="fast" model="" worktree=0 wt_base="" on_end="${SUBAGENT_ON_END:-}"
while [ $# -gt 0 ]; do
  case "$1" in
    --orchestrator) orchestrator="$2"; shift 2 ;;
    --name) name="$2"; shift 2 ;;
    --cwd) cwd="$2"; shift 2 ;;
    --task) task="$2"; shift 2 ;;
    --result-key) key="$2"; shift 2 ;;
    --max-time) max_time="$2"; shift 2 ;;
    --thinking) thinking="$2"; shift 2 ;;
    --tier) tier="$2"; shift 2 ;;
    --model) model="$2"; shift 2 ;;
    --worktree)
      worktree=1
      if [ $# -gt 1 ] && [ "${2#--}" = "$2" ]; then wt_base="$2"; shift 2; else shift; fi ;;
    --on-end) on_end="$2"; shift 2 ;;
    --async) async=1; shift ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
require_orchestrator "$orchestrator"
[ -n "$name" ] && [ -n "$cwd" ] && [ -n "$task" ] || die "--name, --cwd and --task are required"
case "$name" in *[!A-Za-z0-9._-]*) die "--name may only contain [A-Za-z0-9._-]" ;; esac
command -v omp >/dev/null || die "omp not found on PATH"
command -v jq >/dev/null || die "jq not found on PATH"
[ -f "$task" ] || die "task spec not found: $task"
[ -n "$model" ] || model="$(tier_model "$tier")"
cwd="$(abs_path "$cwd")"
[ -d "$cwd" ] || die "--cwd is not a directory: $cwd"

run_dir="$(subagent_home)/$(utc_stamp)-$name"
mkdir -p "$run_dir/artifacts"
cp "$task" "$run_dir/task.md"
: > "$run_dir/stderr.log"
: > "$run_dir/events.jsonl"

if [ "$worktree" = 1 ]; then
  root="$(repo_root "$cwd")"
  rel="${cwd#"$root"}"
  wt="$root/.tmp/wt/$name"
  if [ -e "$wt" ]; then
    # Resume: reuse an existing worktree (a previous run stopped mid-task).
    git -C "$wt" rev-parse --abbrev-ref HEAD | grep -qx "sub/$name" ||
      die "$wt exists but is not on sub/$name"
    echo "$(now) RESUME worktree $wt" >> "$run_dir/status"
  else
    git -C "$root" worktree add -q -b "sub/$name" "$wt" "${wt_base:-HEAD}" ||
      die "git worktree add failed for sub/$name"
    if [ -f "$root/.gitmodules" ]; then
      git -C "$wt" submodule update --init --recursive -q ||
        die "submodule init failed in $wt"
    fi
  fi
  echo "$wt" > "$run_dir/worktree"
  cwd="$wt$rel"
fi

prompt="严格按附件任务说明执行，只做允许的操作。遇到任何不符合预期的情况立即停止，并按「最终输出」一节报告 FAIL。
运行目录：${run_dir}（产物放在 ${run_dir}/artifacts）。工作目录：${cwd}。
如果工作目录里已有未提交改动，那是上一次运行中断留下的：先 git status / git diff 看清进度再接着做，不要重做或回滚。"

# omp's in-session fallback switches model on 429 / usage limits / stalled
# streams without losing context. The tier list becomes the overlay: first
# model is the default role, the rest its fallback chain.
write_overlay() {
  local first="${model%%,*}" rest="" m
  local IFS=,
  for m in ${model#"$first"}; do [ -n "$m" ] && rest="$rest\"$m\", "; done
  {
    echo "modelRoles:"
    echo "  default: $first"
    echo "retry:"
    echo "  modelFallback: true"
    echo "  fallbackChains:"
    echo "    default: [${rest%, }]"
    echo "providers:"
    echo "  streamIdleTimeoutSeconds: 300"
  } > "$run_dir/omp-config.yml"
}

run() {
  local rc
  write_overlay
  echo "$(now) START name=$name cwd=$cwd model=$model" >> "$run_dir/status"
  printf '%q ' omp -p --mode json --config "$run_dir/omp-config.yml" \
    --thinking "$thinking" --auto-approve --no-session --max-time "$max_time" \
    --cwd "$cwd" "@$run_dir/task.md" "<prompt>" > "$run_dir/cmd"
  echo >> "$run_dir/cmd"
  set +e
  AMPHORA_SUBAGENT=1 SUBAGENT_RUN_DIR="$run_dir" \
    omp -p --mode json --config "$run_dir/omp-config.yml" --thinking "$thinking" --auto-approve \
    --no-session --max-time "$max_time" --cwd "$cwd" "@$run_dir/task.md" "$prompt" \
    < /dev/null >> "$run_dir/events.jsonl" 2>> "$run_dir/stderr.log"
  rc=$?
  set -e
  jq -r 'select(.type == "message_end" and .message.role == "assistant")
         | .message.content[]? | select(.type == "text") | .text' \
    "$run_dir/events.jsonl" > "$run_dir/result.md" 2>/dev/null || true
  local line=""
  if [ -n "$key" ]; then
    line="$(grep -Eo "${key}_RESULT=[A-Z]+.*" "$run_dir/result.md" | tail -1 || true)"
  fi
  echo "$(now) END rc=$rc ${line:-${key:+NO_RESULT_LINE}}" >> "$run_dir/status"
  # Completion hook: push the END line somewhere a human or orchestrator sees it.
  if [ -n "$on_end" ]; then
    SUBAGENT_RUN_DIR="$run_dir" SUBAGENT_END="$(tail -1 "$run_dir/status")" \
      bash -c "$on_end" >> "$run_dir/stderr.log" 2>&1 || true
  fi
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
