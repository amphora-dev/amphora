package app.amphora.core.engine

import android.content.Context
import android.util.Log
import app.amphora.core.common.dispatcher.DispatcherProvider
import app.amphora.core.container.model.Container as AmphoraContainer
import com.winlator.cmod.runtime.compat.box64.Box64Preset
import com.winlator.cmod.runtime.compat.box64.Box64PresetManager
import com.winlator.cmod.runtime.container.Container as WnContainer
import com.winlator.cmod.runtime.container.ContainerManager as WnContainerManager
import com.winlator.cmod.runtime.content.ContentsManager
import com.winlator.cmod.runtime.display.environment.ImageFs
import com.winlator.cmod.runtime.wine.EnvVars
import com.winlator.cmod.runtime.wine.LocaleEnv
import com.winlator.cmod.runtime.wine.WineInfo
import com.winlator.cmod.shared.io.FileUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs a Wine command **headlessly** -- no X server, no Vulkan render surface,
 * no audio -- to exercise the box64 + Wine + Bionic-rootfs + prefix stack on a
 * host **without an Adreno GPU** (e.g. an Android emulator on Apple Silicon).
 *
 * ## Why this works without a GPU
 * The full launch chain ([WineEngineImpl.launch]) only touches the GPU at the
 * final Vulkan present (`vk_renderer` -> `XServerSurfaceView`). The Wine binary
 * itself runs via box64 (x86_64 -> ARM64 dynarec) and needs only the rootfs +
 * prefix + a small env slice. `wine --version` / `wineboot --init` never create
 * a `VkInstance`, so the driver env vars the full path sets
 * (`VK_ICD_FILENAMES` / `GALLIUM_DRIVER=zink`) are irrelevant here and
 * intentionally **omitted** -- this env is the wineboot-essential subset of
 * `GuestProgramLauncherComponent.execGuestProgram` with everything X / Vulkan /
 * audio / fakeinput stripped.
 *
 * ## Why ProcessBuilder, not ProcessHelper
 * [com.winlator.cmod.runtime.system.ProcessHelper.exec] redirects stdout to
 * `/dev/null` under `WINEDEBUG=-all`, so it can't capture the `wine --version`
 * output. This runner uses [ProcessBuilder] directly with file-based output
 * capture (deadlock-free) and a timeout (wineboot can be slow; it must not hang
 * a test). The app process is already a subreaper (`JNI_OnLoad`
 * `PR_SET_CHILD_SUBREAPER`), so short-lived children are reaped.
 *
 * This is both a **production seam** (headless wineboot / prefix warm-up is a
 * real need -- WinNative ships a pre-built prefixPack instead, but a headless
 * Wine exec is the canonical CI prefix-init path) and the backbone of the
 * no-GPU test path (`HeadlessWineBootTest`).
 */
@Singleton
class WineHeadlessRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) {

    private val imageFs: ImageFs = ImageFs.find(context)
    private val contentsManager: ContentsManager = ContentsManager(context)
    private val wnContainerManager: WnContainerManager = WnContainerManager(context)

    /** Result of a headless Wine command. [exitCode] is `-1` on timeout. */
    data class Result(val exitCode: Int, val stdout: String, val timedOut: Boolean)

