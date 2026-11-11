package app.amphora.sdk.settings

import app.amphora.sdk.engine.Component
import app.amphora.sdk.settings.SettingType.Toggle.OFF
import app.amphora.sdk.settings.SettingType.Toggle.ON

/**
 * Every graphics and compatibility setting (doc 13), in display order. The
 * catalog is the single source of truth: defaults, docs and the launch
 * mapping are all here.
 */
public object SettingsCatalog {
    public val all: List<SettingSpec> by lazy { build() }

    private val byId: Map<String, SettingSpec> by lazy { all.associateBy { it.id } }

    public operator fun get(id: String): SettingSpec? = byId[id]

    public fun group(group: SettingGroup): List<SettingSpec> = all.filter { it.group == group }

    // Ids the SDK reads directly.
    public const val RESOLUTION: String = "display.resolution"
    public const val RESOLUTION_CUSTOM: String = "display.resolution.custom"
    public const val SCALE: String = "display.scale"
    public const val DPI: String = "display.dpi"
    public const val STEAM_APP_ID: String = "wine.steamAppId"
    public const val ARGS: String = "wine.args"
    public const val ENV: String = "wine.env"
    public const val DLL_OVERRIDES: String = "wine.dllOverrides"
    public const val WINE_LOG: String = "debug.wineLog"
    public const val WINE_LOG_CUSTOM: String = "debug.wineLog.custom"

    private fun build(): List<SettingSpec> = buildList {
        display()
        direct3d()
        opengl()
        translation()
        windows()
        audioInput()
        storage()
        debug()
    }.also { specs -> check(specs.map { it.id }.toSet().size == specs.size) { "duplicate setting id" } }

    private fun MutableList<SettingSpec>.display() {
        val g = SettingGroup.Display
        add(
            SettingSpec(
                id = "display.frameRate",
                group = g,
                level = SettingLevel.Basic,
                type =
                choice(
                    "screen" to "跟随屏幕",
                    "game" to "跟随游戏",
                    "unlimited" to "不限",
                    "30" to "30",
                    "45" to "45",
                    "60" to "60",
                    "90" to "90",
                    "120" to "120",
                ),
                default = "screen",
                scope = SettingScope.Both,
                doc =
                SettingDoc(
                    title = "帧率",
                    summary = "游戏最多渲染多少帧。",
                    rationale =
                    "SurfaceFlinger 只锁存整帧，开不开垂直同步都不撕裂；跟随屏幕不会渲染看不到的帧，省电、少发热、节奏稳。" +
                        "「不限」只用于跑分，多出的帧会被丢掉。",
                    verify = "性能浮层的帧率读数。",
                ),
                targets = listOf("交换链呈现方式与节奏", "DXVK_FRAME_RATE", "按层 setFrameRate"),
                planned = "M3：新交换链（12 §3.1）",
            ),
        )
        add(
            SettingSpec(
                id = RESOLUTION,
                group = g,
                level = SettingLevel.Basic,
                type =
                SettingType.Choice(
                    listOf(
                        ChoiceOption("1280x720", "1280 × 720"),
                        ChoiceOption("1600x900", "1600 × 900"),
                        ChoiceOption("1920x1080", "1920 × 1080"),
                        ChoiceOption("screen", "按屏幕比例", "高 720，宽度按屏幕比例"),
                        ChoiceOption("custom", "自定义"),
                    ),
                ),
                default = "1280x720",
                scope = SettingScope.Both,
                doc =
                SettingDoc(
                    title = "游戏看到的分辨率",
                    summary = "Wine 桌面的大小，也就是游戏能选的最大分辨率。",
                    rationale =
                    "7xx 到 8xx 性能跨度大，720p 是稳妥的起点；放大由宿主缩放完成，不花游戏的 GPU 时间。" +
                        "性能有余的机器按游戏调高。M3 用实测帧率复核。",
                    verify = "游戏内的分辨率列表；会话菜单显示桌面尺寸。",
                ),
                targets = listOf("Wine 桌面尺寸（显示模式列表）"),
                effect = { resolution = it },
            ),
        )
        add(
            SettingSpec(
                id = RESOLUTION_CUSTOM,
                group = g,
                level = SettingLevel.Basic,
                type = SettingType.Text("宽x高，如 1280x800", check = ::checkResolution),
                default = "",
                scope = SettingScope.Both,
                doc =
                SettingDoc(
                    title = "自定义分辨率",
                    summary = "分辨率选「自定义」时使用，格式为 宽x高。",
                    rationale = "宽 640–3840 且为 8 的倍数（避免驱动对齐出问题），高 360–2160。",
                ),
                targets = listOf("Wine 桌面尺寸"),
                visibleWhen = RESOLUTION to "custom",
                effect = { customResolution = it },
            ),
        )
        add(
            SettingSpec(
                id = SCALE,
                group = g,
                level = SettingLevel.Basic,
                type = choice("fit" to "等比（留黑边）", "stretch" to "拉伸", "integer" to "整数倍"),
                default = "fit",
                scope = SettingScope.Both,
                doc =
                SettingDoc(
                    title = "缩放",
                    summary = "游戏画面怎样铺到屏幕上。",
                    rationale = "等比不变形；整数倍适合像素风游戏，画面最锐利但可能留更宽的边；拉伸会变形。",
                ),
                targets = listOf("宿主层几何"),
                effect = { scale = it },
            ),
        )
        add(
            SettingSpec(
                id = "display.hud",
                group = g,
                level = SettingLevel.Basic,
                type = choice("off" to "关", "fps" to "帧率", "detail" to "详细"),
                default = "off",
                scope = SettingScope.Both,
                doc =
                SettingDoc(
                    title = "性能浮层",
                    summary = "在画面上显示帧率或更详细的负载信息。",
                    rationale =
                    "默认不遮挡画面。「帧率」由宿主按真实上屏帧计数，所有图形 API 都有；" +
                        "「详细」再加 CPU、GPU 负载、内存和温度，D3D9–11 另开 DXVK HUD。",
                ),
                targets = listOf("宿主浮层", "DXVK_HUD"),
                planned = "M3：上屏帧统计来自新交换链",
            ),
        )
        add(
            SettingSpec(
                id = DPI,
                group = g,
                level = SettingLevel.Advanced,
                type = choice("96" to "100%", "120" to "125%", "144" to "150%"),
                default = "96",
                scope = SettingScope.Both,
                doc =
                SettingDoc(
                    title = "Windows 缩放（DPI）",
                    summary = "Windows 程序看到的屏幕 DPI。",
                    rationale = "游戏基本不看 DPI；非 96 时不少老程序界面错位。整体放大交给宿主缩放。",
                    verify = "注册表 HKCU\\Control Panel\\Desktop 的 LogPixels。",
                ),
                targets = listOf("注册表 LogPixels（驱动写入）"),
                effect = { dpi = it.toInt() },
            ),
        )
    }

