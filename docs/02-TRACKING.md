# 02 · 进度与当前状态

> 本文是**唯一进度真源**，只记当前状态；过程历史看 `git log`。清理前完整历史：`git show dd99971:docs/02-TRACKING.md`。

---

## 1. 当前版本与暂停点

- main `4229aaa`（`app_update 0.1.0+266.4229aaa6`），Proton pin `11.0-2fd246cfc`（`Proton-11.0-2fd246cfc-x86_64.wcp`）。2026-09-28 用户要求先停，当时所有仓库已推送、无未提交改动。
- 阶段进度（当时与用户定的顺序）：
  - ✅ Phase 0：PR 合并、AIO 2.1.0、HUD FPS
  - ✅ Phase 1a：SurfaceControl 层序、Vulkan 窗口保留边框
  - ✅ Phase 1b：`VK_LAYER_AMPHORA_wsi`（设置里选的驱动真正到 guest）
  - ✅ Phase 2 的测量部分：Turnip 上 D3D8/9 FF、D3D12；wrapper + Qualcomm 复测 -13，结果在 [`09`](09-AIO-GRAPHICS-TEST.md) §2 / §4
- 2026-10-17 · 清理 X11 遗留（`wip/cleanup`：bb3bed7 / c0d3a07 / 8f7a4aa / a556914 + 本次 docs 提交）：删 Java X server、旧 host Vulkan 渲染器（vk/ + shaders）、drawable/sync_fence/gpu_image JNI、无引用脚本，release 补 wineandroid IPC 回调 keep 规则。真机冒烟 PASS（Y700，APK `5c8f5e1`，Proton `11.0-2fd246cfc`，产物 `.tmp/cleanup-smoke/`）：GDI 桌面 + File Manager 出画（`registerSurface ok=true`、无 FATAL / 新 tombstone）；vk 64 144.32 fps（presents 1157 / SF 1155）；dx11 64 144.00 fps（1148/1145）；dx8 64 设备已建成、bench 144.05 fps，但方块不画（Qualcomm FF -13 已知项，见 §2）。

## 2. 下一步与待决

