# 08 - 盖世游戏 (egggame) 逆向对比

> 对 `com.xiaoji.egggame` (盖世游戏) APK 的逆向分析，与 Amphora 架构对比。
> 最后更新: 2026-08-11 · 验证设备: Lenovo TB322FC, Android 16 (API 36)。

---

## 1. 基本信息

| | 盖世游戏 (egggame) | Amphora |
|---|---|---|
| 包名 | `com.xiaoji.egggame` | `app.amphora` |
| targetSdk | 36 | 36 |
| minSdk | 29 | 30 |
| compileSdk | 37 | 37 |
| 内核代号 | WinEmuKernel | com.winlator.cmod (源自 WinNative) |
| 原始项目 | 自研（`/Users/me/Documents/WinEmuKernel/`） | WinNative-Emu fork |
| Wine 版本 | Proton 11.0 arm64x (主) + Proton 10.0 x64 (备) | Proton 11.0 x86_64 (自构建) |
| x86 翻译 | FEX (arm64x 主) + box64 (x64 备) | box64 (x86_64) + FEXCore (arm64ec) |
| rootfs 架构 | Bionic (`/system/bin/linker64`) | Bionic (`/system/bin/linker64`) |

---

## 2. 插件化架构

### 盖世游戏：运行时插件加载

egggame 将 Wine 模拟器核心拆分为**独立插件 APK**，运行时下载加载：

- 主 APK (`base.apk`, 77MB): UI、商店、社交、Steam 好友等
- 插件 APK (`com.xiaoji.egggame.plugin.pcengine`, 23MB): Wine 引擎、X server、native libs
  - 独立 `targetSdk=36`，独立 `base.apk`
  - 独立 `class_index` (261KB DEX 类索引)
  - 独立 `lib/arm64-v8a/` (libwinemu.so, libxserver.so, libvfs.so 等)
  - 有 `oat/` 目录（AOT 编译）

插件通过 `PcEnginePluginHostService` (运行在 `:pcengine` 进程) 加载。

### Amphora：单 APK

所有代码打包在一个 APK 中，无运行时插件下载。

### 影响

egggame 的插件架构允许：
- 主 app 更新不需要重新下载 Wine 运行时
- Wine 引擎独立版本管理（当前有 arm64x 和 x64 两个 wine 版本）
- 多个翻译引擎独立管理（Box64 0.39 + 0.4.1-2, FEX 20260509）

---

## 3. 进程隔离

两者都使用独立 Android 进程运行 Wine：

| | egggame | Amphora |
|---|---|---|
| 进程名 | `:pcengine` | `:session` |
| 触发组件 | `PcEnginePluginHostActivity` | `SessionActivity` |
| Wine 运行在 | `:pcengine` 进程内 | `:session` 进程内 |

---

## 4. Wine 构建策略

### 盖世游戏：双 Wine 构建

egggame 同时安装两个 Wine 版本，用户可选择：

**Container 0: proton11.0-arm64x (主力)**
- Wine 11.0，原生 ARM64 编译（`aarch64-unix/`）
- PE DLL 格式为 arm64x（混合 ARM64 + x86_64 代码）
- x86_64 翻译：**FEX 进程内 JIT**（`libarm64ecfex.dll` + `libwow64fex.dll` + `xtajit64.dll`）
- 构建环境：Linux，NDK r29 (clang 22.0.0)，构建路径 `/home/dev/Desktop/wine-proton-ec_backup/`
- 不需要 box64 包装，wine 二进制原生 ARM64

**Container 1: proton10.0-x64-1 (备用)**
- Wine 8.0-14686-ga7beb4ff6ff，x86_64 编译（`x86_64-unix/`）
- x86_64 翻译：**box64** (0.4.1-2)
- 构建环境：macOS CLion，NDK r26b，构建路径 `/Users/swift/CLionProjects/WinEmuBuild/`
- 与 Amphora 架构相同（box64 包裹 x86_64 wine）

### Amphora：单 Wine 构建

- Proton 11.0 x86_64，自构建（`WinNative-Emu/proton-wine` CI workflow）
- x86_64 翻译：box64 (0.4.3) 主路线，FEXCore (arm64ec) 备路线
- arm64ec 路线在 RFC D5 中被明确标注为"太重"，MVP 不做

### 关键差异

