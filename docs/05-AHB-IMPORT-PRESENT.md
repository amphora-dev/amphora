# 05 · Vulkan AHB 零拷贝游戏渲染 (AHB Import Present)

> 适用：Amphora 架构与渲染通道维护人员，以及后续审查零拷贝呈现机制的开发者。  
> 关联文档：[`04-WINEANDROID-DISPLAY.md`](04-WINEANDROID-DISPLAY.md)（宿主窗口系统）。  
> **核心铁律**：AHB 零拷贝导入是游戏渲染的现行正轨，后续任何优化**严禁倒退或破坏已通的 AHB Import CreateSwapchain 路径**。

---

## 0. 核心原理解析（面向非底层开发者）

在传统的 Windows 模拟器架构中，3D 游戏（DirectX/Vulkan）画面的输出通常非常笨重：
- 游戏在虚拟机或模拟器内部把画面画在一块虚拟内存里；
- 宿主程序再通过 CPU 内存拷贝，或者借助额外的 GPU 中转复制（Blit / ImageReader），把画面一张张搬到 Android 的窗口上。这会带来严重的画面延迟和巨额的功耗发热。

**Amphora 的零拷贝解决方案（AHB Import）**：
1. **什么是 AHB**：`AHardwareBuffer`（简称 AHB）是 Android 系统底层的硬件缓冲区，能够直接映射到 GPU 物理显存，被系统底层窗口合成器（SurfaceFlinger）直接读取。
2. **什么是零拷贝导入**：在游戏初始化交换链（CreateSwapchain）时，Amphora 没有让底层图形驱动凭空分配虚拟纹理，而是**直接把宿主窗口队列里的 AHB 缓冲区“导入”为 Vulkan 图像（`VkImage`）**。
3. **效果**：游戏 GPU 渲染的每笔像素，实际上是**直接写在 Android 屏幕待显示的显存里**。绘制完成后直接通知系统上屏（Present），中间没有任何二道贩子式的内存拷贝或中间层中转。

---

## 1. 当前判据

AHB 链路的验收标准与验证命令见 §5；最近一次真机 PASS 的版本、判据与数字见
[`docs/02-TRACKING.md`](02-TRACKING.md) §3（AHB 逐帧送显、render-done fence、
acquire fence import、VK_LAYER_AMPHORA_wsi 各行）。**只看回读和静态画面不够**——
帧必须真的送进 BufferQueue（SF `totalFrames ≈ presents`）。

---

## 2. 完整画面链路架构

```text
Kotlin 宿主进程 (WineAndroidSessionActivity)
  │
  ├─ 1. 通过 LD_PRELOAD 预加载 libamphora_wsi.so（arm64-v8a 原生库）
  ├─ 2. 为每个 Windows HWND 窗口分配一条独立的 ANativeWindow 代理通道
  │
  ▼
Guest 模拟环境 (Box64 + Wine + wineandroid.drv + win32u)
  │
  ├─ 3. 游戏启动 DirectX/Vulkan 渲染，调用 DXVK 的 CreateSwapchain
  │     └─ win32u 执行 amphora_bind_device_wsi
  │     └─ 通过 Unix Domain Socket (wsi-sc-<pid>.sock) 向 libamphora_wsi 发送创建请求
  │
  ├─ 4. libamphora_wsi 核心调度（CreateSwapchain）：
  │     └─ 把宿主 BufferQueue 的每个槽位都 dequeue 一次（ANativeWindowBuffer + AHB）
  │     └─ 利用 VK_ANDROID_external_memory_android_hardware_buffer 扩展
  │        调用 vkCreateImage + vkBindImageMemory 将其绑定为 VkImage
  │     └─ 导入完成后全部 cancel 回队列
  │
  └─ 5. 渲染循环（Acquire / Render / Present）：
        ├─ 【Acquire 获取缓冲】：dequeueBuffer 拿回一张，宿主把合成器的 release fence 一起交回
        │   （`CMD_DEQUEUE_FENCE`），libamphora_wsi 以 SYNC_FD 临时导入到 app 的 acquire 信号量 / fence，
        │   GPU 等它；导入不了的部分才走 win32u 的延迟空提交（wsi-sc op 8 回复里的 handled 位）
        ├─ 【Render 绘制】：在 win32u 真正执行 vkQueueSubmit 前，与 DXVK 处于同一队列刷新信号
        └─ 【Present 送显】：win32u 提交呈现等待（含 SWAPCHAIN_PRESENT_FENCE），同一次提交再 signal 一个
           可导出 SYNC_FD 的信号量，导出的 fd 经 wsi-sc op 7 交给 libamphora_wsi，再经 SCM_RIGHTS
           （宿主 CMD_QUEUE_FENCE）交给 queueBuffer；SurfaceFlinger 等 fence 到了才锁存。CPU 不等 GPU。
           扩展 / 导出不可用时退回 QueueWaitIdle + 无 fence 的 op 5
```

