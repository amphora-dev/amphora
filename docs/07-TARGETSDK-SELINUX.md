# 07 - targetSdk 与 SELinux exec 约束研究

> 对 Android 10+ W^X 策略如何限制 `filesDir` exec 的深度分析，以及盖世游戏 (`com.xiaoji.egggame`) 如何在 targetSdk=36 下绕过。
> 最后更新: 2026-08-10 · 验证设备: Lenovo TB322FC, Android 16 (API 36), Adreno 830。
>
> **状态：已落地。** Amphora 的 `SDK_TARGET` 已从 28 升至 36，§4 描述的 linker64 + `libamphora-exec.so`
> 方案已实现于 `d9a3090`..`75ec9a5`（2026-08）。本文档保留 SELinux 分析与盖世游戏逆向作为设计依据。

---

## 1. 问题陈述

Amphora 的 `SDK_TARGET = 36`（`ConventionHelpers.kt`）。历史上曾为 28，以回避 targetSdk≥29 的 SELinux `execute_no_trans` neverallow；本文档分析了该约束，并记录了最终采用的 linker64 路由方案（§4）。

盖世游戏 `com.xiaoji.egggame` 的 `targetSdk=36`，`minSdk=29`，但能在同一设备上从 `filesDir` 成功 exec wine + wineserver + 多个 wine 子进程。

本文档回答：盖世游戏怎么做到的？Amphora 能否复用同样方法？

---

## 2. SELinux exec 权限模型

### 2.1 execve 的 SELinux 检查链

当进程调用 `execve(file)` 时，SELinux 按以下顺序检查：

```
1. 检查 execute 权限 (source_domain → file_type:file execute)
   ├─ 拒绝 → EACCES
   └─ 允许 → 继续

2. 检查 execute_no_trans 权限 (source_domain → file_type:file execute_no_trans)
   ├─ 允许 → exec 成功，进程留在当前域
   └─ 拒绝 → 继续

3. 查找 type_transition 规则 (source_domain, file_type, process → new_domain)
   ├─ 找到 → 检查 entrypoint (new_domain → file_type:file entrypoint) + transition (old → new:process transition)
   │   ├─ 允许 → exec 成功，进程切到 new_domain
   │   └─ 拒绝 → EACCES
   └─ 未找到 → EACCES ← 关键：不是"留在原域"
```

**关键纠正**：之前 Amphora 注释中的理解"没有 `execute_no_trans` 就留在当前域"是**错误的**。当 `execute_no_trans` 被 neverallow 且没有匹配的 `type_transition` 时，execve 返回 **EACCES**。

### 2.2 Android 各版本的 neverallow 规则

来源：AOSP `platform/system/sepolicy` `private/app_neverallows.te`，逐版本对比。

核心规则（注释 `b/112357170`）：

```te
# Block calling execve() on files in an apps home directory.
# This is a W^X violation (loading executable code from a writable
# home directory). For compatibility, allow for targetApi <= 28.
neverallow {
  all_untrusted_apps
  -untrusted_app_25
  -untrusted_app_27
  [-untrusted_app_29]   # ← 仅 Android 11/12 和 Android 16 有此豁免
  -runas_app
} { app_data_file privapp_data_file }:file execute_no_trans;
```

各版本差异：

| Android 版本 | `untrusted_app_29` 豁免 | `untrusted_app` (≥30) 被 neverallow |
|---|---|---|
| 10 (API 29) | ❌ 不存在此域 | ✅ |
| 11 (API 30) | ✅ 豁免 | ✅ |
| 12 (API 31) | ✅ 豁免 | ✅ |
| 13 (API 33) | ❌ 豁免移除 | ✅ |
| 14-15 | ❌ | ✅ |
| 16 (API 36) | ✅ 重新豁免 | ✅ |

**结论**：targetSdk ≥ 30 的应用在所有 Android 版本上都被 neverallow 禁止 `execute_no_trans` on `app_data_file`。

### 2.3 设备实测验证

在 Lenovo TB322FC (Android 16) 上读取 `/system/etc/selinux/plat_sepolicy.cil`：

