# 05 - As-Built 架构

> 当前实现的架构真源。决议见 [`01-RFC.md`](01-RFC.md)；进度手账见 [`03-TRACKING.md`](03-TRACKING.md)；资产锁见 [`04-ASSET-MANIFEST.md`](04-ASSET-MANIFEST.md)。
> 最后更新: 2026-07-21 · 状态: **v0.1 端到端已跑通**（Wine desktop 画面 + 相对触控 + host/guest Vulkan 对齐）

---

## 1. 一句话

Amphora 是模块化的 Android Wine 模拟器：`:core:engine` 承载移植自 WinNative 的运行时内核；app/feature 只通过 `WineEngine` 等稳定接口启动会话；运行时二进制经 `BundledContentSource` + SHA 锁定资产进 APK。

---

## 2. 模块图

```
:app
├─ :feature:launcher      SAF 选 .exe + 分辨率 → 导航到会话
├─ :feature:settings      设置页骨架（无实质项）
└─ :core:engine           ★ 架构核心
   ├─ api  → :core:common, :core:content, :core:container
   └─ impl → :core:native, :core:rootfs
        │
        ├─ :core:content     ContentSource / manifest / SHA 校验
        ├─ :core:container   ContainerManager 契约（瘦模型）
        ├─ :core:rootfs      RootfsInstaller 契约
        ├─ :core:native      libwinlator.so（arm64-v8a；fakeinput 已停编）
        └─ :core:common      协程 / AppResult
```

`core/ui/` 仍在磁盘（`build.gradle` 保留便于再加），但 **未** `include(":core:ui")`。

`build-logic`（included build）提供 convention 插件：`amphora.android.{application,library,compose,hilt,native,feature}` + `amphora.content.staging`。

**依赖单向**：`feature/app → engine → {native, rootfs, content, container}`。native 永不向上依赖。

### DIP 落点（重要）

契约在低层模块，依赖 Winlator 内核的实现落在 `:core:engine`：

| 契约模块 | 接口 | 实现（engine） |
|---|---|---|
| `:core:rootfs` | `RootfsInstaller` | `ImageFsRootfsInstaller` |
| `:core:content` | `ContentSource` / `BundledAssetInstaller` | `BundledContentSource` + `WinlatorBundledAssetInstaller` |
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
       1. RootfsInstaller.ensureInstalled()          // imagefs.tzst
       2. WinlatorContainerManager.getOrCreate()     // WINE/BOX64/DXVK/VKD3D .wcp + prefix
       3. XServerWineSessionPreparer                 // prefix 修复 / DXVK+VKD3D DLL / Turnip env
       4. XServer + GameSessionSurface               // 暴露给 UI
       5. XEnvironment + SysV / XServer / ALSA / Net
       6. stageExeIntoPrefix → C:\<exe>              // Z: 映 rootfs，宿主路径不可直传
       7. GuestProgramLauncher: box64 wine explorer /desktop=shell,WxH "C:\..."
       8. startEnvironmentComponents()

GameSessionScreen
  → AndroidView(XServerSurfaceView)  // TextureView + VulkanRenderer 线程
  → TouchInputOverlay                // 相对触控板 + 短按左键
```

Guest 退出 → `XServerSessionHandle.markStopped()`；UI `stop` → 反向停环境并回收 Wine 子进程。
> **专项**：Exit 曾因 Main 线程 `XConnectorEpoll.join` 卡在 `recvAncillaryMsg` 触发 ANR；已 IO + 2s join 超时止血，根治见 [`03-TRACKING.md` §专项](03-TRACKING.md)。

---

## 4. 渲染与输入

| 层 | 组件 | 说明 |
|---|---|---|
| UI | `GameSessionScreen` / `TouchInputOverlay` | Compose；触控走 `injectPointerMoveDelta` + tap |
| Surface | `XServerSurfaceView` | `TextureView`（Compose `AndroidView` 下 SurfaceView 子窗口不可靠） |
| Java 渲染 | `VulkanRenderer` | 加载 `winlator`，direct scene buffer |
| Native | `vk_renderer.c` + adrenotools | swapchain / AHB 导入 / Turnip 或系统 `libvulkan.so` |
| X 协议 | `XServer` + DRI3 / Present / MIT-SHM | Mesa Android WSI → AHardwareBuffer；失败回退 SHM |
| Guest 图形 | Turnip wrapper ICD + DXVK 3.0.2 gplasync + VKD3D 3.0.1 | host renderer 与 guest 共用 adrenotools-wrapped driver |

已知裁剪：无 OSK/字符注入；音频 `setVolume` 未接真实 `AudioTrack`；Present idle 尚未按 GPU release fence 精确门控；Shortcut / desktop `.lnk` 升级 / EffectComposer 后处理已从内核路径拆除（Vulkan scene buffer 仍保留 effect 槽位布局，count=0）。

---

## 5. 内容与资产

- **真源**：`core/content/src/main/assets/content_manifest.json`（派生自 `04-ASSET-MANIFEST.md`）
- **组件**：Wine Proton / Box64 / Turnip wrapper / DXVK / VKD3D（ROOTFS 由 `RootfsInstaller` 独占；ALSA aserver 随 imagefs；pulseaudio.tzst 未入 manifest）
- **安装路径**：
  - `WCP` → `ContentsManager.extraContentFile` + `finishInstallContent` → `filesDir/contents/...`
  - `ARCHIVE` → `TarCompressorUtils` → `filesDir/amphora-content/<component>/<version>/`
- **构建 staging**：`./gradlew :app:stageBundledContent`（**不**挂 preBuild；Proton ~160MB 避免每次 debug APK 膨胀）
  - ARCHIVE / imagefs：从相邻 `WinNative` checkout 拷贝并校验 SHA
  - WCP：从 `nicholasx417/WinNative-Components` releases 下载到 `build/content-cache/`

无资产时 `assembleDebug` 仍绿；端到端运行/instrumented 测试需先 staging。

---

## 6. Native

| 产物 | 内容 |
|---|---|
| `libwinlator.so` | X/Vulkan/AHB/压缩解压/socket/shmem/进程回收；adrenotools 静态链入；zstd+xz FetchContent |
| ABI | **仅 arm64-v8a**；minSdk 26；NDK r28 |

`libfakeinput.so` 不再构建（MVP 输入走 X inject）；源码已从树内移除，手柄路径回归时从 WinNative 再引入。

JNI 绑定类与 `com.winlator.cmod.runtime.*` 内核均在 `:core:engine`（包名保留，C 零改）。远程下载 JNI（`nativeDownloadFile` 等）为 stub——MVP 只走 APK 内联资产。

---

## 7. 关键构建约束

| 项 | 值 / 原因 |
|---|---|
| compileSdk / minSdk | 37 / 26 |
| **targetSdk** | **28**（非 36）：`filesDir` 执行 box64/Wine；targetSdk≥29 会触发 SELinux `execute_no_trans` |
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

- `:feature:settings` 实质项；键盘/手柄；音频音量接线
- Present/DRI3 完善；`RemoteContentSource`；多容器/prefix
- Proton 11 自建（见 `RESEARCH-proton-wine-selfbuild.md`）
- targetSdk 上探（须先把可执行文件迁到可 exec 位置）
