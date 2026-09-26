# 08 · 开发者与 Agent 上手指南 (Agent Bootstrap)

> **接手须知**：任何新加入的开发者或 AI 协同 Agent，在阅读代码或修改前，**请务必先通读本文与项目根目录的 [`AGENTS.md`](../AGENTS.md)**。  
> 快速可执行配方参见：[`.cursor/skills/amphora-from-zero/SKILL.md`](../.cursor/skills/amphora-from-zero/SKILL.md)。  
> 当前项目进展与下一步工作：统一以 [`docs/02-TRACKING.md`](02-TRACKING.md) 末节「当前状态指针」及最新的 Git 提交历史为准。

---

## 0. 一句话定位

Amphora 是面向 Android 平台的模块化 Windows/Wine 模拟器。
- **现行显示真源**：采用纯 Kotlin 实现的 **wineandroid** 宿主体系（每个 Windows HWND 对应独立的 SurfaceView，通过 SurfaceFlinger 硬件合成），**彻底弃用**了 Winlator 遗留的 Java XServer/X11 架构。
- **真机基准环境**：官方日常验证设备为 Lenovo Y700 (TB322FC，Adreno 830，ADB 设备序列号 `HA262AAH`)。

---

## 1. 软件层次划分

理解本项目的分层架构，避免概念混淆：

1. **外层（桌面启动器）**：Amphora Desktop（负责壁纸、游戏图标网格、系统底栏以及参数配置；首发版本不接管 Android 系统的 `SECONDARY_HOME`）。
2. **中层（会话容器）**：每个运行的 Windows 程序运行在独立的 freeform 会话 Activity 中（默认即为 `WineAndroidSessionActivity`）。
3. **内层（窗口体系）**：通过 `wineandroid.drv` 驱动管理会话内的具体 Windows 窗口（HWND），通过原生 SurfaceView 挂载，不将每个内部子窗口做成 Android 系统任务。

---

## 2. 研发模块分工

为保持代码整洁并避免不同功能相互干扰，工程按领域划分为三大工作方向：

| 研发方向 | 核心职责 | 关联核心文档 |
|---|---|---|
| **A. 壳层显示与输入** | 窗口嵌套布局、Surface 生命周期、DPI 适配、letterbox 居中缩放、触控与软键盘输入 | [`04-WINEANDROID-DISPLAY.md`](04-WINEANDROID-DISPLAY.md) |
| **B. 3D 游戏渲染呈现** | Vulkan AHB 导入、交换链创建（CreateSwapchain）、零拷贝送显通道 | [`05-AHB-IMPORT-PRESENT.md`](05-AHB-IMPORT-PRESENT.md) |
| **C. 资产与基础设施** | imagefs 镜像制作、Proton WCP 编译打包、manifest 清单分发及本地覆盖 | [`03-ASSET-MANIFEST.md`](03-ASSET-MANIFEST.md)、[`07-DEV-PIN-OVERLAY.md`](07-DEV-PIN-OVERLAY.md) |

> **提示**：日常开发应保持聚焦，不要在同一个提交或分支中同时混合重构显示壳层与底层渲染管线，除非有明确的跨层联动需求。

---

## 3. 开发环境与设备

代码、构建、adb 都在同一台 Mac 上：仓库 `~/co/github/amphora`，兄弟仓 `../proton-wine`、`../imagefs`、`../content_manifest`。

| 设备 | adb 序列号 | 用途 |
|---|---|---|
| Lenovo Y700 (TB322FC, Adreno 830, Android 16) | `HA262AAH` | 日常基准机 |
| OnePlus 6T (Adreno 630, Android 15) | `5b1736c7` | 老 GPU / 老系统回归 |

一台设备同一时间只归一个 agent 装包、跑冒烟。

---

## 4. 从零初始化仓库

在全新环境下克隆并准备工程：

```bash
# 1. 克隆代码仓库
git clone git@github.com:amphora-dev/amphora.git
cd amphora

# 2. 必须初始化递归子模块（编入 adrenotools 等底层驱动库，否则 native 编译将报错）
git submodule update --init --recursive

# 3. 创建专属开发分支进行工作
git fetch origin
git switch -c wip/<topic> origin/main

# 4. 配置代码质量门禁（可选：执行脚本或运行任意 ./gradlew 任务均会自动激活 .githooks）
bash scripts/setup-git-hooks.sh
```

