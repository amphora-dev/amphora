package app.amphora.gamesession.wineandroid

import android.content.Context
import android.util.Log
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.container.model.Container as AmphoraContainer
import app.amphora.core.engine.AdvancedRuntimePreferences
import app.amphora.core.engine.buildWineExplorerCommand
import app.amphora.core.engine.buildWineProgramCommand
import app.amphora.core.engine.model.DisplayBackend
import app.amphora.core.engine.model.LaunchTarget
import app.amphora.core.engine.stageExecutable
import com.winlator.cmod.runtime.container.Container as WinNativeContainer
import com.winlator.cmod.runtime.container.ContainerManager as WinNativeContainerManager
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.runtime.display.environment.XEnvironment
import com.winlator.cmod.runtime.display.environment.components.GuestProgramLauncherComponent
import com.winlator.cmod.runtime.system.ProcessHelper
import com.winlator.cmod.runtime.wine.EnvVars
import com.winlator.cmod.runtime.wine.WineInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/**
 * Starts `box64 wine explorer /desktop=shell,WxH …` for [DisplayBackend.WINEANDROID]
 * **without** constructing Java XServer / XServerComponent.
 *
 * Reuses [GuestProgramLauncherComponent] + a bare [XEnvironment] (only the launcher
 * component is added). Env differs from the X11 path:
 *
 * Dropped vs X11 (via GPLC when `AMPHORA_WINEANDROID=1`):
 * - `DISPLAY=unix:…/X0` (Java fake X)
 * - `ANDROID_SYSVSHM_SERVER` (no SysVSharedMemoryComponent)
 * - `GST_PLUGIN_FEATURE_RANK=ximagesink:…` (X sink ranking)
 *
 * Added:
 * - `AMPHORA_WINEANDROID=1`
 * - `AMPHORA_WINEANDROID_SOCK=<filesDir>/wineandroid/host.sock`
 *
 * WCP already ships `wineandroid.drv`. Unix ioctl client connect to
 * `AMPHORA_WINEANDROID_SOCK` is still TODO before HWND/Surface appear on
 * [WineAndroidDesktop] (drv still JNI until a sibling change lands).
 */
@Singleton
class WineAndroidLauncher
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) {
    data class RunningGuest(
        val pid: Int,
        val guestExecutable: String,
        val bridgeSocketPath: File,
        private val launcher: GuestProgramLauncherComponent,
        private val environment: XEnvironment,
    ) {
        fun stop() {
            runCatching { launcher.stop() }
            runCatching { environment.stopEnvironmentComponents() }
        }
    }

    suspend fun start(prepared: WineAndroidSessionBootstrap.Prepared): RunningGuest = withContext(dispatchers.default) {
        require(prepared.spec.displayBackend == DisplayBackend.WINEANDROID)
        ProcessHelper.init(context)

        val imageFs = ImageFs.find(context)
        val contentsManager = ContentsManager(context).also { it.syncContents() }
        val wnContainer = resolveWinNativeContainer(prepared.container)
        val wineVersion = wnContainer.getWineVersion()
        val wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion)
        imageFs.setWinePath(wineInfo.path)

        val screenInfo = "${prepared.spec.displaySize.width}x${prepared.spec.displaySize.height}"
        val guestExecutable =
            when (prepared.spec.target) {
                LaunchTarget.EXPLORER -> buildWineExplorerCommand(screenInfo)
                LaunchTarget.PROGRAM -> {
                    val wineExePath = stageExeIntoPrefix(wnContainer, prepared.spec.exePath)
                    buildWineProgramCommand(screenInfo, wineExePath)
                }
            }

        val envVars = buildWineAndroidEnv(imageFs, prepared)
        val wineProfile = contentsManager.getProfileByEntryName(wnContainer.getWineVersion())
        val launcher = GuestProgramLauncherComponent(contentsManager, wineProfile)
        launcher.setContainer(wnContainer)
        launcher.setWineInfo(wineInfo)
        launcher.setGuestExecutable(guestExecutable)
        launcher.setEnvVars(envVars)
        launcher.setBox64Preset(AdvancedRuntimePreferences.box64Preset(context))
        prepared.spec.workingDirectory?.let { launcher.setWorkingDir(File(it)) }

        // Bare environment: only GPLC. No XServerComponent / SysV / ALSA here.
        val environment = XEnvironment(context, imageFs)
        environment.addComponent(launcher)

        Log.i(
            TAG,
            "starting wineandroid guestExecutable=$guestExecutable " +
                "sock=${prepared.bridgeSocketPath.absolutePath}",
        )
        environment.startEnvironmentComponents()
        val pid = launcher.pid
        check(pid > 0) { "wineandroid guest failed to start (pid=$pid)" }
        Log.i(TAG, "wineandroid guest running pid=$pid")
        RunningGuest(
            pid = pid,
            guestExecutable = guestExecutable,
            bridgeSocketPath = prepared.bridgeSocketPath,
            launcher = launcher,
            environment = environment,
        )
    }

    /**
     * Caller env merged by GPLC. Must include the wineandroid markers so GPLC
     * clears Java-X DISPLAY after its defaults.
     */
    private fun buildWineAndroidEnv(imageFs: ImageFs, prepared: WineAndroidSessionBootstrap.Prepared): EnvVars {
        val envVars = EnvVars()
        envVars.put("LC_ALL", app.amphora.core.engine.WineLocalePreferences.resolve(context))
        envVars.put("WINEPREFIX", imageFs.wineprefix)
        envVars.put("WINEDEBUG", "+err,+android,+module,+loaddll")
        for ((key, value) in prepared.envVars) {
            envVars.put(key, value)
        }
        // Markers / bridge path — must win over any accidental DISPLAY from prefs.
        envVars.put(ENV_WINEANDROID, "1")
        envVars.put(ENV_WINEANDROID_SOCK, prepared.bridgeSocketPath.absolutePath)
        // Ensure we do not carry a stale DISPLAY into merge (GPLC still sets then clears).
        envVars.remove("DISPLAY")
        return envVars
    }

    private fun resolveWinNativeContainer(amphora: AmphoraContainer): WinNativeContainer {
        val manager = WinNativeContainerManager(context)
        val target = File(amphora.rootPath).absoluteFile
        manager.loadContainers()
        return manager.getContainers().firstOrNull { it.getRootDir().absoluteFile == target }
            ?: throw IllegalStateException(
                "WinNative container not found at ${amphora.rootPath}",
            )
    }

    private fun stageExeIntoPrefix(container: WinNativeContainer, exePath: String): String {
        val src = File(exePath)
        val exeName = src.name.ifEmpty { "amphora-game.exe" }
        val driveC = File(container.getRootDir(), ".wine/drive_c").apply { mkdirs() }
        val dest = File(driveC, exeName)
        check(stageExecutable(src, dest)) {
            "Could not stage executable $src into Wine prefix at $dest"
        }
        return "C:\\$exeName"
    }

    companion object {
        private const val TAG = "WineAndroidLauncher"
        const val ENV_WINEANDROID = "AMPHORA_WINEANDROID"
        const val ENV_WINEANDROID_SOCK = "AMPHORA_WINEANDROID_SOCK"
    }
}
