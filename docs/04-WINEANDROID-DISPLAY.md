# 04 · WineAndroid 宿主窗口与渲染（架构与显示真源）

> **唯一显示真源**：本文档是 Amphora 宿主窗口、Surface 生命周期、布局及输入的唯一综合真源。  
> 进度指针见 [`docs/02-TRACKING.md`](02-TRACKING.md) 末节。

Amphora 的 `:session` 宿主采用 Kotlin 实现的 `WineAndroidDesktop` / `WineAndroidHostBridge` 对齐上游 `dlls/wineandroid.drv/WineActivity.java` 的窗口树与 Surface 生命周期。我们**彻底弃用**了 Winlator 式的 Java XServer / X11 架构，直接利用 Android 原生 `SurfaceView` 与 Binder 通信实现高效的 Windows 窗口呈现。

---

## 1. 架构总览与宿主原则

- **纯 Kotlin 宿主**：宿主窗口管理由 Kotlin 编写，不移植庞杂的 `WineActivity.java`，也不对齐 Winlator 的 Java XServer / TextureView 合成器。
- **IPC 桥梁**：Wine 运行在独立的 `:session` 进程中（通过 Box64 运行 x86_64 Wine）。Guest 侧的 `wineandroid.drv` 驱动通过 SEQPACKET `\0\Device\WineAndroid` 与宿主通信（`a17810b`），不再依赖传统 JNI 启动。
- **双轨渲染分工**：
  1. **2D 桌面与普通应用（GDI）**：走 `wineandroid.drv` 原生窗口，通过 `ANativeWindow` 将 CPU buffer 绘制到 Android SurfaceView（`api=NATIVE_WINDOW_API_CPU`）。
  2. **3D 游戏（Vulkan / DXVK）**：走 Android Hardware Buffer (AHB) 零拷贝直接送显（见 [`docs/05-AHB-IMPORT-PRESENT.md`](05-AHB-IMPORT-PRESENT.md)）。

---

## 2. 窗口树与嵌套布局

Amphora 借用了上游 WineActivity 的窗口树管理模型（提交 `8a494cc`）：

1. **根容器**：`WineAndroidDesktop`（FrameLayout），负责外层 letterbox 居中。
2. **内容宿主（contentHost）**：内层 FrameLayout，像素尺寸为 `guestW×guestH × hostScale`，偏移 `(offsetX, offsetY)`，等比铺满宿主可视区域（scale-to-fill）。
3. **每 HWND 独立 WindowGroup**：每个 HWND 对应一个 `WindowGroup`（FrameLayout）：
   - 内部包含一个 `SurfaceView`（`match_parent` 铺满该组）；
   - 该 HWND 的子 HWND 嵌套在其 WindowGroup 内部；
   - 最终由系统的 **SurfaceFlinger** 统一负责这些 Surface 的硬件合成。
4. **坐标定位必须使用 visible_rect**：
   - Win32 API 传出的 `visible_*` 坐标是相对**父窗口客户区**的坐标；
   - 例如 Start 按钮 `(0, 0)-(126, 46)` 是相对任务栏（taskbar）的，绝不能当成桌面绝对坐标贴到 contentHost 原点。
5. **挂载与 Reparent 规则**：
   - 当 `parentHwnd == 0`、父窗口是桌面 HWND、或父窗口组尚未就绪时，直接挂载到 `contentHost`；
   - 否则挂载到对应的父 `WindowGroup` 中；
   - 父子关系变化时触发 `reparent`。
6. **防空视图保护**：
   - 布局边长与 `setFixedSize` 的 guest 边长下限均为 **2**（`MIN_GUEST_PX = 2`），防止 0×0 或 1×1 导致底层 ANW/GDI 卡死。

---

## 3. Surface 生命周期与尺寸同步

### 3.1 尺寸变化重新绑定（SURFACE_CHANGED）
- 上游行为：`WineView.onSurfaceTextureSizeChanged` 会通知底层 `wine_surface_changed`。
- Amphora 实现：
  1. `WINDOW_POS` → `updateHwndRects` → 调用 `SurfaceHolder.setFixedSize(visibleW, visibleH)`（传入 guest 像素）；
  2. `SurfaceHolder.Callback.surfaceChanged` 触发时，调用 `nativeRegisterSurface` 向 native 发送 `SURFACE_CHANGED`（带新尺寸）；
  3. 若 `setFixedSize` 修改了缓冲区但系统回调未及时到达，宿主在 Surface 仍有效时也会主动触发一次注册。
