# 13 · 设置目录（图形与兼容）

> 状态：草案（2026-11-08）。落实 [`12`](12-REWRITE-DESIGN.md) 的 D6：App 里要有详细的图形与兼容设置，每项有分组、说明、默认值和理由；默认配置保证开箱体验。
> 默认值以 12 的 D12（Proton Experimental tag）、D13（薄前缀）、D14（出画）为前提；覆盖层级已定（D15）。标"待验证"的默认值要在对应里程碑用数据定。

## 1. 原则

- **一项设置 = SDK 里的一条数据**（`SettingSpec`）。设置页、本文、启动时的生效逻辑都从它生成，三者不会对不上。
- 每条都写清楚：做什么（一句话）、选项、默认值、**为什么是这个默认值**、作用范围（全局 / 按游戏）、什么时候生效（下次启动 / 立即）、落到哪里（环境变量、`dxvk.conf`、注册表、FEX 配置）、怎么确认生效（HUD、日志）。
- **默认值来自 Recipe**。能按设备能力自动决定的，写进默认值本身（"自动：…"），不另加开关。
- **每个值都标来源**：配方默认 / 你的全局设置 / 本游戏内置修正（附原因）/ 你为本游戏设置。改过的项有标记，每项、每组、每个游戏都能一键恢复默认。
- **分三级**：基础（默认展开）、高级（折叠）、开发者（打开开发者模式才显示）。三级都写说明和理由，区别只是默认是否可见。
- **不做的选项也写出来**（§4），说明为什么不给选，免得用户以为漏了。

## 2. 覆盖层级（2026-11-08 已定，12 D15）

从低到高，后面的覆盖前面的：

1. **Recipe 默认**：随配方版本发布。
2. **用户全局**：设置页里改的。
3. **Recipe 按游戏修正**：我们维护的数据，按游戏身份匹配，每条带原因和出处。格式参照 Proton 脚本里的按 appid 表（`default_compat_config`、`fex_application_profiles`）。
4. **用户按游戏**：游戏详情页里改的。

按游戏修正放在用户全局之上，因为它更具体：比如某个游戏必须限 60 帧，否则物理出错，这条应该压过用户"全局不限帧"的偏好。界面上要明确提示"本游戏的内置修正覆盖了你的全局设置：原因 …"，用户仍可以在第 4 层再改回来。

**按游戏修正从哪来**：

| 来源 | 怎么匹配游戏 | 谁维护 | 生效方式 |
|---|---|---|---|
| DXVK 内置配置 | exe 名 | DXVK 上游 | 组件自带，不经我们的层级；用户在第 2、4 层设了同一项时以用户为准 |
| vkd3d-proton 内置配置 | exe 名 | vkd3d-proton 上游 | 同上 |
| Wine 里按游戏的修正 | `SteamGameId`（Steam AppID） | Valve | 需要知道 AppID；旧仓库写死 `SteamGameId=0`，全部失效 |
| Proton 启动脚本里的表（`default_compat_config`、`fex_application_profiles` 等） | Steam AppID | Valve | 换 Recipe 时从对应 tag 的脚本导入，挑出和 Android 有关的条目，进第 3 层 |
| 我们自己的条目 | exe 哈希或 AppID | 我们 | 测试中发现的 Android / Turnip / FEX 特有问题，进第 3 层，每条写原因和出处 |

**游戏身份**：不接 Steam，就没有 AppID，后面三种来源里按 AppID 的条目都用不上。所以游戏身份 = 本地 exe 哈希 + 可选的 Steam AppID。AppID 由我们维护的"exe 名 / 哈希 → AppID"小表自动匹配（只收测过的游戏），匹配不上时用户可以在游戏详情页手动填。填了 AppID 才设 `SteamGameId`。

每个游戏有一页**生效配置**：列出这次启动最终用到的环境变量、`dxvk.conf`、DLL 覆盖、FEX 配置，可以复制，用于反馈问题。

```kotlin
// SDK 里的形状（示意）
data class SettingSpec<T>(
    val id: String,                    // "display.frameRate"
    val group: Group, val level: Level, // 基础 / 高级 / 开发者
    val type: SettingType<T>,          // 枚举、开关、数值范围、列表
    val default: (DeviceCaps) -> T,    // 来自 Recipe，可按设备能力决定
    val scope: Scope,                  // 全局、按游戏、两者
    val mapping: Mapping,              // env / dxvk.conf / 注册表 / FEX 配置
    val doc: SettingDoc,               // 标题、说明、默认值理由、怎么确认
)
```

