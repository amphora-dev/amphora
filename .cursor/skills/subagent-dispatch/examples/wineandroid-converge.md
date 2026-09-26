# Example: three-repo change + ordered release (2026-09-26)

What was actually run for the wineandroid NDK-symbol convergence, reshaped to
the skill layout: specs and the chain file under the ignored `.tmp/specs/`,
repo-relative paths.

## Phase 1: parallel code changes (one subagent per repo)

```bash
S=.cursor/skills/subagent-dispatch/scripts
$S/dispatch.sh --orchestrator claude-opus-5.5 --name proton-jni \
  --cwd ../proton-wine --task .tmp/specs/B-proton-wine-jni.md --result-key B --max-time 40m --async
$S/dispatch.sh --orchestrator claude-opus-5.5 --name amphora-host \
  --cwd .             --task .tmp/specs/C-amphora-host.md     --result-key C --max-time 40m --async
$S/dispatch.sh --orchestrator claude-opus-5.5 --name imagefs-identity \
  --cwd ../imagefs    --task .tmp/specs/D-imagefs-identity.md --result-key D --max-time 30m --async
```

Disjoint repos, disjoint files, no git writes. The orchestrator then reviewed
each diff; one spec premise was wrong (ANWB offset) and was reverted by hand.

## Phase 2: ordered release

`.tmp/specs/release.chain`:

```
# name|cwd|task|max-time|RESULT_KEY
amphora-push|../..|R1-amphora-push.md|60m|STEP1
imagefs-box64|../../../imagefs|R2-imagefs-box64.md|150m|STEP2
proton-release|../../../proton-wine|R3-proton-release.md|180m|STEP3
```

(cwd is relative to the chain file's directory, `.tmp/specs/`.)

```bash
$S/chain.sh --orchestrator claude-opus-5.5 --chain .tmp/specs/release.chain --async
```

Step 2 failed in CI (`${var:0:8}` under dash); the chain stopped as designed,
the orchestrator fixed it in a separate PR, then launched step 3 alone with
`dispatch.sh`. Release specs whitelisted exact commands: push the wip branch,
`gh pr create/checks/merge --rebase`, read-only `gh run`/`gh release`/`gh api`,
at most one `gh workflow run` if no push-triggered run appeared.

## Phase 3: device smoke

One subagent, one device (`HA262AAH`), artifacts in `$SUBAGENT_RUN_DIR/artifacts`.
First attempt was killed: the spec said components update on the home screen;
they update when the session opens. The rerun passed, but its report quoted
lines from the previous run in the appended `wine_stderr.log`, and undercounted
`Unable to lock surface`. The orchestrator sliced the log to this run and wrote
`reviewer-notes.md`.
