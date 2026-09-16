# wineandroid 宿主显示（WineActivity 对齐）

Amphora 的 `:session` 宿主用 Kotlin `WineAndroidDesktop` / `WineAndroidHostBridge`
对齐上游 `dlls/wineandroid.drv/WineActivity.java` 的窗口树与 Surface 生命周期，
**不是** Winlator X11，也不是把每个 HWND 做成系统 freeform Activity。
可借上游细则：[`17-WINEANDROID-UPSTREAM-BORROW.md`](17-WINEANDROID-UPSTREAM-BORROW.md)。
与 X11 单 TextureView 对照、多 Surface 成本、尺寸延迟、已知问题：
[`18-WINEANDROID-VS-X11-SURFACES.md`](18-WINEANDROID-VS-X11-SURFACES.md)。

## 布局：嵌套 + visible_rect

1. **根**：`WineAndroidDesktop`（FrameLayout）做 letterbox。
2. **contentHost**：内层 FrameLayout，尺寸 = `guestW×guestH × hostScale`，偏移
   `(offsetX, offsetY)`，铺满宿主 Activity 可视区域（等比 scale-to-fill）。
3. **每个 HWND**：一个 `WindowGroup`（FrameLayout），内含：
   - GDI / OpenGL `SurfaceView`（`match_parent` 填满该组）
   - 子 HWND 的 `WindowGroup`（嵌套在父组里）
   - 各层由 **SurfaceFlinger** 合成（每层通常自有 BufferQueue；对照见 docs/18）
4. **定位用 visible_rect**（不是单独的 window_rect）。上游 win32u `window.c`
   给出的 visible_* 是相对**父客户区**的坐标；Start `(0,0)-(126,46)` 相对
   taskbar，不得当成桌面绝对坐标贴到 contentHost 原点。
5. **挂载规则**（同 `add_view_to_parent`）：
   - `parentHwnd == 0`、父是 desktop hwnd、或父组尚未创建 → 挂到 `contentHost`
   - 否则挂到父 `WindowGroup`
6. **空视图禁止**：布局与 `setFixedSize` 的 guest 边长下限为 **2**（对齐上游
   `WineWindowGroup.set_layout`）。

Desktop hwnd 的 visible/window 通常是整桌；其 WindowGroup 填满 `contentHost`。

## Surface 尺寸变化必须再 register

上游 `WineView.onSurfaceTextureSizeChanged` 会再次 `wine_surface_changed`。

Amphora 路径：

1. `WINDOW_POS` → `updateHwndRects` → `SurfaceHolder.setFixedSize(visibleW, visibleH)`（guest px）
2. `SurfaceHolder.Callback.surfaceChanged` → 再次 `onSurface` →
   `nativeRegisterSurface` → native 发 `SURFACE_CHANGED`（带新 w/h）
3. 若 `setFixedSize` 后 buffer 变了但 callback 未立刻到，宿主也会在 surface
   仍 valid 时主动再 `onSurface` 一次

**已修症状**：taskbar 先以 1×1 register，随后 pos 到 1280×46 只
`setFixedSize` 而 `surfaceChanged` 为空实现 → guest 卡在 1×1。

logcat 验收：`registerSurface` / native `registerSurface hwnd=… WxH` 在
`windowPosChanged` 之后应出现 **~1280×46**（taskbar），不是一直 1×1。

**已做**：推迟 **第一次** `nativeRegisterSurface`，等到真实 guest w/h > 0
（visible/window/client rect，不是单靠 MIN 2×2 占位）；之后的 resize 仍按上面再
bind。详见 docs/18 §5 / §9。布局仍用 min 2×2 占位，避免 ANW 0/1。

## Session status TextView

`WineAndroidSessionActivity` 保留底部调试 `statusView`（准备 / 启动文案）。
布局与 Surface 借上游项见 [`17-WINEANDROID-UPSTREAM-BORROW.md`](17-WINEANDROID-UPSTREAM-BORROW.md)；
**不要**把删 status 当成借上游任务。

## 方向锁定

`WineAndroidSessionActivity` 在 manifest 中与 X11 `SessionActivity` 一样设置
`android:screenOrientation="sensorLandscape"`，wineandroid 会话强制传感器横屏；
`configChanges` 仍保留 `orientation`。

**HA262 真机验证**（`a4cd0c0`）：portrait-locked 设备 →
`SCREEN_ORIENTATION_SENSOR_LANDSCAPE`；横屏 `ROTATION_90` 宿主 **3040×1904**，
`hostScale` **2.375**（与 `WineAndroidHostScale` 单测一致）。

## Immersive system bars

