# 11 · Android 原生 Vulkan 呈现路径 — 实施计划（交给实现 agent 的自包含规格）

> 状态：调研完成（2026-09-13），待实现。
> 目标读者：接手实现的 agent。本文自包含，不依赖对话上下文。
> 一句话：**在 wineandroid.drv 里补上缺失的 Vulkan 呈现路径，让 D3D/Vulkan 游戏绕过 X11 直达 Android SurfaceFlinger；X11 路径全程保留为对照组与回退。**

> 实施跟踪与 **pin 接口（v47）** 以 [`12-WINEANDROID-MIGRATION.md`](12-WINEANDROID-MIGRATION.md) 为准；本文 master 签名不要直接照抄。

---

## 0. 环境与工作区事实

- 仓库：`/Users/sky/co/github/amphora`（Android 工程，Winlator/WinNative 血统，见 `docs/05-ARCHITECTURE.md`）。
- 测试真机：**Lenovo TB322FC**（Legion 平板，Adreno GPU，ZUI ROM，带 KernelSU 可 `su`），adb 序列号 `HA262AAH`。该机已装 Amphora，内容为 **Proton 11.0**（`/data/data/app.amphora/files/contents/Proton/11.0-d12a5634a-x86_64-0`，纯 x86_64 构建）。
- 模拟器（mac ARM）只够无 GPU 无头测试（`WineHeadlessRunner` / `HeadlessWineBootTest`），**本计划的所有验证都在真机做**。
- 仓库约束（`AGENTS.md`）：当前设计是唯一真源；不做旧版兼容分支；新 rootfs 内容必须走 pin + AppliedMarks 体系；ktlint 必须过。

## 1. 背景与决策（为什么做这件事）

### 1.1 现状（X11 路径）的全部链路

- 启动命令：`wine explorer /desktop=shell,WxH "<exe>"`（`WineEngineImpl.kt:459-463`；文件浏览入口已改 `winefile.exe`）。`/desktop=` 虚拟桌面是绕开 Java X server **没有 RandR** 的手段（扩展只有 BigReq/DRI3/MIT-SHM/Present/Sync/XInput2，位于 `core/engine/src/main/java/com/winlator/cmod/runtime/display/xserver/`）。
- Vulkan：DXVK → winevulkan → **直连 Turnip ICD**（xcb WSI 在 ICD 内）→ DRI3/Present + AHB 导入 → Java X server → Amphora 自研 compositor（flat 可见窗口列表 + 逐窗口 draw）→ SurfaceView → SurfaceFlinger。
- OpenGL：`MESA_LOADER_DRIVER_OVERRIDE` 选 zink（loader 级），`KOPPER_DRI2` 让 kopper 在本 X server 上存活（见 `XServerWineSessionPreparer.kt:1307-1337` 的长注释；**不要**用 `GALLIUM_DRIVER=zink`，那是错误开关）。Android 上 Mesa 只有 Vulkan 可达（freedreno KGSL 后端未合入），所以 **Zink 是唯一硬件加速 GL 路径，现在就是**。
- GDI/2D：软件 DIB → X11 blit。

### 1.2 已拍板的决策

1. **长期方向 = 抛弃 X11**，终点是上游 `wineandroid.drv`（2025 春至今的持续复兴，Twaik Yont + Rémi Bernon）。
2. **不做** native X server + jwm（盖世小鸡 EGG 的做法，见 `docs/08-EGGGAME-COMPARISON.md` 精神）——那是给将废弃的层加码。
3. **工程范围只有 Vulkan 一块**：GDI/2D、窗口、输入、EGL 接口上游都已实现，免费搭载。
4. X11 路径一行不动，作为迁移期对照组；按游戏可配置回退。

## 2. 目标架构

### 2.1 呈现链路对比

```
现状（X11）:
  游戏帧 → winevulkan → Turnip ICD 直连(xcb WSI) → DRI3/Present+AHB
        → Java X server → compositor 整屏再合成一遍 → SurfaceView → SurfaceFlinger

目标（Android 原生）:
  游戏帧 → winevulkan → 系统 loader(libvulkan.so, adrenotools 钩 Turnip)
        → VK_KHR_android_surface → BufferQueue → SurfaceFlinger（终点）
```

消失的东西：一个进程边界、每帧一次全屏合成 pass、一跳 X 协议输入注入、一层桌面大小中间缓冲。swapchain image 就是 gralloc buffer，直接进 SurfaceFlinger——与所有原生 Android 游戏同路。

### 2.2 关键架构认知（决定对错的那条）

