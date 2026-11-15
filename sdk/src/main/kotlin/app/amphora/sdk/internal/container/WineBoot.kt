package app.amphora.sdk.internal.container

import app.amphora.sdk.engine.InstalledRuntime
import app.amphora.sdk.internal.AmphoraPaths
import app.amphora.sdk.internal.engine.engineEnv
import app.amphora.sdk.internal.session.SessionSpec
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Has Wine fill an empty directory with a complete prefix. */
internal fun interface PrefixInitializer {
    /** Returns once the prefix is fully written (registry included); throws [IOException] if Wine failed. */
    suspend fun initialize(prefix: File, runtime: InstalledRuntime, log: File)
}

/**
 * `wineboot --init` without a display: no driver is loaded, so this runs in
 * the app process, outside any session. The registry is written when the
 * wineserver exits, hence the `wineserver -w` afterwards.
 */
internal class WineBoot(private val paths: AmphoraPaths) : PrefixInitializer {
    override suspend fun initialize(prefix: File, runtime: InstalledRuntime, log: File) {
        paths.home.mkdirs()
        paths.tmp.mkdirs()
        val env =
            engineEnv(paths, runtime, prefix) +
                mapOf(
                    // Menu entries and file associations mean nothing here.
                    "WINEDLLOVERRIDES" to "winemenubuilder.exe=d",
                    "WINEDEBUG" to "fixme-all",
                )
        try {
            val boot = run(listOf(SessionSpec.LINKER, runtime.loader.path, "wineboot.exe", "--init"), prefix, env, log)
            val code = await(boot, BOOT_TIMEOUT_MS, "wineboot")
            if (code != 0) throw IOException("wineboot 退出码 $code")
            val wait = run(listOf(SessionSpec.LINKER, runtime.server.path, "-w"), prefix, env, log)
            await(wait, SERVER_TIMEOUT_MS, "wineserver")
        } catch (e: Throwable) {
            // Whatever is still running would keep writing into a directory that is about to be deleted.
            runCatching {
                run(listOf(SessionSpec.LINKER, runtime.server.path, "-k"), prefix, env, log)
                    .waitFor(KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
            throw e
        }
        if (!File(prefix, "system.reg").isFile) throw IOException("wineboot 没有写出注册表")
    }

    private fun run(command: List<String>, prefix: File, env: Map<String, String>, log: File): Process =
        ProcessBuilder(command)
            .directory(prefix)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .apply { environment().putAll(env) }
            .start()

    /** Waits for [process], giving up when the coroutine is cancelled or [timeoutMs] pass. */
    private suspend fun await(process: Process, timeoutMs: Long, what: String): Int {
        val context = currentCoroutineContext()
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        try {
            while (!process.waitFor(POLL_MS, TimeUnit.MILLISECONDS)) {
                context.ensureActive()
                if (System.nanoTime() > deadline) throw IOException("$what 在 ${timeoutMs / 1000} 秒内没有结束")
            }
            return process.exitValue()
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private companion object {
        const val POLL_MS = 200L
        const val BOOT_TIMEOUT_MS = 300_000L
        const val SERVER_TIMEOUT_MS = 60_000L
        const val KILL_TIMEOUT_MS = 5_000L
    }
}
