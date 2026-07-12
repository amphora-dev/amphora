# 04 - 资产清单 / SHA 锁 (Asset Manifest)

> P2 资产获取轨产物。锁定 amphora 运行时所需的全部镜像/驱动/组件资产的来源、版本与 SHA-256。
> 最后更新: 2026-07-12 · 资产源: WinNative @ `48fe6b9` (Git LFS) + `winlator-imagefs` 可复现构建配方

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

## 1. 根文件系统 (rootfs / imagefs)

| 资产 | 压缩 | 大小 (字节) | SHA-256 | 来源 |
|---|---|---|---|---|
| `imagefs.tzst` | zstd | 199,788,876 (190 MB) | `0902e324b60a5c234aa29fcf457f6475a38ef8f61ac2be2118daaef4f236499a` | WinNative Git LFS (`app/src/main/assets/imagefs.tzst`) |

**提取后**: ~877 MB, 10,892 条目, merged-usr 布局 (`bin`/`etc`/`lib`/`share`/`tmp` -> `usr/*` 符号链接)。
**Bionic 标记确认**: `usr/lib/libc.so -> /system/lib64/libc.so`, `libdl.so`/`libm.so` 同; **无** `libc.so.6`/`ld-linux` (glibc 标记)。
**关键库在位**: `libpulse.so`/`libpulseaudio.so`/`libpulsecommon-13.0.so` (PA 13.0), `libvulkan.so.1.4.315`, `libGL.so`, `libsndfile.so`, `libltdl.so`, `libandroid-spawn.so`; `etc/alsa/conf.d/android_aserver.conf` (Bionic ALSA 原生路径); `usr/share/wine/{fonts,nls}` + `winetricks`。
**不在 imagefs 内**: `box64` 二进制 (走 installable component), `libEGL.so`/`libGLESv2.so` (运行时用 `/system`), `home/xuser`/`tmp/.X11-unix` (运行时由 XEnvironment 创建)。
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

> 全部来自 WinNative `app/src/main/assets/` (本地直存, 非 LFS)。Turnip = `graphics_driver/wrapper.tzst` (Mesa Vulkan ICD 包装器, `extractGraphicsDriverFiles` 的提取目标)。box64 二进制在 imagefs 外 (installable), 这里只锁 `.box64rc` 配置。

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
| `dxwrapper/d8vk-1.0.tzst` (D3D8->Vulkan) | `da30a104f83f619214a3bc67c971434837e1a11ca30703d0f79041a93f2be246` |
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

> `proton-9.0-x86_64.txz` (Wine/Proton 主二进制) 在 WinNative assets 内**未见** -- 走 build.gradle `downloadProton` 任务从 GitLab 下载 (见 §3)。amphora P2 未接线, 待 `:core:content` BundledContentSource。

---

## 3. 可复现构建 (备选, 自建 imagefs 而非依赖 LFS blob)

| 来源 | URL | 产物 | SHA-256 | 说明 |
|---|---|---|---|---|
| cnb.cool 仓 (用户指定) | `https://cnb.cool/atowerlight/winlator-imagefs` (HEAD `2daa55c`) | `imagefs.txz` (xz, 18 MB) | `af66e28b61577a0cd8433155ee2123d910f02f7870b8938994d27d81281372e3` | CI 构建配方 (39 包, NDK r29 交叉编译, Bionic). 产物作 commit 附件上传 (无 git tag/release). 本地浅克隆 1.2 MB (仅脚本+docs) |
| GitLab winlator-extra | `https://gitlab.com/winlator3/winlator-extra/-/raw/main/imagefs/imagefs.txz.{00-03}` | `imagefs.txz` (4×50 MB 分卷) | `f96d362b7e148e86ab0d2c290978bf39b38e5c7ffc8ae4adf1d2a65c62bbb780` | Pipetto-crypto build.gradle `downloadImageFS` 的原始源 |

> cnb.cool 重建版 (18 MB, 精简 39 包) 与 WinNative 原版 (190 MB, 全量 877 MB 解压) **内容不同**。amphora 当前用 WinNative 原版 (与内核期望直配, `.tzst`/zstd)。若未来要自建精简 rootfs, cnb.cool 配方可用, 但需把产物 `imagefs.txz` 转 `imagefs.tzst` (xz 解 -> zstd 压) 或扩 `ImageFsInstaller` 支持 XZ 分片 (RFC §7 内核原样复用, 不建议改)。

---

## 4. 待办 (资产侧)

- [ ] `proton-9.0-x86_64.txz` (Wine/Proton 主二进制): 定位下载源 + SHA 锁 (GitLab `winlator-extra/proton/` 或 WinNative `downloadProton` 任务) -- `preparer`/launch 真验需要
- [ ] `:core:content` `BundledContentSource`: 用本清单 SHA 做首启解压校验 (manifest JSON 化)
- [ ] box64 二进制: 确认来源 (installable component from GitHub `brunodev85/winlator` 或 imagefs 内) + SHA 锁
- [ ] 真机端到端: imagefs.tzst 提取 (本轮) + `XServerWineSessionPreparer` 提取 wrapper.tzst/d8vk (下一轮, 需 Container + ContentsManager)
