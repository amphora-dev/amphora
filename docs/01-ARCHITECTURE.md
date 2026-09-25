# 01 · As-Built 架构设计

> **架构真源**：本文档是 Amphora 现行工程实现的唯一架构真源。  
> 进度真源见 [`02-TRACKING.md`](02-TRACKING.md)；资产清单见 [`03-ASSET-MANIFEST.md`](03-ASSET-MANIFEST.md)；立项决议见 [`research/01-RFC.md`](research/01-RFC.md)。  
> **当前状态（2026-09-25）**：默认采用 **WineAndroid 宿主**出画（Android 原生 SurfaceView + 嵌套 WindowGroup）与 **Vulkan AHB 导入零拷贝**渲染；旧版 X11 仅保留作为对比与显式回退。

---

## 1. 核心定位

Amphora 是一款模块化、高可维护性的 Android 平台 Windows/Wine 模拟器：
- **内核封装**：`:core:engine` 承载核心运行时逻辑，上层应用与业务功能仅通过 `WineEngine` 等标准化接口与运行时交互；
- **原生宿主出画**：抛弃了传统模拟器繁重且损耗性能的内置 X11 服务，默认走 Android 原生 `SurfaceView` 窗口体系与 SurfaceFlinger 硬件多层合成；
- **图形零拷贝**：游戏 3D 渲染通过 `amphora_wsi` 桥接将 Android Hardware Buffer (AHB) 零拷贝直接注入系统 Vulkan Swapchain；
- **内容受控交付**：Wine、Box64、DXVK 等二进制运行时组件与驱动均通过 `RemoteContentSource` 按照 SHA-256 强校验在设备端按需下载安装。

---

## 2. 模块拓扑与依赖规则

```
:app
 ├─ :feature:launcher          SAF 选取 .exe + 快捷启动 + 启动参数配置
 ├─ :feature:settings          容器、图形驱动、组件版本与本地覆盖层设置
 └─ :core:engine               ★ 核心运行时（Winlator 运行时逻辑与驱动桥接）
     ├─ api  → :core:common, :core:content, :core:container
     └─ impl → :core:native, :core:rootfs
          │
          ├─ :core:content     ContentSource / 清单解析 / SHA-256 校验
          ├─ :core:container   ContainerManager 契约（容器与 Prefix 抽象）
          ├─ :core:rootfs      RootfsInstaller 契约（系统镜像解压与挂载）
          ├─ :core:native      libwinlator.so + libamphora-exec.so + libamphora_wsi.so
          ├─ :core:ui          设计系统 Tokens 与动效规范（DesignTokens、AmphoraMotion）
          └─ :core:common      协程调度器与基础工具
```

- **依赖单向流动**：`feature/app → engine → {native, rootfs, content, container, ui, common}`，底层的 native 与通用库绝不向上反向依赖；
- **依赖倒置 (DIP)**：标准化契约定义在底层接口模块中，依赖具体运行时的实现收拢在 `:core:engine`：

| 契约模块 | 抽象接口 | engine 中的具体实现 |
|---|---|---|
| `:core:rootfs` | `RootfsInstaller` | `ImageFsRootfsInstaller` |
| `:core:content` | `ContentSource` / `ContentAssetInstaller` | `RemoteContentSource` + `WinlatorContentAssetInstaller` |
| `:core:container` | `ContainerManager` | `WinlatorContainerManager` |
| `:core:engine` | `WineEngine` / `WineSessionPreparer` | `WineEngineImpl` / `WineAndroidSessionBootstrap` |

---

## 3. 运行会话启动数据流

从点击图标到 Windows 程序出画，整体启动链路如下：

```
UI 入口（AmphoraNavHost / DesktopActivity / MainActivity）
  │
  ▼
SessionLaunch.program / explorer(...)
  │
  ├─ [默认] displayBackend = WINEANDROID ──► 启动独立进程 :session 的 WineAndroidSessionActivity
  └─ [回退] displayBackend = X11         ──► 启动 SessionActivity (旧版 Java XServer)

WineAndroidSessionActivity 启动时序：
  1. WineAndroidSessionBootstrap.prepare(...)
     - 确保 Rootfs (imagefs.txz) 与容器 Prefix 已安装就绪；
     - 校验并安装当前 pin 钉选的 WCP 组件（Wine、Box64、DXVK 等）及 Turnip 驱动；
     - 初始化 SEQPACKET 通信通道（\0\Device\WineAndroid）。
  2. WineAndroidDesktop 视图树构建
     - 根容器 FrameLayout 负责黑边 letterbox 居中；
     - 内部 contentHost 按 hostScale 等比缩放铺满屏幕；
     - 构建宿主与 Windows HWND 对应的 WindowGroup 树。
  3. WineAndroidLauncher.launchGuest(...)
     - 配置环境变量（注入 AMPHORA_WINEANDROID=1，LD_PRELOAD 预加载 libamphora_wsi.so）；
     - 通过 /system/bin/linker64 libamphora-exec.so 启动 guest：
       box64 wine explorer /desktop=shell,WxH "C:\<exe>"
  4. 双轨画面呈现与交互打通：
     - 2D GDI 窗口：wineandroid.drv 通过 ANativeWindow 直接绘制到 SurfaceView；
     - 3D Vulkan 游戏：经 amphora_wsi 导入 AHardwareBuffer 实现零拷贝 Present 送显；
     - 交互输入：触控 MotionEvent、物理键盘、软键盘 IME 与光标 PointerIcon 实时分发。
```

