# 05 · Vulkan AHB 零拷贝游戏渲染 (AHB Import Present)

> 状态：**关键门禁已通过**（2026-09-14 在 Lenovo Y700 HA262AAH 真机验证）。  
> 适用：Amphora 架构与渲染通道维护人员，以及后续审查零拷贝呈现机制的开发者。  
> 关联文档：[`04-WINEANDROID-DISPLAY.md`](04-WINEANDROID-DISPLAY.md)（宿主窗口系统）、[`research/11-ANDROID-NATIVE-VULKAN-PLAN.md`](research/11-ANDROID-NATIVE-VULKAN-PLAN.md)（前期技术预研）。  
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

## 1. 验收证据（Lenovo Y700 HA262AAH 真机）

| 验证项 | 实测结果 |
|---|---|
| **测试设备** | Lenovo Y700 (TB322FC，骁龙 8 Gen 3 / Adreno 830)，序列号 `HA262AAH` |
| **测试程序** | `C:/amphora-dxvk-smoke.exe`（DXVK 3D 渲染与回读冒烟程序） |
| **CPU 伪造填充** | **完全关闭**（未开启任何 `AMPHORA_CPU_FILL` 或 `GUEST_CPU_FILL`） |
| **交换链创建** | `AHB_SC create images=3 import=ok`（3 张系统显存缓冲全部成功导入为 VkImage） |
| **帧率与稳定性** | `hr=0` 稳定跑过 50 帧并持续运行至 100+ 帧（实测跑满至 175 帧测试完成） |
| **画面正确性验证** | 第 0、1、5、25、50 帧显存回读采样：`centerRGBA=217,26,178,255 CLASS=MAGENTA`（纯正品红色） |
| **真机视觉呈现** | 屏幕左上角游戏测试窗口正常显示实心品红色，无撕裂、卡死或黑屏 |
| **关联代码版本** | amphora 仓库 `344f731`（`main` 分支）；proton-wine 仓库 `1c62dd9a8ba` |

**真机判定日志关键特征**：

```text
AHB_SC create images=3 import=ok
AHB_SC acquire signal deferred-to-win32u
amphora flush acquire signal res=0
amphora present wait+fence res=0 waits=1 fence=...
d3d-readback ... CLASS=MAGENTA
Present frame=50 hr=0x00000000
```

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
  ├─ 4. libamphora_wsi 核心调度：
  │     └─ 从宿主 ANativeWindow 队列取出物理 Buffer (ANativeWindowBuffer + AHB)
  │     └─ 利用 VK_ANDROID_external_memory_android_hardware_buffer 扩展
  │        调用 vkCreateImage + vkBindImageMemory2 将其绑定为 VkImage
  │     └─ 初始化阶段一次性将窗口队列 buffer 全部交给宿主
  │
  └─ 5. 渲染循环（Acquire / Render / Present）：
        ├─ 【Acquire 获取缓冲】：仅在内存槽位中标记 FREE，将 GPU 信号量等待推迟到 win32u
        ├─ 【Render 绘制】：在 win32u 真正执行 vkQueueSubmit 前，与 DXVK 处于同一队列刷新信号
        └─ 【Present 送显】：win32u 等待渲染信号量与呈现栅栏（SWAPCHAIN_PRESENT_FENCE）就绪后，
           通过 IPC 发送呈现指令并释放槽位，屏幕硬件自动刷新显示