- **DXVK flavor `auto` 改为 guest 侧探测（已落地）**：`auto` 现在按 *guest 看到的* 设备能力选——`amphora-vkprobe.exe`（`scripts/guest-vkprobe/vkprobe.c`，mingw 构建，产物提交为 `app/src/main/assets/vkprobe/amphora-vkprobe.exe`）在**会话内**当启动命令跑：cache miss 时 `WineAndroidLauncher` 把命令换成 `wine start /wait explorer /desktop=shell,WxH "C:\amphora-vkprobe.exe" --then "<game>" <args>`，探针写完 `C:\amphora-vkprobe.json` 后轮询 `C:\amphora-vkprobe.go`（20 s 上限），宿主侧 `GuestVulkanProbe.awaitVerdict` 解析 → `DxvkRequirements` 完整必需集（51 特性 + 2 扩展 + Vulkan 1.3 + 图形+计算队列 + push-data 上限，源自 DXVK gplasync `6b20f622a` `dxvk_device_info.cpp`；Sarek 按其 fork 源码：Vulkan ≥1.1 + `VK_KHR_swapchain` + 图形+计算队列）→ `WinlatorContainerManager.applyProbeVerdict` → `WineSessionPreparer.reapplyDxwrapper` 重链 system32/syswow64 并更新容器 `dxwrapper` → 写 `.go` 放行游戏（任何失败也写 `.go`，按 provisional 链接照样启动）。`getOrCreate` 无条件装全部 pin 的 DXVK（3.0.2 + Sarek），relink 永不下载。
  - **探测缓存 = `CapabilityProbeStore`**（`files/amphora_vkprobe.json`，旧单槽文件直接废弃）：fingerprint 是带名字的组件（driver id、driver 包版本、wine/box64 构建、wsi layer、APK versionCode、探针 sha256、会话 env 的 guest 可见切片 `VK_/ADRENOTOOLS_/WRAPPER_/AMPHORA_/TU_DEBUG/LD_* /XDG_DATA_DIRS/VKD3D_`），组件随结果一起存，miss 时能解释「driver changed」；多槽 LRU（换驱动来回不切不重复探）；原子写、坏文件/未知 schema 视为 miss；同 fingerprint 并发共享一次运行；失败带原因落盘，最多重试 3 次启动，之后只有 fingerprint 变化或设置页「Re-detect GPU capabilities」才重探。
  - **只有 Wine 启动会探测，设置页只读**：游戏和桌面启动走同一套——当前驱动 + 会话 env 的 fingerprint 没有 verdict 时，命令换成 `"C:\amphora-vkprobe.exe" --then <game>`（桌面是 `--then winefile.exe`，`buildWineExplorerProbeCommand`），与 DXVK flavor 选哪个无关（D3D12 / zink / BCn 也吃这份报告）；握手只在结论与当前链接不同时才 relink。设置页不起 Wine：没 verdict 时 DXVK / OpenGL / D3D12 行写「首次启动游戏或桌面时检测」，不把 auto 的无报告兜底（Sarek）当结论；「Re-detect」只清该驱动的 verdict，下次启动重探。曾做过设置页隐藏探测（独立进程跑无窗口会话 + 跨进程会话锁），但运行时只在启动时安装，首装时设置页本来就探不了；剩下的场景（只开过桌面、换了没启动过的驱动）改由启动覆盖后整套删掉：少一个进程、一把锁、一个状态文件，也没了旧进程把上一次的驱动带进游戏的风险。代价：每个驱动首次启动多 ~0.5–0.8 s（Y700 dx9：点启动到首帧 4.1–4.4 s，cache hit 3.0–3.6 s；探针本身 ~3.4 s 但与 Wine 启动重叠），期间会话屏中间显示「Checking GPU capabilities (first start on this driver)…」，宿主 UI 不卡。探针以 Windows GUI 子系统构建（`build.sh` 的 `-Wl,--subsystem,windows`，入口仍是 `main`）：控制台程序从 shell 桌面起会被 Wine 配一个 conhost 窗口，在任务栏里挂到游戏 / winefile 退出。
  - **同一份 guest 报告喂全部消费者**：D3D12 用 `Vkd3dRequirements`（按 pin `VKD3D-3.0.1-3b10bd7a7` 源码逐项核对：Vulkan API ≥1.3、`samplerMirrorClampToEdge`、`VK_EXT_robustness2` 三件套 `robustBufferAccess2/robustImageAccess2/nullDescriptor`、`shaderDrawParameters`、`VK_KHR_push_descriptor`、图形+计算队列；Sarek 的 dxgi 拒 D3D12 另列）；zink blocker 从 `GraphicsDriverCapabilities.zinkBlockers` 的宿主扩展枚举改成读 guest 报告的 `extensions`（`ZinkRequirements.missingExtensions`）；BCn「auto」状态显示 guest `core.textureCompressionBC`。运行时 `applyGalliumDriver` 的 zink 门槛同步改吃 guest 报告，宿主 Vulkan 探测只剩 preparer 里的 `WRAPPER_VK_VERSION` clamp。
  - **弃掉的方案（教训）**：先在 `getOrCreate` 里用 `WineHeadlessRunner` 起第二个 Wine 实例做 headless 探测——冷 prefix 全量 boot（wineboot/services/explorer）、env 手工重建永远追不齐（`WINE_KERNEL_STACK_SIZE`、`libandroid-sysvshm.so` preload 等），guest stdout 到不了 unix fd，且 `install_bpf` 失败后 wrapper ICD 的 `vkCreateInstance` 在 syscall stack 上越界崩（seh `Syscall stack overrun`，winevulkan/loader.c assert）。会话内探测天然绕开全部问题。
  - 顺带：wineandroid 会话里宿主已不用 Vulkan 渲染（GDI 走 CPU 拷贝，3D 只把 AHB `queueBuffer` 给 SurfaceFlinger），宿主 Vulkan 只剩上述探测；X11 时代的 `VulkanRenderer` / `vk_renderer.c` 已无处创建（`XServerSurfaceView` 未被实例化），`resolveHostDriver` 注释里的 “host compositor” 是旧说法。
