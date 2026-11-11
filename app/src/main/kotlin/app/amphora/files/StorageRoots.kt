package app.amphora.files

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/** The mounted storage volumes, then the sideload directory. */
object StorageRoots {
    fun list(context: Context, sideload: File?): List<StorageRoot> {
        val manager = context.getSystemService(StorageManager::class.java)
        val volumes =
            manager.storageVolumes
                .filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }
                .mapNotNull { volume ->
                    val dir = volume.directory ?: return@mapNotNull null
                    StorageRoot(
                        label = if (volume.isPrimary) "内部存储" else volume.getDescription(context),
                        dir = dir,
                        kind = if (volume.isRemovable) StorageRoot.Kind.Removable else StorageRoot.Kind.Internal,
                    )
                }.sortedByDescending { it.kind == StorageRoot.Kind.Internal }
        return volumes + listOfNotNull(sideload?.let { StorageRoot("侧载目录", it, StorageRoot.Kind.Sideload) })
    }
}