```

### 2.1 涉及的关键源码与职责

| 仓库 | 源码路径 | 模块职责 |
|---|---|---|
| **amphora** | `core/native/src/main/cpp/winlator/amphora_ahb_sc.inc` | 负责 AHB 导入为 VkImage、缓冲槽位状态维护（FREE / ACQUIRED）及交换链呈现 |
| **amphora** | `core/native/src/main/cpp/winlator/amphora_wsi.c` | 提供 `wsi-sc-%pid.sock` 服务端，调度 AHB 交换链流程 |
| **amphora** | `core/native/src/main/cpp/winlator/wineandroid_host_anw.c` | 负责宿主端 `ANativeWindow` 与底层 `AHardwareBuffer` 的跨进程收发 |
| **proton-wine** | `dlls/wineandroid.drv/vulkan.c` | Vulkan IPC 客户端，负责通知 win32u 记录 Acquire 信号量状态 |
| **proton-wine** | `dlls/wineandroid.drv/amphora_ahb_sc.inc` | 与 amphora 仓库中的同名文件保持逻辑同源与结构对齐 |
| **proton-wine** | `dlls/wineandroid.drv/amphora_wsi_bridge.c` | Wine 侧与宿主原生 WSI 通信的桥接实现 |
| **proton-wine** | `dlls/win32u/vulkan.c` | 核心调度：绑定设备 WSI、刷新待处理 Acquire 信号、处理 Present 等待栅栏 |

### 2.2 为什么必须开启 `AMPHORA_WINEANDROID=1`

1. **精确替换 WSI**：Wine 底层 win32u 模块只在该环境变量置为 1 时，才会接管并替换底层的 CreateSwapchain、Acquire 和 Present 行为。
2. **强制注入 Vulkan 扩展**：在应用创建 Vulkan 逻辑设备（`vkCreateDevice`）时，强制向设备注入 `VK_ANDROID_external_memory_android_hardware_buffer` 扩展及其依赖，否则后续导入 AHB 时会因驱动不支持而报错失败（`props=0`）。
3. **动态链接符号防丢失**：由于 Android 动态链接器的隔离机制，Wine 组件需要通过 `dlopen("wineandroid.so", RTLD_NOLOAD|RTLD_NOW)` 显式定位驱动符号，防止由于 `RTLD_LOCAL` 导致符号解析为 NULL。

---

## 3. 核心技术踩坑与解决历程

在打通零拷贝渲染的过程中，团队经历了四个关键技术攻坚节点：

1. **废弃旧式 Hook 与内存扫描**：
   - 早期方案曾尝试通过运行时劫持（Runtime Hook）、Vulkan 隐式层（VkLayer）或扫描底层 GraphicBuffer 内存表来截获帧缓冲；
   - 实践证明这类方案在不同 Android 版本和厂商 GPU 驱动上极易崩溃且极难维护。现已全面废止，转为源码级标准 CreateSwapchain 介入。
2. **解决显存导入属性为空（`import props=0`）**：
   - 现象：尝试将 AHB 绑定为 VkImage 时，驱动返回属性全零，显存绑定失败；
   - 根因：游戏本身并不知道自己运行在 Android 上，创建 Vulkan 设备时未请求 Android 硬件缓冲区扩展；
   - 解决：在 win32u 设备创建流程中，自动检测并透明追加 Android 硬件显存扩展。
3. **攻克画面卡死在第 2 帧的同步死锁**：
   - 现象：第一帧画面能正常渲染（屏幕出现品红色），但程序渲染到第 2 帧时彻底卡死；
   - 根因一（队列冲突）：此前直接在 IPC 通信线程上调用 `vkQueueSubmit` 提交信号量，导致与 DXVK 自身的渲染队列线程发生并发抢占甚至死锁；
   - 根因二（状态机损坏）：Present 流程中跳过了部分渲染等待信号量（wait-semaphore），导致 Vulkan 内部的二元信号量状态错乱；
   - 根因三（栅栏未通知）：DXVK 内部依赖 `SWAPCHAIN_PRESENT_FENCE` 栅栏来确认上一帧是否显示完成。如果我们的呈现逻辑没有适时发出该栅栏信号，DXVK 会在 `vkWaitForFences` 上无限期等待。
4. **现行最终同步机制（稳定通过 100+ 帧）**：
   - **IPC 线程绝对不执行 GPU 提交**：IPC 只负责在内存中记录槽位状态；
   - **推迟刷新 Acquire 信号**：Acquire 成功后仅做标记，推迟到 win32u 下一次在 DXVK 自己的队列线程上执行 `vkQueueSubmit` 或 Present 之前，顺手执行 `amphora_flush_pending_acquire`；
   - **统一处理 Present 栅栏**：在 DXVK 队列线程上等待渲染信号量，并触发 host 端的 present fence，最后再通知 IPC 释放缓冲槽位，彻底消除线程竞争。

---

## 4. 架构禁令与红线守则

为保证渲染管线的纯粹与高性能，以下行为被严格禁止：

1. **严禁恢复任何形式的中间拷贝**：严禁引入 `ImageReader`、CPU 内存拷贝（如 `AMPHORA_CPU_FILL`）或主机端无谓的纹理复制（HostVk Blit）来假装出画。
2. **严禁退回 X11 内层渲染**：游戏 3D 渲染必须直接跑在宿主 SurfaceView 与 AHB 零拷贝链路上。
3. **禁止竞态 GPU 提交**：严禁在 IPC 独立线程上直接操作 Vulkan 渲染队列。
4. **禁止猜测私有驱动布局**：不得依靠硬编码偏移猜测高通/联发科私有 GraphicBuffer 内部结构。

---

## 5. 验收标准与验证命令

在修改底层渲染代码或更新 Proton/DXVK 组件后，必须在真机上执行完整验证：

```bash
# 启动 DXVK 3D 渲染冒烟测试
adb shell am start -n app.amphora/.MainActivity \
  --ez app.amphora.debug.WINE_SMOKE true \
  --es app.amphora.debug.WINE_EXE 'C:/amphora-dxvk-smoke.exe'
```

**合格判据**：
- [x] 日志中必须输出 `AHB_SC create images=3 import=ok`；
- [x] 日志必须连续输出 `Present frame=... hr=0x00000000` 且帧数稳定超过 **50** 帧；
- [x] 显存回读采样（guest readback）必须持续为纯正品红色（`CLASS=MAGENTA`）；
- [x] 手机屏幕左上角窗口必须肉眼可见品红色矩形，画面无闪烁、无撕裂、无无响应崩溃（ANR）。
