---
name: Subagent dispatch
description: >-
  use this when the orchestrating agent is claude-opus-5.5 and wants to hand a
  well-scoped coding, release, or device-smoke task to a cheaper headless omp
  subagent: pick a model tier, write the spec, launch (optionally in its own
  git worktree), watch it live, then review. Do not use from any other model.
---
# Subagent dispatch

## Division of labour

The orchestrator (claude-opus-5.5) keeps only what is expensive to get wrong:
root-cause analysis, design decisions, writing the spec's verified facts, and
reviewing results. Everything that can be written as explicit steps goes to a
subagent: mechanical refactors and deletions, test fixes, doc sync, release
commands, device smoke, log slicing. A subagent report is a claim, not
evidence; the orchestrator re-checks before believing it.

Only an orchestrator running as `claude-opus-5.5` may use this. Scripts refuse
without `--orchestrator claude-opus-5.5` (API id `claude-opus-5-5` accepted)
and refuse inside a subagent (`AMPHORA_SUBAGENT=1`), so subagents cannot fan
out further. This is a misuse guard, not a security boundary.

## Model tiers

`--tier` picks an ordered model list (override with `SUBAGENT_MODEL_FAST` /
`_CODE` / `_DEEP`, or `--model a,b,c`). The run writes `omp-config.yml` with
the first model as `modelRoles.default` and the rest as
`retry.fallbackChains.default`; omp itself switches model on 429 / usage limit
/ stalled stream inside the same session (`retry_fallback_applied` event).
Nothing is layered on top: if a run still dies, look at `watch.sh` and
re-dispatch with the same `--name` (the worktree is reused). Do not pass
`--model` to omp directly: an explicit model bypasses the role chain.

| tier | models, in order | use for |
|---|---|---|
| `fast` (default) | intranet `deepseek-v4.1-flash` → cline-free `deepseek-v4.1-flash` | step-by-step specs: device smoke, release commands, greps, doc sync |
| `code` | ClinePass `muse-spark-1.3-contributor` → kilo `meta/muse-spark-1.3-contributor` → intranet `claude-sonnet-5` | multi-file edits that must compile and pass tests |
| `deep` | ClinePass `muse-spark-1.3-contributor` → kilo `meta/muse-spark-1.3-contributor` → intranet `gpt-5.6-luna` | open-ended investigation the spec cannot fully script |

Cost comes first: premium models are only the last fallback. claude-sonnet-5
used ~0.9M tokens in 9 minutes of one refactor before editing a file.
2026-09-26 bench on this repo (same read-only lookup prompt), all correct:
deepseek-v4.1-flash 6-7 s, muse-spark-1.3-contributor 9-10 s, glm-5.3-flash
14-16 s; gemini-3.5-flash rejected by the proxy.
Re-bench when the provider list changes: run one small prompt against each
candidate with `omp -p --mode json --no-session` and compare wall time and
answers.

## Where things live

```
<repo>/.tmp/specs/                 specs and .chain files you write
<repo>/.tmp/wt/<name>/             git worktree (only with --worktree)
<repo>/.tmp/subagents/<UTC>-<name>/
  task.md        copy of the spec
  cmd            exact omp invocation
  events.jsonl   omp --mode json event stream (tool calls, text, usage)
  result.md      final assistant text; the RESULT line is read from here
  stderr.log     omp stderr (provider errors land here)
  status         START / END lines with the RESULT line
  worktree       worktree path (only with --worktree)
  pid            background pid (--async only)
  artifacts/     subagent outputs ($SUBAGENT_RUN_DIR/artifacts)
```

`.tmp/` is git-ignored, so specs there do not dirty `git status`.
Override the run root with `SUBAGENT_HOME=<dir>`. Sibling repos are
`"$(git rev-parse --show-toplevel)/../<repo>"`, never absolute home paths.

Prerequisites: `omp`, `jq` on PATH; `gh` logged in if a step pushes (a token
without `workflow` scope cannot push `.github/workflows/**`).

## Workflow

