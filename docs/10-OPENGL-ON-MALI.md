# 10 - Mali 上的 OpenGL：问题与方案

> 给不熟悉图形栈的人讲清楚：Mali 上 OpenGL 为什么慢、为什么"补 GLX"不是答案、VirGL 怎么解决。
> 最后更新: 2026-08-14

---

## 1. 一句话结论

Amphora 在 Mali 上 OpenGL 当前跑的是 **CPU 软件渲染（softpipe，约 30 fps）**。原因是 Mali 的 Vulkan 驱动缺了 Zink 必须的两个扩展，硬件加速这条路从根上走不通。

正在做的修复是 **VirGL**：绕开 Vulkan，让 guest OpenGL 直接落到 Mali 的原生 GLES 上。阶段 0 真机已验证：**30 fps → 100+ fps**，下一步集成进 app。

---

## 2. 先搞清楚几个概念

### 2.1 桌面 OpenGL vs OpenGL ES

这俩名字像，但是**不同的 API**。

**桌面 OpenGL**（Windows/Linux 用的）功能很全：

- 有"固定管线"——可以直接写 `glBegin(GL_TRIANGLES); glVertex3f(...); glEnd();`，不用写 shader
- 有 `glPushAttrib`/`glPopAttrib`——一键保存/恢复全部 GL 状态
- 有 display list——把一串 GL 命令录下来反复重放
- 有 `glDrawPixels`/`glBitmap`——直接画像素位图
- GLSL 语法宽松，`gl_FragColor` 直接用
- 支持 compatibility profile，老游戏的写法全兼容

**OpenGL ES**（手机/嵌入式用的）为了精简，砍掉了很多：

- 没有固定管线，必须写 shader
- 没有 display list
- 没有 `glPushAttrib`/`glPopAttrib`
- 没有 `glDrawPixels`/`glBitmap`
- GLSL 语法更严格（要写 precision qualifier 等）

所以**不能**把桌面 OpenGL 程序直接丢给 GLES 跑。中间必须有一层翻译。

Windows 游戏用的是桌面 OpenGL。Android 的 Mali GPU 驱动只提供 GLES 和 Vulkan。这中间的翻译就是问题的核心。

### 2.2 什么是 Mesa

Mesa 是一个开源的 OpenGL 实现。它不是 GPU 驱动本身，而是 OpenGL API 和 GPU 驱动之间的中间层。

Mesa 内部有多个"驱动"（gallium driver），每个负责把 OpenGL 翻译成不同的后端：

| Mesa 驱动 | 翻译到 | 特点 |
|---|---|---|
| Zink | Vulkan | 当前主力，把 GL 命令翻成 Vulkan |
| softpipe | CPU | 软件光栅化，慢但稳定，不依赖 GPU |
| llvmpipe | CPU + LLVM JIT | 软件光栅化但用 JIT 加速，比 softpipe 快 |
| virgl | virgl 协议 | 把 GL 命令序列化发给 virglrenderer，后者用 GLES 执行 |

Amphora 的 imagefs 里的 Mesa 是 `-Dgallium-drivers=zink,softpipe -Dllvm=disabled`：只有 zink 和 softpipe，连 llvmpipe 都没有。所以软件回退落到最慢的 softpipe 上。

### 2.3 什么是 GLX 和 EGL

GLX 和 EGL 都是**让 OpenGL 在窗口系统上工作**的接口层。它们负责：

- 选择窗口的像素格式（visual / EGLConfig）
- 创建 OpenGL context
- 把 context 绑到某个窗口（drawable / surface）
- 交换前后缓冲区（swap buffers）

**GLX** 是 X11 专用的。X Server 要实现 GLX extension，客户端通过 X 协议和它交互。

**EGL** 是跨平台的，不依赖 X Server 的 GLX extension。EGL 可以直接和窗口系统（X11、Wayland、Android）对话。

Amphora 的 Java X Server **没有实现 GLX extension**。所以 Wine 不能走 GLX。Amphora 用的是 EGL——Wine 的 EGL 后端（`WINE_USE_EGL=1`）让 OpenGL 通过 EGL 而非 GLX 建立 context 和上屏。

### 2.4 什么是 DRI3

