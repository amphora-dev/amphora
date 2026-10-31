# 11 - Steam 引擎（glibc Linux 会话）立项计划

> 状态：**草案，未拍板**。2026-10-18。
> 定位：Amphora 的第二个会话引擎，在 Android app 里跑 Valve 的 arm64 Steam 客户端（Big Picture）与 ARM64 Proton 游戏。目的是自有平台与学习，不追 DroidDeck 的功能面。
> 依据：与 DroidDeck（`../DroidDeck`，GPL-3.0）、armada-tb322fc、imagefs 的对照调研，以及两轮 Docker 实验（证据目录见 §10）。本文标注 **[已验证]** 的有命令输出为证，**[推断]** 的只读过代码或二进制字符串，**[未验证]** 的是计划假设。

---

## 1. 结论摘要

1. **分层照 SteamOS**：Android app = 硬件 + 内核 + init；我们维护一个小的宿主 OS（Arch Linux ARM，SteamOS 同源）跑 Steam 客户端、gamescope、Mesa；游戏所用的 runtime、Proton、FEX 工具全部交给 Steam 下载和更新。
2. **容器不自己写**：用 Valve 原版 pressure-vessel，只替换它调用的 `bwrap`（`PRESSURE_VESSEL_BWRAP`）为"把 bwrap 参数翻译成一个新 proot"的 shim。DroidDeck 已有这个 shim（Flatpak 用）。
3. **GPU 驱动是我们的**：Turnip（KGSL 后端）在宿主 OS 里构建，pressure-vessel 按 SteamOS 的方式把它和依赖注入游戏容器。
4. **先做设备门禁实验，再写引擎**。最大的未知（proot 在 targetSdk 36 / Android seccomp 下、pressure-vessel 经 proot、Turnip 真机出画、性能）Docker 测不了；第一个实验直接在已装 DroidDeck 的 Y700 上做，几乎不用写代码。
5. **与 Wine 引擎并存，不合并**：两条栈的 libc、x86 方案、出画路径都不同；只共享 `:core:content`、出画层（SurfaceControl / AHB）、音频、构建与发布设施。

## 2. 目标与非目标

**目标（v0 → v1）**

- v0：Y700 上从 Amphora 启动 Steam Big Picture，登录，手柄可用，Steam 自己装好 ARM64 Proton 与 SteamLinuxRuntime_4-arm64，跑通一款 Proton 游戏。
- v1：零拷贝出画、proot 快速路径、帧时间与 DroidDeck 同机持平或更好。
- 全程：每个外部二进制有 pin + SHA；宿主 OS 可复现构建；设备 PASS 写进 `02-TRACKING.md` §3。

**非目标（至少到 v1）**

- 商店（Epic / GOG / Amazon）、XR / 眼镜 / 手部追踪、KDE 桌面、Flatpak、Decky。
- x86-64 Steam 客户端（VAC）；x86 Linux 原生游戏放到 Phase 6。
- 改动 Wine 引擎的出画、Box64、Proton-wine 分支。

## 3. 已确认的事实

