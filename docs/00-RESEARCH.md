# 00 - 研究基础 (Research Basis)

> 整理自对 `WinNative` (fork of Winlator Bionic + Pluvia) 及周边生态的深度分析。
> 目的: 为 Amphora 项目提供事实依据。所有结论标注来源/验证方式。
> 最后更新: 2026-07-10。

---

## 1. 参考项目: WinNative 概况

- **仓库**: 本地 `/Users/sky/co/github/WinNative`（fork 自 `MaxsTechReview/WinNative`，源自 `WinNative-Emu/WinNative`）
- **定位**: Android 上的 Windows (x86_64) 模拟器，融合 Winlator Bionic + Pluvia
- **架构路线**: **Bionic**（Wine 链接 Android bionic libc，非 glibc）
- **构建**: Android Studio + JDK 17 + NDK `27.3.13750724` + CMake；产物 arm64-v8a only
- **CI**: `.github/workflows/` 只编译 native (cpp/) + 把预打包的 assets 塞进 APK，**不重新生成 assets 压缩包**

---

## 2. 代码结构拆解 (spine vs extra)

总量: **183,527 行 Kotlin/Java** + **~64,000 行 C/C++/Rust**。

### 2.1 Kotlin/Java (183,527 行)

| srcDir | 文件 | 行数 | 性质 |
|--------|------|------|------|
| `app` | 16 | 17,904 | 应用壳 (UnifiedActivity 6000+ 行，混了 Steam) |
| `feature` | 227 | 91,694 | **特性层 (大部分可砍)** |
| `runtime` | 244 | 62,971 | **核心运行时 (骨架)** |
| `shared` | 53 | 10,702 | 通用工具/IO |
| `sharedmemory` | 4 | 195 | 共享内存 |

`feature/` 子模块（额外逻辑）:
| 模块 | 行数 | 性质 |
|------|------|------|
| `stores` (steam+epic+gog) | 48,991 | 商店/库集成（Steam 27k, Epic 11k, GOG 10k）|
| `settings` | 18,968 | 设置 UI（多驱动/多版本切换）|
| `sync` | 7,005 | 库同步 |
| `library` | 4,499 | 游戏库 |
| `steamcloudsync` | 4,017 | Steam 云存档 |
| `shortcuts` | 3,879 | 主屏快捷方式 |
| `setup` | 3,379 | 安装引导（含 .wcp 推荐下载）|
| `leaderboard` | 956 | Play Games 排行榜 |

`runtime/` 子模块（骨架）:
| 模块 | 文件 | 行数 | 说明 |
|------|------|------|------|
| `display` | 163 | 39,631 | X-server 显示/进程启动（XServerDisplayActivity 9000+ 行，**混了 Steam 逻辑**）|
| `input` | 29 | 10,113 | 触屏->鼠标/键盘映射 |
| `wine` | 14 | 3,917 | Wine 版本/路径管理 |
| `container` | 6 | 2,773 | Wine prefix/容器管理 |
| `content` | 6 | 2,109 | .wcp 内容安装 |
| `system` | 10 | 2,031 | 系统 |
| `compat` | 9 | 1,475 | box64/FEX 启动 |
| `audio` | 7 | 922 | ALSA->Android 音频 |

### 2.2 C/C++/Rust native (~64,000 行)

| 模块 | 行数 | 性质 |
|------|------|------|
| **`proot`** | 13,297 | **骨架** - rootfs 隔离/挂载 |
| **`winlator`** | 8,814 | **骨架** - Vulkan X-server 渲染器 (`vk/vk_renderer.c` 是技术核心) |
| `wn-steam-client` | 22,716 | Steam 桥接 (可砍) |
| `wn-libsteamclient` | 7,916 | Steam (可砍) |
| `wn-steamapi-bridge` | 6,311 | Steam (可砍) |
| `wn-steam-bootstrap` | 1,872 | Steam (可砍) |
| `wn-steam-launcher` | 1,835 | Steam (可砍) |
| `steamwebhelper-preload` | 684 | Steam (可砍) |
| `patchelf` | 296 | **骨架** - ELF 路径修复 |
| `wn-refactor-size` | 87 | 窗口 resize (小特性) |
| `adrenotools` | (submodule) | GPU 驱动加载 |

