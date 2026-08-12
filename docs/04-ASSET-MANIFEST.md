# 04 - 资产清单 / SHA 锁 (Asset Manifest)

> 资产研究、历史实测与当前分包决策。**生产 pin 的唯一真源是**
> `amphora-dev/content_manifest/content_manifest.json`；本文中的旧 WinNative 表格仅保留为来源研究，
> 不得用于决定设备实际下载版本。
> 最后校对: 2026-08-11。

当前生产基线：

| 组件 | 当前 pin | 压缩大小 |
|---|---|---:|
| rootfs | `imagefs.txz` v44（xz） | 12,161,984 B |
| Wine | `Proton-11.0-d12a5634a-x86_64.wcp` | 66,328,030 B |
| Box64 | `Box64-0.4.5-0db8df775.wcp` | 2,699,688 B |
| DXVK | `Dxvk-3.0.2-gplasync-6b20f622a.wcp` | 8,048,148 B |
| VKD3D | `Vkd3d-3.0.1-3b10bd7a7.wcp` | 3,276,904 B |
| Vulkan wrapper | `wrapper-7eae6442f.tzst` | 670,350 B |

文件名、大小、SHA 和 URL 都必须从 manifest 读取；自动发布可以在不修改本文的情况下更新 pin。
PulseAudio 是例外，不属于远程 manifest：`pulseaudio.tzst`（78,340 B）与约 2.03 MB
PA13 Android JNI 依赖随 APK 固定交付，避免 Wine 客户端、守护进程和模块跨版本漂移。

> **发布联动状态（2026-08-11）**：应用与 imagefs 功能分支已完成 Pulse 代码及
> `winepulse.so`/`.drv` 构建；文首当前生产 Proton pin 仍是联动前产物。manifest
> 发布新 WCP 前，运行时完整性检查会把 Pulse 请求安全回退到 ALSA。

---

## 0. 架构定性 (关键)

WinNative (amphora 移植源) 属 **Pipetto-crypto `winlator_bionic` 血脉**, rootfs 是 **Bionic libc** (非 glibc):
- 所有 ELF 链接 `/system/bin/linker64`, `libc.so -> /system/lib64/libc.so` (无 `.so.6` 后缀)
- amphora 移植的 `com.winlator.cmod` 内核期望的就是这套 Bionic rootfs -- **资产与内核兼容** ✅
- 真机验证设备: Lenovo TB322FC, arm64-v8a, API 36, **Adreno 830** (Turnip 驱动目标 GPU)

**当前 rootfs 压缩格式**：`imagefs.txz`（tar + xz）。`ImageFsRootfsInstaller` 按 manifest
的 `compression=xz` 提取。WinNative 的 `imagefs.tzst`（约 200 MB）只是移植研究基线，
已经退出 Amphora 默认运行路径。

---

## 0.5 分类总览 (按落地根与安装机制)

> 2026-07-29 实测归类。下面各节按 WinNative 的 assets 目录组织（来源视角），本节按
> **解压后落到哪个根** + **谁负责安装** 组织（行为视角）。后者才决定覆盖域、冲突范围和
> 更新代价。体积为实测值，条目数为 `tar -tf` 计数。

### A. Linux rootfs 层 — 落 imagefs 根，`usr/` 布局

多个包可叠加到**同一个根**，因此是共享覆盖域、**提取顺序敏感**。
其中 **`wrapper.tzst` 虽落同一根，但版本通道独立**（见 §0.6）——换 wrapper/hooks **不**重打 imagefs。

| 资产 | 压缩 | 大小 | 条目 | 内容 | 安装者 | 更新通道 |
|---|---|---|---|---|---|---|
| `imagefs.txz` | xz | 12.2 MB | 以当前 Release 为准 | 自建精简 rootfs 本体 | `RootfsInstaller` | **imagefs**（生产基线） |
| `extra_libs.tzst` | zstd | 21.1 MB | 14 | `usr/lib` 的 Mesa `libGL`+`libglapi` / Turnip / vkBasalt / bcn_layer，`usr/share/vulkan` 的 ICD + 隐式层 JSON | — | ⛔ **已废止**（2026-08-01）；GL 改由 imagefs 自建（§0.6） |
| `layers.tzst` | zstd | 4.4 MB | 3 | `usr/lib/libVkLayer_khronos_validation.so` | `TarCompressorUtils` | **可选调试**（§0.6） |
| `wrapper.tzst`（WinNative 历史样本） | zstd | 3.8 MB | 12 | `usr/lib` 的 `libadrenotools` + `libvulkan_wrapper` + 4 个 hook，`usr/share/vulkan/icd.d/wrapper_icd.aarch64.json` | `TarCompressorUtils` | 已由当前约 0.67 MB 自建 pin 替代，见 §0.6 |

### B. 模拟器 / Wine 运行时 — WCP，落 imagefs

| 资产 | 压缩 | 大小 | 内容 |
|---|---|---|---|
| `Proton-11.0-d12a5634a-x86_64.wcp` | 以 profile 为准 | 66.3 MB | `bin/` `lib/` `share/` `prefixPack.txz` `profile.json` |
| `Box64-0.4.5-0db8df775.wcp` | xz | 2.7 MB | `box64` + `profile.json` |

### C. DirectX 翻译层 — WCP，但内容是 Windows DLL，落容器 `system32`/`syswow64`

| 资产 | 压缩 | 大小 | 提供 |
|---|---|---|---|
| `Dxvk-3.0.2-gplasync-6b20f622a.wcp` | xz | 8.0 MB | `d3d8` `d3d9` `d3d10core` `d3d11` `dxgi` |
| `Vkd3d-3.0.1-3b10bd7a7.wcp` | xz | 3.3 MB | `d3d12` `d3d12core` |

### D. 微软可再发行组件 — tzst，与 C 同构但无 `profile.json`

按 `wincomponents.json` 选装。全部是 `system32/` + `syswow64/` 的 DLL 覆盖。

| 资产 | 大小 | DLL/EXE 数 | 提供 |
|---|---|---|---|
| `direct3d.tzst` | 31.2 MB | 98 | `d3dx9_*` `d3dcompiler_*` `d3dx10_*` `d3dx11_*` `d3dcsx_*` |
| `xaudio.tzst` | 2.3 MB | 82 | `xaudio2_*` `xactengine*` `x3daudio1_*` `xapofx1_*` |
| `directshow.tzst` | 2.2 MB | 12 | `quartz` `amstream` `qasf` `qcap` `qedit` `qdvd` |
| `vcrun2010.tzst` | 1.0 MB | 8 | `msvcr100` `msvcp100` `atl100` `vcomp100` |
| `directmusic.tzst` | 0.34 MB | 10 | `dmusic` `dmsynth` `dmband` `dmime` … |
| `directplay.tzst` | 0.43 MB | 8 | `dplayx` `dpnet` `dpwsockx` `dpnsvr.exe` … |
| `directsound.tzst` | 0.43 MB | 2 | `dsound` |

### E. DirectDraw / Glide 包装器 — tzst，落容器 `syswow64`

| 资产 | 大小 | 提供 | 备注 |
|---|---|---|---|
| `dd7to9.tzst` | 3.0 MB | `ddraw.dll` + `dxwrapper.dll` + `.ini` | ⚠️ DirectDraw 三选一 |
| `d7vk.zip` | 以 manifest 为准 | 上游 x32 `ddraw.dll`，D3D3–7 → Vulkan | ⚠️ DirectDraw 三选一 |
| `nglide.tzst` | 1.2 MB | `glide` `glide2x` `glide3x` `3DfxSpl*` | Glide，不与 DirectDraw 三选一冲突 |
| `cnc-ddraw.tzst` | 0.17 MB | `ddraw.dll`；APK 随附官方完整 `ddraw.ini`（全局默认改为 D3D9，保留游戏 preset） | ⚠️ DirectDraw 三选一 |

