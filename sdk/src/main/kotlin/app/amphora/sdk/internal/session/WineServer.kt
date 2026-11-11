package app.amphora.sdk.internal.session

import app.amphora.sdk.internal.AmphoraLog
import java.io.File
import java.util.concurrent.TimeUnit

/** Talks to a session's wineserver from outside Wine. */
internal object WineServer {
    /**
     * `wineserver -k`: kills every process of the prefix's session. The
     * explorer goes with them, which makes the driver end the `:wine` process.
     * Returns false if the command could not run or did not finish.
     */
    fun kill(spec: SessionSpec, timeoutSeconds: Long = 5): Boolean = try {
        val process =
            ProcessBuilder(SessionSpec.LINKER, spec.server, "-k")
                .directory(File(spec.prefixDir))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(File(spec.logFile)))
                .apply { environment().putAll(spec.env) }
                .start()
        process.waitFor(timeoutSeconds, TimeUnit.SECONDS).also { done -> if (!done) process.destroyForcibly() }
    } catch (e: Exception) {
        AmphoraLog.w("wineserver -k failed", e)
        false
    }
}