## 3. 目录

列说明：**范围**里"全 / 游"表示可在全局设、可按游戏设。**落到**是生效时写到哪里。

### 3.1 显示与帧率

| 设置 | 选项 | 默认 | 默认值理由 | 范围 | 落到 |
|---|---|---|---|---|---|
| 帧率 | 跟随屏幕 / 跟随游戏 / 不限 / 30、45、60、90、120 | 跟随屏幕 | SurfaceFlinger 只锁存整帧，开不开垂直同步都不撕裂；跟随屏幕不会渲染看不到的帧，省电、少发热、节奏稳。"不限"只用于跑分，多出的帧会被丢掉 | 全 / 游 | 交换链呈现方式与节奏（12 §3.1）；`DXVK_FRAME_RATE`（DXVK 与 vkd3d-proton 的限帧器）；按层 `setFrameRate` |
| 游戏看到的分辨率 | 1280×720 / 1600×900 / 1920×1080 / 按屏幕比例 / 自定义 | 1280×720 | 旧仓库的默认，7xx 到 8xx 性能跨度大，720p 是稳妥的起点；放大由 HWC 做，不花 GPU；性能有余的机器按游戏调高。M3 用实测帧率复核 | 全 / 游 | Wine 显示模式列表 |
| 缩放 | 等比（留黑边）/ 拉伸 / 整数倍 | 等比 | 不变形；缩放走层几何，HWC 完成 | 全 / 游 | 宿主层几何 |
| 性能浮层 | 关 / 帧率 / 详细 | 关 | 默认不遮挡画面。"帧率"由宿主按真实上屏帧计数（12 §3.1 的事务统计），所有 API 都有；"详细"再加 CPU、GPU 负载、内存、温度（旧仓库宿主浮层的内容），D3D9–11 另开 DXVK HUD | 全 / 游 | 宿主浮层；`DXVK_HUD` |
| Windows 缩放（DPI） | 100% / 125% / 150%（高级） | 100%（96 DPI） | 游戏基本不看 DPI；非 96 时不少老程序界面错位。放大交给宿主缩放 | 全 / 游 | 注册表 `LogPixels` |

### 3.2 Direct3D（DXVK / vkd3d-proton）

| 设置 | 选项 | 默认 | 默认值理由 | 范围 | 落到 |
|---|---|---|---|---|---|
| D3D9 / 10 / 11 实现 | DXVK / WineD3D | DXVK | Proton 的默认；Vulkan 上最快。WineD3D 要经 OpenGL → Zink 两层，只作按游戏的退路 | 全 / 游 | DLL 覆盖（`d3d9`、`d3d10core`、`d3d11`、`dxgi`）；对应 Proton 的 `PROTON_USE_WINED3D` |
| D3D8 实现 | DXVK / WineD3D | DXVK（待验证） | Proton 默认把 D3D8 交给 WineD3D（`dxvkd3d8` 修正才用 DXVK）；我们这里 WineD3D 要多走 Zink，旧仓库 AIO dx8 在 DXVK 上通过。M3 用矩阵比两条路后定 | 全 / 游 | DLL 覆盖 `d3d8` |
| DirectDraw / D3D1–7 实现 | Wine 内置（WineD3D → Zink）/ d7vk / DxWrapper / cnc-ddraw | 待验证（M3） | Proton 用 Wine 内置。旧仓库默认 DxWrapper（转 D3D9 → DXVK），另有 d7vk（直接转 Vulkan）和 cnc-ddraw（2D 老游戏，自带 287 个按游戏配置）；这三个只有 32 位。旧仓库在 Y700 上 64 位 dx7 走 WineD3D → Zink 能出画。M3 用矩阵比 Wine 内置和 d7vk，再定默认值和留几个 | 游 | `ddraw.dll` 链接与 DLL 覆盖 |
| 各向异性过滤 | 游戏决定 / 2× / 4× / 8× / 16× | 游戏决定 | 强制高倍数在移动 GPU 上吃带宽；需要时按游戏开 | 全 / 游 | `d3d9.samplerAnisotropy`、`d3d11.samplerAnisotropy` |
| 最大帧延迟（高级） | 自动 / 1 / 2 / 3 | 自动 | 由 DXVK 按交换链决定；1 降低输入延迟但可能掉帧 | 游 | `dxgi.maxFrameLatency`、`d3d9.maxFrameLatency` |
| 向游戏报告的显卡（高级） | 真实（Qualcomm Adreno）/ AMD / NVIDIA | 真实 | 伪装成 NVIDIA 会让游戏走 NVAPI / DLSS 路径，伪装成 AMD 会走厂商专用路径；只给拒绝未知厂商的游戏按游戏伪装 | 游 | `dxgi.customVendorId` / `customDeviceId` |
| 报告的显存（高级） | 自动 / 2 / 4 / 6 / 8 GB | 自动（待验证） | 统一内存，显存就是系统内存。报太大，游戏按高画质分配，可能被 Android 杀进程；M3 先看 Turnip 在 Y700 上报多少再定 | 游 | `dxgi.maxDeviceMemory`、`dxgi.maxSharedMemory` |
| D3D12 特性级别（高级） | 自动 / 11_0 / 12_0 / 12_1 / 12_2 | 自动（真的不设） | vkd3d-proton 按设备能力报告；个别游戏在高级别下出错时按游戏降（Proton 也这样做）。旧仓库的"自动"其实固定成 12_1（§6.2） | 游 | `VKD3D_FEATURE_LEVEL` |
| D3D12 着色器模型（高级） | 自动 / 6_0 … 6_9 | 自动 | 由 vkd3d-proton 按驱动能力决定；强制驱动不支持的版本，游戏可能起不来 | 游 | `VKD3D_SHADER_MODEL` |
| D3D12 光线追踪（高级） | 自动 / 关 / 强制 | 自动 | vkd3d-proton 自己判断驱动是否安全支持 DXR；强制会跳过它的检查 | 游 | `VKD3D_CONFIG`（`nodxr` / `dxr`） |

