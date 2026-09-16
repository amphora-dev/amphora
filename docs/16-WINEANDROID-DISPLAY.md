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
   **第二台物理机 / 分屏：用户已停（2026-09-16）；默认下一拍勿做。**
2. **禁止 densityDpi 入径（已锁）**：`WineAndroidDpiTest` + SessionActivity 日志
   标明 android densityDpi unused；缺参仍 classic 96。
3. **guest 分辨率档 + 设置页（已接线）**：`WineAndroidGuestResolution` 预设
   HD 1280×720（默认）/ XGA 1024×768 / HD+ 1600×900 / FHD 1920×1080 落在
   `core:engine`；短边启发式 `suggestForHostShortSide`（HA262 仍 720p）。
   **Settings → Common → Display → Resolution** 与 Launcher `Resolution` 枚举与该目录对齐；
   `RuntimeSettingsStore`（SharedPreferences `display_resolution`）存 `R{W}x{H}` /
   guest id，经 `preferenceName` / `fromPreference` 映射；未知旧值回落 DEFAULT。
   Desktop / Launcher 启动走已存 WxH → `SessionLaunch`（**下一拍会话**生效）。
   MainActivity 的 wineandroid/smoke debug 冷启在未传 WIDTH/HEIGHT extras 时也读取该偏好；
   显式 extras 仍覆盖偏好，便于 smoke 固定尺寸。
   **`fdda994` no-WIDTH pref 路径已合入**（读 `display_resolution`）；真机 PASS
   未宣称（Grok Bot 无 Mac 装机；parent 可冒烟）。
4. **WS_VISIBLE / sibling z-order（加固）**：`WineAndroidWindowStack` + Desktop
   sibling 栈；隐窗 removeView；叠窗 `bringChildToFront` 同步。单测
   `WineAndroidWindowStackTest`。
   **HA262 window-stack / WS_VISIBLE smoke PASS**（`4d3d976`，2026-09-16
   ~16:48 Asia/Shanghai）：session 1280×720；first register（desktop /
   taskbar / windows）；`windowPosChanged` 带 `style=`；无 FATAL。
   ART（Mac）：`smoke-artifacts/ha262-window-stack-20260916-164730`。
   **仍开（可选）**：重叠 HWND z-order **人工眼验**（非自动化）。
5. **非目标**：不另造第二套桌面模型；不改 Present/AHB / TextureView / BGRA / IMM32；
   **不做分屏 / 第二台 hostScale**（用户已停）。

相关：`WineAndroidGuestResolution.kt` / `WineAndroidGuestResolutionTest.kt` /
`WineAndroidHostScale.kt` / `WineAndroidHostScaleTest.kt` / `WineAndroidDpiTest.kt`。

## 相关代码

| 文件 | 职责 |
|------|------|
| `WineAndroidDesktop.kt` | contentHost、WindowGroup 嵌套、visible 布局、setFixedSize、surfaceChanged 再 register；GDI 组可焦点 + `sendKeyboardEvent`；IME `WineInputConnection` commit + host composing |
| `WineAndroidImeCommit.kt` | IME 提交文本 → `KeyCharacterMap.getEvents` → KEYBOARD_EVENT；未映射码点交给 `nativeSendUnicodeChar` |
| `WineAndroidImeUi.kt` | 纯 `ImeUiState` reducer + composing chip 可见性（单测） |
| `WineAndroidDebugImeInject.kt` | debug-only extras `IME_UNICODE_TEXT` + `IME_COMPOSING_TEXT`（`FLAG_DEBUGGABLE`）；清 composing 用 `--esn` |
| `WineAndroidDebugImeRelayActivity`（`src/debug`） | 导出中继：adb 中途注入 → 同 UID 启动非导出 Session `onNewIntent` |
| `WineAndroidGuestResolution.kt`（`core:engine`） | guest 预设目录 + preference 映射；Settings/Launcher 对齐 |
| `WineAndroidHostScale.kt` | 纯 letterbox scale/offset 计算（单测覆盖多分辨率） |
| `WineAndroidHostBridge.kt` | createWindow / windowPosChanged(visible_*) / setParent→reparent |
| `WineAndroidWindow.kt` | window/client/visible rect、style、visible（默认 false） |
| `WineAndroidWindowStack.kt` | 纯 WS_VISIBLE / SWP_NOZORDER / sibling reorder + sync 顺序（单测） |
| `WineAndroidSessionActivity.kt` | session + 调试 statusView；host composing TextView chip；`dispatchKeyEvent` → KEYBOARD_EVENT；debug IME unicode / composing extras |
| `wineandroid_host_ipc.c` | `nativeRegisterSurface` → `SURFACE_CHANGED`；`nativeSendMotionEvent` / `nativeSendKeyboardEvent` / `nativeSendUnicodeChar`（`KEYEVENTF_UNICODE`） |

