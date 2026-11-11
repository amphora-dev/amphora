package app.amphora.sdk.internal.session

import android.annotation.SuppressLint
import android.content.Context
import android.os.Process
import android.view.View
import androidx.annotation.MainThread
import app.amphora.sdk.internal.AmphoraLog
import app.amphora.sdk.internal.driver.WineDriver
import app.amphora.sdk.internal.window.DriverPort
import app.amphora.sdk.internal.window.ScaleMode
import app.amphora.sdk.internal.window.SurfaceHost
import app.amphora.sdk.internal.window.TopView
import app.amphora.sdk.internal.window.WindowTree
import app.amphora.sdk.session.SessionPhase
import app.amphora.sdk.settings.Resolution
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a session is, as the session process sees it. */
internal enum class Stage(val label: String) {
    Driver("加载 Wine 驱动"),
    Launch("启动 Wine"),
    Desktop("等待桌面"),
    Running("运行中"),
    Ending("正在结束"),
    Failed("启动失败"),
    ;

    val booting: Boolean get() = this == Driver || this == Launch || this == Desktop
}

internal data class SessionUi(
    val title: String,
    val stage: Stage,
    val startedAt: Long,
    /** The prefix has never been initialized: the first start takes a while. */
    val newPrefix: Boolean,
    val desktop: Resolution,
    val scale: ScaleMode,
    val dpi: Int,
    val runtime: String,
    val logFile: File,
    val runningSince: Long? = null,
    val failure: String? = null,
    val menuOpen: Boolean = false,
) {
    val overlayVisible: Boolean get() = stage != Stage.Running || menuOpen
}

