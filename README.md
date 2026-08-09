# Amphora

A modern, minimal-first, long-term-engineered Android Wine emulator.

> **Status: v0.1 end-to-end working** — launch a Windows `.exe` with Vulkan desktop surface + relative touch on device.
> Architecture: [`docs/05-ARCHITECTURE.md`](docs/05-ARCHITECTURE.md) · RFC: [`docs/01-RFC.md`](docs/01-RFC.md) · Tracking: [`docs/03-TRACKING.md`](docs/03-TRACKING.md)

Name from *amphora* — the ancient two-handled vessel that carried wine. A container that holds Wine containers.

## Principles

1. **Engine/feature isolation** — the Wine/render/input kernel is stable; features depend on it, never the reverse.
2. **Pluggable content source** — production uses `RemoteContentSource`; the engine remains source-agnostic.
3. **Stable native ABI** — C/C++ layer exposes versioned JNI; Kotlin never touches native internals.
4. **Reproducible builds** — every external binary version-locked + hash-verified.

## Modules

```
:app / :feature:{launcher,settings}
        ↓
:core:engine          WineEngine + Winlator runtime (com.winlator.cmod)
        ↓
:core:{content,container,rootfs,native,common,ui}
```

Contracts live in the lower modules; Winlator-backed implementations live in `:core:engine` (DIP). See the architecture doc for the launch pipeline and Vulkan/touch path.

## Quick start

```bash
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk  (arm64-v8a only)
```

The APK stays slim. On first launch the device downloads SHA-pinned Rootfs,
Proton, Box64, DXVK, VKD3D and runtime assets; installed assets are reused on
later launches. The launcher shows app / imagefs versions and download progress.

The pins come from `content_manifest.json`, fetched at runtime from
[`amphora-dev/content_manifest`](https://github.com/amphora-dev/content_manifest)
(`main` raw URL — no APK-bundled fallback). Changing a URL or SHA there does not
require rebuilding the APK; bump `rootfs.version` when the installed imagefs
tree must be replaced. imagefs CI updates that repo after each Release publish.

Instrumented E2E (ARM64 Adreno device recommended):

```bash
./gradlew :app:connectedDebugAndroidTest
```

GitHub Actions CI (`.github/workflows/ci.yml`): `continuous-test` on every push
/ PR - JVM unit tests with JaCoCo coverage summary + debug/androidTest assemble.
On `main`, the same job also publishes the debug APK to the rolling Release tag
`apk` and pins `app_update.json` in
[`amphora-dev/content_manifest`](https://github.com/amphora-dev/content_manifest)
(GitHub Contents API, raw GitHub fallback). Settings → **App update** checks that pin.
Device instrumented coverage stays on Tailscale ADB - see
[`docs/06-ENVIRONMENT.md`](docs/06-ENVIRONMENT.md) §6.

**Notes**

- `stageBundledContent` is **not** wired to `preBuild` (keeps routine debug APKs slim).
- Remote/cloud ADB setup and reliable manual test commands: [`docs/06-ENVIRONMENT.md`](docs/06-ENVIRONMENT.md).
- Debug Wine path: launcher **Debug: Wine smoke test** button, or flip `DEBUG_AUTO_LAUNCH_WINE` in `AmphoraNavHost` (default starts at launcher).
- `targetSdk` is **28** on purpose (exec box64/Wine from `filesDir`); do not bump without an exec-path redesign — see `ConventionHelpers.kt`.

## Docs

| Doc | Role |
|---|---|
| [`docs/05-ARCHITECTURE.md`](docs/05-ARCHITECTURE.md) | As-built architecture (start here) |
| [`docs/01-RFC.md`](docs/01-RFC.md) | Project decisions (D1–D9) |
| [`docs/03-TRACKING.md`](docs/03-TRACKING.md) | Progress / agent handoff |
| [`docs/04-ASSET-MANIFEST.md`](docs/04-ASSET-MANIFEST.md) | Asset SHA locks |
| [`docs/06-ENVIRONMENT.md`](docs/06-ENVIRONMENT.md) | Cloud build, Tailscale ADB and physical-device testing |
| [`docs/02-SCAFFOLD.md`](docs/02-SCAFFOLD.md) | Scaffold-era stack & pitfalls |
| [`docs/00-RESEARCH.md`](docs/00-RESEARCH.md) | WinNative research basis |
<!-- CI push-trigger probe -->
