# 12 · 从零重写：已定决策与下一步

> 状态：设计基线。2026-11-08：第 3 轮的 P1–P3 已拍板（D12–D14），设置覆盖层级已定（D15）；D1 改为接口按需加，D5 改为不留 Box64 接口，新增 D11（不再建设旧代码）。设置清单见 [`13-SETTINGS-CATALOG.md`](13-SETTINGS-CATALOG.md)。
> 整理自 dsh 会话 `session-5cc99402`（2026-11-07 23:04–23:54）及其引用的 Cursor 对话 `87e95aeb`，以及之后的 Claude Code 会话；对话记录只留在本地，不入库。
> 本文取代本仓库旧 `README.md` 里「Steam 客户端 + proot + pressure-vessel」的立项方案（那是 `amphora_old` 的 `docs/research/11-STEAM-ENGINE-PLAN.md` 路线）。

## 1. 已定（用户拍板）

| #   | 事项           | 决定                                                                                                                                                                                   | 出处        |
| --- | -------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------- |
| D1  | 产品形态       | 像 prettier：一套**固定的最佳组合**，开箱即用。代码按模块分清，但**接口等真有第二种实现时再加**（2026-11-08 改，原为"每一层都留接口"；和 D5 同一个原则：不做的事不留抽象） | dsh 第 2 轮；2026-11-08 |
| D2  | 目标           | 以学习为主；同时按 **SDK** 设计，我们自己的 App 是 SDK 的一个标准使用者（标准窗口）                                                                                                    | dsh 第 2 轮 |
| D3  | Steam          | 现在**不接** Steam；以后单独实现 Steam 相关接口（只下载需要的部分，不做完整 Steam 客户端），到时再加需要的扩展点（§2.2）                                                                               | dsh 第 2 轮 |
| D4  | 设备           | **只支持 Adreno 7xx/8xx**（6T / A630 不再支持；Mali、Xclipse 不做）。开发期只保证 Y700（8xx），7xx 等有设备再验证                                                                     | dsh 第 2 轮 |
| D5  | Box64          | **彻底删掉，也不留接口**（2026-11-08 改：原来要留的 `Runtime` 扩展点去掉）。x86 翻译只做 FEX 这一条路；Box64 的 32 位 `wowbox64.dll` 也不做（§3.5） | dsh 第 2 轮；2026-11-08 |
| D6  | 图形与兼容设置 | App 里**要有，而且要详细**。每个设置都要有分组和说明，写明默认值以及选它的理由，让用户敢改、能自定义；默认配置要保证开箱体验好。（这一条否决了第 2 轮"界面上没有图形/兼容设置"的提议） | dsh 第 3 轮 |
| D7  | 仓库           | 用新仓库 `amphora-dev/amphora`，本地目录 `~/co/github/amphora-steam`。旧仓库已改名为 `amphora-dev/amphora_old`，只作参考；配套仓库 proton-wine、content_manifest、buildstream-sdk 继续用。构建从 imagefs 分出到新仓库 `amphora-engine`，imagefs 冻结（2026-11-08 改：原为继续用 imagefs） | dsh 第 3 轮；2026-11-08 |
| D8  | SDK 形式       | 先只出 **AAR**，暂不做 C ABI；内部协议头保持带版本号，方便以后加                                                                                                                       | dsh 第 3 轮 |
| D9  | 测试设备       | 暂时就用现有设备：Y700 `HA262AAH`（A830）                                                                                                                                              | dsh 第 3 轮 |
| D10 | 设计文档       | 写在新仓库（即本文）。旧仓库的文档改动只留在本地，不推送（2026-11-08 改）                                                                                                              | dsh 第 3 轮；2026-11-08 |
| D11 | 旧代码         | **不再建设旧代码。** 配套的基础建设项目（proton-wine、amphora-engine、content_manifest、Turnip 构建）不用考虑对旧代码的兼容，全按新设计改；验证也在新实现上做，旧仓库只看记录、不再跑。旧 App 在线读 content_manifest main 上的清单，改了就会坏，这一点接受，不打快照 | 2026-11-08 |
| D12 | Wine 版本      | 跟 **Proton Experimental**，锁具体 tag，现在是 `experimental-11.0-20261027b`（原 P1，§3.2）                                                                                            | 2026-11-08 |
| D13 | 前缀           | 每个游戏一个**薄前缀**：内容寻址只读仓库 + 符号链接 + wineserver 写时复制；M1 的写时复制测试不通过就退回 Proton 式整份复制（原 P2，§3.3） | 2026-11-08 |
| D14 | 出画           | 保留每 HWND 一层；交换链**重写**进 `wineandroid.drv`，宿主用 ASurfaceTransaction 送显，不移植旧的 BufferQueue 代理（原 P3，§3.1） | 2026-11-08 |
| D15 | 设置覆盖层级   | 配方默认 < 用户全局 < 我们维护的按游戏修正 < 用户按游戏；修正盖过全局时界面要提示原因（13 §2）                                                                                     | 2026-11-08 |

