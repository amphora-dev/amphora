# 02 - Scaffold 落地 (As-Built)

> Status: scaffold 完成，`:app:assembleDebug` 绿。本文记录**实际**技术栈与落地约束。
> 日期: 2026-07-11。依据: [`01-RFC.md`](01-RFC.md) §审核清单 scaffold 顺序。
> 组合对齐 Google `android/compose-samples`（Reply/Jetcaster，2026-07）当前参考栈。

## 1. As-Built 技术栈

| 组件 | 版本 | 说明 |
|---|---|---|
| Gradle | 9.4.1 | wrapper 锁定 |
| AGP | 9.2.1 | 最新 stable；`compileSdk` 上限 37 |
| Kotlin (KGP) | **2.3.21** | AGP 9 built-in Kotlin 默认 2.2.10，由 compose 插件（2.3.21）带 KGP 2.3.21 **覆盖升级**到 2.3.21 |
| KSP | 2.3.9 | 解耦 KSP2，对齐 Kotlin 2.3.x；AGP-9 感知（走 android.sourceSets，无需 workaround）|
| Compose compiler | 2.3.21 | `org.jetbrains.kotlin.plugin.compose`，对齐 KGP |
| Compose BOM | 2026.06.01 | |
| Hilt | 2.59.2 | **要求 AGP 9.0+**（2.60.1 亦可，此处对齐 Google 参考）|
| core-ktx | 1.19.0 | 最新（`minCompileSdk=37`）|
| lifecycle | 2.11.0 | 最新（`minCompileSdk=37`）|
| activity-compose | 1.13.0 | |
| navigation-compose | 2.9.8 | |
| hilt-navigation-compose | 1.3.0 | |
| NDK | 28.0.13004108 (r28) | RFC 写 r27/r29，r28 实测可用 |
| CMake | 3.31.1 | |
| compileSdk / targetSdk / minSdk | **37** / 36 / 26 | 平台包名 `platforms;android-37.0`（带 `.0`）|
| JDK | 17 (Gradle 跑 JBR 21) | |

## 2. 关键约束 / 踩坑

1. **AGP 9 built-in Kotlin** - AGP 9.0 起内置 KGP（默认 2.2.10），**禁止** `org.jetbrains.kotlin.android` 插件。convention 不 apply kotlin-android；`jvmTarget` 默认取 `compileOptions.targetCompatibility`（17）。Compose compiler 插件仍手动 apply，版本对齐 KGP。
2. **Kotlin 2.3.21 怎么来的** - built-in 默认 2.2.10，但 compose 插件 `org.jetbrains.kotlin.plugin.compose:2.3.21` 依赖 KGP 2.3.21，apply 后把进程 KGP 升到 2.3.21（AGP 允许 ≥2.2.10）。KSP 须对齐到 2.3.9。→ **不必接受 2.2.10，可直接用最新 Kotlin 2.3.21**。
3. **Hilt 2.59.2+ → AGP 9** - Hilt 2.59 起仅兼容 AGP ≥ 9.0.0。要最新 Hilt 就得上 AGP 9。
4. **`platforms;android-37.0`（带 `.0`）** - 最新 AndroidX（core 1.19 / lifecycle 2.11 / hilt-nav 1.4 等）`minCompileSdk=37`。平台包名是 `platforms;android-37.0` 不是 `platforms;android-37`（后者 sdkmanager 报 not found）。装上即可 compileSdk 37、用最新 AndroidX。
5. **`native` 是 Java 关键字** - `:core:native` 的 AGP namespace 不能含 `native`，用 `app.amphora.core.nativelib`（模块路径仍 `:core:native`；移植的 `com.winlator.cmod` JNI 类不受影响）。
6. **AGP 9 DSL 变化** - `CommonExtension` 去泛型化；`compileOptions{}`/`testOptions{}` 块方法从 `CommonExtension` 移除（只剩属性 getter），convention 改属性访问（`compileOptions.sourceCompatibility = ...`）。`defaultConfig{}`/`buildFeatures{}`/`externalNativeBuild{}` 块仍在 `ApplicationExtension`/`LibraryExtension`。
7. **Kotlin DSL reified 扩展要显式 import** - `.kt` 源码（convention plugin）里 `extensions.getByType<T>()`/`findByType<T>()` 不自动导入，需 `import org.gradle.kotlin.dsl.getByType/findByType`。

## 3. 模块图（已落地）

```
:app  ── :feature:launcher, :feature:settings, :core:ui, :core:common, :core:engine
:feature:launcher ── :core:engine
:core:engine ── api(:core:common, :content, :container) + impl(:core:native, :rootfs)
:core:container ── api(:core:common, :content)
:core:content  ── api(:core:common)
:core:rootfs   ── api(:core:common)
:core:ui       ── api(:core:common)
:core:native   ── (无向上依赖；CMake 出 libwinlator.so + libfakeinput.so)
:core:common   ── api(kotlinx-coroutines)
```

依赖方向严格单向 `feature -> engine -> {native, rootfs, content, container}`（RFC §6）。

## 4. Convention 插件（build-logic）

| 插件 id | 作用 |
|---|---|
| `amphora.android.application` | com.android.application + common android + 测试依赖 |
| `amphora.android.library` | com.android.library + common + 测试依赖 |
| `amphora.android.compose` | kotlin.plugin.compose + buildFeatures.compose + Compose BOM/ui/material3/tooling |
| `amphora.android.hilt` | ksp + hilt + hilt-android/ksp(hilt-compiler) |
| `amphora.android.native` | ndkVersion + cmake path + arm64-v8a abiFilter（须先 apply library）|
| `amphora.android.feature` | library + compose + hilt（便捷组合）|

## 5. 关键接口（已定义，RFC §6）

- `:core:content` - `ContentSource.resolve(ComponentId): ContentArtifact`；`ContentComponent` enum（ROOTFS/WINE/BOX64/TURNIP/DXVK/AUDIO_PLUGIN）。
- `:core:engine` - `WineEngine.launch(LaunchSpec): SessionHandle` + `inputFeed(): InputSink` + `audioSink(): AudioSink`；`SessionHandle.state: StateFlow<SessionState>`。`StubWineEngine` + `EngineModule`(@Hilt 绑定) 使 DI 图端到端可编译，`launch()` TODO 待 runtime 移植。
- `:core:container` - `ContainerManager`；`:core:rootfs` - `RootfsInstaller`。

## 6. 验证

```
./gradlew help                     # 全工程配置通过
./gradlew :app:assembleDebug       # BUILD SUCCESSFUL（Kotlin 2.3.21 + KSP/Hilt + native .so + Compose + APK）
./gradlew :core:common:test        # 单测骨架通过
```

APK `app/build/outputs/apk/debug/app-debug.apk` 内含 `lib/arm64-v8a/libwinlator.so` + `libfakeinput.so`（CMake/NDK 管线端到端验证）。

## 7. 下一步（v0.1 实现入口）

1. `:core:native` - 移植 WinNative `cpp/winlator/`（48 JNI，保 `com.winlator.cmod` 包名）+ 12 个 Java 调用类（RFC §7/D5）。替换 CMake stub。
2. `:core:engine` - 移植 `runtime/` 内核（~32k 行 Java，RFC §7）；`WineSessionPreparer` 抽取自 XSDA（D9）；`StubWineEngine` 换真实实现。
3. `:core:rootfs` - 接 winlator-imagefs 产物（RFC §11）。
4. `:app` `GameSessionScreen` - Compose 重写薄壳（`AndroidView{SurfaceView}` + `TouchpadView`，D9）。
