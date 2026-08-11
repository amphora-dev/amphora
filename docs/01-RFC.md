# RFC-001: Amphora 项目立项与架构

| | |
|---|---|
| **Status** | Approved - 决议已定；v0.1 已按本 RFC 落地并端到端跑通（as-built 见 [`05-ARCHITECTURE.md`](05-ARCHITECTURE.md)） |
| **作者** | (待定) |
| **日期** | 2026-07-10 |
| **依据** | [`00-RESEARCH.md`](00-RESEARCH.md) |

> 本文保留立项时的决策与取舍，不作为当前实现清单。后续演进会在对应决议下标注；
> 未标注的现状以 [`05-ARCHITECTURE.md`](05-ARCHITECTURE.md) 为准。
> 特别是本文原计划中的 `libfakeinput`、WinHandler 和 SurfaceView 已分别被 X 协议注入、
> 解耦后的 `TouchpadView` 与 TextureView-based `XServerSurfaceView` 取代。

---

## 1. 名称与愿景

**Amphora**（古希腊双耳酒器）--承载 Wine 容器的容器。

**愿景**: 一个现代化、最小先跑通、长期工程化的 Android Wine 模拟器。以 WinNative 为参考（事实依据见 `00-RESEARCH.md`），但全新工程、干净架构，从 MVP 起步，模块边界为长期扩展铺路。

## 2. 背景

- 参考项目 WinNative 已完成深度分析：183k Kotlin/Java + ~78.5k native。可砍: feature 层 91.6k + Steam native ~41k + 死代码 native(proot/patchelf 等) ~28k。Amphora 实际需移植的 native 仅 `winlator`(9.6k)+`fakeinput`+`adrenotools` ≈ 10k；Java 运行时内核（xserver/renderer/environment 等 `runtime/`）整块复用 ~32k 行（见 §7），仅 app 壳 + 特性层全新写。
- **已有资产**: `winlator-imagefs`（Bionic rootfs，42 包，经多轮 CI 迭代转绿，已验证对齐）--rootfs 层已攻克。
- native 技术核心 `winlator`（Vulkan X-server 渲染器，模块 9.6k 行，含 `vk_renderer.c` 3.2k）整块复用；`proot`/`patchelf` 为死代码不移植（见 `00-RESEARCH.md` §2.2/§6.1）。
- 所有运行时二进制（box64/Wine/Turnip/DXVK）均有开放源码构建源。

## 3. 目标与非目标

### v0.1 (MVP) 目标
- 启动一个 Windows .exe，有画面（Vulkan 渲染）、有触屏映射、有音频
- 通过远程 manifest 固定 rootfs、Box64、Wine、图形组件的 URL/大小/SHA，
  安装后复用；常规 APK 不捆绑大型运行时
- 极简 UI：选 exe → 分辨率 → 启动

### v0.1 非目标
- ❌ Steam / Epic / GOG 集成
- ❌ .wcp 运行时下载 / 多版本切换
- ❌ 游戏库 / 云存档 / 排行榜 / 快捷方式
- ❌ 多 flavor 品牌

### 长期目标
- 模块化：可加商店/库而不污染内核
- 可复现构建：所有外部二进制版本锁 + 哈希校验
- 稳定 native ABI
- 可切换 Wine/box64/DXVK 版本（通过可插拔 ContentSource）

## 4. 设计原则（吸取 WinNative 教训）

> WinNative 最大架构债: `XServerDisplayActivity` 11,000 行把"渲染内核 + 进程启动 + Steam 逻辑"搅在一起。

1. **引擎/特性隔离** — Wine/渲染/输入是稳定内核（`engine`），特性层只依赖内核接口，**绝不反向依赖**。
2. **ContentSource 可插拔** — 接口抽象内容来源。MVP 用 `BundledContentSource`（固定二进制），后期加 `RemoteContentSource`（.wcp 式下载），引擎不感知差异。<br>*（现状：已全量切到 `RemoteContentSource`，`BundledContentSource` 已删除；见 05-ARCHITECTURE §2。）*
3. **native ABI 稳定** — C/C++ 层暴露版本化 JNI 接口；Kotlin 侧只调接口，不碰 native 内部。
4. **可复现构建** — 外部二进制版本锁 + 哈希校验；rootfs 用 winlator-imagefs 源码构建。