    private fun MutableList<SettingSpec>.direct3d() {
        val g = SettingGroup.Direct3D
        add(
            SettingSpec(
                id = "d3d.d3d11",
                group = g,
                level = SettingLevel.Basic,
                type = choice("dxvk" to "DXVK", "wined3d" to "WineD3D"),
                default = "dxvk",
                scope = SettingScope.Both,
                doc =
                SettingDoc(
                    title = "D3D9 / 10 / 11 实现",
                    summary = "Direct3D 9–11 翻译到 Vulkan 的方式。",
                    rationale = "Proton 的默认，Vulkan 上最快。WineD3D 要经 OpenGL → Zink 两层，只作按游戏的退路。",
                    verify = "DXVK HUD 或日志里的 DXVK 版本行。",
                ),
                targets = listOf("DLL 覆盖 d3d9、d3d10core、d3d11、dxgi"),
                requires = Component.Dxvk,
                effect = {
                    if (it ==
                        "wined3d"
                    ) {
                        listOf("d3d9", "d3d10core", "d3d11", "dxgi").forEach { name -> dll(name, "b") }
                    }
                },
            ),
        )
        add(
            SettingSpec(
                id = "d3d.d3d8",
                group = g,
                level = SettingLevel.Basic,
                type = choice("dxvk" to "DXVK", "wined3d" to "WineD3D"),
                default = "dxvk",
                scope = SettingScope.Both,
                doc =
                SettingDoc(
                    title = "D3D8 实现",
                    summary = "Direct3D 8 的实现。",
                    rationale =
                    "待验证。Proton 默认把 D3D8 交给 WineD3D；我们这里 WineD3D 要多走一层 Zink，旧仓库 AIO dx8 在 DXVK 上通过。" +
                        "M3 用矩阵比两条路后定。",
                ),
                targets = listOf("DLL 覆盖 d3d8"),
                requires = Component.Dxvk,
                effect = { if (it == "wined3d") dll("d3d8", "b") },
            ),
        )
        add(
            SettingSpec(
                id = "d3d.ddraw",
                group = g,
                level = SettingLevel.Basic,
                type =
                SettingType.Choice(
                    listOf(
                        ChoiceOption("builtin", "Wine 内置", "WineD3D → Zink"),
                        ChoiceOption("d7vk", "d7vk", "直接转 Vulkan，仅 32 位", available = false),
                        ChoiceOption("dxwrapper", "DxWrapper", "转 D3D9 → DXVK，仅 32 位", available = false),
                        ChoiceOption("cncddraw", "cnc-ddraw", "2D 老游戏，仅 32 位", available = false),
                    ),
                ),
                default = "builtin",
                scope = SettingScope.Game,
                doc =
                SettingDoc(
                    title = "DirectDraw / D3D1–7 实现",
                    summary = "最老一代 DirectX 的实现。",
                    rationale = "待验证（M3）。Proton 用 Wine 内置；M3 用矩阵比较 Wine 内置和 d7vk，再定默认值和保留哪几个。",
                ),
                targets = listOf("ddraw.dll 链接与 DLL 覆盖"),
                planned = "M3：图形矩阵后定",
            ),
        )
        add(
            SettingSpec(
                id = "d3d.anisotropy",
                group = g,
                level = SettingLevel.Basic,
                type = choice("app" to "游戏决定", "2" to "2×", "4" to "4×", "8" to "8×", "16" to "16×"),
                default = "app",
                scope = SettingScope.Both,
                doc =
                SettingDoc(
                    title = "各向异性过滤",
                    summary = "强制纹理的各向异性过滤倍数。",
                    rationale = "强制高倍数在移动 GPU 上吃带宽；需要时按游戏开。",
                ),
                targets = listOf("dxvk.conf：d3d9.samplerAnisotropy、d3d11.samplerAnisotropy"),
                requires = Component.Dxvk,
            ),
        )
        add(
            SettingSpec(
                id = "d3d.maxFrameLatency",
                group = g,
                level = SettingLevel.Advanced,
                type = choice("auto" to "自动", "1" to "1", "2" to "2", "3" to "3"),
                default = "auto",
                scope = SettingScope.Game,
                doc =
                SettingDoc(
                    title = "最大帧延迟",
                    summary = "CPU 最多领先 GPU 几帧。",
                    rationale = "由 DXVK 按交换链决定；1 降低输入延迟，但可能掉帧。",
                ),
                targets = listOf("dxvk.conf：dxgi.maxFrameLatency、d3d9.maxFrameLatency"),
                requires = Component.Dxvk,
            ),
        )
        add(
            SettingSpec(
                id = "d3d.vendor",
                group = g,
                level = SettingLevel.Advanced,
                type = choice("real" to "真实（Qualcomm Adreno）", "amd" to "AMD", "nvidia" to "NVIDIA"),
                default = "real",
                scope = SettingScope.Game,
                doc =
                SettingDoc(
                    title = "向游戏报告的显卡",
                    summary = "游戏看到的显卡厂商。",
                    rationale =
                    "伪装成 NVIDIA 会让游戏走 NVAPI / DLSS 路径，伪装成 AMD 会走厂商专用路径；只给拒绝未知厂商的游戏按游戏伪装。",
                ),
                targets = listOf("dxvk.conf：dxgi.customVendorId / customDeviceId"),
                requires = Component.Dxvk,
            ),
        )
        add(
            SettingSpec(
                id = "d3d.vram",
                group = g,
                level = SettingLevel.Advanced,
                type = choice("auto" to "自动", "2" to "2 GB", "4" to "4 GB", "6" to "6 GB", "8" to "8 GB"),
                default = "auto",
                scope = SettingScope.Game,
                doc =
                SettingDoc(
                    title = "报告的显存",
                    summary = "游戏看到的显存大小。",
                    rationale =
                    "待验证。统一内存，显存就是系统内存；报太大，游戏按高画质分配，可能被 Android 杀进程。M3 先看 Turnip 在 Y700 上报多少再定。",
                ),
                targets = listOf("dxvk.conf：dxgi.maxDeviceMemory、dxgi.maxSharedMemory"),
                requires = Component.Dxvk,
            ),
        )
        add(
            SettingSpec(
                id = "d3d12.featureLevel",
                group = g,
                level = SettingLevel.Advanced,
                type = choice("auto" to "自动", "11_0" to "11_0", "12_0" to "12_0", "12_1" to "12_1", "12_2" to "12_2"),
                default = "auto",
                scope = SettingScope.Game,
                doc =
                SettingDoc(
                    title = "D3D12 特性级别",
                    summary = "vkd3d-proton 向游戏报告的特性级别。",
                    rationale = "「自动」真的不设，由 vkd3d-proton 按设备能力报告；个别游戏在高级别下出错时按游戏降（Proton 也这样做）。",
                ),
                targets = listOf("VKD3D_FEATURE_LEVEL"),
                requires = Component.Vkd3d,
                effect = { if (it != "auto") env("VKD3D_FEATURE_LEVEL", it) },
            ),
        )
        add(
            SettingSpec(
                id = "d3d12.shaderModel",
                group = g,
                level = SettingLevel.Advanced,
                type = SettingType.Choice(
                    listOf(ChoiceOption("auto", "自动")) + (0..9).map {
                        ChoiceOption("6_$it", "6_$it")
                    },
                ),
                default = "auto",
                scope = SettingScope.Game,
                doc =
                SettingDoc(
                    title = "D3D12 着色器模型",
                    summary = "vkd3d-proton 报告的最高着色器模型。",
                    rationale = "由 vkd3d-proton 按驱动能力决定；强制驱动不支持的版本，游戏可能起不来。",
                ),
                targets = listOf("VKD3D_SHADER_MODEL"),
                requires = Component.Vkd3d,
                effect = { if (it != "auto") env("VKD3D_SHADER_MODEL", it) },
            ),
        )
        add(
            SettingSpec(
                id = "d3d12.raytracing",
                group = g,
                level = SettingLevel.Advanced,
                type = choice("auto" to "自动", "off" to "关", "force" to "强制"),
                default = "auto",
                scope = SettingScope.Game,
                doc =
                SettingDoc(
                    title = "D3D12 光线追踪",
                    summary = "是否向游戏提供 DXR。",
                    rationale = "vkd3d-proton 自己判断驱动是否安全支持 DXR；强制会跳过它的检查。",
                ),
                targets = listOf("VKD3D_CONFIG（nodxr / dxr）"),
                requires = Component.Vkd3d,
                effect = {
                    when (it) {
                        "off" -> env("VKD3D_CONFIG", "nodxr")
                        "force" -> env("VKD3D_CONFIG", "dxr")
                    }
                },
            ),
        )
    }