- **Adreno 8xx + Qualcomm 驱动的 D3D8/9 FF -13：已实现方案 (b)（自动拆分）**。`dxvk_flavor=auto` 解析为 3.0.2 且生效驱动为 Qualcomm 官方 wrapper / System 时，d3d8/d3d9 取拆分构建（`DxvkFlavorIds.D3D89_FLAVOR`，当前 `SAREK` 即 `1.11.0-async-sarek-0`），dxgi/d3d10core/d3d11 仍 3.0.2；Turnip / Leegao / 显式 `dxvk` / `sarek` 一律不拆（拆分只在生效驱动为 `wrapper` / `System` 时启用）。机制：wrapper token 第四段 `d3d89-<profile>`（`DxWrapperSelection`）；容器解析时主 + 拆分两个 DXVK 组件都装（`contents/DXVK/` 并存，`pruneWcpSiblings` 本就保留同类型全部 manifest pin）；preparer 先按主 profile 全量链接、再把 `system32` / `syswow64` 的 `d3d8.dll` / `d3d9.dll` 重指拆分 profile（`SharedDllLinker`）；`dxvkLinksNeedRefresh` 校验两套链接归属，开会话幂等保持。设置页显示有效结果「Automatic chose DXVK 3.0.2 + DXVK-Sarek 1.11 for Direct3D 8/9」。换拆分构建：改 `D3D89_FLAVOR` + 对应组件发 WCP pin 即可。d3d8 与 d3d9 必须同一套（3.0.2 的 d3d8 经私有 bridge 找 d3d9），dxgi/d3d11/d3d10core 必须同一套。未覆盖：真实游戏、同进程同时用 D3D9 与 D3D11、6T。
  - **2.x 拆分构建评估（Y700 实测，dev-pin 注入 `dxvk_sarek`）**：2.4 / 2.6.2 / 2.7.1 六项全过（dx8/dx9 64+32、32-bit dx7、32-bit ddraw2d），~144 fps、`-13` 0，截图见同一 run 目录 `evidence/`。**推荐 2.6.2**：功能全、d3d8 在、Vulkan 1.3 + `VK_EXT_robustness2` 要求与 2.4 同级；2.7.1 硬要 `VK_KHR_maintenance5` + `VK_KHR_pipeline_library`（feature `true`），旧 Qualcomm 驱动没有的话 device 直接建不成，且 2.7 的 descriptor-buffer 路径在 Qualcomm 上是新代码；Sarek 1.11 要求最低（VK 1.1 + swapchain，无 robustness2）仍是保底。换 pin 步骤（未执行）：构建 `Dxvk-2.6.2` WCP（x64/x32 dll 布局同 Sarek WCP）→ 走 imagefs/发布渠道出资产 → manifest 给拆分组件加/改 pin → `D3D89_FLAVOR` 指过去。各版本必需特性/扩展清单见 `~/.cache/devin-runs/20261018T123818Z-amphora-dxvk-split-b/evidence/dxvk-2x-vulkan-requirements.md`（本机，未入库）。
