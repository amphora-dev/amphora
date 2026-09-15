# wineandroid 可借上游项（WineActivity.java）

对照上游 `dlls/wineandroid.drv/WineActivity.java`（本仓旁路
`../proton-wine` 或 `/Users/sky/co/github/proton-wine`）。**只记可借 /
应借的宿主布局与 Surface 生命周期项**；资产加载、`loadWine`、JNI 启动链等
无关缺口不在本文。

Amphora 实现面：`WineAndroidDesktop` / `WineAndroidHostBridge` /
`WineAndroidWindow`（Kotlin，无 `WineActivity.java`）。总览仍见
[`16-WINEANDROID-DISPLAY.md`](16-WINEANDROID-DISPLAY.md)。
X11 对照与踩坑见 [`18-WINEANDROID-VS-X11-SURFACES.md`](18-WINEANDROID-VS-X11-SURFACES.md)。

**已落地（`wip/ha262-paint`）**：嵌套 WindowGroup、visible_rect、surface 尺寸再
register、WS_VISIBLE / z-order、min 2×2；**保留 statusView**。提交示例：
`a5a0b0e`。
**尚未借 / 尚未做**：推迟第一次 register 到真实尺寸（见 docs/18 §5）。

---

## 1. 嵌套 parent ViewGroup + 父相对 visible_rect 布局

**上游行为**

- 每个 HWND 有 `WineWindowGroup`（ViewGroup）；GDI/client 内容是组内
  `WineView`；子窗 `window_group` 挂到父 `client_group`（`add_view_to_parent`）。
- `pos_changed` / `create_window_groups` 用 **`visible_rect`** 调
  `window_group.set_layout(left, top, right, bottom)`。
- `visible_rect` 来自 win32u，相对**父客户区**；Start 按钮
  `(0,0)-(126,46)` 相对 taskbar，不是桌面绝对坐标。

**我们的缺口（修前）**

- 曾把 HWND 当平铺子 View 贴到根/桌面绝对坐标；Start `(0,0)` 落到桌面原点，
  taskbar/Start 错位（「挤牙膏」式布局）。

**如何采纳**

- `WineAndroidDesktop`：根 letterbox + 内层 `contentHost`；每 HWND 一个
  `WindowGroup`（FrameLayout），SurfaceView `match_parent`，子 Group 嵌套在父
  Group。
- `setParent` → `reparent`；`parentHwnd==0` / desktop / 父未就绪 → 挂
  `contentHost`。
- `windowPosChanged` 传入的 `visible_*` 写入 `WineAndroidWindow.visibleRect`，
  布局用 `visibleRect × hostScale`（缺省再回退 client/window）。

---

## 2. 尺寸变化后重新 bind surface（SURFACE_CHANGED）

**上游行为**

- `WineView.onSurfaceTextureSizeChanged` → 再次 `window.set_surface(...)`（等同
  再 `wine_surface_changed` / 通知 guest 新 w×h）。

**我们的缺口（修前）**

- `WINDOW_POS` 后只 `setFixedSize`，`surfaceChanged` 空实现或不 re-register →
  taskbar 先以 1×1 register，随后卡在 1×1。

**如何采纳**

- 保持 **SurfaceView + `setFixedSize(guest px)`**（见 §6），不要为对齐上游
  盲切 TextureView。
- `SurfaceHolder.Callback.surfaceChanged` → 再调 `onSurface` →
  `nativeRegisterSurface` → native 发 `SURFACE_CHANGED`。
- `setFixedSize` 改变 buffer 且 surface 仍 valid 时，也可主动再 `onSurface`
  一次，避免 callback 延迟。

logcat：`windowPosChanged` 后应再出现 taskbar 约 `1280×46` 的 register，而非
一直 1×1。

---

## 3. WS_VISIBLE 显隐 + z-order（SWP_NOZORDER）

**上游行为**

- `visible = (style & WS_VISIBLE) != 0`；显→隐 `remove_view_from_parent`，
  隐→显 `add_view_to_parent`。
- `(flags & SWP_NOZORDER) == 0` 时 `set_zorder(insert_after)` +
  `sync_views_zorder`（`bringToFront`）。

**我们的缺口（修前）**

- 未跟 style 显隐；未处理 z-order，叠窗顺序可能错。

**如何采纳**

- `WineAndroidWindow.style` / `visible`；`WindowGroup.visibility`
  VISIBLE/GONE。
- `updateHwndRects(..., flags, insertAfter)`：当 `!(flags & SWP_NOZORDER)` 时
  在同一 parent 下按 `insertAfter`（HWND_TOP/BOTTOM/…）重排
  `bringChildToFront` / `addView(index)`。

---

## 4. set_layout 最小边长 ≥ 2（永不空视图）

**上游行为**

```java
if (right <= left + 1) right = left + 2;
if (bottom <= top + 1) bottom = top + 2;
```

**我们的缺口（修前）**

- 0×0 / 1×1 布局或 buffer 导致 ANW/GDI 异常或卡死。

**如何采纳**

- 布局 guest 边长与 `setFixedSize` 均 `max(2, w/h)`（`MIN_GUEST_PX = 2`）。

---

## 5.（可选保留）SurfaceView + host letterbox + DPI 策略

**不要盲目切换到 TextureView。** Amphora GDI 路径依赖 SurfaceView /
`ANativeWindow` + `setFixedSize(guest)`；上游 TextureView 是其实现细节，不是
借来的产品要求。

同时保持：

- **host letterbox / scale-to-fill**：`contentHost` 等比铺 Activity；不靠改
  guest `/desktop=WxH` 铺屏。
- **DPI 策略不变**：产品默认目标 Wine DPI **96**（多设备 TODO）；勿把 Android
  `densityDpi`（如 440）配 720p。当前可继续用 ScreenInfo 风格 mm 公式做
  LogPixels 实验，但不因「借上游」改 DPI 真源。

---

## 非目标（勿垫文）

- 资产 / `loadWine` / Activity 启动脚手架
- 整文件移植 `WineActivity.java`
- CreateSwapchain / 私有 host.sock
- 删除或「借上游」名义改掉 session 调试 status TextView（宿主自有 UI，不在
  本借列表）
