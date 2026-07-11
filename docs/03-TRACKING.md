# 03 - 进度跟踪 / Handoff

> 给下一个 agent 的接手文档。living checklist--完成就勾。
> 最后更新: 2026-07-11 · HEAD `fc14357` · 阶段: **scaffold 完成, 进入 v0.1 实现**
> 必读: [`00-RESEARCH.md`](00-RESEARCH.md) · [`01-RFC.md`](01-RFC.md) · [`02-SCAFFOLD.md`](02-SCAFFOLD.md)

---

## 0. 状态快照

- ✅ scaffold 已落地并提交 (`fc14357`)。`./gradlew :app:assembleDebug` 绿, APK 含 `libwinlator.so`+`libfakeinput.so`。
- ✅ 技术栈对齐 Google `android/compose-samples` 当前参考 (比 `android/nowinandroid` 新一档)。详见 [02-SCAFFOLD.md §1](02-SCAFFOLD.md)。
- ⏭ 下一步: v0.1 实现--移植 WinNative native + runtime 内核, 跑通一个 exe。

| 项 | 值 |
|---|---|
| AGP / Gradle / Kotlin / KSP | 9.2.1 / 9.4.1 / 2.3.21 / 2.3.9 |
| Hilt / Compose BOM | 2.59.2 / 2026.06.01 |
| compileSdk / targetSdk / minSdk / NDK | 37 / 36 / 26 / r28 (28.0.13004108) |
| 包名 / 模块数 | `app.amphora` / 10 模块 + build-logic |

---

## 1. 已完成 ✅

- [x] `build-logic` 6 convention 插件 (application/library/compose/hilt/native/feature)
- [x] `gradle/libs.versions.toml` 版本目录
- [x] 10 模块空壳: `:app` + `:core:{common,ui,native,rootfs,content,container,engine}` + `:feature:{launcher,settings}`
- [x] `:core:native` CMake 桩 -> `libwinlator.so` + `libfakeinput.so` (arm64-v8a, 已打进 APK)
- [x] 核心接口定义: `WineEngine` / `ContentSource` / `ContainerManager` / `RootfsInstaller` + `LaunchSpec`/`SessionHandle`/`InputSink`/`AudioSink`
- [x] `StubWineEngine` + `EngineModule`(@Hilt) 使 DI 图端到端可编译
- [x] `:app` Compose 壳 (NavHost: launcher/settings/session + 主题) + 单测/Instrumentation 骨架
- [x] `docs/02-SCAFFOLD.md` (as-built 栈 + 踩坑)

---

## 2. v0.1 实现路线 (按依赖序)

### 源码定位 (已核实, 非 RFC 旧路径)
WinNative 本地 checkout: `/Users/sky/co/github/WinNative` (remote `WinNative-Emu/WinNative`, branch `main`, HEAD **`48fe6b9`**)
- **native C/C++**: `WinNative/app/src/main/cpp/`
  - 要移植: `cpp/winlator/` (`vk/vk_renderer.c` 3.2k 行 + 8 其它源, 48 JNI), `cpp/winlator/fakeinput.cpp`
  - 死代码不移植: `cpp/proot/`, `cpp/patchelf/`, `cpp/wn-steam-*` (RFC §7/D6)
  - 依赖: `android_sysvshm/` (top-level), `audio_plugin/` (top-level, 随 imagefs 进 rootfs, 非本项目 CMake)
- **Java runtime 内核**: `WinNative/app/src/main/runtime/` (`display/`, `audio/`, `container/`, `content/`, `system/`, `compat/`, `environment/`, `connector/`, `winhandler/`, `renderer/`, `ui/` ... ~32k 行)
- **JNI 绑定类 (12 个)**: `WinNative/app/src/main/java/com/winlator/cmod/`
- **巨型 Activity (不整块搬, 拆解)**: `WinNative/app/src/main/runtime/display/XServerDisplayActivity.java` (10,995 行, D9 拆解)