## 2. 第 2 轮提出的方案（用户没有反对，作为当前基线）

### 2.1 配方（Recipe）

- 只有一套带版本号的配方，比如 `amphora-2026.11`；版本一变就重建前缀，符合"不做兼容"的原则（用户数据不在前缀里，见 §3.3）。
- 按游戏的差异写成数据（参照 Proton 按 appid 打修复的做法）。
- 跟着 Proton tag 走，Android 特有的部分单独列一张差异清单：Bionic 适配补丁、`wineandroid.drv`（含交换链）、Turnip 的 KGSL 后端、前缀写时复制（§3.3）。

| 层          | 选择                                                                                               |
| ----------- | -------------------------------------------------------------------------------------------------- |
| Wine        | Proton tag（建议 Experimental，见 §3.2），原生 aarch64 + arm64ec，用 Bionic 构建；继续用 `wineandroid.drv` |
| x86 翻译    | FEX 作为 Wine 里的 DLL：`libarm64ecfex`（64 位）、`libwow64fex`（32 位）                           |
| D3D8–11     | DXVK，版本按 tag 的 pin；打开 GPL、不用 async；不带 gplasync，也不带 Sarek                          |
| D3D12       | vkd3d-proton，版本按 tag 的 pin                                                                    |
| Vulkan 驱动 | Turnip（固定 Mesa 版本，KGSL 后端）；改按普通 Vulkan ICD 构建，由 loader 直接加载，不再经 wrapper / adrenotools（M3 确认） |
| OpenGL      | Zink 跑在 Turnip 上（沿用已经接上的 Mesa WGL）                                                     |
| 出画        | 每个 HWND 一个 SurfaceControl；交换链收进 `wineandroid.drv`，宿主用 ASurfaceTransaction 送显（§3.1） |
| 前缀        | 每个游戏一个"薄前缀"：内容寻址只读仓库 + 符号链接 + wineserver 写时复制（§3.3）                    |
| 媒体        | Wine Mono、Gecko、GStreamer 配齐                                                                   |
| 缓存        | 每个游戏单独一个目录，Mesa、vkd3d、FEX 代码缓存都放在里面；游戏身份 = exe 哈希 + 可选 Steam AppID  |

### 2.2 SDK 边界

```
amphora-sdk（AAR + native）
├── Engine        设备检查、配方、内容安装与校验
├── Container     前缀生命周期（PrefixStore：仓库、模板、建/重建/删）
├── Session       启动 / 进程管理 / 退出
├── GameSurface   标准窗口组件
├── Input / Audio
└── Settings      设置目录（13）、覆盖层级、生效配置
app = 只依赖 SDK 公开 API 的薄壳（用 Gradle 模块可见性强制）
```

现在**不设扩展点（SPI）**（D1、D5）：每样东西只有一种实现，接口等第二种实现真要做时再定，免得现在猜错。以后可能要加的，记在这里备忘：

| 以后可能的扩展点    | 现在                | 什么时候再定                                   |
| ------------------- | ------------------- | ---------------------------------------------- |
| 游戏身份            | 本地 exe 哈希 + 可选的 Steam AppID（13 §2） | 接 Steam 时（M6）                              |
| 着色器缓存来源      | 只用本地缓存        | 接 Steam 共享缓存或做 CI 预编译时              |
| 内容来源            | 我们 pin 的内容清单 | 接 Steam depot（部分下载）时                   |
| 游戏库              | 用户手动添加 exe    | 接 Steam 游戏库时                              |