1. **Verify the facts yourself.** The subagent executes the spec literally;
   wrong premises become wrong code (seen: "components update on the home
   screen"; they only download after tapping *Prepare and open desktop*).
2. **Write the spec** from [`templates/task.md`](templates/task.md): verified
   background, allowed / forbidden ops, exact steps, self-check commands,
   deliverables, final line `<KEY>_RESULT=OK|FAIL ...`.
3. **Launch**:
   ```bash
   S=.cursor/skills/subagent-dispatch/scripts
   $S/dispatch.sh --orchestrator claude-opus-5.5 --name x11-p1 --cwd . \
     --task .tmp/specs/x11-p1.md --result-key X11P1 --tier code \
     --worktree HEAD --max-time 60m --async
   ```
   Any task that edits a repo you are also using, or runs next to another
   editing subagent, gets `--worktree [BASE]`: a fresh worktree on branch
   `sub/<name>` under `.tmp/wt/`. Review there, then cherry-pick or merge.
   Skip it for read-only and device-only tasks (lookups, smoke, logs), and
   for a single editing subagent when the main checkout is clean
   (`git status --porcelain` empty) and you do not touch it until the run
   ends: review with `git diff`, discard with `git checkout . && git clean -fd`.
   This saves the fresh-worktree gradle build (several minutes).
   Ordered steps (push → CI → next) go through `chain.sh`; chain lines are
   `name|cwd|task.md|max-time|RESULT_KEY[|tier]`.
4. **Watch live**:
   - `$S/status.sh`: one line per run with state `RUN` / `STALL`, idle seconds
     and the tool intent it is on now. `STALL` = no event for
     `SUBAGENT_STALL_S` (default 300) seconds.
   - `$S/watch.sh <name> --last 20` for a snapshot, `$S/watch.sh <name>` to
     follow (tool calls with the model's stated intent, text, errors).
   - Getting told when it ends, best first:
     1. Harness wake-up: in Claude Code run `dispatch.sh` without `--async`
        via Bash `run_in_background` (you are notified on exit), or arm the
        Monitor tool on `tail -F <run dir>/status | grep --line-buffered END`
        (zero tokens while quiet; watches have a deadline, re-arm them).
     2. `--on-end CMD`: runs once with `SUBAGENT_END` / `SUBAGENT_RUN_DIR` set.
        Use it to reach the human: `osascript -e 'display notification ...'`,
        an ntfy / Bark / WeCom bot webhook `curl`, or `agent-notify send`.
     3. Harnesses without either (e.g. an IM-driven agent): poll `status.sh`
        with short sleeps (≤ 5 min per call).
   - Intervene early: if the intents show it drifting off-spec, or looping on
     a detail after the evidence is already in `artifacts/`, stop it:
     `kill $(cat <run>/omp.pid) $(cat <run>/pid)`, append
     `END rc=killed reason=...` to `<run>/status`, then fix the spec and
     relaunch, or conclude from the artifacts yourself.
5. **Review before believing**:
   - code: read the full `git diff` in the worktree, rerun the self-checks and
     the gradle gate yourself;
   - release: PR, CI conclusions, published asset sha, manifest pin;
   - device: re-read raw logs sliced to this run (logs are appended), look at
     the screenshots. Write disagreements into `<run dir>/reviewer-notes.md`.
6. **Clean up**: `git worktree remove .tmp/wt/<name>` and
   `git branch -D sub/<name>` once merged or abandoned.

## Spec rules that paid off

- The scripts close stdin (`</dev/null`); otherwise omp can hang silently.
- Forbid `commit`/`push`/`stash`/`reset` unless the step is a release step;
  for release steps whitelist exact commands and forbid `--force`, pushing
  `main`, cancelling runs, and hand-editing manifests.
- Tell it to stop and report `FAIL` on anything unexpected.
- Background processes inside a subagent (logcat etc.) need `nohup` and a
  "file size > 0 after 5 s" check.
- BuildStream inline commands run under dash; put bash logic in `ci/**/*.sh`.
- macOS bash 3.2: write `${var}` before any non-ASCII character.
- One subagent per device and per worktree at a time.
- Run every evidence command yourself before putting it in the spec, and
  paste it verbatim (quoting included). An unverified grep is where a
  subagent burns tokens: a1-audio-play spent ~40 of 96 calls re-capturing
  `dumpsys` after its own grep quoting failed.
- Give UI paths as exact coordinates or a deep link / debug extra, not "find
  the setting". Hunting a Settings row cost 17 calls.
- Every check has three outcomes: PASS, FAIL, UNVERIFIED (command failed,
  raw output saved). Say "a check command failing twice → mark UNVERIFIED and
  move on; never re-run a round to satisfy one check". Without this, "any
  unmet → FAIL" pushes the model to redo work instead of reporting.
- Put a call budget in the spec ("expect ~40 tool calls; past 60, stop and
  report what you have") and pass a hard cap `--max-calls 1.5N`; the script
  stops omp past it (`BUDGET` in `status`). `status.sh` shows calls and tokens
  per run. omp's own `model.toolCallLoopGuard` only fires on
  5 identical consecutive calls, so it does not catch varied retries.
- First gradle build in a fresh worktree takes minutes; say so in the spec so
  the subagent does not give up.

Worked example (three repos, parallel edits, ordered release, device smoke):
[`examples/wineandroid-converge.md`](examples/wineandroid-converge.md).
