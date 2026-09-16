# 20 · AIO Vulkan / PresentModes 现状（2026-09-16）

> 状态：**开放排查** · 记档 2026-09-16（Asia/Shanghai）  
> 相关：[`04-ASSET-MANIFEST.md`](04-ASSET-MANIFEST.md)（AIO 2.1.0）、[`12-WINEANDROID-MIGRATION.md`](12-WINEANDROID-MIGRATION.md)、[`13-AHB-IMPORT-PRESENT.md`](13-AHB-IMPORT-PRESENT.md)。  
> 仓位：amphora `main`；proton-wine `proton_11.0` @ `05ca3a658db`；imagefs `main` pin 同 tip；已发布 WCP `Proton-11.0-05ca3a658-x86_64.wcp`。

---

## 0. 一句话

为修 AIO `--cube vk` 的「Present mode unsupported」，在 **win32u 全局** 假报 FIFO/MAILBOX/IMMEDIATE，并在 CreateSwapchain 把 IMMEDIATE/MAILBOX remap 成 FIFO。新 WCP 上该报错消失，但 HA262 上 `--cube vk` / `--cube vk --vsync` 目前常见黑屏；干净一轮真机日志显示 guest 在加载 `kernel32.dll` 时以 `c0000135` 退出，**尚未可靠复现到 AHB CreateSwapchain**。OpenGL / D3D11 cube 尚未作为本轮重点重测。

---

## 1. 已落地（代码 / 资产）

| 项 | 位置 |
|---|---|
| Debug `WINE_ARGS`（`am start --es app.amphora.debug.WINE_ARGS`） | amphora：`LaunchSpec.exeArgs` → `WineEngineImpl.buildWineProgramCommand`；需同时 `--ez app.amphora.debug.WINE_SMOKE true`（或 `WINEANDROID`）才会进会话 |
| 官方预编译 AIO Graphics Test **2.1.0** | `core/content/.../winnative/Graphics-Test-{32,64}bit.exe`；见 docs/04 |
| PresentModes 进 `USER_DRIVER_FUNCS`；win32u 假报 + IMMEDIATE/MAILBOX→FIFO remap | proton-wine `068948b16ef` + `05ca3a658db`（`WINE_VULKAN_DRIVER_VERSION` 47→48） |
| WCP 构建 fail-closed：PresentModes thunk 必须走 `vk_funcs` | imagefs `ci/wine/check-present-modes-thunk.py` |
| 已发布 WCP | `Proton-11.0-05ca3a658-x86_64.wcp`（与 tip 同名） |

**包内 ABI（已用 objdump 核对新旧 WCP）：** `winevulkan.so` / `win32u.so` / `wineandroid.so` 在 `05ca3a658` 包内均按 version **48** 一致；`wineandroid.so` 相对旧包仅 version 常量两字节差异。可排除「同包 47/48 混装」。

---

## 2. X11 / 层归属（对照结论）

- `winex11.drv/vulkan.c` **不实现** PresentModes / CreateSwapchain；旧路径是 winevulkan 对 host 的 PresentModes 直通。
- 当前假报与 remap 在 **win32u 全局**，**没有** `amphora_wsi_wanted()` 门闩。这与 docs/12「不要在 `win32u/vulkan.c` 加 android 分支」的意图冲突：功能上不是 `#ifdef android`，但是把 Android Adreno 缺模式问题做成了全驱动行为。
- Amphora AHB 成功路径（`amphora_ahb_sc.inc` 的 `amphora_vkCreateSwapchainKHR`）**不读取** `presentMode`；IPC 仍携带该字段，仅 ICD fallback 会用到。因此「FIFO 取值本身弄坏 AHB」不成立；「PresentModes 补丁整包无副作用」也不成立。

AIO `cube.c`：`--vsync` → `VK_PRESENT_MODE_FIFO_KHR`，默认 → `IMMEDIATE`；列表里没有所选模式则 `ERR_EXIT`「Present mode unsupported」（不是静默回退）。

---

## 3. HA262 真机现象（2026-09-16 晚）

启动示例（Mac mini adb → HA262AAH）：

```bash
adb shell "am force-stop app.amphora; am start -n app.amphora/.MainActivity \
  --ez app.amphora.debug.WINE_SMOKE true \
  --es app.amphora.debug.WINE_EXE 'C:/ProgramData/Microsoft/Windows/Graphics-Test-64bit.exe' \
  --es app.amphora.debug.WINE_ARGS '--cube vk --vsync --autoclose'"
```

| 观察 | 结果 |
|---|---|
| 会话 / WCP | `WineAndroidSession` + `Proton-11.0-05ca3a658`；命令行含 `--cube vk --vsync` |
| logcat `AHB_SC create images=… import=ok` | **无**（亦无 `AHB_SC CreateSwapchain enter`） |
| logcat `WineAndroidWsi` knife13 / `wsi-sc-*.sock` | **有**（宿主 WSI 代理起来了） |
| `wine_stderr`（`WINEDEBUG=+err,+android` → `files/wine_stderr.log`）干净一轮 | **无** `Failed to load`（非 win32u `vulkan_init_once` 的 libvulkan dlopen 早退）；**有** `wine: could not load kernel32.dll, status c0000135` |
| 同轮 logcat | `avc: denied { execmod }` 打在 WCP 内 `gdi32.dll`；`kernel32.dll` 文件在 WCP 路径上存在 |
| 截屏 | 黑底 + 底部 guest 命令行覆盖层 |
| 累积未截断的旧 `wine_stderr` | 曾出现 `ANDROID_VulkanInit ok` 与 `amphora sc CreateSwapchain ret=0`——**不能**当作本轮干净证据 |

上一版公开 tip `0a64ebc8d69`：无 `--vsync` 常见「Present mode unsupported」；有 `--vsync` 曾能出画（观感卡顿）。新 tip 上该对话框消失后的黑屏，与「仅 remap 害了 FIFO」矛盾（`--vsync` 不进 remap）。

---

## 4. 当前主假设与下一步（不要参数扫射）

1. **主假设（干净一轮）：** guest 在首次 `__wine_get_vulkan_driver` / `ANDROID_VulkanInit` 之前因 `kernel32.dll` `c0000135`（及可能的 SELinux `execmod`）退出 → 解释无 VulkanInit、无 AHB_SC。
2. **次假设：** 在 guest 能稳定过 wineboot 的前提下，再核 PresentModes 改走 win32u 之后是否仍能打出 `AHB_SC create images=… import=ok`；有则查 present/合成，无则查 surface 注册 / CreateSwapchain enter。
3. **层修复方向（尚未改代码）：** 若仍要假报 PresentModes，应闸在 Amphora / `amphora_wsi_wanted()`（或等价 Android 条件），而不是长期留在全局 win32u；与 docs/12 对齐。

**暂停点：** 2026-09-16 用户要求停排查、合 main、落文档。OpenGL（`--cube gl`）与 D3D11（`--cube dx11`）仍待 guest 稳定后单独处理。

---

## 5. 调试备忘

- 只传 `WINE_EXE` / `WINE_ARGS` **不会**自动进会话；必须 `WINE_SMOKE` 或 `WINEANDROID`。
- Wine 的 `ERR`/`TRACE` 看 `files/wine_stderr.log`，不要只靠 logcat。
- 查 AHB 是否成功：logcat tag `WineAndroidWsi`，关键字 `AHB_SC create images=`。
- 查 win32u 是否 dlopen 失败：stderr 中 `Failed to load` + `SONAME_LIBVULKAN`（`dlls/win32u/vulkan.c` `vulkan_init_once`）。