    private fun MutableList<SettingSpec>.opengl() {
        val g = SettingGroup.OpenGL
        add(
            SettingSpec(
                id = "gl.implementation",
                group = g,
                level = SettingLevel.Basic,
                type = choice("zink" to "Zink"),
                default = "zink",
                scope = SettingScope.Global,
                doc =
                SettingDoc(
                    title = "OpenGL 实现",
                    summary = "OpenGL 怎样跑到 GPU 上。只读。",
                    rationale = "只支持 Adreno + Turnip，Zink 跑在 Turnip 上是唯一的硬件路径。",
                ),
                targets = listOf("Mesa WGL（opengl32）"),
                requires = Component.Mesa,
            ),
        )
        add(
            SettingSpec(
                id = "gl.oldExtensions",
                group = g,
                level = SettingLevel.Advanced,
                type = SettingType.Toggle,
                default = OFF,
                scope = SettingScope.Game,
                doc =
                SettingDoc(
                    title = "旧版扩展字符串",
                    summary = "把扩展列表截到 2003 年以前。",
                    rationale = "只给扩展字符串过长就崩溃的老游戏开（Proton 的 oldglstr）。",
                ),
                targets = listOf("MESA_EXTENSION_MAX_YEAR=2003"),
                requires = Component.Mesa,
                effect = { if (it == ON) env("MESA_EXTENSION_MAX_YEAR", "2003") },
            ),
        )
        add(
            SettingSpec(
                id = "gl.versionOverride",
                group = g,
                level = SettingLevel.Advanced,
                type = SettingType.Text("自动；或如 4.6", check = { v ->
                    if (v.isEmpty() ||
                        Regex("""\d\.\d(COMPAT|FC)?""").matches(v)
                    ) {
                        null
                    } else {
                        "格式如 4.6"
                    }
                }),
                default = "",
                scope = SettingScope.Game,
                doc =
                SettingDoc(
                    title = "报告的 GL 版本",
                    summary = "改写游戏看到的 OpenGL 版本号。",
                    rationale = "只给检查版本号过严的游戏改。",
                ),
                targets = listOf("MESA_GL_VERSION_OVERRIDE"),
                requires = Component.Mesa,
                effect = { if (it.isNotEmpty()) env("MESA_GL_VERSION_OVERRIDE", it) },
            ),
        )
    }