**Android 上 `VK_KHR_android_surface` 和 swapchain 是系统 loader（`libvulkan.so`）实现的，不是 ICD。** Mesa 已删除 `wsi_common_android.c`（main 分支 404，已验证）。因此：

- 这条路径**必须 dlopen 系统 loader**，不能像 X11 路径那样直连 Turnip ICD（直连会丢 swapchain）。
- 自定义驱动照常工作：**adrenotools 本来就是钩系统 loader 换驱动的**，`TurnipDriverProvisioner` 的成果直接平移。
- 零拷贝是 Android 原生行为，我们不需要再做任何 DRI3/Present/AHB 胶水。
- `libvulkan.so` 是公开 NDK 库；Amphora 的 bionic rootfs 进程走 `/system/bin/linker64` 路由（`docs/07-TARGETSDK-SELINUX.md`），dlopen 无命名空间障碍。

### 2.3 渲染出口分工表（"只走 Vulkan"的正确含义）

| 内容类型 | 出口 | 谁提供 |
|---|---|---|
| D3D9/10/11/12、Vulkan 游戏 | DXVK/vkd3d → **Vulkan surface（本项目要建）** | 我们 |
| 2D 老游戏、DirectDraw 包装、启动器、对话框等一切普通窗口 | win32u `window_surface` 软件 DIB → `android_surface_flush`（`NATIVE_WINDOW_LOCK` → 逐像素 blit 含 alpha/color-key/clip → `UNLOCK_AND_POST`） | 上游已有，免费 |
| OpenGL/WineD3D 兜底 | EGL on ANativeWindow（上游 `opengl.c`），背后需 **zink-EGL Mesa 构建**（内容项，链路与 Vulkan 共享；v1 可不做） | 上游接口 + 内容配置 |

工程范围 = 只建 Vulkan；运行时形态 = Vulkan 主画面 + 软件 GDI 其余窗口的双轨（这是 wineandroid 的既有结构，不是额外负担）。

## 3. 上游 wineandroid.drv 实现现状（2026-09-13 审计，master）

文件清单：`window.c`(1236 行)、`device.c`、`keyboard.c`、`opengl.c`(230 行)、`init.c`、`dllmain.c`、`WineActivity.java`、`AndroidManifest.xml`。**没有 vulkan.c、没有 audio.c**。

| 子系统 | 状态 | 证据 |
|---|---|---|
| 窗口模型 | ✅ 完整 | per-HWND ANativeWindow（`get_ioctl_window(hwnd)`），window+client 双包装经 socket 转发到桌面进程 |
| 输入 | ✅ 完整 | device.c/keyboard.c（AInputQueue） |
| GDI/2D 软件渲染 | ✅ 完整且成熟（CrossOver 血统） | `android_surface_flush`（window.c） |
| OpenGL/EGL | ✅ 可用 | `eglCreateWindowSurface(ANativeWindow)` + pbuffer 兜底 |
| GL+GDI 混合窗口 | ❌ 空 stub | `android_client_surface_present` 是空函数（opengl.c:180-182） |
| Vulkan | ❌ 完全没有 | 无 vulkan.c |

提交节奏：持续但未加速；最新 2026-09-10（Rémi，win32u GL 上下文整合）；Twaik 最后提交 2026-06-09（minSdk 28）。**Vulkan 是上游公认缺的拼图——我们自己补，等于自己点亮"换乘信号"。**

## 4. 实现规格

### 4.1 关键接缝（已验证存在）

`include/wine/vulkan_driver.h`：

```c
struct vulkan_driver_funcs
{
    VkResult (*p_vulkan_surface_create)(struct client_surface *, const struct vulkan_instance *, VkSurfaceKHR *);
    VkBool32 (*p_get_physical_device_presentation_support)(struct vulkan_physical_device *, uint32_t);
    void (*p_map_instance_extensions)( struct vulkan_instance_extensions *extensions );
    void (*p_map_device_extensions)( struct vulkan_device_extensions *extensions );
};
```

win32u/vulkan.c（3196 行）通过 `driver_funcs` 分发，有 `nulldrv_funcs` 空驱动兜底。注册模式与 `opengl_driver_funcs` / `ANDROID_OpenGLInit`（opengl.c:207-230）完全同构。

### 4.2 工作块 1：`dlls/wineandroid.drv/vulkan.c`（新文件，约 300 行，纯新增）

