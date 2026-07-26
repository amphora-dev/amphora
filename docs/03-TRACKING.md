# 03 - 进度跟踪 / Handoff

> 给下一个 agent 的接手文档。living checklist--完成就勾。
> 最后更新: 2026-07-26 · **v0.1 端到端已跑通** (RFC §8: Wine desktop 画面 + 相对触控 + host/guest Vulkan 对齐)。P0–P4 全接线后的关键修复: `ecc7ee3` (desktop surface + relative touch + `GameSessionLaunchTest`) · `65e180f` (真实 DXVK WCP `Dxvk-3.0.2-gplasync` + adrenotools-wrapped Turnip) · `04ec6f5` (host Vulkan 跟容器 graphicsDriverConfig + wine debug logs)。后续: VKD3D 默认接入 · Exit ANR 止血 (见 §专项)。架构真源见 [`05-ARCHITECTURE.md`](05-ARCHITECTURE.md)。
> 必读: [`05-ARCHITECTURE.md`](05-ARCHITECTURE.md) · [`01-RFC.md`](01-RFC.md) · [`04-ASSET-MANIFEST.md`](04-ASSET-MANIFEST.md) · [`02-SCAFFOLD.md`](02-SCAFFOLD.md)

---

## 专项 · Exit ANR / 会话 teardown（待根治）

> 状态: **已止血，未根治** · 记档 2026-07-26 · 提交 `7dc3dc9`

### 现象
点 GameSession **Exit** 后主线程卡死 → 系统「应用无响应」(ANR)。

### 根因（已核实）
1. `GameSessionViewModel.stop/pause/resume` 原先在 `viewModelScope`（默认 **Main**）上调用 `SessionHandle.stop()`。
2. teardown 路径会 `XConnectorEpoll.stop()` → `Thread.join()` **无限等待**。
3. client poll 线程堵在 native `ClientSocket.recvAncillaryMsg`（ancillary FD 收包），`requestShutdown` 未必能立刻唤醒，join 永久挂起 → Main ANR。

### 已做止血（`7dc3dc9`）
- ViewModel：`stop` / `pause` / `resume` 改到 `Dispatchers.IO`（`cleanupScope` 亦用 IO）。
- `XConnectorEpoll`：`joinOrGiveUp(..., JOIN_TIMEOUT_MS=2000)`，epoll / client poll 最多等 2s 后 `interrupt` 放弃。

### 已知残留 / 专项目标
超时放弃后可能留下**会话级**残留：epoll/client 线程、socket FD、ancillary FD、尚未释放的 direct ByteBuffer。优于 ANR，但多次进出会话可能泄漏或二次启动异常。

2026-07-26 续：返回/Exit 后偶发 **整进程闪退** —
`XClientRequestHandler.handleNormalRequest` 对已 `releaseIOStreams()` 的 null
`XInputStream` 调 `available()` → NPE（`XConnectorEpoll` 在 `inputStream==null`
时仍误调 `handleRequest`）。已加 null 守卫 + teardown 竞态吞 RuntimeException。

专项验收建议：
- [ ] Exit 后 5s 内 UI 回到 launcher，**永不** ANR（已基本满足）
- [ ] logcat 无持续增长的 FD / thread（多次 Exit→再开）
- [ ] 根治：shutdown 路径强制关闭 client FD / 唤醒阻塞中的 `recv*`，使 join 在超时前正常结束；评估是否仍需 timeout 兜底
- [ ] 回归：`GameSessionLaunchTest` + 真机 Exit 连点 / 快速重开

相关代码：`GameSessionViewModel.kt` · `XConnectorEpoll.java` · `ClientSocket.recvAncillaryMsg`

---

## 0. 状态快照

