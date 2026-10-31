# 03 · 资产清单与 SHA 锁 (Asset Manifest)

> **生产 pin 的唯一真源是 `amphora-dev/content_manifest` 仓库的 `content_manifest.json`。**
> 本文不写死版本号；要查当前 pin 直接读那个文件（本地 checkout `../content_manifest`）。
> 清理前完整历史：`git show dd99971:docs/03-ASSET-MANIFEST.md`。

## 怎么读当前 pin

- `components`（rootfs / wine / box64 / dxvk / dxvk_sarek / dxvk_d3d89 / vkd3d …）与
  `runtimeAssets`（wrapper / fonts / wincomponents / ddraw 包装器 / adrenotools /
  metadata / AIO …）里的每一项都带 `remoteUrl` + `sha256` + `size`；文件名、大小、
  SHA 和 URL 都以 manifest 为准，发布流程可以在不修改本文的情况下更新 pin。
- **设备侧**：`RemoteContentSource`（`:core:content`）经 GitHub Contents API（raw 回退）
  拉取 manifest，按 SHA-256 校验、原子安装到 `filesDir/contents/`；解析 URL 走
  `RemoteUrlResolver`——entry 缺 `remoteUrl` 时按文件名在 `wcpCatalogUrl`
  指向的 catalog 里查（见 §3）。
- **开发态临时换包**：`filesDir/content/dev_pins.json` 覆盖层，见
  [`07-DEV-PIN-OVERLAY.md`](07-DEV-PIN-OVERLAY.md)。
- **例外**：PulseAudio 不走远程 manifest——`pulseaudio.tzst` 与 PA13 Android JNI
  库随 APK 固定交付，避免 Wine 客户端、守护进程和模块跨版本漂移。

rootfs 是 **Bionic libc**（`libc.so -> /system/lib64/libc.so`，无 glibc 标记）；
`com.winlator.cmod` 内核期望的正是这套 rootfs。当前格式 `imagefs.txz`（tar + xz），
由 `ImageFsRootfsInstaller` 按 manifest 的 `compression` 字段提取。

---

## 1. 分类总览（按落地根与安装机制）

按「解压后落到哪个根 + 谁负责安装」组织，这决定覆盖域、冲突范围和更新代价。
「默认」= 冷启动必备；「可选」= 按需下载。

| 类 | 落点 | 安装者 | 说明 |
|---|---|---|---|
| `imagefs.txz` | `filesDir/imagefs`（`usr/` 布局） | `RootfsInstaller` | 唯一 rootfs 基线；含自建 Mesa GL、ALSA、X11/DRM 底座 |
| `wrapper.tzst` | imagefs `usr/lib` + ICD | `TarCompressorUtils` | `libvulkan_wrapper` + `libadrenotools` + hooks；**hooks 唯一来源**（guest env + host `hookLibDir`），APK 不打第二份 |
| `Proton-*.wcp` / `Box64-*.wcp` | `filesDir/contents/<type>/<ver>/` | `ContentsManager.extraContentFile` | WCP 有 zstd / xz 两种压缩，安装器先试 ZSTD 再试 XZ |
| `Dxvk-*.wcp` / `Vkd3d-*.wcp` | 容器 `system32`/`syswow64` | 同上 | Windows DLL 覆盖，容器级版本切换 |
| `wincomponents/*.tzst` | `contents/WINCOMPONENTS/<id>-<sha>/`，prefix 只软链 | `TarCompressorUtils` | 微软 redist；按 `wincomponents.json` `FALLBACK` 选装；保持 tzst，不改 WCP |
| `ddrawrapper/{dd7to9,cnc-ddraw}.tzst` + `d7vk.zip` + `nglide.tzst` | `contents/DDRAW/<id>-<sha>/` → 容器 `syswow64` | 同上 | DirectDraw 三选一 + nGlide 独立；**互斥**，UI/安装器只落一份；均只部署 PE32 `syswow64/ddraw.dll`，64-bit ddraw 回退 Proton builtin → WineD3D |
| `fonts.tzst` | `contents/FONTS/<sha>/`（全局共享一份） | 同上 | Source Han CN+JP 回退 + 真实 Windows 字体优先 |
| `graphics_driver/mesa-wgl.tzst` | `contents/MESAWGL/mesa-wgl-<sha>/` → 每个 prefix 的 `system32`/`syswow64` `opengl32.dll` + `libgallium_wgl.dll` 软链 | `MesaWglRuntime`（`RuntimeAssetDllCache`） | Mesa 的 Windows OpenGL（zink + softpipe，x86_64 + i686 PE），`WINEDLLOVERRIDES` 带 `opengl32=n,b`；zink 起不来时 Mesa 自己回退 softpipe。imagefs `build-mesa-wgl` 构建，Release `mesa-wgl` |
| `WN-Turnip-*.zip` | `contents/adrenotools/<id>/` | `TurnipDriverProvisioner` | 可选完整 Turnip，单选，见 §4 |
| `gpu_cards.json` / `startmenu.json` / `wincomponents.json` / `default.box64rc` | 各处 | 直接拷 | 裸元数据 |
| `pulseaudio.tzst` + PA13 `jniLibs` | `filesDir/pulseaudio-runtime` / `nativeLibraryDir` | `PulseAudioRuntimeSupport` | APK 固定交付，见上文「例外」 |

