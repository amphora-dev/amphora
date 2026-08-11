# Proton Wine (x86_64 for Android/Bionic) 自建路径调研

> 纯研究输出。调查对象：`WinNative-Emu/proton-wine`（fork 自 `ValveSoftware/wine`，默认分支 `proton_11.0`）。
> 调查样本：`proton_11.0` 分支（2026-07-09 checkout）。
> 结论用于支撑 Amphora「x86_64 + box64，自己从源码构建 Proton」决策。
> **现状更新（2026-08-11）**：该决策已落地为 `amphora-dev/imagefs` BuildStream
> 管线；本文保留上游调研，当前交付方式以 `04-ASSET-MANIFEST.md` 为准。

---

## 0. 一句话结论

上游 `WinNative-Emu/proton-wine` 提供完整源码、工作流和 Android 补丁，可作为
自建起点。Amphora 当前不再复用 1.2 GB termuxfs：BuildStream 只把精简 Bionic
sysroot 作为构建依赖，WCP 打包 Wine、`prefixPack.txz` 和 profile；运行库由
imagefs 提供。Pulse 路径另外用 PA13 开发输入构建 `winepulse.so`，运行时由 Box64
包装到 APK 内同 ABI 的 native `libpulse.so`。

### 0.1 为什么是 PulseAudio 13

这里不是为了功能而把一个已运行的 PA17 daemon “降级”。最初接入 Wine Pulse 驱动时
误用了 Termux PA17 开发包，而 APK 已交付的 daemon、`libpulsecore/common`、
`module-aaudio-sink` 和 Box64 native wrapper 全部属于 PA13 套件。Pulse 的
`libpulsecore`/`libpulsecommon` 是版本绑定的私有 ABI，混用开发输入会让
`winepulse.so` 获得 PA17 符号或依赖，不能由 PA13 运行时可靠满足。

因此构建输入改为 hash-pinned `pulseaudio_13.0-1_x86_64.deb`，并验证：

- 头文件 `PA_MAJOR == 13`、协议版本为 33；
- 开发库依赖 `libpulsecommon-13.0.so`；
- 最终 WCP 的 `winepulse.so` 只保留 `DT_NEEDED=libpulse.so`；
- WCP/imagefs 不携带 guest `libpulse`，运行时只能由 Box64 包装到 APK 的匹配实现。

旧 Termux 包当前**只作为 x86_64 交叉编译 SDK**，不会进入 WCP 或 imagefs。APK 内的
AArch64 PA13 二进制来自 WinNative 的匹配资产，因为当前仓库没有
`module-aaudio-sink` 的可追溯源码和完整构建配方。它能快速恢复端到端功能，但不是理想
终态：后续应在拿到许可与来源清晰的 sink 源码后，把 PA daemon、client、modules 和
依赖放入同一条 BuildStream 源码构建图，并增加 16 KB ELF 门禁。