- **之后**：Phase 3 OpenGL，先实验 A（PE Mesa zink `opengl32.dll`，走 Box64 有 CPU 模拟代价），A 不行再做 B（原生 Mesa surfaceless + 回读，需要出画那一段）。Phase 4：6T 64-bit dx11 偶发卡首帧、32-bit padding 修复上游化、Mali 上 VirGL。Phase 5：ARM64EC 评估。
- **零散待办**：
  - Vulkan 窗口切后台后会话结束（[`09`](09-AIO-GRAPHICS-TEST.md) §4，两条 loader 路径都这样）。
  - 层现在如实报 FIFO / MAILBOX，win32u 里“永远广告 IMMEDIATE、宿主没有就改 FIFO”的补丁（proton-wine `dlls/win32u/vulkan.c` `win32u_vkGetPhysicalDeviceSurfacePresentModesKHR` / `win32u_vkCreateSwapchainKHR`）仍在起作用：没有 IMMEDIATE 就退出的 app（AIO `--cube vk`）两条路径都靠它，要删得先决定层是否报 IMMEDIATE（AHB 交换链上它等同 MAILBOX）。
  - D3D12 选 Sarek 时必然失败（Sarek 的 dxgi 不认 D3D12 设备）。
  - content_manifest `f9cfa31` 已把 AIO 改 pin 到 amphora fork `cnb.cool/atowerlight/aio-graphics-test`（fork `b8acc49` = 上游 2.1.0 `582454d` + D3D8 窗口模式 PresentationInterval 修复），dx8 已在 Y700 复测（`5c8f5e1`）：fork 修复生效——D3D8 设备建成、bench 144.05 fps；方块仍不画是 Qualcomm FF -13（上条等用户决定项），非 AIO 问题。
  - ReDroid CI（`redroid-arm64.yml`）已停自动触发，只留手动 `workflow_dispatch`：main 上一直红，`redroid-ci.sh` 的测试清单在 `8f7a4aa` 去掉两个不存在的类、断言改 `OK (7 tests)`，是否变绿未验。修绿后恢复 `workflow_run` 触发。
  - `WineHeadlessRunner` 为 `HeadlessWineBootTest` 与 PrefixPack 生成器共用，Y700 均已验证（§3）。生成器与 `dd99971`（XServer + GPLC 完整 env）同机同 WCP 对比：两棵 `.wine` 树 472 项逐路径一致，仅 `system.reg` 差 10 行，全部在 `Wow6432Node\Interface` 下、为共享 IID 的最后写入者差异（`Picture`/`IPictureDisp`、`Font`/`IFontDisp` 名、PSOAInterface/PSFactoryBuffer 代理 CLSID、wshom/scrrun TypeLib 反向指针）——两边都是合法 Wine 注册、被引用的 CLSID/Typelib 在两树都在场，64 位 Interface 视图逐字节相同，判定为 X 环境下 32 位注册写序差异，非回归。复现注意：`dd99971` 的生成器在 instrument 进程里要先 `ProcessHelper.init`（HiltTestApplication 不走 `AmphoraApplication.onCreate`，Enforcing 设备上 box64 直接 exec 会被 `execute_no_trans` 拒），给 `setUp` 补一行即可；`HeadlessWineBootTest` 的 staged .wcp 文件名与 Entry 常量硬编码，换 pin 需同步。

## 3. 已验证能力（最近一次真机 PASS）

设备简称：Y700 = Lenovo Y700 `HA262AAH`（Adreno 830，Android 16），6T = OnePlus 6T `5b1736c7`（Adreno 630，Android 15）。

