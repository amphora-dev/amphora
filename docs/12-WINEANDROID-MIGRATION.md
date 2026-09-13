# 12 · wineandroid 切换工程计划

> 状态：进行中（2026-09-13）。当前设计真源；X11 只作对照/回退，不作为长期内层。
> 相关：[`11-ANDROID-NATIVE-VULKAN-PLAN.md`](11-ANDROID-NATIVE-VULKAN-PLAN.md)（接口以 **本 pin** 为准，不要抄 master）。

## 0. 产品形态

- **外层**：Amphora 自己的电脑模式壳（壁纸 + 图标网格 + 底栏）。可登记 `SECONDARY_HOME`。不替换 ZUI `com.zui.desktoplauncher`。
- **会话窗**：一个已启动的程序 = 一个 Android `SessionActivity`（freeform）。系统任务栏 v1 只显示这个程序。
- **内层**：`wineandroid.drv` 管该会话里的 HWND（GDI 软件呈现 + 之后的 Vulkan）。不是每个 HWND 一条系统任务栏。

## 1. 事实（对照过代码）

| 项 | 事实 |
|---|---|
| Proton pin | `d12a5634aa4`，Wine 11.0 名，`WINE_VULKAN_DRIVER_VERSION` **47** |
| 上游 11.17/master | 版本 **48**，签名不同；**仍无** `vulkan.c` |
| WCP 旧开关 | `--enable-wineandroid_drv=no`（已改 yes） |
| Wine 自带 APK | `Makefile.in` 的 `EXTRA_TARGETS = wine-debug.apk` 会拉 gradle；已去掉，只要 `.so` |
| 宿主现状 | Java X + TextureView + `explorer /desktop=shell` |
| Compose | `SurfaceView` 的 surface 不会创建，wineandroid 宿主必须是普通 Activity |

`p_vulkan_surface_create`（pin / v47）：

```c
VkResult (*)(HWND, BOOL, const struct vulkan_instance *, VkSurfaceKHR *, struct client_surface **);
```

不要用文档 11 里 master 的 `(client_surface *, instance, VkSurfaceKHR *)`。

登记走 `user_driver->pVulkanInit` → `ANDROID_VulkanInit`。不要在 `win32u/vulkan.c` 加 android 分支。

## 2. 阶段与验收

### P0 构建（本阶段）

- [x] 只改 `imagefs/ci/wine/build-proton-wcp.sh` 的 drv 开关（proton-wine `build-step-*.sh` 是 WinNative 旧脚本，不动）
- [x] 去掉 wine-debug.apk 构建目标
- [x] WCP 安装后断言 `x86_64-unix/wineandroid.so` 与 `x86_64-windows/wineandroid.drv`
- [x] `dlls/wineandroid.drv/vulkan.c` + `ANDROID_VulkanInit`（v47 签名）
- [x] `winevulkan/make_vulkan`：android 列入 UNEXPOSED（生成类型，不把 `VK_KHR_android_surface` 直接暴露给 Win32 应用）
- [ ] **提交并推送** `amphora-dev/proton-wine` + `amphora-dev/imagefs`（bst `ref` 仍指向旧 commit，推送 proton-wine 后要更新 bst）
- [ ] GitHub Actions `build-proton-wine` 打出新 WCP，更新 `content_manifest` SHA
- **验收**：新 WCP 内存在上述两个文件；旧 X11 路径暂不强制切换

### P1 宿主 2D Spike

- 普通 `Activity`（非 Compose `AndroidView`）承载 per-HWND `Surface`
- 对照 pin 里 `WineActivity.java` 的桌面 View / `wine_surface_changed`
- 启动 `winefile`（或 notepad），**无 X server 进程**
- 输入走 wineandroid，不注入 X
- **验收**：真机 `HA262AAH` 上 winefile 窗口可见、可点

### P2 Vulkan（游戏）

- 新增 `dlls/wineandroid.drv/vulkan.c`，按 **v47** 实现 4 ops
- `init.c` 挂 `.pVulkanInit = ANDROID_VulkanInit`
- `dlopen("libvulkan.so")`，扩展 `VK_KHR_android_surface` / `VK_KHR_swapchain`
- 复用 `TurnipDriverProvisioner` / adrenotools
- **验收**：Spike A 级三角形或 DXVK 全屏游戏 present；X11 对照仍可开

### P3 外层壳

- 网格（`LauncherProgramLibrary` 已有 exe 名单）+ 底栏
- `SessionActivity` 改为 resizeable / freeform
- 可选 `SECONDARY_HOME`
- **验收**：电脑模式副屏能拉起 Amphora 桌面，点图标开一个会话窗

## 3. 本机做不到、需要人的

1. **推送三个仓库的提交**（我这边只有 clone）。
2. **跑 imagefs 的 `build-proton-wine` workflow**（ubuntu-24.04，最长 6 小时）。我这边没有 NDK + BuildStream 沙箱，编不了 WCP。
3. **真机** `HA262AAH`：P1/P2 安装与看画面。
4. P0 打出新包后：**更新 bst `ref` + manifest SHA**（或授权我推）。

## 4. 明确不做

- 升到 Wine 11.17 / master（会离开 Proton 11.0 树）
- 复刻 EGG jwm / native X
- 替换 ZUI Work 桌面 priv-app
- 每个 HWND 一个系统 freeform（P3 之后）
- wine 补丁里写 adrenotools
