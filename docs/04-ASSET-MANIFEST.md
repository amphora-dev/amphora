# 04 - 资产清单 / SHA 锁 (Asset Manifest)

> P2 资产获取轨产物。锁定 amphora 运行时所需的全部镜像/驱动/组件资产的来源、版本与 SHA-256。
> 最后更新: 2026-07-30 · 资产源: WinNative @ `48fe6b9` (Git LFS) + `winlator-imagefs` 可复现构建配方
> **目标结构决策见 §0.6**（已拍板项会覆盖 §0.5 现状描述中的过渡安排）。

---

## 0. 架构定性 (关键)

WinNative (amphora 移植源) 属 **Pipetto-crypto `winlator_bionic` 血脉**, rootfs 是 **Bionic libc** (非 glibc):
- 所有 ELF 链接 `/system/bin/linker64`, `libc.so -> /system/lib64/libc.so` (无 `.so.6` 后缀)
- amphora 移植的 `com.winlator.cmod` 内核期望的就是这套 Bionic rootfs -- **资产与内核兼容** ✅
- 真机验证设备: Lenovo TB322FC, arm64-v8a, API 36, **Adreno 830** (Turnip 驱动目标 GPU)

**rootfs 压缩格式**: `imagefs.tzst` (tar + zstd)。amphora `ImageFsRootfsInstaller` 用
`TarCompressorUtils.extract(Type.ZSTD, ...)` 提取 -- 与资产格式直配, 无需转码。

> ⚠️ 注意: `winlator-imagefs` (cnb.cool) 仓产出的是 `imagefs.txz` (xz, 18MB 重建版, SHA
> `af66e28b...`), 与本表锁定的 WinNative `imagefs.tzst` (zstd, 190MB 原版) 是**两套不同构建**。
> cnb.cool 仓是**可复现构建配方** (备选自建路径), 详见 §3。amphora 当前直用 WinNative 原版资产。

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
| `imagefs.tzst` | zstd | 199.8 MB | 10892 | rootfs 本体（`bin`/`etc`/`lib`/`opt`/`usr`…）| `RootfsInstaller` | **imagefs**（基线） |
| `extra_libs.tzst` | zstd | 21.1 MB | 14 | `usr/lib` 的 Mesa `libGL`+`libglapi` / Turnip / vkBasalt / bcn_layer，`usr/share/vulkan` 的 ICD + 隐式层 JSON | `TarCompressorUtils` | graphics（待拆） |
| `layers.tzst` | zstd | 4.4 MB | 3 | `usr/lib/libVkLayer_khronos_validation.so` | `TarCompressorUtils` | graphics（调试可选） |
| `wrapper.tzst` | zstd | 3.8 MB | 12 | `usr/lib` 的 `libadrenotools` + `libvulkan_wrapper` +（历史）4 个 hook，`usr/share/vulkan/icd.d/wrapper_icd.aarch64.json` | `TarCompressorUtils` | **wrapper+hooks（独立）** |

### B. 模拟器 / Wine 运行时 — WCP，落 imagefs

| 资产 | 压缩 | 大小 | 内容 |
|---|---|---|---|
| `Proton-10.0-4-x86_64.wcp` | **zstd** | 168.6 MB | `bin/` `lib/` `share/` `prefixPack.txz` `profile.json` |
| `Box64-0.4.3-c08554e3f.wcp` | xz | 2.8 MB | `box64` + `profile.json`（仅 2 条目）|

### C. DirectX 翻译层 — WCP，但内容是 Windows DLL，落容器 `system32`/`syswow64`

| 资产 | 压缩 | 大小 | 提供 |
|---|---|---|---|
| `Dxvk-3.0.2-gplasync.wcp` | xz | 6.7 MB | `d3d8` `d3d9` `d3d10core` `d3d11` `dxgi` |
| `Vkd3d-3.0.1-S6_9.wcp` | xz | 3.4 MB | `d3d12` `d3d12core` |

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
| `dd7to9.tzst` | 3.0 MB | `ddraw.dll` + `dxwrapper.dll` + `.ini` | ⚠️ 与 `cnc-ddraw` **互斥** |
| `nglide.tzst` | 1.2 MB | `glide` `glide2x` `glide3x` `3DfxSpl*` | Glide，不与前两者冲突 |
| `cnc-ddraw.tzst` | 0.17 MB | `ddraw.dll` | ⚠️ 与 `dd7to9` **互斥** |

### F. 容器模板 — 落容器目录，**每个容器一份**

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

### 归类暴露出的问题

