# 进度

> 只记当前状态和可复现的证据；决策和理由在 [`research/12-REWRITE-DESIGN.md`](research/12-REWRITE-DESIGN.md)。

## 里程碑 1：Bionic 构建 + 出画（完成，2026-11-15 更新）

**过关标准已达到**：Y700 上 cmd、wineconsole、记事本出画，能输入文字。

| 项 | 状态 | 证据 |
|---|---|---|
| 源码 | Valve Wine Experimental `7b3ceaf8644b`（`experimental-11.0-20261027b`）+ 我们的补丁，分支 `amphora/exp-11.0-20261027b`（本地 `~/co/github/wine-arm64ec`，已推到 `amphora-dev/proton-wine`） | 见下面\"补丁清单\" |
| 构建 | aarch64 unix（NDK r29，API 30，16 KB 页）+ PE aarch64 / arm64ec / i386（llvm-mingw 20261020）；只链 FreeType | 当时用分支里的 `amphora/Dockerfile`、`amphora/build.sh`，现在由 amphora-engine 的 `scripts/wine.sh` 构建；首次全量编译整棵树只有 14 个错误，都在下表 |
| 无界面 | `wine cmd /c "echo … & ver"` 输出 `Microsoft Windows 10.0.19045`；wineboot 建前缀成功 | app 沙箱里经 `run-as` 运行 |
| 出画 | App（`app.amphora.next`，Wine 自带的 `WineActivity`）起 `explorer /desktop=shell,,android`；wineconsole、记事本窗口正常绘制，键盘输入可用 | 截图在会话里；本仓库提交 `4efaf0f` 的 `app/` |
| SDK + 壳（2026-11-12） | `app.amphora`（`:sdk` 的 `SessionActivity` + Kotlin 的窗口树，取代 `WineActivity`）：App 内下载、校验、解包 runtime `amphora-wine-11.0-280718371b09`；命令提示符、记事本出画，桌面 1280×720 等比铺满屏幕；不点窗口直接打字可用；返回键开会话菜单，"结束会话"后没有残留 Wine 进程；会话日志可在 App 里看 | Y700 上实测；单元测试 sdk 193、app 23、ui 10（2026-11-15） |
| 前缀写时复制（D13） | 通过：8 种操作全部成功，共享仓库文件内容、权限、xattr 都不变 | 本仓库 `tools/cowtest/`，见 12 §3.3 |
| 薄前缀（D13，2026-11-15） | 模板在设备上每个 runtime 建一次（无界面 `wineboot --init`，Y700 上 7.3 秒），和 runtime 文件逐字节相同的 866 个文件换成指向 runtime 目录的相对链接，剩 32 个实体文件 2.36 MiB；前缀从模板复制，不再跑 wineboot。`default` 前缀从 688 MiB 降到 2.9 MiB（`du`），旧前缀在下次启动时自动重建，`C:\users` 搬到 `userdata/` 保留 | Y700 实测：经 App 启动记事本、命令提示符、扫雷和一个加进库的游戏（自己的前缀，D: 指向游戏目录）；cmd 里追加写 `C:\windows\notepad.exe` 后前缀里变成实体文件、runtime 里的哈希不变；删 `system32\winemine.exe` 只删链接；`%USERPROFILE%` 的写入落在 `userdata/`；系统页"重建"后再启动，被删的文件回来、`userdata` 里的文件还在；移除游戏后它的前缀和 `userdata` 都删掉，游戏目录不动。和 12 §3.3 原方案的差别见该节末尾 |
| 系统调用探针 | `futex_waitv` 被 app seccomp 杀掉（fsync 在 Android app 里用不了）；`userfaultfd`、`memfd_create`、`pidfd_open` 可用；`set_robust_list` 被杀 | 本仓库 `tools/probe/syscalls.c`，M1 宿主（提交 `4efaf0f` 的 `app/`）的 `amphora.exec` 钩子 |

**补丁清单**（相对 Valve tag，按用途）：