- 实现 4 个 ops。`p_vulkan_surface_create` 逐行镜像 opengl.c 的桥：`client_surface → get_client_window(client->hwnd) → ANativeWindow*`，最后一跳换成 `vkCreateAndroidSurfaceKHR(host_instance, &(VkAndroidSurfaceCreateInfoKHR){ .window = anw }, ...)`。
- `p_map_instance_extensions`：把宿主扩展 `VK_KHR_android_surface`（+`VK_KHR_surface`）上报给 win32u；`p_map_device_extensions`：`VK_KHR_swapchain`。
- `p_get_physical_device_presentation_support`：经宿主 loader 查询（或对 loader 背后的设备恒真）。
- 注册入口命名沿用模式：`ANDROID_VulkanInit`，从 wineandroid.drv 的 unixlib 分发挂入。
- 宿主函数来源：`dlopen("libvulkan.so")`（`SONAME_LIBVULKAN`）→ `vkEnumerateInstanceExtensionProperties` / `vkCreateInstance` / `vkGetInstanceProcAddr`。**把它当作唯一"ICD"对待**——其入口面与 ICD 完全同构，win32u 现有 host 函数表机制直接复用；背后它自己完成 ICD 发现（adrenotools 钩 Turnip）。
- license/文件头照抄上游同目录文件（LGPL2.1+）。

### 4.3 工作块 2：win32u 侧接线（几十行，局部）

- win32u/vulkan.c 的 driver 选择分支：显示驱动为 android 时安装我们的 `vulkan_driver_funcs`（仿 nulldrv/真驱动的分派点）。
- 宿主实例创建走 loader 分支（4.2 的 dlopen 路线），替换该分支下的 JSON ICD 枚举。**不改 X11 分支的任何行为。**

### 4.4 工作块 3：Amphora host（自有仓库，Kotlin/Java）

- 新增引擎路径（如 `LaunchSpec`/engine 配置加 native 分支）：跳过 `XServerComponent`，宿主改为承载 wineandroid 的桌面视图/每窗口 surface。
- 参考上游 `WineActivity.java`（酒进程拆分、桌面视图经 ioctl 创建、事件管道）改写 SessionActivity 接线；输入走 wineandroid 事件管道，**不再注入 X 事件**。
- `TurnipDriverProvisioner` + adrenotools 复用现有实现。
- 按 AGENTS.md：新增 rootfs 内容（若 v1 之后做 zink-EGL）必须走 pin + AppliedMarks。

### 4.5 明确不做（out of scope）

- jwm / native X server / explorer shell 定制（`docs/08`、`09-VIRGL-PLAN` 相关讨论的结论：给将废弃的层加码）。
- 音频驱动（rootfs ALSA/Pulse 与显示栈无关，平移即可）。
- IME 重做、窗口管理语义升级（迁移后期项）。
- wine 补丁里**不得**出现 adrenotools/驱动预置逻辑（属于 `TurnipDriverProvisioner`/preparer）。

## 5. 验证计划：三个 Spike，逐级放行

> 每级有独立通过标准；Spike A 不过则整体暂停并回报。全程真机 `HA262AAH`。

### Spike A（数天，不碰 Wine）——证"链路在你们的进程模型里成立"

1. 写一个最小 Android host（自建 Activity + SurfaceView → `ANativeWindow`）。
2. 在 Amphora 的 bionic rootfs 进程语境（linker64 路由）里 `dlopen libvulkan.so`，用 adrenotools 钩 Turnip（复用 `TurnipDriverProvisioner` 的产物）。
3. 裸 Vulkan：枚举扩展应见 `VK_KHR_android_surface`；创建 surface → swapchain → 画三角形 present。
4. **通过标准**：三角形稳定 present（≥60fps 无明显抖动）；`vkEnumeratePhysicalDevices` 报告的是 Turnip（driverProperties 验证，不是系统 Adreno）；全程无 X server 进程。
5. 顺带记录：loader 是否需要 `MESA_VK_WSI_PRESENT_MODE` 类调参（现有 preparer 1215 行附近有先例）。

### Spike B（1-2 周）——证"wineandroid 的窗口包装可用 + 延迟可接受"

1. 同样的 present 调用放进 wine 进程，ANativeWindow 用 wineandroid 的包装（EGL 已经在走它，buffer queue 语义相同）。
2. **必答的开放问题**：Java 侧托管模型是"单桌面视图"还是"每顶层窗口一个 surface"（读 pin 里 wineandroid 的 WineActivity 胶水 + 实测）。Vulkan 直出要求游戏 HWND 拥有**专属** ANativeWindow（swapchain 挂独占 surface）。最坏结论：v1 约束全屏游戏（游戏 = 唯一 surface）——可接受，是主场景。
3. **通过标准**：present 延迟与 Spike A 同量级（逐帧 queue/dequeue 的 socket 往返不引入可感知延迟；用现有 HUD 的 display_timing 工具测）。

### Spike C（数周）——端到端

