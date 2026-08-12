package app.amphora.core.engine

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import app.amphora.core.engine.privileged.IPrivilegedCleanupService
import app.amphora.core.engine.privileged.PrivilegedCleanupService
import org.json.JSONObject
import rikka.shizuku.Shizuku

/**
 * Optional read-only host performance bridge.
 *
 * The normal app domain remains the primary source. This bridge fills counters hidden by modern
 * Android procfs/sysfs policy when Shizuku is available, without prompting from inside a game.
 */
class ShizukuPerformanceReader(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile private var remote: IPrivilegedCleanupService? = null
    private var binding = false
    private var closed = false

    private val userServiceArgs =
        Shizuku.UserServiceArgs(ComponentName(appContext, PrivilegedCleanupService::class.java))
            .processNameSuffix("metrics")
            .daemon(false)
            .version(SERVICE_VERSION)

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                synchronized(lock) {
                    if (closed) return
                    remote = IPrivilegedCleanupService.Stub.asInterface(service)
                    binding = false
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                synchronized(lock) {
                    remote = null
                    binding = false
                }
            }
        }

    /** Returns the latest synchronous Binder snapshot, or null while unavailable/binding. */
    fun read(): PrivilegedPerformanceSnapshot? {
        val service = remote
        if (service == null) {
            ensureBound()
            return null
        }
        return try {
            val json = JSONObject(service.readPerformanceSnapshot())
            PrivilegedPerformanceSnapshot(
                cpuStat = json.optionalString("cpuStat"),
                gpuLoad = json.optionalString("gpuLoad"),
                gpuCurrentFrequency = json.optionalString("gpuCurrentFrequency"),
                gpuMaxFrequency = json.optionalString("gpuMaxFrequency"),
                privilegedUid = json.optInt("uid", -1),
            )
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to read Shizuku performance snapshot", error)
            synchronized(lock) {
                remote = null
                binding = false
            }
            null
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            remote = null
            binding = false
        }
        runCatching { Shizuku.unbindUserService(userServiceArgs, connection, true) }
            .onFailure { Log.d(TAG, "Shizuku metrics service was already unbound", it) }
    }

    private fun ensureBound() {
        synchronized(lock) {
            if (closed || binding || remote != null || !isReady()) return
            binding = true
            try {
                Shizuku.bindUserService(userServiceArgs, connection)
            } catch (error: RuntimeException) {
                binding = false
                Log.d(TAG, "Shizuku performance service unavailable", error)
            }
        }
    }

    private fun isReady(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: RuntimeException) {
        false
    }

    private fun JSONObject.optionalString(name: String): String? = optString(name).takeIf(String::isNotBlank)

    private companion object {
        const val TAG = "ShizukuPerformance"
        const val SERVICE_VERSION = 2
    }
}

data class PrivilegedPerformanceSnapshot(
    val cpuStat: String?,
    val gpuLoad: String?,
    val gpuCurrentFrequency: String?,
    val gpuMaxFrequency: String?,
    val privilegedUid: Int,
) {
    val hasRootAccess: Boolean
        get() = privilegedUid == 0
}
