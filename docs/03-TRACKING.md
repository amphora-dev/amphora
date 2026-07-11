# 03 - 进度跟踪 / Handoff

> 给下一个 agent 的接手文档。living checklist--完成就勾。
> 最后更新: 2026-07-11 · HEAD `92b00ef` · 阶段: **P1 内核移植 + facade skeleton 完成; XSDA body 抽取延后 P2/P3**
> 必读: [`00-RESEARCH.md`](00-RESEARCH.md) · [`01-RFC.md`](01-RFC.md) · [`02-SCAFFOLD.md`](02-SCAFFOLD.md)

---

## 0. 状态快照

- ✅ scaffold 已落地并提交 (`fc14357`)。
- ✅ P0 已落地并提交 (`9e0929f`): `:core:native` 真实 `libwinlator.so`+`libfakeinput.so` (62 JNI 导出 + JNI_OnLoad, adrenotools 静态链入, 19 shader 编入)。`./gradlew :app:assembleDebug` 绿, APK `lib/arm64-v8a/` 含真 `.so`。
- ✅ P1 已落地并提交 (`dee877e`+`92b00ef`): `:core:engine` runtime Java 内核 (221 .java + 3 .kt) + 11 JNI 绑定 + AdrenotoolsManager 精简 (D8) + cut 类 stub; `WineEngineImpl` facade skeleton (注入 ContainerManager/RootfsInstaller/WineSessionPreparer, launch 编排骨架委托 com.winlator.cmod, 每步 TODO 标 P-phase) + `WineSessionPreparer` 接口 (6 方法, compile-only)。`./gradlew :app:assembleDebug` 绿, APK 31.9MB 含 libwinlator.so 964K。
- ✅ 技术栈对齐 Google `android/compose-samples` 当前参考 (比 `android/nowinandroid` 新一档)。详见 [02-SCAFFOLD.md §1](02-SCAFFOLD.md)。
- ⏭ 下一步: P2 rootfs (clone `winlator-imagefs` + `:core:rootfs` `RootfsInstaller` 真实现); `WineSessionPreparer` body 抽取 (XSDA 6 方法) 随 P2/P3。

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
- [x] **P0 `:core:native` 移植** (`9e0929f`): 10 个 C/CXX 源整块拷 (零改, 保 `com.winlator.cmod`); 真实 CMakeLists (adrenotools submodule `add_subdirectory` + 19 glslc shader); 排除 `native_content_io.cpp` 省 curl/zstd/xz; 删 stub; `libwinlator.so` 62 JNI 导出 + JNI_OnLoad; APK 含真 `.so`

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

### P0 · `:core:native` 移植 ✅ 完成 (`9e0929f`)
- [x] 拷 `cpp/winlator/` C 源到 `core/native/src/main/cpp/winlator/`, **保留 `com.winlator.cmod` 包名** -> C 零改 (10 源: drawable/gpu_image/sync_fence/sysvshared_memory/xconnector_epoll/process_lifecycle/vulkan + vk/vk_dispatch/vk_image/vk_renderer)
- [x] 写真 `core/native/src/main/cpp/CMakeLists.txt`: 单文件出 `libwinlator.so`+`libfakeinput.so`; adrenotools submodule `add_subdirectory`; 19 GLSL shader 经 NDK `glslc`+`bin2c.cmake`。**无 zstd/xz/curl** (见下条裁剪)
- [x] 删 stub (`winlator_stub.c`/`fakeinput_stub.c`)
- [x] 裁剪: 排除 `native_content_io.cpp` -> 省 curl + zstd + xz 三依赖 (该文件是三者唯一消费者; RFC "zstd/xz 仍需" 指 Java 侧 zstd-jni, 非 native 构建)
- [x] adrenotools 作 git submodule 引入 (pin `8483dfd`, 递归 linkernsbypass `b10d485`), 静态链入 libwinlator.so; 产物含 4 个 hook `.so` (main_hook/file_redirect_hook/gsl_alloc_hook/hook_impl, LD_PRELOAD 用)
- [x] 验证: `:core:native:assembleDebug` + `:app:assembleDebug` 绿; `libwinlator.so` 含 **62** 个 `Java_com_winlator_cmod_*` 导出 + `JNI_OnLoad`, 覆盖全部 7 组 (VulkanRenderer 16/Texture 4/GPUImage 5/Drawable+Pixmap 8/XConnectorEpoll+ClientSocket/SyncFenceFd 6/GPUInformation 4/ProcessHelper 2/SysVSharedMemory 4); NativeContentIO 组按裁剪排除

