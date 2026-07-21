# Amphora

A modern, minimal-first, long-term-engineered Android Wine emulator.

> **Status: v0.1 end-to-end working** — launch a Windows `.exe` with Vulkan desktop surface + relative touch on device.
> Architecture: [`docs/05-ARCHITECTURE.md`](docs/05-ARCHITECTURE.md) · RFC: [`docs/01-RFC.md`](docs/01-RFC.md) · Tracking: [`docs/03-TRACKING.md`](docs/03-TRACKING.md)

Name from *amphora* — the ancient two-handled vessel that carried wine. A container that holds Wine containers.

## Principles

1. **Engine/feature isolation** — the Wine/render/input kernel is stable; features depend on it, never the reverse.
2. **Pluggable content source** — `BundledContentSource` (MVP) → `RemoteContentSource` (later), engine-agnostic.
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
# Optional but required for a runnable APK with Proton/Box64/DXVK/rootfs:
./gradlew :app:stageBundledContent   # needs ../WinNative (or -Pamphora.winnative.dir=...) + network for .wcp

./gradlew :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk  (arm64-v8a only)
```

Instrumented E2E (device with Adreno recommended):

```bash
./gradlew :app:connectedDebugAndroidTest
# or the content-aware variant used by GameSessionLaunchTest — see app/build.gradle.kts
```

**Notes**

- `stageBundledContent` is **not** wired to `preBuild` (keeps routine debug APKs slim).
- Debug nav currently auto-launches `assets/exe/notepad.exe` into `GameSessionScreen`; revert `AmphoraNavHost` to `LauncherRoute` for the SAF picker flow.
- `targetSdk` is **28** on purpose (exec box64/Wine from `filesDir`); do not bump without an exec-path redesign — see `ConventionHelpers.kt`.

## Docs

| Doc | Role |
|---|---|
| [`docs/05-ARCHITECTURE.md`](docs/05-ARCHITECTURE.md) | As-built architecture (start here) |
| [`docs/01-RFC.md`](docs/01-RFC.md) | Project decisions (D1–D9) |
| [`docs/03-TRACKING.md`](docs/03-TRACKING.md) | Progress / agent handoff |
| [`docs/04-ASSET-MANIFEST.md`](docs/04-ASSET-MANIFEST.md) | Asset SHA locks |
| [`docs/02-SCAFFOLD.md`](docs/02-SCAFFOLD.md) | Scaffold-era stack & pitfalls |
| [`docs/00-RESEARCH.md`](docs/00-RESEARCH.md) | WinNative research basis |