## 5. 技术栈 (2026 现代 Android)

- Kotlin 2.x + Jetpack Compose + Material3
- Gradle Kotlin DSL + version catalog (`libs.versions.toml`) + `build-logic` convention plugins
- Hilt (DI) + Coroutines/Flow + Navigation-Compose；Room 仅保留在版本目录，当前业务未使用
- NDK r27/r29 + CMake
- 单元测试 (JUnit) + instrumentation 测试骨架

## 6. 模块架构

```
:app                       Compose 壳: 导航/DI 装配/启动
:core:common               工具/协程/错误模型
:core:native               C/C++: winlator(vk_renderer+X compositor) + fakeinput + adrenotools (JNI)  ← 复用
:core:rootfs               imagefs 安装/提取/版本  ← 复用 winlator-imagefs
:core:content              组件管理 (ContentSource 接口: bundled/remote)
:core:container            Wine prefix/容器创建与生命周期
:core:engine               运行时内核: 进程启动/X显示/输入/音频 (稳定接口)
:feature:launcher   ← MVP  选 exe + 分辨率 + 启动
:feature:settings          设置
# ── 长期扩展 (不污染内核) ──
:feature:library           游戏库
:feature:stores:steam      (可选, 后期)
:feature:stores:epic
```

**依赖方向严格单向**: `feature → engine → {native, rootfs, content, container}`。native 永不向上依赖。

### 关键接口（v0.1 先定义）

```kotlin
// core:content
interface ContentSource {
    suspend fun resolve(component: ComponentId): ContentArtifact  // bundled 或 remote 透明
}

// core:engine
interface WineEngine {
    suspend fun launch(session: LaunchSpec): SessionHandle  // 启动 box64+wine, 返回渲染 surface
    fun inputFeed(): InputSink
    fun audioSink(): AudioSink
}
```

引擎内核对上层只暴露 `WineEngine`；Steam/库等特性永远拿不到 native 内部。

## 7. 复用策略

| 资产 | 来源 | 处理 |
|------|------|------|
| **winlator native lib** (9.6k，含 `vk_renderer.c` 3.2k) | WinNative `cpp/winlator/` | **整块移植到 `:core:native`** - 技术核心，绝不重写。JNI 复用策略见下 |
| winlator JNI 调用类（当前保留 11 个运行时绑定类） | WinNative `runtime/` | **保留 `com.winlator.cmod` 包名**，由 `:core:engine` 的 `WineEngine` facade 隔离；导出符号数由 native 构建门禁核对，不再把早期清点值当契约 |
| fakeinput + adrenotools | WinNative `cpp/` | 移植：fakeinput 是独立 `.so`（LD_PRELOAD shim，无 JNI，完全解耦）；adrenotools 是 submodule，静态链入 `libwinlator.so`（Turnip 驱动加载）|
| audio_plugin (`module_pcm_android_aserver.c`) | WinNative `audio_plugin/` | **随 winlator-imagefs 的 `alsa-android-aserver` 包进 rootfs**（`usr/lib/alsa-lib/` + `usr/etc/alsa/conf.d/`），非独立 native 移植项。构建期核实该包产物 = 此插件 |
| ~~proot (18k)~~ | WinNative `cpp/proot/` | **不移植** - 死代码，Bionic 不需 proot |
| ~~patchelf (7.7k)~~ | WinNative `cpp/patchelf/` | **不移植** - 死代码（零调用者）；Bionic 靠 NDK 编译期 interpreter + `LD_LIBRARY_PATH`，无需 ELF patch |
| **imagefs rootfs** | winlator-imagefs | 直接用作 rootfs；构建脚本可纳入项目 |
| box64/Wine/Turnip/DXVK 二进制 | nicholasx417 / Drivers / proton-wine | 锁版本 + 哈希校验，CI 拉取 |
| Kotlin/Compose app 壳 | - | **全新写**，现代架构；app 壳 + 特性层不搬 WinNative 的 app/feature 层（runtime/ 内核 ~32k 行整块复用，见上）|
| Steam/商店/.wcp | — | **不搬**，后期按需在新架构下重写 |

**原则: native 内核 + Java 运行时内核 + rootfs 复用；app 壳（Activity/导航/DI）+ 特性层全新写。**

### 语言策略（总纲）