| 事实 | 状态 | 来源 |
|---|---|---|
| ARM64 Proton = Steam app 4427310，自带 arm64ec Wine、FEX DLL（`libarm64ecfex.dll` / `libwow64fex.dll`）、DXVK、vkd3d-proton；toolmanifest 要求 SteamLinuxRuntime_4-arm64（app 4185400） | 已验证（DroidDeck 代码与测试夹具） | `DroidDeck/tools/linuxfs/overlay/usr/local/bin/steam-compatibility`、`ComponentsManager.kt` |
| x86 Linux 游戏用 Steam 的 FEX 工具（app 3127680）+ sniper（app 1628350） | 已验证（DroidDeck 依赖它们） | `droiddeck-fex` |
| Steam 不管宿主 GPU 驱动；Debian / steamrt4 的 Mesa 无 freedreno KGSL | 已验证 | Devin run 1 `evidence/07a-*` |
| Valve 公开 arm64 runtime：Platform runtime / sysroot、**SDK sysroot（928 包，gcc 14.2、clang 19，头文件与运行库同版本）**；SteamLinuxRuntime_4-arm64 包 = pressure-vessel + `steamrt4_platform_<BUILD_ID>/files`（427 MB，xz 90 MB） | 已验证 | `repo.steampowered.com/steamrt4/images/4.0.20261007.263973/` |
| `latest-public-beta/` 目录在不同 CDN 节点不一致，要用带 BUILD_ID 的目录 | 已验证 | Devin run 1 报告 |
| SteamOS main：gamescope 3.16.30（PKGBUILD 用 `wlroots.git#tag=0.20.2`、动态链接）、glibc 2.43、wayland 1.25、libdrm 2.4.134、xkbcommon 1.13.2、Mesa 26.1.5、Xwayland 24.1.13；公开仓库只有 x86_64 | 已验证 | `steamdeck-packages.steamos.cloud/archlinux-mirror/` |
| steamrt4 是 glibc 2.41 / wayland 1.23 / libdrm 2.4.124 / xkbcommon 1.7 | 已验证 | Devin run 2 |
| Mesa 26.2.4 Turnip（`-Dfreedreno-kmds=kgsl,msm`；只写 `kgsl` 会关掉 GBM/EGL）与 gamescope 3.16.29 + DroidDeck 11 个补丁可构建 | 已验证（Docker） | Devin run 2 `evidence/2-02*`、`2-03*` |
| arm64 Steam 客户端在 Docker 里（直接 / Termux proot 下）自更新并显示登录 + 二维码 | 已验证，但关 GPU、Xvfb、无 gamescope | Devin run 2 `evidence/2-04-steam-ui.png` |
| pressure-vessel 读 `PRESSURE_VESSEL_BWRAP`、`PRESSURE_VESSEL_COPY_RUNTIME`、`PRESSURE_VESSEL_GRAPHICS_PROVIDER`、`PRESSURE_VESSEL_EMULATOR`；每次启动在 `var/tmp-XXXXXX` 用硬链接造可写副本，空闲时 GC | 推断（二进制字符串） | `pressure-vessel-wrap` |
| `_v2-entry-point` 有仿真分支：设了 `STEAM_COMPAT_MACHINE_ARCHITECTURE` / `STEAM_COMPAT_EMULATOR` / `STEAM_COMPAT_GRAPHICS_PROVIDER` 时用 arm64 pressure-vessel 跑 x86 runtime | 推断（脚本） | `_v2-entry-point` |
| Android app 不允许建硬链接（`linkat` → `EACCES`） | 已验证（DroidDeck proot 补丁 0012 为此存在） | `DroidDeck/tools/proot/PATCHES.md` |
| targetSdk ≥ 30：`execute_no_trans` on `app_data_file` 被 neverallow；`execute` / `map execute` 允许 | 已验证（本仓库） | [`07`](07-TARGETSDK-SELINUX.md) |
| DroidDeck 的 `droiddeck-bwrap` 支持 bind / fd-bind / `--file` / `--bind-data` / `--symlink` / `--dir` / `--tmpfs` / `--proc` / `--dev` / `--info-fd` / `--json-status-fd` / `--sync-fd`，不支持 `--overlay`；`BwrapSpawner` 每个沙箱由 app 起一个新 proot | 已验证（读代码） | `droiddeck-bwrap`、`BwrapSpawner.kt` |
| proot 每次路径类 syscall 15–60 µs、单 tracer 7.5–9.5 万次/s；进程内快速路径 `stat` 0.6 µs | 已验证（DroidDeck，SM8850） | `DroidDeck/docs/development/proot-performance.md` |

## 4. 分层与归属