### F. 容器模板 — 落容器目录，**每个容器一份**

> 历史 WinNative 样本；Amphora 当前已废止该包，prefix 只从 Proton
> `prefixPack.txz` 生成，字体改为共享 `fonts.tzst`。

| 资产 | 大小 | 条目 | 内容 |
|---|---|---|---|
| `container_pattern_common.tzst` | 41.6 MB | 102 | `home/xuser/.wine`（prefix 模板）|

体积几乎全是 CJK 字体：`msyh.ttc` 19.7 MB、`08源柔ゴシック` 11.6 MB、`SimHei` 9.8 MB、
`PMingLiU` 8.6 MB、`SourceHanSansCN-Regular.otf` 8.3 MB、`07Yasashisa` 4.7 MB、
`方正粗黑宋简体` 2.8 MB，解压后约 65 MB。另含一份内置 `syswow64/cnc-ddraw`（4.9 MB）。

### G. 可选 GPU 驱动包 — 落 `filesDir/contents/adrenotools/<id>/`

| 资产 | 大小 | 内容 |
|---|---|---|
| `WN-Turnip-1.06-b_Axxx.zip` | 2.7 MB (zip) | `libvulkan_freedreno.so` 14.8 MB + `meta.json` |

### H. 裸配置 / 元数据 — 不压缩，直接拷

`gpu_cards.json`（26.5 KB，GPU 名 → vendor/device ID）·`startmenu.json`（2.1 KB）·
`wincomponents.json`（1.8 KB，D 类的选装清单）·`default.box64rc`（1.3 KB）

### I. APK 内音频运行时 — 不走远程 manifest

| 资产 | 大小 | 内容 | 安装/加载 |
|---|---:|---|---|
| `pulseaudio.tzst` | 78,340 B | ARM64 `pactl`、`module-native-protocol-unix`、`module-aaudio-sink`、`libprotocol-native` | `PulseAudioRuntimeSupport` 按版本 marker 原子安装到 `filesDir/pulseaudio-runtime` |
| `jniLibs/arm64-v8a` PA13 库 | 约 2.03 MB | `libpulseaudio`、`libpulse`、`libpulsecore/common-13.0`、`libsndfile`、`libltdl` | Android legacy JNI packaging 解压到 `nativeLibraryDir` |

这套资产只在用户选择 Pulse 后启动；默认及不支持的平台继续使用 imagefs 内 ALSA
aserver。它与远程 Proton WCP 中的 x86_64 `winepulse.so` 配套，但两边独立发布时必须
保持 `DT_NEEDED=libpulse.so` 和 PA13 ABI 契约。

### 归类暴露出的问题

**C 与 D 同构却用两套机制。** 两者解压后都是 `system32/` + `syswow64/` 的 DLL 覆盖，
唯一区别是 C 多一个 `profile.json` 声明 `files[]` 映射并走 `ContentsManager`，D 靠固定
路径走 `TarCompressorUtils`。分野的实际理由是版本管理需求——DXVK/VKD3D 要允许用户换
版本，微软 redist 不需要——而不是技术差异。

**`.wcp` 有两种压缩格式。** `Proton` 是 zstd，`Box64`/`Dxvk`/`Vkd3d` 是 xz。所以
`ContentsManager.extraContentFile` 必须先试 ZSTD 再试 XZ，§5 里「`.wcp` = xz 压缩 tar」
的表述不准确。

**`cnc-ddraw`、`dd7to9` 与 `d7vk` 互斥。** 三者都提供
`syswow64/ddraw.dll`，UI 和安装器必须只落一份。`d7vk` 另外把 Proton builtin
保留为 `ddraw_.dll`，供其代理调用。

**Turnip 有两份并存且版本不同。** `extra_libs.tzst` 里 11.5 MB（2025-08-07，已 strip），
`WN-Turnip` zip 里 14.8 MB（2026-07-22）。后者装到 `filesDir/contents/adrenotools/`，通过
`ADRENOTOOLS_DRIVER_PATH` 指定，不覆盖前者。imagefs 本身不带 Turnip。

**A 类 hooks（定稿 · 2026-08-05）。** 唯一来源 = 自建 `wrapper.tzst`
（`libadrenotools` + 4 hooks，CI pin `8483dfd`）→ `imagefs/usr/lib`。guest
`ADRENOTOOLS_HOOKS_PATH` 与 host `vulkan.c` `hookLibDir` 都指这里。APK **不**再打包
第二份 hooks。imagefs rootfs 本身不携带 hooks。

**`extra_libs.tzst` 73.3% 是调试符号。** 实测 `.debug_*` + 符号表占 82.2 MB / 112.2 MB
（解压后）：`libGL.so.1.5.0` 80.7 MB 里 66.5 MB 是调试信息，strip 后 14.2 MB；
`libvkbasalt.so` 17.5 MB → 3.3 MB；只有 `libvulkan_freedreno.so` 上游已 strip。
另外 `libvkbasalt.so` 的隐式层要求 `ENABLE_VKBASALT=1`，而代码库中无处设置，
在目标设备上它与 `libbcn_layer.so`（条件为非高通）都不会加载。

---

## 0.6 最终包结构（定稿 · 能缩尽缩）

> **2026-07-30 定稿。** 原则：一根基线、按更新频率切开、默认可跑通、可选另下、同构同机制、目标设备（Adreno）上无消费者的一律不进默认集。
> 下面「默认」= 冷启动必备；「可选」= 目录/点选再下；「砍掉」= Amphora 不再发布/不再提取。

### 总表

| # | 通道 / 产物 | 角色 | 体积目标（压缩） | 落地 | 默认？ |
|---|---|---|---|---|---|
| 1 | **`imagefs`**（自建 `imagefs.txz`） | Bionic 基线：Wine unix 依赖、ALSA、X11/DRM、Mesa GL 等当前构建集合 | **12.2 MB**（以 manifest 为准） | `filesDir/imagefs` | ✅ 唯一 rootfs |
| 2 | **`wrapper.tzst`** | `libvulkan_wrapper` + `libadrenotools` + **4 hooks** + `wrapper_icd` | **~0.7 MB**（自建 strip） | imagefs `usr/lib` + ICD | ✅ **独立发版；hooks 唯一来源** |
| 3 | ~~adrenotools hooks @ APK~~ | — | — | — | ❌ **已取消**（2026-08-05）：APK 排除 4 hook `.so`；host/guest 共用 wrapper 那份 |
| 4 | **Mesa GL 进 imagefs**（不再独立包） | **自建** `libGL.so.1`（`packages/graphics/mesa-gl.sh`：上游 Mesa 源码 + zink + xlib GLX，Termux 链接画像）。Mesa 25.3 起 glapi 已并入 libGL，无 `libglapi` 附件 | 使 imagefs 增约 **+3 MB**（解压 +16 MB） | imagefs `usr/lib` | ✅ 随 imagefs（2026-08-01 落地） |
| 5 | **`Proton-*.wcp`** | Wine 运行时 | 66.3 MB | WCP → imagefs | ✅ |
| 6 | **`Box64-*.wcp`** | x86_64 翻译 | 2.7 MB | WCP → imagefs | ✅ |
| 7 | **`Dxvk-*.wcp`** | D3D8–11 → Vulkan | 8.0 MB | 容器 `system32`/`syswow64` | ✅ |
| 8 | **`Vkd3d-*.wcp`** | D3D12 → Vulkan | 3.3 MB | 同上 | ✅ |
| 9 | ~~`container_pattern_common.tzst`~~ | Winlator prefix 模板（字体/图标/winhandler/ddraw 工具） | ~42 MB | — | ❌ **已废止**（2026-08）：prefix 只靠 Proton `prefixPack` |
| 10 | **`fonts.tzst`**（共享） | Source Han Sans **CN+JP** 回退 + Microsoft YaHei、SimHei、PMingLiU、Tahoma、Microsoft Sans Serif | **~38 MB** | `contents/FONTS/<sha>/`；真实 Windows 字体优先，Source Han 处理未打包字体；FontLink / FontSubstitutes / Wine Replacements | ✅ |
| 11 | **`wincomponents/*.tzst`** | 微软 redist（保持 tzst，**不**改 WCP） | 目录合计 ~38 MB；按 `FALLBACK` 选装 | `contents/WINCOMPONENTS/<id>-<sha>/`；prefix 只软链 DLL/EXE | ✅ 按需解压一次再链接 |
| 12 | **`WN-Turnip-*.zip`** | 可选完整 Turnip | ~2.7 MB zip / ~15 MB `.so` | `contents/adrenotools/<id>/` | ⚪ 可选 |
| 13 | **`ddrawrapper/{cnc-ddraw,dd7to9}.tzst` + `d7vk.zip` + `nglide.tzst`** | DirectDraw 三选一，默认 DxWrapper Dd7to9；nGlide 独立 | 各包以 manifest 为准 | 容器 `syswow64` | ⚪ 可选 |
| 14 | ~~`layers.tzst`~~ | Vulkan validation | ~4.4 MB | — | ❌ **默认不装**（host 调试层可选；guest 不 extract） |
| 14b | **`mesa-gl-override.tzst`**（可选） | 排查 OpenGL/DX7 时替换 `libGL`，**不进发布默认集** | ~5 MB | imagefs `usr/lib` 覆盖 | ⚪ 仅调试 |
| 15 | **FFmpeg 附加包**（可选） | `winedmo` 硬依赖；默认媒体走 GStreamer | 视自建拆包 | imagefs 叠加或并入 imagefs 变体 | ⚪ 可选（默认可不含） |