    private fun MutableList<SettingSpec>.translation() {
        val g = SettingGroup.Translation
        fun fexToggle(
            id: String,
            key: String,
            level: SettingLevel,
            default: String,
            scope: SettingScope,
            doc: SettingDoc,
        ) = SettingSpec(
            id = id,
            group = g,
            level = level,
            type = SettingType.Toggle,
            default = default,
            scope = scope,
            doc = doc,
            targets = listOf("FEX $key"),
            requires = Component.Fex,
            effect = { env("FEX_${key.uppercase()}", if (it == ON) "1" else "0") },
        )
        add(
            fexToggle(
                "fex.tso",
                "TSOEnabled",
                SettingLevel.Basic,
                ON,
                SettingScope.Both,
                SettingDoc(
                    title = "x86 内存序模拟（TSO）",
                    summary = "在 ARM 上模拟 x86 的强内存序。",
                    rationale = "和 Valve 一致。关掉更快，但多线程游戏可能随机崩溃或数据错乱；只对确认没问题的游戏关。",
                ),
            ),
        )
        add(
            fexToggle(
                "fex.multiblock",
                "Multiblock",
                SettingLevel.Basic,
                ON,
                SettingScope.Both,
                SettingDoc(
                    title = "多块编译（Multiblock）",
                    summary = "一次翻译更大的代码块。",
                    rationale = "和 Valve 一致，运行更快；个别游戏卡顿或出错时关。",
                ),
            ),
        )
        add(
            fexToggle(
                "fex.x87ReducedPrecision",
                "X87ReducedPrecision",
                SettingLevel.Advanced,
                ON,
                SettingScope.Game,
                SettingDoc(
                    title = "x87 降精度",
                    summary = "80 位浮点改用 64 位计算。",
                    rationale = "和 Valve 一致，快很多；依赖精确 x87 的程序按游戏关（Proton 对《巫师 3》安装程序就是这样处理的）。",
                ),
            ),
        )
        for ((key, default) in listOf(
            "VectorTSOEnabled" to OFF,
            "MemcpySetTSOEnabled" to OFF,
            "HalfBarrierTSOEnabled" to ON,
        )) {
            add(
                fexToggle(
                    "fex.${key.replaceFirstChar { it.lowercase() }}",
                    key,
                    SettingLevel.Developer,
                    default,
                    SettingScope.Game,
                    SettingDoc(title = key, summary = "TSO 细项，只用于排查。", rationale = "照 Valve 的全局默认。"),
                ),
            )
        }
    }