imagefs 对“源码构建还是消费预构建”的完整原则和组件矩阵见
[`amphora-dev/imagefs/docs/BUILD-SOURCE-POLICY.md`](https://github.com/amphora-dev/imagefs/blob/main/docs/BUILD-SOURCE-POLICY.md)。

---

## 1. 构建工作流全貌

仓库 `.github/workflows/`（本地 + GitHub）共 4 个文件：

| 文件 | 触发 | 目标 | 关键差异 |
|---|---|---|---|
| `build-proton-sdk28.yml` | push/PR `proton_11.0b5` + manual | x86_64 + aarch64 | API 28（Android 9），无 16KB 页支持 |
| `build-proton-sdk35.yml` | push/PR `proton_11.0` + manual | x86_64 + aarch64 | API 35（Android 15），`--enable-16kb-pages`（`-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES` + `max-page-size=16384`） |
| `build-arm64ec-unixlib-steam.yml` | **manual only** | aarch64 only | Arm64EC + FEX UnixLibs + Steam ntdll，x86_64 不用 |
| `README.md` | — | — | 工作流说明文档（非常详尽） |

`sdk28` 与 `sdk35` 除分支名与 `--enable-16kb-pages` 外完全一致（diff 仅 8 处命名/flag）。**Amphora 应基于 `sdk35`**（Android 15+ 设备需要 16KB 页支持；API 35 目标在 API 28-34 设备上仍可运行）。

### 1.1 Job 结构（以 sdk35 为准）

- **build job**：matrix `arch: [x86_64, aarch64]`，`fail-fast: false`，并行。`runs-on: ubuntu-24.04`。
- **release job**：`needs: build`，仅在 push 到 main/master/proton_11.0 或 manual 时跑，用 `softprops/action-gh-release` 发日期 tag `build-<branch>-<YYYYMMDD>-sdk35`。

### 1.2 build job 步骤（x86_64 路径）

1. 释放磁盘（删 dotnet/ghc/boost/AGENTS_TOOLSDIRECTORY）。
2. `actions/checkout@v4`（`submodules: recursive`）。
3. 装构建依赖：`build-essential git wget curl unzip flex bison gettext autoconf automake libtool pkg-config mingw-w64 gcc-multilib g++-multilib libfreetype6-dev(:i386) libpng-dev(:i386) zlib1g-dev(:i386)`。
4. **下载 termuxfs**：`wget .../Assets/termuxfs-x86_64.tar` → 解压到 `$HOME/termuxfs/x86_64/`。
5. 缓存+装 **Android NDK r27d**（`27.3.13750724`）→ `$HOME/Android/Sdk/ndk/27.3.13750724`。
6. 缓存+装 **llvm-mingw**（`bylaws/llvm-mingw` 20250920 ucrt ubuntu-22.04-x86_64）→ `$HOME/toolchains/llvm-mingw-...`。
7. `build-scripts/build-step0-autogen.sh`（应用 `server_protocol.def.patch` + `./autogen.sh`）。
8. 缓存 **wine-tools**（key = `hashFiles('configure.ac','configure')`），未命中则 `build-scripts/build-step0.sh`。
9. `--build-sysvshm`（x86_64 也跑，但 workflow 里该 step 被 `if: matrix.arch == 'aarch64'` 限定 → **x86_64 实际不构建 sysvshm**；脚本内 x86_64 分支存在但 workflow 不触发）。
10. `--enable-16kb-pages --configure` → `build-step-x86_64.sh`。
11. `--enable-16kb-pages --build` → `make -j$(nproc)`。
12. `--enable-16kb-pages --install` → `make install` + 拷贝到 `$HOME/compiled-files-x86_64/{bin,lib,share}`。
13. **下载 prefixPack**：`wget .../Assets/prefixPack-x86_64-11.txz`。
14. 生成 `profile.json`（type=Proton）和 `profile-wine.json`（type=Wine）。
15. 打包：`tar cJf proton-11.0-1-x86_64.wcp bin lib share prefixPack.txz profile.json`；再 `tar cJf proton-wine-11.0-1-x86_64.wcp.xz ...`（type=Wine）。
16. `upload-artifact@v4`（30 天保留）。

### 1.3 产物

| 产物 | 格式 | 用途 | 内容 |
|---|---|---|---|
| `proton-11.0-1-x86_64.wcp` | `tar cJf`（xz） | WinNative（Proton type） | `bin/ lib/ share/ prefixPack.txz profile.json` |
| `proton-wine-11.0-1-x86_64.wcp.xz` | `tar cJf`（xz） | Winlator CMOD/Ludashi（Wine type） | 同上，`profile.json` 的 `type` 为 `Wine` |

`.wcp` 本质就是 xz 压缩的 tar。`profile.json`：
```json
{
  "type": "Proton",
  "versionName": "11.0-1-x86_64",
  "versionCode": 1,
  "description": "...",
  "files": [],
  "wine": { "binPath": "bin", "libPath": "lib", "prefixPack": "prefixPack.txz" }
}
```

**重要**：上游 release（`Proton-11-Beta-2`，2026-04-29）只发布了 **arm64ec** 的 `.wcp`（各 194 MB）。**x86_64 的 `.wcp` 仅存在于 CI artifact（30 天过期），没有长期 release**。所以「下载预编译 x86_64」= 依赖会过期的 CI artifact，或自己 fork 跑 CI。

---

## 2. build-scripts 步骤（x86_64）

`build-scripts/build-step-x86_64.sh` 是一个多 flag 脚本（`--enable-16kb-pages` / `--build-sysvshm` / `--configure` / `--build` / `--install`），核心环境：

```bash
ARCH=x86_64
WIN_ARCH=x86_64,i386          # 同时构建 64 位 + 32 位 PE（wow64）
OUTPUT_DIR=$HOME/compiled-files-x86_64
deps=$HOME/termuxfs/x86_64/data/data/com.termux/files/usr   # termuxfs 解压后的 $PREFIX
RUNTIME_PATH=/data/data/com.termux/files/usr                 # 运行期 rpath（设备上的路径）
install_dir=$deps/../opt/wine

TOOLCHAIN=$HOME/Android/Sdk/ndk/27.3.13750724/toolchains/llvm/prebuilt/linux-x86_64/bin
LLVM_MINGW_TOOLCHAIN=$HOME/toolchains/llvm-mingw-20250920-ucrt-ubuntu-22.04-x86_64/bin
TARGET=x86_64-linux-android28   # --enable-16kb-pages 时切到 x86_64-linux-android35
CC=$TOOLCHAIN/$TARGET-clang     # NDK clang（unix 侧，链接 Bionic libc）
DLLTOOL=$LLVM_MINGW_TOOLCHAIN/llvm-dlltool   # PE 侧用 llvm-mingw

C_OPTS="-march=x86-64 -mtune=generic -Wno-declaration-after-statement -Wno-implicit-function-declaration -Wno-int-conversion"
LDFLAGS="-L$deps/lib -Wl,-rpath=$RUNTIME_PATH/lib"   # 运行期从设备 /data/data/com.termux/files/usr/lib 找库
# freetype/pulse/SDL2/fontconfig/X/gstreamer/ffmpeg 的 CFLAGS/LIBS 全部指向 $deps
```

**configure 关键 flag**：
```
--enable-archs=x86_64,i386   # PE 双架构（64+32 位 Windows 二进制）
--host=x86_64-linux-android28
--with-mingw=clang            # 用 llvm-mingw 做 PE 交叉编译
--with-wine-tools=./wine-tools
--enable-win64 --disable-win16
--enable-nls --disable-tests
--enable-wineandroid_drv=no   # 不用旧 wineandroid 驱动
--with-alsa --with-fontconfig --with-freetype --with-gnutls --with-gstreamer
--with-opengl --with-pthread --with-pulse --with-sdl --with-vulkan
--without-xcomposite/xfixes/xinerama/xrandr/xrender/xshape/xxf86vm
--without-wayland/dbus/cups/udev/usb/v4l2/...
```

**步骤**：
- `--configure`：跑 `./configure`，然后**应用 44 个补丁**（见 §4）。
- `--build`：`rm -rf OUTPUT_DIR/{bin,lib,share} install_dir; make -j$(nproc)`。
- `--install`：`make install`，拷 `bin/wine* bin/reg* bin/msi* bin/notepad`、`lib/wine`、`share/wine` 到 `OUTPUT_DIR`，并建符号链接 `bin/wine -> ../lib/wine/x86_64-unix/wine`、`bin/wine-preloader -> ../lib/wine/x86_64-unix/wine-preloader`。

**wine-tools（build-step0.sh）**：构建原生（host）Wine 工具，用最小 configure（`--without-x --without-gstreamer --without-vulkan --without-wayland`），`make __tooldeps__ nls/all`。交叉编译 Wine 的标准做法（工具跑在 host 上生成文件）。按 `configure.ac`/`configure` 哈希缓存。

**autogen（build-step0-autogen.sh）**：先 `git apply server_protocol.def.patch`，再 `./autogen.sh`。**必须在 autogen 前应用**，因为 `make_requests` 在 autogen 阶段处理 `server/protocol.def` 生成协议代码。该补丁在 `build-step-x86_64.sh` 的 PATCHES 数组里被注释掉（避免重复应用）。

---

## 3. 三个外部依赖的准备方式

### 3.1 termuxfs（~1.2 GB，最重的依赖）

- **是什么**：一个完整的 Termux `$PREFIX`（`/data/data/com.termux/files/usr`），装了 Wine 需要的 dev 包：freetype、pulseaudio、SDL2、fontconfig、gstreamer、glib、gnutls、vulkan、alsa-lib 等，全部为 Android Bionic 编译。
- **构建期怎么用**：解压到 `$HOME/termuxfs/x86_64/`，`$deps=$HOME/termuxfs/x86_64/data/data/com.termux/files/usr` 提供头文件（`-I$deps/include`）、库（`-L$deps/lib`）、pkg-config（`PKG_CONFIG_LIBDIR`）、aclocal。
- **运行期怎么用**：`RUNTIME_PATH=/data/data/com.termux/files/usr` 被 bake 进 `LDFLAGS=-Wl,-rpath=$RUNTIME_PATH/lib`。即设备运行时，Wine 的 `.so` 会从设备上 `/data/data/com.termux/files/usr/lib` 找依赖库 → **APK 必须把 termuxfs 的 lib 部署到设备的这个路径**（或 Amphora 的 imagefs 里建同名软链/拷贝）。
- **怎么准备**：**仓库里没有 termuxfs 构建脚本**（`find` 确认）。workflow 直接从 release `Assets` 下载预编译：
  - `termuxfs-x86_64.tar` = **1182.4 MB**，下载 8 次。
  - `termuxfs-aarch64.tar` = 1135.5 MB，下载 8 次。
- **从零重建 termuxfs 的路径**（若要做）：用 `termux/termux-packages` 的 `scripts/run-docker.sh` + `build-package.sh` 逐个编译所需包（freetype/pulseaudio/SDL2/fontconfig/gstreamer/glib/gnutls/vulkan-loader/alsa-lib/...），输出到 `termuxfs` 根，然后 `tar` 整个 `/data/data/com.termux/files/usr`。**工程量大**：termux-packages 在 Docker 里跑，单包几分钟~十几分钟，几十个包 + 依赖闭包，全程数小时，产物 1.2 GB。**MVP 不建议自建**。

### 3.2 mingw 工具链（PE 交叉编译）

- **用 `bylaws/llvm-mingw`**（20250920，ucrt，ubuntu-22.04-x86_64），不是 GNU mingw-w64。
- 路径：`$HOME/toolchains/llvm-mingw-20250920-ucrt-ubuntu-22.04-x86_64/bin`，加入 `PATH`。
- `configure --with-mingw=clang` 让 Wine 用 clang 驱动 llvm-mingw 生成 x86_64/i386 PE（Windows `.dll`/`.exe`）。
- `DLLTOOL=llvm-dlltool`。
- 注：workflow 也 apt 装了 `mingw-w64`，但实际 `--with-mingw=clang` 走的是 llvm-mingw。apt 的 mingw-w64 可能是某些 host 工具的兜底。
- **llvm-mingw 是预编译发布**（bylaws/llvm-mingw releases），直接下载解压，无需自建。ucrt 变体（vs msvcrt）是现代选择。

### 3.3 prefixPack.txz（wineboot 生成的 Wine prefix）

- **是什么**：一个 `wineboot` 初始化过的 Wine prefix（`~/.wine` 的 `drive_c` + `system.reg` + `user.reg` + `userdef.reg` 等），打包成 xz tar。作为新容器的初始模板。
- **大小**：`prefixPack-x86_64-11.txz` = **7.9 MB**；`prefixPack-arm64ec-11.txz` = 21 MB。
- **怎么准备**：**仓库里没有生成脚本**。workflow 直接从 release `Assets` 下载。生成方式（推断 + Amphora 00-RESEARCH.md 已记）：用构建好的 Wine 跑一次 `wineboot`（在 Android 设备/模拟器或带 Bionic sysroot 的环境里），初始化 prefix，再 `tar cJf prefixPack.txz ~/.wine`。**先有鸡（可运行 Wine）才有蛋（prefixPack）**——首次构建要么复用上游的，要么在能跑 Wine 的环境里现场生成。
- **运行期怎么用**（WinNative `ContainerManager.extractContainerPatternFile`）：创建容器时，把 `prefixPack.txz` 解压到容器目录作为初始 prefix。`ContentsManager` 校验 `profile.winePrefixPack` 存在。

---

## 4. 46 个补丁作用（android/patches/）

共 47 个 `.patch` 文件，其中 `dlls_wow64_process.c.patch` 为 **0 字节（空，未应用，在 PATCHES 数组里注释掉）**。`server_protocol.def.patch` 在 autogen 阶段应用（不在 PATCHES 数组）。**实际应用约 45 个**。

**全部为 Android/Bionic 适配，几乎都是必需的**。按类别：

### 4.1 同步原语（esync/fsync）— 必需，Android 无可靠 futex_waitv
- `dlls_ntdll_unix_esync.c.patch`（42 KB，新增文件）+ `dlls_ntdll_unix_esync.h.patch`：ntdll 侧 eventfd-based esync 实现（Proton 的 esync 特性）。
- `server_esync.c.patch`（6.5 KB，新增）+ `server_esync.h.patch`：server 侧 esync。
- `server_fsync.c.patch`：`#ifdef __ANDROID__` 禁用 fsync_check_support（Android Bionic 的 `futex_waitv` 不可靠/缺失），改走 esync。
- `server_main.c.patch`：`do_fsync()` 否则 `do_esync()` → `esync_init()`。
- `server_thread.c.patch` / `server_inproc_sync.c.patch`：把 esync 接入 inproc device fd 路径。
- `dlls_ntdll_Makefile.in.patch`：esync.c 加入构建。

### 4.2 ntdll/unix — 必需，Bionic libc 差异
- `dlls_ntdll_unix_loader.c.patch`：`IMAGE_FILE_MACHINE_AMD64` 的 PE 目录在 aarch64 下返回 `/aarch64-windows`（box64/arm64ec 跨架构），x86_64 下 `/x86_64-windows`；`M_PERTURB` 用 `#if defined(M_PERTURB)` 保护（Bionic 可能未定义）。
- `dlls_ntdll_unix_virtual.c.patch`：`address_space_limit`/`user_space_limit` 在 Android 降到 `0x7fffff0000`（Android 高地址空间被系统占用）；引入 `shm_utils.h`。
- `dlls_ntdll_unix_signal_x86_64.c.patch`：`jmp %rcx` → `jmp *%rcx`（间接跳转，clang/Android 汇编差异）。**x86_64 专属必需**。
- `dlls_ntdll_unix_fsync.c.patch` / `_server.c` / `_sync.c`：esync/shm 接入。
- `loader_preloader.c.patch`：preloader 地址预留范围警告的 Android 分支。

### 4.3 网络 — 必需
- `dlls_nsiproxy.sys_ip.c.patch`（15 KB）/ `_ndis.c.patch` / `_nsi_common.h.patch`：Android 无标准 `/proc/net/route`，重写 IP/接口枚举（走 `getifaddrs` + Android netlink）。
- `dlls_dnsapi_libresolv.c.patch` / `_record.c.patch`：`#if defined(HAVE_RESOLV) || defined(__ANDROID__)`（Bionic 的 resolv 不同），补 `arpa/inet.h`。

### 4.4 图形/输入/音频驱动 — 必需（WinNative 用 X server）
- `winex11.drv`（7 个：`bitblt/keyboard/mouse/opengl/window/x11drv.h/x11drv_main`）：`_NET_WM_HWND` atom、`WINE_X11FORCEGLX` 环境变量强制 GLX、键盘/鼠标/窗口 Android 适配。
- `dlls_opengl32_unix_wgl.c.patch`：`compare_context_attributes` 加 `v1==v2` 空检查（防御性，**小，可选**）。
- `dlls_winepulse.drv_pulse.c.patch`：`#ifndef __ANDROID__` 禁用 `PTHREAD_PRIO_INHERIT`/`PTHREAD_MUTEX_ROBUST`（Bionic 不支持），用 `pa_mainloop_run` 直跑。
- `dlls_winebus.sys_bus_sdl.c.patch`：SDL2 `.so` 路径尝试 `libSDL2-2.0.so.0` / `libSDL2-2.0.so` / `libSDL2.so`（Android 系统 SDL2）。
- `dlls_win32u_clipboard.c.patch`：`WINE_FROM_ANDROID_CLIPBOARD` 环境变量桥接 Android 剪贴板。
- `dlls_user32_Makefile.in.patch`：构建调整。

### 4.5 wow64（32 位支持）— 必需
- `dlls_wow64_syscall.c.patch`：新增 `wow64GetEnvironmentVariableW`，`get_cpu_dll_name` 读 `HODLL` 环境变量选 CPU DLL（box64/ARM64EC wow64 路径）。
- `dlls_wow64_process.c.patch`：**空文件，未应用**。

### 4.6 server 杂项 — 必需
- `server_unicode.c.patch`：`l_intl.nls` 从 `XDG_DATA_DIRS` 加载（Android 非标准路径）。
- `server_Makefile.in.patch`：esync 加入构建。
- `server_protocol.def.patch`：协议定义（autogen 阶段应用）。

### 4.7 程序 — 必需
- `programs_wineboot_wineboot.c.patch`：新增 `initialize_xstate_features`（x86_64 空实现，aarch64 填 XSTATE），修 wineboot 崩溃。
- `programs_winebrowser_main.c.patch`（5 KB）：winebrowser 通过 winsock 给 Android 发消息（URL/浏览器桥）。
- `programs_winebrowser_Makefile.in.patch` / `programs_winemenubuilder_winemenubuilder.c.patch` / `programs_explorer_desktop.c.patch`：桌面/菜单 Android 适配。

### 4.8 其他
- `dlls_midimap_Makefile.in.patch` + `dlls_midimap_midimap.c.patch`（22 KB）：MIDI mapper Android 实现（Android MIDI API）。
- `dlls_amd_ags_x64_unixlib.c.patch`：`#ifndef __ANDROID__` 包掉 `xf86drm`/`amdgpu` 头（termuxfs 无），配合 `--disable-amd_ags_x64`。
- `dlls_advapi32_advapi.c.patch`：`GetUserName` 返回 `"xuser"` 而非 `"steamuser"`（**纯化妆，可选**）。

**结论：除 `advapi32`（化妆）和 `opengl32`（防御性空检查）外，全部为 Android/Bionic 必需**。没有一个能整类删掉；这是在 Bionic 上跑 Wine 的固有成本。

---

## 5. 产物格式与消费方式

### 5.1 .wcp 格式
`.wcp` = `tar cJf`（xz）打包 `bin/ lib/ share/ prefixPack.txz profile.json`。`profile.json` 描述类型/版本/wine 路径。WinNative 与 Winlator CMOD/Ludashi 共用同一格式，仅 `type` 字段不同（`Proton` vs `Wine`）。

### 5.2 WinNative 消费方式（参考实现）
- `ContentsManager.extraContentFile`：下载/选 .wcp → `TarCompressorUtils.extract`（先试 ZSTD 再试 XZ）解到 tmp → 读 `profile.json` → 校验 `bin/lib/prefixPack` 都存在 → 校验 target 路径在 `imagefs` 内且不在 contentDir/dosdevices → 安装到 `imagefs`。
- `ContainerManager.extractContainerPatternFile`：建容器时，从安装目录取 `prefixPack.txz`（`.tzst` 走 ZSTD，否则 XZ），解压到容器目录作为初始 Wine prefix。
- `ContentProfile`：字段 `type/versionName/versionCode/desc/files[]/wine{binPath,libPath,prefixPack}/official`。

### 5.3 Amphora 当前消费方式

- Proton WCP 由 `amphora-dev/imagefs` 发布，URL、大小和 SHA 由远程
  `content_manifest.json` 管理；常规 APK 不内置 WCP。
- `RemoteContentSource` 下载并校验后交给 `ContentsManager` 安装到 imagefs；
  建容器时从 WCP 的 `prefixPack.txz` 生成 prefix。
- `stageBundledContent` 仅用于离线或 instrumented 测试，输出在
  `build/generated/assets/bundledContent`，不污染源码 assets。
- Wine 的 Bionic 依赖由精简 imagefs 提供；Pulse 客户端例外，通过
  `DT_NEEDED=libpulse.so` 由 Box64 native wrapper 解析到 APK 内 PA13。

---

## 6. 当前自建结果

| 环节 | 当前实现 |
|---|---|
| 源码 | `amphora-dev/proton-wine` 固定 commit |
| 编排 | `amphora-dev/imagefs` 的 `l1/proton-wine-wcp.bst` |
| 工具链 | Android NDK + llvm-mingw + host Wine tools，输入隔离 |
| 运行库 | 精简 imagefs sysroot，不下载完整 termuxfs |
| prefix | 构建生成并打入 `prefixPack.txz` |
| Pulse | PA13 headers/libs 仅作交叉链接输入；产物只依赖 `libpulse.so` |
| 门禁 | x64/i386 PE 架构、Unix ELF、RELR、页对齐、`DT_NEEDED`、绝对路径检查 |
| 交付 | Release WCP + 远程 manifest SHA pin |

主要维护风险仍是 Proton Android 补丁随上游变化，以及 Wine、imagefs 与 APK native
依赖的 ABI 契约。CI 必须正向验证这些契约，不能只检查文件存在。

---

## 7. 后续约束

1. 升级 Proton commit 时重新构建完整 WCP，不复用旧 `prefixPack`。
2. 保持 Wine Unix 库与 imagefs Bionic SONAME/符号集合一致。
3. `winepulse.so` 必须只声明 `DT_NEEDED=libpulse.so`，不得携带 PA17
   `libpulsecommon` 或 Termux 绝对路径。
4. Box64 native wrapper、APK PA13 与 Wine Pulse 驱动应成对回归。
5. 发布后由 content manifest 更新 URL、大小与 SHA，应用仓库不硬编码版本。
6. PA13 Termux 开发包是过渡 SDK，不是运行时来源；若重建 Pulse，必须把
   `module-aaudio-sink` 与 daemon/core/client 作为同一 ABI 单元一起重建。

---

## 8. 关键文件索引

- 上游研究仓库：`WinNative-Emu/proton-wine`（branch `proton_11.0`）
- Amphora fork：`amphora-dev/proton-wine`
- 工作流：`.github/workflows/build-proton-sdk35.yml`（主）、`build-proton-sdk28.yml`、`build-arm64ec-unixlib-steam.yml`（manual，arm64ec only）、`README.md`
- 构建脚本：`build-scripts/build-step0-autogen.sh`、`build-step0.sh`、`build-step-x86_64.sh`、`build-step-arm64ec.sh`
- 补丁：`android/patches/`（47 个 `.patch`）
- sysvshm：`android/android_sysvshm/`（`build-x86_64.sh` / `build-aarch64.sh`，x86_64 workflow 实际不触发）
- shm_utils：`android/shm_utils/shm_utils.h`
- 上游 release `Assets`：`termuxfs-{x86_64,aarch64}.tar`、`prefixPack-{x86_64-11,arm64ec-11}.txz`
- WinNative 消费参考：`app/src/main/runtime/content/{ContentsManager.java,ContentProfile.java}`、`app/src/main/runtime/container/ContainerManager.java`
- Amphora 现有调研：`docs/00-RESEARCH.md` §4.3、`docs/01-RFC.md` D4/D5