| 层 | 语言 | 策略 | 理由 |
|---|---|---|---|
| `runtime/` 内核 ~32k 行（含 audio/xserver/renderer...） | **Java 为主，按边界裁剪** | 保留核心包名 | 当前 11 个 JNI 绑定类保留 `com.winlator.cmod`；epoll/AudioTrack/SHM 等核心逻辑继续使用 Java/C，feature/UI 已拆出 |
| `WineEngine` facade + app 壳（Activity/Compose UI/导航/DI） | **Kotlin** | 全新写 | facade 委托给复用的 Java 类，上层 feature 只见 `WineEngine` 接口 |
| `XServerDisplayActivity`（11k 行） | Kotlin/Compose | **拆解重写** | 与 Activity 生命周期/UI/Steam 深度耦合，无法整块搬；抽取启动编排逻辑重写薄壳 |
| `wine`/`content` | Java | 精简（删分支） | 不转语言，只删下载/多版本分支，保留单版本路径 |
| `input/controls` | - | 砍掉 | MVP 非目标，推 v0.4 |

> 一句话：**runtime 内核全 Java 复用（包括 audio），只有 app 壳 + facade 用 Kotlin；唯一重写的是 `XServerDisplayActivity`。**

### winlator native 复用细则（D5 配套）

- **JNI 面**: 使用名称绑定 `Java_com_winlator_cmod_...`（无 `RegisterNatives`）。
  NativeContentIO 恢复后导出面已超过立项时的 48 个估算；精确集合由构建产物检查，
  不在 RFC 手工维护数字。
- **复用策略**: 保留 `com.winlator.cmod` 包名，11 个当前绑定类放在
  `:core:engine`；`:core:engine` 的 `WineEngine` Kotlin facade 使上层 feature
  不接触 native 内部。
- **C-to-Java 回调**: `vulkan.c` 运行时 `FindClass("com/winlator/cmod/runtime/content/AdrenotoolsManager")` 回调解驱动名 -> 必须提供该类（构造器 `(Context)` + `getLibraryName(String)`）。
- **进程级副作用**: `JNI_OnLoad` 调 `prctl(PR_SET_CHILD_SUBREAPER,1)`（整个 app 进程成为 subreaper），无 SIGCHLD handler。
- **CMake 自含**: 单 `CMakeLists.txt` 出 `libwinlator.so`+`libfakeinput.so`；FetchContent zstd v1.5.6 / xz v5.4.6 静态链入；adrenotools submodule `add_subdirectory` 静态链入；19 个 GLSL shader 经 NDK `glslc` + `bin2c.cmake` 编入。
- **可选裁剪**: `NativeContentIO`(`native_content_io.cpp`) 是唯一用 curl+zstd+xz 的文件（归档解压/下载）。若 MVP 不走 native 下载，可排除该文件 -> 省掉 curl/OpenSSL Prefab AAR 依赖（zstd/xz 仍需，Java 侧 `zstd-jni` 另用）。

### Java 运行时内核复用（:core:engine）

winlator native 只是渲染器；X server 的窗口/输入/进程模型在 **Java 侧**。`:core:engine` 需从 WinNative `runtime/` 整块复用以下内核（保留 `com.winlator.cmod` 包名，不重写）：

| 子模块 | 行数 | 作用 | MVP |
|---|---:|---|---|
| `display/xserver` | 9,833 | X11 服务器（WindowManager/Pointer/Drawable/Pixmap/Cursor/Keyboard/Grab...）99 类 | 必需 |
| `display/renderer` | 1,930 | VulkanRenderer/Texture/GPUImage（native 绑定 + 渲染线程 + 窗口事件监听） | 必需 |
| `display/environment` | 2,840 | XEnvironment + GuestProgramLauncher(1265) + ALSAServer + NetworkHelper | 必需 |
| `display/connector` | 774 | XConnectorEpoll/ClientSocket（X 协议 socket I/O） | 必需 |
| `display/winhandler` | 1,985 | WinHandler（输入事件 -> X server 注入） | 必需 |
| `display/ui` | 1,965 | XServerSurfaceView + TouchpadView（渲染靶 + 触屏覆盖层） | 必需 |
| `audio` | 922 | ALSAClient/ALSAServerComponent/RequestHandler（整块复用 Java） | 必需 |
| `system` | 1,446 | ProcessHelper/GPUInformation/EnvironmentManager | 必需 |
| `compat` | 1,384 | box64 启动器（x86_64 路线） | 必需 |
| `container` | 1,634 | Container(773) + ContainerManager(861) Wine prefix/容器生命周期 | 必需 |
| `content` | 1,194 | ContentsManager + **AdrenotoolsManager**(290->~30 精简，见 D8；GPU 驱动加载，被 `vulkan.c` JNI 回调) | 简化 |
| `shared` 硬依赖 | ~1,700 | FileUtils(705)/TarCompressorUtils(407，解压 .tzst/.txz/.wcp)/XForm(100，坐标变换)/KeyValueSet/NativeContentIO(JNI 绑定类) | 必需 |
| `wine` | 3,655 | Wine 版本/路径管理 | 简化（单版本） |
| ~~`container/Shortcut`~~ | 442 | 主屏快捷方式 | 砍（非目标） |

