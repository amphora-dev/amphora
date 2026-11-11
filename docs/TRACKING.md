# 进度

> 只记当前状态和可复现的证据；决策和理由在 [`research/12-REWRITE-DESIGN.md`](research/12-REWRITE-DESIGN.md)。

## 里程碑 1：Bionic 构建 + 出画（进行中，主体完成，2026-11-08）

**过关标准已达到**：Y700 上 cmd、wineconsole、记事本出画，能输入文字。

| 项 | 状态 | 证据 |
|---|---|---|
| 源码 | Valve Wine Experimental `7b3ceaf8644b`（`experimental-11.0-20261027b`）+ 我们的补丁，分支 `amphora/exp-11.0-20261027b`（本地 `~/co/github/wine-arm64ec`，已推到 `amphora-dev/proton-wine`） | 见下面\"补丁清单\" |
| 构建 | aarch64 unix（NDK r29，API 30，16 KB 页）+ PE aarch64 / arm64ec / i386（llvm-mingw 20261020）；只链 FreeType | 当时用分支里的 `amphora/Dockerfile`、`amphora/build.sh`，现在由 amphora-engine 的 `scripts/wine.sh` 构建；首次全量编译整棵树只有 14 个错误，都在下表 |
| 无界面 | `wine cmd /c "echo … & ver"` 输出 `Microsoft Windows 10.0.19045`；wineboot 建前缀成功 | app 沙箱里经 `run-as` 运行 |
| 出画 | App（`app.amphora.next`，Wine 自带的 `WineActivity`）起 `explorer /desktop=shell,,android`；wineconsole、记事本窗口正常绘制，键盘输入可用 | 截图在会话里；本仓库提交 `4efaf0f` 的 `app/` |
| 前缀写时复制（D13） | 通过：8 种操作全部成功，共享仓库文件内容、权限、xattr 都不变 | 本仓库 `tools/cowtest/`，见 12 §3.3 |
| 系统调用探针 | `futex_waitv` 被 app seccomp 杀掉（fsync 在 Android app 里用不了）；`userfaultfd`、`memfd_create`、`pidfd_open` 可用；`set_robust_list` 被杀 | 本仓库 `tools/probe/syscalls.c`，M1 宿主（提交 `4efaf0f` 的 `app/`）的 `amphora.exec` 钩子 |

**补丁清单**（相对 Valve tag，按用途）：

| 用途 | 提交 | 说明 |
|---|---|---|
| Bionic 编译 | fsync 在 Android 上关掉（Bionic 没有 `shm_open`）；`M_PERTURB` 缺失时跳过；`--disable-amd_ags_x64`（要 libdrm） | 首次编译的全部 C 错误 |
| Valve 的 bug | `uffd_tmp_defs.h` 在所有非 x86_64 架构上把 `__NR_userfaultfd` 定成 i386 的 374；arm64 上这个号不存在，app seccomp 直接杀进程 | 可以报给 Valve |
| SELinux execmod | 无重定位的可执行节先设 R+X 再重定位（旧仓库 `a177c89b75`）；仍被拒时把页搬到匿名内存 | 不修则 ntdll.dll 映射失败 |
| wineandroid | Twaik Yont 的拆进程系列 34 个 + Rémi Bernon 1 个 + 旧仓库对 Proton win32u ABI 的 5 个适配；旧仓库的 Box64 / WSI 改动全部没带（D5、D14） | 全部干净 cherry-pick |
| 薄前缀 | wineserver `open_fd()` 写时复制（`WINE_COW_STORE`）；`SetFileAttributesW` 按 Windows 的做法带 `FILE_WRITE_ATTRIBUTES` 打开 | 后者不改的话，改属性会经链接改到仓库文件的权限和 xattr |

WinNative 那个 Android/Arm64EC 大提交（`986479030a`）没有整体带入：里面的 esync、关 fsync、写死盘符、FLS 回调绕过等是混在一起的；以上只按编译错误和设备上的实际失败逐个加。

**还没做 / 已知问题**：

- 桌面尺寸和 DPI 不对：窗口最大化只占屏幕约四分之一，记事本编辑区字体很小。
- 前缀写时复制的残余缺口：Wine 不检查句柄权限，程序如果用没带 `FILE_WRITE_ATTRIBUTES` 的句柄改属性，仍会改到仓库文件（Windows 上这样调用会失败，正常程序不会这么做）。删除和改名现在会先复制再删 / 改，浪费一次复制，以后可以只删 / 改链接本身。
- 只链了 FreeType：gnutls（`bcrypt` 报 no encryption support）、PulseAudio、Vulkan 等按里程碑加。
- 构建在 [`amphora-engine`](https://github.com/amphora-dev/amphora-engine)（BuildStream；CI 构建 `runtime.bst`，main 上发 Release）。Wine 分支已推到 `amphora-dev/proton-wine`（2026-11-08）。
- 安装包 884 MiB（PE 只部分 strip），还没瘦身。
- 新建的普通 Wine 前缀 688 MiB、897 个文件（Wine 把内置 DLL 复制进 `system32`），薄前缀的模板和仓库（D13）还没做。

## 设备上的测试状态

- Y700 `HA262AAH`：装了 `app.amphora.next`（M1 宿主，debuggable，targetSdk 28），files 目录里有 Wine 和一个 GUI 前缀；测试用的临时前缀、仓库和探针都已删除。旧的 `app.amphora` 没动。