GPU 驱动也不是扩展点：高通系统驱动只是开发者模式里的一个对比开关（13 §3.8），用来在同一个驱动上对比新旧实现，以及判断画面问题是不是 Turnip 的。新架构里 Wine 的 unix 侧是原生 Bionic，推断可以直接加载系统的 `libvulkan.so`，开关只是"加载哪个库"（M3 确认）。

### 2.3 里程碑顺序

1. 风险最大的先做：Proton tag（§3.2）用 Bionic 构建成 aarch64 + arm64ec，带上 `wineandroid.drv`，在 Y700 上跑通记事本和 GDI 窗口。
2. 把 FEX 接进 Wine：先 64 位 hello，再 32 位（WoW64），再打开 FEX 磁盘缓存。
3. DXVK、vkd3d、Turnip、交换链（§3.1）：AIO 图形矩阵和旧仓库记录的结果一致。性能分两步比，只拿记录对比，不再跑旧 App（D11）：
   - 新旧实现比：旧仓库记录的 Y700 基线（vk 832 / dx11 926 / dx12 1140 / dx9-32 431 fps，不锁帧）是在**高通系统驱动**上测的，所以先用开发者开关切到系统驱动，在同一个驱动上比，变慢要能解释原因。
   - 驱动比：同一个新实现，Turnip 对比系统驱动。旧仓库只测过 Turnip 锁帧（144 fps 都能跑满），没有不锁帧的数据；这一步回答"只用 Turnip"（D4）值不值。
4. 定下 SDK 接口，App 改成薄壳。
5. 按游戏的缓存目录，加上 Fossilize 本地录制和回放。
6. 之后再做 Steam。

## 3. 第 3 轮结论（2026-11-08，已拍板）

dsh 第 3 轮在助手回复前被中断，之后的 Claude Code 接续会话 `1f141d91` 回复时被拦截；本节是 2026-11-08 续做的结果。P1–P3 当天都按建议拍板，记为 D12–D14。证据来源：Y700 上 DroidDeck 装的三套 ARM64 Proton、ValveSoftware/Proton 的 tag、`proton` 启动脚本、本地 proton-wine 源码、两台设备上的实测。

| #   | 建议                                                                                         | 理由（一句话）                                                                                               |
| --- | -------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| P1  | Recipe 跟 **Proton Experimental**，锁定到具体 tag（现在是 `experimental-11.0-20261027b`）    | Valve 的 ARM64 支持在 Experimental 里推进：DXVK 3.1.1+、vkd3d-proton 约 3.1、FEX 2610、DLL 是 ARM64X        |
| P2  | 每个游戏一个**薄前缀**：内容寻址只读仓库 + 符号链接 + wineserver 写时复制                    | Android 应用只能用符号链接；照 Proton 原样做每个前缀约 400 MiB，薄前缀约 14 MiB                              |
| P3  | **保留每 HWND 一层**；交换链收进 `wineandroid.drv`，宿主改用 ASurfaceTransaction 送显         | 方向是对的，路径已经最短；换成 arm64ec 后实现可以大幅简化，还能补上 present wait 和帧节奏                    |

### 3.1 Q1 出画：每 HWND 一个 SurfaceControl + AHB 导入交换链

**结论：方向对，保留。** 这是 Android 上从游戏到屏幕最短的路：游戏直接画进 SurfaceFlinger 要显示的 buffer，中间没有我们自己的合成器；全屏游戏层还能走 HWC 硬件叠加，由显示控制器直接扫描输出，GPU 不再做一次合成。

| 方案                                         | 每帧拷贝 | 谁合成               | 代表                          |
| -------------------------------------------- | -------- | -------------------- | ----------------------------- |
| 每 HWND 一层 + AHB 导入（现在）              | 0        | SurfaceFlinger / HWC | Amphora                       |
| Wayland / gamescope 合成到一个 Android Surface | ≥ 1 次 blit（DroidDeck 自测：Adreno 750 上 Vulkan / D3D12 慢约 20%） | 自己的合成器 + SF | DroidDeck；GameNative 1.4 在 Adreno 上默认 |
| X server + GL / Vulkan 合成                  | ≥ 1      | 自己的合成器         | Winlator 一系                 |
| 单个 SurfaceView，宿主自己合成所有窗口       | 1        | 自己                 | —                             |