**不搬 / 重写**（改动多的点，见下）：
- `XServerDisplayActivity.java`（**10,995 行**）-- 巨型 Activity，但 Steam 相关 **903 处** + 录屏/快捷方式 **715 处**可砍（占大头）；真正 MVP 核心启动逻辑（`setupXEnvironment`/`setupWineSystemFiles`/`changeWineAudioDriver`/`setupUI` 等约 10 个方法）仅 **~2-3k 行**。**Amphora 不搬**，用 Compose 重写薄启动 Screen 抽取这些核心方法。抽取后 ~800-1,000 行落到 `:core:engine`(`WineSessionPreparer`) + `:app`(`GameSessionScreen`/`ViewModel`)。**详见 D9。**
- `input/controls`（5,435 行，虚拟手柄/按键布局）-- MVP 只要基础触屏映射（TouchpadView 已含），完整手柄推 v0.4。
- `display/recording`（506 行）、`ExternalDisplayController`（884 行）-- MVP 不做。
- `steampipe`（172 行）-- Steam，不搬。

### 架构债/风险点（MVP 需处理）

- **AdrenotoolsManager 反向依赖 feature 层** ✅ 已定 (a): `runtime/content/AdrenotoolsManager`（内核，被 `vulkan.c` JNI 回调）原 import `feature.settings.GraphicsDriverConfigUtils`（特性层）违反 §4 隔离。**vulkan.c 只回调构造器 + `getLibraryName`**（已核实），故 MVP **固定单 Turnip 驱动**，AdrenotoolsManager 从 290 行精简到 ~30 行：仅保留构造器 + `getLibraryName`，删掉 `reloadContainers`（反向依赖根源）/`installDriver`/`removeDriver`/`enumarateInstalledDrivers`/`setDriverById`/`extractDriverFromResources` 等 13 个多驱动管理方法。架构债解除。
- **XServerDisplayActivity 拆解粒度**: 11k 行里精确剥离核心启动逻辑（约 2-3k）需细心，Steam/录屏/快捷方式逻辑穿插在启动流程中（如 `runPreGameSetup`/`installRedistributables` 混了 Steam），抽取时要理清依赖，避免把可砍逻辑带入 `:core:engine`。

## 8. MVP v0.1 范围

| 项 | 做法 |
|----|------|
| rootfs | 捆绑 winlator-imagefs（Bionic 42 包）+ termuxfs 运行期 lib（复现 `/data/data/com.termux/files/usr/lib` rpath）|
| 运行时二进制 | 自建 Proton 11 x86_64（.wcp）+ box64 + Turnip + DXVK，SHA256 锁定打入 assets |
| native | winlator(48 JNI) + fakeinput + adrenotools 整块复用（保留 `com.winlator.cmod` 包名，C 零改）；audio_plugin 随 imagefs 进 rootfs |
| UI | 单屏：选 .exe -> 分辨率 -> 启动；运行中触屏映射 |
| 显示 | Xvfb -> vk_renderer -> `AndroidView{SurfaceView}`（详见下）|
| 输入 | 覆盖层 View 的 onTouchEvent -> X server inject（详见下）|
| 音频 | ALSA `android_aserver` 插件 -> Unix socket -> `ALSAServerComponent`(Java 复用) -> AudioTrack（详见下）|

**验收标准**: 启动一个 Windows .exe，有画面、有触屏、有声。

