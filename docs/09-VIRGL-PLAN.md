# 09 - VirGL：Mali 上的硬件 OpenGL 方案

> 目的：给非 Adreno 设备（Mali / Xclipse / PowerVR）一条真正走 GPU 的 guest OpenGL 通路。
> 状态：**方案待审，未动工**。
> 最后更新: 2026-08-13 · 定位设备: 华为 Mali-G76 (r34 blob, Vulkan 1.1.191)。

---

## 1. 为什么需要这条路

当前 guest OpenGL 只有一条加速通路——Mesa zink（GL→Vulkan）。2026-08-13 实测确认它在
Mali 上是**死路**，且失败是无声的：

- AIO Graphics Test 的 GPU Info 报 renderer = **softpipe**，`3.3 (Compatibility Profile)
  Mesa 26.2.0`，Vendor `Mesa`。同一次运行里 D3D11（DXVK-Sarek → Vulkan）**3192 fps**，
  OpenGL 只有 **519 fps**。
- `wine_stderr.log` 里唯一的线索是 `MESA-EGL: warning: egl: failed to create dri2 screen`。
- 根因：Mali-G76 r34 缺 zink 硬性要求的 `VK_KHR_dynamic_rendering` 与
  `VK_EXT_robustness2`（`nullDescriptor`）。用 NDK 探针枚举了该设备全部 76 个设备扩展逐条比对确认。
- 无法绕过：zink 已删除 renderpass 路径（Mesa 25.2 的 `zink_render_pass.c` 只剩属性计算，
  全文没有 `vkCreateRenderPass`），`zink_context.c` 里 `dynamic_render` 零条件分支。
- 之所以无声：zink 在 `driver_name_is_inferred` 时屏蔽自己全部 `mesa_loge`，而"缺必需扩展"
  那条只走 `debug_printf`，release（`-Db_ndebug=true`）编译掉了。

雪上加霜的是 imagefs 的 Mesa 是 `-Dgallium-drivers=zink,softpipe -Dllvm=disabled`，
**连 llvmpipe 都没有**，所以软件回退落在最慢的参考光栅器上。

细节见 `03-TRACKING.md` 同日条目与 `core/engine/.../ZinkRequirements.kt` 的文件注释。

**VirGL 绕开整个问题**：guest 用 Mesa 的 virgl gallium 驱动把 GL 命令序列化，安卓侧
virglrenderer 用**原生 GLES** 执行。不碰 Vulkan，也就不关心 Mali 缺哪些 Vulkan 扩展，
而 Mali 的 GLES 驱动是成熟的。Winlator 上游文档对 VirGL 的定位就是
"Best for: Non-Adreno GPUs (Mali, Immortalis, PowerVR)"，只要求 GLES 3.x。

---

## 2. 勘察结论：Winlator 的实现不能直接拿来用

### 2.1 那份代码是死的

| | winlator | winlator-bionic | amphora |
|---|---|---|---|
| `cpp/virglrenderer/` 源码树 | 有（36 C + 72 H，2.0M） | 有 | 无 |
| 主 `CMakeLists.txt` 引用 | **无** | **无** | — |
| `VirGLRendererComponent.java` | 有，**零调用点** | **类不存在** | 无 |
| `VIRGL_SERVER_PATH` 常量 | 有，**零引用** | 有，零引用 | 无 |
| 实际下发的 guest env | `GALLIUM_DRIVER=zink` | `GALLIUM_DRIVER=zink` | 不设（走 `MESA_LOADER_DRIVER_OVERRIDE`） |

决定性证据：JNI 导出符号写的是
`Java_com_winlator_xenvironment_components_VirGLRendererComponent_handleNewConnection`
（`server/virgl_server.c:119`），而 Java 类在两个 fork 里都已挪到 `com.winlator.cmod.*` 包下。
**这段 C 自 cmod 改包名之后就没被链接过。**

amphora 侧则是剥离干净的：全仓搜 `virgl|virpipe|vtest|GALLIUM_DRIVER` 只命中三个 Markdown
文件的历史记述，源码/构建/资产/测试零命中。`GALLIUM_DRIVER` 还有断言守着不许设
（`PreparerGraphicsDriverTest.kt`，原因见 `XServerWineSessionPreparer.kt:1308` 起的注释）。

