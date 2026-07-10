# RFC-001: Amphora 项目立项与架构

| | |
|---|---|
| **Status** | Draft - 待审核 |
| **作者** | (待定) |
| **日期** | 2026-07-10 |
| **依据** | [`00-RESEARCH.md`](00-RESEARCH.md) |

---

## 1. 名称与愿景

**Amphora**（古希腊双耳酒器）--承载 Wine 容器的容器。

**愿景**: 一个现代化、最小先跑通、长期工程化的 Android Wine 模拟器。以 WinNative 为参考（事实依据见 `00-RESEARCH.md`），但全新工程、干净架构，从 MVP 起步，模块边界为长期扩展铺路。

## 2. 背景

- 参考项目 WinNative 已完成深度分析：183k Kotlin/Java + 64k native，其中 ~125k 行为可砍的额外逻辑（Steam/Epic/GOG/.wcp/库/同步），骨架 ~50-60k 行。
- **已有资产**: `winlator-imagefs`（Bionic rootfs，41 包，7 轮 CI 全绿，已验证对齐）--rootfs 层已攻克。
- native 技术核心 `vk_renderer`（Vulkan X-server 渲染器，~9k 行）和 proot 可整块复用。
- 所有运行时二进制（box64/Wine/Turnip/DXVK）均有开放源码构建源。

## 3. 目标与非目标

### v0.1 (MVP) 目标
- 启动一个 Windows .exe，有画面（Vulkan 渲染）、有触屏映射、有音频
- 捆绑固定一套 rootfs + box64 + Wine + Turnip + DXVK（版本锁死）
- 极简 UI：选 exe → 分辨率 → 启动

### v0.1 非目标
- ❌ Steam / Epic / GOG 集成
- ❌ .wcp 运行时下载 / 多版本切换
- ❌ 游戏库 / 云存档 / 排行榜 / 快捷方式
- ❌ 多 flavor 品牌

### 长期目标
- 模块化：可加商店/库而不污染内核
- 可复现构建：所有外部二进制版本锁 + 哈希校验
- 稳定 native ABI
- 可切换 Wine/box64/DXVK 版本（通过可插拔 ContentSource）

## 4. 设计原则（吸取 WinNative 教训）

> WinNative 最大架构债: `XServerDisplayActivity` 9000 行把"渲染内核 + 进程启动 + Steam 逻辑"搅在一起。

1. **引擎/特性隔离** — Wine/渲染/输入是稳定内核（`engine`），特性层只依赖内核接口，**绝不反向依赖**。
2. **ContentSource 可插拔** — 接口抽象内容来源。MVP 用 `BundledContentSource`（固定二进制），后期加 `RemoteContentSource`（.wcp 式下载），引擎不感知差异。
3. **native ABI 稳定** — C/C++ 层暴露版本化 JNI 接口；Kotlin 侧只调接口，不碰 native 内部。
4. **可复现构建** — 外部二进制版本锁 + 哈希校验；rootfs 用 winlator-imagefs 源码构建。

## 5. 技术栈 (2026 现代 Android)

- Kotlin 2.x + Jetpack Compose + Material3
- Gradle Kotlin DSL + version catalog (`libs.versions.toml`) + `build-logic` convention plugins
- Hilt (DI) + Coroutines/Flow + Room + Navigation-Compose
- NDK r27/r29 + CMake
- 单元测试 (JUnit) + instrumentation 测试骨架

## 6. 模块架构

```
:app                       Compose 壳: 导航/DI 装配/启动
:core:common               工具/协程/错误模型
:core:ui                   设计系统/主题/通用 Compose 组件
:core:native               C/C++: proot + vk_renderer + box64启动 (JNI)  ← 复用
:core:rootfs               imagefs 安装/提取/版本  ← 复用 winlator-imagefs
:core:content              组件管理 (ContentSource 接口: bundled/remote)
:core:container            Wine prefix/容器创建与生命周期
:core:engine               运行时内核: 进程启动/X显示/输入/音频 (稳定接口)
:feature:launcher   ← MVP  选 exe + 分辨率 + 启动
:feature:settings          设置
# ── 长期扩展 (不污染内核) ──
:feature:library           游戏库
:feature:stores:steam      (可选, 后期)
:feature:stores:epic
```

**依赖方向严格单向**: `feature → engine → {native, rootfs, content, container}`。native 永不向上依赖。

### 关键接口（v0.1 先定义）

```kotlin
// core:content
interface ContentSource {
    suspend fun resolve(component: ComponentId): ContentArtifact  // bundled 或 remote 透明
}

// core:engine
interface WineEngine {
    suspend fun launch(session: LaunchSpec): SessionHandle  // 启动 box64+wine, 返回渲染 surface
    fun inputFeed(): InputSink
    fun audioSink(): AudioSink
}
```

引擎内核对上层只暴露 `WineEngine`；Steam/库等特性永远拿不到 native 内部。

