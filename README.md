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
:core:{content,container,rootfs,native,common}
```

Contracts live in the lower modules; Winlator-backed implementations live in `:core:engine` (DIP). See the architecture doc for the launch pipeline and Vulkan/touch path.

## Quick start

```bash
./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk  (arm64-v8a only)
```

The APK stays slim. On first launch the device downloads SHA-pinned Rootfs,
Proton, Box64, DXVK, VKD3D and runtime assets; installed assets are reused on
later launches. The optional PulseAudio backend is the exception: its matched
Android native libraries and small module archive ship in the APK so the
daemon/client ABI stays together. The launcher shows app / imagefs versions and
download progress.

The pins come from `content_manifest.json`, fetched at runtime from
[`amphora-dev/content_manifest`](https://github.com/amphora-dev/content_manifest)
through the GitHub Contents API on `main` (raw GitHub fallback; no APK-bundled
manifest fallback). Changing a URL or SHA there does not require rebuilding the
APK; bump `rootfs.version` when the installed imagefs tree must be replaced.
imagefs CI updates that repo after each Release publish.

Instrumented E2E (ARM64 Adreno device recommended):

```bash
./gradlew :app:connectedAndroidTestWithContent
```

The aggregate task stages the manifest-pinned content before running device
tests. Plain `connectedDebugAndroidTest` is useful for a quick run, but
asset-gated tests may skip when the slim APK has no staged runtime content.

Repository-wide JVM tests (Android modules with test sources plus build logic):

```bash
./gradlew jvmTest
```

GitHub Actions CI (`.github/workflows/ci.yml`): `continuous-test` on pushes to
`main` and on pull requests—JVM unit tests with JaCoCo coverage summary plus
debug/androidTest assembly.
On `main`, the same job also publishes the debug APK to the rolling Release tag
`apk` and pins `app_update.json` in
[`amphora-dev/content_manifest`](https://github.com/amphora-dev/content_manifest)
(GitHub Contents API, raw GitHub fallback). Settings → **App update** checks that pin.
Device instrumented coverage stays on Tailscale ADB - see
[`docs/06-ENVIRONMENT.md`](docs/06-ENVIRONMENT.md) §6.

**Notes**

- `stageBundledContent` is **not** wired to `preBuild` (keeps routine debug APKs slim). It writes only to
  `app/build/generated/assets/bundledContent`, which is registered as a main asset source set; `clean` removes it.
- Remote/cloud ADB setup and reliable manual test commands: [`docs/06-ENVIRONMENT.md`](docs/06-ENVIRONMENT.md).
- Debug Wine path: launcher **Debug: Wine smoke test** button; it launches
  `SessionActivity` in the isolated `:session` process.
- `targetSdk` is **36**. App-private AArch64 ELF files start through
  `/system/bin/linker64`; `libamphora-exec.so` preserves that routing for
  Box64/Wine descendants.

## Docs

| Doc | Role |
|---|---|
| [`docs/05-ARCHITECTURE.md`](docs/05-ARCHITECTURE.md) | As-built architecture (start here) |
| [`docs/01-RFC.md`](docs/01-RFC.md) | Project decisions (D1–D9) |
| [`docs/03-TRACKING.md`](docs/03-TRACKING.md) | Progress / agent handoff |
| [`docs/04-ASSET-MANIFEST.md`](docs/04-ASSET-MANIFEST.md) | Asset SHA locks |
| [`docs/06-ENVIRONMENT.md`](docs/06-ENVIRONMENT.md) | Cloud build, Tailscale ADB and physical-device testing |
| [`docs/07-TARGETSDK-SELINUX.md`](docs/07-TARGETSDK-SELINUX.md) | targetSdk 36 app-private ELF execution |
| [`docs/08-EGGGAME-COMPARISON.md`](docs/08-EGGGAME-COMPARISON.md) | GameHub / WinNative / Amphora comparison |
| [`docs/09-FRAME-GENERATION-RESEARCH.md`](docs/09-FRAME-GENERATION-RESEARCH.md) | GameHub、WinNative 与开源插帧方案审计 |
| [`docs/RESEARCH-proton-wine-selfbuild.md`](docs/RESEARCH-proton-wine-selfbuild.md) | Proton source-build research and current BuildStream result |
| [`docs/WRAPPER-BUILD.md`](docs/WRAPPER-BUILD.md) | Rebuilding the Vulkan wrapper |
| [`docs/02-SCAFFOLD.md`](docs/02-SCAFFOLD.md) | Scaffold-era stack & pitfalls |
| [`docs/00-RESEARCH.md`](docs/00-RESEARCH.md) | WinNative research basis |