**改进空间在实现上**，按价值排序：

1. **交换链收进 `wineandroid.drv`（重写里最大的简化）。** 旧实现拆在三处：LD_PRELOAD 进 Box64 进程的 `libamphora_wsi.so`、win32u、wineandroid.drv，中间靠同进程的 `wsi-sc` socket（op 4–9）通信；另外还要隐式层 `VK_LAYER_AMPHORA_wsi` 和 `VK_LOADER_DISABLE_INST_EXT_FILTER=1`，给 Linux 版 Khronos loader 补 Android surface（旧仓库 `05` §2）。这些都是因为 Wine 的 unix 侧是 x86_64、跑在 Box64 里，要绕过 Box64 才调得到 arm64 的 Android 图形库。换成 arm64ec 后 Wine 的 unix 侧本身就是 aarch64 Bionic，AHB 导入、acquire / present、fence 都可以直接写在 wineandroid.drv / win32u 的 Vulkan 路径里。可以删掉：LD_PRELOAD、`wsi-sc` 协议、隐式层、loader 过滤开关。wrapper / adrenotools 也可以去掉：现在的 Turnip 打成 adrenotools 包，经 wrapper ICD 加载（imagefs `build-turnip`），而 Turnip 走 KGSL、不依赖厂商 HAL，改按普通 Vulkan ICD 构建后 loader 就能直接加载（M3 确认）。跨进程协议只剩"宿主 ⇄ Wine 进程"一条。
2. **宿主送显改用 ASurfaceTransaction，不再代理 BufferQueue。** 现在宿主替 Wine 进程跑 BufferQueue：宿主 dequeue，把 AHB 和 fence 发给 Wine，Wine 画完回 fence，宿主再 queue。所以要先把每个槽位 dequeue 一遍再 cancel，重建时 `API_DISCONNECT` / `API_CONNECT` 加 generation，受 `MIN_UNDEQUEUED` 约束，也出过"resize 后新 buffer 落在同一地址"那类 buffer id 的问题（旧仓库 `04` §3.1）。改成：Wine 侧自己分配交换链 AHB（数量、格式自己定），建链时把句柄一次性发给宿主（`AHardwareBuffer_sendHandleToUnixSocket`）；每帧只发"第几张 + acquire fence"，宿主在该 HWND 的 SurfaceControl 上 `ASurfaceTransaction_setBuffer`，release fence 从 `setOnComplete` 的统计里取回。好处：
   - BufferQueue 的槽位规则整个不要了；FIFO / MAILBOX / IMMEDIATE 的语义由我们自己的队列实现。
   - 拿得到 latch 时间和 present fence，可以真正实现 `VK_KHR_present_wait` / present id，而不是像现在这样把扩展从设备里摘掉（旧仓库 `05` §2.4）。DXVK 的帧节奏和低延迟逻辑要用它。
   - 帧节奏：`ASurfaceTransaction_setFrameTimeline` 配 AChoreographer 的 vsync id，固定帧率不用在交换链里 sleep（旧的 `ahb_sc_pace`）；按层 `setFrameRate` 让系统选刷新率档位。
   - 窗口移动 / 缩放和新内容可以放进同一个事务一起生效。现在几何走 Kotlin 的 `syncLayers()` 事务，内容走 BufferQueue，两者不同步。
   - GDI 窗口也能走同一条路（CPU 可映射的 AHB），宿主只剩一种送显方式。

   这不是绕开系统：Android 12 起 SurfaceView 的 BufferQueue 本来就由应用进程里的 BLASTBufferQueue 转成事务提交给 SurfaceFlinger，这里只是去掉中间一层。API 要求：ASurfaceTransaction 29，`setFrameRate` 30，`setFrameTimeline` 33；Y700 是 36。
3. **只给需要的窗口分配层。** 旧仓库 `04` §2 的写法是每个 HWND 一层（含子窗口）。应改成：顶层窗口和带 client surface（Vulkan / GL）的窗口各一层，普通子窗口画进顶层窗口的 surface（Wine 默认就这样）。层越少，越容易全部走 HWC。
4. **把 HWC 和带宽量出来，作为 M3 验收项。**
   - 全屏游戏层是 DEVICE 合成（HWC 叠加）还是 CLIENT 合成（GPU 合成）：`dumpsys SurfaceFlinger` 的 HWC 层表。
   - 交换链 AHB 有没有用上 UBWC 压缩，Turnip 是否按 gralloc 的 UBWC 布局渲染。UBWC 主要省内存带宽，影响功耗和发热。
   - 同一场景的电流（`/sys/class/power_supply/battery/current_now`），对比旧实现。