### 2.2 真正的障碍：合成器架构对不上

Winlator 的 VirGL 依赖一条 GLES 捷径：

- `XServerView extends GLSurfaceView` + `GLRenderer`（GLES 合成器）
- virglrenderer 创建 EGL context 时以合成器的 context 为 **shared context**
  （`virgl_server_renderer.c:125-133`），Java 侧靠往 GLSurfaceView 渲染线程 `queueEvent`
  再 `wait/notify` 抓这个指针
- 上屏就是一句 `glCopyTexImage2D`：host 把 virgl 资源绑成 FBO，回调 Java，
  `Texture.copyFromFramebuffer()` 拷进 X drawable 的 GL texture
  （`virgl_server_renderer.c:556-577` → `VirGLRendererComponent.java:99-125` →
  `renderer/Texture.java:114-123`）

amphora 的合成器是**纯 Vulkan**：

- `runtime/display/ui/XServerSurfaceView.java:27` 是 `extends TextureView`（连 SurfaceView 都不是）
- `renderer/VulkanRenderer.java` 取代了 `GLRenderer`，**没有可以交出去的 EGL context**
- `renderer/Texture.java` 是 "Vulkan-backed texture"（VkImage / VkImageView / VkSampler），
  **没有 `textureId`，没有 `copyFromFramebuffer`**
- `core/native/.../CMakeLists.txt:218` 的 `libwinlator.so` 链的是
  `log android jnigraphics vulkan adrenotools ...`，**不链 EGL / GLESv2 / GLESv3**

所以那条捷径在我们这儿不存在，得另外搭桥。

### 2.3 Winlator 还改了线协议

`server/virgl_server_protocol.h:26-36` 的命令集与上游 vtest **编号不同**，并多出一条
上游没有的 `VCMD_FLUSH_FRONTBUFFER = 9`：

| Winlator | 上游 vtest |
|---|---|
| 1 CREATE_RENDERER / 2 GET_CAPS / 3 RESOURCE_CREATE / 4 RESOURCE_DESTROY | 1 GET_CAPS / 2 RESOURCE_CREATE / 3 RESOURCE_UNREF |
| 5 TRANSFER_GET / 6 TRANSFER_PUT / 7 SUBMIT_CMD / 8 RESOURCE_BUSY_WAIT | 4 TRANSFER_GET / 5 TRANSFER_PUT / 6 SUBMIT_CMD / 7 BUSY_WAIT / 8 CREATE_RENDERER |
| **9 FLUSH_FRONTBUFFER（上游无）** | 9 GET_CAPS2 … |

这意味着 guest 侧的 Mesa 必须是**打过配套补丁的 virgl vtest winsys**。Winlator 交付的
`virgl-23.1.9.tzst`（3.4M，一个 `libGL.so.1.7.0`）就是这么来的，而且是 glibc 时代的构建，
bionic imagefs 用不了。

**这个 fork 不是随手改的**——它正是为了避开上游 vtest 的双份拷贝，见 §4。

---

## 3. 选型一：以哪份 virglrenderer 为基底

### 方案 A：上游 virglrenderer

优点：vrend 是现代的，和 imagefs 的 Mesa 26 guest 驱动同代；协议标准，**guest Mesa 不用打补丁**。

缺点已核实，代价不小：

```
meson.build:88   epoxy_dep = dependency('epoxy', version: '>= 1.5.4')
meson.build:302  gbm_dep = dependency('gbm', version: '>= 18.0.0', required: require_egl)
```

上游硬依赖 **libepoxy**，而且要开 EGL 平台还要 **gbm**。安卓上两个都得先移植
（AOSP 用 minigbm 给 cuttlefish 做过，但那是另一套构建）。等于开工前先欠两个 port。

### 方案 B：Winlator 的 fork

优点：**今天就能在 NDK 下编出来**。它把 epoxy 整个拿掉了，直接 include
`<GLES2/gl2.h> <GLES3/gl3.h> <EGL/egl.h>`（`src/vrend_util.h:33-37`），不需要 gbm，
CMake 只链 `log android EGL GLESv2 GLESv3`。`winlator-imagefs/build-native-libs.sh:198-221`
有过可用的构建 target，产物 433K。全程 `eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx)`
纯离屏，`printf` 重定向 logcat，`memfd_create` 手写 syscall 包装绕开 bionic 版本差异——
安卓适配已经做完了。