| 能力 | 代码版本 | 设备 | 关键判据与数字 | 细节文档 |
|---|---|---|---|---|
| NDK 符号收敛（wrappedandroid），GDI 桌面出画 | amphora `37a8729`（6T 构建 `175bcbd`，仅文档差异）；Box64 `0.4.5-0db8df775-p07e67ded`；Proton `11.0-0d7ffcb97`（proton-wine `0d7ffcb970e`） | Y700 + 6T | `load_android_libs … host=1`，无 `can't find` / abort / host IPC；GDI 桌面 + File Manager + Start 出画（6T 非黑约 82%）；无 FATAL，90 s 会话仍在 | [`01`](01-ARCHITECTURE.md) |
| 音频 Pulse / ALSA 双后端 | 同 09-26 pin 组 | 6T | 30 s 440 Hz：Pulse 有 `protocol-native` sink-input + AAudioSink RUNNING；ALSA `AS0` AudioTrack 送 1,440,480 帧（=30 s×48 kHz）；wine `err:` 音频 0 | [`04`](04-WINEANDROID-DISPLAY.md) |
| 性能 HUD（CPU/RAM/温度等） | `2f3e8f0` | 6T | HUD 显示 CPU / RAM / 宿主内存 / 电池温度，不吞触摸 | [`04`](04-WINEANDROID-DISPLAY.md) |
| 返回键退出确认 | `9b7480f` | 6T | box64 进程 10 → 取消 10 → 确认 0；`:session` 进程退出干净 | [`04`](04-WINEANDROID-DISPLAY.md) §6 |
| AHB 逐帧送显（dequeue/queueBuffer 模型） | `2a70f95` | Y700 + 6T | `presents=175`，游戏层 SF `totalFrames=173`、`dropped=0`；限 30 / 60 / 关 → 29.4–30.0 / 56.7–60.0 fps | [`05`](05-AHB-IMPORT-PRESENT.md) §2.3 |
| 宿主刷新率对齐 + 峰值上限 | `4c4d514` + `ce4b739` | Y700 + 6T | `peak_refresh_rate=144` → 关限帧选 modeId 3（144 Hz）143 fps；限 30/60 → modeId 6/5 | [`05`](05-AHB-IMPORT-PRESENT.md) §5 |
| Vulkan 视图对齐客户区 | `8b3d17b` | Y700 + 6T | 屏上品红框实测与计算坐标一致（±2 px），标题栏露出；`totalFrames=172–173 / presents=175` | [`04`](04-WINEANDROID-DISPLAY.md) §2 |
| render-done fence（替代 QueueWaitIdle） | `157300d` + proton-wine `b1c230b9904`；发布 pin `Proton-11.0-b1c230b99` | Y700 + 6T | `presents=175 fenced=175`，fd 87–103 复用无泄漏；旧 Proton 配新 APK `fenced=0` 正常回退 | [`05`](05-AHB-IMPORT-PRESENT.md) §2.3 |
| manifest 过期刷新重试 | `provisionWithCurrentPins`（同 `157300d` 提交） | Y700 | 旧 manifest → 404×3 → `retrying with refreshed pins` → 会话正常；单测 3 条 | [`03`](03-ASSET-MANIFEST.md) |
| acquire fence 导入（release fence → guest） | `aacf7da` + proton-wine `11064b98807`；发布 pin `Proton-11.0-11064b988` | Y700 + 6T | `acquires=176 imported=176`，`fenced=175`，`flush acquire signal` 0 行；旧 Proton `imported=0` 正常回退 | [`05`](05-AHB-IMPORT-PRESENT.md) §2.3 |
| AIO 全矩阵（32-bit DXVK / alpha / 10-bit / D3D12 instance） | `53ddda5` + proton-wine `6512f18bd02`；发布 pin `Proton-11.0-6512f18bd` | Y700 + 6T | 32-bit DXVK 由 `VK_ERROR_FEATURE_NOT_PRESENT` 变为 142–144 fps；vkcube 背景 (51,51,51) = 清屏色；矩阵见 09 | [`09`](09-AIO-GRAPHICS-TEST.md) §3 |
| AIO 2.1.0 换源 + 旧资产清理 | `b89d083` | Y700 | `Removed unpinned runtime asset` 4 行 + `Staged` 两条；vk/dx11 64 位 143.96–144.01 fps | [`09`](09-AIO-GRAPHICS-TEST.md) §1 |
| HUD FPS / P95 / 1% low | 同 09-28 提交（`nativeRecentPresentTimes`） | Y700 | HUD `144 FPS 6.9 ms` 与 AIO bench 143.96 一致；`P95 9.4 ms`（CSV 9.3） | [`04`](04-WINEANDROID-DISPLAY.md) |
| SurfaceControl 显式层序 + Vulkan 窗口边框 | `10d5079` + proton-wine `2fd246cfc`；发布 pin `Proton-11.0-2fd246cfc`（当前 pin） | Y700 + 6T | bench 弹窗在 3D 之上且可点；窗口有标题栏；灭屏亮屏层重挂恢复；发布组合复验 vk/dx11/ddraw2d × 32/64 PASS | [`04`](04-WINEANDROID-DISPLAY.md) §2 |
| `VK_LAYER_AMPHORA_wsi` 驱动链（wrapper / Turnip / System） | 同 09-28（只改 APK） | Y700 + 6T | wrapper 下 DXVK 见 `Wrapper(Adreno 830) driver 0.800.72`；Y700 dx12 新可跑；6T Turnip 缺 `storageBuffer16BitAccess` 仅 vk | [`05`](05-AHB-IMPORT-PRESENT.md) §2.4 |
| Headless Wine boot（linker64 app-data exec，无显示） | `5c8f5e1`（exec 路由 + staged-.wcp pin 同步）；Proton `11.0-2fd246cfc`、Box64 `0.4.5-0db8df775` | Y700 | `am instrument` `OK (2 tests)`；`Routing app-private executable through linker64` → `exit=0`（`wine --version` 与 `wineboot --init`，后者产出有效 user.reg） | [`01`](01-ARCHITECTURE.md) |
| PrefixPack 生成器走 `WineHeadlessRunner` | 同左；对照基线 `dd99971`（XServer + GPLC env） | Y700 | 同 WCP 同机两轮生成确定性 txz（sha256 `da1ed00c…`，各 2 次全同）；与基线 `diff` 仅 `system.reg` 10 行 Wow64 COM 注册序差异（§2 有逐项解释），无回归 | 本文件 §2 |
| Qualcomm 驱动 D3D8/9 自动拆分 DXVK（auto → d3d8/9 取拆分构建，dxgi/d3d10/11 留 3.0.2） | `8cb146f` + 本提交；Proton `11.0-2fd246cfc`、wrapper 驱动 | Y700 | dx8/dx9 64+32、32-bit dx7、ddraw2d 全出画 143.6–144.5 fps、wine log `-13` 0；dx10/dx11 64+32、dx12、vk 仍 3.0.2/vkd3d 不受影响；多次开会话拆分链接保持；dev-pin 换 2.4/2.6.2/2.7.1 六项同样全过 | 本文件 §2 |
| DXVK `auto` 走 guest 内探测（会话内 vkprobe → 必需集判定 → 重链 → `.go` 放行） | 本提交（`wip/cleanup`）；Proton `11.0-2fd246cfc`、Box64 `0.4.5-0db8df775` | Y700 | `pm clear` 后 wrapper 首启：命令变 `"C:\amphora-vkprobe.exe" --then <game>`，2.4 s 出 JSON（`Wrapper(Adreno 830) Vulkan 1.3`）→ verdict `dxvk-3.0.2;d3d89-sarek` → relink → 出画 144 fps；二次启动 cache hit 走普通命令；切 Turnip（Vulkan 1.4，不拆）与 System（`Adreno 830`，拆）各重探测、均与 DXVK wine log `Found device` 一致；dx9 日志 `DXVK-Sarek: v1.11.0-async` 证实拆分链路；握手 2.2–3.8 s。6T 未在线 | 本文件 §2 |
| 能力探测多槽缓存 + 只在 Wine 启动时探测 + D3D12/Zink/BCn 共用 guest 报告 | `cf3906a` + 本提交（`wip/cleanup`）；Proton `11.0-2fd246cfc`、Box64 `0.4.5-0db8df775` | Y700 | `pm clear` 后开设置页：不起 Wine，DXVK / OpenGL / D3D12 行都是「首次启动游戏或桌面时检测」；首装直接开桌面（Explorer）：装完运行时后命令为 `"C:\amphora-vkprobe.exe" --then "winefile.exe"`，探针 3.4 s（`Wrapper(Adreno 830)` Vulkan 1.3）→ relink 到 `dxvk-3.0.2…;d3d89-1.11.0-async-sarek-0` → Wine File Manager 出画；再开设置页显示 Zink、D3D12 supported、「DXVK 3.0.2 + Sarek for D3D8/9」；切 Turnip 设置页回到「首次启动时检测」且不起 Wine，AIO dx9 首启探针 3.4 s（Vulkan 1.4）→ relink 到 3.0.2 不拆分 → 144.71 fps，再启 cache hit 走普通命令、不 relink，143.99 fps；Turnip 上「Re-detect」+ 显式 DXVK 3.0.2：dx11 照样探测 3.2 s、结论与链接相同不 relink，144.26 fps；dx12 144.03、dx8 143.99 fps，均出画、无 FATAL。6T 未在线 | 本文件 §2 |
| 会话启动状态条 + 探针 GUI 子系统 | 本提交（`wip/cleanup`） | Y700 | 会话屏原先一直留着调试文字（`wineandroid: guest pid=… wine start …`），盖在 Windows 任务栏上；现为居中状态条「Preparing Windows… → Starting Windows… / Checking GPU capabilities (first start on this driver)…」，探测结束且 guest 首个窗口 surface 注册后隐藏（失败时显示「Could not start Windows: …」并保留）。探针改 GUI 子系统后桌面任务栏只剩 Start + Wine File Manager / AIO 窗口；清 verdict 后 AIO dx9 首启探针 3.4 s，点启动到首帧 4.12 s，144.38 fps；dx12 cache hit 2.98 s 到首帧，143.99 fps | 本文件 §2 |
| GDI 窗改尺寸后不再闪空 | 本提交（`wip/cleanup`） | Y700 | AIO dx11 的 FPS 浮条（顶层 GDI 窗，每 500 ms 改文字和宽度）原来每隔一次更新消失半秒：宿主日志里该窗的 buffer 0 改尺寸后以 `new=0` 复用，Wine 画进旧 AHB。修后 buffer 0 改尺寸后 `new=1` 重新下发，5 s 录屏每 12 帧取样 60 张全部有浮条；回归 vk 143.59、dx9 144.57、dx12 143.97 fps，ddraw2d（纯 GDI）144.00 fps 出画 | [`04`](04-WINEANDROID-DISPLAY.md) §3.1 |
| HUD 展开项 GUEST CPU / RSS / 进程数 | 本提交（进程树改按 `/proc/<pid>/stat` 父 PID 建，不再读 `task/*/children`：Y700 内核无 `CONFIG_PROC_CHILDREN`，原先只采到空闲的 launcher，恒为 0%） | Y700 | AIO dx11 64 运行中展开 HUD：`GUEST 4%`、`10 guest · 69 threads`、`GUEST RSS 926 MB`（8 核平均，修前 `GUEST 0%`） | — |
| Advanced 运行时设置生效 + 每次启动的多余步骤 | 本提交（`wip/cleanup`）；Proton `11.0-2fd246cfc` | Y700 | Advanced「Wine log」=Errors → 会话 env `WINEDEBUG=+err`（`5909b50` 起 `AdvancedRuntimePreferences.applyEnvOverrides` 无人调用，Advanced 页 Wine log / DXVK HUD / vkd3d / 自定义 env 等全是空操作，现由 `WineAndroidSessionBootstrap` 叠在 preparer env 与 LaunchSpec env 之间）；默认 `WINEDEBUG=-all`（原强制 `+err,+android`），`trace:android` 0 行；删掉每次启动对 `libXau.so` 跑 `ldd` 的 CurlDeps 检查；keeper 改为 `<cmd> & wait $!`——原脚本找 `$WINEPREFIX/server-*`（实际在 `.wineserver/`）永远找不到，20 s 后无条件退出，宿主把会话当成已结束；修后 AIO dx11 64 `pid->end` 13 s（= bench 8 s + autoclose + 退出），144 fps；dx8/dx9/dx12 64 ~144 fps | — |