```
;; 所有 untrusted_app 变体都能 execute app_data_file
(allow untrusted_app_all app_data_file (file (ioctl read getattr lock map execute open watch watch_reads)))

;; 只有老域有 execute_no_trans
(allow untrusted_app_25 app_data_file (file (execute_no_trans)))
(allow untrusted_app_27 app_data_file (file (execute_no_trans)))
;; untrusted_app_29 在 Android 16 也有（豁免）
;; untrusted_app (≥30) 没有 execute_no_trans

;; neverallow 禁止 targetApi >= 29 的域 execute_no_trans
(neverallow {
  all_untrusted_apps
  -untrusted_app_25
  -untrusted_app_27
  -untrusted_app_29
  -runas_app
} { app_data_file privapp_data_file }:file execute_no_trans;)

;; app_data_file 没有任何 type_transition 规则
;; → execute_no_trans 被 neverallow + 无 type_transition = EACCES
```

### 2.4 `execute` vs `execute_no_trans` vs `map execute`

| 权限 | 作用 | untrusted_app (≥30) 是否有 |
|---|---|---|
| `execute` | 允许将文件加载到内存执行（execve 第一步） | ✅ 有（via `untrusted_app_all`） |
| `execute_no_trans` | 允许 execve 成功且不切域 | ❌ neverallow |
| `map` + `execute` | 允许 `mmap(PROT_EXEC)` 加载 .so | ✅ 有（via `untrusted_app_all`） |

**关键洞察**：`dlopen()` 走的是 `mmap(PROT_EXEC)`，需要 `map execute`，**不需要** `execute_no_trans`。因此 targetSdk ≥ 29 下 `dlopen` filesDir 里的 .so 仍然可以工作。被阻止的只是 `execve()`。

---

## 3. 盖世游戏的绕过方案

### 3.1 基本信息

```
包名: com.xiaoji.egggame
targetSdk: 36, minSdk: 29, compileSdk: 37
Wine: Proton 11.0 arm64x (原生 ARM64 + aarch64-unix)
架构: 本地 Wine 模拟器（非云端）
```

### 3.2 运行时证据

```
:pcengine 进程
  SELinux: u:r:untrusted_app:s0:c103,c257,c512,c768
  exe: /system/bin/app_process64 (标准 app 进程)

wineserver (PID 20178, PPid=1)
  SELinux: u:r:untrusted_app:s0 (同域，未切换)
  exe: /apex/com.android.runtime/bin/linker64  ← 关键
  cmdline: /data/data/com.xiaoji.egggame/files/usr/opt/wine_proton11.0-arm64x/.../wineserver

wine 子进程 (PID 20182, PPid=1)
  SELinux: u:r:untrusted_app:s0
  exe: /apex/com.android.runtime/bin/linker64  ← 关键
  cmdline: /data/data/.../wine C:\windows\system32\services.exe

SELinux 模式: Enforcing
AVC denial: 无（仅 /dev/input getattr 被拒，无关）
wine 二进制标签: u:object_r:app_data_file:s0 (标准 filesDir)
```

### 3.3 核心机制：通过 linker64 exec

**不是** `execve("/data/data/.../wine")`（会 EACCES），而是：

```
execve("/apex/com.android.runtime/bin/linker64", ["/data/data/.../wine", ...args])
```

SELinux 检查链：
1. `execute` on `linker64`（`system_file` 类型）→ ✅ 允许
2. `execute_no_trans` on `linker64` → ✅ 允许（neverallow 只覆盖 `app_data_file`，不覆盖 `system_file`）
3. linker64 启动后 `mmap(PROT_EXEC)` wine 二进制 → ✅ 允许（`map execute` on `app_data_file`）

### 3.4 wine launcher stub

盖世游戏的 `wine` 二进制只有 **9832 字节**，是一个 dlopen launcher：

```c
// 反汇编还原的 main() 逻辑
int main(int argc, char *argv[]) {
    init_reserved_areas();

    // get_self_exe() 是 stub，返回 NULL（不读 /proc/self/exe）
    char *exe_path = get_self_exe();  // → NULL

    // 用 argv[0] 定位 ntdll.so
    void *handle = try_dlopen(exe_path);      // exe_path=NULL → 失败
    if (!handle)
        handle = try_dlopen(argv[0]);         // 用 argv[0] = wine 路径

    // try_dlopen 的路径查找逻辑：
    // 1. realpath_dirname(argv[0])  → .../arm64-v8a/lib/wine/
    // 2. remove_tail(dir, "/loader") → .../arm64-v8a/lib/wine
    // 3. 尝试以下路径（依次）：
    //    a. <base>/dlls/ntdll/ntdll.so         (构建树布局)
    //    b. <dir>/../lib/wine/aarch64-unix/ntdll.so  (安装布局)
    //    c. <dir>/lib/wine/aarch64-unix/ntdll.so     (扁平布局)

    void *(*wine_main)(int, char**) = dlsym(handle, "__wine_main");
    if (!wine_main) {
        fprintf(stderr, "wine: __wine_main function not found in ntdll.so\n");
        exit(1);
    }
    wine_main(argc, argv);  // 直接函数调用，不 exec
}
```