`WineAndroidSessionActivity` 与 `GameSessionScreen` 对齐：创建 / resume 时
`WindowInsetsControllerCompat` 设置
`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` 并隐藏 status/navigation bars；暂停 /
destroy 时恢复显示。状态栏、导航栏保持隐藏，只有从屏幕边缘滑动才临时出现。
不涉及 Present、TextureView、BGRA 或分屏。

## Capture / Cursor（壳层输入）

1. **`setCapture(hwnd)`**：Desktop 记住 capture HWND（0 = release）；触摸 /
   generic motion 经 `WineAndroidCaptureTarget.resolve` 在 capture≠0 时发往
   capture hwnd，否则仍走 hit-test 视图 hwnd。销毁 HWND 时若正被 capture 则清零。
   单测：`WineAndroidCaptureTargetTest`。
2. **`setCursor`**：API 24+ 对齐上游 `WineActivity.set_cursor` —
   (a) `id=0` / 空 bits → `PointerIcon.TYPE_NULL`（隐藏系统指针）；
   (b) 系统 id → `PointerIcon.getSystemIcon`；
   (c) 正尺寸 ARGB `bits` → `PointerIcon.create`。经 `onResolvePointerIcon` +
   View.`pointerIcon` 应用到 WindowGroup / SurfaceView。分类纯逻辑
   `WineAndroidCursorSpec` + 单测。

**Debug 验证钩子 `CAPTURE_HWND`**（debuggable）：
`--ei app.amphora.debug.CAPTURE_HWND N`（MainActivity 冷启；中途 →
`WineAndroidDebugImeRelayActivity`）强制宿主 `setCapture`，**不**依赖 guest
title-bar `IOCTL_SET_CAPTURE`。`0` = release；`-1` = desktop hwnd sentinel
（desktop 未就绪则 defer）。日志 `capture inject scheduled` + Desktop
`capture inject requested=… resolved=…` / `capture hwnd=…`。

**HA262 真机验证**（`82bf652`）：冷启 `-1` → deferred then `resolved=65568`；
swipe `motion … capture=65568`；relay `0` release；relay `-1` 再捕获。
可选 title-bar 真 `IOCTL_SET_CAPTURE` **人工目视确认**仍可选（非欠账）。

**推迟**

- 独立自定义光标 overlay View / Vulkan 合成光标层（SurfaceView 路径用
  PointerIcon 已够壳层可用性）。
- 改 Present / AHB / IMM32 / TextureView。

## DPI

- **已落地**（`d264af1`）：Wine LogPixels = 经典 **96** via
  `WineAndroidDpi.forVirtualDesktopWithHostScale()` → `updateDesktopMetrics`。
  宿主 `hostScale = min(屏宽/guestW, 屏高/guestH)` 等比 letterbox 铺满；
  **勿**再叠 Winlator ScreenInfo ≈254，**勿**把 Android `densityDpi`（如 440）
  喂进 720p 虚拟桌面。
- HA262 例：96 × ~2.375 ≈ 228 上屏等效，近 Winlator 广告的 254，无双计。

### guest 分辨率 / hostScale / 叠窗

`hostScale` 已按当前 Activity 尺寸实时算（非写死 2.375），纯函数在
`WineAndroidHostScale.compute`（`034b38e`）。

1. **多分辨率 letterbox**：unit tests cover HA262 3040×1904（scale=2.375）、
   竖屏 1080×2400、超宽、方屏；content 不越界、居中。
   **HA262 旋转真机**：`onSizeChanged` 竖屏 1904×3040 → scale=1.4875
   offset=(0,984)；横屏 3040×1904 → scale=2.375 offset=(0,97)。
   **第二台物理机 / 分屏：用户已停（2026-09-16）；默认下一项勿做。**
2. **禁止 densityDpi 入径（已锁）**：`WineAndroidDpiTest` + SessionActivity 日志
   标明 android densityDpi unused；缺参仍 classic 96。
3. **guest 分辨率档 + 设置页（已接线）**：`WineAndroidGuestResolution` 预设
   HD 1280×720（默认）/ XGA 1024×768 / HD+ 1600×900 / FHD 1920×1080 落在
   `core:engine`；短边启发式 `suggestForHostShortSide`（HA262 仍 720p）。
   **Settings → Common → Display → Resolution** 与 Launcher `Resolution` 枚举与该目录对齐；
   `RuntimeSettingsStore`（SharedPreferences `display_resolution`）存 `R{W}x{H}` /
   guest id，经 `preferenceName` / `fromPreference` 映射；未知旧值回落 DEFAULT。
   Desktop / Launcher 启动走已存 WxH → `SessionLaunch`（**下一会话**生效）。
   MainActivity 的 wineandroid/smoke debug 冷启在未传 WIDTH/HEIGHT extras 时也读取该偏好；
   显式 extras 仍覆盖偏好。
   Debug log：`guest resolution resolve source=pref|extras|mixed|default pref=… WxH=…`
   （`WineAndroidGuestResolution.describeDebugDimensionSource`）。
   **HA262 真机验证**（`26b5c98`）：run-as 写入 `display_resolution=R1024x768`，
   冷启无 WIDTH/HEIGHT → `source=pref` / session `1024x768`；带 WIDTH=1280 HEIGHT=720
   → `source=extras`。仅记录 HA262，不宣称其它设备。
