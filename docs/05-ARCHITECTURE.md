# 05 - As-Built 架构

> 当前实现的架构真源。决议见 [`01-RFC.md`](01-RFC.md)；进度手账见 [`03-TRACKING.md`](03-TRACKING.md)；资产锁见 [`04-ASSET-MANIFEST.md`](04-ASSET-MANIFEST.md)。
> 最后更新: 2026-08-05 · 状态: **v0.1 端到端已跑通**（Wine desktop 画面 + 相对触控 + host/guest Vulkan 对齐）

---

## 1. 一句话

Amphora 是模块化的 Android Wine 模拟器：`:core:engine` 承载移植自 WinNative 的运行时内核；app/feature 只通过 `WineEngine` 等稳定接口启动会话；运行时二进制由 `RemoteContentSource` 按 `content_manifest.json` 的 SHA pin 在设备上下载安装，不进 APK。

---

## 2. 模块图

```
:app
├─ :feature:launcher      SAF 选 .exe + 分辨率 → 导航到会话
├─ :feature:settings      图形/组件/Box64/容器等设置（已有实质 UI）
└─ :core:engine           ★ 架构核心
   ├─ api  → :core:common, :core:content, :core:container
   └─ impl → :core:native, :core:rootfs
        │
        ├─ :core:content     ContentSource / manifest / SHA 校验
        ├─ :core:container   ContainerManager 契约（瘦模型）
        ├─ :core:rootfs      RootfsInstaller 契约
        ├─ :core:native      libwinlator.so + libamphora-exec.so（arm64-v8a）
        └─ :core:common      协程 dispatcher
```

`core/ui` 已删除（从未 include，无源码）。

`build-logic`（included build）提供 convention 插件：`amphora.android.{application,library,compose,hilt,native,feature}` + `amphora.content.staging`。

**依赖单向**：`feature/app → engine → {native, rootfs, content, container}`。native 永不向上依赖。

### DIP 落点（重要）

契约在低层模块，依赖 Winlator 内核的实现落在 `:core:engine`：

| 契约模块 | 接口 | 实现（engine） |
|---|---|---|
| `:core:rootfs` | `RootfsInstaller` | `ImageFsRootfsInstaller` |
| `:core:content` | `ContentSource` / `ContentAssetInstaller` | `RemoteContentSource` + `WinlatorContentAssetInstaller` |
| `:core:container` | `ContainerManager` | `WinlatorContainerManager` |
| `:core:engine` | `WineEngine` / `WineSessionPreparer` | `WineEngineImpl` / `XServerWineSessionPreparer` |

Hilt 绑定集中在 `EngineModule`；三个 sibling 接口已无 stub。

---

## 3. 启动数据流

```
MainActivity → AmphoraNavHost
  ├─ [默认] launcher → SAF .exe → filesDir/exe/ → game_session
  └─ [测试] Debug: Wine smoke test / DEBUG_AUTO_LAUNCH_WINE=true → Wine session

GameSessionViewModel
  → WineEngine.launch(LaunchSpec)   // MVP 容器 id = "1"
       1. RootfsInstaller.ensureInstalled()          // manifest-pinned imagefs.txz
       2. WinlatorContainerManager.getOrCreate()     // WINE/BOX64/DXVK/VKD3D .wcp + prefix
       3. XServerWineSessionPreparer                 // prefix 修复 / DXVK+VKD3D DLL / Turnip env
       4. XServer + GameSessionSurface               // 暴露给 UI
       5. XEnvironment + SysV / XServer / ALSA / Net
       6. stageExeIntoPrefix → C:\<exe>              // Z: 映 rootfs，宿主路径不可直传
       7. GuestProgramLauncher: box64 wine explorer /desktop=shell,WxH "C:\..."
       8. startEnvironmentComponents()

GameSessionScreen
  → AndroidView(XServerSurfaceView)  // TextureView + VulkanRenderer 线程
  → AndroidView(TouchpadView)        // WinNative 触控板手势 → X inject（无 WinHandler）
```

Guest 退出 → `XServerSessionHandle.markStopped()`；UI `stop` → 反向停环境并回收 Wine 子进程。
> **专项**：Exit 曾因 Main 上 `join` + `recvAncillaryMsg` 阻塞触发 ANR；已 IO 调度 + **先关 client FD 再 join** 根治（2s timeout 仅兜底）。见 [`03-TRACKING.md` §专项](03-TRACKING.md)。

---

## 3.1 内容身份：SHA 是唯一内容真相

