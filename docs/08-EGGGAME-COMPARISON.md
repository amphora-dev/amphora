# 08 - 盖世游戏 (egggame) 逆向对比

> 对 `com.xiaoji.egggame` (盖世游戏) APK 的逆向分析，与 Amphora 架构对比。
> 最后更新: 2026-08-10 · 验证设备: Lenovo TB322FC, Android 16 (API 36)。

---

## 1. 基本信息

| | 盖世游戏 (egggame) | Amphora |
|---|---|---|
| 包名 | `com.xiaoji.egggame` | `app.amphora` |
| targetSdk | 36 | 36 |
| minSdk | 29 | 28 |
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

### 盖世游戏：三路音频

**路径 1: wineaaudio.drv（自研，最低延迟）**
- 自定义 Wine 音频驱动，直接调用 Android **AAudio C API**
- 实现 Windows MMDevice API：`IAudioClient`/`IAudioClient2`/`IAudioClient3`/`IAudioRenderClient`/`IAudioCaptureClient`/`IAudioClock`
- 构建工具：Android clang 22.0.0
- 源码：`../dlls/wineaaudio.drv/mmdevdrv.c`
- 无 ALSA/PulseAudio 中间层

**路径 2: ALSA aserver（与 Amphora 相同）**
- `libasound.so` + `libasound_module_pcm_android_aserver.so`
- `android_aserver.conf` 配置（结构与 Amphora 相同）
- JNI：`ALSAClient_downMix8Bit/16Bit/Float`（与 Amphora 共享代码血脉）
- 数据流：Wine winealsa.drv → alsa-lib → aserver plugin → Unix socket → Java ALSAClient → AudioTrack

**路径 3: PulseAudio 17.0（完整安装）**
- `pulseaudio` 二进制 + 完整 libpulse 库
- `winepulse.drv` Wine 驱动
- 配置：`client.conf`、`daemon.conf`、`default.pa`

### Amphora：单路 ALSA aserver

- ALSA aserver only（MVP 决策）
- 无 PulseAudio（显式排除）
- 无 wineaaudio.drv
- 数据流：Wine winealsa.drv → alsa-lib → aserver → Unix socket → ALSAServerComponent (Java) → AudioTrack

### 关键差异

egggame 的 `wineaaudio.drv` 是最有价值的音频差异——直接 Wine → AAudio，省去了 ALSA aserver 的 Unix socket 跳转 + Java AudioTrack 中间层，延迟更低。

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
| PulseAudio | 17.0 (完整安装) | 无 |
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

## 10. 总结：Amphora 可借鉴的方向

### 高价值（短期可行）

1. ~~**linker64 exec 包装**~~（✅ 已落地，`d9a3090`..`75ec9a5`）：Amphora `SDK_TARGET=36`，Java `AppDataExecutableLauncher` + `libamphora-exec.so` LD_PRELOAD 拦截器已实现。详见 `docs/07-TARGETSDK-SELINUX.md` §4。

2. **wineaaudio.drv**：直接 Wine → AAudio，省去 ALSA aserver 中间层，降低音频延迟。需要自构建 Wine 时添加此驱动。

### 中价值（中期）

3. **手柄 IPC 服务器**：memfd 共享内存 + winebus.sys 伪造 HID 设备。比 X11 事件路径更高效，支持震动反馈。Amphora 已有 `WineUtils.ensureWinebusConfig` stub。

4. **双 Wine 构建**：arm64x (FEX 进程内 JIT) 作为高性能路线，x86_64 (box64) 作为兼容路线。Amphora 已有 FEXCore 路线框架但未完成。

### 低价值（长期/不适用）

5. **C X server**：egggame 的 libxserver.so 功能更完整（触控所有权、手势），但 Amphora 的 Java X server 已满足鼠标/键盘需求，迁移成本极高。

6. **插件化 APK**：egggame 的运行时插件下载对商业产品有意义，但 Amphora 作为开源项目不需要。

7. **PulseAudio**：egggame 安装了完整 PA 17.0，但 wineaaudio.drv 已提供更低延迟的路径，PA 多余。