egggame 的 **arm64x 路线**是 Amphora 没有的：
- arm64x 是 Wine 11+ 的混合 PE 格式，同时包含 ARM64 和 x86_64 代码
- `xtajit64.dll` 是 x86_64 → ARM64 的 JIT 翻译器（类似 Rosetta 2）
- `libarm64ecfex.dll` 用 FEX 替代了微软的 xtajit，做 x86_64 → ARM64 翻译
- 不需要外部 box64 进程包装，wine 原生运行在 ARM64 上
- **性能优势**：省去 box64 的进程间通信开销，JIT 在 wine 进程内完成

---

## 5. X Server 与渲染管线

### 盖世游戏：C 原生 Xorg + 内置 Vulkan 合成器

`libxserver.so` (5MB) 是一个编译为 .so 的 **Xorg 派生 X server**，包含：

- 完整 Xorg 核心：`dix/events.c`、`dix/getevents.c`、`Xi/exevents.c`、`mi/` 等
- 内置 Vulkan 渲染器：`vkCreateAndroidSurfaceKHR` + `vkCreateSwapchainKHR` + `vkQueuePresentKHR`
- 直接绑定 Android Surface：`ANativeWindow_fromSurface(Surface)`
- DRI3 + Present 扩展：通过 AHB fd 传递 pixmap
- GLX 扩展（软件回退）
- Vulkan 驱动加载：`dlopen("libvulkan.so")` → Mesa loader → `libvulkan_freedreno.so` (Turnip)
- 驱动替换：`WINEMU_REPLACED_DRIVER` 环境变量 + `vulkan.adreno.so`

渲染管线：
```
Wine → X11 Pixmap (DRI3 AHB fd)
  → libxserver.so 接收 pixmap
  → 内置 Vulkan 合成器：vkQueuePresentKHR → ANativeWindow
  → Android Surface 显示
```

### Amphora：Java X Server + 独立 Vulkan 合成器

- `XServer.java` (纯 Java，13KB) + Java 扩展（DRI3, Present, XInput2 等）
- `VulkanRenderer.java` (Java，独立的 Vulkan 合成器)
- `XServerSurfaceView` (TextureView) 承载 Surface
- Vulkan 驱动加载：**adrenotools** (`adrenotools_open_libvulkan` + linkernsbypass + hooks)
- OpenGL 路径：EGL + Zink (`WINE_USE_EGL=1`)

渲染管线：
```
Wine → X11 Pixmap (DRI3 AHB fd)
  → Java XServer 接收 pixmap → Drawable
  → VulkanRenderer 合成 → Vulkan texture
  → XServerSurfaceView (TextureView) 显示
```

### 关键差异

| | egggame | Amphora |
|---|---|---|
| X server 实现 | C (Xorg 派生, libxserver.so 5MB) | Java (XServer.java + 扩展) |
| Vulkan 合成器 | 内置于 X server | 独立 Java VulkanRenderer |
| Surface 绑定 | `vkCreateAndroidSurfaceKHR` (native) | Java Surface → JNI nativeCreate |
| Vulkan 驱动加载 | Mesa loader (标准 Linux `dlopen`) | adrenotools (Android HAL + linkernsbypass) |
| 驱动替换 | `WINEMU_REPLACED_DRIVER` + `vulkan.adreno.so` | adrenotools hook + wrapper ICD |
| OpenGL 路径 | GLX (in-process, 可能软件渲染) | EGL + Zink (`WINE_USE_EGL=1`) |

egggame 不使用 adrenotools，而是用 Mesa 标准 ICD 发现机制 + 自定义 `WINEMU_REPLACED_DRIVER` 环境变量替换驱动。这更简单但需要完整 Mesa 安装。

---

## 6. 输入系统

### 盖世游戏

**触控/鼠标/键盘**：通过 C X server 的完整 XInput2 实现
- `sendMouseEvent`、`sendKeyEvent`、`sendTouchEvent`、`sendTextEvent`
- 触控所有权转移：`TouchConvertToPointerEvent`、`TouchListenerGone`
- 手势支持：`GestureListenerGone`
- Raw 事件：`DeliverRawEvent`
- 事件队列：`mieqProcessInputEvents`