1. 工作块 1+2+3 落地，DXVK 游戏全屏直跑。
2. **通过标准**：目标游戏可玩；帧率/延迟优于或持平 X11 路径（同机同游戏 A/B，X11 路径为对照组）；游戏窗口 GDI 叠加缺失按 v1 策略记录（见 §6 风险表）。

## 6. 风险表与既定对策

| # | 风险 | 对策 |
|---|---|---|
| 1 | Proton 11.0-d12a5634a pin **早于** client_surface 架构（2026-06~09 提交） | 双轨：本地原型按 pin 的老 drawable 模型写；上游 MR 版对准 master。**下次 pin bump 必须收敛到上游版，不长期养两份** |
| 2 | 游戏 Vulkan 窗口上的 GDI 叠加内容丢失（swapchain 独占 buffer queue；对应那个空 present stub 的同根问题） | v1 接受丢失（DXVK 时代游戏 UI 基本引擎内渲染）+ 受影响老游戏按配置留 X11 fallback；后期可仿 X11 的 `client_surface_present` 补合成 |
| 3 | master 上 win32u churn（Rémi 活跃） | 补丁 90% 为纯新增文件，冲突面小；方向有利——上游收敛的正是我们插入的抽象层 |
| 4 | OEM loader 怪癖（ZUI） | 与 X11 路径共担（X11 路径同样依赖 adrenotools），非新增风险；Spike A 即暴露 |
| 5 | wrapper 经进程拆分 socket 的逐帧 queue/dequeue 延迟 | Spike B 量化；不达标则评估批量化/直连方案（先测再优化） |
| 6 | 多窗口语义（上游为单桌面视图模型） | 游戏场景无感；桌面应用场景留 X11 fallback |

## 7. 上游合并策略（可维护性是本方案的卖点）

- **补丁足迹目标 ≈500 行，其中 90% 纯新增一个文件** + win32u 几十行 + Makefile.in 一行。这是最容易合并的补丁类别。
- 拿到 Spike B 通过后尽快向 gitlab.winehq.org 提 MR（LGPL2.1 无障碍）。上游动机极强：这场复兴就是为了跑游戏，Vulkan 正是缺块；系统 loader 方案把 AHB 零拷贝难题整个交给 Android，对他们也是白赚。
- **一旦合入，下次 Proton pin 升级自动携带，本地补丁数归零**——"快速合并上游"的终极形态是不维护补丁。
- wine fork 足迹纪律：只有这一个 feature；adrenotools/预置/Java glue 留在 Amphora 仓库。

## 8. 交接清单（给实现 agent 的操作提示）

- 构建：`./gradlew :app:assembleDebug`（当前 ~4s 增量，全量几分钟）；单测 `:core:engine:testDebugUnitTest`；ktlint 必须过。
- 安装：`adb -s HA262AAH install -r app/build/outputs/apk/debug/app-debug.apk`；启动命令 logcat 验证：`adb logcat -d | grep guestExecutable`（tag `WineEngineImpl`）。
- 设备上 su 可用（KernelSU），可用于查看 `/data/data/app.amphora`、`/data/data/com.xiaoji.egggame`（EGG 参照物）。
- X11 路径是活着的对照组：**任何改动不得使其行为变化**；native 路径做成独立分支/配置，默认关闭，Spike 逐级打开。
- 相关文档：`docs/05-ARCHITECTURE.md`（总架构）、`docs/07-TARGETSDK-SELINUX.md`（linker64 路由）、`docs/08-EGGGAME-COMPARISON.md`（EGG 对比）、`docs/03-TRACKING.md`（注意其 Proton-10.0-4/wine-10.0 记录已过时，现 pin 为 11.0）。

## 9. 关键上游源码索引（实现时对照）

| 文件 | 用途 |
|---|---|
| `dlls/wineandroid.drv/opengl.c` | **模板**：client_surface→ANativeWindow 桥（230 行）；注意 :180 空 present stub |
| `dlls/wineandroid.drv/window.c` | per-HWND ANativeWindow（`get_ioctl_window`）、`android_surface_flush` 软件呈现 |
| `include/wine/vulkan_driver.h` | 4-op 接口（§4.1） |
| `dlls/win32u/vulkan.c` | WSI 核心 + `driver_funcs` 分发 + `nulldrv_funcs` 兜底 |
| `dlls/winex11.drv/opengl.c` | `client_surface_present` 调用参照（混合内容合成） |
| `dlls/wineandroid.drv/WineActivity.java` | host 侧胶水参照（进程拆分/桌面视图/事件管道） |

> 以上均为 gitlab.winehq.org/wine/wine master（2026-09-13 快照）。行号会漂移，以结构为准。
