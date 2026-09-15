---
name: Amphora from zero
description: >-
  use this when a new agent (or human) starts Amphora Android work from scratch:
  clone paths, branch, build APK, HA262 smoke, multi-bot/skills, hard bans —
  before editing wineandroid shell, present, or imagefs-related app code.
---
# Amphora from zero

Full methodology (Chinese): [`docs/19-AGENT-BOOTSTRAP.md`](../../../docs/19-AGENT-BOOTSTRAP.md).  
Also read [`AGENTS.md`](../../../AGENTS.md) first.

## When

Onboarding, handoff, or any agent that has not worked this tree before. Not a substitute for track-specific recipes (shell Surface, device smoke, imagefs CAS).

## Machines

| Job | Where |
|-----|--------|
| Code / docs | Grok Bot: `/home/box/co/github/amphora` (do not delete `/workspace/amphora-dev/amphora`) |
| APK + adb | Mac: `/Users/sky/co/src/amphora-dev/amphora` |
| Device | Y700 serial `HA262AAH` |

**This track:** edit on Grok Bot computer only — **no Cursor cloud** checkouts for amphora shell work. Do **not** CopyFromBox ~80MB APKs.

## Clone + branch

```bash
mkdir -p /home/box/co/github && cd /home/box/co/github
git clone git@github.com:amphora-dev/amphora.git   # or gh repo clone
cd amphora && git submodule update --init --recursive
git fetch origin wip/ha262-paint && git checkout wip/ha262-paint
git merge --ff-only FETCH_HEAD   # prefer FETCH_HEAD if origin/* looks stale
```

Org siblings as needed: `imagefs`, `content_manifest`, `proton-wine`.

Git author (local): `skywalker512` / `houzhenhong@outlook.com`.

## Read order

1. `AGENTS.md` → `docs/19-AGENT-BOOTSTRAP.md`
2. Progress: Mac `amphora-progress.md` if present, else `git log -15`
3. Shell: `docs/16` → `17` → `18`; migration phases `docs/12`
4. Present (only if tasked): `docs/13`–`14`

## Tracks (do not mix casually)

- **A shell:** wineandroid Surface / layout / input — docs 16–18
- **B present:** AHB / HWND zero-copy — docs 13–14 (gates closed; next often CI artifact smoke)
- **C build:** imagefs WCP + manifest

## Hard bans

BGRA=5 on Surface path; delete `statusView`; private CreateSwapchain / private `host.sock` as truth; blind TextureView / X11-as-default; temp `.so` as release; noisy tree deletes; Cursor cloud edits on this track.

## Build (APK)

On **Mac** APK truth tree:

```bash
./gradlew :app:assembleDebug
adb -s HA262AAH install -r app/build/outputs/apk/debug/app-debug.apk
```

## Smoke (unattended)

1. `adb devices` → require `HA262AAH` `device` or **stop**.
2. Launch `app.amphora/.MainActivity` → Open desktop → `WineAndroidSessionActivity`.
3. Logcat filters: `WineAndroidDesktop|WineAndroidHostBridge|defer first register|registerSurface|motion|key hwnd|keyboard hwnd`.
4. PASS examples: defer then real-size register; `motion … ok=true` + visible click; hardware key → `key hwnd=… ok=true` / `keyboard hwnd=…` (IME still open); no FATAL/BGRA crash.
5. Save log/screencap under Mac `…/smoke-artifacts/` (not only `/tmp` if you need CopyToBox).
6. **One owner** installs; no parallel adb install wars.

## Multi-bot / skills / routines

- Prefer **one owner** bot + optional read-only specialist + skills + routines.
- In-repo skill: this file. Companion docs: `docs/19`.
- Grok Bot may also have: HA262 wineandroid shell, HA262 device smoke, imagefs CAS build — use them after this onboarding skill.
- Specialists must not change window granularity (top-level-only / TextureView / X11) unless the user asked.

## Report

HEAD + push?, files, which machine built, smoke PASS/FAIL with log quotes, **still open** items.