    private fun MutableList<SettingSpec>.windows() {
        val g = SettingGroup.Windows
        add(
            SettingSpec(
                id = STEAM_APP_ID,
                group = g,
                level = SettingLevel.Basic,
                type =
                SettingType.Text(
                    "自动（steam_appid.txt）",
                    check = { v ->
                        if (v.isEmpty() || v == "none" ||
                            v.all(Char::isDigit) && v.length <= 10
                        ) {
                            null
                        } else {
                            "填数字，或 none"
                        }
                    },
                ),
                default = "",
                scope = SettingScope.Game,
                doc =
                SettingDoc(
                    title = "游戏身份（Steam AppID）",
                    summary = "告诉 Wine、DXVK、vkd3d-proton 这是哪个游戏。留空自动识别，none 表示不设。",
                    rationale = "打开组件里按 SteamGameId 的修正，以及我们的按游戏修正。游戏目录有 steam_appid.txt 时自动使用。",
                    verify = "生效配置里的 SteamGameId。",
                ),
                targets = listOf("SteamGameId", "SteamAppId"),
                effect = { steamAppId = it },
            ),
        )
        add(
            SettingSpec(
                id = "wine.windowsVersion",
                group = g,
                level = SettingLevel.Basic,
                type = choice("win10" to "Windows 10", "win7" to "Windows 7", "winxp" to "Windows XP"),
                default = "win10",
                scope = SettingScope.Game,
                doc =
                SettingDoc(
                    title = "Windows 版本",
                    summary = "程序看到的 Windows 版本。",
                    rationale = "和 Proton 一致；老游戏个别需要 XP / 7。",
                ),
                targets = listOf("前缀注册表"),
                planned = "启动前写前缀注册表（M1 收尾）",
            ),
        )
        add(
            SettingSpec(
                id = "wine.locale",
                group = g,
                level = SettingLevel.Basic,
                type =
                choice(
                    "auto" to "自动",
                    "ja_JP" to "日语",
                    "zh_CN" to "简体中文",
                    "zh_TW" to "繁体中文",
                    "en_US" to "英语",
                ),
                default = "auto",
                scope = SettingScope.Both,
                doc =
                SettingDoc(
                    title = "Windows 语言（区域）",
                    summary = "决定老游戏 ANSI 文本的代码页和字体替换。",
                    rationale =
                    "日文、中文老游戏乱码多半是它。自动：跟随系统语言，不在列表里的用英语。" +
                        "Bionic 的 setlocale 总是返回 C.UTF-8，代码页还要写进注册表（计划中）。",
                ),
                targets = listOf("LC_ALL", "LANG", "注册表 ACP / OEMCP / LCID（计划中）"),
                effect = {
                    val locale = if (it == "auto") systemLocale() else it
                    env("LC_ALL", "$locale.UTF-8")
                    env("LANG", "$locale.UTF-8")
                },
            ),
        )
        add(
            SettingSpec(
                id = "wine.components",
                group = g,
                level = SettingLevel.Advanced,
                type = choice("builtin" to "全部内置"),
                default = "builtin",
                scope = SettingScope.Game,
                doc =
                SettingDoc(
                    title = "Windows 组件",
                    summary =
                    "Direct3D 辅助库、DirectSound、DirectMusic、DirectShow、DirectPlay、XAudio、" +
                        "DirectInput 8、VC++ 2010 用内置还是原生。",
                    rationale = "待验证。和 Proton 一致用 Wine 自己的实现，游戏需要原生运行库时由它的安装程序装。M3 用游戏验证后定。",
                ),
                targets = listOf("前缀里的原生 DLL", "DllOverrides"),
                planned = "M3：按组件逐个验证",
            ),
        )
        add(
            SettingSpec(
                id = "wine.sync",
                group = g,
                level = SettingLevel.Advanced,
                type = choice("auto" to "自动", "wineserver" to "wineserver"),
                default = "auto",
                scope = SettingScope.Both,
                doc =
                SettingDoc(
                    title = "同步方式",
                    summary = "线程同步原语由谁实现。",
                    rationale =
                    "M1 实测：内核没有 /dev/ntsync，futex_waitv 被 Android app 的 seccomp 杀掉，" +
                        "ntsync 和 fsync 都用不了，现在只有 wineserver 同步。" +
                        "可选的是 esync；M3 用游戏帧率决定要不要带。",
                ),
                targets = listOf("WINEESYNC"),
                planned = "M3：决定是否带 esync",
            ),
        )
        add(
            SettingSpec(
                id = "wine.largeAddressAware",
                group = g,
                level = SettingLevel.Advanced,
                type = choice("auto" to "自动", "force" to "强制开"),
                default = "auto",
                scope = SettingScope.Game,
                doc =
                SettingDoc(
                    title = "大地址感知",
                    summary = "让 32 位程序使用 4 GB 地址空间。",
                    rationale = "只给 32 位、内存吃紧会崩的游戏强制开（Proton 的 forcelgadd）。",
                ),
                targets = listOf("WINE_LARGE_ADDRESS_AWARE"),
                effect = { if (it == "force") env("WINE_LARGE_ADDRESS_AWARE", "1") },
            ),
        )
        for ((id, title, variable, proton) in listOf(
            Quad("wine.heapDelayFree", "堆：延迟释放", "WINE_HEAP_DELAY_FREE", "heapdelayfree"),
            Quad("wine.heapZeroMemory", "堆：清零分配", "WINE_HEAP_ZERO_MEMORY", "heapzeromemory"),
            Quad("wine.heapTopDown", "堆：自顶向下", "WINE_HEAP_TOP_DOWN", "heaptopdown"),
        )) {
            add(
                SettingSpec(
                    id = id,
                    group = g,
                    level = SettingLevel.Advanced,
                    type = SettingType.Toggle,
                    default = OFF,
                    scope = SettingScope.Game,
                    doc =
                    SettingDoc(
                        title = title,
                        summary = "堆调试开关。",
                        rationale = "只给有内存使用错误的游戏开（Proton 的 $proton）。",
                    ),
                    targets = listOf(variable),
                    effect = { if (it == ON) env(variable, "1") },
                ),
            )
        }
        add(
            SettingSpec(
                id = "wine.hideD3d11",
                group = g,
                level = SettingLevel.Advanced,
                type = SettingType.Toggle,
                default = OFF,
                scope = SettingScope.Game,
                doc = SettingDoc("隐藏 D3D11", "让游戏以为没有 D3D11，退回 D3D9 / 10 路径。", "Proton 的 nod3d11。"),
                targets = listOf("DLL 覆盖 d3d11="),
                effect = { if (it == ON) dll("d3d11", "") },
            ),
        )
        add(
            SettingSpec(
                id = "wine.hideD3d10",
                group = g,
                level = SettingLevel.Advanced,
                type = SettingType.Toggle,
                default = OFF,
                scope = SettingScope.Game,
                doc = SettingDoc("隐藏 D3D10", "让游戏以为没有 D3D10。", "Proton 的 nod3d10。"),
                targets = listOf("DLL 覆盖 d3d10=、d3d10_1=、d3d10core="),
                effect = { if (it == ON) listOf("d3d10", "d3d10_1", "d3d10core").forEach { dll(it, "") } },
            ),
        )
        add(
            SettingSpec(
                id = DLL_OVERRIDES,
                group = g,
                level = SettingLevel.Advanced,
                type = SettingType.Text("如 d3dx9_43=n,b;xinput1_3=b", check = ::checkDllOverrides),
                default = "",
                scope = SettingScope.Game,
                doc =
                SettingDoc(
                    title = "DLL 覆盖",
                    summary = "额外的 WINEDLLOVERRIDES 条目，和配方生成的合并，同名以这里为准。",
                    rationale = "逃生口；改了会在生效配置页标出。",
                ),
                targets = listOf("WINEDLLOVERRIDES"),
                effect = { parseDllOverrides(it).forEach { (name, mode) -> dll(name, mode) } },
            ),
        )
        add(
            SettingSpec(
                id = ARGS,
                group = g,
                level = SettingLevel.Advanced,
                type = SettingType.Text("如 -windowed -dx11", check = { v ->
                    if (splitArgs(v) !=
                        null
                    ) {
                        null
                    } else {
                        "引号不成对"
                    }
                }),
                default = "",
                scope = SettingScope.Game,
                doc = SettingDoc("启动参数", "追加在程序后面的命令行参数，支持引号。", "逃生口。"),
                targets = listOf("命令行"),
                effect = { args += splitArgs(it).orEmpty() },
            ),
        )
        add(
            SettingSpec(
                id = ENV,
                group = g,
                level = SettingLevel.Advanced,
                type = SettingType.Text("每行一个 NAME=value", multiline = true, check = ::checkEnv),
                default = "",
                scope = SettingScope.Game,
                doc =
                SettingDoc(
                    title = "环境变量",
                    summary = "额外的环境变量。",
                    rationale = "逃生口。不能覆盖引擎生成的变量：被拦下的会在生效配置页标红，要改请用对应的设置。",
                ),
                targets = listOf("环境"),
                effect = { userEnv += parseEnv(it) },
            ),
        )
    }

