# Amphora

Android 上的 Wine 模拟器：x86_64 Wine 跑在 Box64 里，窗口经 `wineandroid.drv` 交给 Kotlin 宿主（每 HWND 一个 SurfaceView）。模块与启动链见 `docs/01-ARCHITECTURE.md`，文档总览 `docs/README.md`，进度只看 `docs/02-TRACKING.md` 末节「当前状态指针」。新 agent 先读 `.cursor/skills/amphora-from-zero/SKILL.md`。

## 开发阶段：不做兼容

当前设计是唯一真源。设备上的旧 prefix / applied mark / WinNative 布局都不是兼容面：不写迁移（applied-mark bump、wipe/rebind、legacy-backup、旧路径回退等），不留「如果还是旧的就……」分支。改坏了就重建容器 / 清 imagefs。

要做的是当前状态同步：按当前 pin 与 AppliedMarks 幂等应用（想要 ≠ 已装才做），删掉 manifest 不再 pin 的组件。

## 命令

- 门禁（pre-push 钩子同款，CI 同款）：`./gradlew spotlessCheck :app:testDebugUnitTest :app:lintDebug`；格式问题 `./gradlew spotlessApply`。任何 gradle 任务都会激活 `.githooks/`。
- APK：`./gradlew :app:assembleDebug`，产物 `app/build/outputs/apk/debug/app-debug.apk`。
- 设备上临时换 WCP / runtime：`scripts/inject-dev-pin.sh`（写 `filesDir/content/dev_pins.json`，见 `docs/07`）。正式发版走 imagefs publish + bump manifest。

## 提交

- 从 `origin/main` 开 `wip/<topic>`，推前 rebase；未推的试错先 squash。
- 跨仓改动（proton-wine / imagefs）在提交信息里写对方分支和 SHA，两边一起验、一起推。
- 真机 PASS 并进被验证的提交或该次推送的一条提交里，写进 `docs/02` 当前状态指针，不单独开 "record PASS" 提交。

## 出画不变量（真机踩过，改动前读 `docs/04`）

- GDI 窗：`api=NATIVE_WINDOW_API_CPU(2)`，格式保持 `PF_RGBA_8888`，颜色由宿主 R/B 交换；Surface 上设 BGRA=5 会整机闪退。
- 桌面 hwnd 与普通窗一样 `attachWindow` + `nativeRegisterSurface`；尺寸变化后必须重新 register。
- GDI 不走 CreateSwapchain / 私有 socket；游戏 Vulkan 走 AHB import swapchain（`docs/05`），勿退。
- 铺满靠宿主等比缩放 + Wine DPI 96，不改 guest `/desktop=` 分辨率，不把 Android densityDpi 喂给 Wine。
