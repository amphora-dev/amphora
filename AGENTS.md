# Amphora

Android 上的 Wine 模拟器。模块与启动链见 `docs/05-ARCHITECTURE.md`。

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

- GDI 建窗：`wineandroid_host_ipc.c` `create_native_win_data` 设 `api=NATIVE_WINDOW_API_CPU(2)`，registerSurface 才会 `API_CONNECT`。勿对 OpenGL 窗强制 CPU。
- GDI 颜色：Surface 路径保持 `PF_RGBA_8888(1)`（HA262AAH 上 BGRA=5 曾整机闪退）；勿对 BufferQueue 强推 BGRA；颜色在 guest `wineandroid.drv` flush 里 R↔B（`AMPHORA_WINEANDROID=1`）。
- 桌面 hwnd：必须走与普通 GDI 窗相同的 `attachWindow` + `nativeRegisterSurface`；`createWindow` 不得因 `isDesktop` 早退跳过 SurfaceView（否则 LOCK -11）。
- 禁 CreateSwapchain / 私有 host.sock。
- 桌面铺满：宿主 `WineAndroidDesktop` 对 HWND SurfaceView 做等比 scale-to-fill（letterbox）；Wine `/desktop=WxH` 仍可配。用 `setFixedSize(guest)` 保 ANW 尺寸，勿靠改 guest 分辨率铺屏。
- Surface 尺寸：空 HWND 矩形不得回退成整桌面；`setFixedSize` 始终钉 guest 像素（未定位前用 1×1），否则小窗 ANW=整屏会拉花底栏。
- Wine DPI：虚拟桌面 + 宿主铺满时用经典 **96**；`hostScale` 按屏实时算（勿写死 2.375）。跨机策略见 `docs/16-WINEANDROID-DISPLAY.md` TODO。**禁止**再叠 254 / 直传 Android 440。
