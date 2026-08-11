# Android 插帧技术调研

> 最后更新：2026-08-11
> 状态：研究结论，不代表 Amphora 已实现或承诺发布插帧。

## 1. 结论

插帧对 Amphora 有技术价值，但当前不应直接进入生产：

- 它提高的是**显示流畅度**，不会提高游戏逻辑帧率；GPU 余量不足时反而会降低真实帧率。
- GameHub 的 `libGameScopeVK.so` 是闭源 Vulkan ICD 包装层，不能作为可维护依赖。
- WinNative 的原生方案仍在开放 PR 中；其最新公开状态默认使用经典块匹配，CNN 路径仍是
  实验开关，不能当作成熟上游。
- Framegen WebGPU 的小模型和拆分推理结构值得验证，但现有权重仅允许非商业研究/个人
  使用，而且公开性能来自桌面 GPU。
- Amphora 若继续，应先做独立实验分支和设备基准，不应先做设置 UI 或发布资产。

推荐顺序：

1. 在现有 Vulkan compositor 中验证经典双向光流 + warp，建立正确性、时延和功耗基线。
2. 移植一个许可清晰的小型 RIFE/IFRNet 类 student 到 Vulkan compute，与经典路径对照。
3. 只有在 720p 的多代 Adreno 实机上满足门槛后，才讨论产品集成。

## 2. GameHub `libGameScopeVK.so`

### 2.1 已确认

对 GameHub 6.1.2 的 pcengine/imagefs 样本复核得到：

- `libGameScopeVK.so` 是约 2.2 MB 的 AArch64 Vulkan ICD 包装层；ICD JSON 把
  `library_path` 指向 app 私有 imagefs 中的该库。
- 库接管 swapchain/present，并在真实帧之间运行 compute pipeline。
- 从库中可提取 54 个有效 SPIR-V compute module；主要 workgroup 为 `16x16`。
- 存在金字塔、相关性/代价体、流估计、warp 和生成阶段，形态接近定制的小型 CNN
  光流/插值管线。
- 参数通过一个 10 字节 mmap 控制块传递，包含启用、倍率、模型/质量和流尺度等紧凑
  字段。
- 样本没有暴露 TensorFlow Lite、ONNX Runtime、ncnn、QNN 或 NNAPI 依赖，也没有
  `VK_NV_optical_flow` 依赖证据。当前实现应描述为**自带 SPIR-V compute 推理**，不能
  写成调用硬件 optical-flow 扩展。

### 2.2 不能从二进制证明

逆向只能说明运行图和数据流，不能证明：

- 网络最初参考了哪个论文或开源仓库；
- 权重的训练数据、teacher、损失函数和许可证；
- GameHub 是否拥有未随 APK 发布的训练源码；
- 所有 shader 都是自研，还是经过转换、裁剪或再训练。

因此“完全自研”和“直接来自某开源模型”都属于过度结论。可确认的是：发布样本把模型
算子、权重和调度封装在自己的闭源 Vulkan 组件内。

## 3. WinNative 的两条路线

### 3.1 LSFG 试验