**native 骨架 = `proot` + `winlator`(vk_renderer) + `patchelf` ≈ 22,400 行**；Steam 相关 ~40,000 行可砍。

---

## 3. 二进制资产清单与可复现性 (assets 544M)

### 3.1 死包/冗余 (~101M, 可直接删)

| 文件 | 大小 | 死因（已验证）|
|------|------|--------------|
| `proton-9.0-arm64ec.txz` | 62M | `R.array.wine_entries` 为空数组 -> `installWineFromAssets` 的提取循环不执行 -> 从不提取；0 引用 |
| `proton-9.0-arm64ec_container_pattern.tzst` | 9.8M | 对应 proton-9.0 从不安装，版本化 pattern 匹配不上；0 引用 |
| `proton-9.0-x86_64_container_pattern.tzst` | 8.3M | x86_64 txz 被 `.gitignore` 忽略根本没打包；0 引用 |
| `graphics_driver/virgl-23.1.9.tzst` | 6.1M | 0 代码/json/xml 引用，旧 glibc 路径 Mesa VirGL |
| `graphics_driver/zink-22.2.5.tzst` | 6.8M | 0 引用，旧 glibc 路径 Mesa Zink |
| `container_pattern.tzst` (base) | 7.9M | 代码只构造 `<version>_container_pattern.tzst` + 兜底用 `_common`，裸文件无加载路径 |
| `ddraw.tzst` 重复 | 0.16M | `dxwrapper/cnc-ddraw-6.6/` 与 `wincomponents/` 各一份 |

> **关键发现**: WinNative 已改用 .wcp 运行时下载 Wine/Proton（`wine-9.20-x86_64.wcp`、`Proton-10-*`），但旧"捆绑默认 Wine"机制（`wine_entries`）清空后没删对应的 .txz/.tzst，遗留 80M 死重。

### 3.2 可疑冗余 (~33M, 需团队决策)

- `steampipe/` 顶层 (33M) vs `wnsteam/steampipe/` -- 两条 Steam 代码路径（过渡期都打包）。
  - 顶层 `steam_api64.dll` **17M**（正常 ~300KB，大 ~50 倍），`steam_api.dll` 15M -- 疑似未 strip 肥大构建。
  - 顶层由 `XServerDisplayActivity.java` 用（旧路径）；`wnsteam/` 由 `WnSteamAssetsInstaller.kt` 用（新 bionic 路径）。

### 3.3 可复现资产

| 资产 | 大小 | 来源/可复现性 |
|------|------|--------------|
| `imagefs.tzst` | 191M | **Bionic** rootfs（验证: `/system/bin/linker64` + `libc.so`）。**已由 winlator-imagefs 项目证明可源码构建**（见 §5）|
| `experimental-drm.tzst` | 15M | gbe_fork，`tools/update-gbe-fork.sh` 确定性复现（pinned + SHA256 校验）|
| `graphics_driver/` (wrapper/extra_libs/zink_dlls 等) | 35M | Mesa/Turnip，`WinNative-Emu/Drivers` 开放构建 |
| `layers.tzst` | 4.2M | Khronos Vulkan-ValidationLayers |
| `pulseaudio.tzst` | 78K | `audio_plugin/` CMake 源码构建 |
| `dxwrapper/d8vk`, `cnc-ddraw` | 2M | DXVK / FunkyFr3sh/cnc-ddraw 开源 |
| `ddrawrapper/cnc-ddraw`, `nglide` | 1.4M | 开源 |
| native .so (cpp/) | - | 本仓库 CMake 源码编译 |
| 运行时 .wcp (DXVK/VKD3D/Box64/FEX/D7VK/Proton) | 下载 | 开放 CI 构建（见 §4）|

### 3.4 不可复现资产（法律结构性，非技术）

| 资产 | 大小 | 原因 |
|------|------|------|
| Valve Steam 客户端 (`wnsteam/bionic/`, `steampipe/`) | ~91M | `steam.exe`/`steamservice`/`lsteamclient` 闭源商业软件。Goldberg 只模拟 steam_api **接口**，非客户端本体 |
| 微软可再发行 DLL (`wincomponents/`) | ~38M | d3dcompiler/d3dx9/XAudio/DirectShow/WM 解码器/VC++ 运行时。闭源，只能从微软 redist 抽取（winetricks 做法），无法源码构建 |
| `extras.tzst` | 2M | Steamless(.NET)/7-Zip 等 Windows 二进制 |