保留不动的：每 HWND 一层、宿主显式层序、按层几何做铺满缩放（HWC 缩放不花 GPU）、切后台 park、Frame rate 设置的语义。

### 3.2 Q2 DXVK 跟哪个版本：Steam 现在发的是什么

Y700 上 DroidDeck 存着 Steam 下载的两套 Valve ARM64 Proton 原包，外加 DroidDeck 自己的构建。从包里读出的版本：

| 构建                                      | DXVK                                               | vkd3d-proton                              | FEX                       | `aarch64-windows` DLL |
| ----------------------------------------- | -------------------------------------------------- | ----------------------------------------- | ------------------------- | --------------------- |
| Proton 11.0 ARM64 `proton-11.0-2e`        | `v2.7.1-498-ga6764047e`（DXVK 的 `proton-11` 分支） | `vkd3d-1.1-5153-g212991fc2`（v3.0 后 253 个提交，`proton-11` 分支） | 包里没有版本名（tag 的子模块是 `1cc4b93e7a71`） | ARM64EC（PE 头 AMD64 + CHPE） |
| Proton Experimental ARM64 `experimental-11.0-20261027b` | `v3.1.1-97-ga78a467ca`（master，2026-10-14）      | `vkd3d-1.1-5679-gcae2e003b`（v3.1 tag 前 7 个提交） | `FEX-2610-101-g1c4f04b`   | ARM64X（PE 头 ARM64 + CHPE） |
| DroidDeck-Proton 11-12.3                  | `v3.1.1-174-gf863776`（2026-11-01）                | v3.1 后 1 个提交（`8293130`）             | `FEX-2610-116-g02e24ca`   | ARM64X                |

- **tag 就是完整的 pin。** ValveSoftware/Proton 的 tag 里，`dxvk`、`vkd3d-proton`、`FEX` 子模块的提交和上面二进制里的版本逐一对上（Experimental：`a78a467cadb2` / `cae2e003bb82` / `1c4f04b71a4d`；稳定版：`a6764047e587` / `212991fc2c26` / `1cc4b93e7a71`）。Wine 本体：稳定版 `e60892cc2398`，Experimental `7b3ceaf8644b`（相对稳定版多 453 个、少 1 个提交）。所以"按 tag 生成所有组件 pin"可以直接做。
- 第 2 轮担心的"DXVK 3 要等 Valve 跟进"已经不成立：Experimental 发的就是 DXVK 3.1.1 之后的 master。稳定版还停在 DXVK 2.7.1 的 proton 分支。
- Steam 的共享着色器缓存按 DXVK **大版本**分桶（`fossilize_engine_filters.json`）；Fallout Shelter 实测下载到的管线来自 DXVK 3.x。以后接 `ShaderCacheSource` 时，跟 DXVK 3 才用得上。

**建议（P1）**：Recipe 跟 Experimental，锁定到具体 tag，换 tag 要过全矩阵（沿用旧仓库"发 pin 才跑全矩阵"的规则）。Experimental 大约每周一个 tag，回归风险靠锁 tag 和矩阵门禁兜住；真遇到 Experimental 回归，可以退到上一个 Experimental tag，而不是退回稳定版。

### 3.3 Q3 前缀：不给每个游戏一份完整副本

**Android 上能用什么**（Y700：Android 16 / API 36 / 内核 6.6；6T：Android 15 / API 35；`/data` 都是 f2fs）：

| 手段                 | 结果                                                                                                     |
| -------------------- | -------------------------------------------------------------------------------------------------------- |
| 符号链接             | 可以                                                                                                     |
| 硬链接               | 不行。两台都被 SELinux 拒绝：`avc: denied { link } … tcontext=u:object_r:app_data_file`（run-as 下实测） |
| reflink              | 不行。`/data` 的 f2fs 特性只有 encryption、extra_attr、projquota、quota_ino、verity、casefold、pin_file   |
| overlayfs、FUSE、bind mount | 不行，需要特权                                                                                    |
| proot 式路径翻译     | 不考虑：每个路径类系统调用多 15–60 µs（DroidDeck 自测），我们本来就是要避开 proot                       |