### 3.3 OpenGL

| 设置 | 选项 | 默认 | 默认值理由 | 范围 | 落到 |
|---|---|---|---|---|---|
| OpenGL 实现 | Zink（只读） | Zink | 只支持 Adreno + Turnip，Zink 跑在 Turnip 上是唯一的硬件路径 | — | Mesa WGL（`opengl32`）|
| 旧版扩展字符串（高级） | 开 / 关 | 关 | 只给扩展字符串过长就崩溃的老游戏开（Proton 的 `oldglstr`） | 游 | `MESA_EXTENSION_MAX_YEAR=2003` |
| 报告的 GL 版本（高级） | 自动 / 手填 | 自动 | 只给检查版本号过严的游戏改 | 游 | `MESA_GL_VERSION_OVERRIDE` |

### 3.4 x86 翻译（FEX）

Valve 在 Proton ARM64 里给 FEX 的全局默认（Y700 上 Experimental 和 11.0 两个包的 `share/fex-emu/Config.json` 相同）：`TSOEnabled=1`、`VectorTSOEnabled=0`、`MemcpySetTSOEnabled=0`、`HalfBarrierTSOEnabled=1`、`X87ReducedPrecision=1`、`Multiblock=1`、`MaxInst=500`。Steam 只把 **TSO** 和 **Multiblock** 两项开放给用户按游戏改（`STEAM_COMPAT_FEX_CONFIG`），每个游戏的默认值来自 Steam 的 appinfo（`STEAM_FEX_TSOENABLED`、`STEAM_FEX_MULTIBLOCK`）。我们照这个分法：

| 设置 | 选项 | 默认 | 默认值理由 | 范围 | 落到 |
|---|---|---|---|---|---|
| x86 内存序模拟（TSO） | 开 / 关 | 开 | 和 Valve 一致。关掉更快，但多线程游戏可能随机崩溃或数据错乱；只对确认没问题的游戏关 | 全 / 游 | FEX `TSOEnabled` |
| 多块编译（Multiblock） | 开 / 关 | 开 | 和 Valve 一致。一次翻译更大的代码块，运行更快；个别游戏卡顿或出错时关 | 全 / 游 | FEX `Multiblock` |
| x87 降精度（高级） | 开 / 关 | 开 | 和 Valve 一致：80 位浮点改用 64 位算，快很多；依赖精确 x87 的程序按游戏关（Proton 对《巫师 3》安装程序就是这样处理的） | 游 | FEX `X87ReducedPrecision` |
| 其余 TSO 细项（开发者） | — | 照 Valve | 只用于排查 | 游 | `VectorTSOEnabled`、`MemcpySetTSOEnabled`、`HalfBarrierTSOEnabled` |

FEX 代码缓存见 §3.7。按游戏的 FEX 修正用 FEX 自己的按应用配置格式（Proton 的 `FEX_APP_CONFIG` / `AppConfig`）。

### 3.5 Windows 环境（Wine）