### 显示衔接（P3 决议）
- **Surface 获取**: `vk_renderer.c:2638` `nativeSurfaceCreated` 经 `ANativeWindow_fromSurface` + `vkCreateAndroidSurfaceKHR` 造 `VkSurfaceKHR`；swapchain 在 `nativeSurfaceChanged` 建。**native 衔接代码零改动**。
- **Compose 方案**: `AndroidView { SurfaceView(...) }` 包在 `Box` 里，`SurfaceHolder.Callback` -> `attachSurface(holder.getSurface())`。**选 SurfaceView 不选 TextureView**（SurfaceView 硬件直通无额外 GPU 拷贝；TextureView 多一次合成，对按需渲染净损失）。
- **渲染模式**: 默认按需（`RENDERMODE_WHEN_DIRTY`），由 X Present 逐帧事件 + 窗口内容事件唤醒，`Choreographer` 合流到每显示帧最多一次；连续模式仅录屏兜底。Compose 侧无需主动 invalidate。
- **HUD/抽屉**: 透明 Compose UI 叠在 SurfaceView 之上正常工作（SurfaceView surface 独立窗口穿洞在下）。

> **后续演进（2026-07）**：Compose `AndroidView` 下的 SurfaceView 子窗口在目标设备上
> 不可靠，实际落地改为 `XServerSurfaceView`（TextureView）。当前实现见
> `05-ARCHITECTURE.md` §4。

### 输入衔接
- **不进 SurfaceView**: WinNative 的 `XServerSurfaceView` 纯渲染靶，不接触摸；触摸由叠在上面的 `TouchpadView`（透明覆盖 View）消费 -> `xServer.injectPointerMove/Button` -> X 协议转给 Wine。
- **Amphora 落地**: 同一 `Box` 内 `AndroidView { TouchpadView(...) }` 覆盖（复用 WinNative 已验证的多指/双指滚动/tap-to-click/触屏笔逻辑），或纯 Compose `Modifier.pointerInput` 重写手势状态机（代价高，非 MVP）。

### 音频衔接（ALSA 路线，MVP 唯一音频路径）
- **完整数据流**: Wine `winealsa.drv` -> alsa-lib(imagefs) -> `libasound_module_pcm_android_aserver.so`(imagefs `usr/lib/alsa-lib/`) -> Unix socket `/usr/tmp/.sound/AS0` -> `ALSAServerComponent`(Java 复用, epoll) -> `AudioTrack` -> 扬声器。
- **Wine 后端配置**: `user.reg` `[Software\\Wine\\Drivers] Audio="alsa"`（建容器时写）。
- **native 插件**: `audio_plugin/module_pcm_android_aserver.c`（ALSA PCM ioplug 插件）随 winlator-imagefs `alsa-android-aserver` 包进 rootfs，**非独立 native 移植项**（构建期核实包产物）。
- **Java 侧（整块复用）**: `ALSAServerComponent` + `ALSAClient`（~615 行）是纯 app 运行时逻辑--epoll socket 服务器 + 自定义二进制协议解析 + `AudioTrack` 创建/写入 + 音量 DSP + underrun 处理 + 可选 SHM。落在 `:core:engine`，原样复用（保留 `com.winlator.cmod` 包名），依赖 winlator 的 `XConnectorEpoll` JNI。**原 MVP 决议只做 ALSA**，PulseAudio/AAudio 留给后续。

> **后续演进（2026-08）**：ALSA 仍是默认及回退路径；可选 PulseAudio/AAudio
> 已实现。链路为 `winepulse.drv → PulseAudio → module-aaudio-sink → AAudio`，
> PA13 运行库与模块随 APK 交付。详见 `05-ARCHITECTURE.md` §4 和
> `08-EGGGAME-COMPARISON.md` §7。

## 9. 路线图

| 阶段 | 内容 | 状态 |
|------|------|------|
| **v0.1** | 一个 exe 跑通（显示、输入、ALSA 音频） | ✅ 已完成 |
| 内容供应 | `RemoteContentSource`、SHA pin、Wine/Box64/DXVK/VKD3D 更新 | ✅ 已完成 |
| 音频增强 | 可选 PulseAudio/AAudio，ALSA 回退 | ✅ 已实现，待扩大真机回归 |
| 输入增强 | 手柄、虚拟键盘、自定义布局 | 📋 候选 |
| 兼容路线 | FEX/arm64ec 与更多设备适配 | 📋 候选 |
| 商店集成 | Steam/Epic/GOG 独立 feature，不反向侵入内核 | 长期候选 |