**P0 修正 (原 checklist 误区, 供下个 agent 参考):**
- `android_sysvshm/` 是 **rootfs (imagefs Tier3.5) 组件**, 非 amphora native -- 它产 `libandroid-sysvshm.so` (socket-based shmem server, 供 rootfs 内 X11 lib 用)。4 个 SysV JNI 实际在 `cpp/winlator/sysvshared_memory.c` (直接开 `/dev/ashmem`), 随 winlator 拷贝即得, 无需单独移植 android_sysvshm。
- JNI 绑定类是 **13 (非 12)**, 散在 `runtime/`(VulkanRenderer/Drawable/Pixmap/GPUImage/Texture/XConnectorEpoll/ClientSocket/SyncFenceFd/GPUInformation/ProcessHelper) + `shared/`(NativeContentIO) + `sharedmemory/`(SysVSharedMemory) + `java/com/winlator/cmod/`(PatchElf 死代码)。**非** `java/com/winlator/cmod/` 内 12 个。按架构 (native 不向上依赖) **全部延至 P1 `:core:engine`** -- 它们 import runtime 内核类, 无法在 `:core:native` 编译; `.so` 编译不需它们 (JNI 符号按名解析, 运行时才 FindClass)。
- minor: AGP `stripDebugDebugSymbols` 对本 .so 报 "Unable to strip, packaging as they are" (debug 包不 strip, APK 略大); `llvm-strip --strip-debug` 手动 OK (941K->217K), 是 AGP 9 debug strip 怪癖, 非损坏。release strip 留 P4 核实。

### P1 · `:core:engine` runtime 内核移植 — **进行中**
- [x] 拷 `runtime/`+`shared/`+`sharedmemory/` Java 内核到 `:core:engine` (`src/main/java/com/winlator/cmod/...`), 保 `com.winlator.cmod` 包名, Java 原样不转 Kotlin (RFC §7)。WinNative 用 5 个 srcDirs + 非 canonical 路径 (javac 容忍); amphora 规整到 canonical 包路径。
- [x] D8 `AdrenotoolsManager` 精简 (~30 行: ctor+`getLibraryName`)。仅 `ImageFsInstaller.installDriversFromAssets` 调过 `extractDriverFromResources` -> no-op (驱动抽取移 P2 RootfsInstaller)。
- [x] 砍: `input/`(controls+rumble+ui+Activities) · `display/recording` · `display/steampipeserver` · `display/ExternalDisplayController` · `display/XServerDisplayActivity`(D9 重写) · `environment/components/{SteamClientComponent,PulseAudioComponent}` · `compat/fexcore`(arm64ec D5 否决, 但 Container/GuestProgramLauncher 引用 -> 整块拷回当死代码) · `system/SessionKeepAliveService` · `audio/midi`(自含, RFC §8 ALSA-only, 推 v0.2+)
- [x] 依赖 (版本取自 WinNative catalog 保源码兼容): `androidx.appcompat`1.7.1 · `androidx.preference`1.2.1 · `com.google.android.material`1.14.0 · `zstd-jni`1.5.7-9@aar · `commons-compress`1.28.0 · `tukaani-xz`1.12。加 `:core:engine/build.gradle.kts`。
- [x] `R`+`BuildConfig` stub (内核 import `com.winlator.cmod.{R,BuildConfig}`; 引擎 namespace=`app.amphora.core.engine` 不匹配)。R.java 78 字段/8 类型 (从实际 `R.type.name` 引用生成)。**P1 compile-only; 真实 res 接线 (namespace+res/) 留 P2/P3 runtime**。
- [x] 解耦剩余 cut 引用 ✅: 23 个 cut 类 stub (co-located 在 engine, 无向上依赖) + `input/rumble`/`compat/fexcore` 整块拷 + `AppTerminationHelper`/`XServerDisplayActivity`/`InputControlsView` 等 stub。WinHandler 124 错 (XSDA field/ctor + input.controls 6 类) 用 stub 解决 (内核 .java 原样不动)。
- [x] `WineSessionPreparer` 接口 (6 方法, 名字 verbatim 自 XSDA + 行号) + `StubWineSessionPreparer` (body TODO P2/P3) - :core:engine (`92b00ef`)
- [x] `WineEngineImpl` facade skeleton: 注入 ContainerManager/RootfsInstaller/WineSessionPreparer + DispatcherProvider, launch 编排骨架委托 com.winlator.cmod (XEnvironment/GuestProgramLauncherComponent/VulkanRenderer), 每步 TODO 标 P-phase; 替换 StubWineEngine 作 bound impl (Stub 保留 fallback)
- [x] Hilt: EngineModule 绑 WineEngineImpl + 临时 @Provides sibling 接口 stub (StubContainerManager/StubRootfsInstaller/StubWineSessionPreparer); `:core:engine` 是 P1 唯一 Hilt 模块, stub 暂居此 -> **P2/P4 移至 :core:{rootfs,container} 并删 EngineModule 对应 @Provides**
- [x] 验证: Hilt 图仍编译 ✅ (`./gradlew :app:assembleDebug` 绿, commit `92b00ef`; APK 31.9MB 含 libwinlator.so 964K)