    private fun MutableList<SettingSpec>.audioInput() {
        val g = SettingGroup.AudioInput
        add(
            SettingSpec(
                id = "audio.buffer",
                group = g,
                level = SettingLevel.Basic,
                type = choice("auto" to "自动", "low" to "低延迟", "stable" to "稳定"),
                default = "auto",
                scope = SettingScope.Both,
                doc =
                SettingDoc(
                    title = "音频缓冲",
                    summary = "音频输出缓冲的大小。",
                    rationale = "低延迟在负载高时容易爆音；默认按设备输出缓冲大小定。",
                ),
                targets = listOf("音频后端参数"),
                requires = Component.Audio,
            ),
        )
        add(
            SettingSpec(
                id = "input.touch",
                group = g,
                level = SettingLevel.Basic,
                type =
                SettingType.Choice(
                    listOf(
                        ChoiceOption("direct", "直接点击", "手指在哪，鼠标就点哪"),
                        ChoiceOption("touchpad", "触控板", available = false),
                        ChoiceOption("rts", "RTS", available = false),
                    ),
                ),
                default = "direct",
                scope = SettingScope.Both,
                doc =
                SettingDoc(
                    title = "触控方式",
                    summary = "触摸怎样变成鼠标操作。",
                    rationale = "直接点击最直观；触控板和 RTS 手势在 M3 之后细化。",
                ),
                targets = listOf("宿主输入"),
            ),
        )
    }

