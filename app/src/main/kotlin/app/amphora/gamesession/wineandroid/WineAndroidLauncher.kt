package app.amphora.gamesession.wineandroid

import android.content.Context
import android.util.Log
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.container.model.Container as AmphoraContainer
import app.amphora.core.engine.AdvancedRuntimePreferences
import app.amphora.core.engine.buildWineExplorerCommand
import app.amphora.core.engine.buildWineProgramCommand
import app.amphora.core.engine.model.LaunchTarget
import app.amphora.core.engine.resolveWineDosPath
import app.amphora.core.engine.stageExecutable
import com.winlator.cmod.runtime.audio.alsaserver.ALSAClient
import com.winlator.cmod.runtime.container.Container as WinNativeContainer
import com.winlator.cmod.runtime.container.ContainerManager as WinNativeContainerManager
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.display.connector.UnixSocketConfig
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.runtime.display.environment.XEnvironment
import com.winlator.cmod.runtime.display.environment.components.ALSAServerComponent
import com.winlator.cmod.runtime.display.environment.components.GuestProgramLauncherComponent
import com.winlator.cmod.runtime.display.environment.components.PulseAudioComponent
import com.winlator.cmod.runtime.system.ProcessHelper
import com.winlator.cmod.runtime.wine.EnvVars
import com.winlator.cmod.runtime.wine.WineInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/**
 * Starts `box64 wine explorer /desktop=shell,WxH …` on the wineandroid host
 * **without** constructing Java XServer / XServerComponent.
 *
 * Env (via GPLC when `AMPHORA_WINEANDROID=1`):
 *
 * - `AMPHORA_WINEANDROID=1` (GPLC gates + WSI preload; **not** a socket path)
 * - `AMPHORA_WSI_DIR=<context.filesDir>/wineandroid` (absolute, no trailing
 *   slash; where libamphora_wsi serves `wsi-%d.sock` / `wsi-sc-%d.sock`)
 * - On redroid/emulator only: `LD_LIBRARY_PATH` prefix for system Vulkan
 * - `LD_PRELOAD` prefix `libamphora_wsi.so`
 * - Audio: `PULSE_SERVER` + [PulseAudioComponent] when the container driver is
 *   PulseAudio (resolved by the preparer), else `ANDROID_ALSA_SERVER` +
 *   [ALSAServerComponent]. Same wiring as the removed X11 path (tag x11-reference).
 *
 * Wine connects to host via fixed abstract `\0\Device\WineAndroid` (upstream).
 * Former `AMPHORA_WINEANDROID_SOCK` / filesystem host.sock is removed.
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
        private val launcher: GuestProgramLauncherComponent,
        private val environment: XEnvironment,
    ) {
        fun stop() {
            runCatching { launcher.stop() }
            runCatching { environment.stopEnvironmentComponents() }
        }
    }

    suspend fun start(prepared: WineAndroidSessionBootstrap.Prepared): RunningGuest = withContext(dispatchers.default) {
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
                    buildWineProgramCommand(screenInfo, wineExePath, prepared.spec.exeArgs)
                }
            }

        val envVars = buildWineAndroidEnv(imageFs, prepared)
        val pulse = applyAudioEnv(imageFs, wnContainer, envVars)
        val wineProfile = contentsManager.getProfileByEntryName(wnContainer.getWineVersion())
        val launcher = GuestProgramLauncherComponent(contentsManager, wineProfile)
        launcher.setContainer(wnContainer)
        launcher.setWineInfo(wineInfo)
        launcher.setGuestExecutable(guestExecutable)
        launcher.setEnvVars(envVars)
        launcher.setBox64Preset(AdvancedRuntimePreferences.box64Preset(context))
        prepared.spec.workingDirectory?.let { launcher.setWorkingDir(File(it)) }

        val environment = XEnvironment(context, imageFs)
        addAudioComponent(environment, imageFs, envVars, pulse)
        environment.addComponent(launcher)

        Log.i(TAG, "starting wineandroid guestExecutable=$guestExecutable ipc=abstract\\\\0\\\\Device\\\\WineAndroid")
        environment.startEnvironmentComponents()
        val pid = launcher.pid
        check(pid > 0) { "wineandroid guest failed to start (pid=$pid)" }
        Log.i(TAG, "wineandroid guest running pid=$pid")
        RunningGuest(
            pid = pid,
            guestExecutable = guestExecutable,
            launcher = launcher,
            environment = environment,
        )
    }

    private fun buildWineAndroidEnv(imageFs: ImageFs, prepared: WineAndroidSessionBootstrap.Prepared): EnvVars {
        val envVars = EnvVars()
        envVars.put("LC_ALL", app.amphora.core.engine.WineLocalePreferences.resolve(context))
        envVars.put("WINEPREFIX", imageFs.wineprefix)
        envVars.put("WINEDEBUG", "+err,+android") // Present smoke: avoid +module/+loaddll flood
        for ((key, value) in prepared.envVars) {
            envVars.put(key, value)
        }
        envVars.put(ENV_WINEANDROID, "1")
        envVars.put(ENV_WSI_DIR, File(context.filesDir, "wineandroid").apply { mkdirs() }.absolutePath)
        // Drop obsolete private sock path if preparer/prefs ever set it.
        envVars.remove(ENV_WINEANDROID_SOCK_OBSOLETE)
        envVars.remove("DISPLAY")
        return envVars
    }

    /** Returns true when PulseAudio is the session backend. */
    private fun applyAudioEnv(imageFs: ImageFs, container: WinNativeContainer, envVars: EnvVars): Boolean {
        val rootPath = imageFs.getRootDir().path
        val pulse = container.getAudioDriver() == AdvancedRuntimePreferences.AUDIO_DRIVER_PULSEAUDIO
        if (pulse) {
            if (!envVars.has("PULSE_LATENCY_MSEC")) {
                envVars.put("PULSE_LATENCY_MSEC", PulseAudioComponent.Options.fromEnvVars(envVars).latencyMillis)
            }
            envVars.put("PULSE_SERVER", rootPath + UnixSocketConfig.PULSE_SERVER_PATH)
        } else {
            envVars.put("ANDROID_ALSA_SERVER", rootPath + UnixSocketConfig.ALSA_SERVER_PATH)
            envVars.put("ANDROID_ASERVER_USE_SHM", "true")
        }
        return pulse
    }

    private fun addAudioComponent(environment: XEnvironment, imageFs: ImageFs, envVars: EnvVars, pulse: Boolean) {
        val rootPath = imageFs.getRootDir().path
        if (pulse) {
            environment.addComponent(
                PulseAudioComponent(
                    UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.PULSE_SERVER_PATH),
                    PulseAudioComponent.Options.fromEnvVars(envVars),
                ),
            )
        } else {
            environment.addComponent(
                ALSAServerComponent(
                    UnixSocketConfig.createSocket(rootPath, UnixSocketConfig.ALSA_SERVER_PATH),
                    ALSAClient.Options.fromEnvVars(envVars),
                ),
            )
        }
        Log.i(TAG, "audio backend=${if (pulse) "pulseaudio" else "alsa"}")
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
        resolveWineDosPath(exePath)?.let { return it }
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

        /** Absolute dir (no trailing slash) where libamphora_wsi serves wsi-%d.sock
         * / wsi-sc-%d.sock; Wine and the preload read it via getenv. */
        const val ENV_WSI_DIR = "AMPHORA_WSI_DIR"

        /** Former filesystem host.sock path — no longer set. */
        const val ENV_WINEANDROID_SOCK_OBSOLETE = "AMPHORA_WINEANDROID_SOCK"
    }
}
