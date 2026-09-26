---
name: Amphora from zero
description: >-
  use this when a new agent (or human) starts Amphora Android work from scratch:
  clone paths, branch, build APK, HA262 smoke, multi-bot/skills, hard bans —
  before editing wineandroid shell, present, or imagefs-related app code.
---
# Amphora from zero

Full methodology (Chinese): [`docs/08-AGENT-BOOTSTRAP.md`](../../../docs/08-AGENT-BOOTSTRAP.md).  
Also read [`AGENTS.md`](../../../AGENTS.md) first.

## When

Onboarding, handoff, or any agent that has not worked this tree before. Not a substitute for track-specific recipes (shell Surface, device smoke, imagefs CAS).

## Machines

One Mac does code, build and adb: repo `~/co/github/amphora`, siblings `../proton-wine`, `../imagefs`, `../content_manifest`.
Devices: Y700 `HA262AAH` (baseline), OnePlus 6T `5b1736c7` (old GPU / Android 15). One owner per device at a time.

## Clone + branch

```bash
mkdir -p ~/co/github && cd ~/co/github
git clone git@github.com:amphora-dev/amphora.git   # or gh repo clone
cd amphora && git submodule update --init --recursive
git fetch origin && git switch -c wip/<topic> origin/main   # base on main; rebase before push
./gradlew spotlessCheck :app:testDebugUnitTest :app:lintDebug   # pre-push gate (enforced by .githooks)
```

Org siblings as needed: `imagefs`, `content_manifest`, `proton-wine`.

Git author (local): `skywalker512` / `houzhenhong@outlook.com`.

## Read order

1. `AGENTS.md` → `docs/08-AGENT-BOOTSTRAP.md`
2. Progress: `docs/02-TRACKING.md` last section (current-status pointer) + `git log -15`; open investigation in `docs/09-AIO-VK-PRESENTMODES-STATUS.md`
3. Shell: `docs/04-WINEANDROID-DISPLAY.md`
4. Present (only if tasked): `docs/05-AHB-IMPORT-PRESENT.md`

## Tracks (do not mix casually)

- **A shell:** wineandroid Surface / layout / input — `docs/04-WINEANDROID-DISPLAY.md`
- **B present:** AHB / HWND zero-copy — `docs/05-AHB-IMPORT-PRESENT.md` (gates closed; next often CI artifact smoke)
- **C build:** imagefs WCP + manifest (`docs/03-ASSET-MANIFEST.md`, `docs/07-DEV-PIN-OVERLAY.md`)

## Hard bans

See `AGENTS.md` (出画不变量). Also: keep the bottom `statusView` debug strip; never ship a hand-copied `.so` (use dev pins).

## Build (APK)

On **Mac** APK truth tree:

```bash
./gradlew :app:assembleDebug
adb -s HA262AAH install -r app/build/outputs/apk/debug/app-debug.apk
```

## Smoke (unattended)

1. `adb devices` → require `HA262AAH` `device` or **stop**.
2. Launch `app.amphora/.MainActivity` → Windows Desktop → button reads **Open desktop** when ready, or **Prepare and open desktop** when pinned components are missing/outdated (“Environment needs attention”). Component download/install only happens after tapping it, not while idling on the home screen → `WineAndroidSessionActivity`.
3. Logcat filters: `WineAndroidDesktop|WineAndroidHostBridge|defer first register|registerSurface|motion|key hwnd|keyboard hwnd`.
4. PASS examples: defer then real-size register; `motion … ok=true` + visible click; hardware key → `key hwnd=… ok=true` / `keyboard hwnd=…`; soft IME is explicit-only (no tap/focus auto-show); no FATAL/BGRA crash.
5. Save log/screencap under Mac `…/smoke-artifacts/`.
6. **One owner** installs; no parallel adb install wars.

## Multi-bot / skills / routines

- Prefer **one owner** bot + optional read-only specialist + skills + routines.
- In-repo skill: this file. Companion docs: [`docs/08-AGENT-BOOTSTRAP.md`](../../../docs/08-AGENT-BOOTSTRAP.md).
- Headless subagents (omp + glm-5.3-flash), **claude-opus-5.5 orchestrator only**: [`../subagent-dispatch/SKILL.md`](../subagent-dispatch/SKILL.md).
- Specialists must not change window granularity (top-level-only / TextureView / X11) unless the user asked.

## Report

HEAD + push?, files, which machine built, smoke PASS/FAIL with log quotes, **still open** items.