- **历史 Bug 解决**：过去任务栏先以 1×1 注册，随后尺寸变为 1280×46 时只改了 fixedSize 却没重新 register，导致任务栏扭曲。当前必须在尺寸改变后重新 bind。

### 3.2 首次注册延迟（Defer Initial Register）
- 在窗口刚创建时，生命周期往往早于真实的 `WINDOW_POS`。
- **现行做法（`5515738`）**：推迟第一次 `nativeRegisterSurface`，直到窗口具有真实的宽高（`visible/window/client rect > 0`），而不是一开始用 2×2 占位注册；后续的尺寸调整依然按上述机制重新 bind。

### 3.3 颜色格式（RGBA + 宿主 Swizzle）
- **HA262 避坑**：对 Surface 路径**严禁**调用 `SET_BUFFERS_FORMAT(BGRA=5)`（真机曾直接整机闪退）；
- 保持标准的 **PF_RGBA_8888 (1)**，颜色差异由宿主软件做 **R/B 交换（swizzle）** 修正。
- 绝不能将上游 TextureView 假设的 BGRA 盲目照搬到 SurfaceView。

---

## 4. 叠窗、可见性与层级同步（z-order）

借用上游 `WS_VISIBLE` 与 `SWP_NOZORDER` 机制（提交 `4d3d976` 加固）：

1. **可见性状态**：
   - 窗口可见性依据 `(style & WS_VISIBLE) != 0`；
   - **隐藏窗口直接从父视图移除**（`removeView`），而不是仅仅设为 `GONE`；显现时重新 `addView`；
   - 默认新创建的 HWND `visible = false`（桌面除外）。
2. **z-order 层级重排**：
   - 当 `!(flags & SWP_NOZORDER)` 时，通过 `WineAndroidWindowStack` 纯逻辑维护每个父节点的 sibling 栈；
   - 重新计算前后遮挡关系后，自底向上（bottom-to-top）调用 `bringChildToFront` 同步 Android View 层级。

---

## 5. 宿主铺满、DPI 与分辨率策略

1. **铺满屏幕（scale-to-fill）**：
   - 正确做法：Buffer 保持 guest 尺寸（`setFixedSize(guest)`），外层 View 根据 `hostScale = min(屏宽/guestW, 屏高/guestH)` 等比放大居中（黑边 letterbox）；
   - **严禁**通过硬改 guest 内部的 `/desktop=WxH` 分辨率来强行铺满，否则会导致应用 UI 严重变形拉伸。
2. **DPI 策略**：
   - 虚拟桌面内部统一采用经典 Wine DPI **96**；
   - 严禁将 Android 原生 `densityDpi`（如 440）喂给 720p 虚拟桌面；
   - 经典 96 配合宿主 ~2.375 的放大倍率，在屏幕上的有效 DPI 约为 228，显示最为协调自然。
3. **分辨率档位**：
   - 支持预设：HD 1280×720（默认）、XGA 1024×768、HD+ 1600×900、FHD 1920×1080，由设置页持久化存储并在下次启动时生效。

---

## 6. 壳层输入（输入、光标与软键盘）

1. **事件通道**：
   - 硬件键盘、`adb input` 与软键盘 commit 统一通过与 MOTION 相同的桌面事件管道发送（`nativeSendKeyboardEvent` / `nativeSendUnicodeChar`）。
2. **宿主按键穿透（Pass-Through）**：
   - `WineAndroidKeyPassThrough`：系统功能键（`BACK`、`VOLUME`、`HOME`、`POWER`）在 native 无对应映射时直接穿透给 Android 系统处理（例如按 BACK 退出 Activity，按音量调系统声音），不向 Wine 注入无效键。
3. **光标管理（PointerIcon）**：
   - 对齐上游 `WineActivity.set_cursor`，根据 ID 设置系统指针图标、自定义 ARGB 图标或隐藏（API 24+ 原生 PointerIcon 支持）。
