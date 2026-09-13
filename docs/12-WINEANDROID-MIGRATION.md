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
| Proton pin | Amphora `proton_11.0` @ `a0b4f11`（含 wineandroid Vulkan stub + ABI fix），Wine 11.0 名，`WINE_VULKAN_DRIVER_VERSION` **47** |
| 上游 11.17/master | 版本 **48**，签名不同；**仍无** `vulkan.c` |
| WCP 旧开关 | `--enable-wineandroid_drv=no`（已改 yes） |
| Wine 自带 APK | `Makefile.in` 的 `EXTRA_TARGETS = wine-debug.apk` 会拉 gradle；已去掉，只要 `.so` |
| 宿主现状 | 默认仍 Java X + TextureView + `explorer /desktop=shell`；P1 `WineAndroidSessionActivity` 可 debug 打开，尚未默认 |
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
- [x] **提交并推送** `amphora-dev/proton-wine` `a0b4f11c0a75877c5cf3ee0b7633e0d2ea54cc27`（`wineandroid: match pinned Proton WindowPosChanged and GL surface_create ABI.`）
- [x] **提交并推送** `amphora-dev/imagefs` `9c33c921ea0021ca5f363d66cfd3769d8b1f1493`（bst `ref` / `PROTON_COMMIT` → a0b4f11）
- [ ] GitHub Actions `build-proton-wine` 跑绿并打出新 WCP，更新 `content_manifest` SHA（run https://github.com/amphora-dev/imagefs/actions/runs/34748539257 ）
- **验收**：新 WCP 内存在上述两个文件；旧 X11 路径暂不强制切换
- **注意**：不会把 Amphora 的 `proton_11.0` 回并进 Valve `proton_11.0`；Amphora fork 是真源。

### P1 宿主 2D Spike

- [x] 普通 `WineAndroidSessionActivity`（非 Compose `AndroidView`）+ `WineAndroidDesktop` per-HWND `SurfaceView`
- [x] `WineAndroidHostBridge`：Kotlin 侧对齐 WineActivity 公开面（`createWindow` / `destroyWindow` / `windowPosChanged` / `setParent` + Surface 回调）；**无 JNI**、不移植 `WineActivity.java`
- [x] Launch 接线：`SessionActivity.intent/launch` 增加 `displayBackend`（默认仍 `X11`；`WINEANDROID` 时转调 `WineAndroidSessionActivity`）
- [x] Debug 入口（**不改变默认用户路径**）：
  - MainActivity extra `app.amphora.debug.WINEANDROID=true`（仅 debuggable）
  - 或本地把 `WineAndroidLaunchGate.FORCE_WINEANDROID_HOST = true`
- [x] `WineAndroidSessionBootstrap`：复用 catalog / runtime / rootfs / container / preparer，**不**起 Java XServer / XServerComponent；socket stub 落在 `filesDir/wineandroid/host.sock`
- [ ] 等 WCP 含 `wineandroid.drv` 后：unix 侧用 socket/fd 讲清 `ioctl_android_*`（字段名见 `device.c`），再 `box64 wine` 无 X desktop
- [ ] 输入走 wineandroid，不注入 X
- **验收**：真机 `HA262AAH` 上 winefile 窗口可见、可点（仍阻塞在新 WCP）

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

1. ~~推送 proton-wine / imagefs~~（已推：proton-wine `a0b4f11`，imagefs `9c33c92`）。amphora 宿主接线可随本提交推。
2. **等 imagefs `build-proton-wine` 跑绿**（run 34748539257）。这边没有 NDK + BuildStream 沙箱，编不了 WCP。
3. **真机** `HA262AAH`：P1/P2 安装与看画面。
4. WCP 绿了之后：**更新 `content_manifest` SHA**（bst 已指向 ABI fix commit）。

## 4. 明确不做

- 升到 Wine 11.17 / master（会离开 Proton 11.0 树）
- 复刻 EGG jwm / native X
- 替换 ZUI Work 桌面 priv-app
- 每个 HWND 一个系统 freeform（P3 之后）
- wine 补丁里写 adrenotools

## 5. 宿主原则（2026-09-13）

- 新代码只写 **Kotlin**。不移植 `WineActivity.java`，不对齐 Winlator。
- 长期去掉 `com.winlator` 那层（Java X server / TextureView compositor）。X11 只是现包还能跑的对照，不是目标。
- pin 里 wineandroid 的 JNI 假定：Wine 从某个 Java 对象 `wine_init` 启动，`ntdll` 带上 `java_vm`，unix 驱动 `RegisterNatives`。Amphora 实际是 **`:session` 进程 `box64 exec wine`**，Wine 不在 JVM 里，这条接不上。
- 因此 P1 的桥是 Amphora 自己的：Kotlin `WineAndroidDesktop` 管 HWND→SurfaceView；unix 侧只要 `ANativeWindow`。不使用 `org.winehq.wine.WineActivity` 类名。

### P1 宿主启动接线（2026-09-13）

| 怎么打开 wineandroid Activity | 说明 |
|---|---|
| `adb shell am start -n app.amphora/.MainActivity --ez app.amphora.debug.WINEANDROID true` | debuggable 包；会 stage debug exe 并进 `WineAndroidSessionActivity` |
| `WineAndroidLaunchGate.FORCE_WINEANDROID_HOST = true` 后重编 | 启动器点图标也走 wineandroid（仅本地） |
| `SessionActivity.launch(..., displayBackend = WINEANDROID)` | API 层转调 |

`LaunchSpec.displayBackend` 默认仍是 `X11`。在设备 WCP 尚未带上 `wineandroid.drv` 之前不要改默认。

仍需新 WCP / unix socket 才能完成的：

- guest `box64 wine`（无 `explorer /desktop` X 路径）真正 exec
- 把 `ioctl_android_create_window` 等帧从驱动送到 `WineAndroidHostBridge`
- `wine_surface_changed` 等价：把 `Surface`/`ANativeWindow` 回传 unix 侧 `register_native_window`

