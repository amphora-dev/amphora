package app.amphora.core.engine

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import app.amphora.core.common.dispatcher.DispatcherProvider
import com.winlator.cmod.runtime.container.Container
import com.winlator.cmod.runtime.container.ContainerManager
import com.winlator.cmod.runtime.wine.WineUtils
import com.winlator.cmod.shared.android.StoragePathUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/**
 * Reconciles Android storage volumes with the persistent Wine drive list.
 *
 * A removable volume can appear after the container was created, so relying on
 * [Container.DEFAULT_DRIVES] leaves it invisible forever. Refreshing adds every
 * currently browsable volume and persists the result for all containers; launch
 * performs the same reconciliation again before creating dosdevices symlinks.
 */
@Singleton
class GuestDriveManager
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) {
    suspend fun refresh(): List<GuestDriveMapping> = withContext(dispatchers.io) {
        val manager = ContainerManager(context)
        manager.loadContainers()
        val containers = manager.containers
        var representativeDrives: String? = null

        containers.forEach { container ->
            val normalized = WineUtils.normalizePersistentDrives(context, container.drives, true)
            if (normalized != container.drives) {
                container.drives = normalized
                container.saveData()
            }
            if (representativeDrives == null) representativeDrives = normalized
        }

        val drives =
            representativeDrives
                ?: WineUtils.normalizePersistentDrives(context, Container.DEFAULT_DRIVES, true)
        describe(drives)
    }

    private fun describe(drives: String): List<GuestDriveMapping> {
        val storageManager = context.getSystemService(StorageManager::class.java)
        val downloads =
            StoragePathUtils.normalizePath(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path,
            )
        val primary = StoragePathUtils.normalizePath(Environment.getExternalStorageDirectory().path)

        return Container.drivesIterator(drives).map { drive ->
            val letter = drive[0].uppercase()
            val file = File(drive[1])
            val path = StoragePathUtils.normalizePath(file.path)
            val volume = runCatching { storageManager?.getStorageVolume(file) }.getOrNull()
            val removable = volume?.isRemovable == true
            val label =
                when {
                    path == downloads -> "Downloads"
                    path == primary -> "Device storage"
                    removable -> volume?.getDescription(context)?.takeIf { it.isNotBlank() } ?: "SD card"
                    else -> file.name.takeIf { it.isNotBlank() } ?: "Storage"
                }
            GuestDriveMapping(
                letter = letter,
                label = label,
                path = path,
                removable = removable,
                available = StoragePathUtils.canBrowse(file),
            )
        }
    }
}

data class GuestDriveMapping(
    val letter: String,
    val label: String,
    val path: String,
    val removable: Boolean,
    val available: Boolean,
)