### P0 · `:core:native` 移植 (技术核心, 先做)
- [ ] 拷 `cpp/winlator/` 全量 C 源到 `core/native/src/main/cpp/winlator/`, **保留 `com.winlator.cmod` 包名** -> C 零改 (RFC §7/D5)
- [ ] 写真 `core/native/src/main/cpp/CMakeLists.txt`: 单文件出 `libwinlator.so`+`libfakeinput.so`; FetchContent zstd v1.5.6 / xz v5.4.6 静态链; adrenotools submodule `add_subdirectory`; 19 GLSL shader 经 NDK `glslc`+`bin2c.cmake`
- [ ] 拷 12 个 `com.winlator.cmod.*` Java 绑定类到 `:core:native` (或 `:core:native`+`:core:engine` 分放)
- [ ] 提供 `vulkan.c` 回调类 `com.winlator.cmod.runtime.content.AdrenotoolsManager` (构造器 `(Context)`+`getLibraryName(String)`, 精简到 ~30 行, D8)
- [ ] 移植 `android_sysvshm` (SysVSharedMemory JNI, 4 函数)
- [ ] 删当前 stub (`winlator_stub.c`/`fakeinput_stub.c`)
- [ ] 可选裁剪: 不走 native 下载则排除 `NativeContentIO`(`native_content_io.cpp`) 省 curl/OpenSSL
- [ ] 验证: `./gradlew :core:native:assembleDebug` 出带真符号的 `.so`

### P1 · `:core:engine` runtime 内核移植
- [ ] 拷 `runtime/` Java 内核到 `:core:engine`, **保 `com.winlator.cmod` 包名, Java 原样不转 Kotlin** (RFC §7 语言策略)
  - display/xserver (9,833) · renderer (1,930) · environment (2,840) · connector (774) · winhandler (1,985) · ui (1,965) · audio (922) · system (1,446) · compat (1,384) · container (1,634) · content (1,194) · shared (~1,700) · wine (3,655, 简化单版本)
- [ ] 抽 `WineSessionPreparer` (Java) 自 XSDA: `ensureWinePrefixReady`/`ensureWinePrefixEssentialFiles`/`extractDXWrapperFiles`/`ensureLaunchRuntimeFilesReady`/`applyGeneralPatches`/`cleanupLingeringSessionProcesses` (D9)
- [ ] `WineEngine` 真实现 = facade 委托上述 com.winlator.cmod 类; 替换 `StubWineEngine` 的 `TODO()`
- [ ] 砍: `input/controls` (5,435, 推 v0.4) · `display/recording` · `ExternalDisplayController` · `steampipe` · XSDA 的 Steam/录屏/快捷方式分支
- [ ] 验证: Hilt 图仍编译 (`./gradlew :app:assembleDebug`)

### P2 · 运行时二进制 + rootfs (与 P1 并行, 长 pole)
- [ ] 定位/clone `winlator-imagefs` (本地未检出, RFC 称已有资产; 产 Bionic 42 包 rootfs)
- [ ] `:core:rootfs` 实现 `RootfsInstaller` (imagefs 安装/提取/版本); 复现 termuxfs rpath `/data/data/com.termux/files/usr/lib`
- [ ] 自建 Proton 11 x86_64 (fork `WinNative-Emu/proton-wine`, 锁 `proton_11.0`, 见 [`RESEARCH-proton-wine-selfbuild.md`](RESEARCH-proton-wine-selfbuild.md) + D7); termuxfs+prefixPack 复用上游 SHA 锁定
- [ ] box64 / Turnip / DXVK: 版本锁 + SHA256 (D4/D8, MVP 固定单 Turnip 驱动)
- [ ] `:core:content` `BundledContentSource` 实现 (assets -> 首启解压到 imagefs)

### P3 · `:app` GameSessionScreen (D9)
- [ ] `AndroidView{SurfaceView}` + `TouchpadView` 覆盖 (复用 WinNative `XServerSurfaceView`/`TouchpadView` 渲染靶+触屏逻辑)
- [ ] `GameSessionViewModel` 生命周期编排: `ContainerManager` -> `WineSessionPreparer` -> `XEnvironment.startEnvironmentComponents` -> `GuestProgramLauncherComponent.execGuestProgram` (box64 wine explorer /desktop=WxH exe) -> `VulkanRenderer.attachSurface`
- [ ] onPause->pauseComponents / onDestroy->stopComponents+ProcessHelper kill