4. **捕获路由（setCapture）**：
   - 支持 Windows 窗口捕获，手势事件优先路由给 `captureTargetHwnd`。
5. **软键盘（IME）策略**：
   - **默认不自动弹出**：点击桌面或普通视图不会弹起键盘，避免遮挡；
   - **显式调出**：通过右上角「键盘」按钮、外部黑边长按或 `IME_SHOW` 调试钩子显式调起；
   - **文字与中文输入**：支持通过 `WineInputConnection` 提交文本；无法映射物理键码的 Unicode/CJK 字符经 `nativeSendUnicodeChar` 走 `KEYEVENTF_UNICODE` 注入。

---

## 7. 与 X11 架构的技术对照（X11 会话链已删除，仅保留对照）

| 对比维度 | Winlator X11 方案 | Amphora WineAndroid 方案（现行） |
|---|---|---|
| **视图架构** | 单一 `TextureView`（XServerSurfaceView） | 每个 HWND 一块独立 `SurfaceView`，包在嵌套 `WindowGroup` 中 |
| **窗口合成** | Java XServer 遍历窗口树，统一绘制进单张纹理 | 交给 Android 系统的 **SurfaceFlinger** 进行原生硬件多层合成 |
| **尺寸与注册** | 换尺寸只需换 Drawable，无每窗注册概念 | 必须在尺寸稳定后调用 `nativeRegisterSurface` 重新绑定 |
| **颜色与通道** | 私有 BGRA AHB + Vulkan 采样 | 保持标准 RGBA，宿主做 R/B 交换，严禁推 BGRA=5 |
| **系统开销** | 单一视图开销小，但有 XServer 软件中转损耗 | 窗口多时消耗 BufferQueue / SurfaceFlinger 层数，但省去 X 中转，3D 呈现性能更好 |

**多 SurfaceView 评估**：
对于 Windows 桌面环境（explorer + 少量顶层窗口），SurfaceFlinger 完全能够高效承载。未来若有打磨需求，可考虑“仅顶层建 Surface”或局部切 TextureView，但当前方案已在真机上验证稳定。

### 7.1 功能对齐表（X11 → wineandroid）

X11 会话链删于 `64fc57c`，删前最后状态是 tag **`x11-reference`**（= `4e39be3`），取参考用 `git show x11-reference:<路径>`。下表是删除时 wineandroid 缺的用户可见功能与运行时组件；补一项改一行，全部 ✅ 前不要删 `core/engine/src/main/java` 里对应的 Winlator 组件。