> 失效行已剔除：2026-09-26 的「3D 冒烟」当时只按回读判 PASS（SF 层只有 4 帧），判据无效，已由 `2a70f95` 的逐帧送显取代。

## 4. 设备与测试分层

- **测试分层（用户认可）**：UI 改动 → 一台 vk 64；Present / 窗口改动 → 两台 vk / dx11 / ddraw2d 64 + 相关场景；驱动链改动 → 受影响后端 32 + 64 两台；全矩阵只在发 Proton / Box64 / 驱动 pin 时跑。脚本 `scripts/aio-smoke/`，换驱动 / flavor 见 [`09`](09-AIO-GRAPHICS-TEST.md) §1。
- **设备现状**：两台装的都是与 `4229aaa` 同源的 debug APK，dev pin 已清，prefs 已还原（Y700 只有 `display_resolution=R1024x768`，6T 只有 `advanced_audio_driver=pulseaudio`）；6T 首次选 Turnip 时下载安装了驱动包，留在 `files/contents/adrenotools/`。
- **6T 是 SELinux Permissive**（非官方 Android 15 ROM，bootloader 解锁，开机即如此，不是我们改的；用户看到过一次“SELinux 已关闭”警告，来源未查到），它上面的 PASS 不代表 Enforcing 设备；Y700 的 enforce 状态未确认。
- 已停项：第二台真机 / 分屏 / hostScale 双机。

