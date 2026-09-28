# 09 · AIO Graphics Test 矩阵

> 记档：2026-09-28。设备：Lenovo Y700 `HA262AAH`（Adreno 830，Qualcomm 驱动 0.800.72，Android 16）、OnePlus 6T `5b1736c7`（Adreno 630，Android 15）。
> 关联：[`03`](03-ASSET-MANIFEST.md) §2.6（资产 pin）、[`04`](04-WINEANDROID-DISPLAY.md)（窗口）、[`05`](05-AHB-IMPORT-PRESENT.md)（Vulkan 呈现）。
> 2026-09-16 那次排查（PresentMode 弹窗、`kernel32.dll` `c0000135`）现在都不再出现，不再追。

## 1. 怎么跑

容器里的 AIO（2.1.0）来自 manifest 的 `aio-graphics-test/Graphics-Test-{32,64}bit.exe`，每次开会话复制到 `C:\ProgramData\Microsoft\Windows\`，开始菜单 Programs → Graphics Test (32/64-bit) 打开它自带的菜单。

脚本（每个后端开一次会话，`--cube <api> --no-menu --bench 8 --autoclose 2`）：

```bash
scripts/aio-smoke/aio-run.sh <serial> <vk|gl|dx7|ddraw2d|dx8|dx9|dx10|dx11|dx12> [bench_s] [64|32]
scripts/aio-smoke/aio-matrix.sh <serial>...     # 全部后端 × 64/32，多台并行，末尾打 RESULT 表
```

产物在 `.tmp/aio-smoke/`：截图（第一个 AHB 交换链出现后 3 s）、logcat、SF timestats、`wine_stderr.log`、AIO 的 `AIO-Graphics-Test_bench.csv`（写在 guest 工作目录 = imagefs 根下的 `AIO Results/Benchmark/`）。

判读：
- `bench=none`：后端没起来，看截图里 AIO 的报错框和 `err=`。
- 有 `bench` 也不等于有画面（见 §4 FF 那条：帧照常提交，方块没画），要看截图。
- FPS 都卡在屏幕刷新率：交换链只有 FIFO / MAILBOX，win32u 把 IMMEDIATE 改成 FIFO，这些数字不代表性能。
- 换驱动：先 `am force-stop app.amphora`，在 `shared_prefs/amphora_graphics.xml` 加 `<string name="adrenotools_driver_id">WN-Turnip-1.06-b</string>`（或 `System`），测完还原原文件；会话日志 `Launch graphics driver: configured=…`、DXVK 的 `Found device: … (Wrapper driver <版本>)` 确认实际驱动（Turnip 是 `26.2.99`，Qualcomm 是 `0.800.72`）。DXVK 版本同一文件的 `dxvk_flavor`（`dxvk` / `sarek`，不设 = auto），wine 日志 `DXVK: 3.0.2` 或 `DXVK-Sarek: v1.11.0-async` 确认。

## 2. 当前结果

组合：APK 自引入 `VK_LAYER_AMPHORA_wsi` 的提交起（guest 走 imagefs Khronos loader + 该层，见 [`05`](05-AHB-IMPORT-PRESENT.md) §2.4），Proton `11.0-2fd246cfc`（发布版），AIO 2.1.0。默认驱动 wrapper（Qualcomm 驱动之上的 ICD）：

| 后端 | 路径 | Y700 64 | Y700 32 | 6T 64 | 6T 32 |
|---|---|---|---|---|---|
| vk | 原生 Vulkan | ✅ | ✅ | ✅ | ✅ |
| gl | opengl32 → EGL | ❌ 无 GL | ❌ | ❌ | ❌ |
| dx7 | 64：wined3d(GL)；32：d7vk → DXVK | ❌ 无 GL | ⚠️ 空画面 | ❌ 无 GL | ✅ |
| ddraw2d | 64：GDI；32：wrapper → DXVK D3D9 | ✅ | ⚠️ 空画面 | ✅ | ✅ |
| dx8 | DXVK D3D8 | ❌ AIO 2.1.0 | ❌ | ❌ | ❌ |
| dx9 | DXVK D3D9（FF） | ⚠️ 空画面 | ⚠️ 空画面 | ✅ | ✅ |
| dx10 / dx11 | DXVK | ✅ | ✅ | ✅ | ✅ |
| dx12 | vkd3d-proton | ✅ | ✅ | ❌ 缺 transform feedback | ❌ |

Turnip（`WN-Turnip-1.06-b`）只测了 vk / dx9 / dx11 / dx12 / ddraw2d：
- **Y700 全过**，而且 dx9、32-bit ddraw2d 出方块 / 色条（FF 的 -13 是 Qualcomm 驱动的问题，Turnip 上没有）；dx12 同样能跑。
- **6T 默认只有 vk 能跑**：WN-Turnip 在 A630 上不报 `storageBuffer16BitAccess`，DXVK 3.0.2 不收这个设备（`No adapters found`）。auto 只看 Vulkan 版本（Turnip 报 1.3），所以还是选了 3.0.2。手动设 `dxvk_flavor=sarek` 后 dx11 64 / dx9 32 / ddraw2d 32 都出画，58 / 58 / 59 fps（wrapper 下 32-bit ddraw2d 只有 24）；dx12 仍不行：vkd3d 在 Turnip A630 上建成了设备，但 dxgi 来自 Sarek，不认 D3D12 设备（`CreateSwapChainForHwnd: Unsupported device type`）。

6T 自动选 DXVK-Sarek 1.11（Vulkan 1.1），Y700 选 DXVK 3.0.2-gplasync。⚠️ 空画面 = 有帧、有 bench，只有清屏色。Y700 把 `dxvk_flavor` 设成 `sarek` 后，dx9 / 32-bit dx7 / 32-bit ddraw2d 全部出方块和色条（v1.7.0 时测，dx8 当时同样）。帧率两台都卡在刷新率（Y700 144、6T 60；6T 32-bit dx7 35、ddraw2d 24）。System 驱动（平台 loader，不经层）在 Y700 上 vk / dx11 64 照常 144 fps。

AIO 2.1.0 与 v1.7.0 的差别：
- **dx8**：2.1.0 建窗口模式设备时 `FullScreen_PresentationInterval` 填 `D3DPRESENT_INTERVAL_IMMEDIATE`；DXVK d3d8 按 D3D8 规则只接受 `DEFAULT`，返回 `D3DERR_INVALIDCALL`，AIO 弹 "Could not create a Direct3D 8 device"。v1.7.0 能建设备。这是 AIO 的问题，不是我们的链路。
- **64-bit ddraw2d** 截图要早（`SHOT_DELAY=5`），2.1.0 跑完 bench 窗口就关了。

## 3. 已修（2026-09-28）

1. **Y700 上 32-bit DXVK 一律建不了设备**（`VK_ERROR_FEATURE_NOT_PRESENT`，safe mode 也一样；d7vk / wrapper 的 32-bit DirectDraw 跟着挂）。winevulkan 的 thunk 把 client 结构逐成员拷进 `conversion_context` 的缓冲区，padding 留着缓冲区里的旧数据；Qualcomm 驱动把 `VkPhysicalDeviceVulkan13Features`（15 个 VkBool32，80 字节）末尾的 padding 当成请求的特性。64-bit 能过只是那块缓冲区碰巧是 0。修法：`conversion_context_alloc` 先清零（proton-wine `6512f18bd02`）。定位方法：在 win32u 里把被拒的 `vkCreateDevice` 每次只改一处重试，只清那个 padding 字就能建成。
2. **游戏画面的 alpha 漏进 Android 合成**：client（Vulkan）SurfaceView 原来是 `RGBA_8888`（半透明），SurfaceFlinger 按交换链的 alpha 混合，vkcube 的 α=0.2 清屏色透出桌面。现在 client 视图是 `PixelFormat.OPAQUE`（`WineAndroidDesktop.kt`），GDI 视图不变。
3. **10-bit 交换链画面发白**：AHB 交换链没设 buffer 格式，队列按 SurfaceView 默认给 8888，导入时 VkImage 格式又取自 buffer，Y700 上 D3D8/9 选的 A2B10G10R10 像素写进了 8888 buffer（暗背景 (26,26,31) 显示成 (104,160,193)，alpha 字节 0xC7 → 半透明）。现在按交换链格式 `SET_BUFFERS_FORMAT`（`amphora_ahb_sc.inc`，见 [`05`](05-AHB-IMPORT-PRESENT.md) §2.3）。
4. **D3D12 在创建 instance 时就失败**：vkd3d-proton 把 `vkGetPhysicalDeviceCooperativeMatrixPropertiesKHR` 当必需函数取；Khronos loader 对任何物理设备函数都给跳板，Android loader 在驱动没这个扩展时返回 NULL，winevulkan 照传。现在 `vkGetInstanceProcAddr` 对宿主缺的物理设备函数也返回 thunk（proton-wine `29a3ed5f28d`）。两台现在都过了这一步，卡在下面驱动能力上。
5. **设置里选的驱动到不了 guest**：wineandroid 会话原来一律把 guest 指到平台 loader（`/system/lib64/libvulkan.so` → Qualcomm 驱动），wrapper / Turnip 设置只影响宿主进程。原因是 imagefs 的 Khronos loader 是 Linux 构建，没有 `VK_KHR_android_surface`，而 wrapper 只带 X11 WSI。现在由 `libamphora_wsi.so` 里的隐式层 `VK_LAYER_AMPHORA_wsi` 提供这个扩展和 surface 查询，guest 用 Khronos loader + wrapper ICD（Turnip 经 adrenotools）；System 驱动仍走平台 loader（[`05`](05-AHB-IMPORT-PRESENT.md) §2.4）。wrapper 默认不再把 adrenotools 指到 `vulkan.adreno.so`：那样 wrapper 取实例扩展时报 `undefined symbol: vkCreateRayTracingPipelinesKHR`，loader 返回 -9。Y700 的 dx12 在 wrapper 下能跑：平台 loader 直连 Qualcomm 驱动时 vkd3d 报 `Lacking support for single texel alignment`，经 wrapper 不再报（wrapper 具体怎么补的没查）。

## 4. 未解决

- **Y700 D3D8/9 固定管线不出画（Qualcomm 驱动）**：DXVK 3.0.2-gplasync 的 FF VS/FS 管线在 Qualcomm 0.800.72 上 `vkCreateGraphicsPipelines` 返回 -13（`Failed to compile pipeline: -13`，每轮 1–2 条），清屏照常、方块不画。32-bit dx7 / ddraw2d 经 wrapper 落到 D3D9 FF，同样。Sarek 1.11 和 Turnip 都没这个问题。可选：Qualcomm 驱动上 D3D8/9 默认走 Sarek 或 Turnip，或者查 DXVK FF 着色器里驱动不收的写法。
- **D3D12 在 6T**：wrapper 下仍缺 transform feedback（`Lacking support for transform feedback`）。Turnip 下 vkd3d 能建设备，但 3.0.2 的 dxgi 因缺 16-bit storage 枚举不到适配器，Sarek 的 dxgi 又不认 D3D12 设备，两条都走不通。
- **Vulkan 窗口不能切后台**：Activity 进后台时容器 SurfaceView 销毁（`layer root destroyed`），宿主那一侧的 buffer serve 退出，guest 下一次 acquire 拿到 -5、重建交换链失败，vkcube 退出，会话随之结束。平台 loader 和层两条路径一样，未修。GDI 窗口不受影响（灭屏再亮屏会重挂层，见 [`02`](02-TRACKING.md) 2026-09-28 层序条目）。
- **OpenGL**：`wineandroid.drv/opengl.c` 用 `EGL_PLATFORM_ANDROID_KHR` 调 imagefs 的 Mesa libEGL（原生 aarch64，Box64 直接转过去；构建只有 `-Dplatforms=x11`），`eglChooseConfig` 一个 config 都没有（`egldrv_init_pixel_formats Failed to get any configs`）。X11 时同一份 Mesa 走 EGL X11 平台 + zink + kopper（xcb surface 经 wrapper ICD）。wineandroid 上缺的是 Wine 的 GL 驱动这一段和 kopper 的出画目标，方案见 [`02`](02-TRACKING.md) 当前状态指针。64-bit DX7 走 wined3d GL，跟着不可用。
- **6T 64-bit dx11 偶发卡首帧**：第二个交换链第一次 acquire 之后再没有 present，AIO 没写 bench（2026-09-28 dev 构建 4 轮里 1 次；发布组合那一轮正常），未查。