> **门禁说明**：项目配置了自动化 Git 钩子（`.githooks/`）。在执行 `git commit` 时会自动校验暂存区 Kotlin 格式，在执行 `git push` 前会自动运行 `./gradlew spotlessCheck :app:testDebugUnitTest :app:lintDebug` 对齐 CI。若格式检查不通过，运行 `./gradlew spotlessApply` 即可一键自动修复。


---

## 5. 开工前必读推荐顺序

在着手编写或修改代码前，建议按以下顺序花费 10 分钟建立全局认知：

1. **[`AGENTS.md`](../AGENTS.md)**：开发红线、架构底线与常见避坑禁令。
2. **[`01-ARCHITECTURE.md`](01-ARCHITECTURE.md)**：As-Built 现行工程架构、数据流向与模块划分。
3. **[`02-TRACKING.md`](02-TRACKING.md)**：进度跟踪记录与当前阶段任务真源。
4. **[`04-WINEANDROID-DISPLAY.md`](04-WINEANDROID-DISPLAY.md)**：窗口树、SurfaceView 绑定时机与输入通道。
5. **[`05-AHB-IMPORT-PRESENT.md`](05-AHB-IMPORT-PRESENT.md)**：Vulkan 零拷贝呈现机制（若涉及游戏渲染或 DXVK）。
6. **[`07-DEV-PIN-OVERLAY.md`](07-DEV-PIN-OVERLAY.md)**：本地替换 WCP 或运行时组件的开发态调试技巧。

---

## 6. 已落地、不要重做

壳层窗口树 / Surface 生命周期 / 输入 / IME（`docs/04`）、AHB 零拷贝 swapchain（`docs/05`）都已在真机闭环。不变量见 `AGENTS.md`「出画不变量」，细节与历史 bug 见对应文档，不在此重复。

---

## 7. 日常编译与真机冒烟验证

### 8.1 构建 APK

```bash
# 在联调机或本地仓库执行
./gradlew :app:assembleDebug
# 编译产物位于: app/build/outputs/apk/debug/app-debug.apk
```

### 8.2 安装并运行冒烟测试

```bash
SERIAL=HA262AAH

# 安装应用
adb -s $SERIAL install -r app/build/outputs/apk/debug/app-debug.apk

# 清理历史日志并启动主页面
adb -s $SERIAL logcat -c
adb -s $SERIAL shell am start -n app.amphora/.MainActivity
```

**关键日志过滤口径**：
- 观察窗口 Surface 状态：`WineAndroidDesktop|WineAndroidHostBridge|defer first register|registerSurface|setFixedSize|surfaceChanged`
- 观察输入与键盘：`WineAndroidDesktop.*motion|keyboard hwnd=|key hwnd=|IME composing|IME unicode`
- 观察 3D 渲染与交换链：`WineAndroidWsi|AHB_SC create images=|Present frame=`

---

## 8. 核心技术文档导航

- [`AGENTS.md`](../AGENTS.md) — 开发纪律、宿主禁令与代码门禁
- [`01-ARCHITECTURE.md`](01-ARCHITECTURE.md) — 现行工程架构与启动时序真源
- [`02-TRACKING.md`](02-TRACKING.md) — 开发进度、历史里程碑与当前状态指针
- [`03-ASSET-MANIFEST.md`](03-ASSET-MANIFEST.md) — 运行时资产清单、分包决策与 SHA 锁
- [`04-WINEANDROID-DISPLAY.md`](04-WINEANDROID-DISPLAY.md) — 宿主窗口树、SurfaceView 生命周期与输入系统
- [`05-AHB-IMPORT-PRESENT.md`](05-AHB-IMPORT-PRESENT.md) — Vulkan AHB 零拷贝游戏渲染机制与验收证据
- [`06-ENVIRONMENT.md`](06-ENVIRONMENT.md) — 开发、编译环境与远程 ADB 调试搭建指南
- [`07-DEV-PIN-OVERLAY.md`](07-DEV-PIN-OVERLAY.md) — 开发态组件覆盖层（`dev_pins.json`）配置方法
- [`09-AIO-VK-PRESENTMODES-STATUS.md`](09-AIO-VK-PRESENTMODES-STATUS.md) — AIO Vulkan 呈现模式排查记录与假设