---

## 4. 组件生态（运行时 .wcp 下载源）

代码实际引用 `nicholasx417/WinNative-Components`（**非** `WinNative-Emu/Components`）。`ContentsManager.java:29`:
```
https://raw.githubusercontent.com/nicholasx417/WinNative-Components/refs/heads/main/contents.json
```

### 4.1 `.wcp` 格式
= `tar -cJf`（**xz 压缩 tar**）+ `profile.json` 清单（type/versionName/files[] 映射 `${system32}`/`${syswow64}`）。

### 4.2 nicholasx417/WinNative-Components（代码实际用）
- 1937 commits, 149 releases, 作者 Xnick417x（第三方）。README 声明工作流"专有"但公开可读。
- **10 个工作流**（全从上游源码构建）+ 4 patch:
  - Box64 + WOWBox64（`ptitSeb/box64`，cmake `-DANDROID=1 -DARM_DYNAREC=1`，`pipetto-controller-fix.patch`）
  - DXVK GPLAsync / Sarek / Pre-Regress（`doitsujin/dxvk` + `dxvk-gplasync-master.patch`，meson）
  - VKD3D（`HansKristian-Work/vkd3d-proton`，meson + llvm-mingw）
  - FEXCore + FEXCore-Unix（`FEX-Emu/FEX`，cmake + mingw）
  - D7VK（`WinterSnowfall/d7vk`，meson，D3D7->Vulkan）
- **Wine / Proton 无构建工作流** -- Wine 是社区构建（`ref4ik` 等），Proton 见 §4.3。

### 4.3 WinNative-Emu/proton-wine（Proton 源）
- fork 自 `ValveSoftware/wine`，默认分支 `proton_11.0`，11198 文件（完整 wine 源码）。
- **开放**: `.github/workflows/build-proton-sdk28.yml` + `build-proton-sdk35.yml`，`build-scripts/*.sh`，**46 个开放补丁** (`android/patches/`: esync/fsync/NTSync/winex11/winepulse/nsiproxy/wow64/...)。
- 产物: `proton-11.0-1-{x86_64,arm64ec}.wcp`（对应 nicholasx417 的 `Proton-11.0-1-*`）。
- **两个构建依赖**（均可开源复现）:
  - **termuxfs** = Termux 包捆绑（`termux/termux-packages` 开源构建系统），提供 freetype/pulseaudio/SDL2/fontconfig 等 bionic 依赖。路径 `/data/data/com.termux/files/usr`。
  - **prefixPack.txz** = `wineboot` 生成的 Wine prefix，可从构建好的 Wine 重生。

### 4.4 WinNative-Emu/Drivers（Mesa/Turnip 源）
- Turnip (Mesa freedreno Vulkan) 驱动，`build_wn_turnip.sh` 从 Mesa main 构建 + `patches/`，开放。
- 开发在 `maxjivi05/Drivers` fork，PR 到 `WinNative-Emu/Drivers`。

### 4.5 其他下载源（代码硬编码）
- `huggingface.co/datasets/Xnick417x/WN-Components`（镜像，`ComponentInstallerSheet.kt:73`）
- `github.com/maxjivi05/Components`（Steam 组件，`SteamService.kt:342`）
- `winnative.dev/Downloads/`（App 更新）

---

## 5. 已有资产: winlator-imagefs (我们的 rootfs 构建)

- **目录**: `/Users/sky/co/github/winlator-imagefs/`
- **仓库**: `https://cnb.cool/atowerlight/winlator-imagefs`
- **目标**: NDK r29 (clang 21) 交叉编译 41 包，`aarch64-linux-android26`，**Bionic libc** (`/system/bin/linker64`)
- **产物**: `imagefs.txz` (17M) + sha256
- **状态**: 7 轮 CI 全绿，与官方 Bionic imagefs 的 SONAME/NEEDED/ELF **全部对齐**验证
- **包拓扑** (41 包):
  - Tier1: zlib/libffi/libexpat/libpng/brotli
  - Tier2: pcre2/freetype/libiconv/libxml2
  - Tier3: fontconfig/harfbuzz/glib
  - Tier3.5: android-sysvshm (libx11 前)
  - Tier4: xorgproto/libxcb/xtrans/libx11 + 10 X 扩展/libdrm/vulkan-headers/vulkan-loader/libglvnd
  - Tier5: alsa-lib/libsndfile/libltdl/stub/pulseaudio/alsa-android-aserver
  - Tier6: openssl/curl
  - Tier7: sdl2
  - Tier8: android-spawn/android-sysv-semaphore (Bionic stub)
  - Tier9: box64
