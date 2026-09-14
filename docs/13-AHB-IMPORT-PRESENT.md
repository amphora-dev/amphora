# 13 · AHB Import CreateSwapchain + Present≥50（knife13）

> 状态：**关键门已过**（2026-09-14 HA262AAH）。  
> 受众：其他 Amphora / wineandroid agent 做审查与下一刀（零拷贝 HWND Surface）。  
> 相关：[`12-WINEANDROID-MIGRATION.md`](12-WINEANDROID-MIGRATION.md)、[`11-ANDROID-NATIVE-VULKAN-PLAN.md`](11-ANDROID-NATIVE-VULKAN-PLAN.md)。  
> 路径巡检口径：无 GB / VkLayer / 扫表；下一拍只清 host blit，**不得退回已通的 AHB import**。

---

## 0. 一句话

Wine/DXVK 的 swapchain 图像不是 ICD 自己建的，而是把宿主 `ANativeWindow` 队列里已经带 `AHardwareBuffer` 的 buffer **导入成 `VkImage`**；GPU clear/绘制直接写进这块共享内存。Present 循环在修好 DXVK 队列线程上的 semaphore/fence 同步后，可稳定跑过第 50 帧，guest staging 读回仍是品红。

**已有零拷贝**：GPU → AHB。  
**尚未零拷贝**：AHB → 会话窗口 Surface（宿主仍可能经 ImageReader / 合成 blit）。下一刀是 HWND Surface 直达。

---

## 1. 验收证据（HA262AAH，2026-09-14）

| 项 | 结果 |
|---|---|
| 设备 | HA262AAH（TB322FC，Adreno 830），adb 在 Mac mini `skydeMac-mini.lan` |
| Smoke | `C:/amphora-dxvk-smoke.exe`（knife9 staging readback） |
| CPU fill | **关**（无 `AMPHORA_CPU_FILL` / `GUEST_CPU_FILL`） |
| CreateSwapchain | `AHB_SC create images=3 import=ok` |
| Present | `hr=0` 到至少 frame=100（日志见 frame=175 `dxvk-smoke done`） |
| guest-readback | frame 0/1/5/25/50 `centerRGBA=217,26,178,255 CLASS=MAGENTA` |
| 截屏 | 左上游戏窗品红；桌面中央多为黑底 |
| 代码 | amphora `344f731`（`main`）；proton-wine `1c62dd9a8ba`（`proton_11.0`） |

判定日志关键字：

```
AHB_SC create images=… import=ok
AHB_SC acquire signal deferred-to-win32u
amphora flush acquire signal res=0
amphora present wait+fence res=0 waits=1 fence=…
d3d-readback … CLASS=MAGENTA
Present frame=50 hr=0x00000000
```

---

## 2. 画面链路（人话 → 代码）

```
Kotlin WineAndroidSessionActivity
  → LD_PRELOAD libamphora_wsi.so（arm64）
  → 创建 sock-proxy ANativeWindow（每 HWND 一条）
  → Wine/box64 + wineandroid.drv + win32u
  → DXVK CreateSwapchain
       win32u amphora_bind_device_wsi
         → amphora_wine_vkCreateSwapchainKHR (wineandroid.so, x86_64)
         → Unix IPC  wsi-sc-<pid>.sock
         → amphora_ahb_sc_create_swapchain (libamphora_wsi)
              dequeue ANWB+AHB → vkCreateImage + BindMemory (ANDROID AHB)
              create 时 win_queue 全部 buffer 给宿主一次
  → DXVK Acquire / Render / Present
       Acquire IPC：只占 FREE 槽，GPU signal 延到 win32u
       win32u QueueSubmit 前 amphora_flush_pending_acquire（同 DXVK 队列）
       Present：win32u 等待 wait-sem + SWAPCHAIN_PRESENT_FENCE（host fence）
                再 IPC Present → guest_queue_knife + 槽 FREE
```

### 2.1 关键文件

| 仓库 | 路径 | 职责 |
|---|---|---|
| amphora | `core/native/.../amphora_ahb_sc.inc` | AHB→VkImage import；槽位 FREE/ACQUIRED；create `win_queue`；Present 只 knife+FREE |
| amphora | `core/native/.../amphora_wsi.c` | `wsi-sc-%pid.sock` 服务端；`amphora_ahb_sc_*` 调度 |
| amphora | `core/native/.../wineandroid_host_anw.c` | 宿主 ANW / AHB 收发 |
| proton-wine | `dlls/wineandroid.drv/vulkan.c` | `amphora_wine_vk*` IPC 客户端；Acquire 后 `amphora_note_acquire_signal` |
| proton-wine | `dlls/wineandroid.drv/amphora_ahb_sc.inc` | 与 amphora 侧 twin（逻辑同源） |
| proton-wine | `dlls/wineandroid.drv/amphora_wsi_bridge.c` | WSI bridge twin |
| proton-wine | `dlls/win32u/vulkan.c` | `amphora_bind_device_wsi`；flush acquire；Present wait+fence |

