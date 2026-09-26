---
name: Subagent dispatch
description: >-
  use this when the orchestrating agent is claude-opus-5.5 and wants to hand a
  well-scoped coding, release, or device-smoke task to a cheaper headless
  subagent (omp + glm-5.3-flash): write the task spec, launch, chain steps,
  then review the result. Do not use from any other model.
---
# Subagent dispatch

## Who may use this

Only an orchestrator running as **`claude-opus-5.5`**. Every script refuses to
start unless `--orchestrator claude-opus-5.5` is passed (the API model id
`claude-opus-5-5` is accepted too), and refuses when it is
already running inside a subagent (`AMPHORA_SUBAGENT=1`), so subagents cannot
dispatch further subagents. This is a guard against misuse, not a security
boundary: an agent that is not opus-5.5 must not pass the flag.

The orchestrator stays responsible for the result. A subagent report is a
claim, not evidence.

## Where things live (no device paths)

All state goes under the repo's ignored `.tmp/` directory:

```
<repo>/.tmp/specs/                 specs and .chain files you write
<repo>/.tmp/subagents/<UTC>-<name>/
  task.md        copy of the spec that was run
  run.log        full omp output
  status         START / END lines with the RESULT line
  cmd            the exact omp invocation
  pid            background pid (--async only)
  artifacts/     subagent outputs ($SUBAGENT_RUN_DIR/artifacts)
```

Specs go under `.tmp/specs/` because it is ignored: an untracked `specs/` in
the repo root makes a release step's `git status --short` check fail.

Override the run-dir root with `SUBAGENT_HOME=<dir>`. Sibling repos are addressed as
`"$(git rev-parse --show-toplevel)/../<repo>"`, never `/Users/...`.
Device-smoke artifacts go in `<run dir>/artifacts/` (pass it to the subagent as
`$SUBAGENT_RUN_DIR/artifacts`).

## Prerequisites

- `omp` on PATH (`omp --version`), model `tencent-intranet/glm-5.3-flash-ioa`
  visible in `omp models`. Override with `SUBAGENT_MODEL`.
- `gh` logged in if the task pushes; note the token may lack `workflow` scope
  (pushing `.github/workflows/**` then fails; split those changes out).

## Workflow

1. **Verify the facts yourself first.** The subagent executes the spec
   literally. Wrong premises in the spec become wrong code or wrong test flows
   (seen: "AHB→ANWB offset is 0", "components update on the home screen").
2. **Write the spec** from [`templates/task.md`](templates/task.md): verified
   background, allowed ops, forbidden ops, exact steps, self-check commands,
   deliverables, and one final line `<KEY>_RESULT=OK|FAIL ...`.
3. **Launch**:
   ```bash
   S=.cursor/skills/subagent-dispatch/scripts
   $S/dispatch.sh --orchestrator claude-opus-5.5 \
     --name proton-jni --cwd ../proton-wine --task .tmp/specs/proton-jni.md \
     --result-key STEP1 --max-time 40m --async
   ```
   Independent repos can run in parallel (one subagent per repo, disjoint files).
   Anything with ordering (push → CI → next step) goes through `chain.sh`.
4. **Chain dependent steps**:
   ```bash
   $S/chain.sh --orchestrator claude-opus-5.5 --chain .tmp/specs/release.chain --async
   ```
   Chain file, one step per line: `name|cwd|task.md|max-time|RESULT_KEY`
   (paths relative to the chain file; `#` comments). A step that does not end
   with `<KEY>_RESULT=OK` stops the chain.
5. **Watch**: `$S/status.sh` (all runs) or `$S/status.sh <run dir>`.
   Poll in short sleeps (≤5 min per tool call); long single waits get cut off.
   If the harness can run a command in the background and notify on exit
   (Claude Code: Bash `run_in_background`), run `dispatch.sh`/`chain.sh`
   without `--async` that way instead of polling.
6. **Review before believing**:
   - code: read the full `git diff`, re-run the self-checks, build/tests yourself;
   - release: check the PR, CI run conclusions, published asset sha, manifest pin;
   - device: re-read raw logs, slice to *this* run (logs are often appended),
     look at the screenshots.
   Write disagreements into `<run dir>/reviewer-notes.md`.

## Spec rules that paid off

- Always run omp with stdin closed (the scripts do `</dev/null`); otherwise the
  CLI can hang waiting for input with no output.
- Forbid `commit`/`push`/`stash`/`reset` unless the step is explicitly a release
  step; for release steps, whitelist exact commands and forbid `--force`,
  pushing `main`, cancelling runs, and hand-editing manifests.
- Tell it to stop and report `FAIL` on anything unexpected instead of improvising.
- Background processes inside a subagent (logcat etc.) need `nohup` and a
  "file size > 0 after 5 s" check.
- BuildStream inline commands run under dash; put bash logic in `ci/**/*.sh`.
- macOS ships bash 3.2: write `${var}` before any non-ASCII character, or the
  bytes get parsed into the variable name (`$run_dir（` → unbound variable).
- Never let two subagents own the same device or the same repo at once.

## Scripts

| Script | Purpose |
|---|---|
| `scripts/dispatch.sh` | run one spec with omp, record run dir, extract RESULT line |
| `scripts/chain.sh` | run steps sequentially, stop at first non-OK |
| `scripts/status.sh` | list runs and their last status line |
| `scripts/lib.sh` | shared guard + path helpers |

Worked example (three repos, parallel edits, ordered release, device smoke):
[`examples/wineandroid-converge.md`](examples/wineandroid-converge.md).
