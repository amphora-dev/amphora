# 09 · AIO Graphics Test 矩阵

> 记档：2026-09-28。设备：Lenovo Y700 `HA262AAH`（Adreno 830，Qualcomm 驱动 0.800.72，Android 16）、OnePlus 6T `5b1736c7`（Adreno 630，Android 15）。
> 关联：[`03`](03-ASSET-MANIFEST.md) §2.6（资产 pin）、[`04`](04-WINEANDROID-DISPLAY.md)（窗口）、[`05`](05-AHB-IMPORT-PRESENT.md)（Vulkan 呈现）。
> 2026-09-16 那次排查（PresentMode 弹窗、`kernel32.dll` `c0000135`）现在都不再出现，不再追。

## 1. 怎么跑

容器里的 AIO 来自 manifest 的 `winnative/Graphics-Test-{32,64}bit.exe`，每次开会话复制到 `C:\ProgramData\Microsoft\Windows\`，开始菜单 Programs → Graphics Test (32/64-bit) 打开它自带的菜单。

脚本（每个后端开一次会话，`--cube <api> --no-menu --bench 8 --autoclose 2`）：

```bash
scripts/aio-smoke/aio-run.sh <serial> <vk|gl|dx7|ddraw2d|dx8|dx9|dx10|dx11|dx12> [bench_s] [64|32]
scripts/aio-smoke/aio-matrix.sh <serial>...     # 全部后端 × 64/32，多台并行，末尾打 RESULT 表
```

产物在 `.tmp/aio-smoke/`：截图（第一个 AHB 交换链出现后 3 s）、logcat、SF timestats、`wine_stderr.log`、AIO 的 `AIO-Graphics-Test_bench.csv`（写在 guest 工作目录 = imagefs 根）。

判读：
- `bench=none`：后端没起来，看截图里 AIO 的报错框和 `err=`。
- 有 `bench` 也不等于有画面（见 §3 FF 那条：帧照常提交，方块没画），要看截图。
- FPS 都卡在屏幕刷新率：win32u 把 IMMEDIATE / MAILBOX 改成 FIFO，这些数字不代表性能。

## 2. 当前结果

Proton 为 proton-wine `wip/aio-matrix` @ `6512f18bd02`（dev 构建），APK 为引入本文的那次提交。

| 后端 | 路径 | Y700 64 | Y700 32 | 6T 64 | 6T 32 |
|---|---|---|---|---|---|
| vk | 原生 Vulkan | ✅ | ✅ | ✅ | ✅ |
| gl | opengl32 → EGL | ❌ 无 GL | ❌ | ❌ | ❌ |
| dx7 | 64：wined3d(GL)；32：d7vk → DXVK | ❌ 无 GL | ⚠️ 空画面 | ❌ 无 GL | ✅ |
| ddraw2d | 64：GDI；32：wrapper → DXVK D3D9 | ✅ | ⚠️ 空画面 | ✅ | ✅ |
| dx8 / dx9 | DXVK D3D8/9（FF） | ⚠️ 空画面 | ⚠️ 空画面 | ✅ | ✅ |
| dx10 / dx11 | DXVK | ✅ | ✅ | ✅ | ✅ |
| dx12 | vkd3d-proton | ❌ 驱动缺能力 | ❌ | ❌ 驱动缺能力 | ❌ |

6T 自动选 DXVK-Sarek 1.11（Vulkan 1.1），Y700 选 DXVK 3.0.2-gplasync。⚠️ 空画面 = 有帧、有 bench，只有清屏色。Y700 把 `dxvk_flavor` 设成 `sarek` 后，dx8 / dx9 / 32-bit dx7 / 32-bit ddraw2d 全部出方块和色条。

## 3. 已修（2026-09-28）

1. **Y700 上 32-bit DXVK 一律建不了设备**（`VK_ERROR_FEATURE_NOT_PRESENT`，safe mode 也一样；d7vk / wrapper 的 32-bit DirectDraw 跟着挂）。winevulkan 的 thunk 把 client 结构逐成员拷进 `conversion_context` 的缓冲区，padding 留着缓冲区里的旧数据；Qualcomm 驱动把 `VkPhysicalDeviceVulkan13Features`（15 个 VkBool32，80 字节）末尾的 padding 当成请求的特性。64-bit 能过只是那块缓冲区碰巧是 0。修法：`conversion_context_alloc` 先清零（proton-wine `6512f18bd02`）。定位方法：在 win32u 里把被拒的 `vkCreateDevice` 每次只改一处重试，只清那个 padding 字就能建成。
2. **游戏画面的 alpha 漏进 Android 合成**：client（Vulkan）SurfaceView 原来是 `RGBA_8888`（半透明），SurfaceFlinger 按交换链的 alpha 混合，vkcube 的 α=0.2 清屏色透出桌面。现在 client 视图是 `PixelFormat.OPAQUE`（`WineAndroidDesktop.kt`），GDI 视图不变。
3. **10-bit 交换链画面发白**：AHB 交换链没设 buffer 格式，队列按 SurfaceView 默认给 8888，导入时 VkImage 格式又取自 buffer，Y700 上 D3D8/9 选的 A2B10G10R10 像素写进了 8888 buffer（暗背景 (26,26,31) 显示成 (104,160,193)，alpha 字节 0xC7 → 半透明）。现在按交换链格式 `SET_BUFFERS_FORMAT`（`amphora_ahb_sc.inc`，见 [`05`](05-AHB-IMPORT-PRESENT.md) §2.3）。
4. **D3D12 在创建 instance 时就失败**：vkd3d-proton 把 `vkGetPhysicalDeviceCooperativeMatrixPropertiesKHR` 当必需函数取；Khronos loader 对任何物理设备函数都给跳板，Android loader 在驱动没这个扩展时返回 NULL，winevulkan 照传。现在 `vkGetInstanceProcAddr` 对宿主缺的物理设备函数也返回 thunk（proton-wine `29a3ed5f28d`）。两台现在都过了这一步，卡在下面驱动能力上。

## 4. 未解决

- **Y700 D3D8/9 固定管线不出画**：DXVK 3.0.2-gplasync 的 FF VS/FS 管线在 Qualcomm 0.800.72 上 `vkCreateGraphicsPipelines` 返回 -13（`Failed to compile pipeline: -13`，每轮 1–2 条），清屏照常、方块不画。32-bit dx7 / ddraw2d 经 wrapper 落到 D3D9 FF，同样。Sarek 1.11 没这个问题。可选：Qualcomm 驱动上 D3D8/9 默认走 Sarek，或者查 DXVK FF 着色器里驱动不收的写法。
- **D3D12**：Y700 Qualcomm 驱动 `uniform/storageTexelBufferOffsetSingleTexelAlignment` 都是 false（vkd3d：`Lacking support for single texel alignment`）；6T 缺 transform feedback（`Lacking support for transform feedback`）。都是驱动能力，不是我们的链路。Turnip 满足这两项，但 wineandroid 路径不认 Turnip 设置：`adrenotools_driver_id=WN-Turnip-1.06-b` 时宿主进程加载了 Turnip，guest 里 DXVK 看到的仍是 Qualcomm 驱动。
- **OpenGL**：`wineandroid.drv/opengl.c` 用 `EGL_PLATFORM_ANDROID_KHR` 调 guest 的 x86_64 Mesa libEGL，`eglChooseConfig` 一个 config 都没有（`egldrv_init_pixel_formats Failed to get any configs`）。以前 X11 路径靠 zink；wineandroid 上 GL 要单独设计（例如 zink 画到我们的 Vulkan 交换链）。64-bit DX7 走 wined3d GL，跟着不可用。
- **6T 64-bit dx11 偶发卡首帧**：第二个交换链第一次 acquire 之后再没有 present，AIO 没写 bench（4 轮里 1 次，其余 3 次正常），未查。