    private fun MutableList<SettingSpec>.storage() {
        val g = SettingGroup.Storage
        add(
            SettingSpec(
                id = "cache.shaderSize",
                group = g,
                level = SettingLevel.Basic,
                type = choice("256" to "256 MB", "512" to "512 MB", "1024" to "1 GB", "2048" to "2 GB"),
                default = "1024",
                scope = SettingScope.Both,
                doc =
                SettingDoc(
                    title = "着色器缓存上限",
                    summary = "每个游戏的着色器缓存最多占多少空间。",
                    rationale = "每个游戏单独目录，互不挤占；Steam 允许每游戏约 5 GB，手机存储更紧。",
                ),
                targets = listOf("MESA_SHADER_CACHE_DIR", "MESA_SHADER_CACHE_MAX_SIZE", "vkd3d-proton 缓存路径"),
                requires = Component.Mesa,
                effect = { env("MESA_SHADER_CACHE_MAX_SIZE", "${it}M") },
            ),
        )
        add(
            SettingSpec(
                id = "cache.fex",
                group = g,
                level = SettingLevel.Basic,
                type = SettingType.Toggle,
                default = ON,
                scope = SettingScope.Both,
                doc =
                SettingDoc(
                    title = "FEX 代码缓存",
                    summary = "把翻译好的 x86 代码存到磁盘。",
                    rationale = "Proton 在 aarch64 上默认开（FEX_DISKCACHE=1）；第二次启动少翻译、少卡顿。代价每游戏约 100 MB。",
                ),
                targets = listOf("FEX_DISKCACHE"),
                requires = Component.Fex,
                effect = { env("FEX_DISKCACHE", if (it == ON) "1" else "0") },
            ),
        )
        add(
            SettingSpec(
                id = "storage.drives",
                group = g,
                level = SettingLevel.Advanced,
                type =
                SettingType.Choice(
                    listOf(
                        ChoiceOption("auto", "自动", "D: 是游戏所在目录"),
                        ChoiceOption("manual", "手动", available = false),
                    ),
                ),
                default = "auto",
                scope = SettingScope.Game,
                doc =
                SettingDoc(
                    title = "盘符映射",
                    summary = "Windows 盘符对应到哪些目录。",
                    rationale = "游戏和存档放哪由你决定，不复制进前缀。C: 是前缀，Z: 是整个文件系统。",
                ),
                targets = listOf("前缀 dosdevices/"),
            ),
        )
    }