| 功能 | X11 参考（`x11-reference`） | wineandroid 现状 | 备注 |
|---|---|---|---|
| 音频服务（Pulse / ALSA） | `core/engine/…/WineEngineImpl.kt` `buildEnvironment` / `buildEnvVars`；`XServerSinks.kt` `XServerAudioSink` | ✅ `WineAndroidLauncher` 按容器驱动起 `PulseAudioComponent` / `ALSAServerComponent` 并设 `PULSE_SERVER` / `ANDROID_ALSA_SERVER`；GPLC 在调用方带 `PULSE_SERVER` 时把 APK nativeLibraryDir 加进 `LD_LIBRARY_PATH`（否则 Box64 找不到 `libpulse.so`，mmdevapi `c0000135`） | 6T 实放 30 s 440 Hz（waveOut）：Pulse 下 pactl 有 `protocol-native` sink-input、`AAudioSink RUNNING`；ALSA 下 `ALSAServerComponent` 起、`AS0` 在、无 pulse 进程，宿主 AudioTrack 送出 1440480 帧（= 30 s × 48 kHz）。两轮 wine `err:` 音频相关 0、无 FATAL |
| SysV 共享内存 / 网络信息组件 | 同上 `buildEnvironment`（`SysVSharedMemoryComponent`、`NetworkInfoUpdateComponent`） | ❌ 未启动 | SysVShm 原本服务 XServer，wineandroid 是否需要待查；NetworkInfo 影响 guest 网络状态 |
| 音量 / 静音 | `gamesession/GameSessionViewModel.kt`、`XServerSinks.kt` | ❌ 无 UI、无 sink | 依赖音频服务 |
| 触控模式：Trackpad / Direct / RTS | `gamesession/input/TouchpadView.kt`、`RtsGestureController.kt`、`TouchpadFingerTracker.kt` | ⚠️ 只有直接触摸（`WineAndroidDesktop`） | RTS：双指平移、长按右键等，见 `RtsGestureController` 顶部注释与测试 |
| 会话控制抽屉（隐藏控件 / 退出 Windows / 触控模式 / 帧率限制） | `gamesession/GameSessionRuntimeDrawer.kt`、`GameSessionOverlays.kt` `DrawerEdgeHandle` | ❌ | 仅有 IME chip（`WineAndroidImeUi`） |
| 退出确认 / 会话结束遮罩 | `GameSessionOverlays.kt` `ExitSessionConfirmationDialog`、`SessionEndingOverlay` | ⚠️ | 返回键弹 AlertDialog 确认（文案同旧版）已补；结束遮罩未做 |
| 暂停 / 恢复 | `GameSessionCoordinator.kt`、`PendingSessionActions` | ⚠️ 只有 Activity `onPause`/`onResume` | |
| 帧率限制 | `GameSessionRuntimeDrawer.kt` `sessionFrameLimitHint`、`FPS_LIMITS` | ❌ | |
| 性能 HUD（FPS / 帧时间 / CPU 核频率 / GPU / 温度 / 电池 / guest 进程） | `gamesession/HostPerformanceMonitor.kt`、`HostPerformanceParser.kt`、`GameSessionPerformanceHud.kt` | ✅（FPS 缺） | 已搬到 `WineAndroidSessionActivity`（ComposeView 叠层，设置开关或 `app.amphora.debug.PERF_HUD`）；帧率 / 帧时间依赖 X11 Present 回调，wineandroid 尚无帧统计，显示 `—` |

X11 专有管道（`XServerSessionHandle`、`XServerInputSink`、`GameSessionSurface`、`StubInputSink`、`DisplayBackend`）不需要对齐。

---

## 8. 排查指南与已知问题避坑

| 异常现象 | 核心根因 | 解决方案与避坑守则 |
|---|---|---|
| **黑屏 / nodrv / ENODEV (-11)** | 缺少 `ANativeWindow` 或桌面 HWND 未注册 Surface | 桌面 HWND 必须与普通窗口一样走 `attachWindow` + `nativeRegisterSurface`；GDI 必须指定 `api=NATIVE_WINDOW_API_CPU(2)`。 |
| **底栏扭曲 / taskbar 保持 1×1** | 初次注册为 1×1，后尺寸改变但未重新绑定 | 尺寸改变（`surfaceChanged`）后必须再次调用 `nativeRegisterSurface` 发送 `SURFACE_CHANGED`。 |
| **整机闪退 / 蓝屏** | 对 Surface 设置了错误的像素格式 | 严禁调用 `SET_BUFFERS_FORMAT(BGRA=5)`，保持 RGBA + 宿主 Swizzle。 |
| **Start 菜单跑到左上角原点** | 错误地使用了桌面绝对坐标布局 | 必须使用 `visible_rect`（相对父客户区坐标）定位，子窗口嵌套在父窗口内。 |
| **换包后未生效** | 使用了已被废弃的 `.local-override` 文件 | 改用 `filesDir/content/dev_pins.json`（见 [`docs/07-DEV-PIN-OVERLAY.md`](07-DEV-PIN-OVERLAY.md)），且必须写全 identity 字段。 |

---

## 9. 非目标与硬约束

1. **GDI 壳层出画不走 CreateSwapchain，禁止引入私有 `host.sock`**；（注意：游戏 Vulkan 的 AHB import CreateSwapchain 见 [`docs/05-AHB-IMPORT-PRESENT.md`](05-AHB-IMPORT-PRESENT.md)，是现行不可倒退路径）。
2. **严禁删除底部 `statusView` 调试条**（此为宿主必要观测点）。
3. **不要为了“像上游”而盲目照搬 `WineActivity.java`、切 TextureView 或抄 BGRA**。
4. **禁止靠修改 guest `/desktop=` 分辨率来铺满屏幕**（仅通过宿主 scale-to-fill 放大）。