```
┌──────────────────────────────────────────────────────────────┐
│ 游戏（Windows 经 Proton；Linux 原生）                          │  Steam
│ 运行在 SteamLinuxRuntime_4-arm64 / sniper 容器里               │  （下载、更新、选 runtime）
│   └─ pressure-vessel（Valve 原版）→ bwrap shim → 新 proot     │
├──────────────────────────────────────────────────────────────┤
│ Steam 客户端 steamrtarm64（自带库 + 宿主库）                   │  Steam
├──────────────────────────────────────────────────────────────┤
│ 宿主 OS（hostfs）：Arch Linux ARM + 我们的包                   │  我们（manifest pin）
│   gamescope 3.16.30+补丁、Mesa Turnip KGSL、Xwayland、         │
│   dbus、session 脚本（照 Valve gamescope-session）、设备层      │
├──────────────────────────────────────────────────────────────┤
│ Amphora app：Wayland 合成器 → SurfaceControl、输入、音频、     │  我们（APK）
│ proot + loader、bwrap 服务、进程监管                           │
└──────────────────────────────────────────────────────────────┘
```

| 组件 | 谁管 | 更新渠道 |
|---|---|---|
| Steam 客户端、ARM64 Proton、SLR4-arm64、sniper、scout、FEX 工具、Proton 内的 DXVK / vkd3d | Steam | Steam 自身 |
| hostfs（gamescope、Mesa、Xwayland、dbus、session、设备层、补的库） | 我们 | imagefs 式发布 → `content_manifest` |
| proot、loader、快速路径、bwrap 服务、合成器、输入、音频 | 我们 | APK |
| Turnip 驱动版本切换 | 我们 | manifest 组件（hostfs 内或单独 pin） |

**不再做的事**（相对 DroidDeck）：删 Proton 的 `require_tool_appid`、给 Proton 准备 Python 和库、默认覆盖 Proton 里的 DLL、把 sniper 当 FEX rootfs 的自制方案（若 §5.6 的 Valve 仿真路径可用）。

## 5. 关键机制

### 5.1 执行环境：proot

- 基线：termux/proot `4dba3afb` + DroidDeck 携带的补丁 0001–0016（WinNative 移植 + DroidDeck 自加，见其 `PATCHES.md`），NDK 构建，talloc 静态链入。
- **exec 位置**：proot 每次 guest exec 都会 `execve` 自己的 loader；targetSdk 36 下 app-data 里的文件不能 `execve`，所以 `libproot.so` 与 `libproot-loader.so` 必须放 APK `jniLibs`（`nativeLibraryDir`，`apk_data_file`）。guest ELF 只被 loader `mmap(PROT_EXEC)`，`map execute` 允许。**[未验证]**：这条在 Amphora targetSdk 36 下成立（实验 S2）。
- loader 是静态 ELF，不能走 Amphora 的 `linker64` 路由；`libamphora-exec.so` 不参与 Linux 引擎。
- **一棵进程树一个 proot**：Steam 客户端树一个；每个 pressure-vessel 容器一个（由 bwrap 服务起）。避免单 tracer 瓶颈，也避免 proot 套 proot。
- 绑定表：v0 用命令行 `-b`；v1 改成共享内存表，供快速路径与 proot 共读（DroidDeck 文档第 5 项方案）。
- 快速路径（DroidDeck `tools/proot/fastpath` + 补丁 0014）放 Phase 5；它在 FEX 32 位模式下会 SIGILL，需按 DroidDeck 的规避方式处理。

### 5.2 容器：pressure-vessel + bwrap shim

路线按忠实度排序，S1 实验决定走哪条：

