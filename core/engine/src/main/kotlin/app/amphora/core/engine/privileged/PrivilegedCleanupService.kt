package app.amphora.core.engine.privileged

import android.util.Log
import app.amphora.core.engine.SessionProcessCleaner
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * One-shot Shizuku user service.
 *
 * This runs as Shizuku's shell/root identity, outside Amphora's process. Its
 * only operation is an explicit emergency force-stop of Amphora itself; normal
 * Wine cleanup remains an own-UID operation in [SessionProcessCleaner].
 */
class PrivilegedCleanupService : IPrivilegedCleanupService.Stub() {
    override fun scheduleForceStop(packageName: String, delayMillis: Int) {
        require(PACKAGE_NAME.matches(packageName)) { "Invalid package name" }
        val delay = delayMillis.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
        thread(name = "AmphoraEmergencyStop", isDaemon = false) {
            try {
                Thread.sleep(delay.toLong())
                val status =
                    ProcessBuilder("am", "force-stop", packageName)
                        .redirectErrorStream(true)
                        .start()
                        .waitFor()
                Log.i(TAG, "am force-stop $packageName exited with status $status")
            } catch (error: Throwable) {
                Log.e(TAG, "Emergency force-stop failed", error)
            } finally {
                exitProcess(0)
            }
        }
    }

    override fun destroy() {
        exitProcess(0)
    }

    private companion object {
        const val TAG = "PrivilegedCleanup"
        const val MIN_DELAY_MS = 250
        const val MAX_DELAY_MS = 5_000
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_.]*")
    }
}