**C 与 D 同构却用两套机制。** 两者解压后都是 `system32/` + `syswow64/` 的 DLL 覆盖，
唯一区别是 C 多一个 `profile.json` 声明 `files[]` 映射并走 `ContentsManager`，D 靠固定
路径走 `TarCompressorUtils`。分野的实际理由是版本管理需求——DXVK/VKD3D 要允许用户换
版本，微软 redist 不需要——而不是技术差异。

**`.wcp` 有两种压缩格式。** `Proton` 是 zstd，`Box64`/`Dxvk`/`Vkd3d` 是 xz。所以
`ContentsManager.extraContentFile` 必须先试 ZSTD 再试 XZ，§5 里「`.wcp` = xz 压缩 tar」
的表述不准确。

**`cnc-ddraw` 与 `dd7to9` 互斥。** 两者都提供 `syswow64/ddraw.dll`，同时提取后者覆盖
前者。`03-TRACKING.md` 里记的「注入 dd7to9 回退导致 DX9–11 挂」很可能与这个覆盖有关。

**Turnip 有两份并存且版本不同。** `extra_libs.tzst` 里 11.5 MB（2025-08-07，已 strip），
`WN-Turnip` zip 里 14.8 MB（2026-07-22）。后者装到 `filesDir/contents/adrenotools/`，通过
`ADRENOTOOLS_DRIVER_PATH` 指定，不覆盖前者。imagefs 本身不带 Turnip。

**A 类的覆盖顺序有实际后果（过渡期）。** 现状三份 hook：imagefs / `wrapper.tzst` /
APK `nativeLibraryDir`，版本彼此漂移。**目标（已拍板）**：hooks 只认 `nativeLibraryDir`；
imagefs **不**再携带 hooks；`wrapper.tzst` 与 hooks **同属独立更新通道**，不焊进 imagefs
（见 §0.6）。

**`extra_libs.tzst` 73.3% 是调试符号。** 实测 `.debug_*` + 符号表占 82.2 MB / 112.2 MB
（解压后）：`libGL.so.1.5.0` 80.7 MB 里 66.5 MB 是调试信息，strip 后 14.2 MB；
`libvkbasalt.so` 17.5 MB → 3.3 MB；只有 `libvulkan_freedreno.so` 上游已 strip。
另外 `libvkbasalt.so` 的隐式层要求 `ENABLE_VKBASALT=1`，而代码库中无处设置，
在目标设备上它与 `libbcn_layer.so`（条件为非高通）都不会加载。

---

## 0.6 目标包结构（按更新频率切开）

> 原则：一根基线包、按更新频率切开、可替换独立、同构用同一机制。
> 2026-07-30 起陆续拍板；未标注「已拍板」的仍待确认。

| 通道 | 典型产物 | 落地 | 更新节奏 | 状态 |
|---|---|---|---|---|
| **imagefs** | 自建 `imagefs.tzst`/`txz` | imagefs 根 | 慢（libc/media/TLS 基线） | 配方仓推进中 |
| **wrapper + adrenotools hooks** | `wrapper.tzst`（`libvulkan_wrapper` / `libadrenotools` / ICD）+ 4 hook `.so` | wrapper → imagefs `usr/lib`+ICD；**hooks → 仅 `nativeLibraryDir`** | **独立、可单独发版** | **已拍板** |
| **graphics（其余）** | 从 `extra_libs` 拆出的 libGL / 可选 Turnip / layers… | imagefs `usr/` 或 `contents/adrenotools/` | 中 | 待拆分定稿 |
| **runtime** | Proton / Box64 `.wcp` | WCP → imagefs | 中–快 | 现状保留 |
| **dx** | DXVK / VKD3D `.wcp` | 容器 `system32`/`syswow64` | 快 | 现状保留 |
| **wincomponents** | 微软 redist tzst | 容器 DLL 覆盖 | 慢 | 是否改 WCP 待定 |
| **ddraw** | cnc-ddraw / dd7to9 / nglide | 容器 `syswow64`（互斥单选） | 按需 | 互斥 UI 待做 |
| **container-pattern** | prefix 模板（建议拆共享 fonts） | 每容器一份 | 慢 | 字体共享待定 |
| **meta** | json / box64rc | 直拷 | 随配置 | 现状保留 |

### 已拍板：`wrapper` + adrenotools hooks 单独更新

1. **不焊进 imagefs。** 换 wrapper ICD / hooks **不得**要求重打或重下整包 imagefs。
   自建 imagefs **不再打包** 4 个 adrenotools hook（官方 blob 里若有同名文件，运行时也
   **不以** imagefs/`wrapper.tzst` 内 hook 为准）。