### 2.1 涉及的关键源码与职责

| 仓库 | 源码路径 | 模块职责 |
|---|---|---|
| **amphora** | `core/native/src/main/cpp/winlator/amphora_ahb_sc.inc` | 负责 AHB 导入为 VkImage、缓冲槽位状态维护（FREE / ACQUIRED）及交换链呈现 |
| **amphora** | `core/native/src/main/cpp/winlator/amphora_wsi.c` | 提供 `wsi-sc-%pid.sock` 服务端，调度 AHB 交换链流程 |
| **amphora** | `core/native/src/main/cpp/winlator/amphora_vklayer.inc` | 隐式层 `VK_LAYER_AMPHORA_wsi`：给 Khronos loader 补 `VK_KHR_android_surface` 与 surface 查询（§2.4） |
| **amphora** | `core/native/src/main/cpp/winlator/wineandroid_host_anw.c` | 负责宿主端 `ANativeWindow` 与底层 `AHardwareBuffer` 的跨进程收发 |
| **proton-wine** | `dlls/wineandroid.drv/vulkan.c` | Vulkan IPC 客户端，负责通知 win32u 记录 Acquire 信号量状态 |
| **proton-wine** | `dlls/wineandroid.drv/amphora_ahb_sc.inc` | 与 amphora 仓库中的同名文件保持逻辑同源与结构对齐 |
| **proton-wine** | `dlls/wineandroid.drv/amphora_wsi_bridge.c` | Wine 侧与宿主原生 WSI 通信的桥接实现 |
| **proton-wine** | `dlls/win32u/vulkan.c` | 核心调度：绑定设备 WSI、刷新待处理 Acquire 信号、处理 Present 等待栅栏 |

### 2.2 为什么必须开启 `AMPHORA_WINEANDROID=1`

1. **精确替换 WSI**：Wine 底层 win32u 模块只在该环境变量置为 1 时，才会接管并替换底层的 CreateSwapchain、Acquire 和 Present 行为。
2. **强制注入 Vulkan 扩展**：在应用创建 Vulkan 逻辑设备（`vkCreateDevice`）时，强制向设备注入 `VK_ANDROID_external_memory_android_hardware_buffer` 扩展及其依赖，否则后续导入 AHB 时会因驱动不支持而报错失败（`props=0`）。
3. **动态链接符号防丢失**：由于 Android 动态链接器的隔离机制，Wine 组件需要通过 `dlopen("wineandroid.so", RTLD_NOLOAD|RTLD_NOW)` 显式定位驱动符号，防止由于 `RTLD_LOCAL` 导致符号解析为 NULL。

### 2.3 交换链与 BufferQueue 的对应（对齐 AOSP `libvulkan/swapchain.cpp`）