DRI3 是 X11 的一个 extension，让客户端和 X Server 之间共享图形缓冲区（buffer），避免拷贝。

标准 Linux 桌面上：

```
1. 客户端发 DRI3 Open 请求
2. X Server 打开 /dev/dri/renderD128（GPU render node），把 FD 传回
3. 客户端拿到 FD，直接和 GPU 通信
4. 客户端渲染完后，把 buffer 通过 DRI3 PixmapFromBuffers 传给 X Server
5. X Server 把这个 buffer 显示到窗口
```

全程零拷贝：GPU 渲染的 buffer 直接变成窗口的内容。

**Amphora 做不到第 2 步**。Android 普通应用没有权限打开 `/dev/dri/render*`，所以 DRI3 Open 返回零 FD。

但 Amphora 不需要那个 FD。它的实际传帧路径是 `DRI3 PixmapFromBuffers` + AHardwareBuffer——用 Android 的 AHB socket 机制传 buffer，不需要 DRM render node。DXVK 每天都在走这条路，D3D11 能跑 3192 fps。

---

## 3. Windows 游戏的 OpenGL 怎么到 GPU

完整链路：

```
Windows 游戏 (WGL/桌面 OpenGL)
  → Wine opengl32          (把 WGL 翻译成宿主 GL/EGL 调用)
  → Wine EGL 后端           (WINE_USE_EGL=1, 不走 GLX)
  → Mesa libEGL            (EGL 接口层)
  → Zink                   (桌面 GL → Vulkan 翻译)
  → Kopper                 (把 Vulkan swapchain 接到 X11)
  → Vulkan wrapper/Leegao  (Vulkan ICD)
  → Mali GPU
```

每个环节都要对上。任意一环断了，就是黑屏或软件渲染。

---

## 4. 为什么 Mali 上 Zink 走不通

### 4.1 Zink 的硬性要求

Zink 把 OpenGL 翻译成 Vulkan，但它不是随便哪个 Vulkan 都能用。它要求 GPU 的 Vulkan 驱动支持六个扩展，缺一个就建不了 screen：

| 扩展 | 用途 |
|---|---|
| `VK_KHR_maintenance1` | 基础维护 |
| `VK_KHR_create_renderpass2` | 现代 renderpass |
| `VK_KHR_imageless_framebuffer` | 无绑定 framebuffer |
| `VK_KHR_descriptor_update_template` | 描述符模板 |
| `VK_KHR_dynamic_rendering` | 动态渲染（替代传统 renderpass） |
| `VK_EXT_robustness2` | nullDescriptor（空描述符） |

### 4.2 Mali-G76 缺了什么

实测华为 Mali-G76 r34 blob（Vulkan 1.1.191）缺最后两个：

- `VK_KHR_dynamic_rendering`
- `VK_EXT_robustness2`（`nullDescriptor`）

前面四个都有，但 zink 是"全有或全无"——少一个就罢工。

### 4.3 失败是静默的

这是最难排查的一点。zink 在 screen 创建失败时**几乎不报错**：

- zink 在"驱动名是推断出来的"时屏蔽自己的 `mesa_loge` 日志；
- 缺扩展那条只走 `debug_printf`，release 构建（`-Db_ndebug`）直接编译掉了；
- 日志里**只剩一行**：`MESA-EGL: warning: egl: failed to create dri2 screen`；
- 然后 OpenGL 就**悄悄**跑在 softpipe 上。

同一个 GPU 上其他 API 完全正常——D3D11 走 DXVK 到 Vulkan 有 3192 fps，D3D9 有 1175 fps，唯独 OpenGL 只有 30 fps。差异巨大但日志几乎不给线索。

### 4.4 无法靠改 Zink 绕过

有人会想：把 zink 对这两个扩展的依赖去掉不就行了？不行。

zink 已经**删除了 renderpass 路径**（Mesa 25.2 的 `zink_render_pass.c` 只剩属性计算，全文找不到 `vkCreateRenderPass`）。去掉断言只会在第一次 `vkCmdBeginRendering` 时直接崩溃，因为整个代码路径只认 dynamic rendering，没有 fallback。