1. **原版 pressure-vessel + shim**（首选）。Steam 照常走 `_v2-entry-point`；`PRESSURE_VESSEL_BWRAP` 指向 shim；shim 把 bwrap 参数发给 app，app 起新 proot（root = runtime 的 `files`，binds 按参数）。
   - Android 不准硬链接 → 优先 `PRESSURE_VESSEL_COPY_RUNTIME=0`（只产生 bind，最贴 proot）。**风险**：二进制里有 "Cannot set up a runtime inside Flatpak without making a mutable copy"，说明部分场景强制要可写副本；若 steamrt4 必须副本，硬链接失败时的行为（复制 427 MB？报错？）要实测。
   - 用户命名空间探测（`steam-runtime-check-requirements`）要过；DroidDeck 已为 x86 客户端伪造过这个探测。
   - 游戏进程不是 Steam 进程树的后代（是 app 起的新 proot）；由替身进程转发退出码与信号，需确认 Steam 的 `use_tool_subprocess_reaper` 接受。
   - 两个 proot 之间共享状态：SysV IPC / futex_waitv / ntsync 的 preload 仿真（DroidDeck `tools/linuxfs/preload/`）靠共享文件，路径要两边都 bind。
2. **自写轻量容器**（回退）：每个游戏一个 proot，root = runtime `files`，自己把 Mesa 及依赖按"新者胜"放进 `/overrides`。等于重写 pressure-vessel 的图形注入，工作量大。
3. **单根直跑**（再回退）：宿主就是 runtime，删 `require_tool_appid`（DroidDeck 现状）。只作临时手段。

### 5.3 出画

- **合成器**：移植 DroidDeck `app/src/main/cpp/waylandcomp` 的核心（`compositor.c`、`wl_dmabuf.c`、`vk_present.c`、`sc_layer.c`、`ahb_swapchain.c`、协议生成物），HDR / 插帧 / Cardboard / 特效链不进 v0。JNI 面照 Amphora 规矩做版本化。
- **v0 路径**：game → gamescope（Wayland 后端，自带 Xwayland）→ linux-dmabuf → 合成器 import → 一次 blit → 自己的 swapchain → SurfaceFlinger。DroidDeck 量过这一拷贝在 Adreno 750 上 Vulkan/D3D12 比 X11 慢约 20%。
- **v1 零拷贝**：缓冲必须一开始就是 gralloc AHB（原始 dma-buf 变不成 AHB，见 DroidDeck `ZERO_COPY_SPIKE.md`）。方案：宿主分配 AHB（可复用 Amphora `05` 的 BufferQueue 槽位模型、generation、双向 SYNC_FD fence），把 dma-buf fd + stride + modifier 经 socket 发给 guest；guest 侧 Mesa Wayland WSI / gbm 补丁改为导入这些缓冲（DroidDeck 估算 WSI 补丁约 300 行）。合成器认出自家缓冲就直接上 SurfaceControl 层，否则回退 blit。gamescope 的 subsurface 直通映射为子 SurfaceControl 层。**[未验证]**：gralloc 与 guest Turnip 对宿主分配缓冲的 UBWC 布局一致。
- **Turnip**：Mesa 26.2.x，带 Armada 的 3 个补丁（sparse sync、A830 chip id、ir3 bindless UBO）；宿主与合成器两侧同源（布局一致是正确性要求，不只是整洁）。
- **gamescope**：3.16.30（SteamOS pin），DroidDeck 11 个补丁 rebase；`-Dsdl2_backend=disabled`；在 Arch 宿主上动态链接，无需 Debian 时代的静态副本。

### 5.4 输入

- 触屏 / 键盘：合成器的 `wl_seat`（移植 DroidDeck 的 touch / pointer / text-input）。
- 手柄：Steam Input 要 evdev / hidraw。DroidDeck 用 `fakeinput`（mmap 环形缓冲 + `inputudev.c` / `udevmon.c` 伪 udev）给 guest 造设备；v0 直接移植。
- Steam 虚拟手柄（Steam Deck 布局）放 Phase 4 末。

### 5.5 音频

- Amphora 已在 APK 里带 PulseAudio 13 + AAudio sink（`pulseaudio.tzst`，见 [`03`](../03-ASSET-MANIFEST.md) 例外条），Wine 引擎在用。Linux 引擎复用同一服务，guest 的 libpulse 连 `native` socket（bind 进 proot 与容器）。
- pipewire-pulse 不进 v0。