| 设置 | 选项 | 默认 | 默认值理由 | 范围 | 落到 |
|---|---|---|---|---|---|
| 游戏身份（Steam AppID） | 自动 / 手填 / 无 | 自动：有 `steam_appid.txt` 就用 | 打开 Wine、DXVK、vkd3d-proton 里按 `SteamGameId` 的修正，以及我们的按游戏修正 | 游 | `SteamGameId`、`SteamAppId` |
| Windows 版本 | Windows 10 / 7 / XP | Windows 10 | 和 Proton 一致；老游戏个别需要 XP / 7 | 游 | 前缀注册表 |
| Windows 语言（区域） | 自动 / 日语 / 简体中文 / 繁体中文 / 英语 | 自动：跟随系统语言，不在列表里的用英语 | 决定老游戏 ANSI 文本的代码页和字体替换，日文、中文老游戏乱码多半是它。Bionic 的 `setlocale` 总是返回 `C.UTF-8`，所以要写注册表（旧仓库同款，保留） | 全 / 游 | `LC_ALL`；注册表 ACP / OEMCP / LCID、`Control Panel\International`；字体替换 |
| Windows 组件（高级） | 每个组件：内置 / 原生（Direct3D 辅助库、DirectSound、DirectMusic、DirectShow、DirectPlay、XAudio、DirectInput 8、VC++ 2010） | 全部内置（待验证） | 和 Proton 一致：Proton 用 Wine 自己的实现，游戏需要原生运行库时由它自己的安装程序装。旧仓库默认把 Direct3D 辅助库、DirectInput 8、VC++ 2010 设成原生（WinNative 带来的做法，属于 Cursor 对话里说要逐个决定的偏离）。M3 用游戏验证后定 | 游 | 前缀里的原生 DLL + `DllOverrides` |
| 同步方式（高级） | 自动 / wineserver | 自动（待验证） | M1 实测（Y700，Android 16）：内核没有 `/dev/ntsync`，`futex_waitv` 被 Android app 的 seccomp 直接杀掉，所以 Valve Wine 的 ntsync 和 fsync 在 Android app 里都用不了，现在只有 wineserver 同步（慢）。可选的是 esync（eventfd，WinNative 重新加回了它，要改服务端协议）。M3 用游戏帧率决定要不要带 esync | 全 / 游 | `WINEESYNC` |
| 大地址感知（高级） | 自动 / 强制开 | 自动 | 只给 32 位、内存吃紧会崩的游戏强制开（Proton 的 `forcelgadd`） | 游 | `WINE_LARGE_ADDRESS_AWARE` |
| 堆调试开关（高级） | 延迟释放、清零分配、自顶向下 | 全关 | 只给有内存使用错误的游戏开（Proton 的 `heapdelayfree` / `heapzeromemory` / `heaptopdown`） | 游 | `WINE_HEAP_DELAY_FREE` 等 |
| 隐藏 D3D11 / D3D10（高级） | 开 / 关 | 关 | 让游戏退回 D3D9 路径（Proton 的 `nod3d11` / `nod3d10`） | 游 | DLL 覆盖 |
| DLL 覆盖（高级） | 列表 | 由 Recipe 生成 | 逃生口；改了会在生效配置页标出 | 游 | `WINEDLLOVERRIDES` |
| 启动参数 / 环境变量（高级） | 文本 | 空 | 逃生口 | 游 | 命令行 / 环境 |

### 3.6 音频与输入

（这两组 M3 之后细化；先列骨架。）

| 设置 | 选项 | 默认 | 默认值理由 | 范围 | 落到 |
|---|---|---|---|---|---|
| 音频缓冲 | 自动 / 低延迟 / 稳定 | 自动 | 低延迟在负载高时容易爆音；默认按设备输出缓冲大小定 | 全 / 游 | 音频后端参数 |
| 触控方式 | 直接点击 / 触控板 / RTS | 直接点击 | 旧仓库现状；触控板、RTS 手势在旧仓库是缺口 | 全 / 游 | 宿主输入 |

### 3.7 存储与缓存