| 环节 | 做法 | 原因 |
|---|---|---|
| 图像数 | `max(minImageCount, MIN_UNDEQUEUED + 2)`，上限 8；`SET_BUFFER_COUNT` 设成同一个数 | 每个队列槽位都是一张已导入的图像，dequeue 不会拿到陌生 buffer；app 最多持有 `图像数 - minImageCount + 1` 张，Acquire 只会等合成器，不会等自己的 Present |
| 重建 | 同一窗口再次 CreateSwapchain 前 `API_DISCONNECT` + `API_CONNECT`（EGL），宿主清空 buffer id 并 generation+1 | 队列只在第一次 queue 之前允许把所有槽位 dequeue 出来；重连后旧 buffer 作废，guest 按新 generation 重新导入 |
| 呈现模式 | 交换链实现的列表只有一份：`amphora_ahb_sc.inc` 的 `g_ahb_sc_present_modes` = FIFO / MAILBOX / IMMEDIATE。swap interval 由设置 Frame rate（env `AMPHORA_FRAME_RATE`）定：`sync`（默认）一律 1；`game` 时 FIFO / FIFO_RELAXED → 1、MAILBOX / IMMEDIATE → 0；`uncapped` 一律 0；固定上限 N 一律 1，`ahb_sc_queue_present` 再把 present 按 1/N 秒节拍（`ahb_sc_pace`，日志 `AHB_SC frame cap=N fps`）。`SET_BUFFER_COUNT` 行带 `present_mode= swap= frame_rate=` | interval 1 由 dequeue 按 vsync 节流；interval 0 时 queue 替换未锁存的帧。SurfaceFlinger 只锁存整帧，所以 IMMEDIATE 和 MAILBOX 行为相同、不撕裂。裸 Vulkan 没有别的限帧器，屏幕也未必有 N Hz 的模式（6T 只有 60），所以上限要在交换链里做；DXVK / VKD3D 自己也按 `DXVK_FRAME_RATE` 限，这里几乎不睡 |
| 并发 | `wsi-sc` 每个请求一个线程 | Acquire 可能阻塞在宿主 dequeue，不能卡住其他交换链的 Present / Destroy |
| 队列配置 | 重连后 `SET_USAGE 0xB00`（TEXTURE / RENDER / COMPOSER）、`SET_BUFFERS_FORMAT`（按交换链格式：R8G8B8A8 → RGBA_8888，A2B10G10R10 → RGBA_1010102，R16G16B16A16_SFLOAT → RGBA_FP16，R5G6B5 → RGB_565）、`SET_BUFFERS_DIMENSIONS` = 交换链 extent、`SCALE_TO_WINDOW` | 断开重连会清掉这些；导入时 VkImage 格式取自 buffer，不设格式时 10-bit 交换链（Adreno 8xx 上 D3D8/9 会选）的像素写进 8888 buffer，画面发白、半透明；AHB 导入要求图像与 buffer 尺寸一致，Surface 尺寸暂时不同（resize 途中）时由合成器缩放 |
| 宿主视图 | client（Vulkan）层按 `clientRect` 布局、buffer 设成客户区尺寸，建成不透明（`setOpaque(true)`） | 同上游 `client_group`；标题栏和边框留在下面的 GDI 视图。Windows 忽略交换链的 alpha，图层标不透明后 SurfaceFlinger 也忽略（之前按 RGBA_8888 半透明合成，vkcube 的 α=0.2 清屏色透出桌面） |
| 呈现 fence | 每个宿主 device 一个 `VkExportSemaphoreCreateInfo(SYNC_FD)` 信号量，present 提交时 signal、`vkGetSemaphoreFdKHR` 导出（导出即复位）；fd 在 box64 同进程里按整数传给 libamphora_wsi，再 SCM_RIGHTS 给宿主 `queueBuffer(fence)` | 替掉每帧 `vkQueueWaitIdle`，和 AOSP 把 release fence 交给 queueBuffer 一致；只有 q50/q100 回读帧在 guest 侧等 fence |

| Acquire fence | 宿主 `CMD_DEQUEUE_FENCE` 在回复前先发 int32 + release fence（SCM_RIGHTS）；libamphora_wsi 对 app 的信号量 / fence 做 `vkImportSemaphoreFdKHR` / `vkImportFenceFdKHR`（SYNC_FD、TEMPORARY，fd -1 = 已释放），不提交；win32u 开 `VK_KHR_external_fence(_fd)` | 同 AOSP / Mesa。取代原来"记在 win32u 一个全局槽、下次提交补空提交"：那个会被多交换链互相覆盖、可能提交到别的 device 的队列、提交前等 acquire fence 会一直等 |

