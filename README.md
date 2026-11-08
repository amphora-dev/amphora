# Amphora

> 状态：里程碑 1 进行中（2026-11-08）：Valve Experimental 的 Wine 已在 Bionic 上构建，Y700 上记事本出画，前缀写时复制通过。进度见 [`docs/TRACKING.md`](docs/TRACKING.md)。
> 设计依据：[`docs/research/12-REWRITE-DESIGN.md`](docs/research/12-REWRITE-DESIGN.md)（已定决策 D1–D15）；设置清单：[`docs/research/13-SETTINGS-CATALOG.md`](docs/research/13-SETTINGS-CATALOG.md)。
> 旧实现（x86_64 Wine + Box64）在 [`amphora-dev/amphora_old`](https://github.com/amphora-dev/amphora_old)，不再开发，只作参考。

在 Android 上运行 Windows 游戏的引擎，按 SDK 设计：照 Valve 的 Proton tag 从源码构建，原生 aarch64 + arm64ec 的 Wine 跑在 Bionic 上，x86 代码交给 Wine 里的 FEX，窗口直接交给 SurfaceFlinger。目标以学习为主；我们自己的 App 是 SDK 的第一个使用者。

## 方式

- **像 prettier**：只有一套带版本号的配方（Recipe），默认就是最佳组合；每个设置都有说明和默认值理由，用户可以全局或按游戏覆盖。
- **贴近 SteamOS，但不搬它的重量**：不跑 Steam 客户端，不用 proot / pressure-vessel / gamescope；照 Proton 的源码和机制做（按游戏的缓存、Fossilize、FEX 代码缓存、按游戏修正）。
- **只支持 Adreno 7xx/8xx**，Vulkan 驱动只用 Turnip（KGSL）。
- **x86 翻译只用 FEX**：Box64 删掉，也不为它留接口。
- **不背旧代码的包袱**：配套仓库全按新设计改，不兼容旧实现；验证只在新实现上做。

```
Windows 游戏（x86 / x64）
  └─ FEX（libarm64ecfex / libwow64fex，Wine 内的 DLL）
Wine（Proton tag，aarch64 + arm64ec，Bionic）
  ├─ DXVK（D3D8–11）· vkd3d-proton（D3D12）· Zink（OpenGL）→ Turnip
  └─ wineandroid.drv：每个 HWND 一个 SurfaceControl，交换链 AHB 零拷贝
Amphora SDK（AAR）：Engine · Container · Session · GameSurface · Input / Audio · Settings
App：只依赖 SDK 公开 API 的薄壳
```

## 里程碑

| # | 内容 | 过关标准 |
|---|---|---|
| 1 | Proton tag 用 Bionic 构建成 aarch64 + arm64ec，带 `wineandroid.drv` | Y700 上记事本和 GDI 窗口出画 |
| 2 | FEX 接进 Wine | 64 位、32 位（WoW64）hello；FEX 磁盘缓存生效 |
| 3 | DXVK、vkd3d-proton、Turnip、交换链 | AIO 图形矩阵与旧仓库记录的结果一致；同在高通系统驱动上对比 Y700 基线 vk 832 / dx11 926 / dx12 1140 / dx9-32 431 fps，变慢要能解释；再比 Turnip 和系统驱动 |
| 4 | SDK 接口定型，App 改成薄壳 | App 只用公开 API |
| 5 | 按游戏缓存目录、Fossilize 本地录制与回放 | — |
| 6 | Steam 相关接口（只下载需要的部分） | — |

## 仓库

- 本仓库：SDK 与 App（APK 模块）、设计文档、设备上的测试工具（`tools/`）。现在的 `app/` 是里程碑 1 的宿主（Wine 自带的 `WineActivity`），里程碑 4 换成 SDK。
- Wine：`amphora-dev/proton-wine` 的 `amphora/exp-11.0-20261027b` 分支（Valve Experimental + 我们的补丁；构建在 amphora-engine）。
- 构建：[`amphora-engine`](https://github.com/amphora-dev/amphora-engine)（本地 `~/co/github/amphora-engine`）。BuildStream 构建 Wine 和设备端原生库，产出 SDK 安装的 runtime 归档；2026-11-08 从 imagefs 分出。
- 继续使用的配套仓库：[`proton-wine`](https://github.com/amphora-dev/proton-wine)（Wine 补丁）、[`content_manifest`](https://github.com/amphora-dev/content_manifest)（组件 pin 与 SHA）、[`buildstream-sdk`](https://github.com/amphora-dev/buildstream-sdk)（宿主 SDK、NDK、llvm-mingw）。它们按新设计改，不再兼容旧仓库。
- [`imagefs`](https://github.com/amphora-dev/imagefs) 冻结：旧产品（x86_64 Wine + Box64、rootfs、WCP）的构建，不再开发；还要用的配方按里程碑搬进 amphora-engine（清单在那边的 `docs/FROM-IMAGEFS.md`）。
- 测试设备：Y700 `HA262AAH`（Adreno 830）。开发期只保证这一台。

## 文档

- [`docs/TRACKING.md`](docs/TRACKING.md)：当前进度和证据。
- [`docs/research/12-REWRITE-DESIGN.md`](docs/research/12-REWRITE-DESIGN.md)：决策、配方、SDK 边界、出画 / 版本 / 前缀三项结论。
- [`docs/research/13-SETTINGS-CATALOG.md`](docs/research/13-SETTINGS-CATALOG.md)：设置目录、覆盖层级、每项默认值与理由。

## 许可

待定。若以后给别人嵌入，不能移植 DroidDeck 的 GPL-3.0 代码。