| 设置 | 选项 | 默认 | 默认值理由 | 范围 | 落到 |
|---|---|---|---|---|---|
| 着色器缓存上限 | 256 MB / 512 MB / 1 GB / 2 GB | 1 GB（每游戏） | 每个游戏单独目录，互不挤占；Steam 允许每游戏约 5 GB，手机存储更紧 | 全 / 游 | `MESA_SHADER_CACHE_DIR`、`MESA_SHADER_CACHE_MAX_SIZE`；vkd3d-proton 缓存路径 |
| FEX 代码缓存 | 开 / 关 | 开 | Proton 在 aarch64 上默认开（`FEX_DISKCACHE=1`）；第二次启动少翻译、少卡顿。代价每游戏约 100 MB | 全 / 游 | `FEX_DISKCACHE` |
| 盘符映射（高级） | 自动 / 手动 | 自动：游戏所在目录一个盘符，下载目录、设备存储、SD 卡依次往后（旧仓库是 D: 下载、F: 设备存储、G: 起 SD 卡） | 游戏和存档放哪由用户决定，不复制进前缀 | 游 | 前缀 `dosdevices/` |
| 前缀信息 | 只读：大小、模板版本、实体文件数 | — | 薄前缀（12 §3.3）应在十几 MiB；变大说明游戏装了东西 | 游 | — |
| 操作 | 清缓存 / 重建前缀（保留存档）/ 删除游戏数据 | — | 重建前缀不动 `userdata/` | 游 | — |

### 3.8 调试（开发者）

| 设置 | 选项 | 默认 | 默认值理由 | 范围 | 落到 |
|---|---|---|---|---|---|
| Wine 日志 | 关 / 错误 / 错误和警告 / 自定义 | 关 | 日志拖慢运行；排查时按游戏开 | 全 / 游 | `WINEDEBUG`：`-all` / `-all,err+all` / `-all,err+all,warn+all`（旧仓库写成 `+err,+warn`，见 §6.2） |
| 着色器缓存开关 | 开 / 关 | 开 | 只在怀疑缓存损坏导致画面错误时关 | 游 | `MESA_SHADER_CACHE_DISABLE` |
| DXVK / vkd3d-proton 日志 | none / error / info / debug | none | 同上 | 游 | `DXVK_LOG_LEVEL`、`VKD3D_DEBUG` |
| FEX 日志 | 关 / 开 | 关 | 同上 | 游 | FEX `SilentLog` |
| Turnip 调试标志 | 文本 | 空 | 只用于排查驱动问题 | 游 | `TU_DEBUG` |
| GPU 驱动 | Turnip / 系统驱动（对比用） | Turnip | 唯一支持的驱动。系统驱动（高通官方）只用来对比：旧仓库的性能基线是在它上面测的（12 §2.3 M3），画面出错时切过去能判断是不是 Turnip 的问题。不是 SDK 扩展点，只是加载哪个 Vulkan 库 | 游 | 加载的 Vulkan 库 |

## 4. 不提供的选项（以及原因）

| 不给选的 | 原因 |
|---|---|
| DXVK async / gplasync | Turnip 支持 GPL（`graphicsPipelineLibrary` + `IndependentInterpolationDecoration`），DXVK 用快速链接编译管线，本来就不卡；gplasync 会在 GPL 之前截走新管线，在 Turnip 上反而用不上 GPL，还会跳过绘制造成画面错误（Cursor 对话结论） |
| DXVK 版本 / Wine 版本 / 驱动版本切换 | 一套配方一套组合（D1）。换版本是换 Recipe，不是设置项 |
| Box64 / 其他翻译器 | D5：只做 FEX，不留接口。Box64 的 32 位 `wowbox64.dll` 也不做（12 §3.5） |
| Mali / Xclipse 相关选项 | D4 只支持 Adreno 7xx/8xx |
| `DXVK_STATE_CACHE_PATH` | DXVK 3.x 已没有这个状态缓存，设置了也不生效 |

## 5. 待定

- 带"待验证"的默认值：同步方式（M1）；D3D8 实现、DirectDraw / D3D1–7 实现、报告的显存、Windows 组件（M3）。
- 音频、输入两组的完整清单（M3 之后）。音频先沿用旧仓库的 PulseAudio（AAudio 输出）；旧仓库在页大小超过 4 KB 的设备上退回 ALSA，新配方是否保留这条退路，看支持范围里有没有 16 KB 页的机器（Y700 是 4 KB）。
- 按游戏修正的数据格式和来源清单（先导入 Proton 脚本里与 Android 相关的表）。

## 6. 旧仓库的设置怎么处理

依据：对 `amphora_old` main `40fc4f7e` 的代码盘点（2026-11-08）。旧仓库有 20 个持久化设置，全部是全局的，存在一个 SharedPreferences（`amphora_graphics`）里，没有按游戏的一层；"按游戏"只来自第三方：Box64 rc 文件（12 个 exe）、cnc-ddraw 的 `ddraw.ini`（287 个游戏）。