2. **hooks 契约。** `ADRENOTOOLS_HOOKS_PATH` **必须**是
   `ApplicationInfo.nativeLibraryDir`（`adrenotools/driver.h`）；APK 经 `useLegacyPackaging`
   解压落盘。hooks 随 APK / native 产物发版，与 imagefs 版本解耦。
3. **wrapper 仍是独立 ARCHIVE。** `graphics_driver/wrapper.tzst`（或后续同名通道产物）
   单独 SHA 锁定、单独发布；guest 侧 ICD/`libvulkan_wrapper`/`libadrenotools` 从该包提取。
   历史包内若仍含 hook `.so`，只作兼容残留，**运行时不依赖**它们。
4. **同通道共版。** wrapper ICD 与 hooks 视为一条「Vulkan adrenotools 栈」——可同一次
   Release 发两个附件，但**绝不是** imagefs 附件；也**不要**并回 `extra_libs` 一锅。

可选 Turnip（`WN-Turnip-*.zip` → `contents/adrenotools/<id>/`）继续走驱动插拔通道，
不与 wrapper+hooks 绑死；默认 Wrapper 模式不设 `ADRENOTOOLS_DRIVER_NAME`。

---

## 1. 根文件系统 (rootfs / imagefs)

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

## 2. 图形驱动 / DX 包装层 / 组件 (SHA-256)

> 全部来自 WinNative `app/src/main/assets/` (本地直存, 非 LFS)。Turnip = `graphics_driver/wrapper.tzst` (Mesa Vulkan ICD 包装器, `extractGraphicsDriverFiles` 的提取目标)。box64 二进制运行时 `.wcp` 下载 (见 §5), 这里只锁 `.box64rc` 配置。

### 2.1 graphics_driver/ (Turnip / VirGL / Zink / extra)
| 资产 | SHA-256 |
|---|---|
| `wrapper.tzst` (Turnip/Mesa Vulkan ICD, **MVP 单驱动**) | `2651fbe6372af36c7d269664416b4f62d959125122ad3b8f79a787788e510fd8` |
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
| `pulseaudio.tzst` | `357bb53fbcf91ab2adcd2e6a4b7fc3f2cb95f1555610681b08dbf6d412ac4bd8` | PA 模块 (glibc/PA17, 与 Bionic imagefs 的 PA13 模块有别) |
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
`ProgramData/Microsoft/Windows`（APK assets / `testdata/aio-graphics-test/` 作离线兜底）：

| 资产 | SHA-256 | 大小 | remoteUrl |
|---|---|---:|---|
| `Graphics-Test-32bit.exe` | `75589dc37b72d509e23c9c3c043fdf8e03855e5d2f1ec846efe2672662719306` | 2,083,443 | `.../releases/download/amphora/AIO-Graphics-Test-32bit.exe` |
| `Graphics-Test-64bit.exe` | `96d76d077139ef469eff31efbc75cd9202b99bf2906b97e3c5de07dc350f5c57` | 2,065,494 | `.../releases/download/amphora/AIO-Graphics-Test-64bit.exe` |

CI 重建后 SHA 会变：更新本表与 `content_manifest.json` 即可。

> `proton-9.0-x86_64.txz` (Wine/Proton 主二进制) 在 WinNative assets 内**未见** -- 走 build.gradle `downloadProton` 任务从 GitLab 下载 (见 §3)。Amphora 生产路径由 `RemoteContentSource` 在设备上下载并校验 manifest 中固定的 Proton WCP，不再要求 build 时打入 APK。

---

## 3. 可复现构建 (备选, 自建 imagefs 而非依赖 LFS blob)

| 来源 | URL | 产物 | SHA-256 | 说明 |
|---|---|---|---|---|
| cnb.cool 仓 (用户指定) | `https://cnb.cool/atowerlight/winlator-imagefs` (HEAD `2daa55c`) | `imagefs.txz` (xz, 18 MB) | `af66e28b61577a0cd8433155ee2123d910f02f7870b8938994d27d81281372e3` | CI 构建配方 (39 包, NDK r29 交叉编译, Bionic). 产物作 commit 附件上传 (无 git tag/release). 本地浅克隆 1.2 MB (仅脚本+docs) |
| GitLab winlator-extra | `https://gitlab.com/winlator3/winlator-extra/-/raw/main/imagefs/imagefs.txz.{00-03}` | `imagefs.txz` (4×50 MB 分卷) | `f96d362b7e148e86ab0d2c290978bf39b38e5c7ffc8ae4adf1d2a65c62bbb780` | Pipetto-crypto build.gradle `downloadImageFS` 的原始源 |