**手柄**：独立 IPC 服务器，**绕过 X11**
- `GamepadServerManager` JNI：`nativeCreate`/`nativeDestroy`/`nativeSetRumbleCallback`/`nativeGetGamepadBuffer`/`nativeUpdateGamepadCount`
- 使用 **memfd 共享内存**（per-player slot，零拷贝）
- IPC 协议：`winemu::ipc::MessageHeader` over Unix socket
- Wine 侧：`winebus.sys` 读取共享内存 → 伪造 HID 设备 (`VID_845E&PID_0001`, Xbox 手柄)
- 手柄状态：Android → Java JNI → memfd SHM → winebus.sys → Wine XInput/DInput
- 震动反馈：winebus → IPC → JNI callback → Android vibrator

### Amphora

**触控/鼠标/键盘**：Java X server
- `Pointer.java`：7 按钮，位置跟踪
- `Keyboard.java`：248 keycode，Android KeyEvent → XKeycode 映射
- `XInput2Extension.java`：XI2 v2.2，RawMotion/RawButtonPress
- `InputDeviceManager.java`：全部走 X 协议事件

**手柄**：**无**（MVP 移除）
- `InputDeviceManager` 注释："Always X-protocol (WinHandler / UDP gamepad path removed for amphora MVP)"
- `libfakeinput.so` 不构建
- `WineUtils.ensureWinebusConfig` 存在但只是 stub（`DisableHidraw=1, DisableInput=0`）
- Container 有 `inputType`/`exclusiveXInput` 字段但未使用

---

## 7. 音频系统

### 先说结论：三者的 Pulse 主链路相同

盖世游戏 6.1.2、WinNative 与 Amphora 的 Pulse 后端都不是 Wine 直接调用 AAudio，
而是同一类四层结构：

```text
Windows 程序
  → Wine winepulse.drv
  → PulseAudio（Unix socket + 混音）
  → module-aaudio-sink
  → Android AAudio
```

因此这条链路与 WinNative **没有架构级差异**；盖世游戏当前版本也确实采用这种方式。
三者的区别主要在运行核来源、资产交付、启动管理、失败回退和构建验证，而不是音频数据
最终经过哪几层。逆向样本中没有发现 `wineaaudio.drv`，不能把盖世 6.1.2 描述成
“Wine 直连 AAudio”。

### 盖世游戏 6.1.2：插件内双音频后端

6.1.0 起运行核位于独立 `com.xiaoji.egggame.plugin.pcengine` 插件。对 6.1.2 主 APK
及插件版本 `100-1` 的复核只确认了 `Alsa` / `Pulse` 两个枚举值；当前插件中没有
`wineaaudio.drv`。旧文档中的直接 Wine → AAudio 结论不能作为当前实现依据。

**路径 1: ALSA aserver**
- `libasound.so` + `libasound_module_pcm_android_aserver.so`
- `android_aserver.conf` 配置（结构与 Amphora 相同）
- JNI：`ALSAClient_downMix8Bit/16Bit/Float`（与 Amphora 共享代码血脉）
- 数据流：Wine winealsa.drv → alsa-lib → aserver plugin → Unix socket → Java ALSAClient → AudioTrack

**路径 2: PulseAudio → AAudio**
- PulseAudio 守护进程 + 匹配的 libpulse 库
- `winepulse.drv` Wine 驱动
- `default.pa` 加载 `module-native-protocol-unix` 与 `module-aaudio-sink`
- 数据流：Wine winepulse.drv → PulseAudio Unix socket → module-aaudio-sink → AAudio

盖世的 Java/Smali 调度、Pulse 配置和 native 模块均随闭源 `pcengine` 插件交付，主 APK
只负责选择与配置。它同时服务 arm64x/FEX 主路线和 x86_64/Box64 备用路线。

### WinNative：Amphora 的可维护实现基线

- 同样提供 ALSA 与 PulseAudio 两种容器音频驱动。
- `PulseAudioComponent` 安装并启动预编译 PulseAudio 运行库，通过 `default.pa` 加载
  `module-native-protocol-unix` 和 `module-aaudio-sink`。
- Wine 会话设置 `PULSE_SERVER` / `PULSE_LATENCY_MSEC`，生命周期暂停和恢复通过
  `pactl suspend-sink` 管理 AAudio stream。
- AAudio sink 具备断开后的错误恢复逻辑。

WinNative 与盖世在这里是**同路线的两套实现**。现有证据不能证明两者二进制同源；
可以确认的是组件边界、Pulse 配置和最终 AAudio sink 路径一致。

### Amphora：沿用 WinNative 基线并补齐工程门禁

