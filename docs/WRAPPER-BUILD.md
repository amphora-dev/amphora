# 自编译 Vulkan Wrapper（Pipetto）

WinNative **不在仓库内编译** `wrapper.tzst`，只 vendoring 预编译包。Amphora 可用下列脚本在 Linux 主机上交叉编译同等产物。

## 配方

| 项 | 值 |
|----|----|
| 源码 | [Pipetto-crypto/mesa](https://github.com/Pipetto-crypto/mesa) `wrapper-25` |
| 驱动 | `-Dvulkan-drivers=wrapper` |
| 平台 | `-Dplatforms=x11` + `-Dandroid-stub=true` + `-D__TERMUX__` |
| 工具链 | Android NDK aarch64（推荐 r26d+） |
| Android API | 默认 API 30，与 `build-logic` 的 `SDK_MIN=30` 一致；可用 `API_LEVEL` 显式覆盖 |
| 链接库 | 生产 `imagefs.txz` 内 aarch64 X11/drm/sysvshm（脚本也兼容旧 `.tzst`） |
| 头文件 | Termux `libxcb`（需 `xcb_present_pixmap_synced` / dri3 syncobj） |

## 步骤

```bash
# 1) 准备 sysroot（使用 content_manifest 当前 pin 的生产 imagefs.txz）
./scripts/prepare-wrapper-sysroot.sh /path/to/imagefs.txz

# 2) 交叉编译并打包
export ANDROID_NDK_HOME=/path/to/android-ndk-r26d
./scripts/build-vulkan-wrapper.sh
```

产物：`build/vulkan-wrapper/wrapper-amphora.tzst`（布局与 WinNative `graphics_driver/wrapper.tzst` 相同：`usr/lib/libvulkan_wrapper.so` + ICD + adrenotools/hooks）。

## 说明

- 二进制 NEEDED 可能比官方包多 `libcutils`/`liblog`/`libsync`（NDK android-stub）；脚本会把 stub `.so` 打进 tzst。
- 脚本中的 `PREFIX_FAKE=/data/data/com.winlator.cmod/...` 只用于生成兼容上游布局的
  Meson prefix/RPATH，不是 Amphora 的 Android 包名；运行时依靠 imagefs
  `LD_LIBRARY_PATH` 解析同包库。
- `prepare-wrapper-sysroot.sh` 根据后缀读取 xz 或 zstd tar；生产构建应优先使用远程
  `content_manifest.json` 中 `components.rootfs` 指向的归档，避免与设备运行库版本漂移。
- 本机已验证编出 aarch64 `libvulkan_wrapper.so`（Pipetto `7eae644` / Mesa 25.0.0-devel）。上机替换前建议先备份原 wrapper，并确认 imagefs 内 X11 库版本匹配。