### 2.2 为什么必须 `AMPHORA_WINEANDROID=1`

- win32u 只在该环境下替换 CreateSwapchain / Acquire / Present。
- CreateDevice 注入 `VK_ANDROID_external_memory_android_hardware_buffer`（+ deps），否则 import `props=0` 失败。
- wineandroid 符号需 `dlopen("wineandroid.so", RTLD_NOLOAD|RTLD_NOW)` 再 `dlsym`（RTLD_LOCAL 导致 `RTLD_DEFAULT` 全 NULL）。

### 2.3 构建注意

- `libamphora_wsi.so`：NDK arm64-v8a，Amphora native CMake target `amphora_wsi`。
- `wineandroid.so` / `win32u.so`：wine-android-build，NDK r29，`x86_64-linux-android30`，源在 `amphora-dev/proton-wine`。
- **win32u 必须带 Vulkan**：`SONAME_LIBVULKAN "libvulkan.so"`；曾误编 `--without-vulkan` → `Wine was built without Vulkan support` / `D3D11CreateDeviceAndSwapChain 0x80004005`（已过时，勿再当当前方向）。
- 真机 sideload：APK 内 `lib/arm64-v8a/libamphora_wsi.so`（zipalign 时 `resources.arsc` 须 Stored）；Proton `x86_64-unix/{wineandroid,win32u}.so` 用 `su cp`；adb 只在 Mac mini。

本地刀目录（非 git）：`/workspace/swapchain-knife/knife13-src-createswapchain/`（BUILD / MAC-SIDELOAD / smoke 脚本 / 截屏）。

---

## 3. 踩坑时间线（审查用）

1. **Runtime hook / VkLayer / GB 扫表**：停掉；主线改为 source-path CreateSwapchain。
2. **import props=0**：CreateDevice 未开 ANDROID AHB external_memory → 注入扩展后 `import=ok`。
3. **品红出现但 Present 卡在 frame=2**：  
   - 曾在 `wsi-sc` IPC 线程 `QueueSubmit` acquire signal → 与 DXVK 抢 `VkQueue`，或 box64 从 Wine 直接调 ICD submit 触发 `UNIX_CALL` assert。  
   - Present 侧跳过 wait-sem → binary semaphore 状态机坏掉。  
   - DXVK `SWAPCHAIN_PRESENT_FENCE` 未在我们的 Present 路径上 signal → `dxvk-submit` 卡在 `vkWaitForFences`。
4. **修复（当前）**：  
   - IPC 线程 **不做** GPU QueueSubmit。  
   - Acquire 成功后 `amphora_note_acquire_signal`；下一次 win32u `vkQueueSubmit` / Present 前 `amphora_flush_pending_acquire`。  
   - Present：在 DXVK 队列上 wait render sems，并对 pNext 里 **已是 host handle** 的 present fence 做 `QueueSubmit(..., fence)`（winevulkan thunk 已 convert），再 IPC 还槽。

---

## 4. 明确不在范围内 / 禁止

- GraphicBuffer 布局猜测、VkLayer、heap/表扫描。
- 用 CPU fill（`AMPHORA_CPU_FILL`）冒充成功。
- 退回 ICD CreateSwapchain 或 X11 长期内层。
- 把每个 HWND 做成系统 freeform 任务（电脑模式壳另议）。

---

## 5. 下一刀：真机 HWND Surface 零拷贝

### 目标

Present 落到 HWND / 会话 Activity 自己的 `Surface`，去掉仍经 host ImageReader / blit 的残留路径。

### 成功标准（路径巡检）

- Present≥50 + 屏上品红（guest-readback 仍 MAGENTA）。
- **日志证明无 host blit**（若当前成功路径已无 blit，验收即可收）。
- **不退回** AHB import。

### 建议验证

- 保留 knife13 import=ok 日志。
- 增加/确认无 `ImageReader` / blit / 二次 copy 的日志或计数。
- HA262AAH screencap 左上窗仍品红。

---

## 6. 给审查 agent 的检查清单

- [ ] `amphora` `main` @ `344f731` 与 `proton-wine` `proton_11.0` @ `1c62dd9a8ba` 已在远端。
- [ ] CreateSwapchain 路径是 IPC → `amphora_ahb_sc_create`，不是 layer/hook。
- [ ] Acquire/Present 的 GPU submit 只在 win32u DXVK 队列线程。
- [ ] Present fence 按 **host** handle 使用（不要再 `vulkan_fence_from_handle` 二次解包）。
- [ ] 真机证据：`import=ok` + `CLASS=MAGENTA` @50 + Present≥50。
- [ ] 下一刀设计是否触及 AHB import（应避免）。

---

## 7. 提交记录

| 日期 | 仓库 | Commit | 说明 |
|---|---|---|---|
| 2026-09-14 | amphora | `344f731` | AHB-import swapchain；wsi-sc；create win_queue |
| 2026-09-14 | proton-wine | `1c62dd9a8ba` | bind WSI；note/flush acquire；present wait+fence |
