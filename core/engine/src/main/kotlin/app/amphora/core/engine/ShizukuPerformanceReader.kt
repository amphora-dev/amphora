package app.amphora.core.engine

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import app.amphora.core.engine.privileged.IPrivilegedCleanupService
import app.amphora.core.engine.privileged.IPerformanceMetricsService
import app.amphora.core.engine.privileged.PrivilegedCleanupService
import org.json.JSONObject
import rikka.shizuku.Shizuku

/**
 * Cross-process client used by the Wine :session process.
 *
 * ShizukuProvider lives in Amphora's default process, so the session process cannot use Shizuku's
 * static Binder directly. This client binds an app-private bridge in the default process.
 */
class PerformanceMetricsClient(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext

    @Volatile private var remote: IPerformanceMetricsService? = null
    @Volatile private var binding = false
    @Volatile private var bound = false
    @Volatile private var closed = false

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (closed) {
                    runCatching { appContext.unbindService(this) }
                    return
                }
                remote = IPerformanceMetricsService.Stub.asInterface(service)
                binding = false
                bound = true
            }

            override fun onServiceDisconnected(name: ComponentName) {
                remote = null
                binding = false
                bound = false
            }
        }

    fun read(): PrivilegedPerformanceSnapshot? {
        val service = remote
        if (service == null) {
            ensureBound()
            return null
        }
        return runCatching { service.readPerformanceSnapshot()?.toPerformanceSnapshot() }
            .onFailure {
                Log.w(TAG, "Unable to read host performance bridge", it)
                remote = null
            }
            .getOrNull()
    }

    override fun close() {
        closed = true
        remote = null
        if (bound || binding) {
            runCatching { appContext.unbindService(connection) }
        }
        binding = false
        bound = false
    }

    private fun ensureBound() {
        if (closed || binding || bound) return
        binding = true
        val intent = Intent(appContext, PerformanceMetricsBridgeService::class.java)
        if (!appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            binding = false
        }
    }

    private companion object {
        const val TAG = "PerformanceMetricsClient"
    }
}

/** Default-process bridge which owns the Shizuku connection. */
class PerformanceMetricsBridgeService : Service() {
    private lateinit var reader: ShizukuPerformanceReader

    private val binder =
        object : IPerformanceMetricsService.Stub() {
            override fun readPerformanceSnapshot(): String? = reader.read()?.toJson()
        }

    override fun onCreate() {
        super.onCreate()
        reader = ShizukuPerformanceReader(this)
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        reader.close()
        super.onDestroy()
    }
}

/**
 * Optional read-only host performance bridge.
 *
 * The normal app domain remains the primary source. This bridge fills counters hidden by modern
 * Android procfs/sysfs policy when Shizuku is available, without prompting from inside a game.
 */
private class ShizukuPerformanceReader(context: Context) : AutoCloseable {
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

private fun String.toPerformanceSnapshot(): PrivilegedPerformanceSnapshot {
    val json = JSONObject(this)
    return PrivilegedPerformanceSnapshot(
        cpuStat = json.optionalString("cpuStat"),
        gpuLoad = json.optionalString("gpuLoad"),
        gpuCurrentFrequency = json.optionalString("gpuCurrentFrequency"),
        gpuMaxFrequency = json.optionalString("gpuMaxFrequency"),
        privilegedUid = json.optInt("uid", -1),
    )
}

private fun PrivilegedPerformanceSnapshot.toJson(): String = JSONObject()
    .put("cpuStat", cpuStat)
    .put("gpuLoad", gpuLoad)
    .put("gpuCurrentFrequency", gpuCurrentFrequency)
    .put("gpuMaxFrequency", gpuMaxFrequency)
    .put("uid", privilegedUid)
    .toString()

private fun JSONObject.optionalString(name: String): String? = optString(name).takeIf(String::isNotBlank)