### 默认集体积对照

| | 现状典型目录（WinNative 原样） | **本定稿默认集** |
|---|---|---|
| rootfs | 官方 `imagefs.tzst` **199.8 MB**（历史研究基线） | 自建 `imagefs.txz` **12.2 MB**（当前 pin） |
| 图形叠加 | `extra_libs`+`layers`+旧 wrapper ≈ **29 MB**（历史） | `wrapper` ~0.7 MB（含 hooks；Mesa GL 已并入 imagefs） |
| 容器模板/字体 | `container_pattern_common` **41.6 MB**（字体堆 + 内嵌 cnc-ddraw） | 无 pattern；共享 Windows UI/CJK + Source Han 回退 `fonts.tzst` ≈ **38 MB** |
| 运行时+DX | 历史 WinNative 组合约 **181 MB** | 当前四项约 **80.4 MB** |
| **默认核心（不含字体/按需组件）** | **≳ 450 MB** 资产面 | rootfs + wrapper + 运行时/DX 约 **93.2 MB** |

### 砍掉 / 不再进默认（有据）

| 项 | 理由 |
|---|---|
| 官方全量 imagefs | 自建子集已覆盖 Wine NEEDED；terminfo/doc/locale/man/Tcl/编码器二进制无消费者 |
| `extra_libs.tzst` 整包 | 有用的只剩 `libGL`（+`libglapi`），现由 imagefs **自建**（`packages/graphics/mesa-gl.sh`，Mesa 25.3 已把 glapi 并进 libGL）；包内 Turnip 与 `WN-Turnip` **双份不同版本**；73% 调试符号 |
| `libvkbasalt.so` + JSON | 代码从不设 `ENABLE_VKBASALT=1`，目标设备不加载 |
| `libbcn_layer.so` + JSON | 条件为非高通；Adreno 830 默认路径不走 |
| `extra_libs` 内 `libvulkan_freedreno` + freedreno ICD | 默认 Wrapper 模式用系统 Adreno；完整 Turnip 只走可选 zip |
| `layers.tzst`（默认） | validation 仅调试 |
| `wrapper.tzst` 内的 4 hook | ✅ 唯一来源（guest+host）；APK 不再打包第二份 |
| `wrapper-leegao` / `virgl-*` / `zink-*` | Amphora 默认路径不用 |
| pattern 内多字体（日文装饰体等）+ 内嵌 `cnc-ddraw` | 默认 DxWrapper Dd7to9；字体改为共享 CN+JP Regular/Bold 四脸包 |
| `d8vk-1.0.tzst` | 默认 DXVK ≥ 3.x 已带 d3d8 |
| wincomponents → WCP | 无版本轮换需求，改格式零收益；**维持 tzst** |

### 通道规则（必须遵守）

