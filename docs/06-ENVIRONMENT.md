# 06 · Build and device environment (Environment)

This document describes the local build and physical-device test environment
used for Amphora. Everything — code checkout, Gradle build, `adb` and both test
devices — lives on **one Mac**, and both devices attach over USB.

## 1. Build environment

Amphora requires:

- JDK 17
- Android SDK platform and build tools selected by the Gradle version catalog
- Android NDK selected by the Android convention plugin
- `adb` from Android platform tools

The SDK bootstrap helper is `scripts/setup-android-sdk.sh`.

Verify the environment:

```bash
java -version
./gradlew --version
adb version
./gradlew jvmTest
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

Generated APKs:

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

The debug APK is intentionally slim. Rootfs, Proton, Box64, DXVK, VKD3D and
manifest-managed runtime archives are not required in `app/src/main/assets`.
On first launch, the device downloads pinned HTTPS artifacts from
`content_manifest.json`, verifies their SHA-256 and size, and atomically installs
them under app-private storage. Later launches use marker/size checks and do not
download them again. The optional PulseAudio backend is deliberately APK-resident:
its PA13 Android libraries are under `jniLibs` and `pulseaudio.tzst` supplies the
matched client/modules.

The current manifest's default core is roughly 93 MB compressed before shared
fonts and optional components. The installed Wine runtime, prefix and game files
need substantially more space, so reserve several GB and keep the device online.
A warm launch normally takes about 6–7 seconds on the verified device.

`stageBundledContent` still exists (`./gradlew :app:stageBundledContent`) for
diagnostic staging into `app/build/generated/assets/bundledContent/`; it is not
part of the production remote-provisioning path.

## 2. Build constraints

Versions are pinned in `gradle/libs.versions.toml` and
`build-logic/convention/src/main/kotlin/app/amphora/buildlogic/ConventionHelpers.kt`.
Current values (verified against those files):

| Item | Value |
|---|---|
| Gradle wrapper | 9.6.0 (`gradle/wrapper/gradle-wrapper.properties`) |
| AGP | 9.4.0 |
| Kotlin / KSP | 2.3.21 / 2.3.9 (KSP must track Kotlin) |
| Hilt / Compose BOM | 2.59.2 / 2026.06.01 |
| compileSdk / targetSdk / minSdk | 37 / 36 / 30 |
| NDK / CMake | 28.2.13676358 (r28) / 3.31.5 |
| JDK | 17 (toolchain; Gradle itself may run on JBR 21) |

Hard-won rules:

- **AGP 9 built-in Kotlin**: never apply `org.jetbrains.kotlin.android`; the
  compose plugin pulls KGP 2.3.21 over the built-in default. Convention plugins
  do not set `jvmTarget` — it defaults to `compileOptions.targetCompatibility`
  (17).
- **compileSdk 37** resolves as platform package `platforms;android-37.0`
  (with the `.0`), not `platforms;android-37`.
- In convention `.kt` files, `extensions.getByType<T>()` / `findByType<T>()`
  need explicit `import org.gradle.kotlin.dsl.getByType/findByType`.
- Dependency direction is one-way: `feature -> engine -> {native, rootfs,
  content, container}`; `:core:native` never depends upward.
- **Keep the `com.winlator.cmod` package name** in `:core:engine` — JNI exports
  are hard-coded `Java_com_winlator_cmod_...`.
- `:core:native`'s AGP namespace is `app.amphora.core.nativelib` (`native` is a
  Java keyword); the module path stays `:core:native`.

## 3. Build, install and test

Build:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
```

Install (device over USB, e.g. the Lenovo Y700 `HA262AAH`):