- 默认仍为 ALSA，避免未验机前改变既有容器行为。
- 设置可选择 PulseAudio；下次启动同步 Wine 注册表为 `Audio=pulse`。
- Pulse 运行时采用 WinNative 维护的匹配套件：守护进程依赖库、`pactl`、
  `module-native-protocol-unix`、`module-aaudio-sink` 和 `libprotocol-native`。
- 当前匹配的 `module-aaudio-sink` 仅为 4 KB ELF；16 KB 页设备自动保留 ALSA，避免
  Android linker 拒绝加载。
- 暂停/恢复通过 `pactl suspend-sink` 关闭并重开 AAudio stream；native sink 的错误回调
  负责处理电话或设备切换造成的 AAudio disconnect。
- 启动时等待并确认 `AAudioSink` 真正出现；模块加载失败会清理 Pulse 进程并明确失败，
  不把“守护进程存在”误判成“音频可用”。
- `pactl` 和守护进程使用适配 targetSdk 36 的独立启动环境，避免 app-private ELF
  执行包装或全局 `LD_PRELOAD` 污染 Pulse 进程。
- 音量和静音状态在 ALSA/Pulse 切换、暂停与恢复后保持一致。
- 自构建 Proton WCP 含 `winepulse.so` 及 x64/x86 `winepulse.drv`；编译头文件、
  native `libpulse` 与 APK 运行时统一为 PulseAudio 13。
- BuildStream 门禁正向验证 `winepulse.so` 的 x86_64 架构和
  `DT_NEEDED=libpulse.so`，并禁止 guest `libpulse` 绕过 Box64 native wrapper。

### 三方差异

| 项目 | 盖世游戏 6.1.2 | WinNative | Amphora |
|---|---|---|---|
| Pulse 数据路径 | winepulse → Pulse → AAudio | winepulse → Pulse → AAudio | winepulse → Pulse → AAudio |
| ALSA 回退 | 有 | 有 | 有；Pulse 不可用时保留 ALSA |
| 运行核交付 | 闭源 `pcengine` 插件 APK | 仓库内预编译资产 | APK Pulse 资产 + 自构建 Proton WCP |
| 会话实现 | 混淆插件代码 | `PulseAudioComponent` | 基于该组件并增加就绪、状态和启动隔离 |
| Wine/PA ABI | 插件整体配套 | 依赖上游预编译 WCP | PA13 对齐并由 CI 断言 |
| 16 KB 页设备 | 当前样本未确认策略 | 当前基线未确认门禁 | 4 KB 模块不可加载时不启用 Pulse |
| 可复现验证 | 无公开构建链 | 主要依赖预编译资产 | BuildStream 构建及 ELF/ABI 门禁 |

PulseAudio 路径比直接 Wine 音频驱动多一层混音服务，但能复用成熟的 Wine Pulse
兼容性并由 AAudio 输出。直接实现新的 `wineaaudio.drv` 不属于本次工作，也不是盖世
6.1.2 已采用的方案。

---

## 8. 组件版本对比

| 组件 | egggame | Amphora |
|---|---|---|
| Wine (arm64x) | Proton 11.0, NDK r29 | 无 |
| Wine (x86_64) | Proton 10.0 (8.0-14686), NDK r26b | Proton 11.0, NDK r27d |
| box64 | 0.39 + 0.4.1-2 | 0.4.3-c08554e3f |
| FEX | Fex_20260509 (`libarm64ecfex.dll`) | FEXCore (env vars, 无 DLL) |
| DXVK | 3.0.2-async + v2.6-1-async | 3.0.2-gplasync |
| VKD3D | proton-3.0.1 | proton (版本待查) |
| Turnip | v26.1.0_b8 (`libvulkan_freedreno.so`) | 源自 WinNative-Emu/Drivers |
| PulseAudio | 完整安装 + AAudio sink | 13.0 可选后端 + AAudio sink |
| libandroid-spawn | 有 (Termux) | 无 |

---

## 9. execve 绕过方案

详见 `docs/07-TARGETSDK-SELINUX.md`。核心差异：

| | egggame | Amphora |
|---|---|---|
| targetSdk | 36 | 36 |
| exec 机制 | `libvfs.so` GOT/PLT hook execve → linker64 包装 | `libamphora-exec.so` LD_PRELOAD 拦截 → linker64 包装 + Java `AppDataExecutableLauncher` 首启包装 |
| wine launcher | 9KB dlopen stub (`get_self_exe()` 返回 NULL) | 标准 wine 二进制（`/proc/self/exe` 由拦截器 readlink hook 修正） |
| `libandroid-spawn.so` | 有 (posix_spawn 实现) | 无 |
| `/proc/self/exe` | 不读（launcher 用 argv[0] 定位 ntdll.so） | `AMPHORA_EXEC__PROC_SELF_EXE` env + `libamphora-exec.so` readlink/realpath hook |

