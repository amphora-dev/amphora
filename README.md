# Amphora Steam 引擎

> 状态：立项（2026-11-06）。调研与两轮 Docker 实验已完成，Phase 0 设备门禁未开始。
> 本仓库只做 Steam 引擎；Wine 引擎留在 [`amphora-dev/amphora_old`](https://github.com/amphora-dev/amphora_old)。
> 方案来源：[`11-STEAM-ENGINE-PLAN.md`](https://github.com/amphora-dev/amphora_old/blob/main/docs/research/11-STEAM-ENGINE-PLAN.md)（唯一真源，细节以它为准）。

在 Android 上跑 Valve 的 arm64 Steam 客户端（Big Picture + Steam Input）与 ARM64 Proton 游戏。
Steam 自己下载和更新 Proton、SteamLinuxRuntime_4-arm64、FEX 工具；我们提供宿主 OS、GPU 驱动、合成器和容器桥。

## 方式：SteamOS 式分层

- **分层照 SteamOS**：Android app = 硬件 + 内核 + init；hostfs（Arch Linux ARM，SteamOS 同源）跑 Steam 客户端、gamescope、Mesa。
- **容器不自己写**：Valve 原版 pressure-vessel，把 `PRESSURE_VESSEL_BWRAP` 换成「bwrap 参数翻译成一个新 proot」的 shim。
- **GPU 驱动是我们的**：Turnip（KGSL 后端）+ Armada 补丁，在 hostfs 里构建，pressure-vessel 按 SteamOS 的方式注入游戏容器；宿主与合成器同源。
- **与 Wine 引擎并存，不合并**：libc（glibc vs bionic）、x86 方案（FEX vs Box64）、出画入口（Wayland 合成器 vs `wineandroid.drv`）都不同；只共享 `:core:content`、出画层、PulseAudio、CI 与发布。

```
Steam 客户端 / ARM64 Proton 游戏 / Linux 原生游戏
  └─ pressure-vessel（Valve 原版）→ bwrap shim → proot（每棵进程树一个）
hostfs：Arch Linux ARM + gamescope + Mesa Turnip + Xwayland + session
Amphora app：Wayland 合成器 → SurfaceControl、输入、音频、proot + loader
```

## 仓库职责

- **hostfs**：ALARM 基底；gamescope（SteamOS PKGBUILD + rebase 补丁）、mesa-turnip-kgsl、session 脚本；快照仓库与发布产物（照 imagefs 的 gate → release → bump-manifest）。
- **guest 侧**：bwrap shim、guest agent（Rust 静态二进制）、preload（SysV IPC / `futex_waitv` / ntsync）、`fakeinput` 移植。
- **设备实验（Phase 0）**：S1 pressure-vessel 经 proot、S2 proot @ targetSdk 36、S3 Turnip KGSL 出画；证据入库。

不进本仓库：APK 模块（`:feature:steam`、`:core:session`、`:core:surface`、`:core:engine-linux`、`:core:wayland` 在 Wine 引擎仓库 `amphora-dev/amphora_old`，迁移到本仓库前不复制）、Proton / Steam 客户端本身、proot 上游。

## 阶段

| 阶段 | 交付 | 退出判据 |
|---|---|---|
| Phase 0 | S1–S3 设备门禁 + 许可 | S1 容器内图形栈 = 宿主 Turnip 且游戏出画；S2 无 `execute_no_trans`；S3 枚举 Adreno 830 |
| Phase 1 | hostfs v0 + 快照仓库 + manifest pin | Docker 里 hostfs + proot 跑到 Steam 登录 |
| Phase 2 | Linux 引擎骨架（`:core:session` 抽取） | Wine 引擎分层回归 PASS；`vulkaninfo` 经协议回传 |
| Phase 3 | 出画 v0 + 输入 + 音频 | Y700 上 `vkcube` 经 gamescope 上屏，触屏可点，有声 |
| Phase 4 | Steam 客户端 / Big Picture / Proton 游戏 | 与 DroidDeck 同机可比（FPS、首帧、崩溃率） |
| Phase 5 | proot 快速路径 + 零拷贝 AHB | 与 DroidDeck 持平或更好；合成器 blit 计数为 0 |

## 关键风险

- Android 禁止硬链接 → pressure-vessel 的「可写 runtime 副本」；优先 `PRESSURE_VESSEL_COPY_RUNTIME=0`，S1 实测。
- targetSdk 36 下 app-data 不可 `execve`：`libproot.so` / `libproot-loader.so` 必须放 APK `jniLibs`（实验 S2）。
- 零拷贝要求 gralloc AHB 与 guest Turnip 的 UBWC 布局一致，否则保留 blit（约 20% 代价）。
- Steam 客户端 / Proton 更新随时可能回归：夜间设备冒烟 + 渠道固定 `steamdeck_publicbeta`。

## 参考

- 计划与证据：[`11-STEAM-ENGINE-PLAN.md`](https://github.com/amphora-dev/amphora_old/blob/main/docs/research/11-STEAM-ENGINE-PLAN.md)，及 `amphora_old` 的 `docs/04`–`docs/07`。
- `DroidDeck/`（GPL-3.0，本地同级 checkout）：proot 补丁、`droiddeck-bwrap`、`waylandcomp`、preload、`fakeinput` 的参考实现。
- `imagefs/`（本地同级 checkout）：hostfs 的构建与发布模式。

## 许可

待定；计划 §6.4 建议 GPL-3.0。移植 DroidDeck 代码需 GPL-3.0 兼容，来源与署名写进各模块 README / PATCHES。