prefix 只从 Proton `prefixPack.txz` 生成（没有独立容器模板包）；
`layers.tzst`（Vulkan validation）默认不装；`mesa-gl-override.tzst` 是排查
OpenGL 的调试覆盖包，不进发布默认集。

## 2. 通道规则（必须遵守）

1. **imagefs 唯一基线**；换 TLS/媒体/Mesa GL 才重发；**永不**因换 wrapper/hooks/Turnip/字体重打。
2. **wrapper + hooks 同包单独发版**（`wrapper.tzst` ARCHIVE），两者**必须同版本**——
   `libadrenotools` 把含 `std::string` 的 `HookImplParams` 跨 `.so` 传给 `libhook_impl`，
   字段布局错配即崩；任何一侧升级都要重新比对构造签名。
   hooks 全局只留一份（随 wrapper → `imagefs/usr/lib`）；`vulkan.c` 经
   `ImageFs.find(context).getLibDir()` 取 hooks 并校验 `libhook_impl.so` 存在。
3. **imagefs 原生 Mesa GL 不单独拆包**（Linux 侧 OpenGL 无插拔需求，私有部分只有 `libglapi`）。
   Windows 程序的 OpenGL 是另一份：`graphics_driver/mesa-wgl.tzst`（见 §1）。
4. **Turnip** 只有可选 zip 一条路径；`ADRENOTOOLS_DRIVER_NAME` 仅在用户点选时设置。
5. **字体** 全局一份；多容器不重复。
6. **发布面**：默认 pin / 产物走公开 GitHub Release（`amphora-dev/*`）+
   `content_manifest`（GitHub Contents API，raw 回退）；不进默认 APK。

### 仍来自第三方的 pin

核心 pin（rootfs / wine / box64 / dxvk / vkd3d / wrapper / fonts）已由
`amphora-dev/imagefs` 自建发布。以下条目仍指向第三方（`../content_manifest/content_manifest.json` @ `f9cfa31`）：