---

## 10. 待办：消除 `/proc/self/exe` 运行时 hook（源码 patch 路线）

### 10.1 动机

Amphora 控制两个关键源码仓库，可以在**编译时**消除对 `/proc/self/exe` 的依赖，而不是在运行时用 readlink/realpath hook 欺骗：

- **`amphora-dev/proton-wine`**：wine 源码，`loader/main.c` 的 `get_self_exe()`
- **`amphora-dev/imagefs`**：box64 构建（`vendor/box64-patches/` 已有 patch 机制）

这与盖世游戏的做法一致（stub 掉 `get_self_exe()`，用 `argv[0]` fallback），比运行时 hook 更干净。

### 10.2 readlink/realpath hook 的代价（为什么要消除）

| 问题 | 说明 |
|---|---|
| `/proc/self/exe` 与其他 proc 文件不一致 | 通过 linker64 exec 后：`exe` 被 hook 伪造为 wine/box64 路径，但 `maps` 第一行映射真实的是 linker64。程序交叉验证时会矛盾 |
| hook 覆盖不完整 | 只 hook 了 `readlink` 和 `realpath`，`open("/proc/self/exe")` + `fstat`、`readlinkat`、`readlinkat(AT_FDCWD, ...)` 都没覆盖 |
| 全局拦截副作用 | LD_PRELOAD 是进程级全局的，`readlink`/`realpath` 是高频 libc 函数，每次调用有函数指针跳转 + 字符串比较开销；hook 有 bug 会破坏所有调用 |
| 调试干扰 | gdb、perf 等工具自己调 readlink，hook 会干扰它们 |

### 10.3 待办任务清单（含实现细节）

#### Task 1: patch wine `get_self_exe()` 返回 NULL（`__ANDROID__`）

- **仓库**：`amphora-dev/proton-wine`（分支 `proton_11.0`）
- **文件**：`loader/main.c` 第 130-149 行
- **当前代码**：

```c
static const char *get_self_exe(void)
{
#if defined(__linux__) || defined(__FreeBSD_kernel__) || defined(__NetBSD__)
    return "/proc/self/exe";
#elif defined (__FreeBSD__) || defined(__DragonFly__)
    static int pathname[] = { CTL_KERN, KERN_PROC, KERN_PROC_PATHNAME, -1 };
    size_t path_size = PATH_MAX;
    char *path = malloc( path_size );
    if (path && !sysctl( pathname, sizeof(pathname)/sizeof(pathname[0]), path, &path_size, NULL, 0 ))
        return path;
    free( path );
#elif defined(__APPLE__)
    uint32_t path_size = PATH_MAX;
    char *path = malloc( path_size );
    if (path && !_NSGetExecutablePath( path, &path_size ))
        return path;
    free( path );
#endif
    return NULL;
}
```

- **改动**：在 `#if defined(__linux__) || ...` 分支前插入 `__ANDROID__` 分支：

```c
static const char *get_self_exe(void)
{
#ifdef __ANDROID__
    /* Amphora routes app-private ELF through /system/bin/linker64, so
     * /proc/self/exe points at linker64, not at this binary. Let main()
     * fall back to try_dlopen(argv[0]) — argv[0] is the real path. */
    return NULL;
#else
    ...原逻辑...
#endif
}
```

- **效果链**：`main()` 里 `try_dlopen(get_self_exe())` → `try_dlopen(NULL)` → `if (!argv0) return NULL;` 立即失败 → fallback `try_dlopen(argv[0])` → 成功加载 `ntdll.so`。与盖世游戏 9KB stub 行为一致。
- **依据**（已核实）：
  - `loader/main.c:191-192`：`if ((handle = try_dlopen( get_self_exe() )) || (handle = try_dlopen( argv[0] )))` — fallback 是现成的
  - `loader/main.c:156`：`if (!argv0) return NULL;` — NULL 入参安全
  - 整个 wine 代码库（`loader/`、`server/`、`programs/`、`dlls/`）只有 `loader/main.c:133` 一处引用 `/proc/self/exe`（已 grep 核实）
  - `try_dlopen` 内 `realpath_dirname(argv[0])` → `remove_tail(dir, "/loader")` → `build_path(p, "dlls/ntdll/ntdll.so")`（构建树布局）或 `build_path(dir, "ntdll.so")`（安装布局）— 与安装路径 `.../lib/wine/x86_64-unix/` 的相对关系需要验证（盖世游戏用的是 `../lib/wine/aarch64-unix/ntdll.so` 布局，Amphora 的 x86_64 安装布局若不同需相应调整 `try_dlopen`）
