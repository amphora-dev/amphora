# wineandroid 宿主显示（WineActivity 对齐）

Amphora 的 `:session` 宿主用 Kotlin `WineAndroidDesktop` / `WineAndroidHostBridge`
对齐上游 `dlls/wineandroid.drv/WineActivity.java` 的窗口树与 Surface 生命周期，
**不是** Winlator X11，也不是把每个 HWND 做成系统 freeform Activity。
可借上游细则：[`17-WINEANDROID-UPSTREAM-BORROW.md`](17-WINEANDROID-UPSTREAM-BORROW.md)。
与 X11 单 TextureView 对照、多 Surface 成本、尺寸延迟、**一路踩坑**：
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

**症状（已修）**：taskbar 先以 1×1 register，随后 pos 到 1280×46 只
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

## DPI

- **已落地**（`d264af1`）：Wine LogPixels = 经典 **96** via
  `WineAndroidDpi.forVirtualDesktopWithHostScale()` → `updateDesktopMetrics`。
  宿主 `hostScale = min(屏宽/guestW, 屏高/guestH)` 等比 letterbox 铺满；
  **勿**再叠 Winlator ScreenInfo ≈254，**勿**把 Android `densityDpi`（如 440）
  喂进 720p 虚拟桌面。
- HA262 例：96 × ~2.375 ≈ 228 上屏等效，近 Winlator 广告的 254，无双计。

### TODO · 多设备 hostScale / DPI

`hostScale` 已按当前 Activity 尺寸实时算（非写死 2.375），纯函数在
`WineAndroidHostScale.compute`（`034b38e`）。

1. **多分辨率 letterbox**：unit tests cover HA262 3040×1904（scale=2.375）、
   竖屏 1080×2400、超宽、方屏；content 不越界、居中。
   **HA262 旋转真机（2026-09-16）**：`onSizeChanged` 竖屏 1904×3040 →
   scale=1.4875 offset=(0,984)；横屏 3040×1904 → scale=2.375 offset=(0,97)。
   与 `WineAndroidHostScale` 单测一致。ART：Mac
   `smoke-artifacts/ha262-hostscale-rotate-20260916-140241/`。
   **仍欠**：第二台物理机 / 分屏。
2. **禁止 densityDpi 入径（已锁）**：`WineAndroidDpiTest` + SessionActivity 日志
   标明 android densityDpi unused；缺参仍 classic 96。
3. **guest 分辨率档（目录已落地）**：`WineAndroidGuestResolution` 预设
   1280×720（默认）/ 1024×768 / 1600×900 / 1920×1080；短边启发式
   `suggestForHostShortSide`（HA262 短边仍选 720p）。**改 guest 边长，不是 DPI**。
   Session 默认仍走 `DEFAULT`=720p；产品 UI / 设置页接线仍开。
4. **可选 UI**：暴露预设选择或「界面大小」— 定产品后再动设置页。

相关：`WineAndroidHostScale.kt` / `WineAndroidHostScaleTest.kt` /
`WineAndroidDpiTest.kt`。

## 相关代码

| 文件 | 职责 |
|------|------|
| `WineAndroidDesktop.kt` | contentHost、WindowGroup 嵌套、visible 布局、setFixedSize、surfaceChanged 再 register；GDI 组可焦点 + `sendKeyboardEvent`；IME `WineInputConnection` commit |
| `WineAndroidImeCommit.kt` | IME 提交文本 → `KeyCharacterMap.getEvents` → KEYBOARD_EVENT；未映射码点交给 `nativeSendUnicodeChar` |
| `WineAndroidDebugImeInject.kt` | debug-only extra `app.amphora.debug.IME_UNICODE_TEXT`（`FLAG_DEBUGGABLE`）|
| `WineAndroidHostScale.kt` | 纯 letterbox scale/offset 计算（单测覆盖多分辨率） |
| `WineAndroidHostBridge.kt` | createWindow / windowPosChanged(visible_*) / setParent→reparent |
| `WineAndroidWindow.kt` | window/client/visible rect、style、visible |
| `WineAndroidSessionActivity.kt` | session + 调试 statusView；desktop 嵌套布局；`dispatchKeyEvent` → KEYBOARD_EVENT；debug IME unicode extra |
| `wineandroid_host_ipc.c` | `nativeRegisterSurface` → `SURFACE_CHANGED`；`nativeSendMotionEvent` / `nativeSendKeyboardEvent` / `nativeSendUnicodeChar`（`KEYEVENTF_UNICODE`） |

## 键盘（EVENT_KEYBOARD）

硬件 / `adb input keyevent` / `adb input text`（KEYCODE 注入）与 soft IME **commit** 都走与 MOTION 同一条 desktop event pipe（`nativeSendKeyboardEvent` / `nativeSendUnicodeChar`）。

- **IME commit 已落地**：`WineAndroidDesktop` 实现 `onCreateInputConnection` → 复用 `WineInputConnection`；committed ASCII/Latin 经 `KeyCharacterMap`（`VIRTUAL_KEYBOARD`）映射为 KeyEvent 再 `sendKeyboardEvent`。删除 / EditorAction(ENTER) / `onSendKeyEvent` 同管。触摸设 `keyTargetHwnd` 后 `showSoftKeyboard()`。
- **CJK/unicode commit 已落地**：`KeyCharacterMap` 无法映射的码点经 `nativeSendUnicodeChar` → 同 pipe 的 `KEYEVENTF_UNICODE`（BMP 一 wchar；补充平面 UTF-16 代理对两次 unicode 事件；各 down+up）。Guest 侧仍是 `NtUserSendHardwareInput`，**不做** IMM32/TSF。
- **Debug IME 注入（HA262 冒烟）**：debuggable 下 `--es app.amphora.debug.IME_UNICODE_TEXT '中文A'`（MainActivity 冷启转发，或 session 已起后 `am start` → `WineAndroidSessionActivity` onNewIntent）。`WineAndroidDesktop.injectCommittedTextForDebug` 走与 soft IME 相同的 commit 路径；日志 `IME unicode inject …` + Desktop `IME unicode hwnd=…` + HostIpc `keyboard unicode …`。
- **HA262 unicode 自动冒烟 PASS**（`702b165`，2026-09-16 ~14:08 Asia/Shanghai）：MainActivity 冷启
  `--ez …WINEANDROID true --ei WIDTH 1280 --ei HEIGHT 720 --es …IME_UNICODE_TEXT '中文A'`；
  `IME unicode inject scheduled reason=onCreate` → deferred → `hwnd=… text='中文A'`；
  HostIpc `keyboard unicode uchar=4e2d` / `6587`；Desktop `U+4e2d`/`U+6587` ok + `KEYCODE_A` ok。
  ART（Mac）：`smoke-artifacts/ha262-ime-unicode-auto-20260916-140749`。
- **仍开**：CJK **composition** 本地化 UI（`onComposingTextChanged` 仍仅 Log；下一拍 host composing
  overlay）；真机 CJK soft IME 眼验；第二台真机 / 分屏；guest 分辨率设置页 UI。勿发明 IMM32/TSF。

## 非目标

- 不引入 `WineActivity.java` / TextureView 照搬
- 不改 guest 分辨率来“铺屏”（铺屏只靠宿主 scale-to-fill）
- 不做 CreateSwapchain / 私有 host.sock