```bash
adb -s HA262AAH install -r -t app/build/outputs/apk/debug/app-debug.apk
adb -s HA262AAH install -r -t \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

The preferred complete instrumented run stages content first:

```bash
./gradlew :app:connectedAndroidTestWithContent
```

Or run a selected smoke suite manually; current androidTest classes are
`RemoteContentSourceTest`, `ImagefsExtractionTest`,
`PreparerGraphicsDriverTest`, `SharedDllLinkerTest`,
`SysVSharedMemoryIntegrationTest`, `AlsaRuntimeSupportTest`,
`AppUpdaterIntegrationTest`, `HeadlessWineBootTest`,
`ShizukuIntegrationTest` and `WineInputConnectionTest`. Example:

```bash
TEST_CLASSES="\
app.amphora.RemoteContentSourceTest,\
app.amphora.ImagefsExtractionTest,\
app.amphora.PreparerGraphicsDriverTest"

adb -s HA262AAH shell am instrument -w -r \
  -e class "$TEST_CLASSES" \
  app.amphora.test/app.amphora.HiltTestRunner
```

Do not treat a green result with `assumeTrue` skips as full coverage. Check the
instrumentation output for skipped asset-gated tests, or use the staging
aggregate above.

Use an outer timeout and cleanup while diagnosing:

```bash
timeout --signal=INT 300s adb -s HA262AAH shell am instrument -w -r \
  -e class "$TEST_CLASSES" \
  app.amphora.test/app.amphora.HiltTestRunner

adb -s HA262AAH shell am force-stop app.amphora
```

## 4. Devices

| Device | adb serial | Role |
|---|---|---|
| Lenovo Y700 (TB322FC, Adreno 830, Android 16) | `HA262AAH` | daily baseline |
| OnePlus 6T (Adreno 630, Android 15) | `5b1736c7` | old GPU / old OS regression; **SELinux Permissive** (unofficial ROM, see `docs/02` §4) |

One device is owned by one agent at a time for installs and smoke runs.

## 5. Troubleshooting

### A test remains active after the local command exits

Killing the local `adb` client does not always stop device-side instrumentation.
Clean it explicitly:

```bash
adb -s <serial> shell am force-stop app.amphora
adb -s <serial> shell am force-stop app.amphora.test
```

### First launch is slow

Cold provisioning includes HTTPS downloads, SHA-256 verification, Rootfs
extraction, WCP installation and Wine prefix creation. Do not use cold-run timing
as the steady-state benchmark. Preserve app data between routine tests to
exercise the installed fast path.

### Rootfs tests hang during recursive traversal

An active Wine imagefs contains directory symlinks and can contain cycles. Tests
must verify fixed structural paths and must not recursively follow the entire
installed tree.

## 6. GitHub Actions CI

Amphora uses GitHub Actions via `.github/workflows/ci.yml`.

Job `continuous-test` runs on `ubuntu-24.04` for pushes to `main` and for pull
requests:

`scripts/ci-check.sh spotlessCheck lint :app:assembleDebug :app:assembleDebugAndroidTest`
uses one Gradle invocation for repository JVM tests, JaCoCo aggregation,
formatting, lint and both APK assemblies.

The runner's preinstalled Android SDK (NDK `28.2.13676358`, CMake `3.31.5`) is
used directly. Gradle User Home is cached via `gradle/actions/setup-gradle`
(basic provider).

GitHub Actions does **not** run Android emulator/redroid instrumented tests
(rootless DinD, no binder, arm64-only APK). Physical-device coverage stays on
the local USB devices above.

`scripts/ci-check.sh` also runs `jvmCoverage` and prints the repository summary
from `build/reports/coverage/jvm-summary.txt`; module HTML/XML reports remain
under each module's `build/reports/coverage/`.

`scripts/setup-android-sdk.sh` remains for local SDK bootstrap; CI relies on the
runner's preinstalled SDK instead.

Local equivalent:

```bash
bash scripts/setup-android-sdk.sh
bash scripts/ci-check.sh   # no args = JVM tests + coverage only
bash scripts/ci-check.sh spotlessCheck lint :app:assembleDebug :app:assembleDebugAndroidTest
```