---

## 4. 双轨出画架构与输入体系

项目实现了两套完全不同层级的呈现机制，现行真源是 WineAndroid 宿主，旧版 X11 仅保留为对比基准。

### 4.1 现行真源：WineAndroid 原生宿主（详见 [`04-WINEANDROID-DISPLAY.md`](04-WINEANDROID-DISPLAY.md)）

1. **2D 桌面与普通应用（GDI 路径）**：
   - 每个 Windows HWND 对应宿主的一块 `SurfaceView`，包在嵌套的 `WindowGroup` 中；
   - 子窗口坐标严格遵循 win32u 的 `visible_rect`（相对父客户区坐标）；
   - `wineandroid_host_ipc.c` 指定 `api=NATIVE_WINDOW_API_CPU(2)`，利用 CPU buffer 绘制，最终由 Android 系统的 **SurfaceFlinger** 统一硬件多层合成；
   - 保持标准的 **PF_RGBA_8888** 格式，颜色通过宿主软件 R/B 交换修正（严禁设 BGRA=5 导致闪退）。
2. **3D 游戏画面（Vulkan AHB 零拷贝，详见 [`05-AHB-IMPORT-PRESENT.md`](05-AHB-IMPORT-PRESENT.md)）**：
   - 宿主通过 `LD_PRELOAD` 注入 `libamphora_wsi.so`；
   - 游戏创建 Swapchain 时，通过 Unix Socket 桥接直接将 Android 系统的 `AHardwareBuffer` 导入为 Vulkan `VkImage`；
   - GPU 绘制直接写入共享内存，Present 流程直接送显，无任何 ImageReader 或 CPU 拷贝损耗（Present≥50 已验证通过）。
3. **输入与交互体系**：
   - **触控与光标**：手势直接分发为 `nativeSendMotionEvent`；光标采用 API 24+ 原生 `PointerIcon`（对齐 Windows 光标形态）；
   - **物理键盘与按键穿透**：常规按键经桌面管道注入；系统功能键（返回键 BACK、音量键 VOLUME）故意穿透回 Android 原生处理；
   - **软键盘与 IME**：默认点击不主动弹起键盘（避免遮挡）；通过悬浮按钮或长按外层黑边调起；支持 CJK 中文字符经 `KEYEVENTF_UNICODE` 注入。

### 4.2 对照与回退：Winlator X11 方案

- **架构特征**：单一 `TextureView`（XServerSurfaceView），由内置 Java XServer 遍历窗口树，统一绘制进单张纹理后由 VulkanRenderer 呈现；
- **回退方式**：在 `SessionLaunch` 中显式指定 `displayBackend = DisplayBackend.X11` 即可切入此旧路径，供调试与对比验证。

---

## 5. 内容与组件资产管理

- **唯一版本真源**：`amphora-dev/content_manifest` 仓库中的 `content_manifest.json`，运行时由 `RemoteContentSource` 动态拉取；
- **开发态本地覆盖（Overlay）**：开发调试时，可通过 `filesDir/content/dev_pins.json`（详见 [`07-DEV-PIN-OVERLAY.md`](07-DEV-PIN-OVERLAY.md)）临时覆盖特定组件（如本地自编的 Box64 或 Proton），无需修改线上清单；
- **容器与应用状态解耦**：
  - 容器内部维护 `AppliedMarks` 指纹（包含 SHA）；
  - 每次启动比对“目标清单 SHA”与“已应用 SHA”；不一致时才重新解压或重挂，避免重复 I/O。

---

## 6. 原生 C/C++ 模块职责 (`:core:native`)

| 原生动态库 | 架构与职责 |
|---|---|
| `libwinlator.so` | C 运行时基础库：提供底层 socket 通信、共享内存、进程树监管及 adrenotools 驱动装载 |
| `libamphora-exec.so` | `LD_PRELOAD` 拦截器：拦截 Box64 / Wine 子进程的 `exec*` 调用，强制将应用私有 ELF 路由给 `/system/bin/linker64` 装载（绕过 targetSdk 36 的 W^X/exec 限制） |
| `libamphora_wsi.so` | aarch64 WSI 桥接库：注入进程后启动服务端，提供 Vulkan AHB 导入与零拷贝 Swapchain 呈现调度 |
| `libamphora-android-shim.so` | 符号垫片库：为特定兼容环境导出包装符号 |

- **ABI 约束**：严格限定为 **arm64-v8a**；compileSdk 37，minSdk 30，targetSdk 36。

---

## 7. 关键设计守则与红线

1. **GDI 壳层严禁引入 CreateSwapchain 或私有 host.sock**：桌面与 2D 窗口必须走标准 `wineandroid` SurfaceView 挂载；
2. **游戏 3D 呈现必须保留 AHB Import CreateSwapchain**：绝不可退回软拷贝或已被清除的 HostVk 冗余路径；
3. **主分支与代码规范**：开发推前必须执行 `./gradlew spotlessCheck :app:testDebugUnitTest` 保证持续集成 (CI) 始终为绿灯。