- **提交方式**：**直接改源码提交**（不是 patch 文件）。已核实：`android/patches/` 目录里的 patch 已全部应用到源码树（如 `loader/preloader.c:1494` 有 `# elif __ANDROID__`），该目录是补丁存档。wine WCP 构建（`imagefs/ci/wine/build-proton-wcp.sh`）无 patch 应用步骤，直接构建 checkout 的源码。
- **版本管理**：修改后 bump `buildstream/elements/l1/proton-wine-wcp.bst` 的 `ref`（当前 `d12a5634aa4d8832761f2d968d5a3d9170034910`）+ `content_manifest.json` 的 wine 条目 sha256/version。

#### Task 2: patch box64 `/proc/self/exe` 用法（`__ANDROID__`）

- **仓库**：`amphora-dev/imagefs`
- **patch 机制**（已核实）：`buildstream/elements/l1/box64-wcp.bst` 的 `configure-commands` 里 `patch -p1 < .bst/patches/<name>.patch`，patch 文件放 `vendor/box64-patches/`。现有 `pipetto-controller-fix.patch` 可作模板。
- **box64 版本**：commit `0db8df7757b523e41cf31b6204c47d22b8fb4f08`（manifest 当前 pin `Box64-0.4.5-0db8df775.wcp`）

- **改动点 1：`src/core.c:352-407` `KillAllInstances()`**

当前逻辑（`core.c:369-380`）：
```c
ssize_t self_len = readlink("/proc/self/exe", exe_path, sizeof(exe_path) - 1);
if (self_len == -1) {
    perror("readlink(/proc/self/exe)");
    closedir(proc_dir);
    return;
}
exe_path[self_len] = '\0';

char* base_name_self = strrchr(exe_path, '/');
base_name_self = base_name_self ? base_name_self + 1 : exe_path;
strncpy(self_name, base_name_self, sizeof(self_name));
self_name[sizeof(self_name) - 1] = '\0';
```

替代方案（box64 有现成的 `my_context->box64path`，`core.c:1050` 赋值：`my_context->box64path = ResolveFile(argv[0], ...)`）：
```c
#ifdef __ANDROID__
    /* linker64 routing makes /proc/self/exe point at linker64, not box64.
     * my_context->box64path is resolved from argv[0] and is the real path. */
    char* base_name_self = strrchr(my_context->box64path, '/');
    base_name_self = base_name_self ? base_name_self + 1 : my_context->box64path;
    strncpy(self_name, base_name_self, sizeof(self_name));
    self_name[sizeof(self_name) - 1] = '\0';
#else
    ...原 readlink 逻辑...
#endif
```

注意：`KillAllInstances()` 的调用点在 `core.c:868`（`box64 -k` / `--kill-all`），此时 `my_context` 已初始化（`core.c:1050` 在 `StartBox()` 前）。需确认函数签名里能拿到 `my_context`（`KillAllInstances()` 当前无参数，需检查调用处上下文）。

**影响评估**：`KillAllInstances` 只在 `-k/--kill-all` 时调用，**不在正常启动路径**。即使不 patch 也不影响游戏运行（`box64 -k` 读不到正确自身路径，kill 不到旧实例，仅此而已）。

- **改动点 2：`src/elfs/elfhacks.c:71-72`**

```c
if (find->name == NULL) /* TODO readlink? */
    find->name = "/proc/self/exe";
```

这是 `dl_iterate_phdr` 回调 `eh_find_callback` 中 `dlpi_name` 为空时的 fallback（`info->dlpi_name[0] == '\0'` 即主程序）。通过 linker64 exec 后这里会得到 `linker64` 的名字，影响 `dl_iterate_phdr` 查找主程序 ELF 的场景。**有 `/* TODO readlink? */` 标注，开发者自己都不确定**。