    /**
     * Exec `box64 wine <[wineArgs]>` against [container]'s prefix, headlessly.
     *
     * @param wineArgs the Wine args, e.g. `"--version"` or `"wineboot --init"`.
     * @param timeoutSec hard timeout; on expiry the process is force-killed and
     *   [Result.timedOut] is true with [Result.exitCode] = -1.
     */
    suspend fun run(
        container: AmphoraContainer,
        wineArgs: String,
        timeoutSec: Long = DEFAULT_TIMEOUT_SEC,
    ): Result = withContext(dispatchers.io) {
        contentsManager.syncContents()
        val wnContainer = resolveWinNativeContainer(container)
        val wineVersion = wnContainer.getWineVersion()
        val wineInfo = WineInfo.fromIdentifier(context, contentsManager, wineVersion)
        imageFs.setWinePath(wineInfo.path)

        val rootDir = imageFs.getRootDir()
        val env = buildHeadlessEnv(rootDir, wnContainer, wineInfo)
        val wineBin = File(wineInfo.path, "bin/wine").absolutePath
        val box64 = File(rootDir, "usr/bin/box64").absolutePath
        // box64 must be executable (mirror GPLC repairRuntimeExecutablePermissions).
        if (File(box64).isFile) FileUtils.chmod(File(box64), 493 /* 0755 octal */)

        val outFile = File.createTempFile("wine-headless", ".log", context.cacheDir)
        val args = splitArgs(wineArgs)
        val command = ArrayList<String>().apply {
            add(box64)
            add(wineBin)
            addAll(args)
        }
        val pb = ProcessBuilder(command).apply {
            directory(rootDir)
            redirectErrorStream(true)
            redirectOutput(ProcessBuilder.Redirect.to(outFile))
            // Keep the inherited Android env (Bionic may want ANDROID_*), overlay the Wine slice.
            environment().putAll(env)
        }

        Log.i(TAG, "headless exec: ${command.joinToString(" ")}  (cwd=${rootDir.path}, timeout=${timeoutSec}s)")
        val box64File = File(box64)
        Log.i(
            TAG,
            "pre-exec box64: exists=${box64File.exists()} isFile=${box64File.isFile}" +
                " len=${if (box64File.isFile) box64File.length() else -1} canExec=${box64File.canExecute()}",
        )
        val process = pb.start()
        val exited = try {
            process.waitFor(timeoutSec, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!exited) process.destroyForcibly()
        val stdout = try {
            outFile.readText()
        } catch (e: Exception) {
            ""
        }
        outFile.delete()
        val exitCode = if (exited) {
            try {
                process.exitValue()
            } catch (e: IllegalThreadStateException) {
                -1
            }
        } else {
            -1
        }
        Log.i(TAG, "headless result: exit=$exitCode timedOut=${!exited} stdoutLen=${stdout.length}")
        Result(exitCode, stdout, !exited)
    }

    /**
     * The wineboot-essential env slice (a subset of GPLC `execGuestProgram`):
     * Wine loads its libs from `LD_LIBRARY_PATH`, finds `wine`/`wineserver` via
     * `PATH`, reads the prefix from `WINEPREFIX`. No `DISPLAY`, no
     * `VK_ICD_FILENAMES`, no `GALLIUM_DRIVER`, no `LD_PRELOAD`, no `ALSA_*`.
     */
    private fun buildHeadlessEnv(
        rootDir: File,
        container: WnContainer,
        wineInfo: WineInfo,
    ): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        env["HOME"] = imageFs.home_path
        env["USER"] = ImageFs.USER
        env["TMPDIR"] = rootDir.path + "/usr/tmp"
        env["PREFIX"] = rootDir.path + "/usr"
        env["LD_LIBRARY_PATH"] = rootDir.path + "/usr/lib:/system/lib64"
        env["PATH"] = wineInfo.path + "/bin:" + rootDir.path + "/usr/bin"
        env["WINEPREFIX"] = imageFs.wineprefix
        env["WINEDEBUG"] = "-all"
        env["LC_ALL"] = LocaleEnv.normalize(LocaleEnv.deriveFromDevice())
        // box64 essentials (mirror GPLC.addBox64EnvVars, minus log/X11GLX frills).
        env["BOX64_DYNAREC"] = "1"
        env["BOX64_NOBANNER"] = "1"
        env["BOX64_NORCFILES"] = "1"
        env["BOX64_RCFILE"] = rootDir.path + "/etc/config.box64rc"
        // Box64Preset.PERFORMANCE dynarec tuning (same as the real launch).
        val presetEnv: EnvVars =
            Box64PresetManager.getEnvVars("box64", context, Box64Preset.PERFORMANCE)
        for (key in presetEnv) env[key] = presetEnv.get(key)
        // CPU affinity (container may pin cores; honor it like the launcher).
        val cpuList = container.getCPUList(true)
        if (!cpuList.isNullOrEmpty()) {
            env["BOX64_CPULIST"] = cpuList
            env["BOX86_CPULIST"] = cpuList
        }
        return env
    }

    /** Bridge amphora Container -> WinNative Container by rootPath (mirror WineEngineImpl). */
    private fun resolveWinNativeContainer(amphora: AmphoraContainer): WnContainer {
        val target = File(amphora.rootPath).absoluteFile
        wnContainerManager.loadContainers()
        return wnContainerManager.getContainers().firstOrNull { it.getRootDir().absoluteFile == target }
            ?: throw IllegalStateException(
                "WinNative container not found at ${amphora.rootPath} " +
                    "(loaded ${wnContainerManager.getContainers().size} container(s))",
            )
    }

    /** Split a Wine arg string into tokens (the test commands need no quoting). */
    private fun splitArgs(args: String): List<String> =
        if (args.isBlank()) emptyList() else args.trim().split("\\s+".toRegex())

    private companion object {
        const val TAG = "WineHeadlessRunner"
        const val DEFAULT_TIMEOUT_SEC = 120L
    }
}
