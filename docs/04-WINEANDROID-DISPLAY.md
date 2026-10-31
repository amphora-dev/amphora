# 04 · WineAndroid 宿主窗口与渲染（架构与显示真源）

> **唯一显示真源**：本文档是 Amphora 宿主窗口、Surface 生命周期、布局及输入的唯一综合真源。  
> 进度真源见 [`docs/02-TRACKING.md`](02-TRACKING.md)。

Amphora 的 `:session` 宿主采用 Kotlin 实现的 `WineAndroidDesktop` / `WineAndroidHostBridge` 对齐上游 `dlls/wineandroid.drv/WineActivity.java` 的窗口树与 Surface 生命周期。我们**彻底弃用**了 Winlator 式的 Java XServer / X11 架构，每个 HWND 的 buffer 是一个 `SurfaceControl` 层，由 SurfaceFlinger 直接合成。

---

## 1. 架构总览与宿主原则

- **纯 Kotlin 宿主**：宿主窗口管理由 Kotlin 编写，不移植庞杂的 `WineActivity.java`，也不对齐 Winlator 的 Java XServer / TextureView 合成器。
- **SurfaceFlinger 每 HWND 一层**：每个 HWND 的 buffer 是一个 `SurfaceControl` 层，由 SurfaceFlinger 硬件合成；层序由宿主显式设置（不依赖 SurfaceView 子层或创建顺序）。
- **IPC 桥梁**：Wine 运行在独立的 `:session` 进程中（通过 Box64 运行 x86_64 Wine）。Guest 侧的 `wineandroid.drv` 驱动通过 SEQPACKET `\0\Device\WineAndroid` 与宿主通信（`a17810b`），不再依赖传统 JNI 启动。
- **双轨渲染分工**：
  1. **2D 桌面与普通应用（GDI）**：走 `wineandroid.drv` 原生窗口，通过 `ANativeWindow` 将 CPU buffer 绘制到该 HWND 的 SurfaceControl 层（`api=NATIVE_WINDOW_API_CPU`）。
  2. **3D 游戏（Vulkan / DXVK）**：走 Android Hardware Buffer (AHB) 零拷贝直接送显（见 [`docs/05-AHB-IMPORT-PRESENT.md`](05-AHB-IMPORT-PRESENT.md)）。

---

## 2. 窗口树与嵌套布局

Amphora 借用了上游 WineActivity 的窗口树管理模型（提交 `8a494cc`）：

1. **根容器**：`WineAndroidDesktop`（FrameLayout），负责外层 letterbox 居中。
2. **内容宿主（contentHost）**：内层 FrameLayout，像素尺寸为 `guestW×guestH × hostScale`，偏移 `(offsetX, offsetY)`，等比铺满宿主可视区域（scale-to-fill）。
3. **每 HWND 独立 WindowGroup + SurfaceControl 层**：每个 HWND 对应一个 `WindowGroup`（FrameLayout，本身不绘制，只负责布局、命中测试和嵌套子窗口）：
   - buffer 是一个 `SurfaceControl` 层（`amphora-gdi-<hwnd>` / `amphora-client-<hwnd>`），`Surface(SurfaceControl)` 交给 native 注册；
   - 所有层都挂在同一个容器 `SurfaceView`（`layerHost`，contentHost 的第一个子 View）的 SurfaceControl 下；
   - 该 HWND 的子 HWND 嵌套在其 WindowGroup 内部；
   - `syncLayers()`（在布局完成后）按 View 树的绘制顺序先序遍历，给每层设 z（父在子下、兄弟按 Win32 栈）、位置缩放（`setGeometry`）和祖先裁剪，一次 Transaction 提交（`WineAndroidLayerGeometry`）。
   - 不再用每 HWND 一个 SurfaceView：SurfaceFlinger 对同一窗口里的多个 SurfaceView 按子层（media overlay 高于普通）再按创建顺序排，跟 View 顺序无关；原来给 Vulkan 视图设的 `setZOrderMediaOverlay` 让 3D 画面盖住所有 GDI 弹窗。
4. **坐标定位必须使用 visible_rect**：
   - Win32 API 传出的 `visible_*` 坐标是相对**父窗口客户区**的坐标；
   - 例如 Start 按钮 `(0, 0)-(126, 46)` 是相对任务栏（taskbar）的，绝不能当成桌面绝对坐标贴到 contentHost 原点。