| 层 | 字段 / 标记 | 职责 |
|---|---|---|
| **下载文件** | manifest `sha256` + 相邻 `<asset>.sha256` | 校验并缓存精确字节 |
| **安装目录** | `.amphora-source.sha256` | WCP、ARCHIVE、rootfs、Turnip 是否就是当前内容 |
| **派生副本** | `.amphora-applied/<asset>.sha256` 或 `AppliedMarks` 中含 SHA 的 fingerprint | 判断解压、复制、Prefix 应用是否需要重做 |
| **兼容名称** | `version` / `Type-verName-verCode` | ContentsManager 查找、路径和 UI 展示；不负责判断内容是否更新 |
| **容器选择** | `.container` 的 `wineVersion` / `box64Version` / `dxwrapper` | 记录用户/清单选择的兼容名称 |

同一个 `verName-verCode` 的 SHA 改变时也必须替换安装；不要求人为增加 `versionCode`。安装先保留旧目录，成功发布新目录并写 SHA 后再删除备份。
runtimeAsset 下载完成不代表更新完成：凡是复制或解压到 imagefs、Prefix、驱动目录的内容，都必须把来源 SHA 纳入 applied 状态。Proton SHA 改变会刷新 Prefix，Box64、WinComponents、wrapper/layers、DirectDraw 和 Turnip 同理。
`reconcileToPin` 只负责删除其他兼容版本的 sibling；`ContentPinResolver` 仍是名称解析入口。

---

## 3.2 容器配置怎么应用

一种方式：

1. **想要什么**：只在容器（唯一真相）。设置页 / 清单只负责改写容器。
2. **装过什么**：`AppliedMarks`（`applied*`）。
3. 想要的 ≠ 装过的 → 去做 → 成功后更新标记。
4. 前缀重建 → `clearOwnedByPrefix`，标记全清，下次会重做。

声音、服务、DLL、组件、盘符、输入、Box64/FEX 全部同一套。  
`dxwrapper` 只认分号格式。图形启动只读容器（`getOrCreate` 已从设置写入）。

---

## 4. 渲染与输入

| 层 | 组件 | 说明 |
|---|---|---|
| UI | `GameSessionScreen` / `TouchpadView` | 触控板：相对位移 + 单击左键 / 双指右键与滚轮 / 长按右键；触屏绝对模式；外接鼠标与手写笔 |
| Surface | `XServerSurfaceView` | `TextureView`（Compose `AndroidView` 下 SurfaceView 子窗口不可靠） |
| Java 渲染 | `VulkanRenderer` | 加载 `winlator`，direct scene buffer |
| Native | `vk_renderer.c` + adrenotools | swapchain / AHB 导入 / Turnip 或系统 `libvulkan.so` |
| X 协议 | `XServer` + DRI3 / Present / MIT-SHM | Mesa Android WSI → AHardwareBuffer；失败回退 SHM |
| Guest 图形 | Wrapper ICD + DXVK + VKD3D；OpenGL→EGL/Zink；32-bit DirectDraw→DxWrapper Dd7to9 或 cnc-ddraw→D3D9/DXVK；x86_64 DirectDraw→Proton builtin ddraw→WineD3D/Zink | 默认 wrapper 包装系统 Adreno，host 直接用同一系统 Vulkan；显式 Turnip 才由 host/guest 共用 adrenotools driver |

已知裁剪：无 OSK/字符注入；无 WinHandler 相对鼠标 UDP（`relativeMouseMovement` 固定 false）；音频 `setVolume` 未接真实 `AudioTrack`；Present idle 尚未按 GPU release fence 精确门控；Shortcut / desktop `.lnk` 升级 / EffectComposer 后处理已从内核路径拆除（Vulkan scene buffer 仍保留 effect 槽位布局，count=0）。

---

## 5. 内容与资产

- **真源**：`amphora-dev/content_manifest` 的 `content_manifest.json`，运行时按 `amphora.contentManifest.url`（`gradle.properties`）拉取；仓库内不留副本。校验由该仓的 `validate_manifest.py` 在 push 时执行
- **组件**（`ContentComponent`）：Wine Proton / Box64 / DXVK / VKD3D / ROOTFS（后者由 `RootfsInstaller` 独占）。Mesa vulkan wrapper 不是 component，它是 `runtimeAssets[]` 条目，由 `RuntimeAssetProvisioner` 装进 `filesDir/runtime-assets/`；ALSA aserver 随 imagefs
- **安装路径**：
  - `WCP` → `ContentsManager.extraContentFile` + `finishInstallContent` → `filesDir/contents/...`
  - `ARCHIVE` → `TarCompressorUtils` → `filesDir/amphora-content/<component>/<version>/`