| 用途 | 提交 | 说明 |
|---|---|---|
| Bionic 编译 | fsync 在 Android 上关掉（Bionic 没有 `shm_open`）；`M_PERTURB` 缺失时跳过；`--disable-amd_ags_x64`（要 libdrm） | 首次编译的全部 C 错误 |
| Valve 的 bug | `uffd_tmp_defs.h` 在所有非 x86_64 架构上把 `__NR_userfaultfd` 定成 i386 的 374；arm64 上这个号不存在，app seccomp 直接杀进程 | 可以报给 Valve |
| SELinux execmod | 无重定位的可执行节先设 R+X 再重定位（旧仓库 `a177c89b75`）；仍被拒时把页搬到匿名内存 | 不修则 ntdll.dll 映射失败 |
| wineandroid | Twaik Yont 的拆进程系列 34 个 + Rémi Bernon 1 个 + 旧仓库对 Proton win32u ABI 的 5 个适配；旧仓库的 Box64 / WSI 改动全部没带（D5、D14） | 全部干净 cherry-pick |
| 键盘输入 | 桌面窗口建好时把它所在的桌面设成输入桌面（`280718371b09`）。键盘事件不带窗口，wineserver 把它排到窗口站的输入桌面，也就是窗口站里第一个建的桌面，不是 explorer 给视图建的 `shell`；鼠标事件带窗口，会顺带切过来 | 不修则程序启动后要先点一下才能打字 |
| 薄前缀 | wineserver `open_fd()` 写时复制（`WINE_COW_STORE`）；`SetFileAttributesW` 按 Windows 的做法带 `FILE_WRITE_ATTRIBUTES` 打开 | 后者不改的话，改属性会经链接改到仓库文件的权限和 xattr |

WinNative 那个 Android/Arm64EC 大提交（`986479030a`）没有整体带入：里面的 esync、关 fsync、写死盘符、FLS 回调绕过等是混在一起的；以上只按编译错误和设备上的实际失败逐个加。

**还没做 / 已知问题**：