> cnb.cool 重建版 (18 MB, 精简 39 包) 与 WinNative 原版 (190 MB, 全量 877 MB 解压) **内容不同**。amphora 当前用 WinNative 原版 (与内核期望直配, `.tzst`/zstd)。若未来要自建精简 rootfs, cnb.cool 配方可用, 但需把产物 `imagefs.txz` 转 `imagefs.tzst` (xz 解 -> zstd 压) 或扩 `ImageFsInstaller` 支持 XZ 分片 (RFC §7 内核原样复用, 不建议改)。

---

## 4. 待办 (资产侧)

- [x] `:core:content` `BundledContentSource` (2026-07-13): `content_manifest.json` (本表派生) + SHA-256 流式校验 + ARCHIVE(`TarCompressorUtils`)/WCP(`ContentsManager.extraContentFile`) 双路径。`.wcp` SHA 已锁 ✅ (2026-07-14, gap #1)。详见 03-TRACKING §P2 #8。
- [x] build 时资产 staging `:app:stageBundledContent` (2026-07-14): manifest 驱动; ARCHIVE 从 WinNative 拷 (SHA 校验) + WCP 从 nicholasx417 GitHub releases 下载, 入 `app/src/main/assets/` (git-ignored)。Best-effort (不破构建), standalone (不 wire preBuild -- 避免 160M Proton 膨胀每次 debug APK)。`.wcp` SHA 已锁 ✅ (2026-07-14, gap #1; wine=`e61d29be8c736abe13f662d33ff4b14fae2b7294b011283be53c8e33665d2b48` / box64=`eec659650ff31df151c13d2a522330b1636b98cd82dbf60ba3ff522759f528fd`)。详见 03-TRACKING §P2 #9。
- [x] 真机 preparer 验证: `RemoteContentSource` 下载 Proton/Box64 `.wcp` + `createContainer` + `extractGraphicsDriverFiles`
- [x] `RemoteContentSource`: Kotlin HTTPS 下载、续传、SHA/大小校验和设备缓存；`nativeDownloadFile` 保持非生产 stub

---

## 5. 运行时组件生态 (.wcp 下载源) -- nicholasx417/WinNative-Components

> 用户指认 + `ContentsManager.java:29` 核实: 运行时组件 (`.wcp` = Winlator Component Package) 从 **`nicholasx417/WinNative-Components`** 下载, 非 `WinNative-Emu/Components`. 这解释了为何 Proton/box64 二进制不在 imagefs/assets -- 它们是**运行时按需下载**, 非打包.

**清单源** (`ContentsManager.REMOTE_PROFILES`):
```
https://raw.githubusercontent.com/nicholasx417/WinNative-Components/refs/heads/main/contents.json
```
`ContentsManager.syncContents()` 拉此 JSON -> 列组件 -> 每个 `remoteUrl` 指向 GitHub release `.wcp` -> `downloadFile` + `finishInstallContent`/`applyContent` 装载.

**可用组件** (contents.json 抽样, verCode=0):
| 类型 | 版本 (抽样) | .wcp remoteUrl |
|---|---|---|
| **Proton** | `Proton-10.0-4-x86_64` | `.../releases/download/Proton/Proton-10.0-4-x86_64.wcp` |
| | `Proton-10-arm64ec-original` / `-unix` | (arm64ec, D5 砍) |
| **Box64** | `Bionic-Box64-0.4.3-8ee3d8f2c` (**匹配 Bionic imagefs**) | `.../releases/download/bionic-box64-nightly-.../Bionic-Box64-0.4.3-8ee3d8f2c.wcp` |
| | `Box64-0.4.3` / `0.3.9` / `0.3.8` / `0.3.7` | `.../releases/download/Stable-Box64/...wcp` |
| **DXVK** | `Dxvk-3.0.2-gplasync` (**amphora MVP 默认**) / `2.4.1-pre-reg` / `a6xx-*` / Sarek | `.../releases/download/Stable-Dxvk/...wcp` |
| **VKD3D** | `Vkd3d-3.0.1-S6_9` (**amphora MVP 默认**, profile `verName=3.0.1-sm69`) / nightly | `.../releases/download/Stable-VKD3D/...wcp` |
| Turnip / WineD3d | -- (不在 contents.json, 仍打包在 assets `graphics_driver/`/`dxwrapper/`) | -- |

> WinNative assets 的 `dxwrapper/d8vk-1.0.tzst` 仅作 DXVK &lt; 2.4 的 d3d8 补丁 (`extractD8VKIfNeeded`); amphora MVP 默认装 nicholasx417 的 `Dxvk-3.0.2-gplasync.wcp` (d3d8/9/10core/11/dxgi) + `Vkd3d-3.0.1-S6_9.wcp` (d3d12/d3d12core), 经 `ContentSource.resolve(DXVK|VKD3D)` + `ContentsManager.applyContent`. 容器 `dxwrapper` 形如 `dxvk-3.0.2-gplasync-0;vkd3d-3.0.1-sm69-0;none`。


### 5.1 amphora 当前锁定版本

| 组件 | 锁定包 | ContentsManager entry / wrapper token | 作用 |
|---|---|---|---|
| **DXVK** | `Dxvk-3.0.2-gplasync.wcp` | `DXVK-3.0.2-gplasync-0` → `dxvk-3.0.2-gplasync-0` | D3D8/9/10/11 → Vulkan（TB322FC 上 DX10/11 有画面） |
| **VKD3D** | `Vkd3d-3.0.1-S6_9.wcp` | profile `verName=3.0.1-sm69` → `vkd3d-3.0.1-sm69-0` | D3D12 → Vulkan（替换 Wine stub `d3d12.dll`） |
| **Proton** | `Proton-10.0-4-x86_64.wcp` | `Proton-10.0-4-x86_64-0` | Wine/Proton 运行时 + prefixPack |
| **Box64** | `Box64-0.4.3-c08554e3f.wcp` | `Box64-0.4.3-c08554e3f-0` | x86_64 → ARM64 用户态翻译 |
| **Turnip** | `graphics_driver/wrapper.tzst` | ARCHIVE `version=1` | Adreno Mesa Turnip + adrenotools wrapper ICD |

容器默认 `dxwrapper`：`dxvk-3.0.2-gplasync-0;vkd3d-3.0.1-sm69-0;none`（第三段 `none` = 不用 cnc-ddraw）。

选型原则：跟上游 **Stable** x86_64（非 arm64ec）。DXVK 默认 **3.0.2-gplasync**：在 TB322FC（Adreno 830 / Turnip）上 DX10/11 可用；曾试 `2.4.1-pre-reg` 想修 D3D9 黑屏，结果 DX9–11 **闪退**，已回退。DX8/DX9「有 FPS 无画面」仍为开放项（已定位 FF PSO compile `-13`；DX8 = DXVK D3D8 compatibility → 同一条 FF 路径）。候选未默认启用：`2.4.1-a6xx-fix` / `2.4.1-special+d8`。VKD3D 仍锁 **3.0.1-S6_9**。

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

**和 WineD3D / Zink 的关系**：`dxwrapper` 装的是 DXVK DLL，但 AIO **OpenGL** 与 **DX7/ddraw** 并不走 DXVK——它们是 Wine `opengl32` / Wine `ddraw` → WineD3D → Mesa **Zink**（`GALLIUM_DRIVER=zink`）→ Turnip。换 DXVK 版本治不了这条栈。

「有 FPS 但黑屏」典型原因（amphora 已修）：launch env 漏合并容器 `DEFAULT_ENV_VARS`（`ZINK_DESCRIPTORS` / `TU_DEBUG=noconform,sysmem` / `mesa_glthread`），SwapBuffers 仍返回（HUD FPS 涨）但帧黑；同时在默认 DXVK 模式下仍需下发 `WINE_D3D_CONFIG`（`renderer=gl`）给 ddraw→WineD3D。见 `WineEngineImpl.buildLaunchEnvVars` + `XServerWineSessionPreparer.extractGraphicsDriverFilesCore`。

**默认目录**：设备端下载走 manifest `wcpCatalogUrl`（`default.json`），当前 Stable 默认只挂少数条目（含我们锁定的 DXVK/VKD3D）；完整历史包仍在 GitHub `Stable-Dxvk` / `Stable-VKD3D` release 资产列表里。

**⚠️ D4 download stub (amphora 当前)**: `native_content_io.cpp:783` `nativeDownloadFile` 返回 `JNI_FALSE`, `nativeFetchContentLength` 返回 `-1` (RFC §10 D4 -- MVP 不做远程抓取, 符号保留避免 UnsatisfiedLinkError). 故 amphora 当前**无法在设备上直接 `syncContents`/下载 `.wcp`**.
- **preparer 真验可行路径** (绕过 stub): host `curl` 下载 `.wcp` -> `adb push` -> `ContentsManager.extraContentFile(Uri, callback)` 本地装 (走 `nativeExtractArchive`, 非 download) -> `createContainer` (抽 Wine prefix) -> 跑 preparer.
- **v0.3**: 恢复 curl body 解除 stub -> 设备直接下载.