缺点：
1. vrend 是 ~Mesa 23 时代的，和 Mesa 26 的 guest virgl 驱动跨了三年。virgl 协议靠 caps 协商，
   理论上会优雅降级，但**没有验证过这个组合**。
2. 带自定义协议，guest Mesa 必须打补丁。
3. 它把 vrend 的全局状态改造成了 per-client（`struct virgl_client`，每个 vrend 调用都带
   client 指针），这是相对上游相当侵入的一处改动，未来跟上游同步会很痛。

### 建议

**取现代 virglrenderer，照 Winlator 的做法去掉 epoxy/gbm 依赖。** 理由：

- 去 epoxy 是机械工作（把 `<epoxy/gl.h>` 换成 GLES 头 + 一层 `eglGetProcAddress` 的
  函数指针加载），Winlator 已经证明可行，我们照着做但不继承它那个老 vrend。
- gbm 只在 virglrenderer 自己要开 render node 的 EGL 平台时才需要；我们用 surfaceless
  EGL + 安卓原生 GLES，不走那条路，可以直接把它 patch 掉。
- 换来的是和 Mesa 26 同代的 vrend，避免了方案 B 最难排查的那类问题。

这条路仍然需要 guest Mesa 打补丁（见 §4），但补丁面小——改的是
`src/gallium/winsys/virgl/vtest/` 下的 socket 与 winsys 两个文件，几百行量级。
imagefs 的 Mesa 是自建的（`packages/graphics/mesa-gl.sh`，上游源码 + 自定义 meson 参数 +
Termux 链接画像），补丁挂进去不难，但**该脚本目前是否已有 patch 步骤未核实**，要开工前确认。

---

## 4. 选型二：帧怎么上屏

### 路线 1：上游 vtest 语义（host 回读 → guest drisw → X PutImage）

上游 vtest 的设计是"host 渲染完读回帧、经 socket 送回 client、client 用 drisw 贴到屏幕"。
落到我们这儿，一帧的路径是：

```
Mali GPU → glReadPixels → unix socket → guest Mesa → X PutImage/MITSHM
        → app 的 Java X server → Vulkan 合成器 → TextureView
```

**像素跨了两次进程边界、拷了至少两遍**，而且 guest 和 host 本来就在同一台设备的同一个
app 里，这些搬运纯属浪费。好处是 guest Mesa 完全不用改，X server 侧也不用改
（MITSHM/PutImage 就是现在 softpipe 出图走的路）。

### 路线 2：host 侧直接落到 X drawable（Winlator 的做法）

加一条 `FLUSH_FRONTBUFFER` 命令，host 收到后把 virgl 资源直接绑成 FBO，blit 进 drawable 的
纹理，**像素一次都不过 socket**。这才是 Winlator 加那条命令的原因。

我们不能照抄它的实现（合成器是 Vulkan，没有共享 GL context），但可以用 AHardwareBuffer 搭桥：

- host 分配一个 AHB，GLES 侧用 `EGL_ANDROID_image_native_buffer` 导入成 EGLImage、
  `glEGLImageTargetTexture2DOES` 绑成 FBO 的颜色附件
- Vulkan 合成器侧用现成的 `renderer/GPUImage.java`（`VK_ANDROID_external_memory_android_hardware_buffer`）
  采样同一块内存
- drawable 走 `setTexture()` + `setDirectScanout(true)` + `setPresentedSourceSize()`，**复用
  DXVK 已经跑通的 DRI3 零拷贝 scanout 通路**（`DRI3Extension.java:34-43` 的 AHB modifier 分支）

这是 amphora 相对 Winlator 的**有利条件**：它们没有 AHB 基础设施，我们有。

### 建议

分两步：**先用路线 1 打通功能，再上路线 2 做零拷贝。** 路线 1 的价值在于它把"virgl 能不能
在这颗 Mali 上跑起来、快多少"和"零拷贝怎么搭"两个风险解耦——前者失败的话后者根本不用做。

