package app.amphora.sdk.settings

import app.amphora.sdk.engine.Component

/**
 * One setting, as data (13 §1): the settings screen, the docs and the launch
 * environment are all generated from it, so they cannot disagree. The default
 * lives here and nowhere else (13 §6.2: the old app had two).
 */
public class SettingSpec internal constructor(
    public val id: String,
    public val group: SettingGroup,
    public val level: SettingLevel,
    public val type: SettingType,
    public val default: String,
    public val scope: SettingScope,
    public val doc: SettingDoc,
    /** Where the value lands, for the docs ("落到"). */
    public val targets: List<String>,
    /** A component the runtime must contain for the setting to do anything. */
    public val requires: Component? = null,
    /** Non-null while the SDK side is not built yet. */
    public val planned: String? = null,
    /** Only meaningful while another setting has a given value ("custom" fields). */
    public val visibleWhen: Pair<String, String>? = null,
    /** What the value does to the launch configuration. */
    internal val effect: ConfigBuilder.(String) -> Unit = {},
) {
    override fun toString(): String = "SettingSpec($id)"
}

public enum class SettingGroup(public val title: String, public val tag: String, public val summary: String) {
    Display("显示与帧率", "DISPLAY", "分辨率、缩放、帧率与性能浮层"),
    Direct3D("Direct3D", "D3D", "DXVK（D3D8–11）与 vkd3d-proton（D3D12）"),
    OpenGL("OpenGL", "GL", "Zink 跑在 Turnip 上"),
    Translation("x86 翻译", "FEX", "FEX：TSO、多块编译、x87 精度"),
    Windows("Windows 环境", "WINE", "游戏身份、版本、语言、DLL 与逃生口"),
    AudioInput("音频与输入", "I/O", "音频缓冲与触控方式"),
    Storage("存储与缓存", "STORAGE", "着色器缓存、FEX 代码缓存、盘符"),
    Debug("调试", "DEBUG", "日志与驱动对比"),
}

/** Basic is shown, advanced is folded, developer needs developer mode (13 §1). */
public enum class SettingLevel(public val label: String) {
    Basic("基础"),
    Advanced("高级"),
    Developer("开发者"),
}

public enum class SettingScope(public val global: Boolean, public val game: Boolean, public val label: String) {
    Global(true, false, "全局"),
    Game(false, true, "按游戏"),
    Both(true, true, "全局 / 按游戏"),
}

public sealed interface SettingType {
    /** Null when [value] is acceptable, else why not. */
    public fun validate(value: String): String?

    public data class Choice(val options: List<ChoiceOption>) : SettingType {
        override fun validate(value: String): String? = when (options.firstOrNull { it.key == value }?.available) {
            null -> "不是可选值：$value"
            false -> "该选项尚未提供：$value"
            true -> null
        }

        public fun option(key: String): ChoiceOption? = options.firstOrNull { it.key == key }
    }

    public data object Toggle : SettingType {
        public const val ON: String = "on"
        public const val OFF: String = "off"

        override fun validate(value: String): String? = if (value == ON || value == OFF) null else "应为 on 或 off"
    }

    public class Text internal constructor(
        public val placeholder: String,
        public val multiline: Boolean = false,
        private val check: (String) -> String? = { null },
    ) : SettingType {
        override fun validate(value: String): String? = check(value)
    }
}

public data class ChoiceOption(
    val key: String,
    val label: String,
    val detail: String? = null,
    /** False for options the SDK does not provide yet; shown, not selectable. */
    val available: Boolean = true,
)

/** The words that go with a setting: what it does, and why the default is the default. */
public data class SettingDoc(
    val title: String,
    val summary: String,
    val rationale: String,
    /** How to confirm it took effect (HUD, log line, …). */
    val verify: String? = null,
)