### 5.6 x86（Phase 6）

- 先试 Valve 的路径：Steam FEX 工具 + sniper，由 `_v2-entry-point` 的仿真分支跑 arm64 pressure-vessel。要先确认 arm64 客户端会设置 `STEAM_COMPAT_EMULATOR` 等变量。
- 不行再参考 DroidDeck "direct" 方案（FEX rootfs = sniper platform）。

### 5.7 宿主 ↔ guest 协议

- 一个 socket，版本化 schema（JSON 或 protobuf 均可），Kotlin 与 guest agent 两侧由同一 schema 生成类型；替代 DroidDeck 的 41 个 `BL_*` 环境变量与 `FileObserver` 文件约定。
- guest agent 用 Rust 静态二进制（session 启动、Steam 启动参数、状态事件、日志收集），不写大段 bash / Python。

## 6. 模块、仓库与构建

### 6.1 Amphora 模块

```
:feature:steam            Steam 入口、会话 UI
:core:session             新：SessionEngine 契约（prepare / launch / stop / surfaces / input）
:core:surface             抽取：SurfaceControl 层、AHB 槽位、fence（Wine 引擎与合成器共用）
:core:engine              现有 Wine 引擎，实现 SessionEngine，行为不变
:core:engine-linux        新：proot 启动、bwrap 服务、hostfs 安装、guest 协议
:core:wayland             新：合成器 native（Wayland server → :core:surface）
:core:content             复用：manifest 新增 hostfs 等组件
```

- 依赖方向照 [`01`](../01-ARCHITECTURE.md) §2：feature → engine → core 契约；两个引擎互不依赖。
- 会话仍在独立进程；Linux 引擎用自己的进程（如 `:linux`），不与 `:session` 混用，便于各自崩溃隔离。
- `:core:session` 抽取先做，且必须让 Wine 引擎按 `02` §4 测试分层回归 PASS 后才合入。

### 6.2 proot / loader 的构建与交付

- imagefs 加 L1 元素（如 `l1/proot.bst`）：termux proot + 补丁 + talloc，NDK r29，产出 `libproot.so` / `libproot-loader.so`。
- 因为必须在 `jniLibs` 里，它们和 PulseAudio 一样是"随 APK 交付"的例外；APK 构建时按 pin 拉取 imagefs 产物并校验 SHA，不手拷 `.so`。

### 6.3 hostfs（新仓库，建议 `amphora-dev/hostfs`）

- 基底：Arch Linux ARM（与 SteamOS 同源，SteamOS 的 PKGBUILD 可几乎原样用）。
- 我们的包：gamescope（SteamOS PKGBUILD + 补丁）、mesa-turnip-kgsl、xwayland（如需补丁）、session 与设备层、guest agent、preload。其余用 ALARM 二进制包。
- **可复现**：ALARM 没有官方历史归档（**[未验证]**，需确认），所以每次发布把实际用到的包按 SHA 收进我们自己的 release / CAS，`repo-add` 成快照仓库，再 `pacstrap` 出根，打 `tar.zst`。
- 构建工具：容器脚本 + 按包内容哈希缓存（Armada `packages/` 模式）。BuildStream 适合从源码建全图（imagefs 的 bionic 场景），不适合 pacman 二进制包解析，这里不用。
- 发布：照 imagefs 的 gate → fixed release → `bump-manifest`；`dev_pins.json` 覆盖照旧可用。
- 体积预期：DroidDeck 的 `linuxfs-r9` 是 3.3 GB 解包（含 KDE、Firefox、Python、Proton 依赖）；hostfs 去掉 Proton/游戏依赖与桌面后应明显更小，**[未验证]**，Phase 1 实测。

### 6.4 许可