## 10. 待决议（请审核）

### D1: 架构路线 - Bionic vs glibc ✅ 已定
- **Bionic**。理由: winlator-imagefs 投入在此且已验证；贴近 WinNative/Winlator-Bionic；性能更好。
- 代价: 胶水比 glibc 路线复杂（已接受）。

### D2: 执行模型 - proot vs 原生 ✅ 已定
- **不需要 proot**。Bionic rootfs 原生执行（工作目录 = rootfs + `LD_LIBRARY_PATH` 指向 `rootfs/usr/lib:/system/lib64`）；`Runtime.exec()` 直接跑 box64/wine，无 chroot（已核实 `GuestProgramLauncherComponent.java:258` 启动链）。
- proot 为 glibc 路线遗留死代码（未编译、零调用）；prefix 隔离靠 Wine prefix（`wineboot` 生成 `WINEPREFIX`）。

### D3: 项目名 ✅ 已定
- **Amphora**，包名 `app.amphora`，仓库 `amphora-dev/amphora`。

### D4: 内容获取 ✅ 已定（后经演进）
- 原 MVP 决议「不做下载、捆绑固定二进制」已被运行时路径取代。
- **现状**：设备经 `RemoteContentSource` + `VerifiedAssetDownloader` 拉取
  [`amphora-dev/content_manifest`](https://github.com/amphora-dev/content_manifest)
  （GitHub Contents API，raw GitHub 回退）中的 SHA pin；native
  `nativeDownloadFile` 仍为 stub（仅保留符号）。
- 自建通道：`amphora-dev/proton-wine`（Proton 11）、`amphora-dev/imagefs`（rootfs / Box64 / wrapper）。
  部分 runtime 资产与 DXVK 仍可来自 WinNative / nicholasx417 上游 pin。

### D5: Wine 架构与版本 ✅ 已定
- **Proton 11 x86_64 + box64**（`WinNative-Emu/proton-wine` 默认分支 `proton_11.0`，源码自建，见 D7）。
- **架构选 x86_64 而非 arm64ec**: 经核实 WinNative 默认运行时即 `proton-9.0-x86_64`（`WineInfo.MAIN_WINE_VERSION` 硬编码），box64 套在 wine 外翻译整个 x86_64 Wine 进程（`GuestProgramLauncherComponent.java:1147` 形如 `box64 wine explorer /desktop=... game.exe`）。box64 是 rootfs Tier9 内单个 ELF，依赖单一、构建简单（单次 cmake）。
- **arm64ec 被否决**: arm64ec Wine 虽原生 ARM64 直跑（不套 box64），但 x86_64 .exe 翻译改靠 FEXCore 进程内 JIT——需额外 `libarm64ecfex.dll`+`libwow64fex.dll`（必须，放 system32）+ 可选 2 个 `.so` + `HODLL` 环境变量 + `~/.fex-emu/AppConfig/` + 22 个 FEX_* 调优开关（`extractEmulatorsDlls()` `:359-490`）；构建需两次 mingw 交叉编译（arm64ec + aarch64 三元组）。**整体比 box64 更重**，"启动链更简"仅指命令前缀少个 box64。MVP 求最简应选 x86_64+box64。
- 版本: `proton_11.0` 源码分支（上游最新，WinNative 运行时尚未验证过此版本，已知风险）。回退路径为 `proton-9.0-x86_64`（WinNative 默认实测版本）。

### D6: patchelf 运行时 vs 构建期 ✅ 已定
- **不移植 patchelf**。`PatchElf.java` 在 WinNative 中零调用者（死代码），`libpatchelf.so` 编了但从未 loadLibrary。
- Bionic 路线无需任何 ELF patch：interpreter 由 NDK 工具链编译期 baked-in（`/system/bin/linker64`），库查找靠运行时 `LD_LIBRARY_PATH`。MVP 不带 patchelf。

### D7: MVP Proton 二进制来源 ✅ 已定
- **自己从源码构建**：fork `WinNative-Emu/proton-wine`（锁 `proton_11.0` commit），基于 `build-proton-sdk35.yml` workflow（matrix 改 `[x86_64]` only）产出 `proton-11.0-1-x86_64.wcp`。详见 [`RESEARCH-proton-wine-selfbuild.md`](RESEARCH-proton-wine-selfbuild.md)。
- **上游 x86_64 无长期预编译 release**（最近 release 仅 arm64ec；x86_64 .wcp 只存于 30 天过期 CI artifact）→ 自建是被迫也是正确选择。
- **termuxfs（~1.2 GB）+ prefixPack.txz（7.9 MB）复用上游 `Assets` release**（SHA256 锁定），MVP 不自建 termuxfs（termux-packages Docker 全量编译数小时，推 v1+）。
- **box64** 走 `nicholasx417/WinNative-Components`（开源 CI 构建）或自建，独立于 Wine。
- **.wcp 产物 SHA256 锁定**，打入 APK assets（`noCompress` xz），首启解压到 imagefs。
- **rpath 路径耦合（硬约束）**: Wine `.so` 的 rpath bake 死 `/data/data/com.termux/files/usr/lib`，Amphora imagefs 必须复现该路径（软链/拷贝 termuxfs 的 lib），否则运行期找不到 freetype/SDL2/pulse。
- **46 补丁几乎全为 Android/Bionic 必需**（esync/fsync/ntdll/nsiproxy/winex11/winepulse/wow64 等），无整类可删；仅 advapi32（化妆）+ opengl32（防御）可选。

> **后续演进**：Proton、Box64、DXVK、VKD3D 和 rootfs 均由
> `amphora-dev/imagefs` 发布并经远程 manifest 安装，不再默认打入 APK；
> Proton WCP 也已由 BuildStream 自构建并加入 winepulse 产物门禁。

### D8: AdrenotoolsManager 简化（GPU 驱动） ✅ 已定
- **固定单 Turnip 驱动**，MVP 不做多驱动切换。Turnip 驱动固定版本捆绑 assets（`graphics_driver/extra_libs.tzst`），预装到 `contents/adrenotools/<固定id>/`。
- `AdrenotoolsManager` 精简到 ~30 行：仅保留 `vulkan.c` JNI 回调必需的构造器 `(Context)` + `getLibraryName(String)`；删除 `reloadContainers`（反向依赖 `feature.settings.GraphicsDriverConfigUtils` 的根源）+ 12 个多驱动管理方法。
- 效果：解除内核反向依赖特性层的架构债（§4）；多驱动切换推 v0.3（`RemoteContentSource` 配套）。

> **后续演进**：默认路径已改为 wrapper 包装系统 Vulkan；可选 Turnip 仍通过
> `contents/adrenotools/<id>/` 安装。`extra_libs.tzst` 已废止，Mesa GL 自建并入
> imagefs。详见 `04-ASSET-MANIFEST.md` §0.6。

### D9: XServerDisplayActivity 拆解策略 ✅ 已定

`XServerDisplayActivity`（10,995 行，421 方法）按职责拆解：

| 职责块 | 方法数 | 行数 | 处理 |
|---|---:|---:|---|
| Steam | 52 | 2,089 | 砍（非目标）|
| 录屏 | 21 | 431 | 砍（非目标）|
| 快捷方式 | 15 | 183 | 砍（非目标）|
| UI/输入/任务管理 | 70 | 1,219 | Compose 重写（薄）|
| 核心/生命周期 + Wine 准备辅助 | ~30 | ~1,400 | 抽取（剥 Steam 分支后 ~800-1,000）|

**抽取后落点：**

`:core:engine` -- 从 XSDA 抽取的 Wine 准备逻辑移到新类 `WineSessionPreparer`（Java）：
- `ensureWinePrefixReady`/`ensureWinePrefixEssentialFiles`（L7127/7164）
- `extractDXWrapperFiles`（L7970）
- `ensureLaunchRuntimeFilesReady`（L6280）
- `applyGeneralPatches`（L10793）
- `cleanupLingeringSessionProcesses`（L2632）/ `buildWineDebug`（L3556）

`:app` -- Kotlin/Compose 新写薄编排器：
- `GameSessionScreen`（Compose）：`AndroidView{SurfaceView}` + `TouchpadView` 覆盖
- `GameSessionViewModel`：生命周期编排，委托 `WineEngine`

**关键**：box64 wine 命令构造已在 `GuestProgramLauncherComponent.execGuestProgram`（L859，复用），`:app` 侧只传 exe 路径 + EnvVars，**不重写 `getWineStartCommand`**（253 行大部分是 Steam/exe 路径解析）。

**MVP 启动调用链（剥掉 Steam/快捷方式）：**
```
GameSessionViewModel.launch(LaunchSpec)
  -> ContainerManager 取/建容器
  -> WineSessionPreparer: ensureWinePrefix + extractDXWrapper + ensureLaunchRuntimeFiles
  -> XEnvironment.startEnvironmentComponents (ALSAServer + XServer + GuestProgramLauncher...)
  -> GuestProgramLauncherComponent.execGuestProgram (box64 wine explorer /desktop=WxH exe)
  -> VulkanRenderer.attachSurface (AndroidView{SurfaceView})
```

**生命周期：**
- `onPause` -> `XEnvironment.pauseComponents` + ALSAServer suspend
- `onResume` -> `XEnvironment.resumeComponents`
- `onDestroy` -> `XEnvironment.stopComponents` + ProcessHelper kill

## 11. 构建与可复现策略

- **rootfs**: winlator-imagefs 构建产物（源码构建，可复现，42 包）。MVP 够用（硬依赖全覆盖；gnutls/gstreamer 软依赖缺失优雅降级，v0.2+ 按需增量）。
- **termuxfs 运行期 lib**: 复用上游 `Assets` release（SHA256 锁定），部署到 imagefs 内 `/data/data/com.termux/files/usr/lib`（或软链）匹配 Wine rpath。v1+ 走 termux-packages 自建。
- **Proton Wine**: fork `WinNative-Emu/proton-wine`，基于 `build-proton-sdk35.yml`（matrix `[x86_64]`）CI 自建，产 `proton-11.0-1-x86_64.wcp`。termuxfs + prefixPack 复用上游预编译（SHA 锁定）。详见 D7 + [`RESEARCH-proton-wine-selfbuild.md`](RESEARCH-proton-wine-selfbuild.md)。
- **box64**: `nicholasx417/WinNative-Components` CI 产物（开源）或自建，SHA256 校验。
- **Turnip/DXVK**: `WinNative-Emu/Drivers` / `doitsujin/dxvk` 开放构建，版本锁 + SHA256 校验。**MVP 固定单 Turnip 驱动**（见 D8），不做多驱动切换。
- **native（as-built）**: 单 `CMakeLists.txt` 编 `libwinlator.so`；
  `libfakeinput.so` 已停编。保留 `com.winlator.cmod` 包名及 11 个绑定类。
  zstd/xz 静态链入用于内容提取；native curl 下载保持 stub，网络下载由 Kotlin
  `VerifiedAssetDownloader` 完成。adrenotools 静态链入，19 个 GLSL shader 随 native
  构建；ALSA aserver 由 imagefs 提供。
- **APK**: 单 standard flavor。assets 用 `noCompress` 保留 tzst/txz/xz。
- **不可复现部分** (Steam 客户端 / 微软 DLL): MVP **不含**（不做 Steam 集成）；v1.x+ 加 Steam 时按 WinNative 模式从 redist 抽取并声明来源。

---

## 审核清单

- [x] D1 架构路线 -> Bionic
- [x] D2 执行模型 -> 不需要 proot
- [x] D3 项目名 -> Amphora
- [x] D4 内容获取 -> `RemoteContentSource` + 远程 manifest pin（自建 Proton/imagefs；非 APK 捆绑）
- [x] D5 Wine 架构与版本 -> Proton 11 x86_64 + box64（否决 arm64ec）
- [x] D6 patchelf -> 不移植（死代码，Bionic 无需 ELF patch）
- [x] D7 MVP Proton 来源 -> 自建（fork proton-wine CI），termuxfs/prefixPack 复用上游 SHA 锁定
- [x] D8 AdrenotoolsManager 简化 -> 固定单 Turnip 驱动，精简到 ~30 行，解除反向依赖
- [x] D9 XServerDisplayActivity 拆解 -> 抽取 ~800-1,000 行核心启动，Steam/录屏/快捷方式砍，Compose 重写薄壳
- [x] 模块架构认可
- [x] MVP 范围认可
- [x] 复用策略认可

审核通过后开始 scaffold: `build-logic` convention plugins → `libs.versions.toml` → 模块空壳 → `:core:native` CMake 桩 → `:core:engine` 接口定义。