**P1 关键发现 (供下个 agent, 修正 RFC/跟踪文档假设):**
1. **WinNative `runtime/` 是 Java+Kotlin 混合** (非纯 Java): 40 个 .kt 文件多为 Compose UI (dialog/theme/toast/nav/focus/widget/HUD/glasses)。amphora 砍 37 个 (app 层, 重写), 留 3 个干净 kernel 逻辑 .kt (`LogManager`/`PeIconExtractor`/`StoragePathUtils`, 被 .java 引用)。
2. **11 个 JNI 绑定类全落 `:core:engine`** (开放问题已定): leaf 下沉不可行 — `SysVSharedMemory` import `XConnectorEpoll` (runtime), `ProcessHelper` import `shared.util.Callback`; `GPUInformation` 虽干净但与 `EnvironmentManager` 同包, 拆包不值。`.so` 按名 loadLibrary + FindClass 走 app classloader, 共处 engine OK。
3. **AGP 9 built-in Kotlin**: `compileDebugKotlin` 与 `compileDebugJavaWithJavac` **分离并行** — 单跑 `compileDebugKotlin` 会 **隐藏 .java 错误** (它不编 .java)。须跑 `:core:engine:compileDebugJavaWithJavac` 或 `:app:assembleDebug` 才见 .java 错。JDK 中文 locale -> 错误是 `错误:`/`程序包...不存在`/`找不到符号` (非 `error:`)。
4. **解耦策略**: cut 类 stub (co-located, 保内核 .java 原样) 优于编辑内核 — `feature/app` 类 stub 后无向上依赖 (stub 在 engine 内), 架构干净; 真正 de-couple (删 SettingsConfig/PrefManager/PluviaApp/Marker/GOG 引用) 可作 P1-followup。
5. `audio/midi` 用 `cn.sherlock`+`jp.kshoji` MIDI 库, 自含 (0 外部引用), 整块砍省两个外部依赖。
6. **sibling 接口 stub 暂居 :core:engine**: P1 仅 `:core:engine` 有 Hilt; `ContainerManager`/`RootfsInstaller`/`WineSessionPreparer` 的 stub impl + `@Provides` 暂放 `EngineModule` (`StubEngineBindings.kt`, `internal`)。P2 (`:core:rootfs`)/P4 (`:core:container`) 给各自模块加 `amphora.android.hilt` + 真实现后, 删 `EngineModule` 三个临时 `@Provides`, 绑定移至 owning module。XSDA 6 方法 body 抽取 (~800-1000 行, 剥 Steam/录屏) 延后 P2/P3 -- 依赖 rootfs 就位才能端到端验, 复用 251KB 抽取调研。