## 5. 发布与上线顺序

- **APK 发布**：`main` 推送由 CI（`.github/workflows/ci.yml`）跑 `scripts/ci/publish-app-update.sh`，把 debug APK 发为 GitHub Release 并把 `content_manifest` 的 `app_update.json` 指到新版本。
- **组件发布**：proton-wine / imagefs 改动走 imagefs PR → `build-proton-wine` 出 WCP Release → bump `content_manifest.json` pin。发版会立即删掉上一版 release 资产。
- **上线顺序**：协议类改动（wsi-sc op 7 / op 8、宿主 `CMD_QUEUE_FENCE` / `CMD_DEQUEUE_FENCE`）**APK 先于 Proton**——新 APK 遇到旧 Proton 走 op 4 / op 5 回退，已双向回归验证。
- **过期 manifest**：设备磁盘缓存的 manifest 可能 pin 已删除的旧资产（下载 404）；`WineAndroidSessionBootstrap` 经 `ContentCatalog.provisionWithCurrentPins` 在 IOException 时刷新 manifest、pin 变了才重试一次。

## 6. 架构铁律

- 每个 HWND 一个 `SurfaceControl` 层，挂在同一个宿主 `SurfaceView`（layerHost）下；层序由宿主 `syncLayers()` 按 View 树先序显式设置。
- GDI 走 CPU buffer（`NATIVE_WINDOW_API_CPU(2)` + `PF_RGBA_8888`，宿主 R/B swizzle），不走 CreateSwapchain / 私有 socket。
- 游戏 Vulkan 走 AHB 导入 swapchain（`libamphora_wsi` + `VK_LAYER_AMPHORA_wsi`），无旧 X11 renderer 回退。
- 铺满靠宿主等比缩放 + Wine DPI 96，不把 Android densityDpi 喂给 Wine。

