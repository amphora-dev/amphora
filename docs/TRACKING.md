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
- 只链了 FreeType：gnutls（`bcrypt` 报 no encryption support）、PulseAudio、Vulkan 等按里程碑加。
- 构建在 [`amphora-engine`](https://github.com/amphora-dev/amphora-engine)（BuildStream；CI 构建 `runtime.bst`，main 上发 Release）。Wine 分支已推到 `amphora-dev/proton-wine`（2026-11-08）。
- runtime 归档下载 153 MiB，解包 845 MiB、1702 个文件（PE 只部分 strip），还没瘦身。
- 薄前缀没做的部分：游戏自己装进前缀的相同文件不去重；重建前缀会丢游戏写进注册表的东西（12 §3.3"Recipe 升级"）。
- 模拟器（API 35 arm64）上会话到了 `desktop 1280x720` 但画面是黑的，没查；真机正常。

## 里程碑 2：FEX（完成，2026-11-15）

| 项 | 状态 | 证据 |
|---|---|---|
| 构建 | amphora-engine `fex/fex.bst`：FEX-2611.1（`4f9c1f1`）+ 上游 main 的 `71444c9`（进程退出时卡死）。Proton tag 里钉的是 `1c4f04b`（FEX-2610-101），2611.1 在它之上多了几处 Windows 修复，GameNative、Winlator-Ludashi 用的也是 2611。照 Proton 的 CMake 参数构建 `libarm64ecfex.dll`、`libwow64fex.dll` 和两个 unix 库；unix 库在 Bionic 上打一个补丁（没有 librt、没有 `shm_open`，后者只给 FEXTop 统计用）。runtime 改名 `amphora-wine-11.0-280718371b09-fex-4f9c1f1`，带 Proton 的 `share/fex-emu/Config.json`，manifest 多一个 `fex` 段 | CI 发布（amphora-engine PR #1，`ead6815`），sha256 `2446a314…`，下载 155 MiB、解包 855 MiB、1707 个文件；PR 和 main 两次构建的归档逐字节相同 |
| 64 位 x86（ARM64EC） | 通过（FEX-2611.1，App 从 CI Release 下载安装的 runtime；之前 `1c4f04b` 也通过）：`tools/fextest` 的 hello64 经 App 作为游戏启动（自己的前缀，D: 是游戏目录），CPUID、SSE 双精度、4 线程原子加、VEH 异常恢复都对，消息框正常绘制 | Y700 截图 |
| 32 位 x86（WoW64） | 通过（同上）：同一套检查，进程 i386 / 原生 arm64，消息框由翻译后的 i386 user32 绘制。模板首次生成时 wineboot 跑 32 位 rundll32 填好 `syswow64`（795 个文件）：模板 1699 个链接 + 46 个实体文件 3.9 MiB，生成 10.9 秒；每个前缀 `du` 约 4.5 MiB | Y700 截图 |
| FEX 配置 | SDK 设 `FEX_APP_CONFIG_LOCATION` 为 runtime 的 `share/fex-emu`（`Z:` 形式：游戏的当前盘是 D:，裸 unix 路径会解析到 D: 上）；TSO、Multiblock、FEX 日志、代码缓存几项设置走 `FEX_*` 环境变量 | 单元测试；Y700 上确认 FEX 读到了这个文件：只在 Config.json 里加 `DiskCachePath`，缓存就写到了那个目录。环境变量层优先级比文件高，所以两边重复的几项以设置为准 |
| 磁盘缓存 | 设置 `cache.fex`（默认开，`FEX_DISKCACHE=1`，同 Proton）。FEX 写到前缀的 `%LOCALAPPDATA%\fex-emu\DiskCache\<构建哈希>\`，也就是 `userdata/<前缀>/steamuser/AppData/Local/fex-emu`，跟着前缀删；子目录按 FEX 构建区分，换了 FEX 后旧构建的目录在下次启动时被 FEX 自己清掉，不用我们管 | Y700：hello64 约 86 KiB，hello32 约 2.8 MiB（WoW64 的 i386 系统库也翻译了）；从 `1c4f04b` 换到 2611.1 后 hello32 的旧目录 `cae46…` 没了，只剩新目录 `dc522…` |

**已知问题**：

- 本地 VM（OrbStack `bst-amd64`）重建 `wine/wine.bst` 时有 481 个 i386 PE 文件整份是 0（大小正常，内容全零；ccache 全部命中），原因没查。CI 的构建没有这个问题（PR #1 的归档里 0 个），所以 runtime 只用 CI 发布的；本地构建出的 runtime 不要装到设备上。
- Android 内核没有 `PR_SET_MEM_MODEL`（硬件 TSO）和 `PR_ARM64_SET_UNALIGN_ATOMIC`，FEX 回退到软件 TSO，TSO 开关是实打实的性能开关。

## 设备上的测试状态

- Y700 `HA262AAH`：装的是本仓库的 `app.amphora`（debug 包），runtime 是 App 从 CI Release 下载安装的 `…-fex-4f9c1f1`（配方 `2026.11-m2`）；游戏库里有 `hello64`、`hello32` 两个测试程序（`/sdcard/Download/amphora-test/x86/`）。M1 宿主 `app.amphora.next` 已不在设备上，`tools/` 里依赖它的两个工具要重新跑得先装回提交 `4efaf0f` 的宿主。
