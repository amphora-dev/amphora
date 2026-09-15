# 18 · wineandroid 与 X11 出画对照 + 一路踩坑

白话对照两条出画路：Winlator 式 **X11 + 单 TextureView 合成**，与 Amphora
**wineandroid 每 HWND 一块 SurfaceView**。布局/借上游细则仍见
[`16-WINEANDROID-DISPLAY.md`](16-WINEANDROID-DISPLAY.md)、
[`17-WINEANDROID-UPSTREAM-BORROW.md`](17-WINEANDROID-UPSTREAM-BORROW.md)。
本文补：**架构差异、为何暂留 SurfaceView、尺寸延迟、多 Surface 成本，以及本轨踩过的坑**。

---

## 1. X11 路径（对照）

- **一个**宿主 `XServerSurfaceView`（`TextureView`）。
- 每个 X 窗口的像素在各自 **X Drawable** 里；Java X server /
  `WindowManager` 管树。
- `VulkanRenderer.collectRenderableWindows` 从根往下走，把子窗
  **parent-relative 坐标累加到 root**，再一次性画进那块 TextureView。
- `ViewTransformation` 做 **screen → surface 的 letterbox / fit / zoom**（只在
  这一层做一次）。
- **改尺寸** = 换/重建 Drawable 到最终 w×h，再 rebuild scene。
- **没有** 每 HWND 的 `setFixedSize`，也 **没有** 每窗
  `re-registerSurface`。

关键文件大致在：

- `core/engine/.../runtime/display/ui/XServerSurfaceView.java`
- `core/engine/.../runtime/display/renderer/VulkanRenderer.java`
- `core/engine/.../runtime/display/renderer/ViewTransformation.java`
- `core/engine/.../runtime/display/xserver/`（`WindowManager`、`Drawable` 等）

这条路天然避开「Surface 先 1×1 / 2×2 再长大却忘了再 bind」这一类 bug。

---

## 2. Amphora wineandroid 路径（当前）

- **每个 HWND** 一块 `SurfaceView`，包在嵌套的 `WindowGroup` 里（借上游
  WineActivity 布局，见 docs/17；落地提交 `a5a0b0e`）。
- 布局：`visible_rect × hostScale`；buffer 用 `setFixedSize(guest px)`（最小
  2×2）。
- `surfaceChanged` → 再 `nativeRegisterSurface`（对齐上游
  `onSurfaceTextureSizeChanged` 再 bind）。
- **SurfaceFlinger** 按层合成这些 Surface（每层通常带自己的 BufferQueue）。
- 底部调试 **`statusView` 保留**（用户明确不要删；不是借上游项）。

关键：`WineAndroidDesktop.kt` / `WineAndroidHostBridge.kt` /
`WineAndroidSessionActivity.kt` / `wineandroid_host_ipc.c`。

---

## 3. 为何先保留 SurfaceView（不是「永远更好」）

- Amphora 要在宿主 letterbox 下对 **guest 像素** 做 `setFixedSize`；SurfaceView
  + `ANativeWindow` 这条路现在跑得通。
- HA262 上对 Surface 路径 **不要** `SET_BUFFERS_FORMAT(BGRA=5)`——会整机闪退；
  保持 **RGBA**，颜色靠软件 **R/B 交换**。上游 in-process TextureView 常按
  BGRA 假设，**不能照搬**。
- X11 对照路径用私有 **BGRA AHB + Vulkan 采样**，颜色问题被那条栈自己消化，
  和 wineandroid Surface 路径不是同一约束。
- **TextureView** = 以后可选打磨（少 punch-through 等），**不是** 现在必须先做的
  切换。先把尺寸/布局/再 bind 做稳。

---

## 4. 多 SurfaceView 贵不贵？

- explorer + 少量顶层窗：**可以接受**。
- 成本主要是 **SurfaceFlinger 层数 / BufferQueue**，不是玄学税。
- 原生 Android 游戏层通常更少；Wine shell（taskbar、Start、菜单）会更碎、更吵。
- 改进由轻到重（都还没当默认真源）：
  1. **推迟 / 晚创建** Surface（见 §5、§9）
  2. 可选改 **TextureView**（打磨，非欠账）
  3. **只给顶层 HWND 建 Surface**，子窗画进父层（一般仍保游戏全屏 ANW
     零拷贝；伤的是很多小 child HWND 分层）
  4. X11 式 **单合成器**（大改，另立项）

---

## 5. 尺寸延迟：为何曾出现 2×2 再长大

- Surface 生命周期往往早于真正的 `WINDOW_POS`；ANW **讨厌** 0/1 边长，所以上游
  / 我们也用 **最小 2×2**。
- 若先以 2×2（或 1×1）`registerSurface`，后来只 `setFixedSize` 到真尺寸却
  **不** 再 register → guest 卡在小 buffer（taskbar 扭曲就是这类）。
- **更好（尚未落地）**：推迟 **第一次** register，等到真实 w/h > 0（或 ≥2）；
  **之后** 的 resize 仍要再 bind（与上游 `onSurfaceTextureSizeChanged` 同类）。
- X11 路径没有「每窗 Surface 先注册再改尺寸」这一环，所以少踩这类坑。

---

## 6. 游戏零拷贝

- 「只给顶层建 Surface」策略，一般 **仍能** 保住全屏/游戏窗的 ANW present
  零拷贝。