4. **WS_VISIBLE / sibling z-order（加固）**：`WineAndroidWindowStack` + Desktop
   sibling 栈；隐窗 removeView；叠窗 `bringChildToFront` 同步。单测
   `WineAndroidWindowStackTest`。
   **HA262 stack 冒烟 PASS**（`4d3d976`）：session 1280×720；first register；
   `windowPosChanged` 带 `style=`；无 FATAL。
   **Debug 验证钩子**：`DUMP_ZORDER` / `ZORDER_TOP_HWND`（`d0bdcb7` helper log PASS；
   **非**视觉重叠眼验）。重叠 HWND z-order **人工目视确认**仍可选。
5. **非目标**：不另造第二套桌面模型；不改 Present/AHB / TextureView / BGRA / IMM32；
   **不做分屏 / 第二台 hostScale**（用户已停）。

相关：`WineAndroidGuestResolution.kt` / `WineAndroidGuestResolutionTest.kt` /
`WineAndroidHostScale.kt` / `WineAndroidHostScaleTest.kt` / `WineAndroidDpiTest.kt`。

## 相关代码

| 文件 | 职责 |
|------|------|
| `WineAndroidDesktop.kt` | contentHost、WindowGroup 嵌套、visible 布局、setFixedSize、surfaceChanged 再 register；GDI 组可焦点 + `sendKeyboardEvent`；IME `WineInputConnection` commit + host composing；软键盘不随 focus/触摸弹出（`imeWanted` 门控 `onCheckIsTextEditor`；显式 `showSoftKeyboard` / letterbox 长按 `toggleSoftKeyboard`）；`setCapture` / `setCursor`（PointerIcon） |
| `WineAndroidCaptureTarget.kt` | capture vs hit-test HWND 纯选择（单测） |
| `WineAndroidCursorSpec.kt` | setCursor 载荷 → System / Custom 分类（单测） |
| `WineAndroidKeyPassThrough.kt` | 故意不进 guest 的宿主键（BACK / VOLUME_* / …；单测） |
| `WineAndroidImeCommit.kt` | IME 提交文本 → `KeyCharacterMap.getEvents` → KEYBOARD_EVENT；未映射码点交给 `nativeSendUnicodeChar` |
| `WineAndroidImeUi.kt` | 纯 `ImeUiState` reducer + composing chip 可见性 + 软键盘 chip 文案/`toggleImeWanted`（单测） |
| `WineAndroidDebugImeInject.kt` | debug-only extras `IME_UNICODE_TEXT` + `IME_COMPOSING_TEXT` + `IME_SHOW`（`FLAG_DEBUGGABLE`）；清 composing 用 `--esn` |
| `WineAndroidDebugCaptureInject.kt` | debug-only `CAPTURE_HWND`（`--ei`；`0` release；`-1` desktop sentinel） |
| `WineAndroidDebugZOrderInject.kt` | debug-only `DUMP_ZORDER` / `ZORDER_TOP_HWND`（重叠眼验；单测） |
| `WineAndroidDebugImeRelayActivity`（`src/debug`） | 导出中继：adb 中途注入 → 同 UID 启动非导出 Session `onNewIntent` |
| `WineAndroidGuestResolution.kt`（`core:engine`） | guest 预设目录 + preference 映射；Settings/Launcher 对齐 |
| `WineAndroidHostScale.kt` | 纯 letterbox scale/offset 计算（单测覆盖多分辨率） |
| `WineAndroidHostBridge.kt` | createWindow / windowPosChanged(visible_*) / setParent→reparent |
| `WineAndroidWindow.kt` | window/client/visible rect、style、visible（默认 false） |
| `WineAndroidWindowStack.kt` | 纯 WS_VISIBLE / SWP_NOZORDER / sibling reorder + sync 顺序（单测） |
| `WineAndroidSessionActivity.kt` | session + 调试 statusView；host composing TextView chip；右上角显式「键盘」chip；`dispatchKeyEvent` → KEYBOARD_EVENT；debug IME unicode / composing / IME_SHOW extras |
| `wineandroid_host_ipc.c` | `nativeRegisterSurface` → `SURFACE_CHANGED`；`nativeSendMotionEvent` / `nativeSendKeyboardEvent` / `nativeSendUnicodeChar`（`KEYEVENTF_UNICODE`） |

