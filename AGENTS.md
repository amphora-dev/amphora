# AGENTS.md

Amphora：在 Android 上跑 Windows 游戏的引擎，按 SDK 设计。本仓库是 SDK 与 App（`amphora-dev/amphora`）。

## 先读

1. `docs/TRACKING.md`：当前进度、证据、已知问题。
2. `docs/research/12-REWRITE-DESIGN.md`：已定决策 D1–D15。和决策冲突的改动先问用户，不要自己改决策。
3. `docs/research/13-SETTINGS-CATALOG.md`：设置目录和覆盖层级。

## 相关仓库（本地都在 `~/co/github/`）

| 目录 | 作用 |
|---|---|
| `amphora-steam`（本仓库） | `:sdk` 引擎、`:ui` 设计系统、`:app` 薄壳；设计文档；`tools/` 设备测试工具 |
| `amphora-engine` | BuildStream 构建 Wine、FEX、DXVK、vkd3d-proton、Turnip，CI 在 main 上发 runtime Release；见那边的 `AGENTS.md` |
| `wine-arm64ec` | Wine 源码，分支 `amphora/exp-11.0-20261027b`（Valve Experimental + 我们的补丁），推到 `amphora-dev/proton-wine` |

## 命令

```bash
./gradlew spotlessApply          # 格式化（ktlint，intellij_idea 风格，120 列）
./gradlew spotlessCheck lint testDebugUnitTest assembleDebug   # 和 CI（.github/workflows/android.yml）一样
./gradlew :sdk:testDebugUnitTest # 只跑 SDK 的测试
adb -s HA262AAH install -r app/build/outputs/apk/debug/app-debug.apk
adb -s HA262AAH logcat           # 包名 app.amphora
tools/device/ui.sh play hello64  # 从命令行启动游戏库里的游戏（会话必须由 App 启动），用法见 tools/README.md
tools/device/aio.sh AIO-Graphics-Test-64bit dx11   # AIO 图形测试的一个后端：帧率、截图、驱动和 DXVK 版本
```

改完 Kotlin 至少跑 `spotlessCheck` 和相关模块的 `testDebugUnitTest`；改了界面或会话，要在 Y700 上实测。

## 约定

- `:app` 只用 `:sdk` 的公开 API；SDK 内部实现放在 `app.amphora.sdk.internal`，并标 `internal`。
- 不设扩展点或接口，等真有第二种实现时再加（D1）。不做 Box64，也不为它留接口（D5）。
- 新设置要进 `SettingsCatalog`，带分组、说明、默认值和选它的理由（D6）；覆盖层级是 配方默认 < 用户全局 < 按游戏修正 < 用户按游戏（D15）。
- 不兼容旧实现（`amphora_old`、`imagefs`），只作参考（D11）。
- GPU 驱动只有 runtime 里的 Turnip，不走系统的 Vulkan 驱动，也不加切换开关（D4，13 §4）。
- 提交信息风格：`<范围>: <小写开头的说明>`，范围如 `sdk`、`app`、`ui`、`docs`、`recipe 2026.11-m2`、`M2`。
- 文档用中文，句子短、直说结论；`TRACKING.md` 只记状态和可复现的证据，决策和理由写进 `12`。
- `docs/conversations/` 是本地的 AI 对话存档，已在 `.gitignore`，不提交。

## Runtime 与配方

- `sdk/src/main/assets/amphora/recipe.json` 钉住 App 下载的 runtime。amphora-engine 发了新 Release 后，按那次构建的 `runtime.env` 更新 `name`、`url`、`sha256`、`size`（`RUNTIME_SIZE`）、`files`、`bytes`，并改 `id`（如 `2026.11-m3`）；runtime 多了或少了组件时同步改 `components`（设置按它判断可不可用）。
- **只装 CI 发布的 runtime**。本地 VM 用 `bst build` 构建的 Wine 会出现内容全为 0 的 i386 DLL（见 TRACKING），不要装到设备上。开发 Wine 时例外：amphora-engine 的 `dev/push.sh` 把 `dev/wine.sh` 增量构建的个别文件换进已装的 runtime（它会查全零和 MZ 头），用完在 TRACKING 的"设备上的测试状态"里写明设备不是发布版。

## 设备

- 只保证 Y700 `HA262AAH`（Adreno 830）。测试程序在 `/sdcard/Download/amphora-test/`：`x86/`（`hello64`、`hello32`）、`vk/`（`tools/vktest` 的三个构建）、`aio/`（AIO 图形测试 64 位和 32 位）。
- 设备上的 DroidDeck（`com.droiddeck.launcher`）只用来对照实验，调试开关用完要关掉。
- `tools/` 里的 `probe` 和 `cowtest` 依赖 M1 宿主 `app.amphora.next`（提交 `4efaf0f` 的 `app/`），现在不在设备上；`fextest` 直接在 App 里作为游戏添加即可。