替代：`__ANDROID__` 下用 `my_context->box64path`（或直接返回不匹配）：
```c
#ifdef __ANDROID__
    /* linker64 routing: /proc/self/exe is not this binary. */
    find->name = my_context->box64path;  // 或 box64argv0
#else
    find->name = "/proc/self/exe";
#endif
```

**影响评估**：正常路径 `dlpi_name` 非空时走不到这个分支；仅主程序（name 为空）查询时触发。patch 或忽略均可，非关键。

- **提交方式**：新增 `vendor/box64-patches/android-proc-self-exe.patch`，在 `box64-wcp.bst` 的 `configure-commands` 加一行 `patch -p1 < .bst/patches/android-proc-self-exe.patch`。
- **版本管理**：重新构建 box64 WCP，更新 `content_manifest.json` 的 box64 条目。

#### Task 3: 移除 `libamphora-exec.so` 的 readlink/realpath hook 与 env 注入

- **仓库**：`amphora-dev/amphora`
- **前置条件**：Task 1/2 完成且新 wine/box64 WCP 发布。在此之前**不能移除**，否则 wine/box64 读 `/proc/self/exe` 得到 linker64。

- **文件 1：`core/native/src/main/cpp/winlator/amphora_exec.c`**
  - 删除 `readlink` hook（390-402 行）：
    ```c
    __attribute__((visibility("default"))) ssize_t readlink(
        const char *restrict path, char *restrict buffer, size_t size) {
      if (strcmp(path, "/proc/self/exe") == 0) { ... }
      return syscall(SYS_readlinkat, AT_FDCWD, path, buffer, size);
    }
    ```
  - 删除 `realpath` hook（404-412 行）：
    ```c
    __attribute__((visibility("default"))) char *realpath(
        const char *path, char *resolved_path) { ... }
    ```
  - 删除 `SELF_EXE_ENV` 宏（51 行）：`#define SELF_EXE_ENV "AMPHORA_EXEC__PROC_SELF_EXE"`
  - `copy_environment()`（133-168 行）：删除 `self_executable` 参数、`self_entry` 输出参数、`asprintf(self_entry, SELF_EXE_ENV "=%s", ...)` 注入逻辑、`SELF_EXE_ENV` 前缀匹配逻辑
  - `execve()`（185-272 行）：调用处改为 `copy_environment(envp, wrap_in_linker, &self_entry)` → `copy_environment(envp)`；删除 `self_entry` 的分配/释放
  - 注意：`execve()` 内部还有一处 `realpath(executable_path, resolved_path)`（210 行）用于路径归一化，**这不是 hook 本身，是内部逻辑**。移除 hook 后这里仍调用 libc realpath，行为不变（传的是 executable_path 不是 `/proc/self/exe`）

- **文件 2：`core/engine/src/main/java/com/winlator/cmod/runtime/system/ProcessHelper.java`**
  - `configureAppDataExecEnvironment()`（72-103 行）：删除 `AMPHORA_EXEC__PROC_SELF_EXE` 注入（82-86 行）：
    ```java
    if (filesDir != null
        && executablePath != null
        && AppDataExecutableLauncher.isAppDataPath(filesDir, new File(executablePath))) {
      environment.put("AMPHORA_EXEC__PROC_SELF_EXE", executablePath);
    }
    ```
  - `executablePath` 参数保留（`AMPHORA_EXEC_ROOT` 判断仍用），或改为不再需要（看调用处是否还传）

- **文件 3（文档同步）**：
  - `docs/07-TARGETSDK-SELINUX.md` §4.2：删掉 `AMPHORA_EXEC__PROC_SELF_EXE` env + readlink hook 描述，改为"wine/box64 已在编译期消除对 /proc/self/exe 的依赖"
  - `docs/08-EGGGAME-COMPARISON.md` §9 对比表：`/proc/self/exe` 行改为与 egggame 对齐的 stub/argv[0] 方案
  - `README.md` 若有相关描述同步

- **保留不动**：
  - `libamphora-exec.so` 的 `execve` hook（linker64 包装逻辑，185-272 行的核心部分）
  - `AppDataExecutableLauncher` Java 首启包装
  - `AMPHORA_EXEC_ROOT` / `AMPHORA_EXEC_LEGACY_ROOT` env（execve 策略判断用）
  - `AMPHORA_EXEC_OPTOUT` / `AMPHORA_EXEC_DEBUG` env

### 10.4 执行顺序、依赖与验证

