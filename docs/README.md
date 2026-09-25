# Amphora 文档导航地图

> **提示**：为消除文档冗余与断层，本项目文档已完成全新连续编号（`01` ~ `09`）。  
> **日常开发只查阅根目录下的【核心真源】**；历史调研、立项 RFC 及专题研究已统一收纳归档至 [`research/`](research/)。

---

## 1. 核心开发与设计真源（01 ~ 09 连续编号）

| 编号与文档 | 核心职责 | 适用场景 |
|---|---|---|
| [`01-ARCHITECTURE.md`](01-ARCHITECTURE.md) | **系统架构设计与数据流向** | 了解模块分工（`:app` / `:feature` / `:core:engine` / `:core:ui` 等）与依赖反转 (DIP) 规则 |
| [`02-TRACKING.md`](02-TRACKING.md) | **全项目唯一进度真源**（末节「当前状态指针」） | 确认当前开发进度、已关门项、下一步排期 |
| [`03-ASSET-MANIFEST.md`](03-ASSET-MANIFEST.md) | **组件资产与 SHA-256 锁** | 查看 Rootfs、Proton、Box64、DXVK 等外部二进制资产版本与下载校验 |
| [`04-WINEANDROID-DISPLAY.md`](04-WINEANDROID-DISPLAY.md) | **WineAndroid 宿主窗口与渲染（综合真源）**<br>*(已整合原 12、16、17、18)* | 开发/排查 2D 桌面、窗口树布局（WindowGroup/visible_rect）、Surface 生命周期、DPI 缩放、键盘/输入法 IME |
| [`05-AHB-IMPORT-PRESENT.md`](05-AHB-IMPORT-PRESENT.md) | **Vulkan AHB 零拷贝游戏渲染（综合真源）**<br>*(已整合原 13、14)* | 开发/排查 3D 游戏出画、`amphora_wsi` 桥接、AHB 导入 CreateSwapchain、Present≥50 流程 |
| [`06-ENVIRONMENT.md`](06-ENVIRONMENT.md) | **开发与测试环境指南** | 配置本地/云端编译环境、ADB 连机（Tailscale）、真机冒烟测试命令 |
| [`07-DEV-PIN-OVERLAY.md`](07-DEV-PIN-OVERLAY.md) | **开发态本地临时换包指南** | 在真机调试时临时覆盖特定版本的 Proton/Box64（`dev_pins.json` 覆盖层） |
| [`08-AGENT-BOOTSTRAP.md`](08-AGENT-BOOTSTRAP.md) | **开发者与协作 Agent 上手指南** | 新人从零上手、多 Bot 协同提交规范、避坑禁令与工作流配方 |
| [`09-AIO-VK-PRESENTMODES-STATUS.md`](09-AIO-VK-PRESENTMODES-STATUS.md) | **AIO Vulkan PresentModes 开放排查记录** | 跟踪 AIO Graphics Test `--cube vk` 黑屏排查与 win32u 假报分析（当前已暂停） |

---

## 2. 专题调研与早期归档（按需查阅，见 `docs/research/`）

此类文档记录了立项初期的技术调研、逆向分析及可行性预研，作为历史背景参考，**不作为当前开发真源**：

- [`00-RESEARCH.md`](research/00-RESEARCH.md)：WinNative 初期研究基石
- [`01-RFC.md`](research/01-RFC.md)：RFC-001 Amphora 项目立项与核心决议（D1–D9）
- [`02-SCAFFOLD.md`](research/02-SCAFFOLD.md)：早期脚手架工程搭建记录
- [`07-TARGETSDK-SELINUX.md`](research/07-TARGETSDK-SELINUX.md)：targetSdk 36 与 Android SELinux `exec` 限制专项调研
- [`08-EGGGAME-COMPARISON.md`](research/08-EGGGAME-COMPARISON.md)：盖世游戏 (egggame) 逆向对比研究
- [`09-FRAME-GENERATION-RESEARCH.md`](research/09-FRAME-GENERATION-RESEARCH.md)：移动端超分辨率与插帧技术调研
- [`09-VIRGL-PLAN.md`](research/09-VIRGL-PLAN.md)：基于 VirGL 的 Mali 硬件 OpenGL 方案
- [`10-OPENGL-ON-MALI.md`](research/10-OPENGL-ON-MALI.md)：Mali 芯片上的 OpenGL 运行方案研究
- [`11-ANDROID-NATIVE-VULKAN-PLAN.md`](research/11-ANDROID-NATIVE-VULKAN-PLAN.md)：早期 Android 原生 Vulkan 路径设想（现行路径见 `05`）
- [`RESEARCH-proton-wine-selfbuild.md`](research/RESEARCH-proton-wine-selfbuild.md)：Proton Wine (x86_64 Android Bionic) 自编译构建调研
- [`WRAPPER-BUILD.md`](research/WRAPPER-BUILD.md)：Vulkan Wrapper (Pipetto) 自编译说明

---

## 3. 推荐阅读路径

```
┌────────────────────────────────────────────────────────┐
│ 1. 快速上手：AGENTS.md → 08-AGENT-BOOTSTRAP.md          │
│    (了解团队规范、禁令、工作分支管理)                  │
└──────────────────────────┬─────────────────────────────┘
                           │
┌──────────────────────────▼─────────────────────────────┐
│ 2. 宏观认知：01-ARCHITECTURE.md → 02-TRACKING.md        │
│    (掌握系统模块设计，以 02 末节确认最新状态)          │
└──────────────────────────┬─────────────────────────────┘
                           │
       ┌───────────────────┴───────────────────┐
       ▼                                       ▼
【需要搞 2D 桌面 / 窗口 / 输入】        【需要搞 3D 渲染 / 游戏出画】
`04-WINEANDROID-DISPLAY.md`             `05-AHB-IMPORT-PRESENT.md`
```