回退：wsi-sc op 8 回复 `handled` 位，导入失败的那一项仍记进 win32u 的全局槽、由下次提交补 signal，这时 libamphora_wsi 先在 CPU 上等 release fence。旧 Proton（op 4）整条走这个回退。

上线顺序：wsi-sc op 7 / op 8 / op 9 和宿主 `CMD_QUEUE_FENCE` / `CMD_DEQUEUE_FENCE` 在 APK 里，新 Proton 会发它们，所以 **APK 先于 Proton**；旧 Proton 配新 APK 走 op 4 / op 5；新 Proton 配旧 APK 时 op 9 得到 `wsi-sc unknown op=9`、连接被关，win32u 改用宿主 loader 的回答。

wsi-sc op 9 `PRESENT_MODES`：请求 `int32 op=9, uint64 host_surface`；回复 `int32 ret`（0 = AHB 交换链的 surface，`VK_ERROR_SURFACE_LOST_KHR` = 没登记），`uint32 count`，`count × uint32 mode`。

### 2.4 guest 的 Vulkan 驱动链

设置里选的驱动决定 guest 用哪个 loader（`GuestProgramLauncherComponent`，按 preparer 是否设了 `VK_ICD_FILENAMES` 分）：

| 驱动 | loader | ICD | surface |
|---|---|---|---|
| wrapper（Adreno 默认） | imagefs Khronos loader 1.4.313 | `wrapper_icd.aarch64.json` → wrapper dlopen `/system/lib64/libvulkan.so`（平台 loader + Qualcomm 驱动）；adrenotools PATH/NAME 不设 | `VK_LAYER_AMPHORA_wsi` |
| Turnip（`WN-Turnip-1.06-b`） | 同上 | wrapper 经 adrenotools 加载包里的 `libvulkan_freedreno.so` | 同上 |
| Leegao | 同上 | Leegao wrapper，adrenotools 指 vendor HAL | 同上 |
| System（模拟器 / 非 Adreno） | 平台 loader（`filesDir/wineandroid/vkloader` 里 `libvulkan.so{,.1}` 软链排在 `LD_LIBRARY_PATH` 最前） | 平台选的驱动 | 平台 loader 自己的 `vkCreateAndroidSurfaceKHR` |

imagefs 的 Khronos loader 是 Linux 构建，没有 Android WSI；wrapper 也只带 X11 WSI。层补这一段：