## 键盘（EVENT_KEYBOARD）

硬件 / `adb input keyevent` / `adb input text`（KEYCODE 注入）与 soft IME **commit** 都走与 MOTION 同一条 desktop event pipe（`nativeSendKeyboardEvent` / `nativeSendUnicodeChar`）。

- **宿主键故意穿透（已落地）**：`WineAndroidKeyPassThrough` 文档化 native
  `keycode_to_vkey==0` 的宿主键（`KEYCODE_BACK` / `VOLUME_*` / `HOME` / `POWER`）。
  Session `dispatchKeyEvent` 在 native 返回 false 时 fall-through 给 Android（BACK
  可 finish Activity；音量归系统）。**不要**给这些键发明 guest vkey。日志
  `key … ok=false passThrough=intentional-host`（其它未映射为 `unmapped`）。
  单测：`WineAndroidKeyPassThroughTest`。
- **HA262 真机验证**（`02d04e1`）：先 tap Desktop，再 `keyevent`。A → `ok=true`；
  VOLUME/BACK → `passThrough=intentional-host`；BACK finish → MainActivity。
- **IME commit 已落地**：`WineAndroidDesktop` 实现 `onCreateInputConnection` → 复用 `WineInputConnection`；committed ASCII/Latin 经 `KeyCharacterMap`（`VIRTUAL_KEYBOARD`）映射为 KeyEvent 再 `sendKeyboardEvent`。删除 / EditorAction(ENTER) / `onSendKeyEvent` 同管。
- **软键盘策略（默认不自动弹出）**：触摸 DOWN 只设 `keyTargetHwnd` + `requestFocus`（硬件键），**不**调用 `showSoftKeyboard()`（`WineAndroidImeUi.shouldAutoShowSoftKeyboardOnTouch() == false`）。无可靠 guest「文本框获焦」信号前，避免桌面/chrome 每点都弹 IME。`onCheckIsTextEditor` 仅在显式 `showSoftKeyboard` 的 `imeWanted` 期间为 true，避免 focus 单独拉起 IME；Session `windowSoftInputMode=stateHidden|adjustNothing`。需要时显式 `showSoftKeyboard()`。`hideSoftKeyboard()` 在 session `onPause` / 窗口失焦时调用。
- **显式软键盘控件（已落地）**：Session 右上角 debug chip「键盘」/「收键盘」调用 `toggleSoftKeyboard()`。contentDescription 恒为 `wineandroid keyboard`（uiautomator 定位）。次要：长按 letterbox（contentHost 外黑边）同样切换。默认仍不随 tap/focus 弹出。
- **Debug 验证钩子 `IME_SHOW`**：`--ez app.amphora.debug.IME_SHOW true|false`（冷启或 Relay）显式 show/hide，**不**改 tap 自动弹出策略。冷启需 IMM serve-ready：`showSoftKeyboard` 在 `imeWanted` 期间按 `0/100/400/1000ms` 重试。
- **HA262 真机验证**（`c5ede16`）：冷启 `IME_SHOW true` → `mInputShown=true` / `show served attempt=0`；Desktop tap → `mInputShown=false`；chip show/hide PASS。
- **CJK/unicode commit 已落地**：`KeyCharacterMap` 无法映射的码点经 `nativeSendUnicodeChar` → `KEYEVENTF_UNICODE`。Guest 侧仍是 `NtUserSendHardwareInput`，**不做** IMM32/TSF。
- **Debug 验证钩子**：`IME_UNICODE_TEXT` / `IME_COMPOSING_TEXT`（冷启 MainActivity；中途 → `WineAndroidDebugImeRelayActivity`）。composing 仅更新 host chip，**不**走 commit/unicode pipe。清 chip 用 `--esn`。
- **HA262 真机验证**：unicode 自动冒烟 PASS（`702b165`）；cold composing chip PASS（`01cf904`）；mid-session composing relay PASS（`fe8f5a2`）。
- **软键盘入口**：仅 chip / letterbox 长按 / `IME_SHOW` / `showSoftKeyboard`；**无** tap/focus 自动弹出。
- **仍开（可选）**：真机 soft IME **人工目视确认** composing chip + CJK commit；第二台真机 / 分屏（**已停**）。

## 非目标

- 不引入 `WineActivity.java` / TextureView 照搬
- 不改 guest 分辨率来“铺屏”（铺屏只靠宿主 scale-to-fill）
- 不做 CreateSwapchain / 私有 host.sock
