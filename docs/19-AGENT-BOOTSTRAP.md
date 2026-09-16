# 19 · Agent 从零开工方法论（Amphora Android）

> 给任意新 agent / 多 bot 协作者：**先读本文 + `AGENTS.md`**，再动代码。  
> 可执行短配方见 [`.cursor/skills/amphora-from-zero/SKILL.md`](../.cursor/skills/amphora-from-zero/SKILL.md)。  
> 进度手记（会话间交接）：Mac 旁路 `amphora-progress.md`（若存在）；否则以本文 + `docs/16–18` + git log 为准。

## 0. 一句话

Amphora 是 Android 上的 Wine 模拟器。当前壳层真源是 **wineandroid**（每 HWND 一块 SurfaceView），不是 Winlator X11 长期内层。真机默认验证机是 Lenovo Y700（ADB `HA262AAH`）。

## 1. 产品三层（别搞混）

对应 `docs/12` + `DesktopActivity`，**不是** Android 企业 Work Profile，也不替换 ZUI Work：

1. **外层**：Amphora Desktop（壁纸 / 图标网格 / 底栏；v1 不抢 `SECONDARY_HOME`）
2. **中层**：一程序一 freeform 会话 Activity（默认 `WineAndroidSessionActivity`）
3. **内层**：`wineandroid` 管该会话里的 HWND；**不做**每 HWND 一个系统 freeform

改「只给顶层建 Surface」只影响会话内 SF 层数，**不改**「一程序一窗」壳模型。X11 单合成器不是长期内层。

## 2. 工作轨（分开排期）

| 轨 | 内容 | 主文档 |
|----|------|--------|
| **A 壳层** | 布局、Surface 时机、DPI、letterbox、输入 | `AGENTS.md`、`docs/16`–`18`、`12` |
| **B 游戏 present** | AHB import CreateSwapchain、HWND ANW 零拷贝 | `docs/13`–`14`（门已过；下一刀多为 CI 产物冒烟） |
| **C 构建** | imagefs / Proton WCP / content_manifest | imagefs 仓 + `docs/15` |

同一次任务不要把 A/B 搅在同一批 PR 里，除非用户明确要求。

## 3. 机器与路径

| 角色 | 机器 | 路径 |
|------|------|------|
| 改代码 / 读仓 / 文档 | Grok Bot 本机（约定） | `/home/box/co/github/amphora`（旁路可读 `/workspace/amphora-dev/amphora`；**勿为卫生删树**） |
| APK 真源 + adb | 用户 Mac mini | `/Users/sky/co/src/amphora-dev/amphora` |
| 真机 | Y700 TB322FC | serial **`HA262AAH`** |
| 进度手记 | Mac（常见） | `/Users/sky/co/src/amphora-dev/amphora-progress.md` |

**硬约定（本轨）**

- 业务代码改在 **Grok Bot 本机**，**不要**用 Cursor cloud agent 改本仓。
- **不要** CopyFromBox 倒腾约 80MB APK；在 Mac 上 `assembleDebug`。
- Git 作者（本机 local config）：`skywalker512` \<houzhenhong@outlook.com\>。
- 用户偏好中文沟通。

## 4. Clone（从零）

组织：`amphora-dev`（至少：`amphora`、`imagefs`、`content_manifest`、`proton-wine`；按任务再拉）。

```bash
mkdir -p /home/box/co/github && cd /home/box/co/github
# 需要鉴权时用已配置的 gh / SSH；勿把 token 打进聊天或日志
git clone git@github.com:amphora-dev/amphora.git
# 或: gh repo clone amphora-dev/amphora
cd amphora
git submodule update --init --recursive   # adrenotools 等；缺了 native 全量编会挂
```

壳层工作分支（除非用户另指）：

```bash
git fetch origin wip/ha262-paint
git checkout wip/ha262-paint
git merge --ff-only origin/wip/ha262-paint   # 或: git reset --hard FETCH_HEAD
```

**Mac 上 remote-tracking 分叉时**：`git fetch origin wip/ha262-paint` 后用 **`FETCH_HEAD`** 做 ff，不要死盯可能过期的 `origin/wip/ha262-paint` 对象。

