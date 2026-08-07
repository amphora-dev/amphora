package app.amphora.core.engine

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import app.amphora.core.engine.privileged.IPrivilegedCleanupService
import app.amphora.core.engine.privileged.PrivilegedCleanupService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

enum class ShizukuCleanupStatus {
    UNAVAILABLE,
    PERMISSION_REQUIRED,
    READY,
}

/**
 * Optional last-resort escape hatch.
 *
 * Shizuku does not participate in normal session teardown. When the user
 * explicitly invokes this operation, a one-shot shell/root user service runs
 * `am force-stop app.amphora`, terminating Amphora and every process in its UID.
 */
@Singleton
class ShizukuEmergencyStopper
@Inject
constructor(@ApplicationContext private val context: Context) {
    private val _status = MutableStateFlow(readStatus())
    val status: StateFlow<ShizukuCleanupStatus> = _status.asStateFlow()

    private var remote: IPrivilegedCleanupService? = null
    private var stopPending = false
    private val userServiceArgs =
        Shizuku.UserServiceArgs(ComponentName(context, PrivilegedCleanupService::class.java))
            .processNameSuffix("cleanup")
            .daemon(false)
            .version(1)

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { refreshStatus() }
    private val binderDeadListener =
        Shizuku.OnBinderDeadListener {
            remote = null
            refreshStatus()
        }
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == PERMISSION_REQUEST_CODE) refreshStatus()
        }
    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                remote = IPrivilegedCleanupService.Stub.asInterface(service)
                if (stopPending) scheduleRemoteStop()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                remote = null
            }
        }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionResultListener)
        Log.i(TAG, "Shizuku cleanup status: ${_status.value}")
    }

    fun refreshStatus() {
        val updated = readStatus()
        if (_status.value != updated) Log.i(TAG, "Shizuku cleanup status: ${_status.value} -> $updated")
        _status.value = updated
    }

    fun requestPermission(): Boolean {
        if (!Shizuku.pingBinder()) {
            refreshStatus()
            return false
        }
        if (readStatus() == ShizukuCleanupStatus.READY) return true
        return try {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            true
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to request Shizuku permission", error)
            refreshStatus()
            false
        }
    }

    /**
     * Schedule a force-stop outside this process. Returns after the user service
     * is bound; successful execution intentionally terminates Amphora.
     */
    fun forceStopSelf(): Boolean {
        if (readStatus() != ShizukuCleanupStatus.READY) {
            refreshStatus()
            return false
        }
        stopPending = true
        remote?.let {
            scheduleRemoteStop()
            return true
        }
        return try {
            Shizuku.bindUserService(userServiceArgs, connection)
            true
        } catch (error: RuntimeException) {
            stopPending = false
            Log.e(TAG, "Unable to bind Shizuku cleanup service", error)
            false
        }
    }

    private fun scheduleRemoteStop() {
        val service = remote ?: return
        stopPending = false
        try {
            service.scheduleForceStop(context.packageName, FORCE_STOP_DELAY_MS)
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to schedule Shizuku emergency stop", error)
        }
    }

    private fun readStatus(): ShizukuCleanupStatus {
        if (!Shizuku.pingBinder()) return ShizukuCleanupStatus.UNAVAILABLE
        return try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuCleanupStatus.READY
            } else {
                ShizukuCleanupStatus.PERMISSION_REQUIRED
            }
        } catch (error: RuntimeException) {
            ShizukuCleanupStatus.UNAVAILABLE
        }
    }

    private companion object {
        const val TAG = "ShizukuCleanup"
        const val PERMISSION_REQUEST_CODE = 0xA650
        const val FORCE_STOP_DELAY_MS = 750
    }
}