5. **挂载与 Reparent 规则**：
   - 当 `parentHwnd == 0`、父窗口是桌面 HWND、或父窗口组尚未就绪时，直接挂载到 `contentHost`；
   - 否则挂载到对应的父 `WindowGroup` 中；
   - 父子关系变化时触发 `reparent`。
6. **防空视图保护**：
   - 布局边长与层 buffer 的 guest 边长下限均为 **2**（`MIN_GUEST_PX = 2`），防止 0×0 或 1×1 导致底层 ANW/GDI 卡死。

---

## 3. Surface 生命周期与尺寸同步

### 3.1 尺寸变化重新绑定（SURFACE_CHANGED）
- 上游行为：`WineView.onSurfaceTextureSizeChanged` 会通知底层 `wine_surface_changed`。
- Amphora 实现：
  1. `WINDOW_POS` → `updateHwndRects` → `Transaction.setBufferSize(layer, visibleW, visibleH)`（guest 像素）；
  2. 随后再调一次 `nativeRegisterSurface`。层的 `Surface` 不变，native 发现是同一个 ANativeWindow 时只发 `SURFACE_CHANGED`（新尺寸），不重连。
- **历史 Bug 解决**：过去任务栏先以 1×1 注册，随后尺寸变为 1280×46 时只改了 fixedSize 却没重新 register，导致任务栏扭曲。当前必须在尺寸改变后重新 bind。
- **尺寸变化后的 buffer 缓存**：宿主按 dequeue 出来的 buffer 指针给 Wine 分配 id，Wine 按 id 缓存 AHB，只在宿主说「新的」时才换。改尺寸后 BufferQueue 会按新尺寸重分配 buffer，新 buffer 可能落在同一地址；只比指针会让 Wine 继续往旧 AHB 里画、真正入队的新 buffer 是空的（透明）。`register_buffer` 现在同时比 handle 和宽高，不同就当新 buffer 发给 Wine（同一 id）。症状：AIO 的 FPS 浮条（每 500 ms 改一次文字和宽度）每隔一次更新消失半秒。

### 3.2 首次注册延迟（Defer Initial Register）
- 在窗口刚创建时，生命周期往往早于真实的 `WINDOW_POS`。
- **现行做法（`5515738`）**：推迟第一次 `nativeRegisterSurface`，直到窗口具有真实的宽高（`visible/window/client rect > 0`），而不是一开始用 2×2 占位注册；后续的尺寸调整依然按上述机制重新 bind。

### 3.3 颜色格式（RGBA + 宿主 Swizzle）
- **HA262 避坑**：对 Surface 路径**严禁**调用 `SET_BUFFERS_FORMAT(BGRA=5)`（真机曾直接整机闪退）；
- 保持标准的 **PF_RGBA_8888 (1)**，颜色差异由宿主软件做 **R/B 交换（swizzle）** 修正。
- 绝不能将上游 TextureView 假设的 BGRA 盲目照搬到我们的层上。
- 以上只管 GDI 层。client（Vulkan）层建成不透明（`SurfaceControl.Builder.setOpaque(true)`），buffer 格式由 AHB 交换链按交换链格式设（[`05`](05-AHB-IMPORT-PRESENT.md) §2.3）。

### 3.4 容器 SurfaceView 销毁（灭屏 / 切后台）
- `layerHost` 的 `surfaceDestroyed` 后，各 HWND 的层保留（`reparent` 回来，不重建）。
- **GDI 层**：照旧 `nativeUnregisterSurface`（Wine 那一侧 `parent=NULL`，dequeue 返回 `-EWOULDBLOCK`，这一帧不画），根回来后完整 `nativeRegisterSurface` 重挂，`SURFACE_CHANGED` 触发重画。
- **client（Vulkan）层**：不注销，改 `nativeParkSurface`。guest 的 Vulkan surface 在创建时就绑死了这个 HWND 的 buffer socket，注销会关掉它，guest 没有重连（下一次 acquire 拿到 -5，app 退出）。park 期间 ANativeWindow、buffer socket、交换链都保留；宿主 serve 线程让 DEQUEUE 一直等到恢复或会话关闭（日志 `serve DEQUEUE … parked` / `unparked after <ms>`），QUEUE 改成 `cancelBuffer`。根回来后同一个 Surface 走 `registerSurface … same surface` 分支解除 park。后台不跑 GPU，代价是游戏渲染线程卡在 acquire 里。

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
   - 正确做法：Buffer 保持 guest 尺寸（层 buffer = guest 像素），外层 View 根据 `hostScale = min(屏宽/guestW, 屏高/guestH)` 等比放大居中（黑边 letterbox）；
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

