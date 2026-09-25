# Amphora

Android 上的 Wine 模拟器。模块与启动链见 `docs/01-ARCHITECTURE.md`。

**新 agent 从零开工**：先读 [`docs/08-AGENT-BOOTSTRAP.md`](docs/08-AGENT-BOOTSTRAP.md) 与 [`.cursor/skills/amphora-from-zero/SKILL.md`](.cursor/skills/amphora-from-zero/SKILL.md)（clone / 编译 / 冒烟 / 多 bot）。

**现在是开发阶段。** 当前设计是唯一真源。设备上的旧 prefix、旧 applied mark、旧 WinNative/Winlator 布局都不是兼容面。

## 不要做

- 为上一版磁盘形态加迁移：applied-mark bump、wipe/rebind、把私有副本改成软链、legacy-backup、旧字体路径、digest-only pin 升级，等等。
- 改落地方式时保留「如果还是旧文件就……」的分支。直接按新设计写。

改坏了就重建容器 / 清 imagefs，不要在代码里兼容上一版。

## 仍要做

按**当前** pin 和 AppliedMarks 做幂等应用（想要 ≠ 已装才做），并删掉 manifest 不再 pin 的组件。这是当前状态同步，不是旧版迁移。

## 开发态换包

设备上临时钉本地 WCP / runtime 资产：用 catalog overlay `filesDir/content/dev_pins.json`（见 `docs/07-DEV-PIN-OVERLAY.md`），脚本 `scripts/inject-dev-pin.sh`。
WCP component pin 必须带 identity（`version`/`verName`/`verCode`/`contentType`/`kind`，来自 `profile.json`）；`inject-dev-pin.sh --component` 会自动写入。

**不要**再用 `<file>.local-override` 旁路（已移除）。正式发版仍走 imagefs publish + bump-manifest。

## 提交与推送

- 以 `main` 为底开 `wip/<topic>`，推前 `git rebase origin/main`；不要把共享 wip 分支当长期主线。
- 推前跑 `./gradlew spotlessCheck :app:testDebugUnitTest`，与 CI 同款，红了不推。
- 真机 PASS 记录并进被验证的那个提交，或每次推送合成一条；不要一个功能配一个 "record PASS" 提交。
- 没推之前的试错（改了又 revert、spotless 补丁）先在本地 squash 掉再推。
- 跨仓改动（amphora ↔ proton-wine / imagefs）在提交信息里写对方仓的分支和 SHA；两边一起验、一起推，不要只推一半。
- 当前状态只写 `docs/02` 末节「当前状态指针」，不要在各处再抄一份。

## wineandroid 宿主出画（勿旁路）

详见 `docs/04-WINEANDROID-DISPLAY.md`（已全面整合上游布局清单、X11 对照与排查指南；总览见 `docs/README.md`）。

- GDI 建窗：`wineandroid_host_ipc.c` `create_native_win_data` 设 `api=NATIVE_WINDOW_API_CPU(2)`，registerSurface 才会 `API_CONNECT`。勿对 OpenGL 窗强制 CPU。
- GDI 颜色：Surface 路径保持 `PF_RGBA_8888(1)`（HA262AAH 上 BGRA=5 曾整机闪退）；勿对 BufferQueue 强推 BGRA，颜色用宿主 R/B 交换修正。
- 桌面 hwnd：必须走与普通 GDI 窗相同的 `attachWindow` + `nativeRegisterSurface`；`createWindow` 不得因 `isDesktop` 早退跳过 SurfaceView（否则 LOCK -11）。
- GDI 出画不走 CreateSwapchain，禁私有 host.sock。游戏 Vulkan 的 AHB import CreateSwapchain（`docs/05`）是现行 Present 路径，勿退。
- **嵌套布局（WineActivity）**：`WineAndroidDesktop` 用 contentHost + 每 HWND `WindowGroup`；子窗挂到父 Group；定位用 **visible_rect**（父客户区相对），不要把 Start `(0,0)` 当桌面绝对坐标。布局/`setFixedSize` 最小 2×2 guest px。
- **Surface 尺寸**：`surfaceChanged` / buffer 变化后必须再 `nativeRegisterSurface`（发 SURFACE_CHANGED）；勿让 taskbar 卡在 1×1。
- **WS_VISIBLE / z-order**：`WineAndroidWindowStack`；隐窗从 parent **removeView**（非仅 GONE）；`!(flags & SWP_NOZORDER)` 时 reorder sibling 栈再 `bringChildToFront` 同步。
- 桌面铺满：contentHost 等比 scale-to-fill（letterbox）；Wine `/desktop=WxH` 仍可配。用 `setFixedSize(guest)` 保 ANW 尺寸，勿靠改 guest 分辨率铺屏。
- Wine DPI：虚拟桌面 + 宿主铺满时用经典 **96**；hostScale 实时算（**分屏/第二台已停**）。勿把 Android `densityDpi`（如 440）配 720p。
- **已做**：推迟第一次 `nativeRegisterSurface` 到真实 guest 尺寸（非单靠 MIN 2×2）；resize 仍再 bind。勿删 statusView；TextureView / 只给顶层 HWND 建 Surface 都是以后可选（见 docs/04 §7）。