- **关键 Bionic 适配**: SysV 共享内存(ashmem stub)/futex 缺失(libxshmfence --disable-futex)/GLX 无用(libglvnd --glx=disabled)/posix_spawn+semaphore stub/libltdl stub
- **依赖顺序约束**: sysvshm<x11, xtrans<x11, openssl<pulseaudio, libXdmcp<libxcb

### 5.1 WinNative imagefs(191M) vs 我们的构建(17M)
- **同架构**（均 Bionic, `/system/bin/linker64`）-- 已验证。
- WinNative 的是**超集**（10892 条目，多了 bzip2/xz/zstd/gnutls/nettle/glib 工具/wine 字体等 userland）。
- 差距是**包数量**非架构差异 -> 用同一 `build-all.sh` + `packages/*.sh` 体系增量扩展即可，无原理障碍。

---

## 6. 关键技术组件

| 组件 | 作用 | 位置/来源 |
|------|------|----------|
| **vk_renderer** | 把 Xvfb framebuffer 用 Vulkan 渲染到 Android Surface -- **技术核心** | WinNative `cpp/winlator/vk/vk_renderer.c` (~9k 行) |
| proot | rootfs 隔离/挂载 | `cpp/proot/` (13k 行) |
| box64 | x86_64 -> ARM64 模拟 | 在 rootfs 内 (imagefs Tier9) |
| Wine/Proton | Windows 兼容层 | .wcp 下载 / proton-wine fork 源码 |
| Turnip | Mesa freedreno Vulkan 驱动 | `graphics_driver/extra_libs.tzst` (libvulkan_freedreno.so) / Drivers 仓库 |
| DXVK/VKD3D | D3D->Vulkan 翻译 | .wcp 下载 / Components 仓库 |
| Xvfb | 虚拟 X server | rootfs 内 + `xvfb-arm64/` |
| PulseAudio/ALSA-aserver | 音频 | rootfs + `audio_plugin/` |
| adrenotools | GPU 驱动加载 | `cpp/adrenotools` (submodule) |

### 6.1 两条架构路线
- **Bionic 路线**（WinNative/Winlator-Bionic/我们的 imagefs）: bionic libc + termuxfs + bionic Wine。性能好、原生 Android 库，但胶水复杂。
- **glibc 路线**（原版 brunodev85/winlator）: glibc rootfs + proot + box86/box64 + glibc Wine。简单、兼容性好，rootfs 更大。

---

## 7. 可复现性总评

- **技术上可复现的 (~76%)**: imagefs rootfs（已证明）、native 代码、全部图形翻译组件（DXVK/VKD3D/Box64/FEX/D7VK/Proton）、Mesa 驱动、gbe_fork、FFmpeg、Khronos layer。
- **技术上不可复现的 (0%)**: 无 -- 连最硬的 imagefs 都破了。
- **法律上不可复现的 (~24%)**: Valve Steam 客户端 + 微软 redist DLL -- 闭源商业软件，任何 Wine 项目共同的边界。

> 结论: WinNative 的 imagefs（最初判断为 glibc 黑盒）实为 Bionic 且可源码复现。Amphora 复用 winlator-imagefs 即可覆盖 rootfs 层。

---

## 8. 来源链接

- WinNative-Emu 组织: https://github.com/WinNative-Emu
- nicholasx417/WinNative-Components (代码实际用): https://github.com/nicholasx417/WinNative-Components
- WinNative-Emu/proton-wine (Proton 源): https://github.com/WinNative-Emu/proton-wine
- WinNative-Emu/Drivers (Turnip): https://github.com/WinNative-Emu/Drivers
- termux/termux-packages (termuxfs 源): https://github.com/termux/termux-packages
- 原版 brunodev85/winlator: https://github.com/brunodev85/winlator
- 本地 winlator-imagefs: `/Users/sky/co/github/winlator-imagefs/`（构建记录 `docs/BUILD-HISTORY.md`）
