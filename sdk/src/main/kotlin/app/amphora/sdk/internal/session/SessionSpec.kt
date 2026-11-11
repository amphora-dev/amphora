package app.amphora.sdk.internal.session

import app.amphora.sdk.session.SessionPhase
import app.amphora.sdk.settings.Resolution
import kotlinx.serialization.Serializable

/**
 * Everything the `:wine` process needs to run one session, fully resolved in
 * the app process (settings, paths, environment). The session process does
 * no policy: it loads the driver, starts the command and hosts the windows.
 */
@Serializable
internal data class SessionSpec(
    val id: String,
    val title: String,
    val gameId: String?,
    val runtimeName: String,
    val loader: String,
    val server: String,
    /** Directory holding ntdll.so, win32u.so, wineandroid.so. */
    val unixLibDir: String,
    val prefixDir: String,
    /** Unix working directory of the Wine process. */
    val workingDir: String,
    /** Program and arguments, run inside the desktop by explorer. */
    val command: List<String>,
    val env: Map<String, String>,
    /** The Wine desktop, resolved against the screen when planned. */
    val desktopWidth: Int,
    val desktopHeight: Int,
    val scale: String,
    val dpi: Int,
    val logFile: String,
    val recordFile: String,
    val createdAt: Long,
) {
    val desktop: Resolution get() = Resolution(desktopWidth, desktopHeight)

    /**
     * argv: the loader through the system linker (W^X: app files cannot be
     * execve()d directly), explorer hosting the command in a desktop of the
     * planned size. The size must be explicit: without one, explorer makes an
     * 800x600 virtual desktop and the driver's screen size is ignored.
     */
    fun argv(): List<String> = listOf(
        LINKER,
        loader,
        "c:\\windows\\system32\\explorer.exe",
        "/desktop=shell,${desktopWidth}x$desktopHeight,android",
    ) + command

    companion object {
        const val LINKER = "/system/bin/linker64"
    }
}

/** `sessions/current.json`, written by the `:wine` process while it lives. */
@Serializable
internal data class SessionRecord(
    val spec: SessionSpec,
    val pid: Int,
    val startedAt: Long,
    /** Heartbeat, so the end time is known even if nobody watched the process die. */
    val lastSeen: Long,
    val phase: SessionPhase,
    /** "1280x720" once the desktop exists. */
    val desktop: String? = null,
)