- **加载**：宿主每次开会话把 manifest 写到 `filesDir/wineandroid/vklayer/amphora_wsi.json`（`library_path` = 本次安装的 `nativeLibraryDir/libamphora_wsi.so`，`instance_extensions` 只有 `VK_KHR_android_surface`，`disable_environment` = `AMPHORA_WSI_LAYER_DISABLE`），`VK_ADD_IMPLICIT_LAYER_PATH` 指过去。loader dlopen 的就是 LD_PRELOAD 进 box64 的那份 so，层和 WSI helper 共用全局状态。
- **`VK_LOADER_DISABLE_INST_EXT_FILTER=1`**：这版 loader 在 vkCreateInstance 时只收它编译进去的已知扩展名，Linux 构建的表里没有 `VK_KHR_android_surface`，即使层提供了也报 `not found in list of known instance extensions`（AIO 弹 `vkCreateInstance Failure`）。
- **建 surface**：loader 也没有 `vkCreateAndroidSurfaceKHR` 的分发槽，所以不经 loader。wineandroid 照旧把宿主 instance 和 client 窗口的 socket 发到 `wsi-<pid>.sock`；helper 看到是层记录过的 instance，就在代理 ANW 上 `API_CONNECT(EGL)`（同 AOSP `CreateAndroidSurfaceKHR`，不连的话第一次 dequeue 报 `BufferQueue has no connected producer`），surface 句柄就是代理窗口指针，登记进 AHB 交换链的表。`vkDestroySurfaceKHR` 时 `API_DISCONNECT` 并放掉窗口。
- **查询**：层拦 `vkGetPhysicalDeviceSurface{Support,Capabilities,Capabilities2,Formats,Formats2,PresentModes}KHR`、`vkGetPhysicalDevicePresentRectanglesKHR`、`vkGetDeviceGroupSurfacePresentModesKHR`，只回答自己的 surface，别的原样往下传。回答照 AOSP `swapchain.cpp`：`minImageCount = min(MAX_BUFFER_COUNT, MIN_UNDEQUEUED + 2)`，`maxImageCount` 不超过 AHB 交换链的 8；变换只有 IDENTITY；格式 RGBA8 UNORM / SRGB，外加 gralloc 能分配的 565 / FP16 / 1010102；呈现模式取 `g_ahb_sc_present_modes`（§2.3，层自己不另列）；窗口查询失败回 `VK_ERROR_SURFACE_LOST_KHR`。win32u 之后再把 extent 改成客户区。
- **呈现模式的唯一来源**：两种 surface（层的、System 下平台 loader 的）都登记在 AHB 交换链的表里，CreateSwapchain 都走 AHB 导入，所以每条驱动路径上交换链实现的都是 `g_ahb_sc_present_modes`。Amphora 会话里 win32u 的 `vkGetPhysicalDeviceSurfacePresentModesKHR` 经 wsi-sc op 9 拿这份列表（System 下平台 loader 回答的是平台交换链的列表，不算数）；op 9 失败（不是 AHB 的 surface、IPC 出错）才原样返回宿主的回答。win32u 不再总是补报 IMMEDIATE，也不再把 IMMEDIATE / MAILBOX 改成 FIFO；vsync 与否由 Frame rate 设置在 AHB 交换链里决定。
- **不报 present wait**：Amphora 会话里 wineandroid 把 `VK_KHR_present_wait` / `VK_KHR_present_wait2` 从 guest 的设备扩展里去掉（proton-wine `433573d69a7`，`ANDROID_map_device_extensions`）。AHB 交换链没有 present wait，win32u 的通用 thunk 会把交换链句柄交给宿主 ICD 的 `vkWaitForPresentKHR`，ICD 不认这个句柄。DXVK 2.x / 3.x 对 FIFO 交换链会调它，所以 Y700 上开 vsync 的 D3D 程序（AIO `--vsync`，DXVK 3.0.2 / 2.6.2）两帧后 `_wassert "!status && vkWaitForPresentKHR"` 卡住（pin `a177c89b7` 也一样）。去掉扩展后 DXVK 日志 `presentWait : 0`，自己控节奏。Sarek 1.11 不用 present wait。
- **交换链**：不变，仍是 wsi-sc 上的 AHB 导入（§2.3）。层 surface 导入失败时不退回 ICD 的 `vkCreateSwapchainKHR`（loader 认不出这个句柄）。helper 取 loader 用 SONAME `libvulkan.so.1`，即 box64 给 win32u 加载的那份；不能用 `libvulkan.so`，那是平台 loader 的 soname，wrapper 也把它加载进同一进程。
- wrapper 默认不设 adrenotools PATH/NAME：指到 `vulkan.adreno.so` 时 wrapper 取实例扩展报 `undefined symbol: vkCreateRayTracingPipelinesKHR`，loader 返回 -9。

---

## 3. 仍然有效的约束（来自踩坑历程）

1. **不做运行时劫持 / 内存扫描截帧**：早期 Runtime Hook、隐式层截帧、扫 GraphicBuffer 内存表的方案在不同驱动上极易崩溃，已废止；现在是源码级 CreateSwapchain 介入。`VK_LAYER_AMPHORA_wsi`（§2.4）是标准 loader 层，只补 Android surface 扩展和查询，不截帧、不接管交换链。
2. **`import props=0` 的处理仍在**：guest 不请求 AHB 扩展时驱动返回属性全零；win32u 在建 device 时自动追加 `VK_ANDROID_external_memory_android_hardware_buffer`。
3. **IPC 线程绝对不执行 GPU 提交**：IPC 只记槽位状态；Acquire 标记推迟到 win32u 在 DXVK 队列线程上的下一次提交前刷新；Present 栅栏在 DXVK 队列线程上处理。
4. **ImageReader / HostVk 中转拷贝已删除，勿回退**：任何形式的中间拷贝（`ImageReader`、CPU copy、host blit）都已被清除，禁止重新引入来假装出画。