## 7. 复用策略

| 资产 | 来源 | 处理 |
|------|------|------|
| **vk_renderer.c** (~9k 行) | WinNative `cpp/winlator/` | **整块移植到 `:core:native`** — 技术核心，绝不重写 |
| proot (13k 行) | WinNative `cpp/proot/` | 移植 |
| patchelf + box64 启动 | WinNative `cpp/` | 移植 |
| **imagefs rootfs** | winlator-imagefs | 直接用作 rootfs；构建脚本可纳入项目 |
| box64/Wine/Turnip/DXVK 二进制 | nicholasx417 / Drivers / proton-wine | 锁版本 + 哈希校验，CI 拉取 |
| Kotlin/Compose app 壳 | — | **全新写**，现代架构，不搬 WinNative 的 183k 行 |
| Steam/商店/.wcp | — | **不搬**，后期按需在新架构下重写 |

**原则: native 内核 + rootfs 复用；app 壳 + 特性层全新写。**

## 8. MVP v0.1 范围

| 项 | 做法 |
|----|------|
| rootfs | 捆绑 winlator-imagefs（Bionic 41 包）|
| 运行时二进制 | 固定一套 box64 + Wine/Proton + Turnip + DXVK，版本锁死打入 assets |
| native | 移植 vk_renderer + proot + box64 启动器（整块复用）|
| UI | 单屏：选 .exe → 分辨率 → 启动；运行中触屏映射 |
| 显示 | Xvfb → Vulkan renderer → Compose Surface |
| 输入 | 触屏 → 鼠标/基础键盘 |
| 音频 | ALSA → Android（aserver 路线）|

**验收标准**: 启动一个 Windows .exe，有画面、有触屏、有声。

## 9. 路线图

| 版本 | 内容 | 估时(1人) |
|------|------|----------|
| **v0.1** | MVP — 一个 exe 跑通（显示+输入+音频）| 2-4 周 |
| v0.2 | 容器管理（多 prefix、游戏列表、快捷方式）| 2-3 周 |
| v0.3 | `RemoteContentSource` — 可切换 Wine/box64/DXVK 版本 | 2-3 周 |
| v0.4 | 输入增强（手柄、虚拟键盘、自定义布局）| 2 周 |
| v1.0 | 稳定性、性能调优、多设备适配 | 持续 |
| v1.x+ | 按需加 Steam/Epic/GOG（独立 feature 模块，内核不动）| 按需 |

## 10. 待决议（请审核）

### D1: 架构路线 — Bionic vs glibc
- **建议: Bionic**。理由: winlator-imagefs 投入在此且已验证；贴近 WinNative/Winlator-Bionic；性能更好。
- 代价: 胶水比 glibc 路线复杂。
- 备选: glibc（原版 Winlator，更简单兼容，但 rootfs 要重建）。

### D2: 执行模型 — proot vs 原生
- Bionic rootfs 可原生跑（libc.so -> /system/lib64/libc.so 符号链接），proot 是否仍需用于 prefix 隔离？
- 需在 v0.1 实现时验证。建议先保留 proot（WinNative/Winlator 已验证可行）。

### D3: 项目名
- **Amphora**，包名 `app.amphora`，目录 `/Users/sky/co/github/amphora`。确认或改。

### D4: 首个 .wcp 下载源
- MVP 捆绑固定二进制（不下载）。v0.3 起接入下载源时，用 `nicholasx417/WinNative-Components`（代码已验证可用）还是自建？建议先复用 nicholasx417。

### D5: Wine 版本
- MVP 用 Proton 11（proton-wine fork 源码可复现）还是 Wine 9.20？建议 Proton 11 arm64ec（WinNative 当前主线）。

## 11. 构建与可复现策略

- **rootfs**: winlator-imagefs 构建产物（源码构建，可复现）。
- **二进制**: box64/Wine/Turnip/DXVK 版本锁 + SHA256 校验（仿 `update-gbe-fork.sh` 模式），CI 拉取并校验。
- **native**: CMake 从源码编译（proot/vk_renderer/patchelf）。
- **APK**: 三个 flavor 暂不做；单 standard。assets 用 `noCompress` 保留 tzst/txz。
- **不可复现部分** (Steam 客户端 / 微软 DLL): MVP **不含**（不做 Steam 集成）；v1.x+ 加 Steam 时按 WinNative 模式从 redist 抽取并声明来源。

---

## 审核清单

- [ ] D1 架构路线 (Bionic/glibc)
- [ ] D2 执行模型 (proot 保留与否)
- [ ] D3 项目名 (Amphora)
- [ ] D4 下载源策略
- [ ] D5 Wine 版本
- [ ] 模块架构认可
- [ ] MVP 范围认可
- [ ] 复用策略认可

审核通过后开始 scaffold: `build-logic` convention plugins → `libs.versions.toml` → 模块空壳 → `:core:native` CMake 桩 → `:core:engine` 接口定义。
