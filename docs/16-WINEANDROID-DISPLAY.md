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

## DPI（Winlator 公式）

X11 路径 `ScreenInfo`：

```text
width_mm  = guest_width_px  / 10
height_mm = guest_height_px / 10
dpi       = guest_width_px * 25.4 / width_mm  ≈ 254
```

这里的「像素」是**虚拟桌面**宽高（如 1280），不是平板放大后的物理像素。

| 做法 | 结果 |
|------|------|
| 720p + Android `densityDpi=440` | 控件巨化（已踩坑） |
| 720p + Winlator ≈254 + 宿主铺满 | 与 X11 自洽（当前） |
| Activity 全像素 + Android 440 | 接近上游 wineandroid，GDI 更重 |

实现：`WineAndroidDpi.fromGuestDesktop` → `updateDesktopMetrics` → `nativeNotifyConfigChanged`。

Windows / Proton / CrossOver **并不**统一用 254：Linux Wine 默认常 96；CrossOver Retina 常 192；Proton 游戏缩放多靠 gamescope。254 是 Winlator/Amphora X11 的虚拟屏约定。

## 开发换包

`scripts/inject-dev-pin.sh --component`：WCP 必须写入 `verName`/`version`/`verCode`/`contentType`/`kind`（从 `profile.json`），否则 Prepare `WCP profile does not match manifest`。

## 当前钉（HA262 开发）

- Proton：`ed592594a`（swizzle），sha `87109db4…`
- Box64：`ae9a8920`（ANativeWindow 等 wrap）
- APK：忽略 SET BGRA + 宿主铺满 + Winlator DPI

## 后置

DXVK / Vulkan / AHB present 另轨；勿用 GDI LOCK 扛游戏性能。