也就是说，这不是"zink 选择性地要求这个扩展"，而是"zink 的代码里已经没有不使用这个扩展的路径了"。

### 4.5 当前做了什么

新增了 `ZinkRequirements.kt` 和 `GraphicsDriverCapabilities.zinkBlockers()`：启动前枚举设备全部 Vulkan 扩展，逐条比对 zink 需求。

```
扩展齐备 → 设 Zink/Kopper 环境变量 → OpenGL 硬件加速
扩展缺失 → 不设这些变量 → 明确落到 softpipe → 设置页明写"走软件渲染"
```

这不是修好了硬件加速，而是把"静默失败"变成了"明确告诉用户 OpenGL 没走 GPU"。Adreno / Turnip 两个扩展齐备，高通设备不受影响。

---

## 5. 为什么不能补 GLX 或直接用 host OpenGL

### 5.1 问题出在哪一层

先理清楚 OpenGL 到 GPU 有几层：

```
层次 1: API 层    — Windows 游戏调 wglCreateContext / glDraw 等
层次 2: 窗口接口  — GLX 或 EGL，负责建 context、绑窗口、swap
层次 3: 翻译层    — 把桌面 OpenGL 命令翻成 GPU 能执行的格式
层次 4: GPU 驱动  — Mali Vulkan HAL / Mali GLES driver
```

Amphora 当前缺的是**层次 3**：没有一个能把桌面 OpenGL 翻译成 Mali 能执行的东西。

补 GLX 只解决**层次 2**——让 Wine 能通过 GLX 建 context。但 context 建好了，后面的 GL 命令谁来翻？Mali 的 GLES driver 不能直接执行桌面 OpenGL 调用。

### 5.2 "直接用宿主 OpenGL"的前提不成立

一个常见想法：Mali GPU 不是有图形驱动吗？直接用不就行了？

**不能，因为 Android 的 Mali 驱动不提供桌面 OpenGL。**

设备上的驱动文件：

```
/vendor/lib64/egl/libGLES_mali.so   → 只提供 OpenGL ES
vendor Vulkan HAL                   → 只提供 Vulkan
```

没有桌面 OpenGL driver（`libGL.so` 意义上的）。

回顾 §2.1：桌面 OpenGL 有固定管线、display list、`glPushAttrib` 等，GLES 全没有。Windows 游戏调 `glBegin(GL_TRIANGLES)`，Mali GLES driver 不知道这个函数是什么。

所以"直接用宿主 OpenGL"这条路在 Android Mali 上从源头就不存在。不管 GLX 补不补，中间都必须有一层翻译。

### 5.3 Direct GLX：补了也是绕回 Zink

标准 Linux 桌面上 Direct GLX 的路径：

```
Wine → Mesa GLX client → DRI3 → Mesa GPU driver → GPU
```

X Server 的 GLX 只负责协商（visual 选择、context 管理），真正的 GL 命令由**客户端的 Mesa** 直接执行，不走 X 协议。

但当前 Mali Android 上：

- 没有可直接使用的桌面 OpenGL Mali DRI driver——开源的 Panfrost 只适用 Mali GPU 的开源栈，不适用 vendor blob；
- Android 应用**无权打开** `/dev/dri/render*`（DRI3 Open 返回零 FD）；
- imagefs Mesa 能用的 GPU 后端只有 Zink。

所以即使补齐 GLX，路径仍然是：

```
Wine → Mesa GLX client → Zink → Vulkan → Mali
```

绕了一圈又回到 Zink，还是卡在 Mali 缺两个 Vulkan 扩展上。GLX 换了层次 2，没动层次 3。

### 5.4 Indirect GLX：等于从零造一套 VirGL

还有一想法：Indirect GLX。让 X Server 不只是协商，而是**接收完整的 GL 命令并在服务端执行**。听起来像"X Server 直接用 host GLES 渲染"。

但要实现这个，你得从零做五件事：

**第一件：实现完整的 GLX 协议。**

GLX 不只是让 `XQueryExtension("GLX")` 返回"GLX 存在"。它有一整套请求：`glXCreateContext`、`glXMakeCurrent`、`glXCreateGLXPixmap`、`glXDestroyContext`、`glXGetFBConfigs`、`glXCreatePbuffer`……还有最复杂的 `glXRender`——客户端把一串 GL 命令打包进一个 X 请求发过来，X Server 要拆包、逐条执行。