    private fun MutableList<SettingSpec>.debug() {
        val g = SettingGroup.Debug
        add(
            SettingSpec(
                id = WINE_LOG,
                group = g,
                level = SettingLevel.Developer,
                type = choice("off" to "关", "err" to "错误", "warn" to "错误和警告", "custom" to "自定义"),
                default = "off",
                scope = SettingScope.Both,
                doc =
                SettingDoc(
                    title = "Wine 日志",
                    summary = "Wine 往会话日志里写多少。",
                    rationale = "日志拖慢运行；排查时按游戏开。「错误和警告」是 err+all,warn+all（旧仓库误写成 +err,+warn）。",
                    verify = "会话日志里的 err: / warn: 行。",
                ),
                targets = listOf("WINEDEBUG"),
                effect = {
                    when (it) {
                        "off" -> env("WINEDEBUG", "-all")
                        "err" -> env("WINEDEBUG", "-all,err+all")
                        "warn" -> env("WINEDEBUG", "-all,err+all,warn+all")
                        "custom" -> env("WINEDEBUG", wineDebugCustom.ifEmpty { "-all" })
                    }
                },
            ),
        )
        add(
            SettingSpec(
                id = WINE_LOG_CUSTOM,
                group = g,
                level = SettingLevel.Developer,
                type = SettingType.Text("如 +relay,+seh", check = { v ->
                    if (v.none { it.isWhitespace() }) null else "不能有空白"
                }),
                default = "",
                scope = SettingScope.Both,
                doc = SettingDoc("WINEDEBUG（自定义）", "Wine 日志选「自定义」时的通道。", "原样写进 WINEDEBUG。"),
                targets = listOf("WINEDEBUG"),
                visibleWhen = WINE_LOG to "custom",
                effect = { wineDebugCustom = it },
            ),
        )
        add(
            SettingSpec(
                id = "debug.shaderCache",
                group = g,
                level = SettingLevel.Developer,
                type = SettingType.Toggle,
                default = ON,
                scope = SettingScope.Game,
                doc = SettingDoc("着色器缓存", "关掉 Mesa 的着色器缓存。", "只在怀疑缓存损坏导致画面错误时关。"),
                targets = listOf("MESA_SHADER_CACHE_DISABLE"),
                requires = Component.Mesa,
                effect = { if (it == OFF) env("MESA_SHADER_CACHE_DISABLE", "true") },
            ),
        )
        add(
            SettingSpec(
                id = "debug.dxvkLog",
                group = g,
                level = SettingLevel.Developer,
                type = choice("none" to "none", "error" to "error", "info" to "info", "debug" to "debug"),
                default = "none",
                scope = SettingScope.Game,
                doc = SettingDoc("DXVK / vkd3d-proton 日志", "两个翻译层的日志级别。", "日志拖慢运行；排查时按游戏开。"),
                targets = listOf("DXVK_LOG_LEVEL", "VKD3D_DEBUG"),
                requires = Component.Dxvk,
                effect = {
                    env("DXVK_LOG_LEVEL", it)
                    env("VKD3D_DEBUG", if (it == "error") "err" else it)
                },
            ),
        )
        add(
            SettingSpec(
                id = "debug.fexLog",
                group = g,
                level = SettingLevel.Developer,
                type = SettingType.Toggle,
                default = OFF,
                scope = SettingScope.Game,
                doc = SettingDoc("FEX 日志", "FEX 往会话日志里写。", "日志拖慢运行；排查时按游戏开。"),
                targets = listOf("FEX SilentLog"),
                requires = Component.Fex,
                effect = { env("FEX_SILENTLOG", if (it == ON) "0" else "1") },
            ),
        )
        add(
            SettingSpec(
                id = "debug.turnip",
                group = g,
                level = SettingLevel.Developer,
                type = SettingType.Text("如 sysmem,nolrz", check = { v ->
                    if (v.none { it.isWhitespace() }) null else "不能有空白"
                }),
                default = "",
                scope = SettingScope.Game,
                doc = SettingDoc("Turnip 调试标志", "传给 Turnip 的 TU_DEBUG。", "只用于排查驱动问题。"),
                targets = listOf("TU_DEBUG"),
                requires = Component.Mesa,
                effect = { if (it.isNotEmpty()) env("TU_DEBUG", it) },
            ),
        )
        add(
            SettingSpec(
                id = "debug.gpuDriver",
                group = g,
                level = SettingLevel.Developer,
                type = choice("turnip" to "Turnip", "system" to "系统驱动（对比用）"),
                default = "turnip",
                scope = SettingScope.Game,
                doc =
                SettingDoc(
                    title = "GPU 驱动",
                    summary = "加载哪个 Vulkan 驱动。",
                    rationale =
                    "Turnip 是唯一支持的驱动。系统驱动只用来对比：旧仓库的性能基线是在它上面测的，画面出错时切过去能判断是不是 Turnip 的问题。",
                ),
                targets = listOf("加载的 Vulkan 库"),
                requires = Component.Mesa,
            ),
        )
    }

    private fun choice(vararg options: Pair<String, String>): SettingType.Choice = SettingType.Choice(
        options.map {
            ChoiceOption(it.first, it.second)
        },
    )

    private data class Quad(val a: String, val b: String, val c: String, val d: String)

    private fun systemLocale(): String {
        val locale = java.util.Locale.getDefault()
        return when {
            locale.language == "ja" -> "ja_JP"
            locale.language == "zh" && (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) -> "zh_TW"
            locale.language == "zh" -> "zh_CN"
            else -> "en_US"
        }
    }
}
