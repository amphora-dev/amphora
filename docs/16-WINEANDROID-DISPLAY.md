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

- 当前实现仍可按 guest ScreenInfo（mm=W/10 → LogPixels≈254）通知 Wine，避免把
  Android `densityDpi`（如 440）配 720p 导致 chrome 巨大。
- **产品目标**：默认 Wine DPI **96**；多设备 / 多分辨率 DPI 策略 **TODO**。

## 相关代码

| 文件 | 职责 |
|------|------|
| `WineAndroidDesktop.kt` | contentHost、WindowGroup 嵌套、visible 布局、setFixedSize、surfaceChanged 再 register |
| `WineAndroidHostBridge.kt` | createWindow / windowPosChanged(visible_*) / setParent→reparent |
| `WineAndroidWindow.kt` | window/client/visible rect、style、visible |
| `WineAndroidSessionActivity.kt` | session + 调试 statusView；desktop 嵌套布局 |
| `wineandroid_host_ipc.c` | `nativeRegisterSurface` → `SURFACE_CHANGED` |

## 非目标

- 不引入 `WineActivity.java` / TextureView 照搬
- 不改 guest 分辨率来“铺屏”（铺屏只靠宿主 scale-to-fill）
- 不做 CreateSwapchain / 私有 host.sock