- 桌面尺寸已解决：桌面大小由 SDK 按设置定（默认 1280×720），再等比缩放到屏幕。DPI 设置（`display.dpi`）经 `wine_config_changed` 生效：150% 下文件管理器的标题栏、菜单和字体按 1.5 倍绘制；但任务栏在 DPI 送达之前已经建好，仍是 100% 的大小。
- 软键盘只验证了能弹出、回车能到 Wine；用 adb 注入的字母会先进中文输入法的拼音候选，真实触摸软键盘打字还没测。
- 前缀写时复制的残余缺口：Wine 不检查句柄权限，程序如果用没带 `FILE_WRITE_ATTRIBUTES` 的句柄改属性，仍会改到仓库文件（Windows 上这样调用会失败，正常程序不会这么做）。删除和改名现在会先复制再删 / 改，浪费一次复制，以后可以只删 / 改链接本身。
- Wine 只链了 FreeType 和 Vulkan：gnutls（`bcrypt` 报 no encryption support）、音频等按里程碑加。
- 构建在 [`amphora-engine`](https://github.com/amphora-dev/amphora-engine)（BuildStream；CI 构建 `runtime.bst`，main 上发 Release）。Wine 分支已推到 `amphora-dev/proton-wine`（2026-11-08）。
- runtime 归档下载 153 MiB，解包 845 MiB、1702 个文件（PE 只部分 strip），还没瘦身。
- 薄前缀没做的部分：游戏自己装进前缀的相同文件不去重；重建前缀会丢游戏写进注册表的东西（12 §3.3"Recipe 升级"）。
- 模拟器（API 35 arm64）上会话到了 `desktop 1280x720` 但画面是黑的，没查；真机正常。

## 里程碑 2：FEX（完成，2026-11-15）

| 项 | 状态 | 证据 |
|---|---|---|
| 构建 | amphora-engine `fex/fex.bst`：Proton tag 里钉的提交 `1c4f04b`（FEX-2610-101），不带上游 main 的修复。2026-11-15 到 11-22 之间用过 FEX-2611.1（`4f9c1f1`）+ 上游的 `71444c9`（进程退出时卡死），那次升级不是因为设备上测到了问题，所以改回 tag 的版本（12 §5 第 3 条）。照 Proton 的 CMake 参数构建 `libarm64ecfex.dll`、`libwow64fex.dll` 和两个 unix 库；unix 库在 Bionic 上打一个补丁（没有 librt、没有 `shm_open`，后者只给 FEXTop 统计用）。runtime 带 Proton 的 `share/fex-emu/Config.json`，manifest 有 `fex` 段，名字里带 `-fex-1c4f04b` | CI 发布（amphora-engine `3148fd8`），runtime `amphora-wine-11.0-770268ec7af9-fex-1c4f04b-gfx-400b6f9e`，sha256 `0a824070…`；设备上 manifest 的 `fex.version` 是 `FEX-2610-101-g1c4f04b` |
| 64 位 x86（ARM64EC） | 通过（`1c4f04b`，App 从 CI Release 下载安装的 runtime；2611.1 上也通过过）：`tools/fextest` 的 hello64 经 App 作为游戏启动（自己的前缀，D: 是游戏目录），CPUID、SSE 双精度、4 线程原子加、VEH 异常恢复都对，消息框正常绘制 | `tools/device/smoke.sh hello64`：`PASSED, exited by itself`；消息框是 2611.1 那次看的截图 |
| 32 位 x86（WoW64） | 通过（同上）：同一套检查，进程 i386 / 原生 arm64，消息框由翻译后的 i386 user32 绘制。模板首次生成时 wineboot 跑 32 位 rundll32 填好 `syswow64`（795 个文件）：模板 1699 个链接 + 46 个实体文件 3.9 MiB，生成 10.9 秒；每个前缀 `du` 约 4.5 MiB | Y700 截图 |
| FEX 配置 | SDK 设 `FEX_APP_CONFIG_LOCATION` 为 runtime 的 `share/fex-emu`（`Z:` 形式：游戏的当前盘是 D:，裸 unix 路径会解析到 D: 上）；TSO、Multiblock、FEX 日志、代码缓存几项设置走 `FEX_*` 环境变量 | 单元测试；Y700 上确认 FEX 读到了这个文件：只在 Config.json 里加 `DiskCachePath`，缓存就写到了那个目录。环境变量层优先级比文件高，所以两边重复的几项以设置为准 |
| 磁盘缓存 | 设置 `cache.fex`（默认开，`FEX_DISKCACHE=1`，同 Proton）。FEX 写到前缀的 `%LOCALAPPDATA%\fex-emu\DiskCache\<构建哈希>\`，也就是 `userdata/<前缀>/steamuser/AppData/Local/fex-emu`，跟着前缀删；子目录按 FEX 构建区分，换了 FEX 后旧构建的目录在下次启动时被 FEX 自己清掉，不用我们管 | Y700：hello64 约 86 KiB，hello32 约 2.8 MiB（WoW64 的 i386 系统库也翻译了）；从 `1c4f04b` 换到 2611.1 后 hello32 的旧目录 `cae46…` 没了，只剩新目录 `dc522…`；换回 `1c4f04b` 后跑过的前缀里又只剩 `cae46…`（32 位）或 `e5129…`（64 位） |
| 进程退出 | 程序自己退出后会话跟着结束，没有卡住：不带 `71444c9` 的 `1c4f04b` 上，hello64、hello32、AIO 的 64 位 vk / dx11 和 32 位 dx9 / dx12（AIO 退出时渲染线程还在）连跑 5 轮，每项都在 15 秒内退出 | `tools/device/smoke.sh hello64 hello32 aio64-dx11 aio32-dx9 aio64-vk aio32-dx12` × 5：每轮 `6 tests, 0 failed`，约 27 秒 |

**已知问题**：

- 本地 VM（OrbStack `bst-amd64`）重建 `wine/wine.bst` 时有 481 个 i386 PE 文件整份是 0（大小正常，内容全零；ccache 全部命中），原因没查。CI 的构建没有这个问题（PR #1 的归档里 0 个），所以 runtime 只用 CI 发布的；本地构建出的 runtime 不要装到设备上。
- Android 内核没有 `PR_SET_MEM_MODEL`（硬件 TSO）和 `PR_ARM64_SET_UNALIGN_ATOMIC`，FEX 回退到软件 TSO，TSO 开关是实打实的性能开关。

## 里程碑 3：图形栈（Vulkan 和 Direct3D 完成，2026-11-22；OpenGL 没做）

**现在的状态**：runtime `amphora-wine-11.0-770268ec7af9-fex-1c4f04b-gfx-400b6f9e`（配方 `2026.11-m3.1`，CI 发布；和 `2026.11-m3` 只差 FEX 改回 tag 的版本）里有 Wine（带 Vulkan 和自己的交换链）、FEX、DXVK、vkd3d-proton、Turnip。Y700 上从 Release 下载安装后，AIO 图形测试的 vk、dx8、dx9、dx10、dx11、dx12 在 64 位和 32 位下都出画。GPU 驱动只用 Turnip，高通系统驱动不再用（12 §2.2、13 §4）。

这一节分两段：先是当前这套（Turnip + 我们构建的 DXVK / vkd3d-proton），后面"交换链"一段是当天早些时候在高通系统驱动上做交换链时的记录，数字留作同驱动对比。

### 驱动、Direct3D、帧率设置（当前）

| 项 | 状态 | 证据 |
|---|---|---|
| Turnip | amphora-engine `mesa/turnip.bst`：Mesa 26.3.1，Android 构建，只带 KGSL，产物是 Vulkan HAL 模块 `lib/libvulkan_freedreno.so`（14 MiB）。带一个补丁（高通新 gralloc 不写 `gmsm` 标记，读不到 AHB 的 UBWC 标志）；旧构建回移的 KGSL 修复（MR 44838）26.3 已自带 | amphora-engine `44372ac` |
| Wine 打开 Turnip | `wineandroid.drv` 按 `WINE_ANDROID_VULKAN_HAL` 直接 `dlopen` 这个模块，取它的 `HMI`、打开 `vk0` 设备，把它的 `vkGetInstanceProcAddr` 换给 win32u；没有 loader。实例级的 `VK_KHR_surface`、`VK_KHR_get_surface_capabilities2` 改由驱动自己报。SDK 每次启动都设这个变量 | Wine `f71b97922d`；logcat 每个用 Vulkan 的进程一行 `wine: vulkan: using Mesa 3D Vulkan HAL from …/lib/libvulkan_freedreno.so`；程序里看到的设备是 `Adreno (TM) 830v1, Vulkan 1.4.363` |
| 32 位 Vulkan | 通过：`vktest32` 离屏渲染回读正确，映射地址在 4 GiB 以下（`readback buffer mapped at 01550000`）。在高通驱动上这一步是 `VK_ERROR_OUT_OF_HOST_MEMORY`（见下面"32 位"） | `tools/device/vkrun.sh vktest32 ""`；三个架构的离屏测试都 `PASS (0 failed)` |
| win32u 修一处 | 32 位程序建 Vulkan 1.0 实例时在 `vkCreateInstance` 里崩（`Exception 0xc0000005 in Unix call`，pc=0）：win32u 查 placed map 对齐时用了不带 KHR 后缀的 `vkGetPhysicalDeviceProperties2`，1.0 的实例上 Mesa 不给这个名字（loader 会给）。改成 KHR 名 | Wine `770268ec7a`；修之前 AIO 32 位的 vk 和 dx12 起不来，修之后见下表 |
| DXVK、vkd3d-proton 的构建 | amphora-engine `dxvk/dxvk.bst`（`a78a467`，`v3.1.1-97-ga78a467cadb22be`）、`vkd3d-proton/vkd3d-proton.bst`（`cae2e00`）：Proton tag 的子模块提交，照它的 `Makefile.in` 构建，布局 `lib/wine/{dxvk,vkd3d-proton}/{aarch64,i386}-windows`。64 位文件是 **ARM64EC，不是 tag 的 ARM64X**（要 Valve 打过补丁的 llvm-mingw，12 §3.1 末尾） | amphora-engine `a3c5ade`；会话日志 `info: DXVK: v3.1.1-97-ga78a467cadb22be`、`vkd3d-proton - build: cae2e003bb82516`、`Found device: Adreno (TM) 830v1 (turnip Mesa driver 26.3.1)` |
| 进前缀 | 建模板时把 `system32`（ARM64EC）和 `syswow64`（i386）里的 `d3d8`、`d3d9`、`d3d10core`、`d3d11`、`dxgi`、`d3d12`、`d3d12core` 链到 runtime 里的 DXVK / vkd3d-proton；每次启动设 `WINEDLLOVERRIDES=…=n`（同 Proton）。模板 1699 个链接 + 46 个实体文件 3.9 MiB，生成 12.6 秒；前缀 `du` 4.3–6.7 MiB | 设备上 `templates/2026.11-m3.1/tree/drive_c/windows/system32/d3d11.dll -> …/lib/wine/dxvk/aarch64-windows/d3d11.dll`；单元测试 |
| 帧率设置 | `display.frameRate` 接通（`WINE_ANDROID_FRAME_RATE`）。640×480 三角形：跟随屏幕（默认）时程序要 IMMEDIATE 也是 120.8 fps；不限时程序要 FIFO 也是 9069 fps；30 / 45 / 60 实测 30.1 / 45.1 / 60.1 fps（三个架构各测一个）。AIO：dx11 限 60 平均 60，dx9-32 跟随屏幕平均 120 | Wine `061cda5ec2`；`tools/device/vkrun.sh vktest64 "--window --frames 240 --mode mailbox" 16 "" display.frameRate=30` 等 |
| 切后台 | Turnip 上 Home 再回来（从 App 的"回到游戏"）：三角形继续转 | Y700 截图，帧号继续增加 |
| 回归 | hello32（FEX；控制台窗口和消息框，都是 GDI 窗口）在 CI 发布的 runtime 上正常，六项检查都 `ok`。记事本、命令提示符没重测 | Y700 截图 |

**AIO 图形测试**（`tools/device/aio.sh <64|32> <后端> 8`：`--cube <后端> --no-menu --bench 8`，程序要的是不限帧，帧率设置"跟随游戏"；x86_64 和 i386 都经 FEX；窗口 500×500 / 632×446。CI 发布的 runtime（FEX `1c4f04b`），每格由脚本核对中途的截图：是会话的桌面、有 AIO 的窗口、窗口里画了方块；每格跑的时候 CPU 上限都是 1996800 / 2246400 kHz，见下）：

| 后端 | 路径 | 64 位 平均（最低） | 32 位 平均（最低） | 旧仓库基线（高通驱动） |
|---|---|---|---|---|
| vk | 程序直接用 Vulkan | 2950（341） | 2842（307） | 832 |
| dx8 | DXVK | 6085（193） | 5782（94） | — |
| dx9 | DXVK（固定功能管线） | 6175（166） | 5831（117） | 32 位 431 |
| dx10 | DXVK | 4898（180） | 4594（117） | — |
| dx11 | DXVK | 4876（147） | 4695（72） | 926 |
| dx12 | vkd3d-proton | 5353（196） | 5138（166） | 1140 |

- FEX 2611.1 的 runtime（配方 `2026.11-m3`）上同一张表是 64 位 2942 / 6225 / 6210 / 4895 / 4943 / 5369，32 位 2877 / 5896 / 5894 / 4640 / 4679 / 5167：两个 FEX 版本的差别不超过 2.3%，而同一个 runtime 测两遍也有约 1% 的出入，分不出快慢。
- 和基线不是同一个条件：旧实现是 x86_64 Wine 跑在 Box64 里、DXVK 3.0.2、高通驱动；这里是 arm64ec Wine、FEX、DXVK 3.1.1、Turnip，Direct3D 的 DLL 是原生 ARM 代码。四项都比基线快几倍，没有变慢要解释的。同驱动的对比只有 vk 一项：高通驱动上 3005，Turnip 上 2950。
- dx8、dx9 在 Turnip 上方块正常：高通驱动编译不了 DXVK 固定功能着色器的问题（旧仓库为此把 D3D8/9 拆到 DXVK 2.6.2）不存在了。
- 这是一个转动的方块，数字说明的是整条链路（FEX、Wine、DXVK、驱动、交换链）每帧的固定开销，不代表游戏帧率。
- **数字跟着 CPU 上限走。** 会话在前台时 Y700 把 CPU 压在 1996800 / 2246400 kHz（硬件上限 3532800 / 4320000），会话一结束就放开；连续测几分钟、机身到 40 °C 左右后第二个上限降到 1958400，这时 64 位的 dx10 / dx11 掉到约 3970（低约 20%），vk 约 2620。`aio.sh` 会把跑分中途的上限打出来，只比上限相同的数字。
- gl 没有：OpenGL 还没做（AIO 报 `OpenGL is not available in this container`）。dx7、ddraw2d 没测：它们走 WineD3D，WineD3D 要 OpenGL，按这个推断也起不来。
- 快测（`tools/device/smoke.sh full`，每格 2 秒，只看能不能出画和退出）：hello64、hello32、三个架构的 vktest 离屏、上面 12 格，`17 tests, 0 failed`，约 80 秒。

### 交换链（当天早些时候，在高通系统驱动上）

当时 Wine 加载的是系统的 `libvulkan.so`（Android 的 loader + 高通驱动，Adreno 830 报 Vulkan 1.3.284）。交换链的做法之后没变；窗口、尺寸变化、切后台这几项在 Turnip 上重测过（上表）。

| 项 | 状态 | 证据 |
|---|---|---|
| Wine 带 Vulkan | amphora-engine `scripts/wine.sh` 改成 `--with-vulkan`，win32u 加载系统的 `libvulkan.so`（现在加载之后由驱动换成 Turnip，见上） | amphora-engine `d430172` |
| 前提验证 | 像 Wine 那样启动的进程（经 `linker64`，app 沙箱）里：自己分配的 AHardwareBuffer 能导入成可渲染的 VkImage（RGBA8 unorm / sRGB、A2B10G10R10、RGBA16F、RGB565，1280×720 和 1283×721），渲染后回读正确；渲染完成能导出成 sync fd；sync fd（含 -1）能导入信号量和 fence | `tools/probe/vkahb.c`，全部 `ok` |
| 离屏渲染 | `tools/vktest` 不带参数：真正的图形管线画三角形、回读、逐像素核对。aarch64 通过，x86_64（FEX）通过；**i386（WoW64）在 `vkMapMemory` 失败**，见下面"32 位" | Y700 截图（经 App 启动） |
| 交换链 | `wineandroid.drv` 自己提供 `VK_KHR_surface` / `VK_KHR_swapchain`：图像是 Wine 进程分配的 AHB，建链时一次发给宿主；每帧只发下标和渲染完成的 sync fd；宿主在窗口 client 视图（SurfaceView）里的一个子层上用 `ASurfaceTransaction_setBuffer` 送显，被替换下来的图像连同合成器的 release fence 发回。`vktest --window` 在 aarch64、x86_64、i386 上都出画，窗口有标题栏和边框，client 区上的点击能到程序 | Y700 截图；Wine `2421d241e4`、`b10583d4a3`，本仓库 `0c4853d` |
| 帧率（640×480 三角形） | FIFO：120.5 fps（面板 120 Hz，每帧都显示）。MAILBOX：aarch64 8810、x86_64 9329 fps；IMMEDIATE：8402 fps（不受刷新率限制，多出的帧在宿主里就还给程序，不进合成器） | `tools/device/vkrun.sh vktest-arm64 "--window --frames 20000 --mode mailbox"` |
| AIO 图形测试（Vulkan） | `AIO-Graphics-Test-64bit.exe --cube vk --no-menu --bench 8`（x86_64，经 FEX；500×500，程序要的是不限帧）：平均 3005 fps，最低 294，最高 3611。旧仓库记录的 Y700 基线是 vk 832 fps，同样在高通系统驱动上。其余几项见上面的矩阵 | 设备上 `AIO Results/Benchmark/AIO-Graphics-Test_bench_Vulkan.txt`：`3005\|294\|3611`；截图。非 FIFO 的交换链最少 4 张图像：3 张时同一测试只有 126 fps（程序还是得等合成器还图） |
| DXVK 预演（不是配方的构建） | 把上游发布的 DXVK 3.1.1 的 x64 DLL 放在 exe 旁边、用"DLL 覆盖"设成 native，只为确认交换链接得住 DXVK；配方要的是按 Proton pin（`a78a467ca`）构建的 ARM64X + i386，还没做。`--cube dx11`：出画，平均 4712 fps（最低 193，最高 20790），DXVK 选了 IMMEDIATE、4 张图像、632×446。`--cube dx9`：帧照常提交（5497 fps），**方块没画**：高通驱动编译不了 DXVK 的固定功能着色器（`DxvkGraphicsPipeline: Failed to compile pipeline: -13`，`FF VS` / `FF FS`），和旧仓库记录的是同一个问题，要 Turnip | 截图；会话日志里 DXVK 的 `info:` / `err:` 行。这两个数字不能直接和旧基线（dx11 926）比：窗口大小、DXVK 版本和构建都不同，只说明新交换链不是瓶颈 |
| HWC | 交换链的层由 HWC 直接叠加（`DEVICE`），640×480 放大到 1520×1141 也是 HWC 做的；整屏没有 GPU 合成（`usesClientComposition=false`） | `dumpsys SurfaceFlinger`：`wine-vulkan#… DEVICE ROT_90 500 152 1641 1672 / 0.0 0.0 640.0 480.0` |
| 尺寸变化 | 最大化（640×480 → 1280×694）和还原：win32u 报 `VK_SUBOPTIMAL_KHR`，程序重建交换链，旧交换链的最后一帧留在屏幕上直到新的第一帧 | Y700 截图 |
| 切后台 | Home 再回来：继续转动。后台期间没有层，宿主每 100 ms 还一帧，程序 10 秒里只画了约 650 帧（前台是每秒 120 帧） | 标题栏帧号 489 → 1145 |
| 刷新率 | Y700 是变刷新率面板，层不表态时系统停在 60 Hz。现在 client 视图对自己的层投票（帧率设置是数字时投这个数，否则投最高）；另外 App 声明成游戏（`appCategory="game"`），联想的系统服务才放开高刷新率。面板停在 120 Hz 而不是它支持的 144 / 165 Hz，是因为这台设备系统设置里的最高刷新率是 120（`settings get system peak_refresh_rate` = 120.0） | `dumpsys SurfaceFlinger`：层 `165.00 Default`，面板 120 Hz；logcat `judgeGameMode: should in GameMode` |

**32 位（WoW64）在高通系统驱动上用不了 Vulkan 的映射内存。** win32u 在 WoW64 下要把 `vkMapMemory` 的结果放到 4 GiB 以下，靠 `VK_EXT_map_memory_placed`，退一步靠 `VK_EXT_external_memory_host`；Y700 的高通驱动两个都没有（`tools/probe/vkahb.c` 会列出来），所以返回 `VK_ERROR_OUT_OF_HOST_MEMORY`。Mesa（Turnip）有 `VK_EXT_map_memory_placed`。这是 runtime 只用 Turnip、不留系统驱动开关的原因之一；12 §2.3 第 3 步里"同在系统驱动上对比"因此只做了 vk 一项。

**相对 12 §3.1 的落地差别**（细节在该节末尾）：

- 出画接在 win32u 的一处：驱动把宿主的 `vkGetInstanceProcAddr` / `vkGetDeviceProcAddr` 包起来，surface 和交换链的入口由驱动回答，其余原样转给宿主。win32u 只多了一个可选的驱动回调，不用逐个改函数表。
- 每个 surface 一条专用 socket，帧不经宿主主线程。
- Valve 的 `454e0dcddc` 让有 client surface 的窗口不建 window surface（X11 上边框由窗口管理器画）；Android 上边框在 window surface 里，所以加了一个只对 Android 生效的补丁把它留住（`cda232ddfd`）。
- SDK 给 Wine 设 `WINE_DISABLE_FULLSCREEN_HACK=1`：放大由宿主的层做，Proton 的 fullscreen hack 会在交换链里再放大一次。

**还没做 / 已知问题**：

- **OpenGL 没有**：Zink（Mesa WGL）没构建，AIO 的 gl 起不来（实测），dx7、ddraw2d 走 WineD3D，按推断也起不来（没测）；WineD3D 的两个选项（`d3d.d3d11`、`d3d.d3d8` 选 WineD3D）也因此用不了。
- **DXVK、vkd3d-proton 的 64 位文件是 ARM64EC，不是 Proton tag 的 ARM64X**：本身是 ARM64 的 Windows 程序加载不了它们（x86_64 和 32 位不受影响）。要对齐得在 buildstream-sdk 里从源码构建 Valve 那套 llvm-mingw（12 §3.1 末尾）。
- Turnip 打不开时没有 Vulkan，不回退到系统驱动（有意的）；这种情况界面上现在没有提示，只在会话日志里有 `err:vulkan`。
- 固定帧率是驱动里按时钟睡的，没用 `ASurfaceTransaction_setFrameTimeline`；`display.frameRate` 设成数字时宿主对层投这个数的票，Y700 上面板有没有因此降档没确认（当时 `dumpsys` 看到的仍是 120 Hz）。
- 性能浮层（`display.hud`）、DXVK 的几项 `dxvk.conf` 设置（各向异性、帧延迟、显卡伪装、显存）、着色器缓存目录还没接；vkd3d-proton 的缓存现在写在游戏目录（`vkd3d-proton.cache`）。
- 交换链只报 RGBA8（unorm / sRGB）和 A2B10G10R10，色彩空间只有 sRGB；不报 BGRA（AHB 没有公开的 BGRA 格式）。HDR、`VK_KHR_present_wait` / `present_id`、`VK_EXT_swapchain_maintenance1` 都没实现，对应的宿主扩展对程序隐藏；vkd3d-proton 因此报 `supports neither present_wait1 or present_wait2. Latency will increase`。
- UBWC、电流这两项 12 §3.1 的验收数据没量。
- 一个窗口上同时有两个 Vulkan surface 时宿主会建两个层（没拦）。宿主主线程和合成器回调线程上有可能阻塞的 socket 发送。
- 保留 window surface 后，Vulkan 窗口的 client 区是否还会被 GDI 画一遍没查（画了也被层盖住，只是浪费）。
- 不能用 `run-as` 无界面跑带 Vulkan 的程序：win32u 初始化 Vulkan 时会加载显示驱动，没有宿主就卡住。测试都经 App 启动（`tools/device/`）。
- 只测了测试程序（vktest、AIO），还没跑过真正的游戏。
- 会话在前台时 CPU 被系统压在约 2.0 / 2.25 GHz（硬件上限 3.5 / 4.3 GHz）：`/sys/devices/system/cpu/cpufreq/policy{0,6}/scaling_max_freq` 在会话进程存在期间是 1996800 / 2246400，结束后约 1 秒恢复。logcat 里有联想的 `judgeGameMode`，像是游戏模式的默认档位（App 为了高刷新率声明成了游戏）；换性能档能不能放开、不声明成游戏时是多少，都没查。
- 本地 VM 的全零问题还在：2026-11-22 本地 `bst build runtime.bst`，新加的检查拦下 509 个全零的 i386 Wine 文件（DXVK、vkd3d-proton、Turnip 的产物正常）；CI 上同一份源码检查通过。跳过检查后打包脚本其余部分跑通。
- runtime 归档下载 174 MiB，解包 910 MiB、1722 个文件。

## 设备上的测试状态

- Y700 `HA262AAH`：装的是本仓库的 `app.amphora`（debug 包，配方 `2026.11-m3.1`）。runtime 是 **CI 发布版** `amphora-wine-11.0-770268ec7af9-fex-1c4f04b-gfx-400b6f9e`，2026-11-22 16:16 在 App 里从 Release 下载安装（sha256 `0a824070…`），之后没用 `dev/push.sh` 换过文件。里程碑 3 的 AIO 表、快测、里程碑 2 的"进程退出"都是在它上面跑的。帧率设置、切后台是在更早的开发构建上测的（同一份 Wine 源码），没在它上面重测。
- 游戏库 7 个：`hello64`、`hello32`（`/storage/emulated/0/Download/amphora-test/x86/`），`vktest-arm64`、`vktest64`、`vktest32`（`…/amphora-test/vk/`），`AIO-Graphics-Test-64bit`、`AIO-Graphics-Test-32bit`（`…/amphora-test/aio/`，`amphora-dev` fork 的 2.1.0 构建）。测试脚本每次启动都把这个游戏的按游戏设置整个换掉，所以它们的设置是最后一次测试留下的。
- `/sdcard/Android/data/app.amphora/files/` 里还留着两个旧 runtime 的侧载归档（155 MiB、153 MiB），可以删。
- M1 宿主 `app.amphora.next` 已不在设备上，`tools/` 里依赖它的两个工具要重新跑得先装回提交 `4efaf0f` 的宿主。
