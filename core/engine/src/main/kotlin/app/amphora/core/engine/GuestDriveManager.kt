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
 * [Container.DEFAULT_DRIVES] leaves it invisible forever. Refresh is deliberately
 * read-only: the UI process must not rewrite a whole container document that the
 * isolated session process may be updating. Launch performs the authoritative
 * reconciliation before creating dosdevices symlinks.
 */
@Singleton
class GuestDriveManager
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
) {
    suspend fun refresh(): List<GuestDriveMapping> = withContext(dispatchers.io) {
        val manager = ContainerManager(context)
        manager.loadContainers()
        val containers = manager.containers
        var representativeDrives: String? = null

        containers.forEach { container ->
            val normalized = WineUtils.normalizePersistentDrives(context, container.drives, true)
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

        val mappings = Container.drivesIterator(drives).mapTo(mutableListOf()) { drive ->
            val letter = drive[0].uppercase()
            val file = File(drive[1])
            val path = StoragePathUtils.normalizePath(file.path)
            val volume = runCatching { storageManager?.getStorageVolume(file) }.getOrNull()
            val removable = volume?.isRemovable == true
            val label =
                when {
                    path == downloads -> "Downloads"
                    path == primary -> "Device storage"
                    removable -> volume.getDescription(context).takeIf { it.isNotBlank() } ?: "SD card"
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

        val mappedPaths = mappings.mapTo(mutableSetOf()) { it.path }
        storageManager
            ?.storageVolumes
            .orEmpty()
            .filter {
                !it.isPrimary &&
                    StoragePathUtils.isReadableMountedState(it.state)
            }.forEach { volume ->
                val platformRoot = volume.directory
                val volumeRoot =
                    platformRoot
                        ?: volume.uuid?.takeIf { it.isNotBlank() }?.let { File("/storage/$it") }
                        ?: context
                            .getExternalFilesDirs(null)
                            .filterNotNull()
                            .firstOrNull { dir ->
                                val owner = runCatching { storageManager.getStorageVolume(dir) }.getOrNull()
                                owner != null &&
                                    owner.isPrimary == volume.isPrimary &&
                                    owner.uuid == volume.uuid
                            }?.let(StoragePathUtils::resolveStorageRootFromExternalFilesDir)
                val path = StoragePathUtils.normalizePath(volumeRoot?.path)
                if (path.isBlank() || !mappedPaths.add(path)) return@forEach
                mappings +=
                    GuestDriveMapping(
                        letter = null,
                        label = volume.getDescription(context).takeIf { it.isNotBlank() } ?: "SD card",
                        path = path,
                        removable = true,
                        available = StoragePathUtils.canBrowse(volumeRoot),
                    )
            }
        return mappings
    }
}

data class GuestDriveMapping(
    val letter: String?,
    val label: String,
    val path: String,
    val removable: Boolean,
    val available: Boolean,
)