### P4 · 收尾
- [ ] `:core:container` `ContainerManager` 实现 (移植 WinNative ContainerManager 861 行)
- [ ] launcher: exe picker + 分辨率选择 -> 构 `LaunchSpec`
- [ ] **验收 (RFC §8)**: 启动一个 Windows .exe, 有画面(Vulkan)+触屏映射+音频

---

## 3. 必须遵守的约束 (别重踩)

**版本/构建**
- 别「逐组件取最新再修」--照抄一个 Google 参考栈 (`android/compose-samples` 的 `gradle/libs.versions.toml`)。查仓库用 `gh search repos`, 别凭记忆赌路径 (例: `now-in-android` 已改名 `nowinandroid`)。
- AGP 9 **built-in Kotlin**: 禁止 apply `org.jetbrains.kotlin.android`。Kotlin 2.3.21 靠 compose 插件带 KGP 覆盖 built-in 2.2.10。convention 不设 jvmTarget (默认取 `compileOptions.targetCompatibility`=17)。
- KSP 必须对齐 Kotlin (2.3.x -> KSP 2.3.9); compose-compiler 版本 = Kotlin 版本。
- `compileSdk=37` 平台包名是 `platforms;android-37.0` (带 `.0`), 不是 `platforms;android-37`。
- 升 AndroidX 前查 AAR `minCompileSdk` (最新 core 1.19/lifecycle 2.11 需 37, 已满足)。
- convention `.kt` 里 `getByType<T>()`/`findByType<T>()` 要 `import org.gradle.kotlin.dsl.getByType/findByType` (不自动导入)。

**架构 (RFC §4/§6/§7)**
- 依赖严格单向: `feature -> engine -> {native, rootfs, content, container}`。native 永不向上依赖。
- **保 `com.winlator.cmod` 包名** -> C/JNI 零改 (函数名硬编码 `Java_com_winlator_cmod_...`)。
- `:core:native` AGP namespace = `app.amphora.core.nativelib` (`native` 是 Java 关键字); 模块路径仍 `:core:native`。
- runtime 内核全 Java 原样复用 (含 audio); 只有 app 壳 + facade 用 Kotlin; 唯一重写的是 `XServerDisplayActivity` (D9)。

---

## 4. 参考资源

| 用途 | 位置 |
|---|---|
| 项目决议/架构 | `docs/01-RFC.md` (D1-D9 已定) |
| 研究基础 (WinNative 拆解) | `docs/00-RESEARCH.md` |
| Proton 自建 | `docs/RESEARCH-proton-wine-selfbuild.md` |
| as-built 栈 + 踩坑 | `docs/02-SCAFFOLD.md` |
| 移植源码 | `/Users/sky/co/github/WinNative` @ `48fe6b9` |
| 栈版本参考 (最新) | `android/compose-samples` (Reply/Jetcaster) `gradle/libs.versions.toml` |
| 栈/convention 参考 (正典, 略旧) | `android/nowinandroid` (注意无连字符) |
| rootfs 源 | `winlator-imagefs` (本地未检出, 需 clone) |

---

## 5. 验证命令

```bash
./gradlew help                  # 全工程配置
./gradlew :app:assembleDebug    # 全量 (Kotlin+KSP/Hilt+native .so+Compose+APK)
./gradlew :core:common:test     # 单测
# APK: app/build/outputs/apk/debug/app-debug.apk (含 lib/arm64-v8a/libwinlator.so)
```

---

## 6. 待决议 / 开放

- [ ] `winlator-imagefs` 仓库地址确认 + clone (RFC 称已有 42 包 CI 转绿, 本机未检出)
- [ ] adrenotools submodule 来源 (WinNative `.gitmodules` 内) 确认可独立拉取
- [ ] P0 完成后定: `NativeContentIO` 是否排除 (MVP 不走 native 下载则排除, 省 curl/OpenSSL Prefab AAR)
- [ ] Proton 11 自建 CI 跑通前, 是否临时用 `proton-9.0-x86_64` 回退 (D5 回退路径)
