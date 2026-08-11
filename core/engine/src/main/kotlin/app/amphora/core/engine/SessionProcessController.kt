package app.amphora.core.engine

import com.winlator.cmod.runtime.system.ProcessHelper

/** Replaceable boundary for own-UID Wine/Box64 process lifecycle operations. */
internal interface SessionProcessController {
    fun pause()

    fun resume()

    fun terminateAndWait(timeoutMs: Long): List<String>
}

/**
 * Production adapter around the ported static process helper.
 *
 * Amphora itself has permission to signal these processes; elevated privileges
 * are neither required nor more reliable. The Shizuku path is reserved for the
 * emergency operation that force-stops the whole app.
 */
internal object DefaultSessionProcessController : SessionProcessController {
    override fun pause() {
        ProcessHelper.pauseAllWineProcesses()
    }

    override fun resume() {
        ProcessHelper.resumeAllWineProcesses()
    }

    override fun terminateAndWait(timeoutMs: Long): List<String> = ProcessHelper.terminateSessionProcessesAndWait(
        timeoutMs,
        /* forceKillAfterTimeout = */
        true,
    )
}