| 资产 | 来源 |
|---|---|
| `graphics_driver/wrapper-leegao.tzst` | `raw.githubusercontent.com/WinNative-Emu/WinNative` @ `48fe6b9` |
| `wincomponents/{wincomponents.json,direct3d,directsound,directmusic,directshow,directplay,xaudio,vcrun2010}.tzst(.json)`（8 项） | 同上 |
| `metadata/{gpu_cards,startmenu}.json`（2 项） | 同上 |
| `box86_64/default.box64rc` | 同上 |
| `ddrawrapper/{nglide,dd7to9,cnc-ddraw}.tzst`（3 项） | 同上 |
| `adrenotools/WN-Turnip-1.06-b_Axxx.zip` | `github.com/WinNative-Emu/Drivers` release `v1.06` |
| `ddrawrapper/d7vk.zip` | `github.com/WinterSnowfall/d7vk` release `v2.0` |
| `components.dxvk_sarek`（`Dxvk-Sarek-async-1.11.wcp`） | `github.com/nicholasx417/WinNative-Components` release |
| `wcpCatalogUrl`（`default.json`，URL 缺失兜底） | `raw.githubusercontent.com/nicholasx417/WinNative-Components` |
| `aio-graphics-test/Graphics-Test-{32,64}bit.exe` | `cnb.cool/atowerlight/aio-graphics-test`（amphora fork） |

即：15 条 raw `WinNative-Emu/WinNative` 条目 + WinNative-Components（Sarek WCP +
catalog）+ `WinNative-Emu/Drivers`（Turnip）+ `WinterSnowfall/d7vk` + cnb.cool AIO fork。

## 3. 运行时组件 catalog 兜底

- 生产组件优先用 manifest 逐项固定的 `remoteUrl`。
- 某 WCP entry 缺 `remoteUrl` 时，`RemoteUrlResolver` 按文件名在 manifest 的
  `wcpCatalogUrl`（当前为 `nicholasx417/WinNative-Components` 的 `default.json`）
  里解析；**catalog 不能覆盖 manifest 的版本、大小或 SHA**。
- `ContentsManager.syncContents()` 只加载本地已装 profile（含无 `profile.json`
  的 fallback），不再读远程 catalog。
- 设备下载**不走 JNI**：`native_content_io.cpp` 只剩本地归档提取；下载链是 Kotlin
  `RemoteContentSource` + `VerifiedAssetDownloader`（续传 + SHA）。

## 4. 可选 adrenotools Turnip（WN-Turnip）

默认 **不**启用；设置页 GPU driver 可选：

| 选项 | `graphicsDriverConfig.version` | 含义 |
|---|---|---|
| **Wrapper**（默认） | `wrapper` | Guest ICD=`wrapper_icd`；adrenotools NAME 不设 → 系统 Adreno |
| **Turnip 1.06-b** | `WN-Turnip-1.06-b` | 下载 Drivers 的 zip → `contents/adrenotools/WN-Turnip-1.06-b/`；guest 仍用 wrapper ICD，但设 `ADRENOTOOLS_DRIVER_PATH/NAME=libvulkan_freedreno.so` |

pin 为 runtimeAssets `adrenotools/WN-Turnip-1.06-b_Axxx.zip`（SHA 见 manifest）；
首次点选时 `TurnipDriverProvisioner` 下载并 `AdrenotoolsManager.installFromZip`。

**ABI 易混点**：真机是 arm64-v8a Android，guest 里跑的是 **x86_64** Wine（外套
Box64）——只选**不带 `arm64ec` 后缀**的包；`arm64ec` 是给 WinNative 的
arm64ec Proton + FEX 路线的（RFC D5），`a6xx` 则是 Adreno GPU 名，两者不同类。

## 5. AIO Graphics Test pin

开始菜单里的 32/64 位图形诊断程序由 manifest `runtimeAssets` 固定
（`aio-graphics-test/Graphics-Test-{32,64}bit.exe`，设备端下载校验后复制到 Wine
`ProgramData/Microsoft/Windows`，与 `metadata/startmenu.json` 对应）。

当前 pin 指向 amphora fork `cnb.cool/atowerlight/aio-graphics-test`
（content_manifest `f9cfa31`；fork `b8acc49` = 上游 `The412Banner/AIO-Graphics-Test`
2.1.0 `582454d` + D3D8 窗口模式 `PresentationInterval` 修复）。APK 不带副本。
真机矩阵与判读见 [`09`](09-AIO-GRAPHICS-TEST.md)。
