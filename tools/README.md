# 设备测试工具

在 M1 宿主 App（`app.amphora.next`）的沙箱里跑；宿主源码在提交 `4efaf0f` 的 `app/`，现在的 `app/` 没有 `amphora.exec` 钩子。编译用 Wine 分支的构建镜像（`amphora/Dockerfile`，下称 `amphora-wine-build:1`）。

| 工具 | 测什么 | 编译 | 运行 |
|---|---|---|---|
| `probe/syscalls.c` | Wine 要用的系统调用是否被 app 的 seccomp 放行 | NDK clang `--target=aarch64-linux-android30` | 放进 `files/`，`am start -n app.amphora.next/org.winehq.wine.WineActivity --es amphora.exec files/probe-syscalls`，看 `logcat -s amphora-probe`（必须由 App 启动：`run-as` 不带 app 的 seccomp） |
| `cowtest/` | 薄前缀写时复制（12 §3.3） | `aarch64-w64-mingw32-clang -O2 -o cowtest.exe cowtest.c` | 把 `cowtest.exe`、`run.sh` 放进 `files/`，`run-as app.amphora.next sh files/run.sh $PWD/files ro` |