**Proton 自己怎么做**（`proton` 脚本，Experimental 和稳定版相同，前缀版本 `11.0-100`）：从 `default_pfx` 建前缀时，指向 Wine 内置 DLL 的条目建成指向 Proton 安装目录的绝对符号链接；`PROTON_DLL_COPY` 名单里的 DLL（DirectX / VC++ 运行库那一批、`comctl32`、`ntdll`、`vulkan-1` 等）改为复制，因为游戏的安装程序会覆盖它们；DXVK / vkd3d-proton 和 Steam 客户端 DLL 也是复制。

**实测这样做有多大**：Y700 上 Fallout Shelter 的前缀（Valve ARM64 Proton，经 DroidDeck）有 663 个实体文件共 398.8 MiB，另有 1336 个符号链接：

| 类别                         | 文件数 | 大小       |
| ---------------------------- | ------ | ---------- |
| `PROTON_DLL_COPY` 复制的运行库 | 253    | 204.7 MiB  |
| DXVK / vkd3d-proton 复制      | 16     | 60.9 MiB   |
| Steam / VR 客户端复制         | 14     | 57.8 MiB   |
| winsxs 复制                  | 38     | 21.4 MiB   |
| 注册表（含 `.reg.old`）       | 4      | 7.8 MiB    |
| 其他                         | 338    | 46.2 MiB   |

另一个前缀（1568590）是 416 MiB。ARM64X 的 DLL 很胖，单个 `d3dx9_*.dll` 约 1.9 MiB、`msvcp*.dll` 2.3–2.7 MiB。也就是说，每个前缀约 400 MiB 里，绝大部分是和安装目录逐字节相同的副本。

**建议（P2）：薄前缀。**

```
files/
├── store/ab/abcdef…            内容寻址，按 sha256 存；文件 0444、目录 0555；跨 Recipe 版本、跨游戏共享
├── templates/<recipe>.json     模板：路径 → sha256 / 目录 / 链接。CI 里用 wineboot 生成，pin 进 content_manifest
├── prefixes/<prefix-id>/
│   ├── system.reg user.reg userdef.reg        实体文件（wineserver 整份重写）
│   ├── drive_c/windows/system32/*.dll  →  store/…   其余文件全是符号链接
│   ├── drive_c/windows/system32/ntdll.dll     实体副本（Proton 也复制；对路径敏感的少数文件）
│   └── .amphora/                              模板版本、我们放置的文件清单
└── userdata/<game-id>/         drive_c/users/<用户> 链到这里；重建前缀不丢存档
```

- **写时复制放在 wineserver。** Wine 在服务端 `open_fd()`（`server/fd.c`）里用普通 `open()` 打开文件（跟随链接），并把 `fd->unix_name` 设成 `realpath()` 的结果（fd.c:2031）；之后的删除（fd.c:1117 `unlink( fd->unix_name )`）和改名（fd.c:2962）作用在**链接目标**上。`DeleteFileW` 虽然带了 `FILE_OPEN_REPARSE_POINT`，服务端并不处理这个标志。所以在 Proton 的布局里，游戏删除或改名一个链接过去的内置 DLL，改的是 Proton 安装目录；在我们的布局里就是共享仓库。规则：`open_fd()` 在打开前，如果请求了写数据、写属性、`DELETE`、`WRITE_DAC` / `WRITE_OWNER`，或者是截断类打开，并且该路径是指向仓库的符号链接，就先把它变成本前缀自己的实体文件（截断类打开直接换成空文件，不用拷贝），再照常打开。只改这一个函数，约 60 行。读和执行不触发，多个前缀共享同一份页缓存。
- **兜底**：仓库文件 0444、目录 0555，绕过写时复制的写入会直接失败，而不会悄悄改坏仓库。f2fs 支持 fs-verity，以后可以再加一层不可变和完整性校验。
- **代价**：f2fs 上每个符号链接约 4.6 KiB（Y700 实测：建 20000 个链接，占用 23044 个 4 KiB 块，删掉后空间回收）。一个前缀约 2200 个条目，约 10 MiB；加上约 4 MiB 注册表，每个前缀约 14 MiB，比 Proton 的布局小约 28 倍。建前缀只是建目录、建链接、复制注册表，不用每个游戏跑 wineboot。
- **Recipe 升级**：按"不做兼容"直接从新模板重建前缀；存档在 `userdata/`，不受影响。游戏自己写进注册表、自己装的运行库会丢，开发阶段可以接受；以后要保留，再做"注册表相对模板的差异重放"。
- **以后可加**：后台"整理存储"，把各前缀里游戏自己装的相同文件（比如同一版 VC++ 运行库）收进仓库、换成链接。有写时复制兜着，这一步是安全的。
- **SDK 落点**：`Container` 模块的 `PrefixStore`；前缀 ID 和游戏 ID 分开，允许多个游戏共用一个前缀（比如以后的启动器）。