---

## 4. 架构禁令与红线守则

为保证渲染管线的纯粹与高性能，以下行为被严格禁止：

1. **严禁恢复任何形式的中间拷贝**：严禁引入 `ImageReader`、CPU 内存拷贝（如 `AMPHORA_CPU_FILL`）或主机端无谓的纹理复制来假装出画。
2. **严禁退回 X11 内层渲染**：游戏 3D 渲染必须直接跑在宿主 client 层与 AHB 零拷贝链路上。
3. **禁止竞态 GPU 提交**：严禁在 IPC 独立线程上直接操作 Vulkan 渲染队列。
4. **禁止猜测私有驱动布局**：不得依靠硬编码偏移猜测高通/联发科私有 GraphicBuffer 内部结构。

---

## 5. 验收标准与验证命令

在修改底层渲染代码或更新 Proton/DXVK 组件后，必须在真机上执行完整验证：

冒烟程序源码在 [`scripts/dxvk-smoke/amphora-dxvk-smoke.c`](../scripts/dxvk-smoke/amphora-dxvk-smoke.c)，文件头有 mingw 构建和推送命令。

[`scripts/dxvk-smoke/present-check.sh`](../scripts/dxvk-smoke/present-check.sh) `<serial> [sync|game|uncapped|30|45|60|90|120]` 跑完下面这些步骤，打印下列判据要看的计数，一轮 10–25 秒。Frame rate 选项写进 `advanced_frame_rate_mode`（`sync` 即不写），退出时还原原来的 prefs。手动步骤：

```bash
# 屏幕须亮着、已解锁，否则 Activity 没有 Surface（no client ANW after wait）
adb shell svc power stayon usb; adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard
adb shell dumpsys SurfaceFlinger --timestats -enable -clear
adb shell am start -n app.amphora/.MainActivity \
  --ez app.amphora.debug.WINEANDROID true \
  --es app.amphora.debug.WINE_EXE /data/user/0/app.amphora/files/exe/amphora-dxvk-smoke.exe
# 等 presents>0 的那条 AHB_SC destroy。DXVK 可能先建一个空交换链再重建，也可能只建一个，
# 所以别数 destroy 次数（只建一个时"等第二次 destroy"会一直等到超时）
adb logcat -e 'AHB_SC destroy .*presents=[1-9]' -m 1
adb shell dumpsys SurfaceFlinger --timestats -dump    # 看 `[BBQ] amphora-client-<hwnd>` 的 totalFrames
adb shell dumpsys SurfaceFlinger --timestats -disable
```

**合格判据**：
- [x] 日志中必须输出 `AHB_SC create images=<N> import=ok`（N 见 §2.3，6T 为 3，Y700 为 6）；
- [x] 日志必须连续输出 `Present frame=... hr=0x00000000` 且帧数稳定超过 **50** 帧；
- [x] 显存回读采样（guest readback）必须持续为纯正品红色（`CLASS=MAGENTA`）；
- [x] **帧真的送到屏幕**：跑前 `dumpsys SurfaceFlinger --timestats -enable -clear`，跑完 `--timestats -dump`，游戏窗口那层 `[BBQ] amphora-client-<hwnd>` 的 `totalFrames` 应接近 `AHB_SC destroy … presents=<n>`（修复前是 4 / 175，修复后 173 / 175）。这条只在 swap interval 1（Sync to display、固定上限）时成立；interval 0（Uncapped、Follow the game 且游戏关 vsync）时被替换的帧不上屏，`totalFrames ≈ 刷新率 × 时长` 且 ≤ `presents`。只看回读和静态品红画面发现不了帧没上屏。同一行的 `fenced=<m>` 在支持 op 7 的 Proton 上应等于 `presents`，为 0 说明退回了 QueueWaitIdle；`imported=` 在支持 op 8 的 Proton 上应等于 `acquires`，为 0 说明 acquire 走了延迟空提交；
- [x] 手机屏幕左上角窗口必须肉眼可见品红色矩形，画面无闪烁、无撕裂、无无响应崩溃（ANR）。