**第二件：管理 context / drawable 生命周期。**

X Server 要维护 GLX context 的状态机、visual/fbconfig 选择表、GLX pixmap 与 X pixmap 的绑定、pbuffer 的 backing storage、context 与 drawable 的 MakeCurrent 绑定。这是一整套资源管理。

**第三件：把桌面 GL 翻译成 GLES。**

这是最大的一块。如果 X Server 收到 GLX 请求后用 GLES 执行，你得自己处理全部 API 差异：

- 固定管线（`glBegin`/`glEnd`/`glVertex3f`/`glColor3f`/`glNormal3f`/`glTexCoord2f`）：GLES 完全没有这套 API。你得用 shader 模拟——把固定管线命令翻译成顶点 shader + 片段 shader。
- display list（`glNewList`/`glCallList`）：GLES 没有。你得自己缓存命令序列、在 `glCallList` 时重放。
- `glPushAttrib`/`glPopAttrib`：GLES 没有。你得自己保存和恢复全部 GL 状态（blend、depth、stencil、texture binding……几十个状态）。
- `glDrawPixels`/`glBitmap`/`glCopyPixels`：GLES 全删了。你得用 texture upload + quad draw 模拟。
- GLSL 翻译：桌面 GLSL 和 GLES GLSL 语法不同（precision qualifier、varying 声明、`gl_FragColor` 等），要转换。
- 格式映射：桌面 GL 的 `GL_RGB8` / `GL_RGBA16F` / 各种 compressed format 要映射到 GLES 支持的格式。

这部分的代码量是几万行 C。

**第四件：命令序列化传输。**

Indirect GLX 下，每个 GL 调用要序列化成 X 协议包、通过 Unix socket 发给 X Server、X Server 解码后执行。对于高频调用（每帧几百个 `glDraw`/`glUniform`），per-call 的 X 协议开销很高。

**第五件：把 GLES framebuffer 交给 Vulkan 合成器。**

X Server 用 GLES 渲染完一帧后，结果在 GLES 的 framebuffer 里。但 Amphora 的合成器是 Vulkan 的，它不认 GLES framebuffer。你要么 `glReadPixels` 读回 CPU 再上传给 Vulkan（慢），要么搭一座桥让 GLES 和 Vulkan 共享同一块内存。

**而 VirGL 是一个现成的项目，它的整个代码库就是在做这五件事。**

VirGL 不是"做了类似的事"，而是 VirGL **就是**做这件事的：

| 你要为 Indirect GLX 做的 | VirGL 已有的对应部分 |
|---|---|
| 完整 GLX 协议解析 | vtest 协议（CREATE_RENDERER / RESOURCE_CREATE / SUBMIT_CMD / FLUSH_FRONTBUFFER……） |
| context/drawable 生命周期 | virglrenderer 内部的 virgl context / resource / framebuffer 对象模型 |
| 桌面 GL → GLES 翻译 | vrend——固定管线→shader（`vrend_shader.c`）、GLSL 翻译（`vrend_shader_tgsi.c`）、格式映射（`vrend_format.c`） |
| 命令序列化传输 | guest Mesa virgl driver 把 GL 命令翻译成 TGSI 中间表示、打包成 command buffer、batch 发送 |
| GLES framebuffer → 合成器 | virgl 资源绑成 FBO → 导出 AHardwareBuffer → 走 Amphora 现有 DRI3/Present 通路 |

Indirect GLX 只是把传输层从 vtest socket 换成 X 协议，后面的翻译工作一模一样。VirGL 的 vrend 已经经过 ChromeOS / Crostini / QEMU 多年验证，兼容性远比自己从头写好。

所以结论：**与其补 GLX 从零实现一套桌面 GL → GLES 翻译，不如直接用 VirGL。**

---

## 6. VirGL：绕开 Vulkan，走原生 GLES

### 6.1 思路

既然 Mali 的 Vulkan 不够用，但 **GLES 是成熟的**，那就别碰 Vulkan：