- 移植 DroidDeck 代码（proot 补丁、preload、合成器、guest 脚本、fakeinput）需 GPL-3.0 兼容。Amphora 目前无 LICENSE，Phase 0 先定许可；同时核对 `:core:engine` 里 Winlator 衍生代码的原许可。
- 署名与来源写进各模块 README / PATCHES。

## 7. 阶段计划

每阶段有退出判据；真机 PASS 写 `02-TRACKING.md` §3，判据不达标不进下一阶段。

### Phase 0 · 门禁实验（设备优先）

| 实验 | 做法 | PASS 判据 | 失败时 |
|---|---|---|---|
| **S1 pressure-vessel 经 proot**（在 Y700 已装的 DroidDeck 里做，不写新代码） | 会话里设 `PRESSURE_VESSEL_BWRAP=/usr/local/bin/droiddeck-bwrap`；先 `SteamLinuxRuntime_4-arm64/_v2-entry-point --verb=run -- steam-runtime-system-info`，再保留 `require_tool_appid` 跑一款 Proton 游戏；copy / no-copy 两种；记录 bwrap 参数、启动耗时、FPS，对比 DroidDeck 直跑 | system-info 显示容器内图形栈 = 宿主 Turnip；游戏出画；启动额外耗时可接受（目标 < 5 s，待定） | 走 §5.2 路线 2；记下具体卡点 |
| **S2 proot @ targetSdk 36** | Amphora debug 构建加 `jniLibs` 里的 proot + loader，起 ALARM 根跑 `bash`、`python3 -c`、`ldconfig`，抓 AVC | 无 `execute_no_trans` 拒绝，命令正常退出 | Amphora 回 targetSdk 28（曾经在 28 跑过），或给 Linux 引擎单独 APK |
| **S3 Turnip KGSL 在 proot 下** | 同上环境跑 `vulkaninfo --summary`、`vkcube --wsi headless` | 枚举到 Adreno 830，渲染无 device lost | 查 KGSL 节点 bind、`kgslid.c` / `kgslpoll.c` 一类 preload 是否必需 |
| **S4 许可** | 定 Amphora LICENSE | 写入仓库 | — |

Docker 侧可并行：用 Valve SDK sysroot 重做 Mesa / preload 构建，去掉 Run 2 的 dpkg status 手改（仅为 runtime 侧产物）。

### Phase 1 · hostfs v0

- ALARM 基底 + gamescope 3.16.30（补丁 rebase）+ Mesa 26.2.x Turnip + session 脚本骨架；快照仓库；发布 + manifest pin。
- 退出：hostfs 由 `RemoteContentSource` 安装；Docker 里 hostfs + proot 跑 Steam 客户端到登录（复现 Run 2 结果，但这次在 Arch 宿主上）。

### Phase 2 · Linux 引擎骨架

- 抽 `:core:session`、`:core:surface`；`:core:engine-linux` 起 proot、跑 guest agent、收日志；bwrap 服务从 DroidDeck 移植。
- 退出：Wine 引擎回归 PASS（`02` §4 分层）；Linux 会话里 `vulkaninfo` 输出经协议回传到 app。

### Phase 3 · 出画 v0 + 输入 + 音频

- 合成器移植（blit 路径）；gamescope Wayland 后端；触屏 / 键盘；PulseAudio 复用。
- 退出：`vkcube` 经 gamescope 在 Y700 上屏，SF `totalFrames ≈ presents`；触屏可点；`paplay` 有声。

### Phase 4 · Steam

- Steam 客户端安装与更新（照 `droiddeck-steam-install` 的 CDN 清单逻辑，或 Steam bootstrap）；Big Picture 登录；fakeinput 手柄；Steam 自装 4427310 / 4185400；经 S1 选定的容器路线跑第一款 Proton 游戏。
- 退出：同一款游戏在 Amphora 与 DroidDeck 同机可比（FPS、首帧时间、崩溃率）。

### Phase 5 · 性能

