package app.amphora.core.engine

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Opt-in DXVK / Wine graphics diagnostics for “FPS but black” cases (AIO DX9).
 *
 * Inject via [LaunchSpec.env] (`graphicsDiag=true` nav arg) or merge into the
 * container `envVars`. Does **not** change DXVK DLL pins — observation only.
 *
 * After a repro, pull:
 * - `{filesDir}/dxvk-logs/` (`d3d9.log`, `dxgi.log`, …)
 * - `{filesDir}/wine_stderr.log` (when `WINEDEBUG` ≠ `-all`)
 *
 * How to read a black+FPS DX9 run:
 * 1. **AIO/DXVK HUD visible on black scene** → present path alive; look for
 *    empty draws, wrong RT, or timeline/WSI stalls in `d3d9.log`.
 * 2. **HUD missing but AIO FPS still ticks** → app logic advances without a
 *    visible present (less common for AIO).
 * 3. Log keywords: `error`, `Failed to`, `timeline`, `Present`, `VK_ERROR`.
 */
object GraphicsDiag {
    const val TAG = "GraphicsDiag"
    const val LOG_DIR_NAME = "dxvk-logs"
    const val WINE_STDERR_NAME = "wine_stderr.log"

    /** Env merged last by [WineEngineImpl.buildLaunchEnvVars] when diag is on. */
    fun launchEnv(context: Context): Map<String, String> {
        val logDir = ensureLogDir(context)
        return mapOf(
            // On-screen: confirm present + API (D3D9 vs 11) while scene is black.
            "DXVK_HUD" to "fps,devinfo,api,version,memory,gpuload",
            "DXVK_LOG_LEVEL" to "info",
            "DXVK_LOG_PATH" to logDir.absolutePath,
            // Override the hardcoded WINEDEBUG=-all so ProcessHelper captures wine_stderr.log.
            "WINEDEBUG" to "+err,+seh,warn+d3d",
            // Harmless on stock DXVK; required on binsem builds if we trial those later.
            "DXVK_DISABLE_TIMELINE_SEMAPHORES" to "1",
        )
    }

    fun ensureLogDir(context: Context): File {
        val dir = File(context.filesDir, LOG_DIR_NAME)
        if (!dir.isDirectory && !dir.mkdirs()) {
            Log.w(TAG, "Failed to create DXVK log dir: ${dir.absolutePath}")
        }
        return dir
    }

    /** Drop DXVK pipeline state cache — corrupt cache can look like black frames. */
    fun clearStateCache(context: Context) {
        val cache = File(context.filesDir, "imagefs/home/xuser/.cache")
        if (!cache.isDirectory) return
        var n = 0
        cache.walkTopDown().filter { it.isFile }.forEach {
            if (it.delete()) n++
        }
        Log.i(TAG, "Cleared $n file(s) under DXVK state cache ${cache.absolutePath}")
    }

    fun logPaths(context: Context): List<File> {
        val filesDir = context.filesDir
        val out = mutableListOf(File(filesDir, WINE_STDERR_NAME))
        val logDir = File(filesDir, LOG_DIR_NAME)
        if (logDir.isDirectory) {
            out += logDir.listFiles()?.sortedBy { it.name }.orEmpty()
        }
        return out
    }
}
