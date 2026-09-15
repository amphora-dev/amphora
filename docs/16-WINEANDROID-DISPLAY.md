# 16 · wineandroid 显示：出画、颜色、铺满、DPI

> 给后续会话的单一说明。实现真源在代码；本页只记结论与禁区。

## 目标

HA262AAH 上 Amphora 默认 wineandroid 会话：有窗、颜色正确、桌面等比铺满、控件大小正常。

## 禁令

- 临时 `.so` 塞真机
- `CreateSwapchain` / 私有 `host.sock`
- 对 SurfaceView BufferQueue `SET_BUFFERS_FORMAT(BGRA=5)`（HA262 整机闪退）
- Cursor cloud 改本仓库

## 出画（GDI）

1. 宿主 `create_native_win_data`：GDI 窗 `api=NATIVE_WINDOW_API_CPU(2)`，`registerSurface` 才会 `API_CONNECT`。
2. 桌面 hwnd 也要 `attachWindow` + SurfaceView（不可因 `isDesktop` 跳过）。
3. Guest：`NATIVE_WINDOW_LOCK` → 拷 DIB → `UNLOCK_AND_POST`（上游同路）。

## 颜色

- Surface / ANW 保持 `PF_RGBA_8888(1)`；宿主忽略 guest 的 SET BGRA。
- Guest Proton（`ed592594a`）在 `AMPHORA_WINEANDROID=1` 时 flush 做 R↔B。
- 上游 TextureView 可走 BGRA；我们 SurfaceView 路径不能。

## 铺满

- 虚拟桌面分辨率可配（默认 `1280x720`，`explorer /desktop=shell,WxH`）。
- 宿主 `WineAndroidDesktop` 等比 scale-to-fill（letterbox）；`setFixedSize(guest)` 保 ANW=Wine 尺寸。
- **不要**默认把桌面改成 Activity 全像素（GDI 拷贝压力大）。

## DPI（虚拟桌面 + 宿主铺满）

宿主把 guest 桌面 ×`hostScale` 铺满；`hostScale` **按当前屏实时计算**，不是写死 2.375。

Wine 侧当前用经典 **96**（宿主已负责放大；勿再叠 Winlator 254，勿传 Android densityDpi）。

| Wine DPI | × hostScale(例 2.375) | 上屏等效 | 观感 |
|----------|----------------------|---------|------|
| 440 | 2.375 | ~1045 | 巨化 |
| 254 | 2.375 | ~600 | 仍偏大 |
| **96（当前）** | 2.375 | **~228** | 与 Winlator 报的 254 同量级 |

96 ≠ 254；接近的是「96×缩放≈228」≈ 254。

实现：`WineAndroidDpi.forVirtualDesktopWithHostScale()` → 96。


## 开发换包

`scripts/inject-dev-pin.sh --component`：WCP 必须写入 `verName`/`version`/`verCode`/`contentType`/`kind`（从 `profile.json`），否则 Prepare `WCP profile does not match manifest`。

## 当前钉（HA262 开发）

- Proton：`ed592594a`（swizzle），sha `87109db4…`
- Box64：`ae9a8920`（ANativeWindow 等 wrap）
- APK：忽略 SET BGRA + 宿主铺满 + Winlator DPI

## 后置

DXVK / Vulkan / AHB present 另轨；勿用 GDI LOCK 扛游戏性能。

## Surface 尺寸陷阱

`createWindow` 时许多 HWND 还没有 `WINDOW_POS`（矩形 0×0）。若宿主把空矩形回退成「整桌面」再 `registerSurface`，ANW 会变成宿主铺满尺寸（HA262 上约 3040×1710），而 Wine 仍按真实小窗画像素 → Android 把一条内容拉成整块（底栏/标题扭曲）。

正确做法：空矩形先 `1×1` + `setFixedSize(1,1)`；收到真实坐标后再改 layout 与 fixed size。

## TODO · 跨机宿主缩放与 DPI

`hostScale = min(屏宽/guestW, 屏高/guestH)` 随设备变（HA262≈2.375，别的手机可能是 1.5～3）。当前策略：

- 虚拟桌面默认 1280×720（可配）
- 宿主等比铺满（自动算 scale，**不必每台手写 2.375**）
- Wine DPI 固定经典 **96**（不跟 scale 再叠 254）

待想清楚再改（不要现在散改）：

1. **DPI 是否跟 scale 联动**：`96` 固定 vs `254/scale` vs 用户可调 LogPixels
2. **默认 guest 分辨率**：固定 720p vs 按短边分档 vs 贴近物理像素（GDI 成本）
3. **多机验收**：至少再找一台不同 density/分辨率冒烟，核对控件观感
4. **设置项**：是否暴露「界面大小」滑条（改 DPI 或 guest 分辨率）

记录于 `docs/16-WINEANDROID-DISPLAY.md`；实现前先定产品策略。