- 代价是很多小 child HWND 不再各自一层，分层/透明/弹出可能变难。

---

## 7. 一路踩坑（按主题、大致时间线）

### 7.1 不出画：nodrv / 黑屏 / ENODEV

- 缺 `ANativeWindow_fromSurface` 或 Surface 没挂上 → native 侧拿不到窗。
- Box64 wrap / 驱动加载缺口 → 表现为 nodrv、黑屏。
- desktop HWND **没有** Surface，或 GDI 用了错误 **api** → `ENODEV` / `-11` /
  `-19` 一类失败。
- **教训**：GDI 要 `NATIVE_WINDOW_API_CPU`；desktop 也要走与普通 GDI 窗相同的
  `attachWindow` + `nativeRegisterSurface`（见 `d2010e1` 一带）。

### 7.2 换包旁路：`.local-override` 已废弃

- 旧的 `LOCAL_OVERRIDE` / `<file>.local-override` 路径已拿掉。
- 开发态换 WCP / runtime：用 **`dev_pins.json` content-manifest overlay**
  （`docs/15-DEV-PIN-OVERLAY.md`）+ `scripts/inject-dev-pin.sh`。
- inject 必须写入 WCP 的 **verName / version** 等 identity，否则 profile 对不上、
  看起来「钉了却没用上」。

### 7.3 颜色与格式（HA262）

- 对 Surface 路径 **禁止** `SET_BUFFERS_FORMAT(BGRA=5)`（曾整机崩溃）。
- 保持 **RGBA**，宿主做 **R/B swizzle**。
- 小角落、芥末色：常是 **没做宿主放大** + RGBA/BGRA 搞混叠在一起。
- 上游 TextureView 的 BGRA 假设 **不要** 盲抄到 Amphora Surface 路径。

### 7.4 宿主铺满与空窗

- 正确做法：`setFixedSize(guest)` + View 按 `hostScale` 放大（letterbox /
  scale-to-fill）；**不要** 靠改 guest `/desktop=` 分辨率「铺屏」。
- 曾踩：空 HWND 的 Surface 错误拉到整屏（如 3040×1710）→ 底栏等严重变形。

### 7.5 DPI

- 把 Android `densityDpi`（如 **440**）直接喂给 1280×720 → chrome 巨大。
- Winlator 风格约 **254** 在宿主再 ×2.375 时仍偏大。
- **产品默认**：经典 Wine DPI **96**（屏上看大约有效 ~228，视 hostScale）；
  多设备 `hostScale` / DPI 策略仍是 TODO（docs/16 已写）。

### 7.6 Taskbar / Start 错位与扭曲

- 平铺每 HWND 用「桌面绝对坐标」→ Start `(0,0)` 贴到桌面原点；X11 则是
  parent-relative **累加**。已借上游：**嵌套 WindowGroup + visible_rect**。
- 底部 **`statusView` 调试条保留**——用户不要删；别当成「借上游」任务去拆。
- taskbar **1×1 register** 后 `WINDOW_POS` 变大，却不 `surfaceChanged` 再
  register → 一直小 buffer。已修：尺寸变了必须再 bind（docs/16 §Surface）。

### 7.7 借上游该借什么、不借什么

- **已借（`a5a0b0e`）**：嵌套组、visible_rect、尺寸变化再 register、
  WS_VISIBLE / z-order、最小 2×2；**statusView 不动**。
- **保持 Amphora 自己的**：SurfaceView、`setFixedSize(guest)`、host letterbox、
  DPI 策略；不要为了「像上游」切 TextureView 或删 status。

### 7.8 同步与发版纪律

- 优先 **git bundle / Mac 本机 `assembleDebug` + adb**；**不要** 用
  CopyFromBox 倒腾约 80MB APK。
- 正包仍走 **imagefs `build-proton-wine`**；临时 `.so` **不是** 真源。
- 禁止 CreateSwapchain / 私有 `host.sock`。
- 本机 recc/CAS 增量编 vs GitHub Actions：加快迭代可以，**发版真源不变**。

---

## 8. 已落地（正反馈）

- 正式 WCP pin 路径；需要时用 `inject-dev-pin` 钉本地 Box64（identity 写全）。
- `wip/ha262-paint` 上嵌套 `WindowGroup` + visible_rect 等 borrow（`a5a0b0e`）。
- 出画：GDI `api=CPU`、桌面 SurfaceView、RGBA + swizzle、host scale-to-fill。
- 硬冒烟脚本 + 仓库旁 **`amphora-progress.md` 作单一进度源**（下一会话先读它）。

---

## 9. 下一步（尚未实现则别写成已做）

1. **推迟第一次 Surface register**，等到真实 w/h > 0（或 ≥2）；之后 resize 仍再
   bind。（优先、改动面小）
2. TextureView：可选打磨，非第一优先级。
3. 只给顶层建 Surface：更大改动；游戏全屏 ANW 零拷贝通常仍可接受。
4. 单合成器 X11 风格：另开大项。

---

## 非目标

- 删除 `statusView`
- 盲切 TextureView / 盲抄上游 BGRA
- CreateSwapchain、私有 host.sock、临时 `.so` 当真源
- Cursor cloud agents 改本仓（本轨约定本地 Grok Bot）