```
Windows 桌面 OpenGL
  → Mesa virgl/virpipe（把 GL 命令翻译成 virgl 协议的中间表示）
  → Unix socket（跨进程传输）
  → virglrenderer（安卓侧，接收命令、用原生 GLES 执行）
  → Mali GLES driver（/vendor/lib64/egl/libGLES_mali.so）
  → Mali GPU
```

VirGL 本来就是为"非 Adreno GPU（Mali、PowerVR）"设计的，只要求 GLES 3.x。

### 6.2 阶段 0 结果（2026-08-13，已通过）

不改 app 代码，纯手工验证：

1. imagefs Mesa 加了 `virgl` gallium driver（[imagefs#5](https://github.com/amphora-dev/imagefs/pull/5)，CI 绿）。
2. 用 Termux 的 `virglrenderer-android`（上游 vtest 协议，免移植 libepoxy/gbm）。
3. guest 侧设 `GALLIUM_DRIVER=virpipe` + `LIBGL_ALWAYS_SOFTWARE=1` + `VTEST_SOCKET_NAME=<路径>`。
4. 真机两次复现：guest Mesa 连上 vtest socket，server 子进程加载 `/vendor/lib64/egl/libGLES_mali.so`——原生 Mali GLES 驱动。

**性能判据通过**：

| 基线 | VirGL | 结论 |
|---|---|---|
| softpipe 约 30 fps | 100+ fps | ≥3× softpipe，进入阶段 1 |

> 注：之前文档里写的 "519 fps" 是 AIO 测试列表里其他项目（D3D11）的残留值，不是 OpenGL 成绩，已纠正。

### 6.3 阶段 0 的帧搬运路径（性能下限）

当前走的是上游 vtest 语义，帧要跨进程搬好几次：

```
Mali GPU 渲染
  → glReadPixels（GPU 回读到 CPU）
  → Unix socket（像素从 host 传到 guest）
  → guest Mesa 收到帧
  → X PutImage（帧再从 guest 传给 X Server）
  → Java X Server 收到
  → Vulkan 合成器采样
  → TextureView 显示
```

像素跨了两次进程边界、拷了至少两遍。**测出来的 100+ fps 是性能下限**——这还是在有 readback 和 socket 传输的情况下达到的。

### 6.4 阶段 1：集成进 app

把手工验证变成正式功能：

| 改动 | 位置 | 性质 |
|---|---|---|
| virglrenderer 源码树 + CMake target | `core/native/src/main/cpp/virglrenderer/` | 新增 so |
| 链 EGL/GLESv2/GLESv3 | `core/native/.../CMakeLists.txt` | 现在不链 GL |
| `VirGLRendererComponent` | `runtime/display/environment/components/` | 挂 XConnectorEpoll |
| OpenGL 后端三态化 | `XServerWineSessionPreparer.applyGalliumDriver` | zink / virpipe / 不设 |
| 驱动选择维度 | `GraphicsDriverIds.kt` | VirGL 是 OpenGL 后端轴，正交于 Vulkan ICD |
| 设置项与文案 | `SettingsViewModel` + `CommonSettings` | 两态提示改三态 |

### 6.5 阶段 2：零拷贝上屏

用 AHardwareBuffer 搭桥，去掉 readback 和 socket 帧传输：

- host 侧 virglrenderer 分配 AHB，用 `EGL_ANDROID_image_native_buffer` 导入成 EGLImage 绑 FBO；
- Vulkan 合成器侧用 `GPUImage.java`（`VK_ANDROID_external_memory_android_hardware_buffer`）采样同一块内存；
- 复用 DXVK 已跑通的 DRI3 零拷贝 scanout 通路。

完成后接近：

```
Mali GLES image → AHardwareBuffer → Vulkan compositor
```

没有 readback、没有 socket 传整帧、没有 PutImage。

---

## 7. 环境变量怎么配合的

几个变量处于不同层级，不能互换。

### 7.1 每个变量管什么

| 环境变量 | 层级 | 决定什么 |
|---|---|---|
| `WINE_USE_EGL=1` | Wine 层 | Wine 用 EGL 后端而非 GLX |
| `MESA_LOADER_DRIVER_OVERRIDE=zink` | Mesa EGL 层 | Mesa EGL 把整个显示后端选为 Zink/Kopper |
| `LIBGL_KOPPER_DRI2=1` | Mesa Kopper 层 | 强制 Kopper 绕过标准 DRI3 能力检查 |
| `GALLIUM_DRIVER=zink` | Mesa Gallium 层 | 只在 Gallium 建 pipe_screen 时指定 Zink，不改变 EGL 已选的上屏路径 |
| `LIBGL_ALWAYS_SOFTWARE=1` | Mesa EGL 层 | 要求软件路径；和 Zink 互斥 |
| `LIBGL_KOPPER_DISABLE` | Mesa Kopper 层 | 故意不设；会关掉 Kopper，而 Kopper 正是 Zink 上屏的机制 |

### 7.2 为什么核心是 `MESA_LOADER_DRIVER_OVERRIDE`

Mesa 的 EGL 初始化逻辑（`eglapi.c`）：

```c
ForceSoftware = getenv("LIBGL_ALWAYS_SOFTWARE");
env = getenv("MESA_LOADER_DRIVER_OVERRIDE");
Options.Zink = env && !strcmp(env, "zink");
```

也就是说：

```
MESA_LOADER_DRIVER_OVERRIDE=zink
  → EGL display 的 Options.Zink = true
  → X11 EGL 初始化时 driver_name = "zink"
  → 选择 dri2_initialize_x11_kopper()
  → eglSwapBuffers → Kopper → vkQueuePresentKHR
```

这个变量决定的是"EGL 从一开始走哪条路"。设成 zink，EGL 全程走 Zink/Kopper，渲染和上屏匹配。

### 7.3 为什么不设 `GALLIUM_DRIVER=zink`

`GALLIUM_DRIVER` 被读取的时间更晚。它只作用于软件 winsys 建 Gallium screen 时选哪个 driver：

```c
const char *drivers[] = {
    debug_get_option("GALLIUM_DRIVER", ""),   // 如果设了，用这个
    "llvmpipe",                                // 否则试 llvmpipe
    "softpipe",                                // 再否则试 softpipe
};
```

问题是：如果 EGL 此时已经选了 swrast/drisw 上屏框架（因为没有设 `MESA_LOADER_DRIVER_OVERRIDE`），`GALLIUM_DRIVER=zink` 会在软件上屏路径里塞一个 Zink screen——**渲染在 GPU，但上屏走 `xcb_put_image`（CPU 搬像素）**。

Zink 的 `flush_frontbuffer` 只认识 Kopper display target，不会把 Vulkan framebuffer 自动读回 CPU 再交给 `xcb_put_image`。结果：**GPU 在渲染，窗口黑屏**。

更糟的是 Mesa 有个"EGL 初始化失败时自动重试 Zink"的 fallback：

```c
if (!Options.Zink && !getenv("GALLIUM_DRIVER")) {
    Options.Zink = true;
    retry_initialize();
}
```

只要设了任何 `GALLIUM_DRIVER`，这次自动重试就被跳过——**设它正是把 Kopper 关掉的原因**。

### 7.4 `LIBGL_KOPPER_DRI2` 不选 Zink

它不负责选择 Zink。它是在 `Options.Zink` 已经为 true 的前提下，让 Kopper 继续初始化：

```c
force_zink = Options.Zink && getenv("LIBGL_KOPPER_DRI2");
```

Amphora 必须强制，因为标准 Kopper 初始化时会检查 X Server 的 DRI3 能力：

1. `DRI3Open` 能不能返回一个有效的 render-device FD？
2. X Server 有没有 DRI3 multibuffer 支持？

Amphora 的 X Server 第 1 项不满足（DRI3 Open 返回零 FD，因为 Android 应用打不开 `/dev/dri/render*`）。标准 Kopper 看到零 FD 就认为"这个 X Server 不够格"，拒绝初始化。

但实际 present 不需要 DRM FD——Kopper 走的是 `VkSurfaceKHR → VK_KHR_xcb_surface → AHardwareBuffer → DRI3 PixmapFromBuffers → Present`，DXVK 已经在用这条路。

所以 `LIBGL_KOPPER_DRI2=1` 是"缺标准 DRI3 条件时仍强制启用 Kopper"的逃生舱。名字有误导：它不是"用传统 DRI2 渲染"。

### 7.5 正确组合

设（Adreno 上）：

```bash
WINE_USE_EGL=1
MESA_LOADER_DRIVER_OVERRIDE=zink
LIBGL_KOPPER_DRI2=1
```

不设：

```bash
GALLIUM_DRIVER
LIBGL_ALWAYS_SOFTWARE
LIBGL_KOPPER_DISABLE
```

Mali 上 zink 跑不起来（缺扩展），所以 zink 相关变量全部不设，OpenGL 落 softpipe。VirGL 集成后，对应位置改成 `GALLIUM_DRIVER=virpipe`。

---

## 8. DRI3 在 Amphora 上怎么工作

### 8.1 标准桌面 vs Amphora

标准 Linux 桌面：

```
客户端: DRI3 Open → X Server 打开 /dev/dri/renderD128 → 返回 DRM FD
客户端: 拿到 FD，直接和 GPU 驱动通信，做 dmabuf 共享
客户端: 渲染完后，DRI3 PixmapFromBuffers 把 buffer 传给 X Server
X Server: 显示这个 buffer
```

Amphora：

```
客户端: DRI3 Open → X Server 返回零 FD（Android 无权开 render node）
客户端: 不需要那个 FD
客户端: 渲染完后，DRI3 PixmapFromBuffers 通过 AHB socket 传 buffer
X Server: GPUImage.nativeAhbImportFromSocket 接收 AHB
合成器: vkr_texture_import_ahb 把 AHB 绑成 VkImage，采样显示
```

### 8.2 这条路有什么问题吗

实际工作得不错。DXVK 每天在走这条路，D3D11 跑 3192 fps。但有几个已知限制：

**格式限制**：只接受 BGRA_8888、RGBA_8888、RGBX_8888 三种 AHB 格式。其他格式回退到 SHM（CPU 拷贝），游戏不会知道。

**DRI3 版本锁在 1.2**：故意不上 1.3，因为 1.3 的 FenceFromFD 会加一个 sync 线程，和 X dispatch 线程竞争锁，导致帧率回归。代价是没有 fence 同步，靠 Vulkan 的 submit 顺序保证。

**外部格式 AHB 不支持**：Vulkan 返回 `VK_FORMAT_UNDEFINED` 的 vendor 外部格式（通常 YCbCr）会被拒绝，因为当前渲染器只有一个 mutable sampler layout，接不了 YCbcr conversion。

### 8.3 这是 WinNative 的代码吗

是的。DRI3Extension、GPUImage、gpu_image.c、vk_image.c 都来自 WinNative 的 runtime 内核，在 `dee877e`（P1 移植）整体搬入。

Amphora 在这之上的改动：删掉了 GPUImage 里原来的 EGL/GLES interop，改成纯 AHardwareBuffer 生命周期管理 + Vulkan 直接消费。原注释写的是："All EGL/GLES interop has been removed; the Vulkan compositor consumes the AHB directly"。

这对 VirGL 的意义是：阶段 2 零拷贝不需要新写任何基础设施——DXVK 已经在跑这条路的代码，VirGL 只要能把帧写进 AHB 就行。

---

## 9. 当前状态与下一步

| 路径 | 状态 | 性能 |
|---|---|---|
| EGL + Zink（Adreno/Turnip） | 正式可用 | GPU 加速，零拷贝 |
| EGL + softpipe（Mali） | 正式可用 | ~30 fps，CPU 渲染 |
| EGL + VirGL 阶段 0（Mali） | 真机验证通过 | 100+ fps（下限） |
| VirGL 阶段 1 集成 | 待开工 | 预期 ≥ 阶段 0 |
| VirGL 阶段 2 零拷贝 | 规划中 | 去掉 readback/传输 |
| GLX / Indirect GLX | 不适用 | 不解决桌面 GL → GLES 翻译问题 |

**下一步**：把 virglrenderer 生命周期、vtest socket 和 OpenGL 后端三态选择（zink / virpipe / 不设）集成进 app，替换当前的手工 env 下发。