## 7. 验证命令

```bash
./gradlew spotlessCheck :app:testDebugUnitTest :app:lintDebug   # 门禁（pre-push / CI 同款）
./gradlew jvmTest                                             # 全仓 JVM 单测
./gradlew :app:assembleDebug                                  # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleDebugAndroidTest                       # androidTest 编译
# 真机冒烟（屏幕亮着、已解锁）：
adb -s HA262AAH shell svc power stayon usb && adb -s HA262AAH shell input keyevent KEYCODE_WAKEUP && adb -s HA262AAH shell wm dismiss-keyguard
adb -s HA262AAH install -r app/build/outputs/apk/debug/app-debug.apk
scripts/dxvk-smoke/present-check.sh HA262AAH                  # AHB present 判据，见 docs/05 §5
scripts/aio-smoke/aio-run.sh HA262AAH vk 8 64                 # AIO 后端冒烟，见 docs/09 §1
```

## 8. 旁注

- imagefs main 有他人的 CI 提交（`bb7f5b3` 等）；content_manifest `24df3a7` 曾把同一版本名 `Proton-11.0-2fd246cfc` 重新 pin 成 sha256 `1ab7366c…`（原 `a93cd764…`）——版本名没变，已装 profile 不会重下，是否预期未确认。
- dev 主机 `/data/co/github/imagefs` 的 checkout 落后 main，下次构建前先 pull。