## 键盘（EVENT_KEYBOARD）

硬件 / `adb input keyevent` / `adb input text`（KEYCODE 注入）与 soft IME **commit** 都走与 MOTION 同一条 desktop event pipe（`nativeSendKeyboardEvent` / `nativeSendUnicodeChar`）。

- **IME commit 已落地**：`WineAndroidDesktop` 实现 `onCreateInputConnection` → 复用 `WineInputConnection`；committed ASCII/Latin 经 `KeyCharacterMap`（`VIRTUAL_KEYBOARD`）映射为 KeyEvent 再 `sendKeyboardEvent`。删除 / EditorAction(ENTER) / `onSendKeyEvent` 同管。触摸设 `keyTargetHwnd` 后 `showSoftKeyboard()`。
- **CJK/unicode commit 已落地**：`KeyCharacterMap` 无法映射的码点经 `nativeSendUnicodeChar` → 同 pipe 的 `KEYEVENTF_UNICODE`（BMP 一 wchar；补充平面 UTF-16 代理对两次 unicode 事件；各 down+up）。Guest 侧仍是 `NtUserSendHardwareInput`，**不做** IMM32/TSF。
- **Debug IME 注入（HA262 冒烟）**：debuggable 下 `--es app.amphora.debug.IME_UNICODE_TEXT '中文A'`（MainActivity **冷启**转发；**中途**用 debug-only 导出 `WineAndroidDebugImeRelayActivity` → 同 UID `startActivity` 非导出 Session → `onNewIntent`）。勿直接 `am start` Session（SecurityException）；勿指望中途 `am start MainActivity`（Session 在上，只把 task 拉前台）。`WineAndroidDesktop.injectCommittedTextForDebug` 走与 soft IME 相同的 commit 路径；日志 `IME unicode inject …` + Desktop `IME unicode hwnd=…` + HostIpc `keyboard unicode …`。
- **HA262 unicode 自动冒烟 PASS**（`702b165`，2026-09-16 ~14:08 Asia/Shanghai）：MainActivity 冷启
  `--ez …WINEANDROID true --ei WIDTH 1280 --ei HEIGHT 720 --es …IME_UNICODE_TEXT '中文A'`；
  `IME unicode inject scheduled reason=onCreate` → deferred → `hwnd=… text='中文A'`；
  HostIpc `keyboard unicode uchar=4e2d` / `6587`；Desktop `U+4e2d`/`U+6587` ok + `KEYCODE_A` ok。
  ART（Mac）：`smoke-artifacts/ha262-ime-unicode-auto-20260916-140749`。
- **Host composing overlay 已落地**：`onComposingTextChanged` → `ImeUiState` → SessionActivity 左上角
  TextView chip（commit/finish/`WineInputConnection.reset` 清空）。**不做** IMM32/TSF；
  composition 永不进 guest。
- **Debug composing 注入（HA262 冒烟）**：debuggable 下 `--es app.amphora.debug.IME_COMPOSING_TEXT 'nihao'`
  （MainActivity **冷启**；中途 → `WineAndroidDebugImeRelayActivity`）。仅 `updateImeUiState(composingText=…)`，
  **不**走 commit/unicode pipe。**清 chip 用 `--esn …IME_COMPOSING_TEXT`**（`am` 拒绝 `--es … ''`）。
  日志 `IME composing inject scheduled` + Desktop `IME composing inject len=… (host-local…)`。
  配方：`/workspace/ha262-ime-composing-overlay-smoke.md`。
- **HA262 cold composing chip PASS**（`01cf904`）：MainActivity 冷启
  `--es …IME_COMPOSING_TEXT nihao` → chip 显示；`--esn` 清 chip。
- **HA262 mid-session composing relay PASS**（`fe8f5a2`，2026-09-16 ~14:22 Asia/Shanghai）：
  Session 已在前台（MainActivity WINEANDROID）；
  `am start …WineAndroidDebugImeRelayActivity --es IME_COMPOSING_TEXT nihao` →
  Session `onNewIntent len=5` + Desktop inject len=5；chip 显示；
  `--esn IME_COMPOSING_TEXT` → `onNewIntent len=0`；chip 清；无 unicode/keyboard unicode 泄漏。
  ART（Mac）：`smoke-artifacts/ha262-ime-composing-relay-20260916-142224`。
- **仍开（可选）**：真机 soft IME **眼验** composing chip（`adb input text` 无法模拟 composing）；
  第二台真机 / 分屏（**已停**，勿排下一拍）。

## 非目标

- 不引入 `WineActivity.java` / TextureView 照搬
- 不改 guest 分辨率来“铺屏”（铺屏只靠宿主 scale-to-fill）
- 不做 CreateSwapchain / 私有 host.sock