- proot 快速路径与共享绑定表；零拷贝 AHB（§5.3 v1）；帧节奏（Choreographer → `wp_presentation`，`ASurfaceTransaction_setFrameRate`）。
- 退出：与 DroidDeck 同机持平或更好；零拷贝时合成器 blit 计数为 0。

### Phase 6 · 扩展（各自独立可选）

- x86 Linux 游戏（§5.6）、x86-64 客户端、HDR、插帧、Steam 虚拟手柄布局等，各自过门禁再做。

## 8. 风险与回退

| 风险 | 影响 | 早期信号 | 回退 |
|---|---|---|---|
| pressure-vessel 强制可写副本 + Android 禁硬链接 | 每次启动复制或直接失败 | S1 no-copy 失败 | 路线 2 / 3；或 proot 层把 `linkat` 模拟成复制并缓存 |
| pressure-vessel 启动在 ptrace 下太慢（大量扫描、生成 ld.so cache / locale） | 游戏启动多出数秒到数十秒 | S1 计时 | 预热缓存、`PRESSURE_VESSEL_GENERATE_LOCALES=0`、快速路径前移 |
| targetSdk 36 下 proot loader 不可用 | 整个引擎不可行 | S2 | targetSdk 28 |
| Steam 客户端 / Proton 更新破坏兼容 | 回归随时出现 | 夜间冒烟 | 夜间设备冒烟 + 客户端渠道固定 `steamdeck_publicbeta` |
| 零拷贝的 UBWC 布局不一致 | v1 性能目标落空 | Phase 5 首测 | 保留 blit（约 20% 代价，DroidDeck 量值） |
| ALARM 无历史归档 | hostfs 不可复现 | Phase 1 | 自建快照仓库（已列为默认做法） |
| 工作量被低估 | 进度失控 | 每阶段 | 每阶段只交退出判据；非目标严格不做 |

## 9. 与 Wine 引擎的关系

- 共享：`:core:content`（manifest / dev pin / 校验下载）、`:core:surface`、PulseAudio 服务、CI 与发布、HUD 采集。
- 不共享：libc（bionic vs glibc）、x86 方案（Box64 vs FEX）、Wine 来源（自编 Proton-wine vs Valve ARM64 Proton）、出画入口（wineandroid.drv 每 HWND 一层 vs Wayland 合成器）。
- 交叉点：`02` §2 的 Phase 5「ARM64EC 评估」与 Steam 引擎用的 Valve ARM64 Proton 是同一技术（arm64ec + FEX DLL）；S1 / Phase 4 的测量可直接作为该评估的输入。

## 10. 待用户拍板

1. 认可"SteamOS 式分层 + 原版 pressure-vessel"为首选，单根直跑只作回退？
2. S1 在 Y700 上用 DroidDeck 做（需要在设备上装 / 改 DroidDeck）？
3. hostfs 基底选 Arch Linux ARM（SteamOS 同源）而非 Debian？
4. Amphora 许可定 GPL-3.0？
5. Linux 引擎与 Wine 引擎同一个 APK（需 S2 通过）？

## 11. 证据索引（本机，未入库）

- Devin run 1 / 2：`~/.cache/devin-runs/20261018T114349Z-steamrt4-arm64-base/`（`report.md`、`report-2.md`、`evidence/`、`work/recipe/`）。
- SteamOS gamescope 源码包：`steamdeck-packages.steamos.cloud/archlinux-mirror/sources/jupiter-main/gamescope-3.16.30-2.src.tar.gz`。
- SLR4-arm64：`repo.steampowered.com/steamrt4/images/4.0.20261007.263973/SteamLinuxRuntime_4-arm64.tar.xz`。
- DroidDeck 参考点：`docs/development/proot-performance.md`、`app/src/main/cpp/waylandcomp/ZERO_COPY_SPIKE.md`、`docs/development/x86-64-steam-client.md`、`tools/proot/PATCHES.md`、`app/src/main/java/com/droiddeck/launcher/runtime/BwrapSpawner.kt`。
