# 12 · wineandroid 切换工程计划

> 状态：进行中（2026-09-13）。当前设计真源；X11 只作对照/回退，不作为长期内层。
> 相关：[`11-ANDROID-NATIVE-VULKAN-PLAN.md`](11-ANDROID-NATIVE-VULKAN-PLAN.md)（接口以 **本 pin** 为准，不要抄 master）。

## 0. 产品形态

- **外层**：Amphora 自己的电脑模式壳（壁纸 + 图标网格 + 底栏）。可登记 `SECONDARY_HOME`。不替换 ZUI `com.zui.desktoplauncher`。
- **会话窗**：一个已启动的程序 = 一个 Android 会话 Activity（freeform）。系统任务栏 v1 只显示这个程序。默认宿主是 `WineAndroidSessionActivity`；X11 `SessionActivity` 仍可显式打开。
- **内层**：`wineandroid.drv` 管该会话里的 HWND（GDI 软件呈现 + 之后的 Vulkan）。不是每个 HWND 一条系统任务栏。

## 1. 事实（对照过代码）

| 项 | 事实 |
|---|---|
| Proton pin | Amphora `proton_11.0` @ `8573c4b5e`（含 wineandroid Vulkan stub + ABI / drawable_mutex），Wine 11.0 名，`WINE_VULKAN_DRIVER_VERSION` **47** |
| 上游 11.17/master | 版本 **48**，签名不同；**仍无** `vulkan.c` |
| WCP 旧开关 | `--enable-wineandroid_drv=no`（已改 yes） |
| Wine 自带 APK | `Makefile.in` 的 `EXTRA_TARGETS = wine-debug.apk` 会拉 gradle；已去掉，只要 `.so` |
| WCP（绿） | imagefs `a5f118f`；proton-wine `8573c4b5e`；CI run [34754885882](https://github.com/amphora-dev/imagefs/actions/runs/34754885882)；产物 `Proton-11.0-8573c4b5e-x86_64.wcp`（SHA256 `60e1d879c8d8f0d0f154730fc6704082abb2e954c0b55b29f7b3f7b64e8d7840`）；`content_manifest` `d2967a1` |
| 宿主现状 | **默认 wineandroid**（`LaunchSpec` / `SessionLaunch` / `WineAndroidLaunchGate.FORCE_WINEANDROID_HOST = true`）；X11 为显式回退 |
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
- [x] **提交并推送** `amphora-dev/proton-wine`（含 ABI fix 链；当前 pin tip `8573c4b5e` / `drawable_mutex`）
- [x] **提交并推送** `amphora-dev/imagefs` `a5f118f`（bst / `PROTON_COMMIT` → `8573c4b5e`）
- [x] GitHub Actions `build-proton-wine` 跑绿并打出新 WCP，更新 `content_manifest` SHA（run [34754885882](https://github.com/amphora-dev/imagefs/actions/runs/34754885882)；manifest `d2967a1`）
- **验收**：新 WCP 内存在上述两个文件；宿主默认已切 wineandroid，X11 仍可显式回退
- **注意**：不会把 Amphora 的 `proton_11.0` 回并进 Valve `proton_11.0`；Amphora fork 是真源。

### P1 宿主 2D Spike

- [x] 普通 `WineAndroidSessionActivity`（非 Compose `AndroidView`）+ `WineAndroidDesktop` per-HWND `SurfaceView`
- [x] `WineAndroidHostBridge`：Kotlin 侧对齐 WineActivity 公开面（`createWindow` / `destroyWindow` / `windowPosChanged` / `setParent` + Surface 回调）；**无 JNI**、不移植 `WineActivity.java`
- [x] Launch 接线：`SessionActivity.intent/launch` 增加 `displayBackend`（`WINEANDROID` 时转调 `WineAndroidSessionActivity`）；`LaunchSpec` / `SessionLaunch` **默认 `WINEANDROID`**
- [x] 默认用户路径 = wineandroid；X11 回退：
  - `SessionLaunch.program(..., displayBackend = X11)` / `LaunchSpec(..., displayBackend = X11)`
  - MainActivity extra `app.amphora.debug.X11=true`（仅 debuggable）
  - 本地把 `WineAndroidLaunchGate.FORCE_WINEANDROID_HOST = false` 后重编（恢复旧默认）
- [x] `WineAndroidSessionBootstrap`：复用 catalog / runtime / rootfs / container / preparer，**不**起 Java XServer / XServerComponent；socket stub 落在 `filesDir/wineandroid/host.sock`
- [x] `WineAndroidLauncher`：bare `XEnvironment` + 仅 `GuestProgramLauncherComponent`；命令仍是 `box64 wine explorer /desktop=shell,WxH …`（与 X11 同形）
- [x] Env：`AMPHORA_WINEANDROID=1` 时 GPLC **丢掉** `DISPLAY=unix:…/X0`、`ANDROID_SYSVSHM_SERVER`、`GST_PLUGIN_FEATURE_RANK=ximagesink…`；写入 `AMPHORA_WINEANDROID_SOCK`
- [x] Host socket：`WineAndroidHostSocket` 在 `filesDir/wineandroid/host.sock` 上 listen；帧为 `opcode+nbytes+payload`，payload 字段顺序对齐 `device.c` 的 `ioctl_android_create_window` / destroy / window_pos_changed / set_window_parent；派发到 `WineAndroidHostBridge`（主线程 UI）。`HOST_SURFACE_CHANGED` 仅回传 hwnd/opengl/ready，**不含** native handle。
- [ ] **仍阻塞**：unix 侧改连 `AMPHORA_WINEANDROID_SOCK`（替代 JNI `RegisterNatives`），并实现 `register_native_window` / Surface fd 回传（WCP 已含 drv，但 drv 仍走 JNI，直到 sibling 改动落地）
- [ ] 输入走 wineandroid，不注入 X
- **验收**：真机 `HA262AAH` 上 winefile 窗口可见、可点（阻塞在 unix socket / JNI→ioctl 桥）

### P2 Vulkan（游戏）

- 新增 `dlls/wineandroid.drv/vulkan.c`，按 **v47** 实现 4 ops
- `init.c` 挂 `.pVulkanInit = ANDROID_VulkanInit`
- `dlopen("libvulkan.so")`，扩展 `VK_KHR_android_surface` / `VK_KHR_swapchain`
- 复用 `TurnipDriverProvisioner` / adrenotools
- **验收**：Spike A 级三角形或 DXVK 全屏游戏 present；X11 对照仍可开

### P3 外层壳

- [x] v1 `DesktopActivity`：壁纸 + `LauncherProgramLibrary` 图标网格 + 底栏（本会话 / Explorer / Settings / 返回）
- [x] 点图标 → `SessionLaunch` → 默认 wineandroid（`WineAndroidSessionActivity`）；显式 `displayBackend=X11` 走旧路径
- [x] `SessionActivity` `resizeableActivity=true`（一程序一会话窗；不是每 HWND 一个系统 freeform）
- [x] 入口：启动器顶栏 **Desktop**；或 debuggable `app.amphora.debug.DESKTOP`。**不**注册 `SECONDARY_HOME`（避免抢平板主屏）
- [ ] 可选 `SECONDARY_HOME` / 电脑模式副屏（以后）
- **验收（真机）**：副屏或桌面 Activity 能点图标开会话窗

## 3. 本机做不到、需要人的

1. ~~推送 proton-wine / imagefs~~（已推：proton-wine `8573c4b5e`，imagefs `a5f118f`）。
2. ~~imagefs `build-proton-wine` 跑绿~~（run 34754885882；产物 `Proton-11.0-8573c4b5e-x86_64.wcp`）。
3. ~~更新 `content_manifest` SHA~~（`d2967a1`）。
4. **真机** `HA262AAH`：P1/P2 安装与看画面（仍等 unix socket 桥）。
5. **sibling**：unix drv 改连 `AMPHORA_WINEANDROID_SOCK`（替代 JNI）。

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

| 怎么打开 | 说明 |
|---|---|
| 启动器 / Desktop 点图标 / `SessionLaunch.program` | **默认** wineandroid（`FORCE_WINEANDROID_HOST = true`） |
| `adb shell am start -n app.amphora/.MainActivity --ez app.amphora.debug.WINEANDROID true` | debuggable；显式 wineandroid（与默认同路径） |
| `adb shell am start -n app.amphora/.MainActivity --ez app.amphora.debug.X11 true` | debuggable；**回退 X11** |
| `SessionLaunch.program(..., displayBackend = X11)` / `SessionActivity.launch(..., displayBackend = X11)` | API 层显式 X11 |
| `WineAndroidLaunchGate.FORCE_WINEANDROID_HOST = false` 后重编 | 本地把默认改回 X11 |

`LaunchSpec.displayBackend` 默认是 `WINEANDROID`。X11 `GameSessionViewModel` 仍显式传 `DisplayBackend.X11`。

仍需 unix socket 才能完成的（WCP 已含 drv）：

- ~~guest `box64 wine explorer /desktop=shell` exec~~（`WineAndroidLauncher` 已启动；无 Java X）
- ~~把 `ioctl_android_*` 帧送到 HostBridge~~（Kotlin listen/decode 已就绪；**unix 驱动尚未 connect**）
- `wine_surface_changed` 完整等价：Surface/`ANativeWindow` fd 回传 + unix `register_native_window`（`HOST_SURFACE_CHANGED` 目前只有 hwnd/opengl/ready）
- ~~WCP 内实际存在 `wineandroid.drv` / `wineandroid.so`~~（imagefs CI 绿；`Proton-11.0-8573c4b5e-x86_64.wcp`）

