# 09 · AIO Vulkan PresentModes 现状排查

> 状态：**开放排查（已暂停）** · 记档时间：2026-09-16  
> 关联文档：[`03-ASSET-MANIFEST.md`](03-ASSET-MANIFEST.md)（AIO 2.1.0 资产）、[`04-WINEANDROID-DISPLAY.md`](04-WINEANDROID-DISPLAY.md)（窗口与显示系统）、[`05-AHB-IMPORT-PRESENT.md`](05-AHB-IMPORT-PRESENT.md)（Vulkan 零拷贝呈现）。  
> 代码与资产版本：amphora 仓库 `main` 分支；proton-wine 仓库 `proton_11.0` @ `05ca3a658db`；已发布对应 WCP 资产 `Proton-11.0-05ca3a658-x86_64.wcp`。

---

## 0. 核心背景与现象（通俗说明）

### 什么是 AIO Graphics Test 与 PresentMode 问题？
AIO Graphics Test 是 WinNative/Amphora 常用的综合图形基准测试程序，其中 `--cube vk` 是一个 Vulkan 立方体旋转测试。
1. **报错根因**：Android 平台的 Adreno 驱动在物理设备上默认只支持 `FIFO`（垂直同步队列模式），而 AIO 的 Vulkan 测试程序默认要求 `IMMEDIATE`（立即呈现模式）。因为驱动列表里没有测试程序想要的那一档，测试程序直接报错弹窗：`Present mode unsupported` 并退出。
2. **修复尝试（Proton-Wine 侧）**：为了消除此弹窗，我们在底层 `win32u` 模块中向应用程序汇报系统支持 `FIFO`、`MAILBOX` 和 `IMMEDIATE`，并在创建交换链时，将不支持的模式自动重映射（remap）为 `FIFO`。
3. **真机排查现状（HA262）**：更新 WCP 运行时包后，`Present mode unsupported` 的弹窗确实消失了，但测试窗口黑屏。经排查真机日志，发现此时程序根本还没走到 Vulkan 初始化，而是在加载系统核心动态库 `kernel32.dll` 时因为权限（SELinux `execmod` 限制）报错 `c0000135` 提前退出了。
4. **当前结论**：黑屏并非 Vulkan 模式重映射本身破坏了出画链路，而是由于 Wine 基础环境在当前测试配置下未能正常加载系统库。该问题已于 2026-09-16 记录并暂时挂起，待后续环境稳定后单独重测。

---

## 1. 已落地的代码与资产

| 项目 | 涉及模块与位置 | 功能说明 |
|---|---|---|
| **调试参数支持** | amphora: `LaunchSpec.exeArgs` → `WineLaunchCommands.buildWineProgramCommand` | 支持通过 `adb shell am start --es app.amphora.debug.WINE_ARGS '...'` 传入任意启动参数 |
| **官方测试程序** | `core/content/.../winnative/Graphics-Test-{32,64}bit.exe` | 预编译 AIO 2.1.0 官方基准测试可执行程序 |
| **PresentModes 补丁** | proton-wine: `068948b16ef` + `05ca3a658db` | 在 win32u 中兼容模式汇报，并将 IMMEDIATE/MAILBOX 重定向为 FIFO |
| **构建门禁检查** | imagefs: `ci/wine/check-present-modes-thunk.py` | 确保编译产物中的 PresentModes 调用严格走驱动函数表 |
| **运行时 WCP 发布** | `Proton-11.0-05ca3a658-x86_64.wcp` | 包含上述模式修复的完整运行时包，内部驱动版本（version 48）对齐一致 |

---

## 2. 架构层面对照与分析

1. **X11 遗留模式对照**：
   - 传统的 `winex11.drv` 自身并不实现 PresentModes 转换，而是简单直通；
   - 当前在 `win32u` 中的假报与模式重映射是全驱动生效的，尚未加上 `amphora_wsi_wanted()` 的宿主特定门禁。
2. **零拷贝路径独立性**：
   - Amphora 的 Vulkan AHB 零拷贝路径（`amphora_ahb_sc.inc`）内部并不依赖特定的 `presentMode`，因此重映射逻辑本身不会破坏 AHB 导入交换链。

---

## 3. HA262 真机实测现象记录（2026-09-16）

启动命令：
```bash
adb shell "am force-stop app.amphora; am start -n app.amphora/.MainActivity \
  --ez app.amphora.debug.WINE_SMOKE true \
  --es app.amphora.debug.WINE_EXE 'C:/ProgramData/Microsoft/Windows/Graphics-Test-64bit.exe' \
  --es app.amphora.debug.WINE_ARGS '--cube vk --vsync --autoclose'"
```

**实测日志与观测结果**：
- **宿主 WSI 代理通道**：正常建立（`wsi-sc-*.sock` 服务端启动成功）；
- **交换链创建（AHB_SC）**：**未触发**（没有打印 `AHB_SC create images`）；
- **Wine 底层日志**：出现 `wine: could not load kernel32.dll, status c0000135`；
- **系统安全日志**：伴随 `avc: denied { execmod }` 拦截记录，指向运行时中的动态链接库；
- **屏幕表现**：黑底，底部停留 guest 命令行调试覆盖层。

---

## 4. 当前核心假设与后续排查建议

1. **首要假设（阻断根本原因）**：
   测试程序在调用 Vulkan 图形驱动（`ANDROID_VulkanInit`）之前，就已经因为 `kernel32.dll` 无法加载（错误码 `c0000135`，可能由于 Android SELinux 内存执行限制 `execmod`）异常退出。这也是为什么底层完全没有进入交换链创建流程的原因。
2. **次要验证项（待前置问题解决后）**：
   当 Wine 能够稳定加载基础环境后，再重新核对 PresentModes 补丁是否能够顺利触发 `AHB_SC create images=... import=ok`。
3. **架构规范优化建议**：
   后续若继续启用 PresentModes 模式假报，建议将其严格限制在 `amphora_wsi_wanted()` 条件下，避免影响通用 win32u 行为。

> **当前处理**：排查已按计划叫停归档，不阻塞主干开发。OpenGL（`--cube gl`）与 DirectX 11（`--cube dx11`）立方体测试留待后续统一排查。
