# 设备测试工具

`probe/syscalls.c` 和 `cowtest/` 在 M1 宿主 App（`app.amphora.next`）的沙箱里跑；宿主源码在提交 `4efaf0f` 的 `app/`，现在的 `app/` 没有 `amphora.exec` 钩子。其余的用现在的 App（`app.amphora`）。PE 程序用 Wine 分支的构建镜像编译（`amphora/Dockerfile`，下称 `amphora-wine-build:1`，里面有 llvm-mingw）。

| 工具 | 测什么 | 编译 | 运行 |
|---|---|---|---|
| `probe/syscalls.c` | Wine 要用的系统调用是否被 app 的 seccomp 放行 | NDK clang `--target=aarch64-linux-android30` | 放进 `files/`，`am start -n app.amphora.next/org.winehq.wine.WineActivity --es amphora.exec files/probe-syscalls`，看 `logcat -s amphora-probe`（必须由 App 启动：`run-as` 不带 app 的 seccomp） |
| `fextest/hello.c` | FEX：64 位（ARM64EC）和 32 位（WoW64）x86 能不能跑：CPUID、SSE、线程原子操作、异常恢复，最后弹消息框 | `x86_64-w64-mingw32-clang -O2 -o hello64.exe hello.c -luser32`，`i686-…` 同理出 `hello32.exe` | 放到共享存储，在 App 里作为游戏添加并启动（不需要 M1 宿主）；`-q` 不弹框 |
| `cowtest/` | 薄前缀写时复制（12 §3.3） | `aarch64-w64-mingw32-clang -O2 -o cowtest.exe cowtest.c` | 把 `cowtest.exe`、`run.sh` 放进 `files/`，`run-as app.amphora.next sh files/run.sh $PWD/files ro` |
| `probe/vkahb.c` | 交换链（12 §3.1）要用的东西在 Wine 那样启动的进程里是否可用：系统 `libvulkan.so` 给出 GPU；自己分配的 AHardwareBuffer 能导入成可渲染的 VkImage（RGBA8 unorm / sRGB、A2B10G10R10、RGBA16F、RGB565，含非对齐尺寸）；渲染完成能导出成 sync fd；sync fd 能导入信号量和 fence。另外列出 32 位映射要用的扩展有没有 | NDK clang `--target=aarch64-linux-android30 -O2 -Wl,-z,max-page-size=16384 -o probe-vkahb vkahb.c -lvulkan -landroid` | `adb push` 到 `/data/local/tmp`，`run-as app.amphora` 下拷进 `cache/`，`/system/bin/linker64 $PWD/cache/probe-vkahb`（和 Wine 一样经系统 linker 启动）。逐项打印，最后 `PASS` / `FAIL` |
| `vktest/` | Wine 里的 Vulkan：不带参数是离屏（真正的图形管线画三角形再回读，逐像素核对），结束时弹消息框；`--window` 是出画（窗口、surface、交换链，转动的三角形，结束时报帧率），可选 `--frames N`、`--mode fifo\|mailbox\|immediate`、`--size WxH` | `./build.sh` 出 `out/vktest-arm64.exe`、`vktest64.exe`、`vktest32.exe`（要 Docker 镜像 `amphora-wine-build:1` 和 Vulkan-Headers，默认取 Homebrew 的）；改了着色器后 `./build.sh shaders` | 放到共享存储，在 App 里作为游戏添加并启动。不要用 `run-as` 无界面跑：win32u 初始化 Vulkan 时会加载显示驱动，没有宿主就卡住 |
| `device/ui.sh` | 从命令行操作设备上的 App：列出屏幕上的文字、按文字点击、截图、解锁、把 `/sdcard/Download/amphora-test/` 下的 exe 加进游戏库、启动游戏、结束会话、改游戏的启动参数、看最新的会话日志。会话必须由 App 启动（它是 Wine 窗口的宿主），所以要自动化就得走界面；按界面上的中文文字找控件，界面改了要跟着改 | 不用编译（bash、python3、adb） | `tools/device/ui.sh`（不带参数打印用法）；默认设备是 Y700，`ANDROID_SERIAL` 可改 |
| `device/vkrun.sh` | 用给定参数跑游戏库里的一个 vktest，打印它的报告、驱动宿主端的日志和崩溃记录，然后结束会话 | 同上 | `tools/device/vkrun.sh vktest64 "--window --frames 1500 --mode fifo" 18`（先 `ui.sh add vk vktest64.exe`） |