没选的方案：所有游戏共用一个前缀（Winlator / GameNative 的做法，最省空间，但游戏之间的注册表和运行库会互相影响，也不能单独重置一个游戏）；照抄 Proton 的布局（每个前缀约 400 MiB）。

**M1 验证结果（2026-11-08，Y700，`tools/cowtest/`）**：

- 不做写时复制、仓库可写时，读代码的判断成立，而且更糟：`DeleteFile` 删掉了仓库文件（前缀里留下断链），`MoveFileEx` 把仓库文件搬进了前缀，`CREATE_ALWAYS`、截断、覆盖写、可写映射都直接改了仓库文件。
- 仓库只读（文件 0444、目录 0555）时仓库保住了，但这些操作全部 `ACCESS_DENIED`，游戏会坏。所以兜底不能代替写时复制。
- 加上 wineserver 写时复制（`WINE_COW_STORE`）后，8 种操作全部成功，仓库文件内容、权限、xattr 都不变，前缀里的结果符合 Windows 语义（覆盖写保留尾部、删除和改名作用在前缀自己的副本上），只读访问的文件仍是链接。
- 测出一个漏洞并已修：Wine 的 `SetFileAttributesW` 只带 `SYNCHRONIZE` 打开，服务端看不出要改文件，属性经链接改到了仓库文件的权限和 xattr；改成和 Windows 一样带 `FILE_WRITE_ATTRIBUTES`。另外也说明"0444 兜底"挡不住 chmod 和 xattr（同一个 uid 总能 chmod）。

### 3.4 Q4 图形与兼容设置清单

见 [`13-SETTINGS-CATALOG.md`](13-SETTINGS-CATALOG.md)。

### 3.5 Box64 能不能走 WoW64（2026-11-08 定：只做 FEX）

用户的问题（2026-11-08）：Wine 的 unix 侧变成原生 aarch64 后，Box64 是不是就直接不要了？只做 FEX，还是 Box64 能做 WoW64 的方案？

**Box64 现在有两个 PE 形式的子项目**，和 FEX 的两个 DLL 一一对应，插在同一个 arm64ec Wine 里，不需要 x86_64 Wine：

| 用途 | FEX | Box64 | Box64 的状态（ptitSeb/box64，2026-11-08） |
| --- | --- | --- | --- |
| 32 位 x86（WoW64） | `libwow64fex.dll` | `wowbox64.dll`（`-DWOW64=ON`） | v0.3.6（2025-06）起提供，最初给 Hangover 用；官方文档仍称"实验性"；最近的修复是 2026-10 的"4 GB 以上 JIT 代码的异常" |
| 64 位 x64（ARM64EC） | `libarm64ecfex.dll` | `box64ec`（`-DARM64EC=ON`） | 开发中：CMake 选项写着"no emulation yet"；2026-10-05 能跑解释器，2026-11-05 才开始编 dynarec |

**Wine 怎么选 32 位翻译 DLL**（Valve Wine Experimental `7b3ceaf8644b`，`dlls/wow64/syscall.c` 的 `get_cpu_dll_name`）：读注册表 `HKLM\Software\Microsoft\Wow64\x86` 的默认值，没有就在 ARM64 上用 `libwow64fex.dll`。换成 Box64 只要把 `wowbox64.dll` 放进 `system32`、改这一个注册表值，不用改 Wine，也不用 SDK 接口。WinNative 就是这么做的：arm64ec 容器里 64 位固定 FEX，32 位可选 FEX 或 wowbox64。

