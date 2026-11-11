package app.amphora.sdk.library

import java.io.File
import kotlinx.serialization.Serializable

/**
 * A Windows program the user added. [id] is assigned once and keys per-game
 * settings and statistics, so they survive the exe being updated; [exeSha256]
 * is the identity used to match our per-game fixes (13 §2).
 */
@Serializable
public data class Game(
    val id: String,
    val title: String,
    /** Absolute Unix path of the executable. */
    val exePath: String,
    val exeSha256: String,
    val exeSize: Long,
    val arch: PeArch,
    /** From steam_appid.txt next to the exe. */
    val detectedAppId: Long? = null,
    val addedAt: Long,
    val lastPlayedAt: Long? = null,
    val playSeconds: Long = 0,
    /** Prefix the game runs in. Separate from [id] so games can share one (12 §3.3). */
    val prefixId: String = DEFAULT_PREFIX,
) {
    val exeName: String get() = File(exePath).name
    val directory: File get() = File(exePath).parentFile ?: File("/")

    public companion object {
        public const val DEFAULT_PREFIX: String = "default"
    }
}

/** Machine type from the PE header. */
@Serializable
public enum class PeArch(
    public val label: String,
    /** Runs without x86 translation. */
    public val native: Boolean,
) {
    X86("x86", false),
    X64("x64", false),
    Arm64("ARM64", true),
    Arm64ec("ARM64EC", true),
    Unknown("?", false),
}

/**
 * Programs that ship with Wine, runnable before any game is added: the
 * quickest way to see the runtime work and to poke at a prefix.
 */
public enum class BuiltinTool(
    public val title: String,
    public val detail: String,
    /** Command line given to explorer /desktop. */
    public val command: List<String>,
) {
    Console("命令提示符", "cmd，在 Wine 控制台里", listOf("wineconsole.exe")),
    Notepad("记事本", "最简单的 GDI 窗口", listOf("notepad.exe")),
    Explorer("文件管理器", "浏览前缀和盘符", listOf("winefile.exe")),
    Regedit("注册表编辑器", "查看和修改前缀注册表", listOf("regedit.exe")),
    Winecfg("Wine 设置", "winecfg", listOf("winecfg.exe")),
    TaskManager("任务管理器", "看进程和 CPU", listOf("taskmgr.exe")),
    Control("控制面板", "游戏控制器等", listOf("control.exe")),
    Minesweeper("扫雷", "winemine，试试鼠标", listOf("winemine.exe")),
}