旁路树 `/workspace/amphora-dev/*` 可以并存；**不要**为了路径整洁去删。

## 5. 必读顺序（开工前 10 分钟）

1. `AGENTS.md`（禁令 + 宿主出画要点）
2. 本文（方法论）
3. 进度：`amphora-progress.md`（若有）或 `git log --oneline -15`
4. 壳层：`docs/16` → `17` → `18`（对照 X11、踩坑、下一步）
5. 迁移阶段：`docs/12`（勾选可能略旧；以 git + 18 为准）
6. 若碰游戏 present：`docs/13`–`14`（勿退 AHB import）

## 6. 已落地事实（别再当欠账）

壳层（`wip/ha262-paint`，示例提交）：

- 嵌套 `WindowGroup` + `visible_rect`：`8a494cc`
- 推迟第一次 `nativeRegisterSurface` 到真实 guest 尺寸：`5515738`
- SurfaceView 触摸 → wineandroid `MOTION_EVENT`（不注入 X）：`d3a7bd5`
- 硬件 KEYBOARD → wineandroid `KEYBOARD_EVENT`（KEYCODE / `adb keyevent`）：`35c9921`
- Soft IME commit → 同 `KEYBOARD_EVENT` 管（`WineInputConnection` + `WineAndroidImeCommit`）；CJK → `KEYEVENTF_UNICODE`（`nativeSendUnicodeChar`）；**host composing chip**（`ImeUiState` / SessionActivity TextView）；composition **不**进 guest；**无** tap/focus 自动弹出（显式 chip / letterbox 长按 / `IME_SHOW` / `showSoftKeyboard` only）
- Debug IME unicode 自动冒烟 **PASS** on `702b165`（MainActivity 冷启 `IME_UNICODE_TEXT`；ART `ha262-ime-unicode-auto-20260916-140749`）
- Debug IME composing：**cold chip PASS** `01cf904`；**mid-session relay PASS** `fe8f5a2`（~14:22 Asia/Shanghai；ART `ha262-ime-composing-relay-20260916-142224`）
- **Keyboard chip + IME_SHOW serve-ready PASS** on `c5ede16`（~19:48 Asia/Shanghai；冷启 `IME_SHOW true` → `mInputShown=true` / `show served attempt=0`；chip show/hide；ART Mac `ha262-ime-show-serve-20260916-194731`）
- DPI 经典 **96** 已落地（`d264af1`）；hostScale 多分辨率单测已落地（`034b38e`）；guest 分辨率设置页已接线 `WineAndroidGuestResolution`；`fdda994` no-WIDTH pref；**第二台/分屏用户已停**
- WS_VISIBLE / sibling z-order：`8a494cc` + `WineAndroidWindowStack` 加固 `4d3d976`（隐窗 removeView + sync bringToFront）；**HA262 stack smoke PASS**（~16:48 Asia/Shanghai；ART `ha262-window-stack-20260916-164730`）；重叠 z-order 人工眼验仍可选
- **sensorLandscape** 会话锁：`a4cd0c0`；**HA262 PASS**（~17:40 Asia/Shanghai；portrait-locked → `SENSOR_LANDSCAPE`，ROTATION_90 **3040×1904**，hostScale **2.375**）
- **setCapture / setCursor** 壳层接线：capture HWND 路由 MOTION；PointerIcon 隐藏/系统/自定义 bits（无独立 cursor overlay）；debug `CAPTURE_HWND` 注入；**HA262 CAPTURE_HWND inject routing PASS** on `82bf652`（~19:52 Asia/Shanghai；ART Mac `ha262-capture-inject-20260916-195128`）；可选 title-bar 真 IOCTL_SET_CAPTURE 眼验仍可选
- **BACK / VOLUME_* 故意宿主穿透 PASS**：`WineAndroidKeyPassThrough` + Desktop `passThrough=intentional-host`；**HA262 PASS** on `02d04e1`（~19:57 Asia/Shanghai；tap Desktop 获焦后 keyevent；A ok=true；VOLUME/BACK intentional-host；BACK finish→MainActivity；ART Mac `ha262-back-passthrough-20260916-195631`）；native 仍 `keycode_to_vkey==0`（勿发明 guest vkey）
- GDI：`api=CPU`、RGBA（HA262 **禁** Surface `BGRA=5`）、host scale-to-fill