## 7. 功能缺口（对照 x11-reference）

X11 会话链删于 `5909b50`，删前最后状态是 tag **`x11-reference`**（= `4e39be3`），取参考用 `git show x11-reference:<路径>`。下表只列 wineandroid 仍缺（非 ✅）的用户可见功能与运行时组件；已对齐项（音频服务 Pulse/ALSA、性能 HUD、返回键退出确认等）的验收记录见 [`docs/02-TRACKING.md`](02-TRACKING.md) §3。

| 功能 | X11 参考（`x11-reference`） | wineandroid 现状 | 备注 |
|---|---|---|---|
| SysV 共享内存 / 网络信息组件 | `SysVSharedMemoryComponent`、`NetworkInfoUpdateComponent`（`buildEnvironment` 启动） | ❌ 组件已在 `c0d3a07` 删除 | 取回：`git show x11-reference:core/engine/src/main/java/com/winlator/cmod/runtime/display/environment/components/SysVSharedMemoryComponent.java` / `…/NetworkInfoUpdateComponent.java`；wineandroid 是否需要仍待查 |
| 音量 / 静音 | `GameSessionViewModel.kt`、`XServerSinks.kt` | ❌ 无 UI、无 sink | 音频服务（Pulse / ALSA）本身已通，缺的是音量/静音控制面 |
| 触控模式：Trackpad / Direct / RTS | `gamesession/input/TouchpadView.kt`、`RtsGestureController.kt`、`TouchpadFingerTracker.kt` | ⚠️ 只有直接触摸（`WineAndroidDesktop`） | RTS：双指平移、长按右键等，见 `x11-reference` 的 `RtsGestureController` 顶部注释与测试 |
| 会话控制抽屉（隐藏控件 / 退出 Windows / 触控模式 / 帧率限制） | `GameSessionRuntimeDrawer.kt`、`GameSessionOverlays.kt` `DrawerEdgeHandle` | ❌ | 仅有 IME chip（`WineAndroidImeUi`） |
| 退出确认 / 会话结束遮罩 | `GameSessionOverlays.kt` `ExitSessionConfirmationDialog`、`SessionEndingOverlay` | ⚠️ | 返回键弹 AlertDialog 确认（文案同旧版）已补；结束遮罩未做 |
| 暂停 / 恢复 | `GameSessionCoordinator.kt`、`PendingSessionActions` | ⚠️ 只有 Activity `onPause`/`onResume` | |
| 帧率限制 | `GameSessionRuntimeDrawer.kt` `sessionFrameLimitHint`、`FPS_LIMITS` | ⚠ | 设置里一个 **Frame rate**（pref `advanced_frame_rate_mode`：Sync to display（默认）/ Follow the game / Uncapped / 30–120 fps），取代原来的 Frame limit 和 Vulkan present mode。每次启动写 env `AMPHORA_FRAME_RATE`（AHB 交换链据此定 swap interval，见 [`05`](05-AHB-IMPORT-PRESENT.md) §2.3）和 `DXVK_FRAME_RATE`（上限值，否则 0；DXVK 与 VKD3D-Proton 的限帧器都读它），上限时 preparer 另写 `DXVK_CONFIG` 的 `maxFrameRate`；裸 Vulkan 的上限由 `ahb_sc_pace` 做。宿主刷新率对齐已接（`WineAndroidSessionActivity` → `RefreshRateUtils`，只有固定上限会选对应档位，其余选峰值，档位不超过用户的峰值刷新率设置；Y700 限 30 / 60 / 关 实测 30 / 60 / 144 Hz）。宿主 HUD FPS（`nativeRecentPresentTimes`，宿主 QUEUE 时间戳）和 AIO bench 数的是 present；swap interval 0（Uncapped、Follow the game 且游戏关 vsync）时会超过上屏帧数，上屏以 SF `totalFrames` 为准。会话内切换未做（随会话抽屉） |

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