/**
 * The session, inside the `:wine` process: loads the driver, starts Wine,
 * builds the window tree and keeps `sessions/current.json` up to date. One
 * per process, outliving activity instances; the activity only lends it a
 * [SurfaceHost] to show the desktop in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal object SessionHost : WindowTree.Events {
    private val scope = MainScope()

    /** Record writes, in order and off the main thread. */
    private val writer = Dispatchers.IO.limitedParallelism(1)

    private val _ui = MutableStateFlow<SessionUi?>(null)
    val ui: StateFlow<SessionUi?> = _ui.asStateFlow()

    var spec: SessionSpec? = null
        private set

    // Built on the application context: the views outlive activity instances.
    @SuppressLint("StaticFieldLeak")
    private var tree: WindowTree? = null
    private var wine: java.lang.Process? = null
    private var records: SessionRecords? = null
    private var log: SessionLog? = null

    // Lent by the activity between attach() and detach().
    @SuppressLint("StaticFieldLeak")
    private var surface: SurfaceHost? = null
    private var onExit: (() -> Unit)? = null
    private var phase = SessionPhase.Starting
    private var desktopSize: String? = null
    private var startedAt = 0L
    private var heartbeat: Job? = null

    val active: Boolean get() = spec != null

    /** The view keys and the soft keyboard go to. */
    val focusTarget: View? get() = tree?.focusTarget

    /** Turns the desktop into a text editor for the soft keyboard, or back. Returns the view to restart input on. */
    @MainThread
    fun softKeyboard(on: Boolean): View? {
        val tree = tree ?: return null
        tree.softKeyboard = on
        return tree.focusTarget
    }

    @MainThread
    fun attach(surface: SurfaceHost, onExit: () -> Unit) {
        this.surface = surface
        this.onExit = onExit
        surface.mode = _ui.value?.scale ?: ScaleMode.Fit
        tree?.top?.let(surface::show)
    }

    @MainThread
    fun detach(surface: SurfaceHost) {
        if (this.surface !== surface) return
        surface.clear()
        this.surface = null
        onExit = null
    }

    /** Starts the session described by [spec]. Once per process. */
    @MainThread
    fun begin(context: Context, spec: SessionSpec, screenWidth: Int, screenHeight: Int) {
        check(this.spec == null) { "session already running in this process" }
        this.spec = spec
        startedAt = System.currentTimeMillis()
        records = SessionRecords(File(spec.recordFile))
        val log = SessionLog(File(spec.logFile)).also { this.log = it }
        val desktop = spec.desktop
        val scale = ScaleMode.of(spec.scale)
        _ui.value =
            SessionUi(
                title = spec.title,
                stage = Stage.Driver,
                startedAt = startedAt,
                newPrefix = !File(spec.prefixDir, "system.reg").isFile,
                desktop = desktop,
                scale = scale,
                dpi = spec.dpi,
                runtime = spec.runtimeName,
                logFile = File(spec.logFile),
            )
        surface?.mode = scale
        writeRecord()
        heartbeat =
            scope.launch {
                while (isActive) {
                    delay(HEARTBEAT_MS)
                    writeRecord()
                }
            }
        log.line("session process pid ${Process.myPid()}, screen ${screenWidth}x$screenHeight, desktop $desktop")

        scope.launch {
            try {
                withContext(Dispatchers.IO) { WineDriver.load(File(spec.unixLibDir)) }
            } catch (e: UnsatisfiedLinkError) {
                return@launch fail("加载 Wine 驱动失败：${e.message}", e)
            } catch (e: SecurityException) {
                return@launch fail("加载 Wine 驱动失败：${e.message}", e)
            }
            val tree =
                WindowTree(
                    context.applicationContext,
                    DriverPort.Native,
                    desktop.width,
                    desktop.height,
                    this@SessionHost,
                )
            this@SessionHost.tree = tree
            WineDriver.start(tree)
            stage(Stage.Launch)

            val process =
                try {
                    withContext(Dispatchers.IO) { startWine(spec) }
                } catch (e: IOException) {
                    return@launch fail("无法启动 Wine：${e.message}", e)
                }
            wine = process
            log.line("wine started")
            if (_ui.value?.stage == Stage.Launch) stage(Stage.Desktop)
            val code = withContext(Dispatchers.IO) { process.waitFor() }
            onWineExited(code)
        }
    }

    private fun startWine(spec: SessionSpec): java.lang.Process = ProcessBuilder(spec.argv())
        .directory(File(spec.workingDir))
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(File(spec.logFile)))
        .apply { environment().putAll(spec.env) }
        .start()

    private suspend fun onWineExited(code: Int) {
        log?.line("wine exited with code $code")
        val stage = _ui.value?.stage
        if (stage != null && stage.booting) {
            fail("Wine 退出了（代码 $code），桌面没有出现。日志里有原因。", null)
            return
        }
        // The driver exits the process when the desktop disconnects; this is the fallback.
        delay(EXIT_GRACE_MS)
        log?.line("still running ${EXIT_GRACE_MS}ms after wine exited; exiting")
        exit()
    }

    // WindowTree.Events

    override fun onDesktop(view: TopView) {
        surface?.show(view)
        spec?.let { WineDriver.wine_config_changed(it.dpi) }
    }

    override fun onDesktopSized(width: Int, height: Int) {
        desktopSize = "${width}x$height"
        if (phase == SessionPhase.Starting) phase = SessionPhase.Running
        log?.line("desktop ${width}x$height")
        _ui.update {
            it?.copy(
                stage = if (it.stage.booting) Stage.Running else it.stage,
                desktop = Resolution(width, height),
                runningSince = it.runningSince ?: System.currentTimeMillis(),
            )
        }
        writeRecord()
    }

    // Menu

    fun toggleMenu() {
        _ui.update { ui ->
            when {
                ui == null -> null
                ui.stage == Stage.Running -> ui.copy(menuOpen = !ui.menuOpen)
                else -> ui
            }
        }
    }

    fun closeMenu() {
        _ui.update { it?.copy(menuOpen = false) }
    }

    /** Ends the session: `wineserver -k`, and the driver takes the process down when the desktop goes. */
    fun end() {
        val spec = spec ?: return exit()
        val current = _ui.value ?: return exit()
        if (current.stage == Stage.Ending) return
        if (current.stage == Stage.Failed && wine == null) return exit()
        stage(Stage.Ending)
        phase = SessionPhase.Ending
        writeRecord()
        log?.line("end requested in the session")
        scope.launch {
            withContext(Dispatchers.IO) { WineServer.kill(spec) }
            delay(EXIT_GRACE_MS)
            log?.line("still running ${EXIT_GRACE_MS}ms after wineserver -k; exiting")
            exit()
        }
    }

    /** Leaves for good: the driver cannot be started again in this process. */
    fun exit() {
        heartbeat?.cancel()
        onExit?.invoke()
        Process.killProcess(Process.myPid())
    }

    private fun fail(message: String, error: Throwable?) {
        AmphoraLog.e(message, error)
        log?.line("failed: $message")
        _ui.update { it?.copy(stage = Stage.Failed, failure = message, menuOpen = false) }
    }

    private fun stage(stage: Stage) {
        _ui.update { it?.copy(stage = stage) }
    }

    private fun writeRecord() {
        val spec = spec ?: return
        val records = records ?: return
        val record = SessionRecord(spec, Process.myPid(), startedAt, System.currentTimeMillis(), phase, desktopSize)
        scope.launch(writer) { records.write(record) }
    }

    private const val HEARTBEAT_MS = 5_000L
    private const val EXIT_GRACE_MS = 4_000L
}