---

## 5. 推荐的分阶段计划

### 阶段 0 · 可行性实测（先做，判据明确）

目标：**在不改 app 代码的前提下，量出 virgl over GLES 在这颗 Mali 上的真实帧率。**

1. imagefs 的 `packages/graphics/mesa-gl.sh` 把
   `-Dgallium-drivers=zink,softpipe` 改成 `zink,softpipe,virgl`。
   **virgl 不依赖 LLVM**，这点和 llvmpipe 完全不同，是个便宜改动。
2. 编一个 arm64 的独立 vtest server（现代 virglrenderer + 去 epoxy/gbm），先当普通可执行文件跑。
3. 手工把 server 起在 app 数据目录里的一个 socket 上，guest 侧设
   `GALLIUM_DRIVER=virpipe` + `LIBGL_ALWAYS_SOFTWARE=1` + `VTEST_SOCKET_NAME=<路径>`
   （用现成的 `advanced_custom_env` 就能下发，不用改代码）。
4. 跑 AIO Graphics Test 的 OpenGL 项，读 GPU Info 里的 renderer 字符串和 fps。

**判据**（当前基线：同一台设备上 OpenGL/softpipe 519 fps，D3D11/DXVK-Sarek 3192 fps）：

| 结果 | 结论 |
|---|---|
| renderer 显示 `virgl (Mali-G76)` 且 fps ≥ 3× softpipe | 继续阶段 1 |
| 能跑但 fps < 2× softpipe | 停，回头评估给 Leegao 补 zink 缺的两个扩展 |
| 跑不起来 | 记录失败点，重新评估 |

注意阶段 0 走的是路线 1（双份拷贝），**测出来的是下限**，阶段 2 的零拷贝还能再往上抬。

### 阶段 1 · 集成进 app

| 改动 | 位置 | 性质 |
|---|---|---|
| 引入 virglrenderer 源码树 + CMake target | `core/native/src/main/cpp/virglrenderer/` | 新增，`libwinlator.so` 之外单独一个 so |
| `libwinlator.so` 或新 so 链 EGL/GLESv2/GLESv3 | `core/native/src/main/cpp/CMakeLists.txt:218` | 现在完全不链 GL |
| `VirGLRendererComponent` | `runtime/display/environment/components/` | 挂到现成的 `XConnectorEpoll`，与 Winlator 同源，机械工作 |
| JNI 符号名 | virglrenderer 的 server 层 | amphora 的包路径是 `runtime.display.environment.components`，与两个 Winlator fork 都不同 |
| socket 常量 | `runtime/display/connector/UnixSocketConfig.java:7-10` | Winlator 用 `/tmp/.virgl/V0`，我们其余 socket 都在 `/usr/tmp/` 下，路径要跟 imagefs 布局对齐 |
| 驱动枚举 | `GraphicsDriverIds.kt` | 当前四个 id 全是 Vulkan ICD 维度，VirGL 是 **OpenGL 后端**维度，是新的一轴，不能简单塞进去（见 §7） |
| env 下发三态化 | `XServerWineSessionPreparer.kt:1336 applyGalliumDriver` | 现在是"zink 或什么都不设"两态；`GALLIUM_DRIVER=virpipe` 与 `MESA_LOADER_DRIVER_OVERRIDE=zink` **互斥** |
| 能力探测接线 | `GraphicsDriverCapabilities.kt` + `ZinkRequirements.kt` | 已有框架，VirGL 接成"zink 不可用时"的分支 |
| 设置项与文案 | `SettingsViewModel.kt` `OpenGlBackendStatus` + `CommonSettings.kt` | 已有的"OpenGL 走软件渲染"提示改成三态 |
| 资产 | `content_manifest` + `RuntimeAssetProvisioner` | 若 virgl 随 imagefs 的 megadriver 一起出，则**不需要**新资产 |

### 阶段 2 · 零拷贝（视阶段 0/1 结果决定）

加自定义 flush 命令 + AHB 搭桥，见 §4 路线 2。同时需要给 guest Mesa 的
`src/gallium/winsys/virgl/vtest/` 打补丁，挂进 imagefs 的 `packages/graphics/mesa-gl.sh`。