**关键设计**：`get_self_exe()` 是 stub，不读 `/proc/self/exe`。因为通过 linker64 exec 时 `/proc/self/exe` 指向 linker64 而非 wine，所以 launcher 用 `argv[0]` 定位自身路径。这避免了 Termux 需要给数千个包打 `/proc/self/exe` 补丁的问题。

### 3.5 ELF 依赖

```
wine launcher NEEDED:
  libandroid-spawn.so   ← 提供 posix_spawn()（wine 创建子进程用）
  libdl.so
  libc.so
```

`libandroid-spawn.so` 是 Termux 项目的库（[源码](https://github.com/termux/termux-packages/tree/master/packages/libandroid-spawn)），提供 `posix_spawn()` 的用户态实现。其 `posix_spawn` 内部调用 `execve()`。

### 3.6 libvfs.so 的 execve 拦截器

**关键发现**：盖世游戏的 `libvfs.so`（源码路径 `WinEmuKernel/lib/src/main/cpp/vfs/src/syscall_hooks.cpp`）实现了 execve hook。

反汇编还原的逻辑（`winemu_mmap` 函数内，地址 `0x515a4`-`0x518c0`）：

```c
// 伪代码还原
int hooked_execve(const char *path, char *const argv[], char *const envp[]) {
    // 系统路径白名单 - 可直接 exec
    static const char *safe_prefixes[] = {
        "/odm/", "/bin/", "/wine-preloader",
        "/apex/", "/proc/", "/sbin/",
        "/system/", "/system_ext/", "/vendor/"
    };

    for (auto prefix : safe_prefixes) {
        if (starts_with(path, prefix)) {
            // 系统路径 → 直接 execve（SELinux 允许）
            return syscall(SYS_execve, path, argv, envp);  // 0x51804
        }
    }

    // filesDir 路径 → 包装成 linker64 调用
    // 构建新 argv: [path, ...original_argv]
    char **new_argv = build_argv(path, argv);  // 0x5184c-0x518a8
    return syscall(SYS_execve, "/system/bin/linker64", new_argv, envp);  // 0x518b4
}
```

rodata 字符串证据：
```
linker64
/apex/com.android.runtime/bin/linker64
/system/bin/linker64
Failed to open linker64
/proc/self/fd/{}
/proc/self/cmdline
/proc/self/maps
```

libvfs.so 的 hook 机制：
- 不使用 LD_PRELOAD（`:pcengine` 进程环境无 LD_PRELOAD）
- 通过 GOT/PLT hook 或类似机制拦截 execve 调用
- 使用 raw `syscall()` 发系统调用（绕过 libc wrapper）
- 导入 `dlsym`、`dlopen`、`mprotect`、`syscall` 等

### 3.7 完整 exec 链路

```
Java 层 (:pcengine, targetSdk=36)
  │
  │  execve("/apex/.../linker64", ["/data/data/.../wine", ...])
  │  ← linker64 是 system_file，execute_no_trans 允许
  │
  ▼
wine launcher (9KB ELF, 被 linker64 加载到内存 via mmap PROT_EXEC)
  │
  │  main():
  │    get_self_exe() → NULL (stub)
  │    try_dlopen(argv[0]) → dlopen ntdll.so (via map execute, 允许)
  │    dlsym("__wine_main") → 函数指针
  │    __wine_main(argc, argv) → 直接调用
  │
  ▼
wine 运行 (ntdll.so)
  │
  │  创建子进程 (wineserver, services.exe 等):
  │    posix_spawn(path, ...)  ← ntdll.so 调用
  │      ↓
  │    libandroid-spawn.so 的 posix_spawn:
  │      vfork() / fork()
  │      execve(path, argv, env)  ← 最终调用 execve
  │        ↓
  │    libvfs.so hook 拦截:
  │      if (path 是系统路径) → execve(path) 直接调用
  │      else → execve("/system/bin/linker64", [path, ...])  ← 包装
  │
  ▼
wineserver / wine 子进程
  exe → /apex/com.android.runtime/bin/linker64
  cmdline → /data/data/.../wine C:\windows\system32\services.exe
  SELinux → untrusted_app (不切域)
```

---

## 4. Amphora 的实现

Amphora 已采用 linker64 路由 + LD_PRELOAD execve 拦截器（`libamphora-exec.so`），`SDK_TARGET = 36`。
实现横跨 Java 启动层与 native 拦截层，两条路线（box64 / FEXCore）均覆盖。

### 4.1 Java 启动层：`AppDataExecutableLauncher`

`ProcessHelper.prepareCommandForAppData()` 在 `exec` 前调用 `AppDataExecutableLauncher.prepare()`，
检测 `command[0]` 是否为 app filesDir 下的 AArch64 ELF，若是则前插 `/system/bin/linker64`：

```
GuestProgramLauncherComponent.execGuestProgram()
  → ProcessHelper.prepareCommandForAppData(command)
    → AppDataExecutableLauncher.prepare(filesDir, command)
      若 command[0] ∈ filesDir 且为 AArch64 ELF：
        wrapped = ["/system/bin/linker64", command[0], ...command[1:]]
  → ProcessHelper.exec(prepared)
    → forkAndExec()
      → execve("/system/bin/linker64", ["/data/data/.../box64", ...])
```

检测逻辑（`AppDataExecutableLauncher.java`）：
- 读 ELF 头前 20 字节，校验 `e_machine == EM_AARCH64 (183)`
- `isAppDataPath()` 用 canonical path 判断是否在 filesDir 下
- 已是 `/system/bin/linker64` 开头的命令幂等返回（不二次包装）
- x86/x86_64 ELF 不包装（交给 box64 翻译，不直接 exec）
- 非 ELF 文件（脚本）不在此层处理（交由 `libamphora-exec.so` 的 shebang 重写）

### 4.2 Native 拦截层：`libamphora-exec.so`

Java 层只路由首个 ELF。Box64/Wine 运行后继续 `fork` + `execve` 子进程
（wineserver、services.exe 等），这些调用仍指向 app_data_file，需要拦截。

`libamphora-exec.so`（`core/native/src/main/cpp/winlator/amphora_exec.c`，派生自
[termux-play-store/termux-exec](https://github.com/termux-play-store/termux-exec)）
通过 LD_PRELOAD 拦截 `execve`/`execvpe`/`execl`/`fexecve` 等全部 exec 系列函数：

```c
__attribute__((visibility("default"))) int execve(
    const char *executable_path, char *const argv[], char *const envp[]) {
  // 1. 读取文件头，判断 ELF / shebang
  // 2. 非 native ELF（x86 等）→ raw_execve 原样放行
  // 3. shebang 脚本 → 用 PREFIX 重写解释器路径
  // 4. native AArch64 ELF 且路径在 AMPHORA_EXEC_ROOT/LEGACY_ROOT 下：
  //      重写 argv = [argv[0], executable_path, ...original]
  //      executable_path = "/system/bin/linker64"
  // 5. 注入 AMPHORA_EXEC__PROC_SELF_EXE=<原路径>，供 readlink("/proc/self/exe") 返回
}
```
关键设计：
- **`/proc/self/exe` 修正**：execve 包装前在 envp 注入 `AMPHORA_EXEC__PROC_SELF_EXE`，
  拦截器同时 hook `readlink`/`realpath`，对 `/proc/self/exe` 返回该值而非 linker64 路径。
  这解决了盖世游戏用 `get_self_exe()` stub 绕过、Termux 用 per-package 补丁绕过的问题，
  Amphora 无需改任何被 exec 的二进制。
- **`AMPHORA_EXEC_ROOT` / `AMPHORA_EXEC_LEGACY_ROOT`**：filesDir 的 canonical 路径
  （`/data/user/<id>/...`）与 legacy 别名（`/data/data/<pkg>/files`）。Box64 会 canonicalize
  路径，两者都必须匹配，否则 canonical 后的路径逃逸拦截。
- **`AMPHORA_EXEC_OPTOUT`**：环境变量逃生舱，跳过拦截走 raw execve。
- **系统路径白名单**：`/system/bin/sh` 等系统路径直接放行，不包装。

### 4.3 LD_PRELOAD 注入

`ProcessHelper.configureAppDataExecEnvironment()` 在进程启动前：
1. 设置 `AMPHORA_EXEC_ROOT`、`AMPHORA_EXEC_LEGACY_ROOT`、`AMPHORA_EXEC__PROC_SELF_EXE`
2. 找到 `libamphora-exec.so`（在 APK 的 nativeLibraryDir 下）
3. 前插到 `LD_PRELOAD`（`libamphora-exec.so <原有 preload>`）

这保证 Box64/Wine 及所有 fork 出的子进程都加载拦截器。

### 4.4 两条路线的差异

| | box64 路线 | FEXCore 路线 |
|---|---|---|
| 首次 exec | Java `AppDataExecutableLauncher` 包装 linker64 | 同左 |
| 后续 execve | `libamphora-exec.so` 拦截 → linker64 包装 | 同左 |
| `/proc/self/exe` | `AMPHORA_EXEC__PROC_SELF_EXE` env + readlink hook | 同左 |
| 改造量 | Java 包装 + native 拦截器（已实现） | 同左，无需额外拦截器 |

§4.3 原先估计 FEXCore 路线需要"复杂拦截器"，实际上 `libamphora-exec.so` 统一覆盖了
两条路线的所有 execve 调用——原生 ARM64 wine 的 `posix_spawn`/`fork`+`execve` 同样被拦截。

### 4.5 性能对比

| | 直接 exec (targetSdk 28，已弃用) | linker64 exec (targetSdk 36，当前) |
|---|---|---|
| 启动开销 | 内核读 PT_INTERP → 加载 linker64 → 加载二进制 | 直接加载 linker64 → 加载二进制 |
| 运行时性能 | 无差异 | 无差异 |
| 内存 | 无差异 | 无差异 |
| SELinux 合规 | 依赖 `untrusted_app_27` 域的 `execute_no_trans` | 通过 `system_file` 的 `execute_no_trans` |

### 4.6 `memfd_create` + `fexecve` 方案（不可行）

`memfd_create` 创建的文件标签是 `appdomain_tmpfs`，AOSP 策略只有 `execute` 但没有
`execute_no_trans` allow 规则，也没有 `type_transition`，因此 `fexecve(memfd_fd)` 同样会 EACCES。

---

## 5. 参考来源

- AOSP SELinux 策略源码:
  - [Android 10 app_neverallows.te](https://android.googlesource.com/platform/system/sepolicy/+/refs/tags/android-10.0.0_r41/private/app_neverallows.te)
  - [Android 11 app_neverallows.te](https://android.googlesource.com/platform/system/sepolicy/+/refs/tags/android-11.0.0_r39/private/app_neverallows.te)
  - [Android 13 app_neverallows.te](https://android.googlesource.com/platform/system/sepolicy/+/refs/tags/android-13.0.0_r74/private/app_neverallows.te)
- [Android 10 行为变更: targetSdk 29+](https://developer.android.com/about/versions/10/behavior-changes-10)
- [Termux #1072: No more exec from data folder on targetAPI >= Android Q](https://github.com/termux/termux-app/issues/1072)
- [Termux #3653: target sdk 33 or 34](https://github.com/termux/termux-app/issues/3653)
- [Termux termux-exec-package](https://github.com/termux/termux-exec-package) / [DeepWiki](https://deepwiki.com/termux/termux-exec-package)
- [Termux libandroid-spawn posix_spawn.cpp](https://github.com/termux/termux-packages/blob/master/packages/libandroid-spawn/posix_spawn.cpp)
- [Termux wine-stable build.sh](https://github.com/termux/termux-packages/blob/1ae8d0ea5/x11-packages/wine-stable/build.sh)
- [StackOverflow: SELinux blocks execution of native executable on Android](https://stackoverflow.com/questions/70741783/selinux-blocks-execution-of-native-executable-on-android)
- 设备实测: Lenovo TB322FC, Android 16 (API 36), `plat_sepolicy.cil` + 动态进程分析