```
Task 1 (wine patch) ──┐
                      ├──> Task 3 (移除 hook) ──> 发布新 wine WCP + box64 WCP + APK
Task 2 (box64 patch) ─┘
```

Task 3 依赖 Task 1/2 完成并发布新 WCP 后。Task 1/2 相互独立，可并行。

**Task 1 验证**（proton-wine 构建后）：
1. `readelf -l` 确认 wine launcher PT_INTERP 为 `/system/bin/linker64`（imagefs CI 已有此检查，见 `box64-wcp.bst:49-51`）
2. 设备上从 filesDir 直接 `execve(wine)` 会 EACCES（对照），经 `AppDataExecutableLauncher` 包装后成功启动
3. 启动后 `cat /proc/<wine-pid>/exe` 显示 linker64，但 wine 正常加载 ntdll.so 并进入 `__wine_main`（说明 argv[0] fallback 生效）
4. wineboot 完整跑通（wineserver + services.exe 正常 fork）

**Task 2 验证**（box64 WCP 构建后）：
1. `box64 --version` 正常
2. `box64 -k` 不崩溃（即使 kill 不到旧实例，行为可接受）
3. 游戏启动路径（正常 box64 启动）不受影响

**Task 3 验证**（APK 构建后）：
1. 移除 hook 后，全量回归 `GameSessionLaunchTest`（androidTest）：Wine desktop 画面 + 相对触控 + Vulkan 帧
2. 设备上 `readlink /proc/<wine-pid>/exe` = linker64，`cat /proc/<wine-pid>/maps` 第一行 = linker64 —— 两者**一致**（消除矛盾）
3. `AMPHORA_EXEC__PROC_SELF_EXE` 不再出现在 `ps e` / `/proc/<pid>/environ` 中
4. gdb/perf 附加进程不再受 readlink hook 干扰（可选验证）

### 10.5 为什么盖世游戏不选 readlink hook

盖世游戏控制全部 wine 源码（WinEmuKernel），`get_self_exe()` 改成 stub 只需要改一个函数，wine launcher 立刻走 `argv[0]` fallback，**零运行时开销、零不一致风险、零维护负担**。它的 box64 备用路线遇到 `/proc/self/exe` 问题（`KillAllInstances`）影响有限——那只是清理旧实例的辅助功能，读不到正确路径只是 kill 不到其他实例，不影响游戏运行。

**能用 stub 解决的问题，没有人会选择加一层运行时 hook。** Amphora 同样控制源码（proton-wine + imagefs），没有理由不这样做。

---

## 11. 总结：Amphora 可借鉴的方向

### 高价值（短期可行）

1. ~~**linker64 exec 包装**~~（✅ 已落地，`d9a3090`..`75ec9a5`）：Amphora `SDK_TARGET=36`，Java `AppDataExecutableLauncher` + `libamphora-exec.so` LD_PRELOAD 拦截器已实现。详见 `docs/07-TARGETSDK-SELINUX.md` §4。

2. **消除 `/proc/self/exe` 运行时 hook**（📋 待办，见 §10）：patch wine `get_self_exe()` + box64 `/proc/self/exe` 用法（`__ANDROID__`），随后移除 `libamphora-exec.so` 的 readlink/realpath hook 和 `AMPHORA_EXEC__PROC_SELF_EXE` env 注入。消除 `exe`/`maps` 不一致与全局 hook 开销，与盖世游戏做法对齐。

3. ~~**PulseAudio → AAudio 可选后端**~~（✅ 已落地）：匹配的 PulseAudio 13 运行库、
   `pactl` 与 AAudio sink 模块随 APK 发布；设置中可选，并保留 ALSA 回退。

### 中价值（中期）

3. **手柄 IPC 服务器**：memfd 共享内存 + winebus.sys 伪造 HID 设备。比 X11 事件路径更高效，支持震动反馈。Amphora 已有 `WineUtils.ensureWinebusConfig` stub。

4. **双 Wine 构建**：arm64x (FEX 进程内 JIT) 作为高性能路线，x86_64 (box64) 作为兼容路线。Amphora 已有 FEXCore 路线框架但未完成。

### 低价值（长期/不适用）

5. **C X server**：egggame 的 libxserver.so 功能更完整（触控所有权、手势），但 Amphora 的 Java X server 已满足鼠标/键盘需求，迁移成本极高。

6. **插件化 APK**：egggame 的运行时插件下载对商业产品有意义，但 Amphora 作为开源项目不需要。