---

## 6. amphora 已具备的基础设施

这些都不用重做：

| 能力 | 位置 |
|---|---|
| Unix socket epoll 连接器 | `runtime/display/connector/XConnectorEpoll.java` + `cpp/winlator/xconnector_epoll.c` |
| 环境组件框架 | `runtime/display/environment/XEnvironment.java`；`WineEngineImpl.kt` 的 `buildEnvironment` 就是 `addComponent` + socket 的模式 |
| X server drawable 模型 | `Drawable.renderLock` / `getTexture()` / `setPresentedSourceSize()`，与 flush 回调所需的接口完全对得上 |
| AHardwareBuffer 零拷贝 scanout | `renderer/GPUImage.java` + `cpp/winlator/gpu_image.c` + `DRI3Extension.java` 的 AHB modifier 分支（DXVK 在用） |
| 原生构建体系 | `core/native/.../CMakeLists.txt`（FetchContent + add_subdirectory + glslc 都有） |
| 资产投递 | `ContentStagingPlugin` + manifest pin + SHA-256 校验 |
| GL 后端能力探测与文案 | `ZinkRequirements.kt` / `GraphicsDriverCapabilities.zinkBlockers()` / `OpenGlBackendStatus`（2026-08-13 新增） |

---

## 7. 未决问题

1. **驱动选择的维度**。`GraphicsDriverIds` 现在四个 id 全是"guest 用哪个 Vulkan ICD"，
   而 VirGL 回答的是"guest OpenGL 走哪条路"。这是**正交的两轴**：一台 Mali 设备完全可能
   Vulkan 走 Leegao（给 DXVK 用）、OpenGL 走 VirGL。UI 上是拆成两个设置项，还是合成一个
   预设，需要定。
2. **Mesa 26 guest virgl 驱动 × 我们的 vrend 版本**，caps 协商能否优雅降级，只能实测。
3. **socket 路径**。Winlator 用 `/tmp/.virgl/V0`，amphora 其余 socket 都在 `/usr/tmp/` 下
   （`UnixSocketConfig.java:7-10`），要跟 imagefs 布局对齐后再定。
4. **GL 版本上限**。virgl over GLES 3.2 host 能给 guest 到什么 GL 版本？社区数据显示
   `MESA_GL_VERSION_OVERRIDE=4.0` 是常见做法，说明默认可能偏低。要确认它够不够
   DirectDraw/WineD3D 那条路用。
5. **`LIBGL_ALWAYS_SOFTWARE=1` 与 `WINE_USE_EGL=1` 的相互作用**。当前我们强制 Wine 走 EGL
   后端（`applyWineEglBackend`，`XServerWineSessionPreparer.kt:1375`），virpipe 是从 sw
   winsys 路径选中的，两者组合没验证过。
6. **和 DirectDraw 的关系**。`DirectDrawWrapperIds.kt` 里 WineD3D 那档显式依赖 OpenGL，
   VirGL 生效后 DirectDraw 游戏也会改道，需要一起回归。

---

## 8. 被否掉的替代方案

| 方案 | 为什么否 |
|---|---|
| 给 zink 打补丁去掉 `dynamic_rendering` 必需 | zink 已删除 renderpass 路径，去掉断言只会在第一次 `vkCmdBeginRendering` 崩 |
| 让 Leegao wrapper 补上缺的扩展 | `VK_KHR_dynamic_rendering` 要在 wrapper 里按 `VkRenderingInfo` 动态造兼容 RenderPass+Framebuffer 并缓存、把 `VkPipelineRenderingCreateInfo` 翻成 dummy render pass、处理 resolve 与 suspend/resume 语义，几千行量级。`VK_EXT_robustness2` 的 `nullDescriptor` 倒是几百行可做。**没有完全排除，是 VirGL 失败后的备选。** |
| imagefs Mesa 加 llvmpipe | 只让软件渲染没那么慢，不解决硬件加速；且要先给 buildstream-sdk 加交叉编译的 target LLVM（几十分钟 CI 的新增构建），还有 llvmpipe 的 JIT 码要再被 box64 动态翻译一遍的不确定性 |
