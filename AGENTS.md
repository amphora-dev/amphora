# Amphora

Android 上的 Wine 模拟器。模块与启动链见 `docs/05-ARCHITECTURE.md`。

**新 agent 从零开工**：先读 [`docs/19-AGENT-BOOTSTRAP.md`](docs/19-AGENT-BOOTSTRAP.md) 与 [`.cursor/skills/amphora-from-zero/SKILL.md`](.cursor/skills/amphora-from-zero/SKILL.md)（clone / 编译 / 冒烟 / 多 bot）。

**现在是开发阶段。** 当前设计是唯一真源。设备上的旧 prefix、旧 applied mark、旧 WinNative/Winlator 布局都不是兼容面。

## 不要做

- 为上一版磁盘形态加迁移：applied-mark bump、wipe/rebind、把私有副本改成软链、legacy-backup、旧字体路径、digest-only pin 升级，等等。
- 改落地方式时保留「如果还是旧文件就……」的分支。直接按新设计写。

改坏了就重建容器 / 清 imagefs，不要在代码里兼容上一版。

## 仍要做

按**当前** pin 和 AppliedMarks 做幂等应用（想要 ≠ 已装才做），并删掉 manifest 不再 pin 的组件。这是当前状态同步，不是旧版迁移。

## 开发态换包

设备上临时钉本地 WCP / runtime 资产：用 catalog overlay `filesDir/content/dev_pins.json`（见 `docs/15-DEV-PIN-OVERLAY.md`），脚本 `scripts/inject-dev-pin.sh`。
WCP component pin 必须带 identity（`version`/`verName`/`verCode`/`contentType`/`kind`，来自 `profile.json`）；`inject-dev-pin.sh --component` 会自动写入。

**不要**再用 `<file>.local-override` 旁路（已移除）。正式发版仍走 imagefs publish + bump-manifest。

## wineandroid 宿主出画（勿旁路）

详见 `docs/16-WINEANDROID-DISPLAY.md`；**可借上游清单**见
`docs/17-WINEANDROID-UPSTREAM-BORROW.md`；**与 X11 对照 + 一路踩坑**见
`docs/18-WINEANDROID-VS-X11-SURFACES.md`。

- GDI 建窗：`wineandroid_host_ipc.c` `create_native_win_data` 设 `api=NATIVE_WINDOW_API_CPU(2)`，registerSurface 才会 `API_CONNECT`。勿对 OpenGL 窗强制 CPU。
- GDI 颜色：Surface 路径保持 `PF_RGBA_8888(1)`（HA262AAH 上 BGRA=5 曾整机闪退）；勿对 BufferQueue 强推 BGRA，颜色用宿主 R/B 交换修正。
- 桌面 hwnd：必须走与普通 GDI 窗相同的 `attachWindow` + `nativeRegisterSurface`；`createWindow` 不得因 `isDesktop` 早退跳过 SurfaceView（否则 LOCK -11）。
- 禁 CreateSwapchain / 私有 host.sock。
- **嵌套布局（WineActivity）**：`WineAndroidDesktop` 用 contentHost + 每 HWND `WindowGroup`；子窗挂到父 Group；定位用 **visible_rect**（父客户区相对），不要把 Start `(0,0)` 当桌面绝对坐标。布局/`setFixedSize` 最小 2×2 guest px。
- **Surface 尺寸**：`surfaceChanged` / buffer 变化后必须再 `nativeRegisterSurface`（发 SURFACE_CHANGED）；勿让 taskbar 卡在 1×1。
- **WS_VISIBLE / z-order**：跟 style 显隐；`!(flags & SWP_NOZORDER)` 时按 insertAfter 重排。
- 桌面铺满：contentHost 等比 scale-to-fill（letterbox）；Wine `/desktop=WxH` 仍可配。用 `setFixedSize(guest)` 保 ANW 尺寸，勿靠改 guest 分辨率铺屏。
- Wine DPI：虚拟桌面 + 宿主铺满时用经典 **96**；跨机 hostScale 实时算（多设备 TODO）。勿把 Android `densityDpi`（如 440）配 720p。上游窗口布局见 `docs/17-WINEANDROID-UPSTREAM-BORROW.md`；X11 对照与踩坑见 `docs/18-WINEANDROID-VS-X11-SURFACES.md`。
- **已做**：推迟第一次 `nativeRegisterSurface` 到真实 guest 尺寸（非单靠 MIN 2×2）；resize 仍再 bind。勿删 statusView；TextureView / 只给顶层 HWND 建 Surface 都是以后可选（见 docs/18）。