1. **imagefs** 唯一基线；换 TLS/媒体/**Mesa GL** 才重发；**永不**因换 wrapper/hooks/Turnip/字体重打。
2. **wrapper + hooks** 同包单独发版（`wrapper.tzst` ARCHIVE）：hooks **不**进 APK。
   **必须同版本**——`HookImplParams` 跨 `.so` 传 C++ 结构体，见下「ABI」一节。
   **hooks 全局只保留一份**（随 wrapper → `imagefs/usr/lib`）；host/guest 共用。
   见下「收敛到只有一份 hooks」。
3. **Mesa GL 不单独拆包**，理由见下「为什么 Mesa GL 进 imagefs 而 wrapper 不进」。
4. **Turnip** 只有可选 zip 一条路径；`ADRENOTOOLS_DRIVER_NAME` 仅在用户点选时设置。
5. **ddraw** 默认 DxWrapper Dd7to9；`cnc-ddraw` / `dd7to9` / `d7vk`
   互斥，UI/安装器只落一份。三套路径都只部署 PE32
   `syswow64/ddraw.dll`；x86_64 无对应 wrapper，按 `ddraw=n,b` 回退 Proton
   builtin ddraw/WineD3D→Zink。
6. **字体** 全局一份；多容器不重复打进 pattern。
7. **发布面**：默认 pin / 产物走公开 GitHub Release（`amphora-dev/*`）+
   `content_manifest`（GitHub Contents API，raw 回退）；不塞进默认 APK。

### 图形栈的依赖归属（实测）

三个图形消费者对 imagefs 的 NEEDED（`readelf -dW`，2026-07-30 实测；`libGL.so.1` 这一列
自 2026-08-01 起是 imagefs 自建的 `mesa-gl`，`libglapi` 已被 Mesa 25.3 并进 libGL）：

| imagefs 提供的库 | `libvulkan_wrapper` | `libvulkan_freedreno`（Turnip） | `libGL.so.1` | Wine unix 侧 |
|---|:---:|:---:|:---:|:---:|
| `libdrm.so` | ✅ | ✅ | ✅ | |
| `libc++_shared.so` | ✅ | ✅ | ✅ | |
| `libandroid-sysvshm.so` | ✅ | ✅ | | (LD_PRELOAD) |
| `libxcb.so` `libX11-xcb.so` `libxcb-{dri3,present,sync,randr,shm}.so` | ✅ | ✅ | | |
| `libz.so.1` `libzstd.so.1` | | ✅ | ✅ | |
| `libX11.so` `libXext.so` | | | ✅ | ✅ `winex11.so` |
| `libxshmfence.so` `libexpat.so.1` | | ✅ | | |
| `libandroid-shmem.so` | | | ✅ | |
| `libglapi.so.0` | | | ✅ | |
| `libnativewindow.so` `liblog.so` `libm/libdl/libc` | ✅ | ✅ | ✅ | ✅ |

**结论：这些库不属于 wrapper，也不属于 libGL，它们是多方共享的底座。**
`libdrm` / `libc++_shared` 被三方同时链；`libX11` / `libXext` 连 Wine 的 `winex11.so`
都要；`libz` / `libzstd` 是 Turnip 与 libGL 共用。**共享底座归 imagefs**，这与「它们看起来
是图形相关」并不矛盾——图形栈的三个消费者都是**叠加在 imagefs 之上**的。

真正跟 libGL 绑死的只有两项：`libglapi.so.0`（Mesa 自己的一半，**必须与 libGL 同版本**，
1.4 MB）和 `libandroid-shmem.so`。

### 为什么 Mesa GL 进 imagefs，而 wrapper 不进

**修正一个论据**：先前写「拆开会 soname 错配」不准确。上表这些库的 soname 都很稳定
（`libX11.so` 无后缀、`libdrm.so`、`libz.so.1`、`libzstd.so.1`、`libglapi.so.0`），更新也不
频繁，所以错配风险其实不高。**「有依赖」本身不构成拆包的否决理由**——`wrapper` 同样依赖
imagefs 的 `libxcb` / `libdrm` / `libandroid-sysvshm`，它照样独立发版。

判据应当是**是否存在独立换版需求**，以及**拆出去能否换来实际收益**：

**libGL — 不拆。**
- **OpenGL 无插拔需求**：Wine `opengl32` → EGL → **Zink** → Vulkan 驱动；DirectDraw 单独走可选 wrapper → D3D9/DXVK。
  用户要换的是**下层 Vulkan 驱动**，不是 libGL 本身；没有「libGL 版本选择」这个 UI。
- **拆了也省不下**：libGL 私有的只有 `libglapi`（1.4 MB）。底座留在 imagefs，
  拆出去的包 ≈ libGL 自身体积，默认集总量不变，只多一个附件、多一次校验、多一处
  版本组合。
- **重打成本已经很低**：imagefs 现在是我们自己 CI 的产物（一次 CI + 一个 Release 附件），
  不再是当年重下 199 MB 外部 blob，所以「避免重打 imagefs」不再是拆包动机。

**wrapper + hooks — 拆。** 理由是插拔点 + hooks 的打包位置约束（下节澄清其真实强度），
libGL 两者都不具备：Vulkan 驱动是**真实插拔点**（Wrapper 默认 / 可选 Turnip 二选一，有 UI），
ICD 与 hooks 要能独立于 rootfs 换版。

### hooks 路径：`nativeLibraryDir` 的约束到底有多硬

> **历史方案分析，非当前布局。** 本节保留用于说明为什么两种目录都能加载；
> “hooks 指向 APK”方案随后被否决。当前唯一布局是自建 `wrapper.tzst` →
> `imagefs/usr/lib`，见 §0.6 与本文开头的定稿表。

**先纠正一个说法**：先前写「hooks 物理上不可能待在 imagefs 里」是错的。读 adrenotools 实现：

```cpp
// src/driver.cpp:75
auto hookNs{android_create_namespace("adrenotools-libvulkan", hookLibDir, nullptr,
                                     ANDROID_NAMESPACE_TYPE_SHARED, nullptr, nullptr)};
// 随后从该 namespace 解析:
linkernsbypass_namespace_dlopen("libhook_impl.so", RTLD_NOW, hookNs);
linkernsbypass_namespace_dlopen("libmain_hook.so", RTLD_GLOBAL, hookNs);
```

`hookLibDir` 只是新建 linker namespace 的 **`ld_library_path`**。平台并不要求它等于
`nativeLibraryDir`——任何「hook `.so` 确实存在且可 `dlopen`」的目录都能解析。

`driver.h` 那句 "MUST point to `nativeLibraryDir`" 的实际语境是**打包**：上游把 hooks 放
`jniLibs`，于是该目录就是 `nativeLibraryDir`；而 `useLegacyPackaging = false` 时 Android
直接从 APK 读 `.so`、不解压落盘，那个目录里就是空的——所以它警告的是「别指到一个没有
hook 文件的地方」。

**因此之前（WinNative / amphora 移植初期）的做法是可行的**：hooks 打进 `wrapper.tzst` →
`imagefs/usr/lib/`，`ADRENOTOOLS_HOOKS_PATH` 指 `imageFs.getLibDir()`。能工作因为
(a) imagefs 在 app 私有 `filesDir` 下、同 uid、非 sdcard，`dlopen` 允许；
(b) amphora targetSdk 36 仍允许 `dlopen(app_data_file)`；只有直接 `execve` 被禁，
    原生进程由 linker64 + `libamphora-exec.so` 路由；
(c) `ld_library_path` 接受任意目录。
它还有个优点：hooks 与 guest 侧 `libvulkan_wrapper.so` **同包同版本**，且路径在 imagefs 内
自洽（未来若引入 mount namespace，guest 视角也一定可见）。

**改指 `nativeLibraryDir` 的真实收益**不是「否则跑不起来」，而是消除**三份 hook 版本漂移**
（imagefs 内上游预编译一份 66 KB / `wrapper.tzst` 内 6–41 KB 一份 / APK 里我们自建
submodule `8483dfd` 一份），并让 hooks 与 APK 内静态链入 `libwinlator.so` 的
`libadrenotools` 同源。

### `HookImplParams` 的跨 `.so` ABI — 已实测，混搭安全

`libadrenotools` 把一个 **C++ 结构体**（含 `std::string`）跨 `.so` 边界传给 `libhook_impl`：

```cpp
// src/driver.cpp:108
initHookParam(new HookImplParams(featureFlags, tmpLibDir, hookLibDir, ...));
// struct HookImplParams { int; std::string ×5; adrenotools_gpu_mapping *; }
```

**所以 `libadrenotools` 与 4 个 hook 必须同版本编译**，字段增删即布局错配。

guest 侧 `libvulkan_wrapper.so` 是**上游预编译 blob**，NEEDED 指名 `libadrenotools.so`
（同样是 `wrapper.tzst` 的上游 blob），而 hooks 现在来自 APK 里自建 submodule `8483dfd`
——两版混搭。**2026-07-30 实测比对，两侧一致：**

上游 `libadrenotools.so`（stripped，NDK **r28c**）仍以 weak symbol 导出了构造函数：

```
_ZN14HookImplParamsC2EiPKcS1_S1_S1_S1_P23adrenotools_gpu_mapping
  → HookImplParams::HookImplParams(int, char const*, char const*, char const*,
                                   char const*, char const*, adrenotools_gpu_mapping*)
```

与 submodule `8483dfd` 的 `hook_impl_params.h:22` 逐参对齐（`int` + **5×** `const char*`
+ `adrenotools_gpu_mapping*`）。字段集相同 → 布局相同（`int` + 5×`std::string` + 指针；
两侧同为 NDK `libc++_shared`，`_LIBCPP_ABI_VERSION=1` 下 `std::string` 恒 24 字节）。
**当时的局部结论**：在所测提交上，hooks 指向 APK 自建版未发生 ABI 错配；这不构成
采用多份 hooks 的理由，当前实现仍只保留 wrapper 内一份。

顺带测出的第二件事：guest `libvulkan_wrapper.so` 对 `libadrenotools` 的**未定义符号只有
一个** `adrenotools_open_libvulkan`（不引用 `patch_bcn` / `set_turbo` / `get_bcn_type`）。
所以若将来要让 guest 侧也用自建 `libadrenotools.so` 覆盖 wrapper 内那份，符号面是够的
（我们的 `driver.h` 导出集覆盖它）——这是备选方案 (b)，当前**不需要**执行。

尽管如此，§0.6 的「wrapper 与 hooks 同通道共版」规则保留：这条 ABI 边界是真实存在的，
只是当前两版恰好一致。**任何一侧升级都必须重新比对这个 mangled 构造签名。**

### 收敛到「只有一份 hooks」✅ 已完成（2026-08-05）

**唯一来源**：自建 `wrapper.tzst`（adrenotools @ `8483dfd`）→ `imagefs/usr/lib`。

| 来源 | 落点 | 现状 |
|---|---|---|
| 自建 `wrapper.tzst` | `imagefs/usr/lib/` | ✅ guest env + host `hookLibDir` |
| APK submodule hooks | — | ❌ **不建**：APK 只静链 `adrenotools`；SHARED hooks 由 imagefs wrapper CI 编进 `wrapper.tzst` |
| imagefs rootfs | — | ✅ 配方不打 hooks |

`AppUtils.getNativeLibDir` 已不存在；host 若再读 `nativeLibraryDir` 会静默回退系统 Adreno。
`vulkan.c` 现经 `ImageFs.find(context).getLibDir()` 取 hooks，并校验 `libhook_impl.so` 存在。

> `winlator-imagefs` 曾用 `build-native-libs.sh` 编这 4 个 hook（连同 proot /
> virglrenderer）进 `native-libs.tar.xz`。**2026-07-30 已删除该脚本与对应 CI 阶段**：
> 那些都不是 imagefs 内容，也不是 amphora 的来源，留着只会变成 hook 的第四个版本源。
> 该仓现在只产 `imagefs.txz`。

排查 OpenGL/DX7 黑屏时若要频繁试不同 Mesa，用 `mesa-gl-override.tzst`（表 14b，
带上同版本 `libglapi`）——**调试手段，不是发布结构**。

### 默认冷启动安装顺序

```
imagefs（已含 strip 后的 Mesa GL）→ wrapper（含 hooks）
       → Proton.wcp → Box64.wcp
       → Dxvk.wcp → Vkd3d.wcp
       → fonts（共享）→ wincomponents（FALLBACK）
ADRENOTOOLS_HOOKS_PATH / host hookLibDir = imagefs/usr/lib
```

### 已拍板摘要

| 决策 | 状态 |
|---|---|
| wrapper + adrenotools hooks 单独更新、不焊 imagefs | ✅ |
| hooks 全局唯一来源 = 自建 `wrapper.tzst` → `imagefs/usr/lib`（host+guest）| ✅ 2026-08-05；APK **不编不打包** hooks |
| 自建 imagefs 为唯一默认 rootfs；官方 199 MB 退出默认 | ✅ |
| Proton `11.0-d12a5634a-x86_64` 为默认 wine pin | ✅ 见远程 `content_manifest` |
| `extra_libs` 废止；strip 后的 Mesa GL **并入 imagefs**（不单独拆包，见上）；砍 vkBasalt/BCn/包内 Turnip | ✅ |
| `layers` / WN-Turnip / ddraw 包装器 = 可选 | ✅ |
| 字体从 pattern 拆出共享；pattern 去内嵌 ddraw | ✅ |
| wincomponents 保持 tzst + 选装，不改 WCP | ✅ |
| FFmpeg 不挡默认媒体（GStreamer 优先）；可作 imagefs 变体/附加包 | ✅ |

---

## 0.7 整体分布总览（谁产出 → 落到哪）

> 一张表看清全局。**产出方**列指真正的构建/发布源，**通道**对应 §0.6，
> **落地**指设备上的最终位置。体积为压缩后实测值。

### 按产出方

| 产出方 | 产物 | 通道 | 体积 | 落地 | 状态 |
|---|---|---|---|---|---|
| **amphora APK**（本仓 `:core:native`） | `libwinlator.so`（含**静态** adrenotools + 19 shader + zstd/xz）| — | 2.5 MB | `nativeLibraryDir` | ✅ |
| 同上 | ~~4× adrenotools hook~~ | — | — | — | ❌ APK 不建；由 `wrapper.tzst` 提供 |
| **`amphora-dev/imagefs`** | `imagefs.txz` / Proton / Box64 / DXVK / VKD3D / `wrapper-*.tzst`（含 hooks@8483dfd）| rootfs+runtime+dx+wrapper | 见 pin | imagefs / 容器 DLL | ✅ 当前核心 pin |
| **`nicholasx417/WinNative-Components`** | WCP catalog fallback | catalog | — | 下载解析 | 🟡 manifest URL 不可用时回退 |
| **`WinNative-Emu/WinNative`**（raw）| `wincomponents` / `ddrawrapper` / meta json / box64rc | runtimeAssets | 见 pin | 各落地根 | 🟡 仍有 pin；可逐步自有化 |
| 同上 | ~~官方 `imagefs.tzst` / `wrapper.tzst` / `extra_libs.tzst`~~ | — | — | — | ⛔ 已由 amphora-dev 自建或废止 |
| **`WinNative-Emu/Drivers`** | `WN-Turnip-*.zip` | 可选驱动 | 2.7 MB | `contents/adrenotools/<id>/` | ⚪ 可选 |
| **`amphora-assets`（cnb）** | 历史镜像设想 | — | — | — | ⛔ **不再阻塞**（生产 = GitHub Release + `content_manifest` / GitHub API）|
| **`aio-graphics-test`**（cnb）| 图形自测 PE | 测试 | 小 | 容器 `drive_c` | ✅ |

### 按落地根（覆盖域视角）

| 落地根 | 谁写入 | 冲突风险 |
|---|---|---|
| `imagefs/`（rootfs） | `imagefs`（含自建 `libGL`）+ `wrapper.tzst` + `layers` + Proton/Box64 WCP | **提取顺序敏感**；hooks 曾在此三份漂移 |
| 容器 `system32`/`syswow64` | DXVK/VKD3D/Proton builtin/DirectDraw/native wincomponents cache 的只读软链接 + 容器私有配置 | `cnc-ddraw` / `dd7to9` / `d7vk` **互斥**；写入前必须先 unlink，禁止跟随链接改共享源 |
| 容器 `.wine`（prefix） | Proton `prefixPack` + 共享字体链接 + 容器私有配置 | pattern 已废止；写入前须处理旧链接 |
| `filesDir/contents/<type>/<ver>/` | `ContentsManager`（WCP） | 版本化，无冲突 |
| `filesDir/contents/DDRAW/<id>-<sha>/` | runtime asset 解压一次后的 immutable DLL cache | prefix 只链接 DLL；INI/shader 仍为容器私有 |
| `filesDir/contents/WINCOMPONENTS/<id>-<sha>/` | native wincomponents 解压一次后的 immutable DLL/EXE cache | prefix 只链接；DllOverrides / CLSID 仍写容器注册表 |
| `filesDir/contents/adrenotools/<id>/` | wrapper ICD 桥接 + 可选 Turnip | 单选 |
| **`imagefs/usr/lib`（wrapper.tzst）** | **hooks 唯一权威来源**（guest env + host `hookLibDir`） | APK 不再带 hooks |

### 数量与体积汇总

| | 历史 WinNative 基线 | 当前 Amphora |
|---|---|---|
| 核心 pin 产出方 | 多个外部源 + APK | `amphora-dev/imagefs` + 远程 manifest |
| 默认核心（不含字体/按需组件） | ≳ 450 MB | **约 93.2 MB** |
| 其中 rootfs | 199.8 MB | **12.2 MB** |
| 其中 wrapper | 旧图形叠加约 29 MB | **0.67 MB** |
| 其中容器模板 | 41.6 MB | **0**（使用 Proton `prefixPack`） |
| hooks 副本数 | 3（历史）| **1**（`wrapper.tzst` → `imagefs/usr/lib`）|

---

## 1. 历史 WinNative 根文件系统样本（非生产 pin）

> 本节保留移植阶段的全量样本数据，用于解释裁剪来源。设备不会下载这里列出的 SHA；
> 当前 rootfs 只看文首所述远程 manifest。

| 资产 | 压缩 | 大小 (字节) | SHA-256 | 来源 |
|---|---|---|---|---|
| `imagefs.tzst` | zstd | 199,788,876 (190 MB) | `0902e324b60a5c234aa29fcf457f6475a38ef8f61ac2be2118daaef4f236499a` | WinNative Git LFS (`app/src/main/assets/imagefs.tzst`) |

**提取后**: ~877 MB, 10,892 条目, merged-usr 布局 (`bin`/`etc`/`lib`/`share`/`tmp` -> `usr/*` 符号链接)。
**Bionic 标记确认**: `usr/lib/libc.so -> /system/lib64/libc.so`, `libdl.so`/`libm.so` 同; **无** `libc.so.6`/`ld-linux` (glibc 标记)。
**关键库在位**: `libpulse.so`/`libpulseaudio.so`/`libpulsecommon-13.0.so` (PA 13.0), `libvulkan.so.1.4.315`, `libGL.so`, `libsndfile.so`, `libltdl.so`, `libandroid-spawn.so`; `etc/alsa/conf.d/android_aserver.conf` (Bionic ALSA 原生路径); `usr/share/wine/{fonts,nls}` + `winetricks`。
**不在 imagefs 内**: `box64` 二进制 + Proton/Wine 主二进制 (**运行时 `.wcp` 下载**, 见 §5), `libEGL.so`/`libGLESv2.so` (运行时用 `/system`), `home/xuser`/`tmp/.X11-unix` (运行时由 XEnvironment 创建)。
**D7 termuxfs rpath**: Wine `.so` 的 `DT_RUNPATH=/data/data/com.termux/files/usr/lib` 烙在 ELF 内; imagefs 内**无**该路径 (grep 0 命中)。运行时由 launch `LD_LIBRARY_PATH` 解析 (P3 事项, 非 P2 提取阻塞)。

**获取方式** (复现):
```bash
cd /path/to/WinNative
git lfs install                          # 一次性
git lfs pull --include="app/src/main/assets/imagefs.tzst"
shasum -a 256 app/src/main/assets/imagefs.tzst   # 须 = 0902e324...
```

---

## 2. 历史上游图形驱动 / DX 包装层 / 组件样本

> 全部来自 WinNative `app/src/main/assets/` (本地直存, 非 LFS)。`graphics_driver/wrapper.tzst` 是包装系统驱动的 Vulkan ICD；完整 Turnip 是单独下载的 WN-Turnip 包。box64 二进制运行时 `.wcp` 下载 (见 §5), 这里只锁 `.box64rc` 配置。

### 2.1 graphics_driver/ (Wrapper / VirGL / Zink / extra)
| 资产 | SHA-256 |
|---|---|
| `wrapper.tzst` (系统 Vulkan 包装 ICD + adrenotools hooks，**默认**) | `2651fbe6372af36c7d269664416b4f62d959125122ad3b8f79a787788e510fd8` |
| `wrapper-leegao.tzst` | `3eabf6fc53f3b738eb80e7f80e3b28f761a8cebaa62b7e6c1f05e0e1228a969d` |
| `extra_libs.tzst` (vkBasalt + Mesa 库) | `e27859423f4f151ef48bb2f076043cda45fd46de1df5eea2ceed30bcf0ebd38a` |
| `zink_dlls.tzst` | `efe27f0de6a55bfb6c2e9eab79b6baaae64b35af81ce49c097de6b720f258cbb` |
| `zink-22.2.5.tzst` | `8a1de929cda4699d36a27448cd4d8065103475d1238aac03a71e76b72dcb433c` |
| `virgl-23.1.9.tzst` | `c42f22641079f678d501ca920a5b2e549add2b3418d18e1e10f19ddb838eeed0` |

### 2.2 dxwrapper/ (DXVK 系) + ddrawrapper/
| 资产 | SHA-256 |
|---|---|
| `dxwrapper/d8vk-1.0.tzst` (D3D8->Vulkan, only for DXVK &lt; 2.4 via extractD8VKIfNeeded) | `da30a104f83f619214a3bc67c971434837e1a11ca30703d0f79041a93f2be246` |
| `dxwrapper/cnc-ddraw-6.6/ddraw.tzst` | `eabb2d59ce7060767996d0c9597ee8772059126a3af01698e59a5e7905e49867` |
| `ddrawrapper/dd7to9.tzst` | `c038232755e829256b3fb2d6f2fe05cc74fc17a99907eb79e0e07e64f47e8605` |
| `ddrawrapper/ddraw-11.8.tzst` | `92e6a19232dd39085584f73114a34a5ee0a49a90a440342b75493c310f53a43e` |
| `ddrawrapper/ddraw-4.21.tzst` | `06d073dc65ab89d1b6bb3023d5d2a7b9f7b7d7dc883ecaf4eb342093431c376a` |
| `ddrawrapper/nglide.tzst` | `3f5eb40810376cdb5bb2d18ea374bbd08177053b4aa9f923b9b440ebefe0d554` |
| `ddrawrapper/cnc-ddraw.tzst` | `d2aea34321200dd07a979a83c329da207d67af8e72fa182c307a39257d5e1d5b` |

### 2.3 box86_64/ (box64 配置)
| 资产 | SHA-256 |
|---|---|
| `default.box64rc` | `652f51566dc050fbb58c06dc956638bc5667113accc2394e291703bd9f0aed18` |
| `lightsteam.box64rc` | `dfbfff33a9132d46faeca98d73b259ff80f4888c9fe7f2279f1d0ae4ba214134` |
| `ultralightsteam.box64rc` | `e28a950787a2bec74b46df4a44af1de1daa9227f28edd7bac6190aeb06e260cc` |

### 2.4 wincomponents/ (微软闭源运行时)
| 资产 | SHA-256 |
|---|---|
| `direct3d.tzst` | `149d55436e050bfa3fe8caf650cb97ddb2bc9fa99cd96869bd2bc4977b708fb5` |
| `vcrun2010.tzst` | `9def7b4b5c31c839d155f05e372e0e9ce4968b57f815c1abf93b7735e7d0ceb7` |
| `wmdecoder.tzst` | `a2ca365cc43265680ee71a7b13a4626ed02368feec135571fb841c7b4abf62d1` |
| `directsound.tzst` | `72f0e880eef1387a55602ac02b07ef26778d23c2f709931bd2d13e92f8dc99d4` |
| `directplay.tzst` | `70eb81449230af66286f5b4d5922dca13057600c35d4cdfe91930e966c2386d9` |
| `directshow.tzst` | `aa682b7ee6e5903e4571bfe5a8dc9b05d0a9f05cd860cfdc810f2ddd3745e9b4` |
| `xaudio.tzst` | `2f80fd6242136b543ee2e1c8fb7daa9c0bcd0c318886ca502f84f456c83bbc43` |
| `directmusic.tzst` | `eb906732d14766641edfae0b2d722c36f4632de7fa321a5844037115b513b77e` |
| `ddraw.tzst` | `eabb2d59ce7060767996d0c9597ee8772059126a3af01698e59a5e7905e49867` |
| `wincomponents.json` | `b0ef5a4279a5024807d6e2763ba1cf936e6c798b4fae5a7773be54c35ece7553` |

### 2.5 顶层杂项
| 资产 | SHA-256 | 备注 |
|---|---|---|
| `layers.tzst` | `9beac20c3e2c7f2c224f2c18cc6dc253b0f70450006fc986970992c7a8814ffa` | Vulkan 层 |
| `extras.tzst` | `c8750ea9df7bccb8d6b93e9d3432214a9fa473ca32022a46cf02a471eb02a052` | Mono MSI 等 |
| `experimental-drm.tzst` | `cc95e069d6221d9fa7f92c8311574131d6fda277b65c587a771cc10cad898736` | |
| `pulseaudio.tzst` | `357bb53fbcf91ab2adcd2e6a4b7fc3f2cb95f1555610681b08dbf6d412ac4bd8` | ARM64 Pulse 模块；当前 Amphora 与 PA13 JNI 库配套后随 APK 交付 |
| `container_pattern.tzst` | `4043fa27127c663461d45e953f9023bdcacf31b21f611d954d5b4e872ebb32e6` | Wine prefix 模板 |
| `container_pattern_common.tzst` | `6f5a7b011f2ab79d8be60c4783de97f635289fd901fb624b26c2a3725fc8f479` | |
| `proton-9.0-x86_64_container_pattern.tzst` | `fa25987a3ba4f1b951bd1161f9a65a9450f0b72b87583fd3d9971d92c63a9608` | Proton x86_64 prefix |
| `proton-9.0-arm64ec_container_pattern.tzst` | `9826ac61405f641ca8326335208c3e1ed5cec107f4b0354f813825ec398b841b` | arm64ec (D5 砍) |
| `proton-9.0-arm64ec.txz` | `63938f0b90d6ec9b2213bd419b27949768d5766af0da541831ce0d0f962c9381` | arm64ec (D5 砍, LFS) |

### 2.6 AIO Graphics Test

开始菜单中的 32/64 位图形诊断程序固定为 patched AIO Graphics Test（基于
[`The412Banner/AIO-Graphics-Test`](https://github.com/The412Banner/AIO-Graphics-Test)
`cube_d3d8.c`：窗口模式 `FullScreen_PresentationInterval` 必须为
`D3DPRESENT_INTERVAL_DEFAULT`，否则 DXVK D3D8 `CreateDevice` 返回
`D3DERR_INVALIDCALL`）。

公开源与固定 Release：[`cnb.cool/atowerlight/aio-graphics-test`](https://cnb.cool/atowerlight/aio-graphics-test)
标签 **`amphora`**（每次 `main` 推送由 CI 覆盖附件，不保留历史版本）。设备端按
`content_manifest.json` 的 `remoteUrl` 下载并校验后复制到 Wine
`ProgramData/Microsoft/Windows`。APK 中的同名文件只是当前离线候选，不是生产 pin 真源：

| 资产 | SHA-256 | 大小 | remoteUrl |
|---|---|---:|---|
| `Graphics-Test-32bit.exe` | `75589dc37b72d509e23c9c3c043fdf8e03855e5d2f1ec846efe2672662719306` | 2,083,443 | `.../releases/download/amphora/AIO-Graphics-Test-32bit.exe` |
| `Graphics-Test-64bit.exe` | `96d76d077139ef469eff31efbc75cd9202b99bf2906b97e3c5de07dc350f5c57` | 2,065,494 | `.../releases/download/amphora/AIO-Graphics-Test-64bit.exe` |

CI 重建后 SHA 会变：先更新 `content_manifest.json`；本文仅在人工校对时同步。

> `proton-9.0-x86_64.txz` (Wine/Proton 主二进制) 在 WinNative assets 内**未见** -- 走 build.gradle `downloadProton` 任务从 GitLab 下载 (见 §3)。Amphora 生产路径由 `RemoteContentSource` 在设备上下载并校验 manifest 中固定的 Proton WCP，不再要求 build 时打入 APK。

---

## 3. 可复现构建 (备选, 自建 imagefs 而非依赖 LFS blob)

| 来源 | URL | 产物 | SHA-256 | 说明 |
|---|---|---|---|---|
| cnb.cool 历史仓 | `cnb.cool/atowerlight/winlator-imagefs` (HEAD `2daa55c`；当前不可访问) | `imagefs.txz` (xz, 18 MB) | `af66e28b61577a0cd8433155ee2123d910f02f7870b8938994d27d81281372e3` | 仅保留早期来源记录；生产已迁移到 `amphora-dev/imagefs` |
| GitLab winlator-extra | `https://gitlab.com/winlator3/winlator-extra/-/raw/main/imagefs/imagefs.txz.{00-03}` | `imagefs.txz` (4×50 MB 分卷) | `f96d362b7e148e86ab0d2c290978bf39b38e5c7ffc8ae4adf1d2a65c62bbb780` | Pipetto-crypto build.gradle `downloadImageFS` 的原始源 |

> 这些是早期可复现构建来源。当前生产使用 `amphora-dev/imagefs` 发布的精简
> `imagefs.txz`，安装器已原生支持 manifest 声明的 xz 压缩，不需要转码为 zstd。

---

## 4. 待办 (资产侧)

- [x] `:core:content` 远程内容通道：运行时获取独立仓库的 `content_manifest.json`，
  流式校验 SHA-256/大小，并按 ROOTFS、WCP 和 runtime asset 类型安装。
- [x] build 时资产 staging `:app:stageBundledContent`：每次按远程 manifest 精确同步非 ROOTFS `components` + 全部 `runtimeAssets` 到 `app/build/generated/assets/bundledContent/`，并由 Android main assets source set 打包；优先使用 WinNative 本地同路径文件，缺失时按 `remoteUrl`（WCP 可回落 catalog）下载。源文件、缓存和生成文件必须同时匹配 manifest `size` 与 SHA-256，否则任务失败；临时目录完整验证后才替换输出，不再写入或污染 `app/src/main/assets/`。任务仍保持 standalone，不挂 `preBuild`。
- [x] 真机 preparer 验证: `RemoteContentSource` 下载 Proton/Box64 `.wcp` + `createContainer` + `extractGraphicsDriverFiles`
- [x] `RemoteContentSource`: Kotlin HTTPS 下载、续传、SHA/大小校验和设备缓存；`nativeDownloadFile` 保持非生产 stub

---

## 5. 运行时组件 catalog fallback -- nicholasx417/WinNative-Components

> 生产组件优先使用远程 manifest 中逐项固定的 `remoteUrl`。该 catalog 仅用于 URL
> 缺失时的解析回退和可选版本发现，不能覆盖 manifest 的版本、大小或 SHA。

**历史 catalog 示例**：
```
https://raw.githubusercontent.com/nicholasx417/WinNative-Components/refs/heads/main/contents.json
```
`ContentsManager.syncContents()` 可读取 catalog 发现版本；默认安装仍由 `ContentCatalog`
和 manifest pin 驱动。

**可用组件** (contents.json 抽样, verCode=0):
| 类型 | 版本 (抽样) | .wcp remoteUrl |
|---|---|---|
| **Proton** | `Proton-10.0-4-x86_64` | `.../releases/download/Proton/Proton-10.0-4-x86_64.wcp` |
| | `Proton-10-arm64ec-original` / `-unix` | (arm64ec, D5 砍) |
| **Box64** | `Bionic-Box64-0.4.3-8ee3d8f2c` (**匹配 Bionic imagefs**) | `.../releases/download/bionic-box64-nightly-.../Bionic-Box64-0.4.3-8ee3d8f2c.wcp` |
| | `Box64-0.4.3` / `0.3.9` / `0.3.8` / `0.3.7` | `.../releases/download/Stable-Box64/...wcp` |
| **DXVK** | `Dxvk-3.0.2-gplasync` / `2.4.1-pre-reg` / `a6xx-*` / Sarek | `.../releases/download/Stable-Dxvk/...wcp` |
| **VKD3D** | `Vkd3d-3.0.1-S6_9` / nightly | `.../releases/download/Stable-VKD3D/...wcp` |
| Wrapper / WineD3d | -- (不在 contents.json, 仍打包在 assets `graphics_driver/`/`dxwrapper/`) | -- |

> WinNative assets 的 `dxwrapper/d8vk-1.0.tzst` 仅作 DXVK &lt; 2.4 的 d3d8 补丁 (`extractD8VKIfNeeded`); amphora MVP 默认装 manifest 固定的 DXVK（d3d8/9/10core/11/dxgi）+ VKD3D（d3d12/d3d12core），经 `ContentSource.resolve(DXVK|VKD3D)` + `ContentsManager.applyContent`. 容器 `dxwrapper` 形如 `dxvk-…;vkd3d-…;dd7to9`。


### 5.1 amphora 当前锁定版本

| 组件 | 锁定包 | ContentsManager entry / wrapper token | 作用 |
|---|---|---|---|
| **DXVK** | `Dxvk-3.0.2-gplasync-6b20f622a.wcp` | `DXVK-3.0.2-gplasync-6b20f622a-0` | D3D8/9/10/11 → Vulkan |
| **VKD3D** | `Vkd3d-3.0.1-3b10bd7a7.wcp` | `VKD3D-3.0.1-3b10bd7a7-0` | D3D12 → Vulkan |
| **Proton** | `Proton-11.0-d12a5634a-x86_64.wcp` | `Proton-11.0-d12a5634a-x86_64-0` | 自建 Wine/Proton + prefixPack |
| **Box64** | `Box64-0.4.5-0db8df775.wcp` | `Box64-0.4.5-0db8df775-0` | x86_64 → ARM64 用户态翻译 |
| **Wrapper** | `wrapper-7eae6442f.tzst` | runtime asset `graphics_driver/wrapper.tzst` | Guest Vulkan ICD + adrenotools hooks |

容器默认 `dxwrapper`：`dxvk-…;vkd3d-…;dd7to9`；第三段可由 UI 在
`dd7to9`、`cnc-ddraw` 与 `d7vk` 之间切换。

选型原则：默认使用 manifest 锁定的 x86_64（非 arm64ec）组件。Catalog 中的 Stable、
nightly 或 GPU 特化包只用于显式兼容性试验，不能静默替换生产 pin。

### 5.3 可选 adrenotools Turnip（WN-Turnip）

默认 **不**启用。启动器 **GPU driver** 可选：

| 选项 | `graphicsDriverConfig.version` | 含义 |
|---|---|---|
| **Wrapper**（默认） | `wrapper` | Guest ICD=`wrapper_icd`；adrenotools NAME 不设 → 系统 Adreno |
| **Turnip 1.06-b** | `WN-Turnip-1.06-b` | 下载 `Drivers@v1.06` Balanced zip → `contents/adrenotools/WN-Turnip-1.06-b/`；Guest 仍用 wrapper ICD，但设 `ADRENOTOOLS_DRIVER_PATH/NAME=libvulkan_freedreno.so`；Host 同 id |

资产 pin：`runtimeAssets` 条目 `adrenotools/WN-Turnip-1.06-b_Axxx.zip`（SHA 见 manifest）。首次点选时 `TurnipDriverProvisioner` 下载并 `AdrenotoolsManager.installFromZip`。

### 5.2 上游版本族怎么读（为何看起来「很多」）

同一组件在 `Stable-*` / nightly / Sarek 等 tag 下会有大量 `.wcp`，名字后缀大致表示：

| 后缀 / 系列 | 含义 | 何时考虑 |
|---|---|---|
| **主版本号**（`1.x` / `2.x` / `3.x`） | 上游 DXVK / vkd3d-proton 大版本；API 覆盖与驱动假设不同 | 新游戏偏新；老游戏黑屏/崩溃可回退旧线 |
| **`gplasync` / `async` / `dyasync`** | 社区异步着色器编译补丁（减少卡顿 stutter） | **默认优先**；纯 vanilla 同步版兼容偶发更好但更卡 |
| **`pre-reg` / `pre-regress`** | 针对特定回归的回退/特化构建 | 某游戏在最新 gplasync 坏、旧构建好时 |
| **`a6xx-*` / `special+d8` / `sp`** | **GPU 特化**：Adreno A6xx 系驱动补丁 / D3D8 特化（与 ABI 无关；手机 GPU 族名） | 仅当默认黑屏且社区明确点名；amphora 默认不碰 |
| **`Sarek`**（可带 `arm64ec`） | 独立 DXVK fork，偏旧 Adreno / 兼容线 | 新 DXVK 在老 GPU 上挂时的备选，**非**默认 |
| **`arm64ec`**（DXVK/VKD3D/Proton/D7VK/FEX 都有） | **ABI 路线**：Windows-on-ARM EC 二进制，配 **FEXCore** 翻 x86 游戏；**不是**「给 ARM 手机用的 DXVK」 | amphora **一律不用**（RFC D5：Box64 + **x86_64** Wine） |
| **无 `arm64ec` 后缀**（如 `Dxvk-3.0.2-gplasync`） | **x86_64** PE，由 Box64 翻译整棵 Wine 树 | **amphora 默认选这类** |
| **VKD3D `S6_9` / `sm69`** | 暴露较高 Shader Model（6.9） | 较新 DX12；与 `VKD3D_SHADER_MODEL` env 配合 |
| **VKD3D `3.0a` / `3.0b` / `Tfix` / `tilting`** | 同期实验/修复分支 | 默认 3.0.1-S6_9 出问题再试 |
| **nightly + short hash** | 滚动构建，未进 Stable 目录 | 调试上游；不要当 MVP 默认 |

> **易混点**：真机是 **arm64-v8a Android**，但 guest 里跑的是 **x86_64 Wine**（外面套 Box64）。所以要下 **不带 `arm64ec`** 的 `.wcp`。带 `arm64ec` 的包是给「arm64ec Proton + FEX」那条 WinNative 路线的；装错 ABI 会直接对不上 `ContentsManager`/DLL 架构。`a6xx` 则是 **Adreno GPU** 名，和 `arm64ec` 不是一类东西。

**和 WineD3D / Zink 的关系**：AIO **OpenGL** 走 Wine `opengl32` → EGL →
Mesa **Zink**。32-bit DirectDraw 在 DxWrapper Dd7to9、cnc-ddraw（D3D9
renderer）和 d7vk（D3D3–7 直转 Vulkan）中三选一；缺包时启动失败。三套路径都
不部署 x86_64 DLL，因此 64-bit DirectDraw 走 Proton builtin `ddraw` → WineD3D
→ EGL/Zink。

「有 FPS 但黑屏」历史原因是 launch env 漏合并容器 `DEFAULT_ENV_VARS`（`ZINK_DESCRIPTORS` / `TU_DEBUG=noconform,sysmem` / `mesa_glthread`）。这些变量现在只服务 OpenGL EGL/Zink；DirectDraw 强制走 native wrapper → D3D9/DXVK。

**默认目录**：设备端下载走 manifest `wcpCatalogUrl`（`default.json`），当前 Stable 默认只挂少数条目（含我们锁定的 DXVK/VKD3D）；完整历史包仍在 GitHub `Stable-Dxvk` / `Stable-VKD3D` release 资产列表里。

**D4 native download stub（有意保留）**: `native_content_io.cpp` 的
`nativeDownloadFile` / `nativeFetchContentLength` 仍返回失败——**设备下载不走 JNI**。
生产路径是 Kotlin `RemoteContentSource` + `VerifiedAssetDownloader`（可续传 + SHA），
按 GitHub API / raw 上的 `content_manifest.json` pin 拉取 `.wcp` / ARCHIVE / ROOTFS。
不要把 native stub 理解成「设备不能下载」。
- **历史诊断路径**（仍可离线使用）：host `curl` 下载 `.wcp` → `adb push` →
  `ContentsManager.extraContentFile` 本地安装。生产路径不计划恢复 JNI curl。