### 6.1 逐项去留

| 旧设置（键） | 处理 | 新位置 / 原因 |
|---|---|---|
| 分辨率（`display_resolution`，默认 1280×720） | 保留 | §3.1，加按游戏 |
| GPU 驱动（`adrenotools_driver_id`：wrapper / Turnip / Leegao / System） | 删 | 只用 Turnip（D4）；系统驱动留成开发者对比项（§3.8） |
| D3D8–11 翻译（`dxvk_flavor`：自动 / DXVK 3.0.2 / Sarek），及其 guest 探针和 D3D8/9 拆到 DXVK 2.6.2 | 删 | 版本由 Recipe 定；Sarek 和拆分都是为 6T 和高通系统驱动准备的。"重新检测 GPU 能力"按钮随探针一起删，改成启动时的设备检查 |
| Windows 语言（`wine_locale`） | 保留 | §3.5 |
| DirectDraw 层（`directdraw_wrapper_id`，默认 DxWrapper） | 保留，默认值待定 | §3.2，M3 定 |
| Box64 翻译档位（`advanced_box64_preset`） | 删 | D5；对应的新项是 FEX 的 TSO / Multiblock（§3.4） |
| 音频后端（`advanced_audio_driver`：PulseAudio / ALSA） | 待定 | §5 |
| DXVK 异步管线（`advanced_dxvk_async`） | 删 | §4 |
| 帧率（`advanced_frame_rate_mode`） | 保留 | §3.1，语义不变；固定帧率的实现从交换链里 sleep 改成事务帧时间线（12 §3.1） |
| D3D12 特性级别 / 着色器模型 / 光线追踪 | 保留 | §3.2；修正"自动" |
| BC 纹理处理（`advanced_bcn_mode` → `WRAPPER_EMULATE_BCN`） | 删 | wrapper 专有；Turnip 原生支持 BC 纹理 |
| 宿主性能浮层、DXVK HUD | 合并 | §3.1 性能浮层 |
| 着色器缓存开关 / 上限（全局共享一个目录，上限 512 MB） | 保留，改成每游戏 | 开关进开发者（§3.8）；上限默认每游戏 1 GB（§3.7） |
| Wine 日志（`advanced_wine_debug`） | 保留 | §3.8，修正取值 |
| 自定义环境变量（`advanced_custom_env`） | 保留，改成按游戏 | §3.5；见 §6.2 最后一条 |
| Windows 组件（`windows_components`） | 保留为按游戏，默认改成全内置（待验证） | §3.5 |
| 清理着色器缓存 / 恢复默认 | 保留 | 改成按游戏、每项 / 每组 / 每游戏恢复 |
| 只能手动设的 `refresh_rate_override`、Box64 日志开关 | 删 | 刷新率由帧率设置按层决定 |

### 6.2 旧实现里的坑（新实现别重犯）

- **默认值有两份。** DXVK 异步那一行 UI 默认 `false`，ViewModel 和"恢复默认"按 `true`，实际生效也是开：新装就显示"已修改"，单项恢复写进去的是 `false`。新实现默认值只有 `SettingSpec` 一处。
- **"自动"不自动。** D3D12 特性级别选"自动"时不写变量，但启动准备阶段固定写了 `VKD3D_FEATURE_LEVEL=12_1`，所以实际是 12_1。新实现里"自动"必须真的不设，生效配置页能看出来。
- **日志取值写错。** "错误和警告"写的是 `WINEDEBUG=+err,+warn`，这打开的是名叫 `err`、`warn` 的通道，不是错误、警告这两类输出；正确写法是 `err+all`、`warn+all`。
- **死配置。** 容器的 `DEFAULT_ENV_VARS`（`TU_DEBUG=noconform,sysmem`、`ZINK_DESCRIPTORS=lazy`、`mesa_glthread=true` 等）在 `5909b50c` 删掉读取方之后再没进过会话；`DXVK_STATE_CACHE_PATH` 在 DXVK 3.x 无效；`SteamGameId=0` 写死，Wine 里按游戏的修正全部失效。新实现从 `SettingSpec` 生成环境，没有映射的值进不了会话。
- **保护名单不全。** 自定义环境变量号称不能覆盖引擎变量，但只拦了 15 个名字，`VK_LAYER_PATH`、`ADRENOTOOLS_*` 等照样能改。新实现的保护名单从"引擎生成的变量"自动得出，被覆盖的项在生效配置页标红。

