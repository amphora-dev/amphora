package app.amphora.core.engine

import com.winlator.cmod.runtime.system.ProcessHelper

/**
 * Own-UID Wine/Box64 process cleanup.
 *
 * Amphora itself has permission to signal these processes; elevated privileges
 * are neither required nor more reliable. The Shizuku path is reserved for the
 * emergency operation that force-stops the whole app.
 */
internal fun interface SessionProcessCleaner {
    fun terminateAndWait(timeoutMs: Long): List<String>
}

internal object DefaultSessionProcessCleaner : SessionProcessCleaner {
    override fun terminateAndWait(timeoutMs: Long): List<String> =
        ProcessHelper.terminateSessionProcessesAndWait(
            timeoutMs,
            /* forceKillAfterTimeout = */ true,
        )
}
