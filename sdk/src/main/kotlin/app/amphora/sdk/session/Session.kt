package app.amphora.sdk.session

import app.amphora.sdk.library.BuiltinTool
import app.amphora.sdk.library.Game
import app.amphora.sdk.settings.EffectiveConfig
import java.io.File
import kotlinx.serialization.Serializable

/** What a session runs: a game from the library, or a program that ships with Wine. */
public sealed interface LaunchTarget {
    public val title: String

    public data class Play(val game: Game) : LaunchTarget {
        override val title: String get() = game.title
    }

    public data class Tool(val tool: BuiltinTool) : LaunchTarget {
        override val title: String get() = tool.title
    }
}

@Serializable
public enum class SessionPhase(public val label: String) {
    /** The session process is up; Wine has not shown its desktop yet. */
    Starting("启动中"),
    Running("运行中"),
    Ending("正在结束"),
}

/** What a launch is doing before the session process takes over. */
public enum class LaunchStep(
    public val label: String,
    /** Takes long enough to be worth explaining. */
    public val slow: Boolean = false,
) {
    Planning("准备启动"),

    /** Wine initializes the template prefix: once per installed runtime. */
    Template("初始化 Windows 环境", slow = true),

    /** A prefix is made from the template. */
    Prefix("建立前缀"),
    Starting("启动会话"),
}

/** Where sessions are. There is at most one: Wine's driver binds one socket per device. */
public sealed interface SessionState {
    /** No session. [last] is the most recent one that ended. */
    public data class Idle(val last: EndedSession?) : SessionState

    /** Between "launch" and the session process reporting in; [step] says what is being done. */
    public data class Launching(val title: String, val step: LaunchStep = LaunchStep.Planning) : SessionState

    public data class Live(val session: LiveSession) : SessionState
}

public data class LiveSession(
    val id: String,
    val title: String,
    val gameId: String?,
    val pid: Int,
    val startedAt: Long,
    val phase: SessionPhase,
    /** Wine desktop size, "1280x720", once it exists. */
    val desktop: String?,
    val logFile: File,
)

public data class EndedSession(
    val id: String,
    val title: String,
    val gameId: String?,
    val startedAt: Long,
    val endedAt: Long,
    /** False when Wine never showed its desktop: the start failed. */
    val reachedDesktop: Boolean,
    val logFile: File,
) {
    val seconds: Long get() = ((endedAt - startedAt) / 1000).coerceAtLeast(0)
}

public sealed interface LaunchResult {
    public data class Started(val sessionId: String, val warnings: List<String>) : LaunchResult

    /** Another session is running; end it first. */
    public data class Busy(val session: LiveSession) : LaunchResult

    public data object RuntimeNotReady : LaunchResult

    public data class Failed(val message: String) : LaunchResult
}

/** What [SessionController.launch] would run, without running it. */
public data class LaunchPreview(
    val config: EffectiveConfig,
    /** The command line, one argument per element. */
    val argv: List<String>,
    val workingDir: String,
    val warnings: List<String>,
)