游戏轨：

- AHB import CreateSwapchain + Present≥50：见 `docs/13`
- HWND ANW 零拷贝热路径：见 `docs/14`；CI Present 冒烟 **先**合 AHB 补丁并 bump WCP，再真机；不手刀 sideload `.so`

## 7. 硬禁（违反即停）

- Surface/ANW 路径 `SET_BUFFERS_FORMAT(BGRA=5)`（保持 RGBA + 宿主 R/B swizzle）
- 删除 session 调试 `statusView`
- 把私有 CreateSwapchain / 私有 `host.sock` 当真源（官方 amphora AHB WSI 另见 docs/13）
- 盲切 TextureView / 整棵退回 X11 当长期内层
- 用壳层多 Surface「假装」游戏 present；DXVK→AHB 另轨
- 临时 `.so` / `.local-override` 当真源（开发钉包用 `docs/15` `dev_pins.json`）
- 为卫生删 checkout；Cursor cloud 改本轨代码
- 把 Android `densityDpi`（如 440）直接当 Wine DPI 喂 720p

## 8. 编译

### 8.1 App / APK（日常迭代 — 优先 Mac）

```bash
cd /Users/sky/co/src/amphora-dev/amphora   # Mac APK 真源
git fetch origin wip/ha262-paint && git merge --ff-only FETCH_HEAD
./gradlew :app:assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

Grok Bot 上可跑 Kotlin 编译做快速检查；**全量 native** 依赖 submodule（缺 `adrenotools` 会挂）。正式装机以 Mac APK 为准。

### 8.2 开发换包（WCP / Box64 等）

见 `docs/15` + `scripts/inject-dev-pin.sh`（写全 WCP identity）。不要复活 `.local-override`。

### 8.3 正式 WCP / imagefs

走 `amphora-dev/imagefs` 的 `build-proton-wine` + bump `content_manifest`。本机 CAS/recc 只加速迭代，不改发版真源。

## 9. 真机冒烟（HA262）

**门闩**：`adb devices` 必须看到 `HA262AAH` 为 `device`；否则停并上报，不要猜。

```bash
SERIAL=HA262AAH
adb -s $SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s $SERIAL logcat -c
adb -s $SERIAL shell am start -n app.amphora/.MainActivity
# UI：点「Open desktop」进 WineAndroidSessionActivity
# 或从最近项 / Desktop 进会话
```

### 9.1 壳层 Surface / defer

过滤：`WineAndroidDesktop|WineAndroidHostBridge|defer first register|registerSurface|setFixedSize|surfaceChanged`

**PASS 线索**：未知尺寸时有 `defer first register`；随后真实尺寸 register / `firstDone=true`；taskbar 不长期卡 1×1/2×2；无 FATAL/BGRA 闪退。

### 9.2 输入（MOTION + KEYBOARD）

**已落地**：MOTION（`d3a7bd5`）+ 硬件 KEYBOARD（`35c9921`）+ soft IME **commit**（`WineInputConnection` → KEYBOARD_EVENT）+ CJK **unicode**（`KEYEVENTF_UNICODE` / `nativeSendUnicodeChar`）+ **host composing overlay**（`ImeUiState` chip）。Composition **host-local only**。

过滤：`WineAndroidDesktop.*motion`；`WineAndroidDesktop.*key` / native `keyboard hwnd=` / `key hwnd=` / `keyboard unicode`；IME：`IME composing` / `IME unicode`

**PASS 线索**：`motion hwnd=… ok=true`（DOWN/UP）；UI 上可见点击效果（如 winefile / Start）；**不得**走 `XServerInputSink` / `TouchpadView`。

键盘（硬件 KEYCODE / `adb input keyevent`）：`key hwnd=… ok=true`；native `keyboard hwnd=… vkey=…`。`adb shell input keyevent 29`（A）或 `66`（ENTER）；`adb shell input text hello` 走 KEYCODE 注入。Soft IME：**不**随 tap/focus 自动弹出（策略：显式 chip `content-desc=wineandroid keyboard` / letterbox 长按 / `IME_SHOW` / `showSoftKeyboard` only）；commit ASCII 应见同样 `key hwnd=…`；CJK commit 应见 `IME unicode` + native `keyboard unicode uchar=`。**Debug unicode 自动冒烟 PASS**（`702b165`）。**Cold composing chip PASS**（`01cf904`）。**Mid-session composing relay PASS**（`fe8f5a2`）。**Keyboard chip + IME_SHOW serve-ready PASS**（`c5ede16`，~19:48 Asia/Shanghai；ART Mac `ha262-ime-show-serve-20260916-194731`）。**BACK/VOLUME intentional-host PASS**（`02d04e1`，~19:57；先 tap Desktop；ART Mac `ha262-back-passthrough-20260916-195631`）。**仍开（可选）**：真机 CJK soft IME 眼验 composing chip（`adb input text` 仅 ASCII）。

### 9.3 壳层 WS_VISIBLE / sibling z-order

**HA262 window-stack smoke PASS** on `4d3d976`（2026-09-16 ~16:48 Asia/Shanghai）：
session 1280×720；first register（desktop/taskbar/windows）；`windowPosChanged`
带 `style=`；无 FATAL。ART（Mac）：`smoke-artifacts/ha262-window-stack-20260916-164730`。
**仍开（可选）**：重叠 HWND z-order 人工眼验。

### 9.4 产物存放

建议 Mac：`/Users/sky/co/src/amphora-dev/smoke-artifacts/`（log + screencap）。`/tmp` 可能无法 CopyToBox。

### 9.5 无人值守

- **单一 owner bot** 跑 install；禁止并行 install 战争。
- 设备断开 / gradle 失败：失败即停并通知。
- 清设备 prefix 仅当用户明确要求。

## 10. 测试（仓内）

```bash
./gradlew :app:testDebugUnitTest          # 含 WineAndroidProtocolTest 等
# 仪器测试按模块；真机以 §9 为准
```

Native 单文件可在本机用 NDK clang `-c` 做语法级检查；不能替代装机冒烟。

## 11. 多 bot / skill / routine（怎么协作）

### 11.1 推荐形态

**一个 owner bot**（改代码 + 冒烟 + 对用户交付）  
+ **按需 specialist**（只读对照/调研，默认不改业务代码）  
+ **skill**（可复用步骤）  
+ **routine**（定时/事件触发，例如工作日真机冒烟）

不要默认再挂「总管 bot」叠一层；避免多人同时 `adb install`。

### 11.2 本仓 skill 入口

| 位置 | 用途 |
|------|------|
| `.cursor/skills/amphora-from-zero/SKILL.md` | **从零开工**（本文的可执行摘要） |
| Grok Bot workflows（若已装） | `amphora-ha262-wineandroid-shell`、`ha262-device-smoke`、`imagefs-local-build-with-actions-cas` |

新 bot：先读本 skill / 本文，再读轨专 skill；**不要**复制第二套互相打架的「真源路径」。

### 11.3 Specialist 边界示例

「出画路径对照」类 bot：只读仓与公开资料，交付对照结论；**不**擅自改粒度（顶层 Surface / TextureView / X11）除非用户点头。defer-register 门槛已过后，②仍属可选中期项。

## 12. 默认下一拍（文档顺序，可能随进度变）

**已落地（2026-09-16）**：MOTION + 硬件 KEYBOARD + soft IME commit + CJK
`KEYEVENTF_UNICODE`（**HA262 debug unicode 自动冒烟 PASS** @ `702b165`）+ **host
composing overlay** + **cold chip PASS** `01cf904` + **mid-session composing relay
PASS** `fe8f5a2` + **keyboard chip + IME_SHOW serve-ready PASS** @ `c5ede16`
（~19:48 Asia/Shanghai；ART Mac `ha262-ime-show-serve-20260916-194731`；策略=
**无** tap/focus 自动弹出，仅显式 chip / letterbox 长按 / `IME_SHOW` /
`showSoftKeyboard`）；DPI 96；hostScale 多分辨率单测（`034b38e`）+ HA262 旋转已验；
guest 分辨率预设目录 + **Settings/Launcher 接线**（`WineAndroidGuestResolution`）；
游戏轨 AHB 已合入 `proton_11.0` @ `0a64ebc`，WCP 已发，HA262 Present **v10 PASS**
（壳层当时 HEAD `728f3db`，~16:50 Asia/Shanghai；ART
`ci-present-20260916-165016-v10-reg`）；**sensorLandscape PASS** @ `a4cd0c0`
（~17:40；ROTATION_90 3040×1904，hostScale 2.375）；**setCapture / setCursor**
壳层接线（capture 路由 + PointerIcon；无 cursor overlay）；**CAPTURE_HWND inject
routing PASS** @ `82bf652`（~19:52 Asia/Shanghai；ART Mac
`ha262-capture-inject-20260916-195128`；`-1`→desktop、swipe routed、`0` release、relay 再捕获）；
**BACK/VOLUME intentional-host PASS** @ `02d04e1`（~19:57 Asia/Shanghai；ART Mac `ha262-back-passthrough-20260916-195631`；tap Desktop 获焦后 A ok=true；VOLUME/BACK `passThrough=intentional-host`；BACK finish→MainActivity）。

**IME / soft-IME 入口轨**：已关（除可选 CJK composing/commit 眼验）。

**本拍优先 / 仍开：**

1. **壳层叠窗（log smoke 已 PASS）**：WS_VISIBLE / sibling z-order @ `4d3d976`
   （session 1280×720；first register；`windowPosChanged`+`style=`；无 FATAL；
   ART `ha262-window-stack-20260916-164730`）。**仍开（可选）**：重叠 HWND
   z-order **人工眼验**；guest 分辨率 no-WIDTH pref（`fdda994`）真机 PASS 未宣称。
2. **可选**：真机 soft IME **CJK** 眼验 composing chip + commit（勿发明 IMM32/TSF；
   composition 已 host-local）。软键盘入口 / `IME_SHOW` serve-ready **已 PASS**，
   勿再垫 soft-IME 弹出刀。
3. **可选**：title-bar 真 `IOCTL_SET_CAPTURE` 眼验 + cursor hide/arrow（debug
   `CAPTURE_HWND` **routing 已 PASS** @ `82bf652`；勿再垫 inject 刀）。配方：
   `/workspace/ha262-capture-inject-recipe.md`（已验）；旧
   `/workspace/ha262-capture-cursor-smoke-recipe.md`（title-bar 手工）。
4. **可选**：重叠 HWND z-order **人工眼验**（log smoke 已 PASS；见 §9.3 /
   window-stack 配方）。guest 分辨率 no-WIDTH pref（`fdda994`）真机 PASS 仍未宣称。
   BACK/VOLUME intentional-host **已 PASS** @ `02d04e1`（勿再垫）；配方保留：
   `/workspace/ha262-back-passthrough-recipe.md`（**先 tap Desktop**）。

**已停 / 勿排下一拍**：第二台真机 / 分屏 / hostScale 双机（用户 2026-09-16 停）。

**不是**下一拍：TextureView、只给顶层 Surface、X11 单合成（`docs/18` §9 可选后置）；
勿再叠 Present 刀尖 `.so`；**不做分屏**；独立自定义光标 overlay View；IMM32/TSF。

动手前用 `git log` + progress **核对**上表，勿盲抄过期勾选。

## 13. 交付汇报模板

对用户报告时带齐：

- 分支 / `git rev-parse --short HEAD` / 是否已 push  
- 改动文件与意图（一句话）  
- 编译：哪台机器、是否成功  
- 冒烟：serial、PASS/FAIL、log 关键字摘录、截图路径  
- **仍开着的项**（勿把后置项标成完成）

## 14. 相关索引

- `AGENTS.md` — 开发态纪律与宿主禁令  
- `docs/05-ARCHITECTURE.md` — 模块与启动链  
- `docs/12` — wineandroid 切换阶段  
- `docs/13`–`14` — AHB / HWND 零拷贝  
- `docs/15` — dev pin overlay  
- `docs/16`–`18` — 显示、借上游、X11 对照与踩坑  