### P2 · 运行时二进制 + rootfs (与 P1 并行, 长 pole)
- [ ] 定位/clone `winlator-imagefs` (本地未检出, RFC 称已有资产; 产 Bionic 42 包 rootfs)
- [ ] `:core:rootfs` 实现 `RootfsInstaller` (imagefs 安装/提取/版本); 复现 termuxfs rpath `/data/data/com.termux/files/usr/lib`
- [ ] 自建 Proton 11 x86_64 (fork `WinNative-Emu/proton-wine`, 锁 `proton_11.0`, 见 [`RESEARCH-proton-wine-selfbuild.md`](RESEARCH-proton-wine-selfbuild.md) + D7); termuxfs+prefixPack 复用上游 SHA 锁定
- [ ] box64 / Turnip / DXVK: 版本锁 + SHA256 (D4/D8, MVP 固定单 Turnip 驱动)
- [ ] `:core:content` `BundledContentSource` 实现 (assets -> 首启解压到 imagefs)
- [ ] **`WineSessionPreparer` body 抽取** (D9, XSDA 6 方法 ~800-1000 行, 剥 Steam/录屏; 复用 251KB 调研) - 依赖本节 rootfs 就位才能端到端验; 抽完替换 `StubWineSessionPreparer`, 同步删 `EngineModule.provideWineSessionPreparer` + `provideRootfsInstaller` 临时 stub (移 `:core:rootfs`)

### P3 · `:app` GameSessionScreen (D9)
- [ ] `AndroidView{SurfaceView}` + `TouchpadView` 覆盖 (复用 WinNative `XServerSurfaceView`/`TouchpadView` 渲染靶+触屏逻辑)
- [ ] `GameSessionViewModel` 生命周期编排: `ContainerManager` -> `WineSessionPreparer` -> `XEnvironment.startEnvironmentComponents` -> `GuestProgramLauncherComponent.execGuestProgram` (box64 wine explorer /desktop=WxH exe) -> `VulkanRenderer.attachSurface`
- [ ] onPause->pauseComponents / onDestroy->stopComponents+ProcessHelper kill
- [ ] `WineEngineImpl` P3 step 真实现 (替换 TODO): `startEnvironment`/`launchGuestProgram`/`sessionHandleFor`; `inputFeed`/`audioSink` 换 XServer/ALSAServer-backed sink; 同步删 `EngineModule.provideContainerManager` 临时 stub (移 `:core:container`, P4)

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
- [x] ~~adrenotools submodule 来源~~ ✅ 已引为 amphora git submodule (`core/native/src/main/cpp/adrenotools` @ `8483dfd`, 递归 linkernsbypass `b10d485`); `git submodule update --init --recursive` 即可
- [x] ~~`NativeContentIO` 是否排除~~ ✅ 已排除 (P0), 省 curl/zstd/xz; Java 侧 `NativeContentIO.java`+`TarCompressorUtils` 的 native 调用在 P1 移植时 stub 或后续按需补
- [ ] Proton 11 自建 CI 跑通前, 是否临时用 `proton-9.0-x86_64` 回退 (D5 回退路径)
- [ ] minor: AGP 9 debug strip 对本 .so no-op (见 P0 修正); release strip 在 P4 核实
- [x] ~~13 个 JNI 绑定类全放 :core:engine 还是 leaf 下沉~~ ✅ 已定: 11 个全落 `:core:engine` (leaf 下沉不可行, 见关键发现 #2)
- [ ] P2/P4: 删 `EngineModule` 三个临时 sibling-stub `@Provides` (ContainerManager/RootfsInstaller/WineSessionPreparer), 绑定移至 :core:{container,rootfs,engine} 各自模块 (需先给 :core:rootfs/:core:container 加 `amphora.android.hilt`)