- ✅ scaffold 已落地并提交 (`fc14357`)。
- ✅ P0 已落地并提交 (`9e0929f`): `:core:native` 真实 `libwinlator.so`+`libfakeinput.so` (62 JNI 导出 + JNI_OnLoad, adrenotools 静态链入, 19 shader 编入)。`./gradlew :app:assembleDebug` 绿, APK `lib/arm64-v8a/` 含真 `.so`。
- ✅ P1 已落地并提交 (`dee877e`+`92b00ef`): `:core:engine` runtime Java 内核 (221 .java + 3 .kt) + 11 JNI 绑定 + AdrenotoolsManager 精简 (D8) + cut 类 stub; `WineEngineImpl` facade skeleton (注入 ContainerManager/RootfsInstaller/WineSessionPreparer, launch 编排骨架委托 com.winlator.cmod, 每步 TODO 标 P-phase) + `WineSessionPreparer` 接口 (6 方法, compile-only)。`./gradlew :app:assembleDebug` 绿, APK 31.9MB 含 libwinlator.so 964K。
- ✅ 技术栈对齐 Google `android/compose-samples` 当前参考 (比 `android/nowinandroid` 新一档)。详见 [02-SCAFFOLD.md §1](02-SCAFFOLD.md)。
- ✅ P2(部分) 已落地并提交 (`c593021`): native 资产提取能力恢复 (修正 P0 over-exclusion -- `native_content_io.cpp` 回归, zstd v1.5.6 + liblzma v5.4.6 静态链入, curl/download 2 JNI stub per D4); libwinlator.so 66 JNI 导出 (62+4 NativeContentIO), `TarCompressorUtils` kernel-wide 可用; `ImageFsRootfsInstaller` 真实现 (适配 `ImageFsInstaller`, 剥 Steam/Container/Activity, 仅 imagefs 提取+版本), EngineModule 绑定, `StubRootfsInstaller` 删除。`./gradlew :app:assembleDebug` 绿, APK 34.5MB 含 libwinlator.so 2.5MB (zstd+xz 静态链入)。compile-only。
- ✅ P2(续) `WineSessionPreparer` body 抽取已落地 (`829e83b`): D9 XSDA 6 方法 body 逐字移植到 `XServerWineSessionPreparer` (849 行, XSDA L6127/7127/6280/7164/7970/7537 + helpers L6290/7950/5777/6398/6410/8098/8124/10793), 剥 Steam/录屏/快捷方式/Activity/arm64ec/UI-refresh (D5/D8/D9); 4 个 feature-layer/.kt 小类移植 (WinComponentSetup 145 行 + DXVKConfigUtils/WineD3DConfigUtils/GraphicsDriverConfigUtils 共 193 行 -> `com.winlator.cmod.runtime.{wine,container}`); 接口加 `envVars(): Map<String,String>` 输出 accessor (XSDA `envVars` 字段, 供 P3 launch 合并); `StubWineSessionPreparer` 删除, EngineModule 绑真实现。`./gradlew :app:assembleDebug` 绿, APK 34.5MB。compile-only (端到端验待资产)。
- ✅ 资产获取轨: `winlator-imagefs` clone (cnb.cool/atowerlight, 构建配方) + imagefs.tzst 真资产 (WinNative Git LFS, 190MB, SHA `0902e324...`, Bionic) + box64/Turnip/DXVK 全资产 SHA 锁 (`docs/04-ASSET-MANIFEST.md`); 真机 (Lenovo TB322FC / Adreno 830 / arm64-v8a / API 36) rootfs 提取 instrumented 验证通过 (877MB Bionic rootfs, 27,614 条目, 1.5s).
- ✅ P3 已落地并提交 (`2a2078a`+`0404922`, compile-only): `WineEngineImpl.launch` 真接线 (XSDA `setupXEnvironment` L6439 移植 -- RootfsInstaller->ContainerManager->Preparer->resolveWinNativeContainer->`XServer`->`EnvVars`->`XEnvironment`+components->`startEnvironmentComponents`, 剥 Steam/shortcut/recording/arm64ec/WinHandler); `XServerSessionHandle` (pause/resume/stop via XEnvironment+ProcessHelper, teardownOnce); 真 sinks (`XServerInputSink`/`XServerAudioSink`); `GameSessionSurfaceProvider` 暴露 `XServer` (WineEngine 接口保 kernel-free); `GameSessionScreen` (AndroidView{`XServerSurfaceView`}+`TouchInputOverlay`+生命周期, D9 XSDA 重写); `GameSessionViewModel` (launch/stop/pause/resume, Throwable 边界)。`./gradlew :app:assembleDebug` 绿, `:core:content`/`:core:common` test 绿。launch 在 P4 container stub 抛, 端到端验待 P4。
- ✅ P4 已落地并提交 (`fdaa4e8`, `:app:assembleDebug` 绿): `:core:container` `ContainerManager` 真实现 = `WinlatorContainerManager` (`:core:engine`, DIP 桥); launcher SAF `.exe` picker + 分辨率; exe 进 `drive_c` 跑 `C:\<name>`; syncContents gap 修复; **零 stub 剩余**.
- ✅ **RFC §8 真机验收通过** (2026-07-21): 启动 Windows `.exe` → Vulkan Wine desktop 有画面 + 相对触控。P4 后关键修复见上 (DXVK WCP / host-guest Vulkan / surface+touch)。
- ✅ 残留清理 (2026-07-21): 默认 `LauncherRoute`; 内置 PE 测试路径保留 (launcher **Debug: Wine smoke test** + `DEBUG_AUTO_LAUNCH_WINE`); 删 Graphics-Test staging / `StubAudioSink` / 空 `SettingsViewModel`; surface 渲染异常改记日志。
- ✅ 残留清理续: 删 `GameRecorder`/`PulseAudioComponent`/`WinToast`/`AppTerminationHelper`/`PerformanceHudState` + 接线拆除; `WineInfo` 不再读假 `R.array.wine_entries` (走 ContentsManager install dir)。
- ✅ WinHandler/手柄 stub 闭包清理: 删 `WinHandler`/`XServerDisplayActivity`/controls stubs/`rumble/*`; 按钮一律走 X 协议; `FakeInputWriter` 仅留 GPLC env 空环辅助; inputType 常量内联到 `Container`。
- ✅ fakeinput 裁剪 + BuildConfig/R 收敛: GPLC 不再 copy/LD_PRELOAD `libfakeinput` / FAKE_EVDEV/udev; CMake 停编 fakeinput; 删 `FakeInputWriter` + 手写 `BuildConfig`; Vulkan validation 改读 `FLAG_DEBUGGABLE`; preset/拷贝文案硬编码，避开假 `R` ID。
- ✅ 假 `R` 死 UI 闭包: 删手写 `R.java` + `DownloadProgressDialog`/`MultiSelectionComboBox`/`HttpUtils`/`AppUtils`; `ImageFsInstaller` 仅留 `LATEST_VERSION`; wallpaper 改纯色回退; Box64/FEX 去掉 Spinner/import-export 死路径。
- ✅ MVP 再削: `WineThemeManager` 仅留默认串; 删 `MSBitmap`/`LogManager`/`CPUStatus`/`UnitUtils`/`fakeinput.cpp`; Box64/FEX 仅留 `getEnvVars`; 去掉 pulseaudio.tzst 旁路提取 + manifest `audio_plugin`（MVP ALSA-only，aserver 在 imagefs）。`:feature:settings` **保留**（v0.2 实质项）。
- ✅ 内核再削 (2026-07-21): 删 Shortcut / PE 图标 / WineThemeManager / EffectComposer；GPLC 去 shortcut 与浏览器/剪贴板 prefs；ContainerManager 去 duplicate/shortcuts；ContentsManager 去 remote profiles；`NativeContentIO` 去 download JNI 包装；`AssetPaths` 仅留 GPU_CARDS+WINE_STARTMENU；un-include `:core:ui`（目录保留）。
- ⚠️ **AIO DX8/DX9 黑屏+FPS** (2026-07-26): DX10–12 正常；DX9 有 FPS 无画面（FF PSO `-13`）。
  - **DX8 CreateDevice 失败**（弹窗 “Could not create a Direct3D 8 device”）已定位并修：AIO 在 windowed 下传 `FullScreen_PresentationInterval=IMMEDIATE`，DXVK D3D8 只允许 `DEFAULT` → `D3DERR_INVALIDCALL`。补丁源与固定 Release：公开仓 [`atowerlight/aio-graphics-test`](https://cnb.cool/atowerlight/aio-graphics-test) 标签 `amphora`（CI 每次覆盖）；manifest `remoteUrl` 指向该下载地址。
  - DX8 走 DXVK **D3D8→D3D9 compatibility mode**，与 DX9 同栈；CreateDevice 通过后仍可能撞上 FF PSO `-13` 黑屏（开放）。
  - 曾试 `Dxvk-2.4.1-pre-reg` → DX9–11 **闪退**，已回退 3.0.2；勿再盲换 2.4.1。
  - **可选 Turnip** 已可加载，但 **未能消掉** FF PSO `-13`。
  - **定位入口**: 启动器 **Debug: Wine + DXVK diag** → AIO DX8/DX9；日志 `{filesDir}/wine_stderr.log`。Diag 的 `WINEDEBUG` 仅 `+err`。
- ⚠️ **AIO OpenGL/DX7 黑屏+FPS** (2026-07-26): 已修 launch 合并 `ZINK_*`/`TU_DEBUG` + 始终下发 `WINE_D3D_CONFIG`。续试注入 `ADRENOTOOLS_*=freedreno` / `renderer=vulkan` / `dd7to9` **回退**——在 TB322FC 上导致 DX9–11 也挂（log：`ADRENOTOOLS_DRIVER_NAME=libvulkan_freedreno.so`）。OpenGL/DX7 黑屏仍为开放项；完整 Turnip 请用启动器可选 **Turnip 1.06-b**（勿再盲注 NAME）。`ddrawrapper/*` runtimeAssets 保留（nglide 提取不再 404）。
- ⏭ 下一步: 用可选 Turnip 验证 DX9 PSO；**Exit ANR 根治**；v0.2 候选 settings / 键盘手柄。详见 [`05-ARCHITECTURE.md`](05-ARCHITECTURE.md) §9。

| 项 | 值 |
|---|---|
| AGP / Gradle / Kotlin / KSP | 9.2.1 / 9.4.1 / 2.3.21 / 2.3.9 |
| Hilt / Compose BOM | 2.59.2 / 2026.06.01 |
| compileSdk / targetSdk / minSdk / NDK | 37 / **28** / 26 / r28 (28.0.13004108) — targetSdk 28 因 `filesDir` 执行 box64/Wine (SELinux) |
| 包名 / 模块数 | `app.amphora` / 9 模块 + build-logic（`core/ui` 目录保留但未 include） |
| 架构文档 | [`05-ARCHITECTURE.md`](05-ARCHITECTURE.md) |

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
- [x] `R`+`BuildConfig` stub 曾用于 P1 compile-only；后续已删手写 `BuildConfig`/`R.java`，相关路径改硬编码 / `:app` 真资源名解析 / 删死 UI。
- [x] 解耦剩余 cut 引用 ✅: 23 个 cut 类 stub (co-located 在 engine, 无向上依赖) + `input/rumble`/`compat/fexcore` 整块拷 + `AppTerminationHelper`/`XServerDisplayActivity`/`InputControlsView` 等 stub。WinHandler 124 错 (XSDA field/ctor + input.controls 6 类) 用 stub 解决 (内核 .java 原样不动)。
- [x] `WineSessionPreparer` 接口 (6 方法, 名字 verbatim 自 XSDA + 行号) + `StubWineSessionPreparer` (body TODO P2/P3) - :core:engine (`92b00ef`)
- [x] `WineEngineImpl` facade skeleton: 注入 ContainerManager/RootfsInstaller/WineSessionPreparer + DispatcherProvider, launch 编排骨架委托 com.winlator.cmod (XEnvironment/GuestProgramLauncherComponent/VulkanRenderer), 每步 TODO 标 P-phase; 替换 StubWineEngine 作 bound impl (Stub 保留 fallback)
- [x] Hilt: EngineModule 绑 WineEngineImpl + 临时 @Provides sibling 接口 stub (StubContainerManager/StubRootfsInstaller/StubWineSessionPreparer); `:core:engine` 是 P1 唯一 Hilt 模块, stub 暂居此 -> **P2/P4 移至 :core:{rootfs,container} 并删 EngineModule 对应 @Provides**
- [x] 验证: Hilt 图仍编译 ✅ (`./gradlew :app:assembleDebug` 绿, commit `92b00ef`; APK 31.9MB 含 libwinlator.so 964K)

**P1 关键发现 (供下个 agent, 修正 RFC/跟踪文档假设):**
1. **WinNative `runtime/` 是 Java+Kotlin 混合** (非纯 Java): 40 个 .kt 文件多为 Compose UI (dialog/theme/toast/nav/focus/widget/HUD/glasses)。amphora 砍 37 个 (app 层, 重写), 留 3 个干净 kernel 逻辑 .kt (`LogManager`/`PeIconExtractor`/`StoragePathUtils`, 被 .java 引用)。
2. **11 个 JNI 绑定类全落 `:core:engine`** (开放问题已定): leaf 下沉不可行 — `SysVSharedMemory` import `XConnectorEpoll` (runtime), `ProcessHelper` import `shared.util.Callback`; `GPUInformation` 虽干净但与 `EnvironmentManager` 同包, 拆包不值。`.so` 按名 loadLibrary + FindClass 走 app classloader, 共处 engine OK。
3. **AGP 9 built-in Kotlin**: `compileDebugKotlin` 与 `compileDebugJavaWithJavac` **分离并行** — 单跑 `compileDebugKotlin` 会 **隐藏 .java 错误** (它不编 .java)。须跑 `:core:engine:compileDebugJavaWithJavac` 或 `:app:assembleDebug` 才见 .java 错。JDK 中文 locale -> 错误是 `错误:`/`程序包...不存在`/`找不到符号` (非 `error:`)。
4. **解耦策略**: cut 类 stub (co-located, 保内核 .java 原样) 优于编辑内核 — `feature/app` 类 stub 后无向上依赖 (stub 在 engine 内), 架构干净; 真正 de-couple (删 SettingsConfig/PrefManager/PluviaApp/Marker/GOG 引用) 可作 P1-followup。✅ **P1-followup 已完成 (2026-07-18)**: 删 8 个 RFC §7 stub (GOGGame/GOGService/GOGConstants/Marker/MarkerUtils/PrefManager/EpicGameFixHelper/GogDependencyFixHelper) + 改 3 个调用点 (GameFixes 砍 GOG/Epic 路径 / ImageFsInstaller 删 clearSteamDllMarkers / WineUtils 删 PrefManager 死分支); 进一步发现 `GameFixes` 自 P1 移植以来 0 外部引用 (XSDA 调用点被 D9 砍) — 连 `GameFixes` + `gamefixes/` 整目录一并删; `SteamBridge` (5 方法全反射查不存在的 `feature.stores.steam.service.SteamService`/`SteamClientManager`, catch 后返默认值, 运行期 100% 死) 紧跟着也删 — `runtime/compat/` 根下现仅剩 `box64/`+`fexcore/` 子包。✅ **孤儿清理 (2026-07-18 续)**: 全仓扫 0 外部引用类, 删 31 个文件 — 16 个 XSDA UI/manager 移植残留 (FEXCoreManager/WineRequestHandler/VKD3DVersionItem/SessionLogWriter/LogFileUtils/FrameRating/SGSRResolutionUtils/AudioFocusHandler/BadLength/BadCursor/EnvVarsView/ColorPickerView/CubicBezierInterpolator/WaveView/ImageUtils/ActivityResultHost) + 14 个 Effect 子类 (CRTEffect/VividEffect/... 等, `EffectComposer.addEffect` 从未被调, 整条 post-process 流水线死; `Effect.java` base + `EffectComposer.java` 被 `VulkanRenderer` 引用故保留) + `StubWineEngine.kt` (`@Inject` 但未在任何 `@Provides`/`@Binds` 绑, docstring 写"deliberately-retained fallback"但实际无 Hilt 绑定点); 进一步删 `SettingsConfig.java` (inline `DEFAULT_WINLATOR_PATH` 进 Box64/FEXCorePresetManager + 删 `ImageFsInstaller.resetEmulatorsVersion` 调用) + `PluviaApp.java` (僵尸, `ProcessHelper` 调 `getInstance().getFilesDir()` 永远 NPE 被 try/Throwable 吞, 连带删 `resolveWineStderrLog`/`resolveWineTailCapture`/`startDebugTailThread`/`emitDebugLine`/`tailSeq` 死代码 + 2 import); `app/` 整目录消失。`feature/stores/` 整目录早已消失。`:app:assembleDebug` 绿。**Defer**: `XServerDisplayActivity.java` + `WinHandler.java` (ctor 死但 `WinHandler` 类被 `XServer` 字段 + `Container.DEFAULT_INPUT_TYPE` + `XServerWineSessionPreparer.FLAG_INPUT_TYPE_DINPUT` 引用为类型, 删需动 kernel 多处, 留 v0.2+ 手柄支持时一并处理)。
5. `audio/midi` 用 `cn.sherlock`+`jp.kshoji` MIDI 库, 自含 (0 外部引用), 整块砍省两个外部依赖。
6. **sibling 接口 stub 暂居 :core:engine**: P1 仅 `:core:engine` 有 Hilt; `ContainerManager`/`RootfsInstaller`/`WineSessionPreparer` 的 stub impl + `@Provides` 暂放 `EngineModule` (`StubEngineBindings.kt`, `internal`)。P2 (`:core:rootfs`)/P4 (`:core:container`) 给各自模块加 `amphora.android.hilt` + 真实现后, 删 `EngineModule` 三个临时 `@Provides`, 绑定移至 owning module。XSDA 6 方法 body 抽取 (~800-1000 行, 剥 Steam/录屏) 延后 P2/P3 -- 依赖 rootfs 就位才能端到端验, 复用 251KB 抽取调研。

### P2 · 运行时二进制 + rootfs (与 P1 并行, 长 pole)
- [x] native 资产提取能力恢复 (`c593021`): `native_content_io.cpp` 回归 + zstd v1.5.6/liblzma v5.4.6 静态链入 + curl/download 2 JNI stub; libwinlator.so 66 导出, `TarCompressorUtils` kernel-wide 可用 (修正 P0 over-exclusion -- RFC §7 原意只省 curl, zstd/xz 提取仍需)
- [x] 定位/clone `winlator-imagefs` (cnb.cool/atowerlight, 构建配方非预产物; imagefs.tzst 真资产来自 WinNative Git LFS)
- [x] `RootfsInstaller` 真实现 (`c593021`, `ImageFsRootfsInstaller` in `:core:engine`): imagefs 提取 (shard/单档) + 版本 (`.img_version`); 剥 Steam/Container/Activity. **契约留 `:core:rootfs`, concretion 落 `:core:engine` (DIP -- `:core:rootfs` 不可见 `TarCompressorUtils`/`ImageFs`)**
- [x] termuxfs rpath 核实 (D7): imagefs 内**无** `/data/data/com.termux/...` 路径 (grep 0 命中); rpath 烙在 Wine ELF, 运行时由 launch `LD_LIBRARY_PATH` 解析 (P3 事项, 非提取阻塞)
- [ ] 自建 Proton 11 x86_64 (fork `WinNative-Emu/proton-wine`, 锁 `proton_11.0`, 见 [`RESEARCH-proton-wine-selfbuild.md`](RESEARCH-proton-wine-selfbuild.md) + D7); termuxfs+prefixPack 复用上游 SHA 锁定
- [x] box64 / Turnip / DXVK: SHA256 锁定 (D4/D8, MVP 单 Turnip=`graphics_driver/wrapper.tzst`; 见 [`04-ASSET-MANIFEST.md`](04-ASSET-MANIFEST.md) §2)
- [x] `:core:content` `BundledContentSource` 实现 (2026-07-13): `ContentManifest`+`content_manifest.json` (5 组件, 04-ASSET-MANIFEST 派生) + `BundledContentSource` (编排: 查 manifest -> 拷资产 + SHA-256 流式校验 -> 委托 `BundledAssetInstaller` -> `Resolved`) + `WinlatorBundledAssetInstaller` (`:core:engine`, ARCHIVE=`TarCompressorUtils.extract(File)`, WCP=`ContentsManager.extraContentFile`+`finishInstallContent`); `EngineModule` 绑 `ContentSource`. DIP (契约 `:core:content`, concretion `:core:engine`). `:core:content:test` 6 JVM 单测过, `:app:assembleDebug` 绿 (APK 含 manifest+libwinlator.so). 详见 §P2 #8.
- [x] **`WineSessionPreparer` body 抽取** (D9, `829e83b`): `XServerWineSessionPreparer` 849 行, XSDA 6 方法 + helpers 逐字移植, 剥 Steam/录屏/快捷方式/Activity/arm64ec/UI (D5/D8/D9); 4 个小类移植 (WinComponentSetup + DXVKConfigUtils/WineD3DConfigUtils/GraphicsDriverConfigUtils); 接口加 `envVars()` 输出 accessor; `StubWineSessionPreparer` 删除, EngineModule 绑真实现。✅ **真机验证通过** (见 §P2 #7): `extractGraphicsDriverFiles` 填 14 envVars (`WRAPPER_VK_VERSION=1.3.284` 等) + `wrapper.tzst` 提进 imagefs root。

**P2 关键发现 (供下个 agent, 修正 P0/RFC 假设):**
1. **P0 over-exclusion 修正**: P0 排除 `native_content_io.cpp` 时连 zstd+xz native 依赖一起砍, 但 RFC §7 原意是只省 curl (下载), zstd/xz 提取仍需 ("zstd/xz 仍需" 指 native 提取, 非仅 Java zstd-jni). 后果: `TarCompressorUtils.extract` -> `NativeContentIO.extractAsset` -> native (符号缺失) 对整个 kernel 是死路径 (ContentsManager/ContainerManager/ImageFsInstaller 调即 UnsatisfiedLinkError); P1 compile-only 没暴露. P2 (`c593021`) 恢复: cpp 回归 + FetchContent zstd v1.5.6/liblzma v5.4.6 静态链 + curl/download 2 JNI stub. 66 导出 (62+4). 提取 (L781-835) 与下载 (L836-927, curl) 在 cpp 内干净分离, 剥 curl 无伤提取.
2. **DIP: 契约在低模块, concretion 在 engine**: `RootfsInstaller` 接口留 `:core:rootfs`, 但真 impl `ImageFsRootfsInstaller` 落 `:core:engine` (紧邻它适配的 `ImageFs`/`TarCompressorUtils`). 因依赖方向 `engine -> rootfs`, `:core:rootfs` 不可见 kernel. 这是 DIP (低模块拥抽象, 高模块拥 concretion), 非 "stub 暂居" 妥协. **`:core:rootfs` 无需 Hilt**. `ContainerManager` (P4) / `WineSessionPreparer` (P2 ✅ `XServerWineSessionPreparer`) 同理: 若 impl 依赖 kernel, concretion 落 engine, EngineModule 删 stub 换真 impl. 跟踪文档原 "移 owning module" 假设据此修正.
3. **imagefs 资产仍是占位**: WinNative `assets/imagefs.tzst` 仅 134 字节 (占位), 真实 ~869MB 提取产物来自 `winlator-imagefs` (本地未检出). `gh search repos` 找到 `Other-backup/winlator-imagefs-v2` + `kissGPT/imagefs-winlator` 但非 `WinNative-Emu/` 下; clone + SHA 锁仍是 P2 资产项. `ImageFsRootfsInstaller` 提取路径正确 (对 shard/单档), 端到端验待资产.
4. **xz 测试二进制 bloat**: xz FetchContent 默认建 test_*/xzdec 二进制 (非链入 .so, 仅占 build 空间+时间). 已加 `XZ_BUILD_TESTS OFF` 抑制. `ensure_parent_dir` (cpp L75) 在 download stub 后无调用者, 编译报 unused-function 警告 (无害, 保留待 v0.3 download 恢复).
5. **XSDA body 抽取: envVars 输出 accessor + 4 类小补丁 + AdrenotoolsManager stub**: (a) XSDA 的 `envVars` 是 Activity 字段, 被 `extractGraphicsDriverFiles`/DXVK/wined3d `setEnvVars` 累积, launch 时消费. 接口原 6 方法全 `Unit` 返回, 没有输出通道 -> 给 `WineSessionPreparer` 加 `envVars(): Map<String,String>` (additive, 不改现有签名), impl 持 `EnvVars envState` 累积, `WineEngineImpl` P3 合并进 launch env. (b) 6 方法依赖 4 个 WinNative feature-layer/.kt 小类 (`WinComponentSetup` 145 行 .kt + `DXVKConfigUtils`/`WineD3DConfigUtils`/`GraphicsDriverConfigUtils` 共 193 行), 全部移植到 `com.winlator.cmod.runtime.{wine,container}` (包名从 `feature.settings` 提升到 runtime, 逻辑零改). (c) `extractGraphicsDriverFiles` 的 adrenotools 驱动加载块 stub: D8 已把 `AdrenotoolsManager` 精简到只剩 `getLibraryName` (删 `setDriverById`/`getDriverName`/`getDriverVersion`), 单固定 Turnip 驱动 env-var 设置 (ADRENOTOOLS_DRIVER_PATH/NAME/HOOKS_PATH) 留 TODO, 资产到位 (P2) + runtime (P3) 恢复. (d) `getDxvkFrameRateOverride` stub=0 (快捷方式/偏好驱动, amphora 无), `getActiveGameDirectoryPath`=null / `isSteamShortcut`=false (快捷方式-only, D9 砍). (e) `desktopTheme` apply (`WineThemeManager.apply` 需 `xServer.screenInfo`) 延后 P3 setupXEnvironment (prep 阶段无 xServer). (f) `AmphoraContainer`->WinNative `Container` 解析: `ContainerManager.loadContainers()` + 按 `rootPath` 匹配 `getRootDir()` (P4 ContainerManager 接管桥接). (g) `WinHandler.FLAG_INPUT_TYPE_DINPUT` 是 `byte`, Kotlin `and` 需 `.toInt()` (Java 自动提升). 全 849 行 compile-only, `:app:assembleDebug` 绿.
6. **资产获取 + 真机 rootfs 提取验证**: (a) `winlator-imagefs` (cnb.cool/atowerlight) 是**构建配方仓** (1.2MB 脚本+CI, 产 `imagefs.txz` 18MB 重建版 SHA `af66e28b`), 非预产物; imagefs.tzst 真资产 (190MB, zstd, SHA `0902e324...`, **Bionic libc**) 实为 WinNative Git LFS blob (`git lfs install` + `git lfs pull --include="app/src/main/assets/imagefs.tzst"`), 与 amphora `ImageFsRootfsInstaller` (ZSTD/`.tzst`) 直配, 无需转码. 解压 877MB / 10,892 条目 / merged-usr / `libc.so -> /system/lib64/libc.so` (Bionic 确认, 无 glibc 标记). (b) box64/Turnip/DXVK 资产已存 WinNative assets (非 LFS), 全 SHA 锁定 ([`04-ASSET-MANIFEST.md`](04-ASSET-MANIFEST.md)); Turnip=`graphics_driver/wrapper.tzst`, DXVK=`dxwrapper/d8vk-1.0.tzst`, box64 二进制不在 imagefs (走 installable). (c) **真机验证**: Lenovo TB322FC (arm64-v8a / API 36 / Adreno 830) instrumented `ImagefsExtractionTest` 通过 -- `libwinlator.so` 加载 OK, `TarCompressorUtils.extract(ZSTD)` 提取 877MB rootfs (27,614 条目, 1.5s), Bionic 结构 + merged-usr + libc.so 符号链接断言全过. (d) **preparer 真机验 ✅ 已通过** (见 #7): 原阻塞 (需 Proton 资产 + Container 创建) 已用运行时 .wcp 本地安装绕过; `extractGraphicsDriverFiles` 的 native 提取原语 (`TarCompressorUtils.extract`) 与 rootfs 同路径已证. (e) D7 termuxfs rpath: imagefs 内无 `/data/data/com.termux/...`, rpath 烙 Wine ELF, 运行时 `LD_LIBRARY_PATH` 解析 (P3). (f) imagefs.tzst 测试资产 (190MB) git-ignored (`*.tzst`), 测试用 `assumeTrue` 资产缺时 skip.
7. **preparer 真机验证通过** (`PreparerGraphicsDriverTest`, 2026-07-13): (a) **D4 下载 stub 绕过路径确立**: `native_content_io.cpp:783 nativeDownloadFile` 返回 `JNI_FALSE` (MVP 无远程抓取), 但 `ContentsManager.extraContentFile(uri)` 走 `nativeExtractArchive` (L728, 非下载路径) -- 宿主 curl `.wcp` + `adb push` 到外存 + `extraContentFile` 本地装, 完全绕过 stub. (b) **.wcp 下载源** (`nicholasx417/WinNative-Components` releases): `Proton-10.0-4-x86_64.wcp` (161MB, zstd, 含 `bin/`+`lib/wine/`+`prefixPack.txz`) + `Bionic-Box64-0.4.3-8ee3d8f2c.wcp` (2.7MB, xz, `box64`->`${bindir}/box64`). entry name = `type-verName-verCode` (verCode=0): `Proton-10.0-4-x86_64-0` / `Box64-0.4.3-8ee3d8f2c-0`. (c) **真机全链路** (Lenovo TB322FC / Adreno 830 / arm64-v8a / API 36, 2.7s): imagefs 提取 (1.6s) -> Proton/Box64 `extraContentFile`+`finishInstallContent` 安装 -> `ContainerManager.createContainer` (从 Proton `prefixPack.txz` 抽 Wine prefix) -> 反射调 preparer 内部 `ContentsManager.syncContents()` (preparer 不自调, 需补) -> 删 `.wine` 强制 `ensureWinePrefixReady` 修复 (firstTimeBoot=true) -> `extractGraphicsDriverFiles` -> `wrapper.tzst`/`layers.tzst`/`extra_libs.tzst` 提进 imagefs root + 14 envVars 填充. (d) **envVars 验证**: `GALLIUM_DRIVER=zink`, `WRAPPER_VK_VERSION=1.3.284` (GPUInformation 查到 Adreno 真实 Vulkan patch), `VK_ICD_FILENAMES=.../wrapper_icd.aarch64.json`, `MESA_VK_WSI_PRESENT_MODE=mailbox`, `WRAPPER_EMULATE_BCN=3`, DXVK 系 (`DXVK_ASYNC=1` 等). (e) **wrapper 库设备实测**: `libvulkan_wrapper.so` (19MB) + `libvulkan_freedreno.so` (11MB) + `libadrenotools.so`/`libhook_impl.so` + `wrapper_icd.aarch64.json` 全在 imagefs/usr/lib + usr/share/vulkan/icd.d. (f) **资产打包缺口 (已补)**: app APK 原 `app/src/main/assets/` 不存在 (路径 1 资产在 WinNative 上游未拷入); 测试从本地 WinNative checkout (`/Users/sky/co/github/WinNative` @ `48fe6b9`) 拷 `graphics_driver/wrapper.tzst`+`extra_libs.tzst`+`layers.tzst` 进 amphora assets (git-ignored `*.tzst`) 后 wrapper 提取验通. **生产需 `BundledContentSource` 或 build 时拷资产进 APK**. (g) **WineInfo 解析**: identifier `Proton-10.0-4-x86_64-0` 因尾部 `-0`(verCode) 不匹配 `pattern` (`^(wine|proton)-...-(x86_64|...)$`), 走 `wineProfile != null` 分支 -> `path=getInstallDir`; 需 profilesMap (syncContents) 已就绪, 否则回退 MAIN_WINE_VERSION (path=null, 不抛). `extractGraphicsDriverFilesCore` 的 envState.put 全无条件 (不依赖 wineInfo), 故 envVars 即使 WineInfo 未解析也填充.

8. **`:core:content` `BundledContentSource` 落地** (2026-07-13): (a) **DIP 落点**: `ContentManifest`+`ManifestEntry`+`BundledContentSource`+`BundledAssetInstaller`(接口) 在 `:core:content` (纯编排: 查 manifest -> 拷资产到临时文件 + SHA-256 流式 tee 校验 -> 委托 installer -> `ContentArtifact.Resolved`); 内核依赖的 concretion `WinlatorBundledAssetInstaller` 在 `:core:engine` (ARCHIVE=`TarCompressorUtils.extract(Type,File,File)`, WCP=`ContentsManager.extraContentFile(Uri)`+`finishInstallContent`)。与 `ImageFsRootfsInstaller`/`XServerWineSessionPreparer` 同 DIP (契约低模块, concretion engine); `:core:content` 无 Hilt, `EngineModule.@Provides` 构造绑定 `ContentSource`+`BundledAssetInstaller`+`ContentManifest`。(b) **manifest**: `core/content/src/main/assets/content_manifest.json` (04-ASSET-MANIFEST 派生, 5 组件: wine/box64=WCP + turnip/dxvk/audio_plugin=ARCHIVE; ROOTFS 故意不入 -- `RootfsInstaller` 拥有, `resolve(ROOTFS)` 抛 UnsupportedOp 指向它; `.wcp` SHA 已锁 (gap #1), tar 资产全锁)。`ContentManifest.parse(json)` 纯 JVM 可测, `load(context)` 读合并资产 (APK 内 `assets/content_manifest.json` 1828B 已确认)。(c) **WCP 路径替代 curl+push**: `resolve(WINE)`/`resolve(BOX64)` 从 APK assets 读 `.wcp` -> `extraContentFile` (本地 `nativeExtractArchive`, 非 D4 下载 stub) -> `finishInstallContent` 装到 `filesDir/contents/<type>/<verName>-<verCode>/` (与测试 curl+push 同路径, `syncContents`/`createContainer` 无感)。生产需 `.wcp` 打进 `app/src/main/assets/` (build 时 staging, git-ignored `*.wcp`)。(d) **ARCHIVE 前向**: `resolve(TURNIP/DXVK/AUDIO_PLUGIN)` SHA 校验 + 提到 `filesDir/amphora-content/<component>/<version>/`; 当前内核 (`extractGraphicsDriverFiles`/`ImageFsRootfsInstaller`) 仍直接读 `context.assets`, P3 才迁移消费 `Resolved.path`。(e) **缓存**: `isInstalled` (路径存在) 即命中; version 编码进路径, manifest bump 走新路径 (旧目录孤儿); `ERROR_EXIST` (他路径已装) 当成功 (CompletableDeferred 同步完成安全)。(f) **验证**: `:core:content:test` 6 JVM 单测过 (manifest 解析 / WCP+ARCHIVE / null-SHA skip / 默认 zstd / 未知组件 IllegalArgumentException); `:app:assembleDebug` 绿 (APK 61M -- 含 3 个 git-ignored `.tzst` + manifest + libwinlator.so 2.5M); `BundledContentSourceTest` (instrumentation, 3 tier assumeTrue 门控: manifest 加载恒跑 / TURNIP 需 wrapper.tzst / WINE 需 Proton .wcp) 编译过, 真机验 ✅ 通过 (2026-07-14, §P2 #10)。~~`.wcp` SHA 锁~~ ✅ (gap #1, 2026-07-14 -- SHAs 已入 manifest)。~~build 时资产 staging Gradle 任务~~ ✅ (§P2 #9)。

9. **build 时资产 staging Gradle 任务 `:app:stageBundledContent`** (2026-07-14): (a) **manifest 驱动**: 读 `core/content/src/main/assets/content_manifest.json` (单一真源) -> 每 entry 按 `kind` 分发; ARCHIVE 从 WinNative checkout (`amphora.winnative.dir`, 默认 `../WinNative`) `app/src/main/assets/<assetPath>` 拷 + SHA-256 校验 pinned; WCP 从 `nicholasx417/WinNative-Components` GitHub releases 下载 (URL 映射 build-only, 同 `ContentsManager.REMOTE_PROFILES` 源) -> `build/content-cache/` 缓存 -> 拷入 `app/src/main/assets/`。入 `app/src/main/assets/` (git-ignored `*.tzst`+`*.wcp`)。(b) **best-effort 不破构建**: 缺 WinNative checkout / 下载失败 / SHA 不匹配 -> `logger.warn` skip 该资产, task 不 fail (`BundledContentSourceTest` tier `assumeTrue` 门控, 构建无资产也绿)。(c) **standalone 不 auto-wire preBuild**: 160M Proton `.wcp` 入 `src/main/assets` 会膨胀每次 debug APK (61M->231M), 故 `stageBundledContent` 显式运行 (`./gradlew :app:stageBundledContent`); routine `assembleDebug` 保持 slim。删 `app/src/main/assets/*.wcp` 复 slim。(d) **idempotent**: `outputs.upToDateWhen` 检全资产存在 + ARCHIVE SHA; 已 staged skip; download 仅 cache miss 时。(e) **.wcp SHA 锁定 (gap #1 ✅)**: wine `Proton-10.0-4-x86_64.wcp` = `e61d29be8c736abe13f662d33ff4b14fae2b7294b011283be53c8e33665d2b48` (161M), box64 `Bionic-Box64-0.4.3-8ee3d8f2c.wcp` = `eec659650ff31df151c13d2a522330b1636b98cd82dbf60ba3ff522759f528fd` (2.7M); 已入 manifest `sha256` 锁 ✅ (2026-07-14, gap #1 闭环)。(f) **验证**: `:app:stageBundledContent` 跑通 (5 资产全 staged, ARCHIVE SHA 全匹配 manifest pinned: turnip `2651fbe6...`/dxvk `da30a104...`/audio_plugin `357bb53f...`); `:app:assembleDebug` 绿 APK 231M 含 `assets/{content_manifest.json,Proton*.wcp,Bionic-Box64*.wcp,graphics_driver/wrapper.tzst,dxwrapper/d8vk-1.0.tzst,pulseaudio.tzst}`; `:core:content:test` 6/6 绿 (无回归, 用 SAMPLE 不读真 manifest)。~~`.wcp` SHA 锁~~ ✅ (gap #1, 2026-07-14 -- SHAs 已入 manifest)。

10. **`BundledContentSourceTest` 真机验通过** (2026-07-14, Lenovo TB322FC / Adreno 830 / arm64-v8a / API 36): `:app:connectedDebugAndroidTest` (class filter `app.amphora.BundledContentSourceTest`) 3/3 绿 (`skipped=0 failures=0 errors=0`, XML `TEST-TB322FC - 16-_app-.xml`)。跑前 `adb shell pm clear app.amphora` 清 data 强制 cache miss -> `stageAndVerify` 真校验 SHA (locked, 非 null 分支 mismatch 即 throw)。三 tier: (1) `manifest_loadsAndParsesAllEntries` (0.001s) -- APK 内合并 manifest 加载, wine/box64/turnip 全在, wine contentType+verName 就绪; (2) `resolve_turnip_archive_extractsWithShaVerify` (0.03s) -- ARCHIVE: wrapper.tzst 拷+SHA 校验 (`2651fbe6...`)+`TarCompressorUtils.extract` 提取, wrapper_icd.aarch64.json 断言过; (3) `resolve_wine_wcp_installsLocally` (1.549s) -- WCP: 161M Proton.wcp 拷+SHA 校验 (`e61d29be...`, **锁后首次真校验**)+`extraContentFile`+`finishInstallContent` 装到 `filesDir/contents/Proton/10.0-4-x86_64-0/`, `bin/`+`prefixPack` 断言过。时长证 cache miss (1.549s >> hit ~0ms)。**P2 资产获取轨含真机全闭环**: 生产路径 `BundledContentSource` (APK 内联资产, 无远程下载) 设备验通, 替代 `PreparerGraphicsDriverTest` host curl+adb push workaround。注: connectedDebugAndroidTest 跑完自动卸载 app, run-as/logcat 无残留, XML 为权威结果。

### P3 · `:app` GameSessionScreen (D9) -- compile-only 落地 (`2a2078a`+`0404922`)
- [x] `AndroidView{SurfaceView}` 渲染靶 (复用 WinNative `XServerSurfaceView`/`VulkanRenderer`, renderer 配置 + `xServer.setRenderer` 移植自 XSDA setupUI L6914)。**触屏改 amphora-native `TouchInputOverlay`** (TouchpadView 与 XSDA 强耦合, P1 已砍 -- 详见 P3 发现 #2)
- [x] `GameSessionViewModel` 生命周期编排: `WineEngine.launch` -> `RootfsInstaller` -> `ContainerManager` -> `WineSessionPreparer` -> `XEnvironment.startEnvironmentComponents` -> `GuestProgramLauncherComponent` (`box64 wine explorer /desktop=WxH exe`) -> `XServerSurfaceView.attachSurface` (via surface StateFlow)
- [x] onPause->`XEnvironment.onPause` / onResume->`onResume`+`ProcessHelper.resumeAllWineProcesses` / onDestroy->`stopEnvironmentComponents`+`terminateAllWineProcesses` (+forceKill fallback)。`XServerSurfaceView.onPause/onResume` (渲染线程) 由 SurfaceHolder 生命周期处理
- [x] `WineEngineImpl` P3 step 真实现 (替换 TODO): `startEnvironment`/`launchGuestProgram`/`sessionHandleFor` (合并为 setupXEnvironment 移植); `inputFeed`/`audioSink` 换 `XServerInputSink`/`XServerAudioSink` (xServer.injectPointerMove/Button + `ALSAClient.setOutputSuspended`)。`EngineModule.provideContainerManager` stub **保留至 P4** (DIP: concretion 落 :core:engine 时删, 同 RootfsInstaller/Preparer)

**P3 关键发现 (供下个 agent):**
1. **P3 是编排/接线, 非重写**: `GuestProgramLauncherComponent` (1265 行, P1 已移植) 由*配置*驱动 -- `setGuestExecutable("wine explorer /desktop=shell,WxH exe")`+`setEnvVars`+`setContainer`+`setWineInfo`+`setBox64Preset`+`setTerminationCallback`, 再 `environment.startEnvironmentComponents()` (GPLC 最后启动, exec guest)。`XEnvironment` (P1 移植) API 是 `onPause/onResume/stopEnvironmentComponents` (非跟踪文档原写 "pauseComponents")。`XServerSurfaceView`+`VulkanRenderer`+`XServer`+`XServerComponent`+`ALSAServerComponent`+`SysVSharedMemoryComponent`+`NetworkInfoUpdateComponent` 全 P1 移植就绪 -- P3 只编排。
2. **TouchpadView 不可直用**: `runtime/input/ui/TouchpadView.kt` (697 行) import `XServerDisplayActivity` 并以 Activity 为 context (ctor `TouchpadView(ctx, xServer, handler, hideControlsRunnable)`) -- 与我们正重写的 XSDA 强耦合 (P1 已整块砍 input/ui)。改 amphora-native `TouchInputOverlay`: Compose `pointerInput`+`awaitEachGesture` 直调 `xServer.injectPointerMove/ButtonPress|Release` (XSDA TouchpadView 同路径)。MVP direct-touch (down=press+move, up=release); trackpad 相对模式/手势 profile 留 P4+。
3. **WinHandler 跳过 (MVP)**: `WinHandler(XServerDisplayActivity)` 同样 Activity 耦合, 且是*手柄*输入 (UDP -> wine)。MVP 触屏/鼠标走 `XServer` pointer injection (X 协议), 无需 WinHandler。`xServer.setWinHandler(null)`/`winHandler.start()` 不调。手柄支持留 P4+ (需解耦 WinHandler 或重写)。
4. **surface 暴露设计 (DIP)**: GameSession UI (D9 XSDA 重写) 需 `XServer` 构造 `XServerSurfaceView(ctx, xServer)`。`WineEngine` 接口保持 kernel-free ("feature layers never touch native internals"); 加 `GameSessionSurfaceProvider` 接口 (`WineEngineImpl` 实现 + `EngineModule` 单独 `@Provides`), 暴露 `surface: StateFlow<GameSessionSurface?>` (持 `XServer`)。`SessionHandle` (model) 仍 kernel-free (state/awaitReady/pause/resume/stop); concrete `XServerSessionHandle` (engine) 持 `XEnvironment`+`XServer`。
5. **Amphora Container -> WinNative Container 桥**: `GuestProgramLauncherComponent.setContainer` 要 WinNative `Container` (getEmulator/getBox64Version/getCPUList...), amphora `Container` 仅 {id,rootPath,winePrefixPath}。`WineEngineImpl.resolveWinNativeContainer` 镜像 `XServerWineSessionPreparer.resolveContainer` (按 rootPath 匹配 `wnContainerManager.getContainers()`)。**P4 ContainerManager 接管此桥** (TRACKING P2 #5f 已定)。launch 链在 `containerManager.getOrCreate` (amphora stub, `NotImplementedError`) 处抛 -- VM `catch (Throwable)` (CancellationException 重抛) 优雅显示错误不崩。
6. **envVars 合并**: XSDA setupXEnvironment 设 LC_ALL/WINEPREFIX/WINEDEBUG + **container env** + prep envVars (driver/DXVK/WineD3D) + shortcut env。amphora: `LocaleEnv` + `WINEPREFIX`/`WINEDEBUG` + `container.getEnvVars()`（`ZINK_*`/`TU_DEBUG`/`mesa_glthread`，OpenGL/ddraw→Zink 必需）+ `preparer.envVars()` + `spec.env` + ALSA。曾漏合并容器 env → AIO GL/DX7 黑屏+FPS（2026-07-26 已修）。GPLC 自设 HOME/PATH/LD_LIBRARY_PATH/DISPLAY/BOX64_* 等。
7. **imagefs 版本**: `ImageFsInstaller.LATEST_VERSION = 22` (WinNative 常量); `RootfsSpec(targetRoot=imageFs.getRootDir(), imagefsVersion="22", termuxfsSha256="")`。termuxfs 无独立 archive (D7: rpath 烙 Wine ELF, launch `LD_LIBRARY_PATH` 解析), 字段预留未来 pin。
8. **lifecycle-runtime-compose 加入 catalog**: `collectAsState`/`LocalLifecycleOwner` 需此依赖 (Compose BOM 1.11.4 的 `AndroidView` 在 `androidx.compose.ui.viewinterop` 非 `viewbinding`; `LocalLifecycleOwner` 已从 `ui.platform` 移至 `androidx.lifecycle.compose`)。app `build.gradle.kts` 加 `implementation(libs.androidx.lifecycle.runtime.compose)`。
9. **端到端验待 P4**: P3 compile-only -- launch 在 P4 container stub 抛, surface 不 emit, 屏幕显错 + Exit。真机验 (启动 .exe, Vulkan 画面, 触屏映射, 音频) 是 P4 验收 (RFC §8)。

### P4 · 收尾 -- 落地 + RFC §8 真机验收 ✅
- [x] `:core:container` `ContainerManager` 实现 = `WinlatorContainerManager` (`:core:engine`, DIP 桥 -- WinNative `ContainerManager` 861 行已在 P1 移植为内核, P4 写 amphora-facing concretion 适配它)
- [x] launcher: exe picker (SAF) + 分辨率选择 -> 构 `LaunchSpec`
- [x] **验收 (RFC §8)**: 启动 Windows .exe, Vulkan desktop 有画面 + 相对触控 ✅ (2026-07-21)。音频路径已接线 (ALSA component); 音量 API 仍未接 `AudioTrack`。跑前需 `:app:stageBundledContent`。
- [x] P4 后关键修复: `ecc7ee3` desktop surface + relative touch + `GameSessionLaunchTest`; `65e180f` DXVK → `Dxvk-3.0.2-gplasync.wcp` + host/guest Turnip wrapper 对齐; `04ec6f5` host Vulkan 读容器 `graphicsDriverConfig` + wine debug logs。

**P4 关键发现 (供下个 agent):**
1. **WinNative `ContainerManager` 861 行早非 P4 移植项**: P1 已把 `runtime/container/` 内核 (Container 773 + ContainerManager 861 + Shortcut) 整块拷进 `:core:engine` (保 `com.winlator.cmod` 包名)。P4 的 "移植" 实为写 amphora `ContainerManager` 接口的 **DIP concretion** (`WinlatorContainerManager`) -- 桥接 amphora 的 lean `Container{id,rootPath,winePrefixPath}` 与 WinNative `Container`(id+rootDir+wineVersion+emulator+...)。同 `ImageFsRootfsInstaller`/`XServerWineSessionPreparer` 模式: 契约在低模块 (`:core:container`), concretion 在 `:core:engine` (紧邻它适配的内核); `:core:container` 无 Hilt, `EngineModule` 绑定。**三个 sibling 接口全部毕业, 零 stub 剩余**。
2. **`getOrCreate` 拥有完整容器创建依赖链**: (a) `ContentSource.resolve(WINE/BOX64)` -- `BundledContentSource` 从 APK assets 装 Proton/Box64 .wcp (idempotent, `isInstalled` 缓存); (b) `contentsManager.syncContents()` -- 装载已装 profile 供 `createContainer`+`WineInfo.fromIdentifier`; (c) 解析 wineVersion -- manifest WINE entry `version` (= ContentsManager entry name `Proton-10.0-4-x86_64-0`), fallback 扫装好的 Proton profile, 最后 `MAIN_WINE_VERSION`; (d) `createContainer(JSONObject{name,wineVersion,graphicsDriver=wrapper,dxwrapper=dxvk+vkd3d,wincomponents=FALLBACK}, cm)` -- 自动选 box64 (x86_64) + 自动填 box64Version + 从 Proton `prefixPack.txz` 抽 Wine prefix; (e) `activateContainer` -- 符号链接 `home/xuser` -> `home/xuser-<id>` (Wine HOME = `imageFs.home_path` = `root/home/xuser` 的目标)。amphora `ContainerId` = WinNative int id 的字符串 (`"1"`); MVP 单共享容器 (RFC §9 multi-prefix=v0.2)。
3. **syncContents gap 修复 (原 §P2 #7c)**: `WineEngineImpl`/`XServerWineSessionPreparer`/`WinlatorContainerManager` **各持独立 `ContentsManager(context) 实例** (`ImageFs.find` 也是每次 `new`)。原 `PreparerGraphicsDriverTest` 需反射调 preparer 私有 `contentsManager.syncContents()` 才能让 `WineInfo.fromIdentifier`/`repairContainerWinePrefix` 解析 profile -- 是生产 bug。P4 修: `WinlatorContainerManager.getOrCreate` + `WineEngineImpl.launch` (getOrCreate 后) + `XServerWineSessionPreparer.resolveState` (!alreadyResolved 分支) 各自调 `contentsManager.syncContents()`。三次扫描 (各扫 `filesDir/contents/*/` 几个 profile.json) 开销可忽。未做 ContentsManager DI 单例 (会破 `PreparerGraphicsDriverTest` 直接 ctor + 反射 workaround; 净改进但风险/收益不划算, 推 v0.2)。
4. **exe 路径必须进 `drive_c` (Z: 映 rootfs 非 `/`)**: `WineUtils.createDosdevicesSymlinks` 造 `c:`->`../drive_c`, `z:`->`container.getRootDir()/../..` = **rootfs 根** (`filesDir/imagefs`), 非 Android `/`。`hostPathToMappedWinePath` 对 rootfs 外的路径走 fallback `Z:\<path>` -> 解析到 `<rootfs>/<path>` (不存在)。所以 launcher SAF 暂存的 `filesDir/exe/<name>` (rootfs 兄弟目录) **不可直传 Wine**。解法: `WineEngineImpl.stageExeIntoPrefix` 把暂存 exe 拷进容器 `drive_c/<name>`, 跑 `C:\<name>` (C:->drive_c 恒在)。镜像 WinNative `ensureDriveCGameSymlink` 模式 (WineUtils, 它对游戏目录造符号链接进 `drive_c/WinNative/Games/`)。idempotent (size 匹配 skip)。
5. **launcher exe picker = SAF `OpenDocument("*/*")`**: `.exe` mime 不可靠 (`application/octet-stream`/`x-dosexec`/未知), 用 `*/*`。`ContentResolver.query(DISPLAY_NAME)` 取文件名, 拷进 `filesDir/exe/<name>` (app-private, 无 scoped-storage 权限问题)。`Resolution` enum (1280×720/1920×1080/1024×768/800×600) `FilterChip` 选择。`feature:launcher` 加 `androidx.activity.compose` (rememberLauncherForActivityResult)。导航 `gameSessionRoute` 对 exePath `Uri.encode` (Unix 路径含 `/`, 防查询串解析出错; `NavType.StringType.parseValue` 自动 decode)。
6. **`removeContainer` 是 private**: amphora `delete` 不能直调; 用 public `removeContainerAsync(Container, Runnable)` + `suspendCancellableCoroutine` 桥 (callback 走 main Handler, resume 回 IO)。MVP 删除非关键路径 (RFC §8 只验启动)。shortcuts (`loadShortcuts`/`upgradeShortcuts`)/duplicate 留在移植内核未用 (D9 non-target / v0.2)。
7. **端到端验 ✅**: launch 链全接线并真机跑通 (RFC §8)。复现: `./gradlew :app:stageBundledContent` → 装 APK / `GameSessionLaunchTest`。DXVK 现为 WCP (`Dxvk-3.0.2-gplasync`, 见 manifest), 非早期 `d8vk-1.0.tzst` ARCHIVE。debug 默认直启 notepad -- 见 `AmphoraNavHost`。

### P4+ · 端到端硬化 (2026-07-21) ✅
- [x] 显示 explorer desktop surface (不再隐藏桌面进程窗口)
- [x] 触控改为相对移动 + tap-to-click
- [x] 真实 DXVK WCP + 旧 `dxvk-1.0` 容器迁移
- [x] host `VulkanRenderer` 与 guest 共用 adrenotools-wrapped Turnip
- [x] `GameSessionLaunchTest` / `XServerSurfaceViewInitTest` + content-aware connected 测试入口
- [x] 文档: [`05-ARCHITECTURE.md`](05-ARCHITECTURE.md) as-built 架构真源

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
| **as-built 架构 (优先读)** | `docs/05-ARCHITECTURE.md` |
| 项目决议 | `docs/01-RFC.md` (D1-D9 已定) |
| 研究基础 (WinNative 拆解) | `docs/00-RESEARCH.md` |
| Proton 自建 | `docs/RESEARCH-proton-wine-selfbuild.md` |
| scaffold 栈 + 踩坑 | `docs/02-SCAFFOLD.md` |
| 移植源码 | `/Users/sky/co/github/WinNative` @ `48fe6b9` |
| 栈版本参考 (最新) | `android/compose-samples` (Reply/Jetcaster) `gradle/libs.versions.toml` |
| 栈/convention 参考 (正典, 略旧) | `android/nowinandroid` (注意无连字符) |
| rootfs 源 | `winlator-imagefs` (cnb.cool/atowerlight, 构建配方); imagefs.tzst 真资产 = WinNative Git LFS (见 [`04-ASSET-MANIFEST.md`](04-ASSET-MANIFEST.md)) |
| 资产 SHA 锁 | [`docs/04-ASSET-MANIFEST.md`](04-ASSET-MANIFEST.md) |

---

## 5. 验证命令

```bash
./gradlew help                       # 全工程配置
./gradlew :app:stageBundledContent   # 打 imagefs/.wcp/.tzst 进 APK assets (端到端必需)
./gradlew :app:assembleDebug         # 全量 (Kotlin+KSP/Hilt+native .so+Compose+APK)
./gradlew :core:common:test          # 单测
./gradlew :app:connectedDebugAndroidTest   # 真机 / 含 GameSessionLaunchTest
# APK: app/build/outputs/apk/debug/app-debug.apk (含 lib/arm64-v8a/libwinlator.so)
```

---

## 6. 待决议 / 开放

- [x] `winlator-imagefs` 仓库地址确认 + clone ✅ cnb.cool/atowerlight (构建配方); imagefs.tzst 真资产 = WinNative Git LFS (190MB, SHA `0902e324...`)
- [x] ~~adrenotools submodule 来源~~ ✅ 已引为 amphora git submodule (`core/native/src/main/cpp/adrenotools` @ `8483dfd`, 递归 linkernsbypass `b10d485`); `git submodule update --init --recursive` 即可
- [x] ~~`NativeContentIO` 是否排除~~ ✅ P0 排除 (省 curl), P2 (`c593021`) **恢复提取**: `native_content_io.cpp` 回归 + zstd/xz 静态链, curl/download 2 JNI stub. `TarCompressorUtils` kernel-wide 可用 (见 P2 关键发现 #1)
- [ ] Proton 11 自建 CI 跑通前, 是否临时用 `proton-9.0-x86_64` 回退 (D5 回退路径)
- [ ] minor: AGP 9 debug strip 对本 .so no-op (见 P0 修正); release strip 在 P4 核实
- [x] ~~13 个 JNI 绑定类全放 :core:engine 还是 leaf 下沉~~ ✅ 已定: 11 个全落 `:core:engine` (leaf 下沉不可行, 见关键发现 #2)
- [x] ~~P2/P4: `RootfsInstaller` + `WineSessionPreparer` + `ContainerManager` 已真实现~~ ✅ `RootfsInstaller` (P2 `c593021`) + `WineSessionPreparer` (P2 `829e83b`, `XServerWineSessionPreparer` 849 行) + `ContainerManager` (P4, `WinlatorContainerManager`) concretion 均在 `:core:engine` (DIP -- `:core:rootfs`/`:core:container` 不可见 kernel). `:core:rootfs`/`:core:container` 无需 Hilt. **`EngineModule` 零 stub 剩余** (三个 sibling 接口全毕业, `StubContainerManager` 删除).