WinNative [PR #355](https://github.com/WinNative-Emu/WinNative/pull/355) 和
[PR #359](https://github.com/WinNative-Emu/WinNative/pull/359) 在 2026-05
试验了 GameNative 的 LSFG Android 集成，随后关闭。公开讨论没有给出足以归因的关闭
原因，所以不能写成“因为性能”或“因为许可证而放弃”。

这条路线并非完整开源模型：

- Android 层、AHardwareBuffer 共享和 Vulkan layer 适配可以开源；
- 高质量生成仍需要用户合法取得的 Lossless Scaling `Lossless.dll`；
- Android 12+ 不能向其他不可调试 app 任意注入 layer，独立 LSFG-Android 因此使用
  MediaProjection + overlay；Amphora 自己控制 guest compositor，才有机会走内部零拷贝。

它适合作为 Vulkan/AHardwareBuffer 集成参考，不适合作为 Amphora 可直接分发的完整
后端。

### 3.2 原生 compositor 方案

WinNative [PR #537](https://github.com/WinNative-Emu/WinNative/pull/537)
直接在 compositor 的 Vulkan present 路径实现插帧，包含：

- 多尺度块匹配、双向 warp、遮挡/置信度处理；
- 独立 worker、history ring、swapchain acquire 和 present pacing；
- 2x/3x/4x、刷新率投票、输出 FPS 统计和可重复的测试场景；
- 一套 CNN compute/FP16 权重实验路径。

该 PR 的演进本身说明了真正的工作量不只在“生成一张中间图”：

- cadence 必须按 vsync 而非 draw 次数推进；
- 真实帧、插值帧和 present deadline 的顺序错误会造成时间倒退；
- flow image 的读写竞争、swapchain acquire 丢帧和 compositor 驱动不一致都能产生
  明显回归；
- PSNR 可能奖励模糊或复制真实帧，必须结合 ground truth、SSIM、边缘/纹理保持和
  帧序列检查。

PR 中确实包含与 GameHub shader/权重形态相近的 `wnfg_*` SPIR-V 与 FP16 文件，但截至
2026-07-29 的最新公开提交已把**经典块匹配设为默认**，CNN 仅由
`debug.winnative.fgcnn=1` 开启。提交说明明确记录 CNN flow 单位和 warp 几何仍无法从
代码可靠推导。因此它是重要研究样本，不是可以直接合入 Amphora 的稳定组件。

## 4. 开源模型候选

| 路线 | 优点 | 主要问题 | Amphora 位置 |
|---|---|---|---|
| 经典块匹配/光流 | 无模型权重；容易做 ground-truth 验证 | 遮挡、细物体和大运动质量有限 | 第一阶段基线 |
| IFRNet-Small | 小模型，插值结构清晰 | 仍需确认代码与每份权重的许可证；需移动端转换 | 候选 |
| RIFE 小模型 | 生态和预训练权重较多 | “可下载”不等于可商用；模型差异大 | 候选/teacher |
| FastFlowNet/PWC 类 | 光流可复用于 warp 与遮挡判断 | 不是完整 frame synthesis；还需生成器 | 光流候选 |
| FSR 3 Frame Interpolation | 开源且质量路线成熟 | 需要游戏提供 motion vector、depth、UI mask 等 | 不适合通用 Wine 后处理 |
| LSFG-VK | Vulkan 集成已有参考 | 依赖合法取得的闭源 Lossless 模型实现 | 不作为可分发后端 |
| diffusion/大模型 VFI | 上限高 | 算力、显存和时延不符合移动实时预算 | 离线 teacher，不上设备 |

“最新模型”不是这里的首要指标。移动端需要固定分辨率、稳定 p95、低显存、可解释的
降级和许可清晰；这些约束通常比论文指标更严格。

## 5. Framegen WebGPU 审计

调查对象：
[MONZikWasTaken/Framegen](https://github.com/MONZikWasTaken/Framegen)。

### 5.1 有价值的部分

- RIFE-family student 只有约 736k 参数，FP32 权重约 2.9 MB，适合做移动端量级验证。
- 把每对帧只运行一次的 trunk 与每个插值时刻运行的 FiLM/head 拆开，可在 3x/4x 时
  摊薄特征提取成本。
- 输入/输出可以保持 texture resident；WGSL runtime 展示了不用完整 ML framework
  也能执行卷积、warp 和稀疏 refine。
- `shader-f16`、storage buffer 和 indirect dispatch 的使用，对 Vulkan compute
  端口有直接参考价值。

### 5.2 不能直接集成的原因

- 仓库代码是 MIT，但
  [`WEIGHTS_LICENSE.md`](https://github.com/MONZikWasTaken/Framegen/blob/main/WEIGHTS_LICENSE.md)
  明确把现有权重限制为非商业研究/个人使用；代码许可不能覆盖权重。
- 权重由 RIFE-family teacher 蒸馏，作者也明确指出 teacher/数据许可链不够干净。
- 公开的约 2–4 ms 数据来自 RTX 4060 Ti，不是 Adreno；不能外推到 Android。
- 项目在 2026-07 才建立，缺少 Android、多代移动 GPU、热降频和长时间运行验证。
- Amphora 是 native Vulkan compositor。引入 WebView/WebGPU 会增加一套运行时和跨 API
  同步；真正可用的方向是按模型图重写 Vulkan compute，而不是嵌入网页 runtime。

结论是：**允许内部技术验证，不允许携带现有权重发布**。如果算法达标，应使用许可
清晰的数据和 teacher 自训权重，并单独记录模型卡、数据来源和权重许可。

## 6. GPU、NPU 与真实收益

### GPU

插帧至少增加光流/特征提取、warp、合成和额外 present。GPU 已接近满载时，启用插帧
可能降低真实帧率、增加排队和发热；“30→60 输出”只有在生成开销小于原有 GPU 余量时
才成立。

### NPU

Android 没有一个能覆盖所有设备、稳定导入 Vulkan image 且零拷贝同步的统一 NPU
接口。QNN/Hexagon、NNAPI 和厂商驱动的算子、精度、缓存及 fence 能力不同。

可行的长期结构是：

- Vulkan compute 为通用后端；
- 对特定 Snapdragon 设备增加 QNN 插件；
- NPU 只承担 trunk/flow，warp 和 present 仍在 GPU；
- 运行时根据真实帧率、GPU 时间、温度和同步成本决定是否启用。

NPU 不应成为第一阶段依赖，否则会在验证算法前先承担厂商适配成本。

## 7. 训练与数据

初版不要求录制真实游戏：

- Vimeo90K、X4K1000FPS、GoPro 等高帧率视频可产生中间帧 ground truth；
- Sintel、Blender Open Movies 等内容可用于许可清晰的补充，但必须逐项保存许可；
- 大 teacher 可离线生成 flow、occlusion 和中间帧伪标签；
- 小 student 再针对 UI、粒子、镜头平移和低纹理游戏场景微调。

真实游戏画面主要用于 domain fine-tune 和最终回归，不应是唯一训练集。否则容易过拟合
少数游戏，也更难处理数据授权。

资源量级取决于是否从零训练：

- 复现/转换预训练小模型：单张 16–24 GB GPU 足够做验证；
- 蒸馏 1–5M 参数 student：可从单张 24 GB 起步，较完整实验适合多卡；
- 从零训练大 teacher：成本会进入数百到数千 GPU 小时，不是 Amphora 的首选路径。

## 8. Amphora 验证门槛

实验必须同时报告真实游戏帧和输出帧，禁止只显示“生成后 FPS”：

1. 设备至少覆盖 Adreno 6xx、7xx、8xx；先测 480p，再测 720p。
2. 记录 source FPS、output FPS、GPU p50/p95、present interval、功耗、温度和 10 分钟
   降频后的结果。
3. 720p 目标暂定生成 p95 小于 6–8 ms，启用后 source FPS 下降不超过 5%；这是
   go/no-go 门槛，不是当前已达到的数据。
4. 用确定性测试场景生成 `t=0.25/0.5/0.75` ground truth，检查顺序、重复、丢帧、
   ghosting、遮挡和 UI/HUD。
5. GPU 余量不足、FIFO/present 模式不兼容、温度过高或生成 miss deadline 时自动旁路，
   始终优先显示真实帧。

只有经典路径和一个小模型路径都完成相同基准，才能判断神经网络是否真的带来足够收益。