- **构建 staging**：`./gradlew :app:stageBundledContent`（**不**挂 preBuild；Proton ~160MB 避免每次 debug APK 膨胀）
  - 每次重新读取同一份远程 manifest，遍历非 ROOTFS `components` 与全部 `runtimeAssets`；`-Pamphora.contentManifest.file=<path>` 可离线
  - 优先使用相邻 `WinNative` checkout 中的同路径文件；缺失时用条目的 `remoteUrl` 下载（WCP 可回落 `wcpCatalogUrl`）
  - 本地文件、下载缓存和最终生成文件都同时校验 `size` 与 SHA-256，任一不匹配即失败
  - 先完整写临时目录，成功后精确替换 `app/build/generated/assets/bundledContent/`，不会污染 `app/src/main/assets/`；该目录已接入 main Android assets source set

无资产时 `assembleDebug` 仍绿；端到端运行/instrumented 测试需先 staging。

---

## 6. Native

| 产物 | 内容 |
|---|---|
| `libwinlator.so` | X/Vulkan/AHB/压缩解压/socket/shmem/进程回收；adrenotools 静态链入；zstd+xz FetchContent |
| `libamphora-exec.so` | `LD_PRELOAD` 拦截 Box64/Wine 后续 `exec*`，把 app-private AArch64 ELF 改由 `/system/bin/linker64` 装载 |
| ABI | **仅 arm64-v8a**；minSdk 30；NDK r28 |

`libfakeinput.so` 不再构建（MVP 输入走 X inject）；源码已从树内移除，手柄路径回归时从 WinNative 再引入。

JNI 绑定类与 `com.winlator.cmod.runtime.*` 内核均在 `:core:engine`（包名保留，C 零改）。远程下载 JNI（`nativeDownloadFile` 等）保持 stub——下载在 Kotlin 侧由 `VerifiedAssetDownloader` 做（可续传 + SHA 校验），native 只留符号避免 `UnsatisfiedLinkError`。

---

## 7. 关键构建约束

| 项 | 值 / 原因 |
|---|---|
| compileSdk / minSdk | 37 / 30（对齐 Box64、Vulkan wrapper 与 wineserver 的 `LIBC_R` 依赖） |
| **targetSdk** | **36**：Java 首启和 native 后续 `exec*` 都通过 `/system/bin/linker64`，不直接 `execve(app_data_file)` |
| AGP / Gradle / Kotlin / KSP | 9.2.1 / 9.4.1 / 2.3.21 / 2.3.9 |
| Hilt / Compose BOM | 2.59.2 / 2026.06.01 |
| AGP 9 built-in Kotlin | **禁止**再 apply `org.jetbrains.kotlin.android` |
| 包名 | `app.amphora`；`:core:native` namespace = `app.amphora.core.nativelib`（`native` 是关键字） |

---

## 8. 与文档的关系

| 文档 | 角色 |
|---|---|
| `00-RESEARCH.md` | WinNative 拆解依据（历史） |
| `01-RFC.md` | 立项决议 D1–D9（不变真源） |
| `02-SCAFFOLD.md` | scaffold 时 as-built 栈与踩坑（历史；栈版本仍有效） |
| `03-TRACKING.md` | agent handoff checklist（living） |
| `04-ASSET-MANIFEST.md` | 资产 SHA 锁 |
| **`05-ARCHITECTURE.md`** | **当前实现架构（本文）** |

---

## 9. 当前缺口（v0.2+ 候选）

- `:feature:settings` 续增强；键盘/手柄；音频音量接线
- Present/DRI3 完善；多容器/prefix
- 部分 runtime 资产仍 pin 自 WinNative raw（wincomponents / ddrawrapper / meta）；`container_pattern_common` / `layers` 已从默认路径拆除；共享 `fonts.tzst` 提供真实 Microsoft YaHei、SimHei、PMingLiU、Tahoma、Microsoft Sans Serif，并保留 Source Han CN+JP 处理未打包字体；每容器通过 `Fonts/` symlink + FontLink / `FontSubstitutes` / Wine `Fonts\Replacements` 注册。Wine 会直接扫描 `C:\windows\Fonts`，不再向 imagefs `/usr/share/fonts` 重复建链或强制运行 `fc-cache`
- Exit 真机连点 / FD 泄漏回归