**结论**（用户选了"只做 FEX"）：

- D5 的"不留接口"不受影响。以前要留 `Runtime` 扩展点，是因为 Box64 要在 Wine 外面跑整个 x86_64 Wine；Box64 走 WoW64 DLL 后，它和 FEX 是同一个插槽，用不着运行时一级的抽象。
- 配方里只放 FEX。理由：64 位只有 FEX 能用，32 位也用 FEX 才是一套翻译器、一套代码缓存、一套设置（TSO / Multiblock）；这也和 Valve 在 Steam 上发的 ARM64 Proton 一致。
- wowbox64 也不做开发者对比项。真要对比时，实现就是"放一个 DLL + 写一个注册表值"，随时能加。
- box64ec 能用之前不考虑 64 位的 Box64。

## 4. 背景事实（来自 Cursor 对话 `87e95aeb` 和 dsh 第 1 轮）

- **市场**：重的有 DroidDeck（真 Steam 客户端 + proot + gamescope，约 3 GB，Adreno 730+）；中等的有 GameNative（Steam/Epic/GOG 游戏库、云存档、Bionic arm64ec Proton）；轻的有 Winlator、WinNative、GameHub、盖世。Valve 的 Steam Frame 走的就是 Proton + FEX。结论：做给玩家的 App 很难打出差异化；有价值的方向是学习，以及可复现、和 Proton 对齐的 SDK 或引擎。
- **"贴近 SteamOS"分三层**：直接用 Valve 的二进制（不做，需要 glibc 和合成器）；照 Proton 源码构建（做）；照搬机制（最有价值，做：按游戏缓存、Fossilize、FEX 磁盘缓存、`SteamGameId`、媒体解码器、Turnip GPL）。
- **旧仓库和 Proton 的差距**：Wine 基线落后约 4 个月；缺 Wine Mono/Gecko；GStreamer 只有 base；`SteamGameId=0` 写死，47 个文件里 68 处按游戏的修复没有生效；`DXVK_STATE_CACHE_PATH` 在 DXVK 3.x 已失效。
- **着色器缓存**：DXVK 在 Steam 上的分桶不看 GPU 厂商，vkd3d 按厂商分；gplasync 会让 Turnip 上的 GPL 用不上，所以 Turnip 上用 `DXVK_ASYNC=0`。
- **Fallout Shelter 实测**（Y700 上的 DroidDeck）：本地 200 条管线里有 68% 命中 Steam 共享缓存，但 Steam 下载的 2661 条里只有约 5% 这台机器用得上；DroidDeck 拦掉了 `d3ddriverquery64.exe`，所以 Steam 下载的是错误的通用分桶。
- **许可**：如果走"给别人嵌入"的路线，不能移植 DroidDeck 的 GPL-3 代码。

## 5. 下一步（按顺序）

1. **里程碑 1 收尾**（进度见 [`../TRACKING.md`](../TRACKING.md)；出画和写时复制已通过）：桌面尺寸已解决、DPI 设置已生效（2026-11-12；非 100% 时任务栏不跟着变，见 TRACKING）；剩下薄前缀的模板和仓库。Wine 分支已推到 `amphora-dev/proton-wine`，构建已在 amphora-engine 的 CI 上（2026-11-08）。
2. **设置清单**：按 [`13`](13-SETTINGS-CATALOG.md) 的结构定稿，先把"默认值来自 Recipe、四层覆盖（D15）"的数据模型落进 SDK。数据模型和四层解析已在 `:sdk`（`SettingsCatalog`，2026-11-12）；App 也已提前改成只用 SDK 公开 API 的壳（§2.3 第 4 步的 App 部分），SDK 接口还没定型。
3. 之后按 §2.3 的顺序继续（M2：FEX）。

## 6. 注意事项

- Y700 上装着 DroidDeck（`com.droiddeck.launcher`），只用来对照实验；调试开关用完要关掉。
- 本次从 Y700 拉下的 Proton 原包在本机 `/tmp/pexp/`（Experimental）、`/tmp/pexp-dl/{stable,ddp}/`，解包在 `/tmp/pexp-x/`；不入库。
